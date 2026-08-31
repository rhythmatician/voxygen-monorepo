package com.rhythmatician.voxygen.backend.voxy;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.output.VoxelVolumeWriter;
import com.rhythmatician.voxygen.generation.scheduling.LodGenerationService;

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
 * <p>This facade keeps production writers and compatibility tooling on the same
 * low-level bindings while migration to {@link VoxelVolumeWriter} completes.
 *
 * <p><b>Seam boundary — internal migration facade.</b> The intended deep module seam is
 * {@link VoxelVolumeWriter}; new code must use that interface. This facade is retained
 * solely for backwards compatibility during migration ( {@code LoDiffusionClient},
 * {@code ShaderSectionWriter}, {@code WorldGenEventHandler}, and
 * {@code LodGenerationService} still call through here).
 * It will be reduced to package-private / removed in a follow-up PR once all callers
 * are funneled through {@link RealVoxyVolumeWriter} / {@link VoxelVolumeWriter}.
 *
 * <p><b>Internal — do not use outside {@code com.rhythmatician.lodiffusion.voxy};
 * use {@link VoxelVolumeWriter}.</b> Direct use of reflection helpers
 * ({@code l0Index}, {@code composeVoxel}, {@code writeAtLevel}, etc.) leaks
 * storage details (YZX order, nativeRes clamp, CAS + markDirty) that the seam hides.
 * If you need a Voxy write from outside the package, obtain a {@link VoxelVolumeWriter}
 * via {@link RealVoxyVolumeWriter} construction (see {@code LodGenerationService}).
 *
 * <p><b>Middle-Man justification (Fowler):</b> this class is pure delegation by design.
 * It is the <em>seam boundary</em> between the {@code voxy} deep module and the rest of
 * the codebase. Voxy types are not on the compile classpath; all Voxy access is via
 * reflection ({@link VoxyEngine}/{@link VoxyWorldBinding}). Keeping a single facade
 * entry-point preserves the deep-module abstraction and allows migration off Voxy
 * without touching callers. Not speculative - retained intentionally.
 *
 * <p>Direct use is discouraged outside the {@code voxy} package; prefer
 * {@link VoxelVolumeWriter} or the underlying focused classes.
 */
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

    /**
     * @deprecated Internal section lifecycle — use {@link VoxelVolumeWriter#writeSection}.
     * @see VoxyEngine#createEmptySection()
     */
    @Deprecated
    public static Object createEmptySection() {
        return VoxyEngine.createEmptySection();
    }

    /**
     * @deprecated Internal mapper access — hidden behind {@link RealVoxyVolumeWriter} canonical translation.
     * @see VoxyEngine#getMapper(Object)
     */
    @Deprecated
    public static Object getMapper(Object worldEngine) {
        return VoxyEngine.getMapper(worldEngine);
    }

    /**
     * @deprecated Internal mip lifecycle — hidden behind {@link VoxelVolumeWriter}.
     * @see VoxyEngine#mipSection(Object, Object)
     */
    @Deprecated
    public static void mipSection(Object section, Object mapper) {
        VoxyEngine.mipSection(section, mapper);
    }

    /**
     * @deprecated Internal insert path — use {@link VoxelVolumeWriter#writeSection}.
     * @see VoxyEngine#insertUpdate(Object, Object)
     */
    @Deprecated
    public static void insertUpdate(Object worldEngine, Object section) {
        VoxyEngine.insertUpdate(worldEngine, section);
    }

    /**
     * @deprecated Internal existence check — use {@link VoxelVolumeWriter#isRegionFullyPopulated} / writer backpressure.
     * @see VoxyEngine#sectionExists(Object, int, int, int)
     */
    @Deprecated
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

    /**
     * @deprecated Internal {@code VoxelizedSection} field access — use {@link VoxelVolumeWriter}.
     * @see VoxyWorldBinding#setSectionPosition(Object, int, int, int)
     */
    @Deprecated
    public static void setSectionPosition(Object section, int x, int y, int z) {
        VoxyWorldBinding.setSectionPosition(section, x, y, z);
    }

    /**
     * @deprecated Internal storage array access — hidden behind {@link VoxelVolumeWriter}.
     * @see VoxyWorldBinding#getSectionData(Object)
     */
    @Deprecated
    public static long[] getSectionData(Object section) {
        return VoxyWorldBinding.getSectionData(section);
    }

    /**
     * @deprecated Internal — use {@link VoxelVolumeWriter}.
     * @see VoxyWorldBinding#setNonAirCount(Object, int)
     */
    @Deprecated
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

    /**
     * @deprecated Internal packed-voxel encoding hidden behind {@link RealVoxyVolumeWriter};
     * new code works with canonical IDs via {@link VoxelVolume}.
     * @see VoxyWorldBinding#composeVoxel(int, int, int)
     */
    @Deprecated
    public static long composeVoxel(int blockId, int biomeId, int light) {
        return VoxyWorldBinding.composeVoxel(blockId, biomeId, light);
    }

    /**
     * @deprecated Internal voxel-air test hidden behind {@link VoxelVolume#isAllAir()} / writer logic.
     * @see VoxyWorldBinding#isAir(long)
     */
    @Deprecated
    public static boolean isAir(long voxel) {
        return VoxyWorldBinding.isAir(voxel);
    }

    /**
     * @deprecated Internal storage order YZX — hidden behind {@link VoxelVolumeWriter};
     * use {@link VoxelVolume} XYZ access. Internal use only.
     * @see VoxyWorldBinding#l0Index(int, int, int)
     */
    @Deprecated
    public static int l0Index(int x, int y, int z) {
        return VoxyWorldBinding.l0Index(x, y, z);
    }

    // ------------------------------------------------------------------ //
    //  Direct WorldSection level writes
    // ------------------------------------------------------------------ //

    /**
     * @deprecated Migration facade — new code use {@link VoxelVolumeWriter#writeRegion};
     * internal YZX/scale-clamp/CAS detail hidden behind {@link RealVoxyVolumeWriter}.
     * @see VoxyWorldBinding#writeAtLevel(Object, int, int, int, int, long[])
     */
    @Deprecated
    public static int writeAtLevel(Object worldEngine, int lvl,
                                    int sectionX, int sectionY, int sectionZ,
                                    long[] voxels) {
        return VoxyWorldBinding.writeAtLevel(worldEngine, lvl, sectionX, sectionY, sectionZ, voxels);
    }

    /**
     * @deprecated Migration facade — new code use {@link VoxelVolumeWriter#writeRegion}.
     * @see VoxyWorldBinding#writeFullWorldSection(Object, int, int, int, int, long[])
     */
    @Deprecated
    public static int writeFullWorldSection(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ,
                                             long[] voxels) {
        return VoxyWorldBinding.writeFullWorldSection(worldEngine, lvl, wsX, wsY, wsZ, voxels);
    }

    /** Preserve-mask variant: octants named in the mask survive candidate overwrite. */
    public static int writeFullWorldSection(Object worldEngine, int lvl,
                             int wsX, int wsY, int wsZ,
                             long[] voxels,
                             byte preserveOctantsMask) {
        return VoxyWorldBinding.writeFullWorldSection(
                worldEngine, lvl, wsX, wsY, wsZ, voxels, preserveOctantsMask);
    }

    /**
     * @deprecated Internal octree existence — hidden behind writer seam.
     * @see VoxyWorldBinding#sectionExistsAtLevel(Object, int, int, int, int)
     */
    @Deprecated
    public static boolean sectionExistsAtLevel(Object worldEngine, int lvl,
                                                int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.sectionExistsAtLevel(worldEngine, lvl, wsX, wsY, wsZ);
    }

    /**
     * @deprecated Internal WorldSection read — hidden behind writer seam.
     * @see VoxyWorldBinding#readWorldSectionBlocks(Object, int, int, int, int)
     */
    @Deprecated
    public static int[][][] readWorldSectionBlocks(Object worldEngine, int lvl,
                                                   int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.readWorldSectionBlocks(worldEngine, lvl, wsX, wsY, wsZ);
    }

    /**
     * @deprecated Internal octant upsample — hidden behind writer seam.
     * @see VoxyWorldBinding#extractOctantAndUpsample(int[][][], int)
     */
    @Deprecated
    public static long[] extractOctantAndUpsample(int[][][] parent32, int octant) {
        return VoxyWorldBinding.extractOctantAndUpsample(parent32, octant);
    }

    /**
     * @deprecated Internal NEC child mask — hidden behind writer seam.
     * @see VoxyWorldBinding#getChildExistenceMask(Object, int, int, int, int)
     */
    @Deprecated
    public static byte getChildExistenceMask(Object worldEngine, int lvl,
                                              int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.getChildExistenceMask(worldEngine, lvl, wsX, wsY, wsZ);
    }

    /**
     * @deprecated Internal occupancy scan — hidden behind writer seam.
     * @see VoxyWorldBinding#getOccupiedOctantMask(Object, int, int, int, int)
     */
    @Deprecated
    public static byte getOccupiedOctantMask(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ) {
        return VoxyWorldBinding.getOccupiedOctantMask(worldEngine, lvl, wsX, wsY, wsZ);
    }

    /**
     * Returns {@code true} if Voxy has fully claimed all 8 octants
     * ({@code nonEmptyChildren == 0xFF}).  Use this as the upstream inference
     * guard: if all octants are already populated by real chunk data, there is
     * nothing for LODiffusion to contribute so the task can be skipped entirely.
     *
     * @see VoxyWorldBinding#allOctantsPopulated(Object, int, int, int, int)
     */
    @Deprecated
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
