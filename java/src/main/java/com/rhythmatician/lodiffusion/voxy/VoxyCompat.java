package com.rhythmatician.lodiffusion.voxy;

/**
 * Thin facade over the Voxy compatibility layer.
 *
 * <p>All implementation has been split across three focused classes:
 * <ul>
 *   <li>{@link VoxyDetection} — classpath detection and the {@code isAvailable()} flag.</li>
 *   <li>{@link VoxyEngine}    — engine-level reflection bindings and section operations
 *       ({@code createEmptySection}, {@code getMapper}, {@code mipSection},
 *       {@code insertUpdate}, {@code sectionExists}, {@code getWorldEngine},
 *       {@code getOrCreateWorldEngine}).</li>
 *   <li>{@link VoxyWorldBinding} — direct {@code WorldSection} writes, voxel encoding
 *       constants, and world-level helpers ({@code writeAtLevel}, {@code sectionExistsAtLevel},
 *       {@code setSectionPosition}, {@code getSectionData}, {@code setNonAirCount},
 *       {@code composeVoxel}, {@code isAir}, {@code l0Index}).</li>
 * </ul>
 *
 * <p>This facade exists solely so that all existing callers ({@code VoxySectionWriter},
 * {@code LodGenerationService}, etc.) continue to compile and run without modification.
 */
/**
 * @deprecated Prefer {@link VoxelVolumeWriter} seam; this class is retained only
 * for internal {@link RealVoxyVolumeWriter} delegation and will be removed.
 */
@Deprecated
public final class VoxyCompat {

    private VoxyCompat() {}

    // ------------------------------------------------------------------ //
    //  Detection
    // ------------------------------------------------------------------ //

    /** @see VoxyDetection#isAvailable() */
    public static boolean isAvailable() {
        return VoxyDetection.isAvailable();
    }

    // ------------------------------------------------------------------ //
    //  Engine operations
    // ------------------------------------------------------------------ //

    /** @see VoxyEngine#createEmptySection() */
    public static Object createEmptySection() {
        return VoxyEngine.createEmptySection();
    }

    /** @see VoxyEngine#getMapper(Object) */
    public static Object getMapper(Object worldEngine) {
        return VoxyEngine.getMapper(worldEngine);
    }

    /** @see VoxyEngine#mipSection(Object, Object) */
    public static void mipSection(Object section, Object mapper) {
        VoxyEngine.mipSection(section, mapper);
    }

    /** @see VoxyEngine#insertUpdate(Object, Object) */
    public static void insertUpdate(Object worldEngine, Object section) {
        VoxyEngine.insertUpdate(worldEngine, section);
    }

    /** @see VoxyEngine#sectionExists(Object, int, int, int) */
    public static boolean sectionExists(Object worldEngine,
                                         int sectionX, int sectionY, int sectionZ) {
        return VoxyEngine.sectionExists(worldEngine, sectionX, sectionY, sectionZ);
    }

    /** @see VoxyEngine#getWorldEngine(net.minecraft.world.World) */
    public static Object getWorldEngine(net.minecraft.world.World world) {
        return VoxyEngine.getWorldEngine(world);
    }

    /** @see VoxyEngine#getOrCreateWorldEngine(net.minecraft.world.World) */
    public static Object getOrCreateWorldEngine(net.minecraft.world.World world) {
        return VoxyEngine.getOrCreateWorldEngine(world);
    }

    // ------------------------------------------------------------------ //
    //  VoxelizedSection field access
    // ------------------------------------------------------------------ //

    /** @see VoxyWorldBinding#setSectionPosition(Object, int, int, int) */
    public static void setSectionPosition(Object section, int x, int y, int z) {
        VoxyWorldBinding.setSectionPosition(section, x, y, z);
    }

    /** @see VoxyWorldBinding#getSectionData(Object) */
    public static long[] getSectionData(Object section) {
        return VoxyWorldBinding.getSectionData(section);
    }

    /** @see VoxyWorldBinding#setNonAirCount(Object, int) */
    public static void setNonAirCount(Object section, int count) {
        VoxyWorldBinding.setNonAirCount(section, count);
    }

    // ------------------------------------------------------------------ //
    //  Voxel encoding constants (forwarded from VoxyWorldBinding)
    // ------------------------------------------------------------------ //

    public static final int  BLOCK_ID_SHIFT = VoxyWorldBinding.BLOCK_ID_SHIFT;
    public static final int  BLOCK_ID_BITS  = VoxyWorldBinding.BLOCK_ID_BITS;
    public static final long BLOCK_ID_MASK  = VoxyWorldBinding.BLOCK_ID_MASK;

    public static final int  BIOME_ID_SHIFT = VoxyWorldBinding.BIOME_ID_SHIFT;
    public static final int  BIOME_ID_BITS  = VoxyWorldBinding.BIOME_ID_BITS;
    public static final long BIOME_ID_MASK  = VoxyWorldBinding.BIOME_ID_MASK;

    public static final int  LIGHT_SHIFT    = VoxyWorldBinding.LIGHT_SHIFT;

    /** @see VoxyWorldBinding#composeVoxel(int, int, int) */
    public static long composeVoxel(int blockId, int biomeId, int light) {
        return VoxyWorldBinding.composeVoxel(blockId, biomeId, light);
    }

    /** @see VoxyWorldBinding#isAir(long) */
    public static boolean isAir(long voxel) {
        return VoxyWorldBinding.isAir(voxel);
    }

    /** @see VoxyWorldBinding#l0Index(int, int, int) */
    public static int l0Index(int x, int y, int z) {
        return VoxyWorldBinding.l0Index(x, y, z);
    }

    // ------------------------------------------------------------------ //
    //  Direct WorldSection level writes
    // ------------------------------------------------------------------ //

    /** @see VoxyWorldBinding#writeAtLevel(Object, int, int, int, int, long[]) */
    public static int writeAtLevel(Object worldEngine, int lvl,
                                    int sectionX, int sectionY, int sectionZ,
                                    long[] voxels) {
        return VoxyWorldBinding.writeAtLevel(worldEngine, lvl, sectionX, sectionY, sectionZ, voxels);
    }

    /** @see VoxyWorldBinding#writeFullWorldSection(Object, int, int, int, int, long[]) */
    public static int writeFullWorldSection(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ,
                                             long[] voxels) {
        return VoxyWorldBinding.writeFullWorldSection(worldEngine, lvl, wsX, wsY, wsZ, voxels);
    }

    /** @see VoxyWorldBinding#sectionExistsAtLevel(Object, int, int, int, int) */
    public static boolean sectionExistsAtLevel(Object worldEngine, int lvl,
                                                int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.sectionExistsAtLevel(worldEngine, lvl, wsX, wsY, wsZ);
    }

    /**
     * Returns {@code true} if Voxy has fully claimed all 8 octants
     * ({@code nonEmptyChildren == 0xFF}).  Use this as the upstream inference
     * guard: if all octants are already populated by real chunk data, there is
     * nothing for LODiffusion to contribute so the task can be skipped entirely.
     *
     * @see VoxyWorldBinding#allOctantsPopulated(Object, int, int, int, int)
     */
    public static boolean allOctantsPopulated(Object worldEngine, int lvl,
                                               int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.allOctantsPopulated(worldEngine, lvl, wsX, wsY, wsZ);
    }

    // ── Save-queue monitoring ──────────────────────────────────────────────

    /**
     * Returns the number of pending save tasks in Voxy's {@code SectionSavingService},
     * or {@code -1} if the queue depth cannot be determined.
     *
     * @see VoxyEngine#getSaveQueueDepth(Object)
     */
    public static int getSaveQueueDepth(Object worldEngine) {
        return VoxyEngine.getSaveQueueDepth(worldEngine);
    }
}
