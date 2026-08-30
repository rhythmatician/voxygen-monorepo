package com.rhythmatician.voxygen.generation.scheduling;

import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.world.World;

/**
 * Continuous, position-aware chunk scheduler that feeds the 4-stage ONNX
 * pipeline as the player moves through the world.
 *
 * <p>Replaces the old one-shot {@code populateQueue} approach.  Runs on the
 * {@code LODiffusion-Gen} thread and wakes every {@link #TICK_INTERVAL_MS}ms
 * to:
 * <ol>
 *   <li>Read the player's current section position (volatile, written at 20 Hz)</li>
 *   <li>If the player crossed a section boundary: enqueue newly-visible columns,
 *       re-prioritise in-flight tasks, and cancel distant stale work</li>
 *   <li>Track heading direction via exponential moving average for movement-cone
 *       priority</li>
 * </ol>
 *
 * <h3>Movement cone</h3>
 * <p>When the player is moving, sections ahead of the heading direction receive
 * higher priority (lower number).  When stationary, priorities fall back to pure
 * Manhattan distance (360° fill).  This matches the design in PROJECT-OUTLINE § 5.1.
 *
 * <h3>Teleportation handling</h3>
 * <p>If the player moves more than {@link #generationRadius} sections in a single
 * tick (i.e. teleportation), the scheduler clears the queue and
 * {@code generatedSections} set, resets heading, and starts fresh from the new
 * position.
 *
 * <h3>Back-pressure</h3>
 * <p>The scheduler stops enqueuing when the queue exceeds {@link #maxQueueSize}
 * tasks, preventing runaway memory when the player moves faster than inference
 * can keep up.
 */
public final class ChunkScheduler {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkScheduler.class);

    /** How often the scheduler checks for player movement (ms). */
    private static final int TICK_INTERVAL_MS = 100;

    // ── Tuning parameters ───────────────────────────────────────────────

    /** Generation radius in sections (Manhattan distance from player). */
    private final int generationRadius;

    /**
     * Extra margin beyond generationRadius before cancellation.
     * Tasks between radius and radius+margin are kept alive to avoid
     * thrashing when the player oscillates near the boundary.
     */
    private final int cancelMargin;

    /**
     * How strongly to bias toward the player's heading direction.
     * 0.0 = pure Manhattan distance, 1.0 = aggressive forward cone.
     */
    private final float coneStrength;

    /**
     * Maximum number of sections in the queue.  Stop enqueuing new
     * sections when the queue exceeds this to prevent memory blowup.
     */
    private final int maxQueueSize;

    // ── Dependencies ────────────────────────────────────────────────────

    private final LodGenerationQueue queue;
    private final AtomicBoolean stopRequested;
    private final Set<Long> generatedSections;
    private final ColumnContextProvider contextProvider;

    // ── State ───────────────────────────────────────────────────────────

    /** Last known player section coordinates. */
    private int lastCentreX;
    private int lastCentreZ;

    /** Exponential moving average of the heading direction (not normalised). */
    private float headingX;
    private float headingZ;

    /** EMA smoothing factor for heading (0.0–1.0, higher = more responsive). */
    private static final float HEADING_ALPHA = 0.3f;

    /** Minimum heading magnitude to activate directional priority. */
    private static final float HEADING_THRESHOLD = 0.1f;

    /** Base section Y range constants (matching LodGenerationService). */
    private static final int Y_SECTIONS = 16;
    private static final int Y_BASE_SECTION = -4;
    private static final int SURFACE_MARGIN = 1;

    // ── Stats ───────────────────────────────────────────────────────────

    private int totalEnqueued;
    private int totalCancelled;
    private int reprioritiseCount;

    // ── Functional interface for column context ─────────────────────────

    /**
     * Callback to build/cache column conditioning context.
     * This decouples ChunkScheduler from LodGenerationService's
     * internal column context cache.
     */
    @FunctionalInterface
    public interface ColumnContextProvider {
        /**
         * Get or build the column context for the given section column.
         *
         * @param world    the Minecraft world
         * @param sectionX section X coordinate
         * @param sectionZ section Z coordinate
         * @return conditioning context, or null to skip this column
         */
        LodGenerationService.ColumnContext provide(World world, int sectionX, int sectionZ);
    }

    // ── Construction ────────────────────────────────────────────────────

    /**
     * @param queue             the pipeline queue to feed
     * @param stopRequested     shared stop flag from LodGenerationService
     * @param generatedSections shared set of already-generated section keys
     * @param contextProvider   callback to build/cache ColumnContext
     * @param generationRadius  radius in sections
     * @param cancelMargin      extra sections before cancellation
     * @param coneStrength      heading bias strength (0–1)
     * @param maxQueueSize      back-pressure cap
     */
    public ChunkScheduler(LodGenerationQueue queue,
                          AtomicBoolean stopRequested,
                          Set<Long> generatedSections,
                          ColumnContextProvider contextProvider,
                          int generationRadius,
                          int cancelMargin,
                          float coneStrength,
                          int maxQueueSize) {
        this.queue = queue;
        this.stopRequested = stopRequested;
        this.generatedSections = generatedSections;
        this.contextProvider = contextProvider;
        this.generationRadius = generationRadius;
        this.cancelMargin = cancelMargin;
        this.coneStrength = coneStrength;
        this.maxQueueSize = maxQueueSize;
    }

    // ── Main scheduling loop ────────────────────────────────────────────

    /**
     * Run the continuous scheduling loop.  This method blocks until
     * {@code stopRequested} is set.  Call from the main worker thread
     * after stage workers have been started.
     *
     * @param world            the Minecraft world
     * @param playerSectionX   volatile supplier of player X (lambda or method ref)
     * @param playerSectionZ   volatile supplier of player Z
     * @param vanillaChunkCheck returns true if vanilla has a loaded chunk at (sx, sz)
     */
    public void run(World world,
                    java.util.function.IntSupplier playerSectionX,
                    java.util.function.IntSupplier playerSectionZ,
                    java.util.function.BiPredicate<Integer, Integer> vanillaChunkCheck) {

        // Seed initial position
        lastCentreX = playerSectionX.getAsInt();
        lastCentreZ = playerSectionZ.getAsInt();
        headingX = 0f;
        headingZ = 0f;

        // Initial population — fill the full radius around spawn
        int initialEnqueued = enqueueRadius(world, lastCentreX, lastCentreZ,
                generationRadius, vanillaChunkCheck);
        LOGGER.info(String.format(
                "[ChunkScheduler] Initial population: %d sections (radius=%d, centre=%d,%d)",
                initialEnqueued, generationRadius, lastCentreX, lastCentreZ));

        // ── Continuous loop ─────────────────────────────────────────────
        while (!stopRequested.get()) {
            try {
                Thread.sleep(TICK_INTERVAL_MS);
            } catch (InterruptedException e) {
                break;
            }

            int cx = playerSectionX.getAsInt();
            int cz = playerSectionZ.getAsInt();

            if (cx == lastCentreX && cz == lastCentreZ) {
                continue;  // Player hasn't crossed a section boundary
            }

            // ── Detect teleportation ────────────────────────────────────
            int jumpDist = Math.abs(cx - lastCentreX) + Math.abs(cz - lastCentreZ);
            if (jumpDist > generationRadius) {
                handleTeleport(world, cx, cz, vanillaChunkCheck);
                lastCentreX = cx;
                lastCentreZ = cz;
                headingX = 0f;
                headingZ = 0f;
                continue;
            }

            // ── Update heading EMA ──────────────────────────────────────
            float dx = cx - lastCentreX;
            float dz = cz - lastCentreZ;
            headingX = HEADING_ALPHA * dx + (1f - HEADING_ALPHA) * headingX;
            headingZ = HEADING_ALPHA * dz + (1f - HEADING_ALPHA) * headingZ;

            // ── Enqueue newly-visible columns ───────────────────────────
            if (queue.trackedTaskCount() < maxQueueSize) {
                int newEnqueued = enqueueNewColumns(world, cx, cz,
                        lastCentreX, lastCentreZ, vanillaChunkCheck);
                if (newEnqueued > 0) {
                    totalEnqueued += newEnqueued;
                    queue.addTotalEnqueued(newEnqueued);
                }
            }

            // ── Re-prioritise existing tasks ────────────────────────────
            float headingMag = (float) Math.sqrt(headingX * headingX + headingZ * headingZ);
            if (headingMag > HEADING_THRESHOLD && coneStrength > 0f) {
                float nx = headingX / headingMag;
                float nz = headingZ / headingMag;
                queue.reprioritiseDirectional(cx, cz, nx, nz, coneStrength);
            } else {
                queue.reprioritise(cx, cz);
            }
            reprioritiseCount++;

            // ── Cancel distant stale work ───────────────────────────────
            int cancelRadius = generationRadius + cancelMargin;
            int cancelled = queue.cancelBeyondRadius(cx, cz, cancelRadius);
            if (cancelled > 0) {
                totalCancelled += cancelled;
            }

            lastCentreX = cx;
            lastCentreZ = cz;

            // ── Periodic progress logging ───────────────────────────────
            if (reprioritiseCount % 50 == 0) {
                LOGGER.info(String.format(
                        "[ChunkScheduler] centre=(%d,%d) heading=(%.2f,%.2f) " +
                        "tracked=%d queues=[%d|%d|%d|%d] done=%d cancelled=%d",
                        cx, cz, headingX, headingZ,
                        queue.trackedTaskCount(),
                        queue.stageQueueSize(0), queue.stageQueueSize(1),
                        queue.stageQueueSize(2), queue.stageQueueSize(3),
                        queue.completedCount(), totalCancelled));
            }
        }

        LOGGER.info(String.format(
                "[ChunkScheduler] Exiting — enqueued=%d, cancelled=%d, reprioritised=%d times",
                totalEnqueued, totalCancelled, reprioritiseCount));
    }

    // ── Enqueue helpers ─────────────────────────────────────────────────

    /**
     * Enqueue all columns within {@code radius} of the given centre.
     * Skips columns where vanilla has loaded chunks and sections that
     * have already been generated.
     *
     * @return number of sections enqueued
     */
    private int enqueueRadius(World world, int centreX, int centreZ,
                               int radius,
                               java.util.function.BiPredicate<Integer, Integer> vanillaCheck) {
        int enqueued = 0;
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                if (stopRequested.get()) return enqueued;
                int sx = centreX + dx;
                int sz = centreZ + dz;
                enqueued += enqueueColumn(world, sx, sz, centreX, centreZ, vanillaCheck);
            }
        }
        queue.addTotalEnqueued(enqueued);
        totalEnqueued += enqueued;
        return enqueued;
    }

    /**
     * Enqueue columns that are newly visible after the player moved from
     * (oldCX, oldCZ) to (newCX, newCZ).  A column is "newly visible" if
     * it is within {@code generationRadius} of the new centre but was
     * outside the radius of the old centre.
     *
     * <p>This is the incremental counterpart to {@link #enqueueRadius} —
     * it only processes the crescent/ring of new columns, not the full disc.
     *
     * @return number of sections enqueued
     */
    private int enqueueNewColumns(World world, int newCX, int newCZ,
                                   int oldCX, int oldCZ,
                                   java.util.function.BiPredicate<Integer, Integer> vanillaCheck) {
        int enqueued = 0;
        int r = generationRadius;
        for (int dx = -r; dx <= r; dx++) {
            for (int dz = -r; dz <= r; dz++) {
                int sx = newCX + dx;
                int sz = newCZ + dz;
                // Only enqueue if this column was OUTSIDE the old radius
                int oldDist = Math.abs(sx - oldCX) + Math.abs(sz - oldCZ);
                if (oldDist <= r) continue;  // was already covered

                if (stopRequested.get()) return enqueued;
                enqueued += enqueueColumn(world, sx, sz, newCX, newCZ, vanillaCheck);
            }
        }
        return enqueued;
    }

    /**
     * Enqueue all Y sections for a single column (sx, sz).
     *
     * @return number of sections enqueued for this column
     */
    private int enqueueColumn(World world, int sx, int sz,
                               int centreX, int centreZ,
                               java.util.function.BiPredicate<Integer, Integer> vanillaCheck) {
        // Skip columns where vanilla has loaded real chunks
        if (vanillaCheck.test(sx, sz)) return 0;

        // Get or build column context
        LodGenerationService.ColumnContext ctx = contextProvider.provide(world, sx, sz);
        if (ctx == null) return 0;

        // Compute Y range from surface heightmap
        float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float h = ctx.rawHm()[lx][lz];
                if (h < minH) minH = h;
                if (h > maxH) maxH = h;
            }
        }

        int minSectionY = Math.max(
                Math.floorDiv((int) Math.floor(minH), 16) - SURFACE_MARGIN,
                Y_BASE_SECTION);
        int maxSectionY = Math.min(
                Math.floorDiv((int) Math.ceil(maxH), 16) + SURFACE_MARGIN,
                Y_BASE_SECTION + Y_SECTIONS - 1);

        int priority = Math.abs(sx - centreX) + Math.abs(sz - centreZ);

        int enqueued = 0;
        for (int sy = minSectionY; sy <= maxSectionY; sy++) {
            long key = LodGenerationService.sectionKey(sx, sy, sz);
            if (generatedSections.contains(key)) continue;

            SectionTask task = new SectionTask(sx, sy, sz, priority, key);
            task.columnContext = ctx;
            if (queue.enqueue(task)) {
                enqueued++;
            }
        }
        return enqueued;
    }

    // ── Teleportation handling ──────────────────────────────────────────

    /**
     * Handle a teleportation event: clear the queue, forget generated
     * sections, and repopulate from the new position.
     */
    private void handleTeleport(World world, int newCX, int newCZ,
                                 java.util.function.BiPredicate<Integer, Integer> vanillaCheck) {
        LOGGER.info(String.format(
                "[ChunkScheduler] Teleport detected: (%d,%d) → (%d,%d) — resetting",
                lastCentreX, lastCentreZ, newCX, newCZ));

        queue.clear();
        generatedSections.clear();
        totalCancelled = 0;
        totalEnqueued = 0;
        reprioritiseCount = 0;

        int enqueued = enqueueRadius(world, newCX, newCZ,
                generationRadius, vanillaCheck);
        LOGGER.info(String.format(
                "[ChunkScheduler] Post-teleport repopulation: %d sections", enqueued));
    }

    // ── LRU eviction for generatedSections ──────────────────────────────

    /**
     * Evict entries from {@code generatedSections} that are very far from
     * the current centre.  This prevents unbounded memory growth during
     * long play sessions.  Evicted keys can be re-generated if the player
     * returns, but Voxy's RocksDB already has the data so no visual glitch
     * occurs.
     *
     * <p>Called periodically by the scheduler (e.g. every 100 ticks).
     *
     * @param centreX current player section X
     * @param centreZ current player section Z
     * @param maxDist sections beyond this distance are evicted
     * @return number of entries evicted
     */
    public static int evictDistantSections(Set<Long> generatedSections,
                                            int centreX, int centreZ,
                                            int maxDist) {
        int evicted = 0;
        var it = generatedSections.iterator();
        while (it.hasNext()) {
            long key = it.next();
            // Unpack section coordinates (20 bits each, signed via sign extension)
            int sx = (int) (key >> 40) << 12 >> 12;  // sign-extend from 20 bits
            int sz = (int) (key & 0xFFFFF) << 12 >> 12;
            int dist = Math.abs(sx - centreX) + Math.abs(sz - centreZ);
            if (dist > maxDist) {
                it.remove();
                evicted++;
            }
        }
        return evicted;
    }
}
