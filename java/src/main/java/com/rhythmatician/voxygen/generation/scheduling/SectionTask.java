package com.rhythmatician.voxygen.generation.scheduling;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single section's journey through the 4-stage ONNX inference
 * pipeline.  Each section progresses through stages 0→1→2→3 before being
 * written to Voxy.
 *
 * <h3>Stage pipeline</h3>
 * <pre>
 *   Stage 0 (init → LOD4):   no parent needed      → produces 1×1×1 block logits
 *   Stage 1 (LOD4 → LOD3):   parent from stage 0   → produces 2×2×2 block logits
 *   Stage 2 (LOD3 → LOD2):   parent from stage 1   → produces 4×4×4 block logits
 *   Stage 3 (LOD2 → LOD1):   parent from stage 2   → produces final 16³ block logits
 * </pre>
 *
 * <p>Thread safety: the {@link #state} field uses an {@link AtomicReference}
 * for lock-free state transitions.  All other fields are effectively final
 * after construction (set once during promotion, read afterward).
 */
public final class SectionTask implements Comparable<SectionTask> {

    /** Pipeline states — a section progresses linearly through these. */
    public enum State {
        /** Newly created, waiting for stage 0 queue. */
        PENDING,
        /** Actively being processed by a stage worker. */
        PROCESSING,
        /** All 4 stages complete, result written to Voxy. */
        READY,
        /** Inference or write failed. */
        FAILED,
        /** Cancelled because player moved away — skipped by workers. */
        CANCELLED
    }

    // ── Identity (immutable after construction) ─────────────────────────

    /** Chunk-section X coordinate. */
    public final int sectionX;
    /** Chunk-section Y coordinate. */
    public final int sectionY;
    /** Chunk-section Z coordinate. */
    public final int sectionZ;

    /** Packed key for deduplication: same encoding as LodGenerationService.sectionKey(). */
    public final long key;

    /**
     * Distance-based priority — lower = higher urgency.  Volatile so it
     * can be updated by the scheduler when the player moves.
     */
    volatile int priority;

    // ── Mutable pipeline state ──────────────────────────────────────────

    /** Current pipeline state (lock-free). */
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    /** Which stage this task is waiting for next (0–3). */
    private volatile int nextStage;

    /**
     * Column conditioning data shared across all Y sections in the same column.
     * Set before enqueue, read by all stage workers.  Immutable record.
     */
    volatile LodGenerationService.ColumnContext columnContext;

    /**
     * Binary solid-occupancy parent derived from the previous stage's argmax (class 0 = air).
     * <ul>
     *   <li>Stage 0: null (no parent)</li>
     *   <li>Stage 1: {@code float[1][1][1]} from stage 0</li>
     *   <li>Stage 2: {@code float[2][2][2]} from stage 1</li>
     *   <li>Stage 3: {@code float[4][4][4]} from stage 2</li>
     * </ul>
     * Deep-copied from the producing thread's buffers before cross-thread handoff.
     */
    volatile float[] parentFlat;

    /** Failure reason (set when state = FAILED). */
    volatile String failureMessage;

    // ── Construction ────────────────────────────────────────────────────

    public SectionTask(int sectionX, int sectionY, int sectionZ,
                       int priority, long key) {
        this.sectionX = sectionX;
        this.sectionY = sectionY;
        this.sectionZ = sectionZ;
        this.priority = priority;
        this.key      = key;
        this.nextStage = 0;
    }

    // ── State transitions ───────────────────────────────────────────────

    /** Get current state. */
    public State state() { return state.get(); }

    /** Get the next stage index this task needs (0–3). */
    public int nextStage() { return nextStage; }

    /**
     * Atomically transition from PENDING → PROCESSING.
     * @return true if transition succeeded
     */
    public boolean claimForProcessing() {
        return state.compareAndSet(State.PENDING, State.PROCESSING);
    }

    /**
     * Advance to the next stage after successful inference.
     * Transitions PROCESSING → PENDING and increments nextStage.
     *
     * @param parentFlat deep-copied flat parent array for the next stage
     *                   (null for the final stage transition)
     */
    public void promoteToNextStage(float[] parentFlat) {
        this.parentFlat = parentFlat;
        this.nextStage++;
        state.set(State.PENDING);
    }

    /** Mark as complete (all stages done, written to Voxy). */
    public void markReady() {
        this.parentFlat = null; // free intermediate data
        state.set(State.READY);
    }

    /** Mark as failed. */
    public void markFailed(String message) {
        this.failureMessage = message;
        this.parentFlat = null;
        state.set(State.FAILED);
    }

    /**
     * Cancel this task if it is still PENDING.  Cancelled tasks are
     * silently skipped by stage workers (claimForProcessing returns false).
     *
     * @return true if the task was in PENDING state and is now CANCELLED
     */
    public boolean cancel() {
        return state.compareAndSet(State.PENDING, State.CANCELLED);
    }

    /** @return true if this task has been cancelled. */
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    /**
     * Update this task's priority based on current player position.
     * Used by the scheduler when the player moves to re-weight the
     * priority queues.
     *
     * @param centreX current player section X
     * @param centreZ current player section Z
     */
    public void updatePriority(int centreX, int centreZ) {
        this.priority = Math.abs(sectionX - centreX) + Math.abs(sectionZ - centreZ);
    }

    /**
     * Update this task's priority using a direction-weighted distance.
     * Sections ahead of the player's heading get a bonus (lower priority
     * number = higher urgency), sections behind get a penalty.
     *
     * @param centreX  current player section X
     * @param centreZ  current player section Z
     * @param headingX normalised heading X component
     * @param headingZ normalised heading Z component
     * @param coneStrength how much to bias toward heading (0=pure Manhattan, 1=aggressive)
     */
    public void updateDirectionalPriority(int centreX, int centreZ,
                                           float headingX, float headingZ,
                                           float coneStrength) {
        int dx = sectionX - centreX;
        int dz = sectionZ - centreZ;
        int manhattan = Math.abs(dx) + Math.abs(dz);

        // Dot product with heading: positive = ahead, negative = behind
        float dot = dx * headingX + dz * headingZ;
        // Scale penalty: sections behind get up to coneStrength * manhattan added
        float directionalPenalty = -dot * coneStrength;

        this.priority = manhattan + Math.round(directionalPenalty);
    }

    // ── Comparable (priority queue ordering) ────────────────────────────

    /**
     * Natural ordering: lower priority value = closer to player = higher urgency.
     */
    @Override
    public int compareTo(SectionTask other) {
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public String toString() {
        return "SectionTask[(" + sectionX + "," + sectionY + "," + sectionZ
                + ") stage=" + nextStage + " state=" + state.get()
                + " pri=" + priority + "]";
    }
}
