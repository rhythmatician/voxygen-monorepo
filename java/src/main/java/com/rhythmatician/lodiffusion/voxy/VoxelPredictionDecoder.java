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
     * @param argmax int[32][32][32] in Y,Z,X order (as from OctreeModelRunner.OctreeOutput)
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
     * Vertical column palette for fallback decoding.
     * Bundles the 12 canonical IDs the fallback terrain uses so they
     * travel as one domain type rather than 12 loose ints.
     */
    public record FallbackPalette(
            int air,
            int stone,
            int deepslate,
            int dirt,
            int grassBlock,
            int sand,
            int water,
            int redSand,
            int gravel,
            int snowLayer,
            int podzol,
            int mycelium) {
        static FallbackPalette defaults() {
            return new FallbackPalette(
                    0, 923, 319, 343, 400, 855, 1018, 825, 401, 893, 703, 593);
        }
    }

    /**
     * Decode fallback per-column data into a 16^3 semantic volume (SectionPos).
     *
     * @param sectionY section Y
     * @param rawHm [16][16] water surface heightmap indexed [x][z]
     * @param oceanFloorHm [16][16] floor or null indexed [x][z]
     * @param biomeIdx [16][16] canonical biome indexed [x][z]
     */
    public static VoxelVolume fromFallback(int sectionY, float[][] rawHm, float[][] oceanFloorHm, int[][] biomeIdx) {
        return fromFallback(sectionY, rawHm, oceanFloorHm, biomeIdx, FallbackPalette.defaults());
    }

    public static VoxelVolume fromFallback(
            int sectionY, float[][] rawHm, float[][] oceanFloorHm, int[][] biomeIdx, FallbackPalette palette) {
        int baseY = sectionY * 16;
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float waterSurfaceY = rawHm[lx][lz];
                float groundY = oceanFloorHm != null ? oceanFloorHm[lx][lz] : waterSurfaceY;
                int wBlockY = (int) Math.floor(waterSurfaceY);
                int gBlockY = (int) Math.floor(groundY);
                int canonBiome = biomeIdx[lx][lz];
                HeightmapFallbackGenerator.SurfaceType st =
                        HeightmapFallbackGenerator.surfaceTypeForBiome(canonBiome);
                for (int ly = 0; ly < 16; ly++) {
                    int worldY = baseY + ly;
                    int canonBlock = pickCanonical(worldY, gBlockY, wBlockY, st, palette);
                    b.setBlock(lx, ly, lz, canonBlock);
                    int bio = CanonicalRegistries.isValidBiomeId(canonBiome)
                            ? canonBiome
                            : CanonicalRegistries.BIOME_UNKNOWN;
                    b.setBiome(lx, ly, lz, bio);
                }
            }
        }
        return b.build();
    }

    private static int pickCanonical(
            int worldY,
            int groundBlockY,
            int waterSurfaceBlockY,
            HeightmapFallbackGenerator.SurfaceType surfaceType,
            FallbackPalette p) {
        if (worldY >= groundBlockY) {
            if (worldY < waterSurfaceBlockY) return p.water();
            if (worldY >= HeightmapFallbackGenerator.SEA_LEVEL) {
                if (worldY == groundBlockY && surfaceType == HeightmapFallbackGenerator.SurfaceType.SNOW) {
                    return p.snowLayer();
                }
                return p.air();
            }
            return p.water();
        } else if (worldY >= groundBlockY - 3) {
            int depth = groundBlockY - 1 - worldY;
            boolean underwater = groundBlockY < waterSurfaceBlockY;
            return switch (surfaceType) {
                case SAND -> p.sand();
                case RED_SAND -> p.redSand();
                case GRAVEL -> p.gravel();
                case STONE -> p.stone();
                case SNOW -> (depth == 0 && !underwater) ? p.grassBlock() : p.dirt();
                case PODZOL -> (depth == 0 && !underwater) ? p.podzol() : p.dirt();
                case MYCELIUM -> (depth == 0 && !underwater) ? p.mycelium() : p.dirt();
                default -> (depth == 0 && !underwater) ? p.grassBlock() : p.dirt();
            };
        } else if (worldY < 0) {
            return p.deepslate();
        } else {
            return p.stone();
        }
    }
}
