package com.rhythmatician.lodiffusion.voxy;

/**
 * Pre-sampled conditioning data for a single 32×32 octree section footprint.
 *
 * <p>Unlike the existing {@link LodGenerationService.ColumnContext} which is
 * fixed at 16×16 (one chunk), octree sections have a 32×32 voxel grid at
 * each level, where each voxel represents a different number of blocks
 * depending on the LOD level:
 * <ul>
 *   <li>L0: 32× 1 = 32 blocks  → tile 2 chunks</li>
 *   <li>L1: 32× 2 = 64 blocks  → pool 4 chunks</li>
 *   <li>L2: 32× 4 = 128 blocks → pool 8 chunks</li>
 *   <li>L3: 32× 8 = 256 blocks → pool 16 chunks</li>
 *   <li>L4: 32×16 = 512 blocks → pool 32 chunks</li>
 * </ul>
 *
 * <p>The heightmap and biome data are resampled (pooled or tiled) from
 * the vanilla world data to produce 32×32 grids at the appropriate scale.
 *
 * @param heightmap5  height planes {@code float[5][32][32]} — 5-channel
 *                    heightmap encoding (surface, min, max, mean, std or
 *                    similar channels depending on the model contract).
 *                    Indexed as [channel][row][col] in z-major order
 *                    (matching the ONNX model's expected layout).
 * @param biomeIdx    biome indices {@code int[32][32]} — canonical biome IDs
 *                    at this footprint resolution.  Indexed [row][col].
 * @param rawHm       raw surface heights {@code float[32][32]} in block Y,
 *                    used for HEIGHTMAP_CLIP (clamping predictions above
 *                    the surface to air).  May be {@code null} if clip is
 *                    disabled.
 */
public record OctreeColumnContext(
    float[][][] heightmap5,   // [5][32][32]
    int[][]     biomeIdx,     // [32][32]
    float[][]   rawHm         // [32][32]
) {

    /**
     * Flatten heightmap5 to a 1-D array in ONNX input order:
     * {@code [5 * 32 * 32]} with layout {@code [ch][z][x]} matching
     * the model's expected {@code float32[N, 5, 32, 32]} input.
     *
     * <p>The caller transposes x↔z during flattening if the source data
     * is x-major.  This method assumes the data is already in z-major
     * order (matching training convention).
     */
    public float[] flattenHeightmap() {
        float[] flat = new float[5 * 32 * 32];
        flattenHeightmapInto(flat);
        return flat;
    }

    /**
     * Fill a pre-allocated array with the flattened heightmap data.
     * The array must have length {@code 5 * 32 * 32 = 5120}.
     * This variant avoids array allocation for performance-sensitive paths.
     *
     * @param dst pre-allocated destination array of length 5120
     */
    public void flattenHeightmapInto(float[] dst) {
        int idx = 0;
        for (int ch = 0; ch < 5; ch++) {
            for (int r = 0; r < 32; r++) {
                System.arraycopy(heightmap5[ch][r], 0, dst, idx, 32);
                idx += 32;
            }
        }
    }

    /**
     * Flatten biome indices to a 1-D {@code long[]} in ONNX input order:
     * {@code [32 * 32]} with layout {@code [z][x]} matching the model's
     * expected {@code int64[N, 32, 32]} input.
     */
    public long[] flattenBiome() {
        long[] flat = new long[32 * 32];
        flattenBiomeInto(flat);
        return flat;
    }

    /**
     * Fill a pre-allocated array with the flattened biome data.
     * The array must have length {@code 32 * 32 = 1024}.
     * This variant avoids array allocation for performance-sensitive paths.
     *
     * @param dst pre-allocated destination array of length 1024
     */
    public void flattenBiomeInto(long[] dst) {
        int idx = 0;
        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                dst[idx++] = biomeIdx[r][c];
            }
        }
    }
}
