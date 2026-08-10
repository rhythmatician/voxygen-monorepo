package com.rhythmatician.lodiffusion.voxy;

import java.util.concurrent.atomic.AtomicReference;

/**
 * Represents a single WorldSection in the octree generation pipeline.
 *
 * <p>Unlike {@link SectionTask} which flows through 4 sequential stages,
 * an OctreeTask represents ONE section at ONE LOD level.  The pipeline
 * spawns child tasks breadth-first via occupancy masks predicted by the
 * ONNX model.
 *
 * <h3>LOD hierarchy</h3>
 * <pre>
 *   L4: 16 m/voxel, 512³ block footprint  (root — from octree_init.onnx)
 *   L3:  8 m/voxel, 256³ block footprint  (from octree_refine.onnx)
 *   L2:  4 m/voxel, 128³ block footprint  (from octree_refine.onnx)
 *   L1:  2 m/voxel,  64³ block footprint  (from octree_refine.onnx)
 *   L0:  1 m/voxel,  32³ block footprint  (leaf — from octree_leaf.onnx)
 * </pre>
 *
 * <h3>Parent→child coordinate expansion</h3>
 * <pre>
 *   childX = (parentX &lt;&lt; 1) + (octant &amp; 1)
 *   childY = (parentY &lt;&lt; 1) + ((octant &gt;&gt; 2) &amp; 1)
 *   childZ = (parentZ &lt;&lt; 1) + ((octant &gt;&gt; 1) &amp; 1)
 * </pre>
 *
 * <h3>Octant index from local coords</h3>
 * <pre>
 *   (x &amp; 1) | ((z &amp; 1) &lt;&lt; 1) | ((y &amp; 1) &lt;&lt; 2)
 * </pre>
 *
 * <p>Thread safety: the {@link #state} field uses an {@link AtomicReference}
 * for lock-free state transitions.  All identity fields are final.  Context
 * fields ({@code columnContext}, {@code parentContextFlat}) are set once
 * before enqueue and read afterward — safely published via the queue's
 * memory ordering.
 */
public final class OctreeTask implements Comparable<OctreeTask> {

    /** Pipeline states for an octree task. */
    public enum State {
        /** Newly created, waiting in the level queue. */
        PENDING,
        /** Actively being processed by a level worker. */
        PROCESSING,
        /** Inference complete, result written to Voxy. */
        READY,
        /** Inference or write failed. */
        FAILED,
        /** Cancelled because player moved away. */
        CANCELLED
    }

    // ── Key packing constants ────────────────────────────────────────────

    private static final int COORD_BITS = 20;
    /** Bias added to signed coordinates before packing into 20-bit unsigned field. */
    static final int COORD_BIAS = 1 << (COORD_BITS - 1); // 524_288
    /** Minimum representable coordinate value (inclusive). */
    static final int MIN_COORD = -COORD_BIAS;             // -524_288
    /** Maximum representable coordinate value (inclusive). */
    static final int MAX_COORD = COORD_BIAS - 1;          //  524_287

    // ── Identity (immutable after construction) ─────────────────────────

    /** LOD level: 4 (root) down to 0 (leaf / block-resolution). */
    public final int level;

    /** WorldSection X coordinate at this LOD level. */
    public final int wsX;

    /** WorldSection Y coordinate at this LOD level. */
    public final int wsY;

    /** WorldSection Z coordinate at this LOD level. */
    public final int wsZ;

    /**
     * Packed key for deduplication.  Encodes level + coordinates so that
     * tasks at different LOD levels sharing numeric coordinates don't
     * collide.
     */
    public final long wsKey;

    /**
     * Which octant of the parent this task occupies (0-7), or {@code -1}
     * for root tasks (L4) that have no parent.
     */
    public final int octant;

    // ── Priority ────────────────────────────────────────────────────────

    /**
     * Distance-based priority — lower = higher urgency.  Volatile so it
     * can be updated by the scheduler when the player moves.
     */
    volatile int priority;

    /**
     * True when this task's XZ footprint is adjacent to a fully-generated
     * vanilla chunk.  Inherited by children.  Reduces priority by
     * {@value #VANILLA_BORDER_BOOST} so Voxy LODs fill in at the seam
     * between generated and ungenerated terrain first.
     */
    public volatile boolean nearVanilla;

    /** True when this task touches the current processed frontier. */
    public volatile boolean nearProcessed;

    /** Priority reduction applied to tasks adjacent to loaded vanilla chunks. */
    static final int VANILLA_BORDER_BOOST = 12;

    /**
     * Priority reduction applied to tasks next to any already-processed
     * section (vanilla or generated).  As completed tasks propagate the
     * frontier outward the boost cascades ring-by-ring, ensuring voxels
     * closest to the rendered boundary are visited first.
     */
    static final int PROCESSED_BORDER_BOOST = 12;


    // ── Mutable state ───────────────────────────────────────────────────

    /** Current pipeline state (lock-free). */
    private final AtomicReference<State> state = new AtomicReference<>(State.PENDING);

    /**
     * Column conditioning data for this section's footprint at this level.
     * Contains heightmap (5 channels, 32×32) and biome (32×32) data
     * scaled to match the section's geographic footprint.
     * Set before enqueue, read by inference workers.
     */
    public volatile OctreeColumnContext columnContext;

    /**
     * Parent's block predictions for the octant this task occupies,
     * extracted from the parent's 32³ output and upsampled 2× to 32³ via
     * nearest-neighbor.  Stored as a flat {@code long[32 * 32 * 32]}
     * containing argmax block IDs as int64 (the ONNX model's baked-in
     * embedding does the lookup).
     *
     * <p>{@code null} for L4 root tasks (no parent).
     */
    public volatile long[] parentContextFlat;

    /** Failure reason (set when state = FAILED). */
    public volatile String failureMessage;

    // ── Construction ────────────────────────────────────────────────────

    /**
     * Create a new octree task.
     *
     * @param level    LOD level (0-4)
     * @param wsX      WorldSection X at this level
     * @param wsY      WorldSection Y at this level
     * @param wsZ      WorldSection Z at this level
     * @param octant   parent octant (0-7), or -1 for root
     * @param priority distance-based priority (lower = closer)
     */
    public OctreeTask(int level, int wsX, int wsY, int wsZ,
                      int octant, int priority) {
        this.level    = level;
        this.wsX      = wsX;
        this.wsY      = wsY;
        this.wsZ      = wsZ;
        this.octant   = octant;
        this.priority = priority;
        this.wsKey    = packKey(level, wsX, wsY, wsZ);
    }

    // ── Key packing ─────────────────────────────────────────────────────

    /**
     * Pack level + section coordinates into a single long for deduplication.
     *
     * <p>Layout: {@code [3 bits level][20 bits x][20 bits y][20 bits z]}
     * — supports the signed range {@code [MIN_COORD, MAX_COORD]} per axis.
     *
     * <p>Coordinates are encoded with a bias ({@link #COORD_BIAS}) so the
     * representable signed range maps to the unsigned 20-bit range
     * {@code [0, 1_048_575]}. Inputs outside this range throw an
     * {@link IllegalArgumentException} rather than silently colliding.
     */
    public static long packKey(int level, int x, int y, int z) {
        if (level < 0 || level > 7) {
            throw new IllegalArgumentException("level out of range for packKey: " + level);
        }
        if (x < MIN_COORD || x > MAX_COORD) {
            throw new IllegalArgumentException("x out of range for packKey: " + x);
        }
        if (y < MIN_COORD || y > MAX_COORD) {
            throw new IllegalArgumentException("y out of range for packKey: " + y);
        }
        if (z < MIN_COORD || z > MAX_COORD) {
            throw new IllegalArgumentException("z out of range for packKey: " + z);
        }

        int bx = x + COORD_BIAS;
        int by = y + COORD_BIAS;
        int bz = z + COORD_BIAS;

        return ((long) level << 60)
             | ((long) bx << 40)
             | ((long) by << 20)
             | (long) bz;
    }

    // ── State transitions ───────────────────────────────────────────────

    /** Get current state. */
    public State state() { return state.get(); }

    /**
     * Atomically transition from PENDING → PROCESSING.
     * @return true if transition succeeded
     */
    public boolean claimForProcessing() {
        return state.compareAndSet(State.PENDING, State.PROCESSING);
    }

    /** Mark as complete (inference done, written to Voxy). */
    public void markReady() {
        this.parentContextFlat = null; // free intermediate data
        state.set(State.READY);
    }

    /** Mark as failed with a reason. */
    public void markFailed(String message) {
        this.failureMessage = message;
        this.parentContextFlat = null; // free intermediate data
        state.set(State.FAILED);
    }

    /**
     * Cancel this task if it is still PENDING.  Cancelled tasks are
     * silently skipped by workers (claimForProcessing returns false).
     *
     * @return true if the task was PENDING and is now CANCELLED
     */
    public boolean cancel() {
        return state.compareAndSet(State.PENDING, State.CANCELLED);
    }

    /** @return true if this task has been cancelled. */
    public boolean isCancelled() {
        return state.get() == State.CANCELLED;
    }

    // ── Priority ────────────────────────────────────────────────────────

    /**
     * Update this task's priority based on current player position.
     *
     * <p>The player position is expressed in 16-block chunk coordinates
     * ({@code blockX >> 4}).  World sections at level L cover
     * {@code 32 × 2^L} blocks, so we shift by {@code level + 1} to
     * convert the player position into the same coordinate space as
     * this task's {@code wsX / wsZ}.
     *
     * @param playerSectionX player chunk X ({@code blockX >> 4})
     * @param playerSectionZ player chunk Z ({@code blockZ >> 4})
     */
    public void updatePriority(int playerSectionX, int playerSectionZ) {
        int playerAtLevel_X = WorldSectionCoord.sectionToWorldSection(playerSectionX, level);
        int playerAtLevel_Z = WorldSectionCoord.sectionToWorldSection(playerSectionZ, level);
        this.priority = Math.abs(wsX - playerAtLevel_X)
                      + Math.abs(wsZ - playerAtLevel_Z)
                      - (nearVanilla ? VANILLA_BORDER_BOOST : 0)
                      - (nearProcessed ? PROCESSED_BORDER_BOOST : 0);
    }

    /**
     * Update priority using a direction-weighted distance, biasing toward
     * the player's heading.
     *
     * @param playerSectionX player chunk X ({@code blockX >> 4})
     * @param playerSectionZ player chunk Z ({@code blockZ >> 4})
     * @param headingX       normalised heading X component
     * @param headingZ       normalised heading Z component
     * @param coneStrength   bias strength (0=pure Manhattan, 1=aggressive cone)
     */
    public void updateDirectionalPriority(int playerSectionX, int playerSectionZ,
                                           float headingX, float headingZ,
                                           float coneStrength) {
        int playerAtLevel_X = WorldSectionCoord.sectionToWorldSection(playerSectionX, level);
        int playerAtLevel_Z = WorldSectionCoord.sectionToWorldSection(playerSectionZ, level);
        int dx = wsX - playerAtLevel_X;
        int dz = wsZ - playerAtLevel_Z;
        int manhattan = Math.abs(dx) + Math.abs(dz);
        float dot = dx * headingX + dz * headingZ;
        float directionalPenalty = -dot * coneStrength;
        this.priority = manhattan + Math.round(directionalPenalty)
                      - (nearVanilla ? VANILLA_BORDER_BOOST : 0)
                      - (nearProcessed ? PROCESSED_BORDER_BOOST : 0);
    }

    // ── Comparable (priority queue ordering) ────────────────────────────

    /**
     * Natural ordering: lower priority value = closer to player = higher urgency.
     */
    @Override
    public int compareTo(OctreeTask other) {
        return Integer.compare(this.priority, other.priority);
    }

    @Override
    public String toString() {
        return "OctreeTask[L" + level + " (" + wsX + "," + wsY + "," + wsZ
                + ") oct=" + octant + " pri=" + priority
                + (nearVanilla ? " VB" : "")
                + (nearProcessed ? " PB" : "")
                + " " + state.get() + "]";
    }

    // ── Coordinate utilities (delegated to WorldSectionCoord) ────────────

    /** @see WorldSectionCoord#childX(int, int) */
    public static int childX(int parentX, int octant) {
        return WorldSectionCoord.childX(parentX, octant);
    }

    /** @see WorldSectionCoord#childY(int, int) */
    public static int childY(int parentY, int octant) {
        return WorldSectionCoord.childY(parentY, octant);
    }

    /** @see WorldSectionCoord#childZ(int, int) */
    public static int childZ(int parentZ, int octant) {
        return WorldSectionCoord.childZ(parentZ, octant);
    }

    /** @see WorldSectionCoord#octantIndex(int, int, int) */
    public static int octantIndex(int lx, int ly, int lz) {
        return WorldSectionCoord.octantIndex(lx, ly, lz);
    }
}
