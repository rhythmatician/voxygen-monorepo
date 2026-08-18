package com.rhythmatician.lodiffusion.voxy;

/**
 * Model-free deterministic End base-terrain producer for Render L4 only.
 *
 * <p><b>Deterministic approximation — centre-sample rasterization.</b>
 * Converts the 16^3-block L4 cell occupancy from density by a single
 * centre-sample at {@code (x0+8, y0+8, z0+8)} for each active voxel where
 * {@code [y0, y0+16)} overlaps the End responsibility {@code Y [0,128)}.
 * Honestly labeled approximation: thin features or edge occupancy within a
 * 16-block voxel may vanish if the centre sample lies outside the solid.
 * Validated diagnostically against a bounded any-solid oracle
 * (L4 solid iff any of 4096 blocks solid) to quantify disagreement; the
 * oracle is not a gate. 8192 evaluations per 512^3 L4 region
 * (32 * 8 * 32 active voxels).
 *
 * <p><b>Honest omission:</b> base density != final geometry. Placed features
 * (obsidian pillars, gateways, etc.) are omitted and recorded as honest
 * omission per Worldgen Partition v1 / Profile-Inactive vs Omit, not glossed
 * as collapsed to {@code end_stone}. This tracer covers base-terrain only.
 *
 * <p>Produces semantic {@link VoxelVolume} extent 32 (512 blocks at 16
 * blocks/voxel) aligned to {@link Level#L4} ({@code regionSections()=32}).
 * Y outside {@code [0,128)} is air-padded (8 active slices, 24 air).
 * Vocabulary is reduced to {@code air | end_stone}; biome is
 * {@link CanonicalRegistries#BIOME_UNKNOWN} (tracer-only rendering concession
 * translates it to plains in {@link RealVoxyVolumeWriter}).
 *
 * <p>Package-private, no public SPI.
 */
final class EndL4DeterministicCandidate {

    static final int BLOCK_AIR = CanonicalRegistries.BLOCK_AIR;
    static final int BLOCK_END_STONE = 359; // canonical minecraft:end_stone
    static final int EXTENT = 32;
    static final int VOXEL_BLOCKS = 16;
    static final int END_MIN_Y = 0;
    static final int END_MAX_Y = 128;
    static final int ACTIVE_Y_SLICES = 8;

    private final WorldNoiseAccess noiseAccess;

    EndL4DeterministicCandidate(WorldNoiseAccess noiseAccess) {
        if (noiseAccess == null) {
            throw new NullPointerException("noiseAccess");
        }
        this.noiseAccess = noiseAccess;
    }

    /**
     * Deterministic approximation: centre-sample L4 rasterization against the
     * End [0,128) responsibility. Thin-occupancy caveat: a voxel is marked
     * solid only if its centre density &gt; 0; thin sheets not covering the
     * centre are missed. Honest omission of placed features (pillars, gateways)
     * is intentional and recorded; do not gloss as end_stone.
     *
     * @param origin SectionPos aligned to Level.L4 (regionSections()=32)
     * @param level must be Level.L4
     * @return VoxelVolume extent 32 with air|end_stone, air-padded outside [0,128)
     */
    VoxelVolume produceRegion(Level level, SectionPos origin) {
        if (level != Level.L4) {
            throw new IllegalArgumentException("EndL4DeterministicCandidate only supports L4, got " + level);
        }
        if (!Level.L4.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to L4 regionSections=" + Level.L4.regionSections());
        }
        VoxelVolume.Builder b = VoxelVolume.builder(EXTENT);
        // Default is already air + BIOME_UNKNOWN; only active voxels are sampled.
        int baseBlockX = origin.x() << 4;
        int baseBlockY = origin.y() << 4;
        int baseBlockZ = origin.z() << 4;

        for (int y = 0; y < EXTENT; y++) {
            int y0 = baseBlockY + y * VOXEL_BLOCKS;
            int y1 = y0 + VOXEL_BLOCKS;
            boolean activeY = y0 < END_MAX_Y && y1 > END_MIN_Y;
            if (!activeY) {
                continue;
            }
            for (int z = 0; z < EXTENT; z++) {
                for (int x = 0; x < EXTENT; x++) {
                    int cx = baseBlockX + x * VOXEL_BLOCKS + 8;
                    int cy = y0 + 8;
                    int cz = baseBlockZ + z * VOXEL_BLOCKS + 8;
                    double density = noiseAccess.sampleFinalDensity(cx, cy, cz);
                    int blockId = density > 0 ? BLOCK_END_STONE : BLOCK_AIR;
                    b.setBlock(x, y, z, blockId);
                    // biome stays BIOME_UNKNOWN (builder default); explicitly retain
                }
            }
        }
        return b.build();
    }

    /**
     * Convenience overload for tests: origin aligned to L4, level implied.
     */
    VoxelVolume produceL4Region(SectionPos origin) {
        return produceRegion(Level.L4, origin);
    }
}
