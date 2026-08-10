package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.PriorityBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

/**
 * Priority queue system for octree-based LOD generation.
 *
 * <p>5 queues (one per LOD level 0-4), processed L4-first for breadth-first
 * tree traversal.  After inference at level N, occupied octants spawn child
 * tasks at level N-1.
 *
 * <h3>Threading model</h3>
 * <p>Multiple worker threads may drain different levels concurrently.
 * The queue provides thread-safe enqueue, drain, and child-spawning.
 *
 * <h3>Priority boosting</h3>
 * <p>A task may receive a constant priority reduction if it lies adjacent
 * to a vanilla-loaded chunk (set by the generation service when roots are
 * enqueued).  Newly added in this revision, we also track adjacency to
 * *processed* sections and propagate a similar boost outward as each
 * section completes.  This produces a ring-expanding frontier that keeps
 * generation focused just beyond the visible boundary.
 *
 * <h3>Shutdown</h3>
 * <p>Shutdown cascades top-down: when L4 is done generating, its completion
 * is signalled, and workers at L3 know no more parents will arrive, etc.
 * Each worker uses {@link #isUpstreamDone(int)} to decide when to exit.
 *
 * @see OctreeTask
 */
public final class OctreeQueue {

    /** Number of LOD levels (0-4 inclusive). */
    static final int NUM_LEVELS = 5;

    /** All tracked tasks, keyed by packed wsKey (deduplication). */
    private final ConcurrentHashMap<Long, OctreeTask> allTasks =
            new ConcurrentHashMap<>();

    /** Per-level priority queues.  Index 0 = L0 (finest), 4 = L4 (coarsest). */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final PriorityBlockingQueue<OctreeTask>[] levelQueues =
            new PriorityBlockingQueue[NUM_LEVELS];

    // ── Completion tracking ─────────────────────────────────────────────

    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger failedCount    = new AtomicInteger();
    private volatile int totalEnqueued;

    /**
     * Set after all L4 root tasks have been enqueued (population phase done).
     * L4 workers check this + queue empty to know when to exit.
     */
    private final AtomicBoolean populationDone = new AtomicBoolean();

    /**
     * Per-level completion flags.  Set when all workers for a level have
     * exited.  Used by child levels to know when no more parent spawns
     * will arrive.
     */
    private final AtomicBoolean[] levelComplete = new AtomicBoolean[NUM_LEVELS];

    /**
     * Lock for re-prioritisation.  Held during drain–update–re-add cycles
     * to prevent workers from seeing transiently empty queues.
     */
    private final ReentrantLock reprioritiseLock = new ReentrantLock();



    // ── Octree efficiency stats (RocNet-inspired) ─────────────────────
    // Track how many octants are spawned vs pruned at each level to
    // measure the efficiency advantage the octree is supposed to buy.

    /** Per-level count of octants spawned (occupied). */
    private final AtomicInteger[] spawnedPerLevel = new AtomicInteger[NUM_LEVELS];

    /** Per-level count of octants pruned (empty). */
    private final AtomicInteger[] prunedPerLevel = new AtomicInteger[NUM_LEVELS];

    // ── Construction ────────────────────────────────────────────────────

    public OctreeQueue() {
        for (int i = 0; i < NUM_LEVELS; i++) {
            levelQueues[i]  = new PriorityBlockingQueue<>();
            levelComplete[i] = new AtomicBoolean();
            spawnedPerLevel[i] = new AtomicInteger();
            prunedPerLevel[i]  = new AtomicInteger();
        }
    }



    // ── Enqueue ─────────────────────────────────────────────────────────

    /**
     * Enqueue a root L4 task.
     *
     * @return {@code true} if enqueued; {@code false} if a task with the
     *         same key already exists (duplicate)
     */
    public boolean enqueueRoot(OctreeTask task) {
        if (task.level != 4) {
            throw new IllegalArgumentException(
                    "enqueueRoot: expected L4 task, got L" + task.level);
        }
        if (allTasks.putIfAbsent(task.wsKey, task) != null) return false;
        levelQueues[4].add(task);
        return true;
    }

    /**
     * Enqueue a child task at any level (internal use by spawnChildren).
     *
     * @return {@code true} if enqueued; {@code false} if duplicate
     */
    boolean enqueueChild(OctreeTask task) {
        if (allTasks.putIfAbsent(task.wsKey, task) != null) return false;
        levelQueues[task.level].add(task);
        return true;
    }

    // ── Child spawning ──────────────────────────────────────────────────

    // No margin constant — surface pruning is now zero-margin.
    // Heightmap values are "first air Y" (topSolid + 1).
    // Prune if entirely above (minBlockY >= surfMax) or
    // entirely below (maxBlockY_excl < surfMin).

    /**
     * After inference on a parent task, spawn child tasks for each occupied
     * octant.  This is the core of the octree traversal.
     *
     * <p>For each set bit in {@code occMask}:
     * <ol>
     *   <li>Compute child WorldSection coordinates from parent + octant</li>
     *   <li>Extract the 16³ octant region from the parent's 32³ argmax
     *       predictions</li>
     *   <li>Upsample 16³ → 32³ via nearest-neighbor to produce the child's
     *       parent context</li>
     *   <li>Create and enqueue the child {@link OctreeTask}</li>
     * </ol>
     *
     * <p>Children whose Y range does not intersect the surface heightmap
     * are pruned entirely — only surface-intersecting children are refined.
     * Pruning uses zero margin: any child entirely above or below the
     * heightmap surface ("first air Y" values) is skipped.
     *
     * <p>Column context is NOT built here — it is deferred to the worker
     * thread that processes the child, avoiding blocking the parent's
     * inference thread on noise sampling.
     *
     * <p>Does nothing if the parent is at L0 (leaves have no children).
     *
     * @param parent         the parent task that just completed inference
     * @param occMask        8-bit occupancy mask from sigmoid(occ_logits) > 0.5
     * @param blockArgmax    the parent's 32³ argmax block IDs as
     *                       {@code int[32][32][32]} (Y, Z, X order),
     *                       already converted from logits
     * @param playerSectionX current player section X (L0 coordinates)
     * @param playerSectionZ current player section Z (L0 coordinates)
     * @return number of children actually enqueued (may be less than
     *         popcount(occMask) if duplicates were detected)
     */
    public int spawnChildren(OctreeTask parent, byte occMask,
                             int[][][] blockArgmax,
                             int playerSectionX, int playerSectionZ) {
        if (parent.level <= 0) return 0;

        int childLevel = parent.level - 1;
        int spawned = 0;
        int pruned = 0;

        // Pre-compute per-octant surface Y ranges from the parent's heightmap
        // for the surface-intersection check.  null if no heightmap available.
        float[][] parentRawHm = (parent.columnContext != null)
                ? parent.columnContext.rawHm() : null;

        for (int oct = 0; oct < 8; oct++) {
            if ((occMask & (1 << oct)) == 0) {
                pruned++;
                continue;
            }

            int cx = OctreeTask.childX(parent.wsX, oct);
            int cy = OctreeTask.childY(parent.wsY, oct);
            int cz = OctreeTask.childZ(parent.wsZ, oct);

            // Skip children whose Y range is entirely outside the world
            if (LodGenerationService.isOutOfWorldY(childLevel, cy)) {
                pruned++;
                continue;
            }

            // Extract the 16³ octant from the parent's 32³ predictions
            // Octant bits: bit0=X, bit1=Z, bit2=Y
            int offX = (oct & 1) * 16;
            int offY = ((oct >> 2) & 1) * 16;
            int offZ = ((oct >> 1) & 1) * 16;

            // Upsample 16³ → 32³ via nearest-neighbor, flatten to
            // long[32 * 32 * 32] for the ONNX parent_blocks input
            long[] childParentFlat = extractAndUpsampleOctant(
                    blockArgmax, offY, offZ, offX);

            // Compute proper distance-based priority from current player pos
            int playerAtLevel_X = WorldSectionCoord.sectionToWorldSection(playerSectionX, childLevel);
            int playerAtLevel_Z = WorldSectionCoord.sectionToWorldSection(playerSectionZ, childLevel);
            int childPriority = Math.abs(cx - playerAtLevel_X)
                              + Math.abs(cz - playerAtLevel_Z);

            // ── Surface-intersection pruning (zero-margin) ─────────────
            // Heightmap values are "first air Y" (topSolid + 1).
            // - surfMin = lowest first-air-Y in this octant's XZ footprint
            // - surfMax = highest first-air-Y
            // Prune if the child's block-Y range is entirely above the
            // surface (childMinBlockY >= surfMax → all air) or entirely
            // below it (childMaxBlockY_excl < surfMin → all underground).
            // This aggressively skips both sky and deep-underground
            // octants.  May clip some treetops in heavily forested biomes
            // (noise-based heightmap doesn't include trees/structures),
            // but the speed gain is large.
            if (parentRawHm != null) {
                int childMinBlockY = WorldSectionCoord.worldSectionToBlockMin(cy, childLevel);
                int childMaxBlockY = WorldSectionCoord.worldSectionToBlockMax(cy, childLevel) + 1;

                // Scan the 16×16 sub-region of the parent's 32×32 heightmap
                // that corresponds to this octant's XZ footprint
                float surfMin = Float.MAX_VALUE;
                float surfMax = -Float.MAX_VALUE;
                for (int rz = offZ; rz < offZ + 16; rz++) {
                    for (int rx = offX; rx < offX + 16; rx++) {
                        float h = parentRawHm[rz][rx];
                        if (h < surfMin) surfMin = h;
                        if (h > surfMax) surfMax = h;
                    }
                }

                // Zero-margin: prune if entirely above OR entirely below surface
                if (childMaxBlockY < surfMin || childMinBlockY >= surfMax) {
                    pruned++;
                    continue;
                }
            }

            OctreeTask child = new OctreeTask(
                    childLevel, cx, cy, cz, oct, childPriority);
            child.parentContextFlat = childParentFlat;
            child.nearVanilla = parent.nearVanilla;  // propagate boost down octree

            // Column context is built lazily by the processing worker
            // (not here) to avoid blocking the inference thread on noise sampling

            if (enqueueChild(child)) {
                spawned++;
            }
        }

        if (spawned > 0 || pruned > 0) {
            spawnedPerLevel[childLevel].addAndGet(spawned);
            prunedPerLevel[childLevel].addAndGet(pruned);
        }

        if (spawned > 0) {
            HelloTerrainMod.LOGGER.debug(
                    "[OctreeQueue] Spawned {} children at L{} from parent L{} ({},{},{}) — pruned {}",
                    spawned, childLevel, parent.level,
                    parent.wsX, parent.wsY, parent.wsZ, pruned);
        }

        return spawned;
    }

    /**
     * Extract a 16³ octant from a 32³ volume and upsample 2× via
     * nearest-neighbor to produce a flat 32³ array of block IDs.
     *
     * <p>The result is shaped as {@code long[32 * 32 * 32]} in
     * row-major Y,Z,X order matching the ONNX parent_blocks input
     * layout {@code [N, 32, 32, 32]}.
     *
     * @param src  source 32³ argmax values, indexed [Y][Z][X]
     * @param offY Y offset of the octant (0 or 16)
     * @param offZ Z offset of the octant (0 or 16)
     * @param offX X offset of the octant (0 or 16)
     * @return flat long[32768] containing the upsampled octant block IDs
     */
    static long[] extractAndUpsampleOctant(int[][][] src,
                                            int offY, int offZ, int offX) {
        long[] dst = new long[32 * 32 * 32];
        int idx = 0;
        for (int dy = 0; dy < 32; dy++) {
            int srcY = offY + (dy >> 1);  // nearest-neighbor: /2
            for (int dz = 0; dz < 32; dz++) {
                int srcZ = offZ + (dz >> 1);
                for (int dx = 0; dx < 32; dx++) {
                    int srcX = offX + (dx >> 1);
                    dst[idx++] = src[srcY][srcZ][srcX];
                }
            }
        }
        return dst;
    }

    // ── Polling ─────────────────────────────────────────────────────────

    /**
     * Poll the next task, processing higher (coarser) levels first to
     * achieve breadth-first traversal.  Scans from L4 down to L0.
     *
     * @param timeout maximum time to wait for a task
     * @param unit    time unit for the timeout
     * @return the next highest-priority task from the coarsest non-empty
     *         level, or {@code null} if the timeout expired
     */
    public OctreeTask pollNext(long timeout, TimeUnit unit)
            throws InterruptedException {
        // Try each level from coarsest to finest (breadth-first)
        for (int lvl = 4; lvl >= 0; lvl--) {
            OctreeTask task = levelQueues[lvl].poll();
            if (task != null) return task;
        }
        // Nothing immediately available — wait briefly on the coarsest
        // non-empty-or-active level
        for (int lvl = 4; lvl >= 0; lvl--) {
            if (!isLevelPermanentlyDone(lvl)) {
                return levelQueues[lvl].poll(timeout, unit);
            }
        }
        return null;
    }

    /**
     * Settling delay (ms) for the first greedy poll after a blocking poll
     * wakes on an empty-to-non-empty queue transition.  Gives
     * {@link #spawnChildren} time to add all sibling tasks so the
     * priority queue can return them in true distance order.
     *
     * <p>Without this, the blocking poll returns oct-0 (the first child
     * added by the loop in spawnChildren) — which is usually NOT the
     * closest sibling.  50 ms is negligible vs 8+ seconds of context
     * building per task.
     */
    private static final long SIBLING_SETTLE_MS = 50;

    /**
     * Drain up to {@code maxBatch} tasks from a specific level's queue.
     *
     * <p>Performs a blocking poll with timeout for the first task, then
     * waits briefly ({@value #SIBLING_SETTLE_MS} ms) for sibling tasks
     * from {@link #spawnChildren} to accumulate before greedily draining
     * additional tasks.  This ensures the batch contains the closest
     * tasks from the full sibling set, not just the first one added.
     *
     * @param level    LOD level (0-4)
     * @param maxBatch maximum tasks to return
     * @param timeout  how long to wait for the first task
     * @param unit     time unit
     * @return list of 0 to maxBatch tasks
     */
    public List<OctreeTask> drainLevel(int level, int maxBatch,
                                        long timeout, TimeUnit unit)
            throws InterruptedException {
        List<OctreeTask> batch = new ArrayList<>(maxBatch);

        OctreeTask first = levelQueues[level].poll(timeout, unit);
        if (first == null) return batch;
        batch.add(first);

        // Brief settling delay: let sibling tasks from spawnChildren
        // accumulate in the priority queue.  Re-insert the first task
        // so all siblings compete fairly in priority order.
        if (level < 4 && SIBLING_SETTLE_MS > 0) {
            Thread.sleep(SIBLING_SETTLE_MS);
            if (!levelQueues[level].isEmpty()) {
                // Siblings arrived — re-insert first and drain in true
                // priority order so we batch the closest tasks together
                levelQueues[level].add(first);
                batch.clear();
                levelQueues[level].drainTo(batch, maxBatch);
                return batch;
            }
        }

        while (batch.size() < maxBatch) {
            OctreeTask next = levelQueues[level].poll();
            if (next == null) break;
            batch.add(next);
        }
        return batch;
    }

    /** Non-blocking poll from a specific level. */
    public OctreeTask pollLevel(int level) {
        return levelQueues[level].poll();
    }

    // ── Completion signals ──────────────────────────────────────────────

    /** Signal that all L4 root tasks have been enqueued. */
    public void signalPopulationDone() { populationDone.set(true); }

    /**
     * Signal that all workers for a level have exited.
     * Child-level workers use this to know when their queue is permanently
     * drained (no more parents will spawn children).
     */
    public void signalLevelComplete(int level) { levelComplete[level].set(true); }

    /** Increment the completed-task counter (called after Voxy write). */
    public void markCompleted() { completedCount.incrementAndGet(); }

    /**
     * When a task transitions to READY, call this to boost any nearby
     * pending tasks so the processing frontier expands outward one ring at a time.
     *
     * <p>We only consider horizontal neighbours (±1 in X/Z) because
     * priority ignores Y; vertical propagation would behave the same but
     * offer little benefit and complicate test scenarios.  The method is
     * idempotent – marking an already-boosted task again has no effect.
     */
    public void propagateAdjacency(OctreeTask completed) {
        int lvl = completed.level;
        // four cardinal directions in XZ
        int[][] deltas = {{1,0,0},{-1,0,0},{0,0,1},{0,0,-1}};
        for (int[] d : deltas) {
            int nx = completed.wsX + d[0];
            int ny = completed.wsY + d[1];
            int nz = completed.wsZ + d[2];
            // pack key and look up
            long key = OctreeTask.packKey(lvl, nx, ny, nz);
            OctreeTask neighbour = allTasks.get(key);
            if (neighbour == null) continue;
            // only boost pending tasks that haven't already been marked
            if (!neighbour.isCancelled() && !neighbour.nearProcessed) {
                neighbour.nearProcessed = true;
            }
        }
    }

    /** Increment the failed-task counter. */
    public void markFailed() { failedCount.incrementAndGet(); }

    /**
     * Check whether a level worker should exit because no more tasks
     * will arrive in its queue.
     *
     * <ul>
     *   <li>L4: exits when population is done and queue is empty.</li>
     *   <li>L3-L0: exits when the parent level (level+1) is complete
     *       and its queue is empty.</li>
     * </ul>
     */
    public boolean isUpstreamDone(int level) {
        if (level == 4) return populationDone.get();
        return levelComplete[level + 1].get();
    }

    /** Check if a level is permanently done (upstream done + queue empty). */
    private boolean isLevelPermanentlyDone(int level) {
        return isUpstreamDone(level) && levelQueues[level].isEmpty();
    }

    /**
     * Atomically check exit conditions and drain remaining tasks for a level.
     *
     * <p>Holds the {@code reprioritiseLock} during the check-and-drain to
     * prevent a race where {@link #reprioritise} / {@link #reprioritiseDirectional}
     * drains the queue (making it appear empty) while a worker decides to exit.
     *
     * <p>Workers <em>must</em> use this method instead of separately calling
     * {@link #isUpstreamDone(int)} and {@link #pollLevel(int)} to avoid
     * premature termination when a reprioritisation is in progress.
     *
     * @param level LOD level (0-4)
     * @return remaining tasks drained from the queue if upstream is done and
     *         the queue is empty after draining; {@code null} if the level is
     *         not yet eligible to exit (upstream not done, or tasks remain)
     */
    public List<OctreeTask> tryFinalDrain(int level) {
        reprioritiseLock.lock();
        try {
            if (!isUpstreamDone(level)) return null;
            List<OctreeTask> remaining = new ArrayList<>();
            levelQueues[level].drainTo(remaining);
            return remaining;
        } finally {
            reprioritiseLock.unlock();
        }
    }

    // ── Live re-prioritisation ──────────────────────────────────────────

    /**
     * Re-heap all level queues using updated priorities based on the
     * player's current section position (L0 coordinates).
     */
    public void reprioritise(int playerSectionX, int playerSectionZ) {
        reprioritiseLock.lock();
        try {
            for (int lvl = 0; lvl < NUM_LEVELS; lvl++) {
                List<OctreeTask> tmp = new ArrayList<>();
                levelQueues[lvl].drainTo(tmp);
                for (OctreeTask t : tmp) {
                    if (!t.isCancelled()) {
                        t.updatePriority(playerSectionX, playerSectionZ);
                    }
                }
                levelQueues[lvl].addAll(tmp);
            }
        } finally {
            reprioritiseLock.unlock();
        }
    }

    /**
     * Re-heap using direction-weighted priorities.
     */
    public void reprioritiseDirectional(int playerSectionX, int playerSectionZ,
                                         float headingX, float headingZ,
                                         float coneStrength) {
        reprioritiseLock.lock();
        try {
            for (int lvl = 0; lvl < NUM_LEVELS; lvl++) {
                List<OctreeTask> tmp = new ArrayList<>();
                levelQueues[lvl].drainTo(tmp);
                for (OctreeTask t : tmp) {
                    if (!t.isCancelled()) {
                        t.updateDirectionalPriority(playerSectionX, playerSectionZ,
                                headingX, headingZ, coneStrength);
                    }
                }
                levelQueues[lvl].addAll(tmp);
            }
        } finally {
            reprioritiseLock.unlock();
        }
    }

    /**
     * Cancel all PENDING tasks whose Manhattan distance from the given
     * centre exceeds {@code maxRadius}.  Distance is computed at each
     * task's native level.
     *
     * @return number of tasks cancelled
     */
    public int cancelBeyondRadius(int playerSectionX, int playerSectionZ,
                                   int maxRadius) {
        int cancelled = 0;
        for (Map.Entry<Long, OctreeTask> entry : allTasks.entrySet()) {
            OctreeTask t = entry.getValue();
            int playerAtLevel_X = WorldSectionCoord.sectionToWorldSection(playerSectionX, t.level);
            int playerAtLevel_Z = WorldSectionCoord.sectionToWorldSection(playerSectionZ, t.level);
            int dist = Math.abs(t.wsX - playerAtLevel_X)
                     + Math.abs(t.wsZ - playerAtLevel_Z);
            int radiusAtLevel = maxRadius >> (t.level + 1);
            if (dist > radiusAtLevel && t.cancel()) {
                cancelled++;
            }
        }
        // Clean up terminal tasks from the dedup map
        allTasks.entrySet().removeIf(e -> {
            OctreeTask.State s = e.getValue().state();
            return s == OctreeTask.State.CANCELLED
                || s == OctreeTask.State.READY
                || s == OctreeTask.State.FAILED;
        });
        return cancelled;
    }

    /**
     * Remove a task from the dedup map so the same section can be
     * re-enqueued later.
     */
    public void removeFromDedup(long key) {
        allTasks.remove(key);
    }

    // ── Stats / Accessors ───────────────────────────────────────────────

    public void setTotalEnqueued(int total) { this.totalEnqueued = total; }
    public void addTotalEnqueued(int delta) { this.totalEnqueued += delta; }
    public int totalEnqueued()  { return totalEnqueued; }
    public int completedCount() { return completedCount.get(); }
    public int failedCount()    { return failedCount.get(); }
    public int totalProcessed() { return completedCount.get() + failedCount.get(); }
    public int levelQueueSize(int level) { return levelQueues[level].size(); }
    public int trackedTaskCount() { return allTasks.size(); }

    /**
     * Format queue sizes as a compact string for logging:
     * {@code "L4:12|L3:45|L2:100|L1:230|L0:500"}.
     */
    public String queueSizeSummary() {
        StringBuilder sb = new StringBuilder();
        for (int lvl = 4; lvl >= 0; lvl--) {
            if (sb.length() > 0) sb.append('|');
            sb.append("L").append(lvl).append(':').append(levelQueues[lvl].size());
        }
        return sb.toString();
    }

    /**
     * Format octree efficiency stats as a compact string for logging.
     *
     * <p>RocNet-inspired: this shows how effectively the octree prunes
     * empty subtrees at each level.  A well-calibrated model should
     * prune most octants at coarse levels and fewer at fine levels.
     *
     * <p>Example: {@code "L3: 120 spawned / 200 pruned (62.5% pruned) |
     * L2: 480 spawned / 480 pruned (50.0% pruned)"}.
     *
     * @return formatted efficiency summary, or empty string if no stats
     */
    public String efficiencySummary() {
        StringBuilder sb = new StringBuilder();
        for (int lvl = 3; lvl >= 0; lvl--) {
            int s = spawnedPerLevel[lvl].get();
            int p = prunedPerLevel[lvl].get();
            int total = s + p;
            if (total == 0) continue;
            if (sb.length() > 0) sb.append(" | ");
            double prunePct = 100.0 * p / total;
            sb.append(String.format("L%d: %d spawned / %d pruned (%.1f%% pruned)",
                    lvl, s, p, prunePct));
        }
        return sb.toString();
    }

    /** Per-level spawn count accessor. */
    public int spawnedAt(int level) { return spawnedPerLevel[level].get(); }

    /** Per-level prune count accessor. */
    public int prunedAt(int level) { return prunedPerLevel[level].get(); }

    /** Clear all state for reuse or shutdown. */
    public void clear() {
        for (int i = 0; i < NUM_LEVELS; i++) {
            levelQueues[i].clear();
            levelComplete[i].set(false);
            spawnedPerLevel[i].set(0);
            prunedPerLevel[i].set(0);
        }
        allTasks.clear();
        completedCount.set(0);
        failedCount.set(0);
        totalEnqueued = 0;
        populationDone.set(false);
    }
}
