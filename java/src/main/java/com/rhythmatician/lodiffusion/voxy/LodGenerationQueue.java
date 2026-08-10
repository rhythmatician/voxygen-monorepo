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

/**
 * Thread-safe priority queue system for the 4-stage ONNX inference pipeline.
 *
 * <p>Sections enter at stage 0 and are promoted through stages 1→2→3 as each
 * stage completes.  Each stage has its own {@link PriorityBlockingQueue}
 * ordered by distance from the player (closest first).
 *
 * <h3>Continuous scheduling</h3>
 * <p>The queue supports live re-prioritisation as the player moves:
 * {@link #reprioritise(int, int)} re-weights all pending tasks, and
 * {@link #cancelBeyondRadius(int, int, int)} marks distant tasks as
 * cancelled so stage workers skip them cheaply.
 *
 * <h3>Shutdown cascade</h3>
 * <p>Clean shutdown flows from upstream to downstream:
 * <ol>
 *   <li>Service stop requested → {@link #signalPopulationDone()}</li>
 *   <li>All stage-0 workers drain their queue and exit →
 *       {@link #signalStageComplete(int) signalStageComplete(0)}</li>
 *   <li>Stage-1 worker drains its queue and exits →
 *       {@link #signalStageComplete(int) signalStageComplete(1)}</li>
 *   <li>… until stage 3 exits.</li>
 * </ol>
 *
 * <p>Each worker uses {@link #isUpstreamDone(int)} to decide when to exit
 * after its queue is empty.
 */
public final class LodGenerationQueue {

    static final int NUM_STAGES = 4;

    /** All tracked tasks, keyed by section position (deduplication). */
    private final ConcurrentHashMap<Long, SectionTask> allTasks =
            new ConcurrentHashMap<>();

    /** Per-stage priority queues.  Stage workers poll from their queue. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    private final PriorityBlockingQueue<SectionTask>[] stageQueues =
            new PriorityBlockingQueue[NUM_STAGES];

    // ── Completion tracking ─────────────────────────────────────────────

    private final AtomicInteger completedCount = new AtomicInteger();
    private final AtomicInteger failedCount    = new AtomicInteger();
    private volatile int totalEnqueued;

    /** Set after all sections have been added to the stage-0 queue. */
    private final AtomicBoolean populationDone = new AtomicBoolean();

    /**
     * Per-stage completion flags.  Set when <em>all</em> workers for a
     * stage have exited (for stage 0, this means every thread in the pool).
     */
    private final AtomicBoolean[] stageComplete = new AtomicBoolean[NUM_STAGES];
    /**
     * Lock protecting re-prioritisation.  Held during drain–re-add cycles
     * to prevent stage workers from seeing a transiently empty queue.
     */
    private final ReentrantLock reprioritiseLock = new ReentrantLock();
    // ── Construction ────────────────────────────────────────────────────

    public LodGenerationQueue() {
        for (int i = 0; i < NUM_STAGES; i++) {
            stageQueues[i]  = new PriorityBlockingQueue<>();
            stageComplete[i] = new AtomicBoolean();
        }
    }

    // ── Enqueue / promote ───────────────────────────────────────────────

    /**
     * Enqueue a new task into the stage-0 queue.
     *
     * @return {@code true} if enqueued; {@code false} if a task with the
     *         same key already exists (duplicate)
     */
    public boolean enqueue(SectionTask task) {
        if (allTasks.putIfAbsent(task.key, task) != null) return false;
        stageQueues[0].add(task);
        return true;
    }

    /**
     * Promote a task to its next stage queue after successful processing.
     * The task's {@link SectionTask#nextStage()} must already have been
     * incremented via {@link SectionTask#promoteToNextStage(float[])}.
     */
    public void promoteToNextStage(SectionTask task) {
        int next = task.nextStage();
        if (next < NUM_STAGES) {
            stageQueues[next].add(task);
        }
    }

    // ── Polling ─────────────────────────────────────────────────────────

    /** Blocking poll with timeout. */
    public SectionTask poll(int stage, long timeout, TimeUnit unit)
            throws InterruptedException {
        return stageQueues[stage].poll(timeout, unit);
    }

    /** Non-blocking poll. */
    public SectionTask poll(int stage) {
        return stageQueues[stage].poll();
    }

    /**
     * Drain up to {@code maxBatch} tasks from a stage queue in one shot.
     *
     * <p>First performs a blocking poll with the given timeout to wait for
     * at least one task, then greedily drains any additional tasks that
     * are already queued (up to {@code maxBatch - 1} more).  This enables
     * batched inference: the worker collects as many tasks as are available
     * without introducing extra latency when the queue is sparse.
     *
     * @param stage     which stage queue to drain (0–3)
     * @param maxBatch  maximum number of tasks to return in one call
     * @param timeout   how long to wait for the <em>first</em> task
     * @param unit      time unit for the timeout
     * @return list of 0–{@code maxBatch} tasks; empty if the timeout
     *         expired with no tasks available
     */
    public java.util.List<SectionTask> drainStage(int stage, int maxBatch,
                                                   long timeout, TimeUnit unit)
            throws InterruptedException {
        java.util.List<SectionTask> batch = new java.util.ArrayList<>(maxBatch);

        // Wait for at least one task
        SectionTask first = stageQueues[stage].poll(timeout, unit);
        if (first == null) return batch;
        batch.add(first);

        // Greedily take more without blocking
        while (batch.size() < maxBatch) {
            SectionTask next = stageQueues[stage].poll();
            if (next == null) break;
            batch.add(next);
        }
        return batch;
    }

    // ── Completion signals ──────────────────────────────────────────────

    /** Signal that all sections have been added to the stage-0 queue. */
    public void signalPopulationDone() { populationDone.set(true); }

    /**
     * Signal that all workers for a stage have exited.
     * Downstream workers use this to know when their queue is permanently
     * drained.
     */
    public void signalStageComplete(int stage) { stageComplete[stage].set(true); }

    /** Increment the completed-sections counter (called after Voxy write). */
    public void markCompleted() { completedCount.incrementAndGet(); }

    /** Increment the failed-sections counter. */
    public void markFailed() { failedCount.incrementAndGet(); }

    /**
     * Check whether a stage worker should exit because no more tasks will
     * arrive in its queue.
     * <ul>
     *   <li>Stage 0: exits when population is done and queue is empty.</li>
     *   <li>Stage N&gt;0: exits when stage N−1 is complete and queue is empty.</li>
     * </ul>
     *
     * <p><b>Thread safety:</b> the upstream-done flag is set <em>after</em>
     * all upstream queue additions, so checking the flag then the queue size
     * is eventually consistent.  Callers should do a final non-blocking poll
     * after seeing upstream-done to avoid TOCTOU misses.
     */
    public boolean isUpstreamDone(int stage) {
        if (stage == 0) return populationDone.get();
        return stageComplete[stage - 1].get();
    }

    // ── Live re-prioritisation ───────────────────────────────────────

    /**
     * Re-heap all stage queues using updated priorities based on the
     * player's current section position.  Called by the scheduler when
     * the player crosses a section boundary.
     *
     * <p>This drains each queue, updates each task's priority via
     * {@link SectionTask#updatePriority(int, int)}, and re-adds them.
     * The lock prevents stage workers from seeing a transiently empty
     * queue mid-drain.
     *
     * @param centreX current player section X
     * @param centreZ current player section Z
     */
    public void reprioritise(int centreX, int centreZ) {
        reprioritiseLock.lock();
        try {
            for (int s = 0; s < NUM_STAGES; s++) {
                List<SectionTask> tmp = new ArrayList<>();
                stageQueues[s].drainTo(tmp);
                for (SectionTask t : tmp) {
                    if (!t.isCancelled()) {
                        t.updatePriority(centreX, centreZ);
                    }
                }
                stageQueues[s].addAll(tmp);
            }
        } finally {
            reprioritiseLock.unlock();
        }
    }

    /**
     * Re-heap using direction-weighted priorities.  Sections ahead of the
     * player's heading receive higher priority (lower number).
     *
     * @param centreX      current player section X
     * @param centreZ      current player section Z
     * @param headingX     normalised heading X component
     * @param headingZ     normalised heading Z component
     * @param coneStrength bias strength (0=pure Manhattan, 1=aggressive cone)
     */
    public void reprioritiseDirectional(int centreX, int centreZ,
                                         float headingX, float headingZ,
                                         float coneStrength) {
        reprioritiseLock.lock();
        try {
            for (int s = 0; s < NUM_STAGES; s++) {
                List<SectionTask> tmp = new ArrayList<>();
                stageQueues[s].drainTo(tmp);
                for (SectionTask t : tmp) {
                    if (!t.isCancelled()) {
                        t.updateDirectionalPriority(centreX, centreZ,
                                headingX, headingZ, coneStrength);
                    }
                }
                stageQueues[s].addAll(tmp);
            }
        } finally {
            reprioritiseLock.unlock();
        }
    }

    /**
     * Cancel all PENDING tasks whose Manhattan distance from the given
     * centre exceeds {@code maxRadius}.  Cancelled tasks remain in the
     * queue but are skipped by workers in O(1) via
     * {@link SectionTask#claimForProcessing()}.
     *
     * <p>Also removes cancelled and terminal tasks from the dedup map
     * so they can be re-enqueued if the player returns.
     *
     * @return number of tasks cancelled in this call
     */
    public int cancelBeyondRadius(int centreX, int centreZ, int maxRadius) {
        int cancelled = 0;
        for (Map.Entry<Long, SectionTask> entry : allTasks.entrySet()) {
            SectionTask t = entry.getValue();
            int dist = Math.abs(t.sectionX - centreX) + Math.abs(t.sectionZ - centreZ);
            if (dist > maxRadius && t.cancel()) {
                cancelled++;
            }
        }
        // Clean up terminal tasks from the dedup map
        allTasks.entrySet().removeIf(e -> {
            SectionTask.State s = e.getValue().state();
            return s == SectionTask.State.CANCELLED
                || s == SectionTask.State.READY
                || s == SectionTask.State.FAILED;
        });
        return cancelled;
    }

    /**
     * Remove a task from the dedup map so the same section can be
     * re-enqueued later (e.g. after cancellation + player return).
     */
    public void removeFromDedup(long key) {
        allTasks.remove(key);
    }

    /** @return total number of tracked tasks (all states). */
    public int trackedTaskCount() {
        return allTasks.size();
    }

    // ── Accessors ───────────────────────────────────────────────────────

    public void setTotalEnqueued(int total) { this.totalEnqueued = total; }
    public void addTotalEnqueued(int delta) { this.totalEnqueued += delta; }
    public int totalEnqueued()  { return totalEnqueued; }
    public int completedCount() { return completedCount.get(); }
    public int failedCount()    { return failedCount.get(); }
    public int stageQueueSize(int stage) { return stageQueues[stage].size(); }

    /** Clear all state for reuse or shutdown. */
    public void clear() {
        for (int i = 0; i < NUM_STAGES; i++) {
            stageQueues[i].clear();
            stageComplete[i].set(false);
        }
        allTasks.clear();
        completedCount.set(0);
        failedCount.set(0);
        totalEnqueued = 0;
        populationDone.set(false);
    }
}
