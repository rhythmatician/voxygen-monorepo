package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/** Exact virgin End base-terrain producer for one aligned L1 WorldSection. */
final class ExactEndL1Candidate {
    private static final int BLOCK_SPAN = 64;
    private static final int CHUNK_SPAN = 4;

    @FunctionalInterface
    interface ChunkColumnSampler {
        void sampleChunk(
                int chunkX, int chunkZ, int minY, int maxY, SolidBlockConsumer consumer);
    }

    @FunctionalInterface
    interface SolidBlockConsumer {
        void accept(int blockX, int blockY, int blockZ, boolean solid);
    }

    private final ChunkColumnSampler sampler;

    ExactEndL1Candidate(WorldNoiseAccess noiseAccess) {
        this(Objects.requireNonNull(noiseAccess, "noiseAccess")::sampleExactEndBaseTerrainChunk);
    }

    ExactEndL1Candidate(ChunkColumnSampler sampler) {
        this.sampler = Objects.requireNonNull(sampler, "sampler");
    }

    VoxelVolume produceExactL1(SectionPos origin) {
        Objects.requireNonNull(origin, "origin");
        if (!Level.L1.isAligned(origin)) {
            throw new IllegalArgumentException("origin " + origin + " is not aligned to L1");
        }

        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        int minY = Math.max(baseY, EndL4DeterministicCandidate.END_MIN_Y);
        int maxY = Math.min(baseY + BLOCK_SPAN, EndL4DeterministicCandidate.END_MAX_Y);
        if (minY >= maxY) {
            return VoxelVolume.uniform(
                    EndL4DeterministicCandidate.EXTENT,
                    EndL4DeterministicCandidate.BLOCK_AIR,
                    CanonicalRegistries.BIOME_UNKNOWN);
        }

        boolean[] solid = new boolean[EndL4DeterministicCandidate.EXTENT
                * EndL4DeterministicCandidate.EXTENT * EndL4DeterministicCandidate.EXTENT];
        SolidBlockConsumer consumer = (blockX, blockY, blockZ, occupied) -> {
            if (!occupied
                    || blockX < baseX || blockX >= baseX + BLOCK_SPAN
                    || blockY < minY || blockY >= maxY
                    || blockZ < baseZ || blockZ >= baseZ + BLOCK_SPAN) {
                return;
            }
            int x = (blockX - baseX) >> 1;
            int y = (blockY - baseY) >> 1;
            int z = (blockZ - baseZ) >> 1;
            solid[(y * EndL4DeterministicCandidate.EXTENT + z)
                    * EndL4DeterministicCandidate.EXTENT + x] = true;
        };

        for (int chunkX = origin.x(); chunkX < origin.x() + CHUNK_SPAN; chunkX++) {
            for (int chunkZ = origin.z(); chunkZ < origin.z() + CHUNK_SPAN; chunkZ++) {
                sampler.sampleChunk(chunkX, chunkZ, minY, maxY, consumer);
            }
        }

        VoxelVolume.Builder result = VoxelVolume.builder(EndL4DeterministicCandidate.EXTENT);
        for (int y = 0; y < EndL4DeterministicCandidate.EXTENT; y++) {
            for (int z = 0; z < EndL4DeterministicCandidate.EXTENT; z++) {
                for (int x = 0; x < EndL4DeterministicCandidate.EXTENT; x++) {
                    int index = (y * EndL4DeterministicCandidate.EXTENT + z)
                            * EndL4DeterministicCandidate.EXTENT + x;
                    if (solid[index]) {
                        result.setBlock(x, y, z, EndL4DeterministicCandidate.BLOCK_END_STONE);
                    }
                }
            }
        }
        return result.build();
    }
}
