package com.rhythmatician.lodiffusion.world.noise;

import io.github.lodiffusion.worldgen.QuartNoiseCompute;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Cross-thread dispatch queue that bridges the LOD generation thread
 * (which has no GL context) to the render thread (which owns the GL context
 * and can execute {@link QuartNoiseCompute} dispatches).
 *
 * <h2>Threading model</h2>
 * <ul>
 *   <li><b>Gen thread</b> ({@code LODiffusion-Gen}) calls {@link #enqueue(int, int, int)},
 *       which returns a {@link CompletableFuture}&lt;{@link SectionNoiseData}&gt;.
 *       The gen thread blocks on {@code future.get(timeout)} (see
 *       {@link GpuNoiseRouterSampler}).</li>
 *   <li><b>Render thread</b> calls {@link #tickDrain()} once per client tick
 *       (via {@code ClientTickEvents.END_CLIENT_TICK}).  This pops up to
 *       {@link #MAX_DRAIN_PER_TICK} requests, batches them into a single GPU
 *       dispatch via {@link QuartNoiseCompute#compute(int[][], int)}, and
 *       completes the corresponding futures with the results.</li>
 * </ul>
 *
 * <h2>Lifecycle</h2>
 * <pre>
 *   WorldGenEventHandler.onWorldLoad  → GpuNoiseDispatchQueue.init(quartCompute)
 *   LodiffusionClient END_CLIENT_TICK → GpuNoiseDispatchQueue.tickDrain()
 *   WorldGenEventHandler.onWorldUnload→ GpuNoiseDispatchQueue.shutdown()
 * </pre>
 *
 * @see GpuNoiseRouterSampler
 * @see QuartNoiseCompute
 */
public final class GpuNoiseDispatchQueue {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Maximum requests drained per client tick (20 ticks/sec → 640 sections/sec). */
    public static final int MAX_DRAIN_PER_TICK = 32;

    /** Volatile singleton — set by {@link #init}, cleared by {@link #shutdown}. */
    private static volatile GpuNoiseDispatchQueue INSTANCE;

    // ── Instance state ──────────────────────────────────────────────────

    private final QuartNoiseCompute compute;
    private final ConcurrentLinkedQueue<NoiseRequest> queue = new ConcurrentLinkedQueue<>();
    private final ConcurrentLinkedQueue<ColumnRequest> columnQueue = new ConcurrentLinkedQueue<>();

    // Metrics (thread-safe counters)
    private final AtomicLong totalEnqueued = new AtomicLong();
    private final AtomicLong totalDispatched = new AtomicLong();
    private final AtomicLong totalFailed = new AtomicLong();

    // ── Inner records ───────────────────────────────────────────────────

    /**
     * A pending GPU noise request with its completion handle.
     */
    record NoiseRequest(int sectionX, int sectionY, int sectionZ,
                        CompletableFuture<SectionNoiseData> future) {
    }

    /**
     * A pending GPU column request — all Y-sections for one chunk column.
     * Dispatched as a single batch; resolved to {@code SectionNoiseData[]} ordered
     * by ascending sectionY ({@code minSectionY} first).
     */
    record ColumnRequest(int sectionX, int sectionZ,
                         int minSectionY, int maxSectionY,
                         CompletableFuture<SectionNoiseData[]> future) {
        int sectionCount() { return maxSectionY - minSectionY + 1; }
    }

    // ── Constructor (private — use init()) ──────────────────────────────

    private GpuNoiseDispatchQueue(QuartNoiseCompute compute) {
        this.compute = compute;
    }

    // ── Static lifecycle ────────────────────────────────────────────────

    /**
     * Initialise the singleton dispatch queue.
     * Called on the render thread during world load (after QuartNoiseCompute is ready).
     *
     * @param quartCompute the initialised QuartNoiseCompute instance
     */
    public static void init(QuartNoiseCompute quartCompute) {
        if (quartCompute == null) {
            LOGGER.warn("[GpuNoiseDispatchQueue] init called with null QuartNoiseCompute — skipping");
            return;
        }
        if (!quartCompute.isReady()) {
            LOGGER.warn("[GpuNoiseDispatchQueue] init called but QuartNoiseCompute is not ready — skipping");
            return;
        }
        GpuNoiseDispatchQueue old = INSTANCE;
        if (old != null) {
            LOGGER.warn("[GpuNoiseDispatchQueue] Replacing existing instance — draining {} pending requests",
                    old.queue.size());
            old.cancelAll("Queue replaced by new world load");
        }
        INSTANCE = new GpuNoiseDispatchQueue(quartCompute);
        LOGGER.info("[GpuNoiseDispatchQueue] Initialised (maxDrainPerTick={})", MAX_DRAIN_PER_TICK);
    }

    /**
     * Shut down the singleton dispatch queue.
     * Called on the render thread during world unload.  Any pending futures are
     * completed exceptionally so blocked gen threads unblock immediately.
     */
    public static void shutdown() {
        GpuNoiseDispatchQueue q = INSTANCE;
        INSTANCE = null;
        if (q != null) {
            int remaining = q.queue.size();
            q.cancelAll("World unloading");
            LOGGER.info("[GpuNoiseDispatchQueue] Shutdown — cancelled {} pending, " +
                            "stats: enqueued={}, dispatched={}, failed={}",
                    remaining, q.totalEnqueued.get(), q.totalDispatched.get(), q.totalFailed.get());
        }
    }

    /**
     * Returns the current singleton instance, or {@code null} if not initialised.
     */
    public static GpuNoiseDispatchQueue instance() {
        return INSTANCE;
    }

    // ── Public API (gen thread) ─────────────────────────────────────────

    /**
     * Enqueue a section for GPU noise evaluation.
     * Called from the gen thread.  The returned future will be completed on the
     * render thread when the GPU dispatch finishes.
     *
     * @param sectionX chunk-X coordinate
     * @param sectionY section-Y coordinate
     * @param sectionZ chunk-Z coordinate
     * @return future that resolves to the sampled noise data
     */
    public CompletableFuture<SectionNoiseData> enqueue(int sectionX, int sectionY, int sectionZ) {
        CompletableFuture<SectionNoiseData> future = new CompletableFuture<>();
        queue.add(new NoiseRequest(sectionX, sectionY, sectionZ, future));
        totalEnqueued.incrementAndGet();
        return future;
    }

    /**
     * Enqueue all Y-sections for a single chunk column as one atomic batch.
     *
     * <p>All sections from {@code minSectionY} to {@code maxSectionY} (inclusive)
     * are dispatched in a single {@link QuartNoiseCompute#compute} call on the
     * render thread.  The returned future resolves to a {@code SectionNoiseData[]}
     * in ascending sectionY order (index 0 = {@code minSectionY}).
     *
     * <p>The column counts against the {@link #MAX_DRAIN_PER_TICK} budget — a
     * 24-section overworld column consumes 24 of the 32 slots per tick.
     *
     * @param sectionX   chunk-X coordinate
     * @param sectionZ   chunk-Z coordinate
     * @param minSectionY lowest section-Y to sample (inclusive)
     * @param maxSectionY highest section-Y to sample (inclusive)
     * @return future that resolves to {@code SectionNoiseData[(maxSectionY - minSectionY + 1)]}
     */
    public CompletableFuture<SectionNoiseData[]> enqueueColumn(
            int sectionX, int sectionZ, int minSectionY, int maxSectionY) {
        CompletableFuture<SectionNoiseData[]> future = new CompletableFuture<>();
        columnQueue.add(new ColumnRequest(sectionX, sectionZ, minSectionY, maxSectionY, future));
        totalEnqueued.addAndGet(maxSectionY - minSectionY + 1);
        return future;
    }

    // ── Public API (render thread) ──────────────────────────────────────

    /**
     * Drain up to {@link #MAX_DRAIN_PER_TICK} requests and dispatch them as a
     * single GPU batch.  Must be called on the render thread (GL context required).
     *
     * <p>This is the only method that touches {@link QuartNoiseCompute}, ensuring
     * all GL calls happen on the render thread.
     */
    public void drainAndDispatch() {
        drainAndDispatch(MAX_DRAIN_PER_TICK);
    }

    /**
     * Drain up to {@code maxBatch} requests and dispatch them.
     *
     * <p>Column requests are drained first (each column consumes its section-count
     * of the budget), followed by individual section requests for any remaining
     * capacity.  All gathered work is dispatched as a <b>single</b> GPU call, so
     * a column and several individual sections can share one compute round-trip.
     *
     * @param maxBatch maximum number of <em>sections</em> to include in this batch
     */
    public void drainAndDispatch(int maxBatch) {
        if (queue.isEmpty() && columnQueue.isEmpty()) return;

        // Collect column requests first (each column expands into N section origins)
        java.util.List<ColumnRequest> colBatch = new java.util.ArrayList<>();
        java.util.List<int[]> origins = new java.util.ArrayList<>(maxBatch);
        int remaining = maxBatch;

        while (remaining > 0) {
            ColumnRequest col = columnQueue.peek();
            if (col == null) break;
            int needed = col.sectionCount();
            if (needed > remaining) break;   // won't fit this tick; leave for next
            columnQueue.poll();
            colBatch.add(col);
            for (int sy = col.minSectionY(); sy <= col.maxSectionY(); sy++) {
                origins.add(new int[]{col.sectionX() * 16, sy * 16, col.sectionZ() * 16});
            }
            remaining -= needed;
        }

        // Fill remaining budget with individual section requests
        NoiseRequest[] indivBatch = new NoiseRequest[remaining];
        int indivCount = 0;
        for (int i = 0; i < remaining; i++) {
            NoiseRequest req = queue.poll();
            if (req == null) break;
            indivBatch[indivCount++] = req;
            origins.add(new int[]{req.sectionX() * 16, req.sectionY() * 16, req.sectionZ() * 16});
        }

        int totalCount = origins.size();
        if (totalCount == 0) return;

        try {
            // Build origins array for QuartNoiseCompute
            int[][] originsArr = origins.toArray(new int[0][]);

            // GPU dispatch + readback (all on render thread)
            SectionNoiseData[] results = compute.compute(originsArr, totalCount);

            // Distribute results back to column futures
            int resultIdx = 0;
            for (ColumnRequest col : colBatch) {
                int n = col.sectionCount();
                SectionNoiseData[] colResults = new SectionNoiseData[n];
                System.arraycopy(results, resultIdx, colResults, 0, n);
                col.future().complete(colResults);
                resultIdx += n;
            }

            // Distribute results back to individual section futures
            for (int i = 0; i < indivCount; i++) {
                indivBatch[i].future().complete(results[resultIdx++]);
            }

            totalDispatched.addAndGet(totalCount);

        } catch (Exception e) {
            // Complete all futures exceptionally so gen thread doesn't hang
            LOGGER.error("[GpuNoiseDispatchQueue] GPU dispatch failed for batch of {} — " +
                    "completing futures exceptionally", totalCount, e);
            for (ColumnRequest col : colBatch) {
                col.future().completeExceptionally(e);
            }
            for (int i = 0; i < indivCount; i++) {
                indivBatch[i].future().completeExceptionally(e);
            }
            totalFailed.addAndGet(totalCount);
        }
    }

    // ── Static convenience for tick hook ────────────────────────────────

    /**
     * Called from {@code ClientTickEvents.END_CLIENT_TICK}.
     * No-ops if the queue is not initialised.
     */
    public static void tickDrain() {
        GpuNoiseDispatchQueue q = INSTANCE;
        if (q != null) {
            q.drainAndDispatch();
        }
    }

    // ── Internal helpers ────────────────────────────────────────────────

    /**
     * Cancel all pending requests by completing their futures exceptionally.
     */
    private void cancelAll(String reason) {
        NoiseRequest req;
        int cancelled = 0;
        while ((req = queue.poll()) != null) {
            req.future().completeExceptionally(
                    new IllegalStateException("GPU dispatch queue cancelled: " + reason));
            cancelled++;
        }
        ColumnRequest col;
        while ((col = columnQueue.poll()) != null) {
            col.future().completeExceptionally(
                    new IllegalStateException("GPU dispatch queue cancelled: " + reason));
            cancelled += col.sectionCount();
        }
        if (cancelled > 0) {
            LOGGER.debug("[GpuNoiseDispatchQueue] Cancelled {} pending sections: {}", cancelled, reason);
        }
    }

    // ── Metrics accessors ───────────────────────────────────────────────

    public long getTotalEnqueued()   { return totalEnqueued.get(); }
    public long getTotalDispatched() { return totalDispatched.get(); }
    public long getTotalFailed()     { return totalFailed.get(); }
    public int  getPendingCount()    { return queue.size(); }
}
