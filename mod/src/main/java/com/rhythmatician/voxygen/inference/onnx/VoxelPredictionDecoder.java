package com.rhythmatician.voxygen.inference.onnx;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.output.VoxelVolumeWriter;
import com.rhythmatician.voxygen.worldgen.heightmap.HeightmapFallbackGenerator;

/**
 * Inference-boundary decoder: model outputs (argmax / heightmap fallback)
 * into semantic {@link VoxelVolume}.
 *
 * <p>Only place that understands model output layout, argmax ordering,
 * and heightmap-to-block mapping. The {@link VoxelVolumeWriter} never
 * touches logits or raw heightmaps.
 */
public final class VoxelPredictionDecoder {

    private VoxelPredictionDecoder() {}

    /**
     * Decode a 32-cubed argmax volume (YZX order from ONNX) into canonical
     * {@link VoxelVolume} extent 32. Mirrors {@link com.rhythmatician.lodiffusion.onnx.VoxyModelRunner}
     * Y,Z,X contract: argmax[y][z][x].
     *
     * @param argmax int[32][32][32] in Y,Z,X order
     * @param biomeIdx32 int[32][32] canonical biome indices indexed [z][x]
     * @return semantic volume with XYZ access (extent 32)
     */
    public static VoxelVolume fromOctreeArgmax(int[][][] argmax, int[][] biomeIdx32) {
        if (argmax == null || biomeIdx32 == null) {
            throw new NullPointerException("argmax and biomeIdx32 must not be null");
        }
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
     * Decode raw float logits [V][Y][Z][X] via argmax into semantic
     * {@link VoxelVolume} extent 32. Convenience overload when caller
     * has not yet argmaxed.
     *
     * @param logits float[V][32][32][32] in [channel][Y][Z][X] order
     * @param biomeIdx32 int[32][32] canonical biome indices indexed [z][x]
     */
    public static VoxelVolume fromLogits(float[][][][] logits, int[][] biomeIdx32) {
        if (logits == null || logits.length == 0 || biomeIdx32 == null) {
            throw new NullPointerException("logits and biomeIdx32 must not be null");
        }
        int vocab = logits.length;
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int best = 0;
                    float bestVal = logits[0][y][z][x];
                    for (int c = 1; c < vocab; c++) {
                        float v = logits[c][y][z][x];
                        if (v > bestVal) {
                            bestVal = v;
                            best = c;
                        }
                    }
                    int canonBiome = biomeIdx32[z][x];
                    if (!CanonicalRegistries.isValidBiomeId(canonBiome)) {
                        canonBiome = CanonicalRegistries.BIOME_UNKNOWN;
                    }
                    if (!CanonicalRegistries.isValidBlockId(best)) {
                        best = CanonicalRegistries.BLOCK_AIR;
                    }
                    b.setBlock(x, y, z, best);
                    b.setBiome(x, y, z, canonBiome);
                }
            }
        }
        return b.build();
    }

    // ------------------------------------------------------------------
    // Fallback heightmap
    // ------------------------------------------------------------------

    /**
     * Canonical block IDs for fallback terrain, mirroring the training vocab.
     * Verified against {@code python/config/voxy_vocab.json}.
     */
    public static final class FallbackPalette {
        private FallbackPalette() {}

        public static FallbackPalette defaults() {
            return new FallbackPalette();
        }

        public int air() { return 0; }
        public int stone() { return 923; }
        public int deepslate() { return 319; }
        public int dirt() { return 343; }
        public int grassBlock() { return 400; }
        public int sand() { return 855; }
        public int water() { return 1018; }
        public int redSand() { return 825; }
        public int gravel() { return 401; }
        public int snowLayer() { return 893; }
        public int podzol() { return 703; }
        public int mycelium() { return 593; }
    }

    /**
     * Decode per-column heightmap/biome data into a 16-cubed semantic
     * {@link VoxelVolume} at given section Y. Uses same rules as
     * {@link HeightmapFallbackGenerator#pickBlockId} but operates on
     * canonical IDs.
     *
     * @param sectionY section Y coordinate (section = blockY >> 4)
     * @param rawHm [16][16] surface heightmap in block Y, indexed [x][z]
     * @param oceanFloorHm [16][16] floor or null
     * @param biomeIdx [16][16] canonical biome indices indexed [x][z]
     * @return semantic 16^3 volume at sectionY
     */
    public static VoxelVolume fromFallback(int sectionY,
                                           float[][] rawHm,
                                           float[][] oceanFloorHm,
                                           int[][] biomeIdx) {
        if (rawHm == null || biomeIdx == null) {
            throw new NullPointerException("rawHm and biomeIdx must not be null");
        }
        int baseY = sectionY * 16;
        FallbackPalette p = FallbackPalette.defaults();
        int air = p.air();
        int stone = p.stone();
        int deepslate = p.deepslate();
        int dirt = p.dirt();
        int grass = p.grassBlock();
        int sand = p.sand();
        int water = p.water();
        int redSand = p.redSand();
        int gravel = p.gravel();
        int snowLayer = p.snowLayer();
        int podzol = p.podzol();
        int mycelium = p.mycelium();

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
                    int canonBlock = pickCanonical(
                            worldY, gBlockY, wBlockY, st,
                            air, stone, deepslate, dirt, grass, sand,
                            water, redSand, gravel, snowLayer, podzol, mycelium);
                    int bio = CanonicalRegistries.isValidBiomeId(canonBiome)
                            ? canonBiome : CanonicalRegistries.BIOME_UNKNOWN;
                    b.setBlock(lx, ly, lz, canonBlock);
                    b.setBiome(lx, ly, lz, bio);
                }
            }
        }
        return b.build();
    }

    private static int pickCanonical(int worldY, int groundBlockY, int waterSurfaceBlockY,
                                     HeightmapFallbackGenerator.SurfaceType surfaceType,
                                     int air, int stone, int deepslate, int dirt, int grass,
                                     int sand, int water, int redSand, int gravel,
                                     int snowLayer, int podzol, int mycelium) {
        if (worldY >= groundBlockY) {
            if (worldY < waterSurfaceBlockY) {
                return water;
            }
            if (worldY >= HeightmapFallbackGenerator.SEA_LEVEL) {
                if (worldY == groundBlockY
                        && surfaceType == HeightmapFallbackGenerator.SurfaceType.SNOW) {
                    return snowLayer;
                }
                return air;
            }
            return water;
        }
        if (worldY >= groundBlockY - 3) {
            int depth = groundBlockY - 1 - worldY;
            boolean underwater = groundBlockY < waterSurfaceBlockY;
            return switch (surfaceType) {
                case SAND -> sand;
                case RED_SAND -> redSand;
                case GRAVEL -> gravel;
                case STONE -> stone;
                case SNOW -> (depth == 0 && !underwater) ? snowLayer : dirt;
                case PODZOL -> (depth == 0 && !underwater) ? podzol : dirt;
                case MYCELIUM -> (depth == 0 && !underwater) ? mycelium : dirt;
                default -> (depth == 0 && !underwater) ? grass : dirt;
            };
        } else if (worldY < 0) {
            return deepslate;
        } else {
            return stone;
        }
    }
}
