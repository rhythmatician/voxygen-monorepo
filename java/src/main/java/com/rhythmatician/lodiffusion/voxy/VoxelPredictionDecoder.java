package com.rhythmatician.lodiffusion.voxy;

/**
 * Inference-boundary decoder: model outputs (argmax / logits) -&gt; semantic {@link VoxelVolume}.
 *
 * <p>Only place that understands model output layout (axis order, argmax, logits).
 * Writer never touches logits.
 */
public final class VoxelPredictionDecoder {
    private VoxelPredictionDecoder() {}

    /**
     * Decode a 32^3 argmax volume (YZX order from ONNX) into a canonical {@link VoxelVolume} extent 32.
     *
     * @param argmax int[32][32][32] in Y,Z,X order (as from {@link com.rhythmatician.lodiffusion.onnx.OctreeModelRunner.OctreeOutput})
     * @param biomeIdx32 int[32][32] canonical biome indices indexed [z][x]
     * @return semantic volume with XYZ access (extent 32)
     */
    public static VoxelVolume fromOctreeArgmax(int[][][] argmax, int[][] biomeIdx32) {
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int canonBlock = argmax[y][z][x];
                    int canonBiome = biomeIdx32[z][x];
                    // Validate or map unknown -> BIOME_UNKNOWN already valid; clamp invalid biome to unknown
                    if (!CanonicalRegistries.isValidBiomeId(canonBiome)) {
                        canonBiome = CanonicalRegistries.BIOME_UNKNOWN;
                    }
                    if (!CanonicalRegistries.isValidBlockId(canonBlock)) {
                        canonBlock = CanonicalRegistries.BLOCK_AIR;
                    }
                    b.setBlock(x, y, z, canonBlock);
                    b.setBiome(x, y, z, canonBiome);
                }
            }
        }
        return b.build();
    }

    /**
     * Decode fallback per-column data into a 16^3 semantic volume (SectionPos).
     * Reuses {@link HeightmapFallbackGenerator} pick logic but writes into {@link VoxelVolume} instead of packed longs.
     *
     * @param sectionY section Y
     * @param rawHm [16][16] water surface heightmap indexed [x][z]
     * @param oceanFloorHm [16][16] floor or null indexed [x][z]
     * @param biomeIdx [16][16] canonical biome indexed [x][z]
     * @param baseY sectionY*16 handled internally; caller passes sectionY for worldY calc
     */
    public static VoxelVolume fromFallback(int sectionY, float[][] rawHm, float[][] oceanFloorHm, int[][] biomeIdx) {
        int baseY = sectionY * 16;
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        // Need to map HeightmapFallbackGenerator's VOXY block ids to canonical -> we directly use canonical IDs
        // Derive canonical fallback ids (same mapping as RealVoxyVolumeWriter.buildFallbackBlockMap reverse)
        // Use voxy_vocab.json constants:
        int AIR = 0, STONE = 923, DEEPSLATE = 319, DIRT = 343, GRASS = 400, SAND = 855, WATER = 1018,
                RED_SAND = 825, GRAVEL = 401, SNOW_LAYER = 893, PODZOL = 703, MYCELIUM = 593;
        // snowy grass uses grass_block canonical as well; handled via surfaceType
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float waterSurfaceY = rawHm[lx][lz];
                float groundY = oceanFloorHm != null ? oceanFloorHm[lx][lz] : waterSurfaceY;
                int wBlockY = (int) Math.floor(waterSurfaceY);
                int gBlockY = (int) Math.floor(groundY);
                int canonBiome = biomeIdx[lx][lz];
                HeightmapFallbackGenerator.SurfaceType st = HeightmapFallbackGenerator.surfaceTypeForBiome(canonBiome);
                for (int ly = 0; ly < 16; ly++) {
                    int worldY = baseY + ly;
                    int canonBlock = pickCanonical(worldY, gBlockY, wBlockY, st, AIR, STONE, DEEPSLATE, DIRT, GRASS, SAND, WATER, RED_SAND, GRAVEL, SNOW_LAYER, PODZOL, MYCELIUM);
                    b.setBlock(lx, ly, lz, canonBlock);
                    // Validate biome, keep as canonical
                    int bio = CanonicalRegistries.isValidBiomeId(canonBiome) ? canonBiome : CanonicalRegistries.BIOME_UNKNOWN;
                    b.setBiome(lx, ly, lz, bio);
                }
            }
        }
        return b.build();
    }

    // Mirrors HeightmapFallbackGenerator.pickBlockId but with canonical IDs instead of Voxy IDs
    private static int pickCanonical(int worldY, int groundBlockY, int waterSurfaceBlockY,
                                     HeightmapFallbackGenerator.SurfaceType surfaceType,
                                     int AIR, int STONE, int DEEPSLATE, int DIRT, int GRASS, int SAND, int WATER,
                                     int RED_SAND, int GRAVEL, int SNOW_LAYER, int PODZOL, int MYCELIUM) {
        if (worldY >= groundBlockY) {
            if (worldY < waterSurfaceBlockY) return WATER;
            if (worldY >= HeightmapFallbackGenerator.SEA_LEVEL) {
                if (worldY == groundBlockY && surfaceType == HeightmapFallbackGenerator.SurfaceType.SNOW) return SNOW_LAYER;
                return AIR;
            }
            return WATER;
        } else if (worldY >= groundBlockY - 3) {
            int depth = groundBlockY - 1 - worldY;
            boolean underwater = groundBlockY < waterSurfaceBlockY;
            return switch (surfaceType) {
                case SAND -> SAND;
                case RED_SAND -> RED_SAND;
                case GRAVEL -> GRAVEL;
                case STONE -> STONE;
                case SNOW -> (depth == 0 && !underwater) ? GRASS : DIRT; // snowy grass maps to grass canonical
                case PODZOL -> (depth == 0 && !underwater) ? PODZOL : DIRT;
                case MYCELIUM -> (depth == 0 && !underwater) ? MYCELIUM : DIRT;
                default -> (depth == 0 && !underwater) ? GRASS : DIRT;
            };
        } else if (worldY < 0) {
            return DEEPSLATE;
        } else {
            return STONE;
        }
    }
}
