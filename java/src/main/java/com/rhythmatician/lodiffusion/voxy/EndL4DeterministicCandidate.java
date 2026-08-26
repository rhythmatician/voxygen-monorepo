package com.rhythmatician.lodiffusion.voxy;

/**
 * Model-free deterministic End base-terrain producer for Render L0..L4.
 *
 * <p><b>Deterministic approximation — centre-sample rasterization.</b>
 * Converts cell occupancy from density by a single centre-sample at the
 * centre of each active voxel ({@code 2^level} blocks per axis, matching
 * Voxy's node geometry: node = {@code 32<<level} blocks, always 32³ voxels)
 * where the voxel overlaps the End responsibility {@code Y [0,128)}.
 * Honestly labeled approximation: thin features or edge occupancy within a
 * voxel may vanish if the centre sample lies outside the solid. The caveat
 * strengthens at finer Levels (per the map's Stage 2 fidelity note).
 * Validated diagnostically against a bounded any-solid oracle; the
 * oracle is not a gate.
 *
 * <p><b>Honest omission:</b> base density != final geometry. Placed features
 * (obsidian pillars, gateways, etc.) are omitted and recorded as honest
 * omission per Worldgen Partition v1 / Profile-Inactive vs Omit, not glossed
 * as collapsed to {@code end_stone}. This producer covers base-terrain only.
 *
 * <p>Produces semantic {@link VoxelVolume} extent 32 aligned to the requested
 * {@link Level} ({@code regionSections()} sections per axis). Y outside
 * {@code [0,128)} is air-padded. Vocabulary is reduced to
 * {@code air | end_stone}; biome is {@link CanonicalRegistries#BIOME_UNKNOWN}
 * (tracer-only rendering concession translates it to plains in
 * {@link RealVoxyVolumeWriter}).
 *
 * <p>Package-private, no public SPI.
 */
final class EndL4DeterministicCandidate {

    static final int BLOCK_AIR = CanonicalRegistries.BLOCK_AIR;
    static final int BLOCK_END_STONE = 359; // canonical minecraft:end_stone
    static final int EXTENT = 32;
    static final int END_MIN_Y = 0;
    static final int END_MAX_Y = 128;

    private final WorldNoiseAccess noiseAccess;

    EndL4DeterministicCandidate(WorldNoiseAccess noiseAccess) {
        if (noiseAccess == null) {
            throw new NullPointerException("noiseAccess");
        }
        this.noiseAccess = noiseAccess;
    }

    /**
     * Deterministic approximation: centre-sample rasterization against the
     * End [0,128) responsibility at the requested Level. Thin-occupancy
     * caveat: a voxel is marked solid only if its centre density &gt; 0;
     * thin sheets not covering the centre are missed, increasingly so at
     * finer Levels. Honest omission of placed features (pillars, gateways)
     * is intentional and recorded; do not gloss as end_stone.
     *
     * @param level  Level L0..L4
     * @param origin SectionPos aligned to {@code level.regionSections()}
     * @return VoxelVolume extent 32 with air|end_stone, air-padded outside [0,128)
     */
    VoxelVolume produceRegion(Level level, SectionPos origin) {
        if (level == null || level.value() < Level.L0.value() || level.value() > Level.L4.value()) {
            throw new IllegalArgumentException(
                    "End scaffold supports L0..L4 only, got " + level);
        }
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level
                    + " regionSections=" + level.regionSections());
        }
        // Blocks per voxel at this Level (Voxy node geometry: node = 32<<L
        // blocks, always 32^3 voxels): L4=16, L3=8, L2=4, L1=2, L0=1.
        int voxelBlocks = 1 << level.value();
        int centreOffset = voxelBlocks / 2;

        VoxelVolume.Builder b = VoxelVolume.builder(EXTENT);
        // Default is already air + BIOME_UNKNOWN; only active voxels are sampled.
        int baseBlockX = origin.x() << 4;
        int baseBlockY = origin.y() << 4;
        int baseBlockZ = origin.z() << 4;

        for (int y = 0; y < EXTENT; y++) {
            int y0 = baseBlockY + y * voxelBlocks;
            int y1 = y0 + voxelBlocks;
            boolean activeY = y0 < END_MAX_Y && y1 > END_MIN_Y;
            if (!activeY) {
                continue;
            }
            for (int z = 0; z < EXTENT; z++) {
                for (int x = 0; x < EXTENT; x++) {
                    int cx = baseBlockX + x * voxelBlocks + centreOffset;
                    int cy = y0 + centreOffset;
                    int cz = baseBlockZ + z * voxelBlocks + centreOffset;
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
