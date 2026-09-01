package com.rhythmatician.voxygen.semantic.biome;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;

/**
 * Computes the v5 anchor conditioning inputs from the Minecraft world.
 *
 * <p>The v5 octree contract requires:
 * <ul>
 *   <li>{@code heightmap} [N, 5, 32, 32] float32 — 5-plane heightmap
 *       (surface, ocean_floor, slope_x, slope_z, curvature), normalised</li>
 *   <li>{@code biome} [N, 32, 32] int64 — biome index per column</li>
 *   <li>{@code y_position} [N] int64 — vertical slab position</li>
 * </ul>
 *
 * <p>Router6 (temperature, vegetation, continentalness, erosion, depth, ridges)
 * was dropped entirely — biome + heightmap already encode the relevant
 * information.  See {@code docs/NOISE-DESIGN.md} for rationale.
 *
 * <p>This class retains the 16×16 sampling helpers used by the heightmap
 * fallback pipeline and the sparse-octree column builder.
 */
public final class AnchorSampler {

    /** MC sea level — used to normalise heights. */
    private static final float SEA_LEVEL = 62f;
    /** Typical raw height range for normalisation. */
    private static final float HEIGHT_RANGE = 320f;

    private AnchorSampler() {}

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Container for anchor inputs for one 16×16 chunk column. */
    public record AnchorInputs(
        float[][]  heightPlanes5,  // [5][16] row-major (will be reshaped to [5,4,4]) — density-cell resolution
        int[][]    biomeIdx,       // [16][16]
        float[][]  rawHm,          // [16][16] surface block-Y
        float[][]  oceanFloorHm    // [16][16] ocean/river floor block-Y (may be null)
    ) {}

    /**
     * Sample anchor inputs using the server-side noise pipeline.
     *
     * <p>Produces real heightmap and biome data for any (sectionX, sectionZ)
     * coordinate without needing a loaded chunk.
     *
     * @param noiseAccess server-side noise access (must not be null)
     * @param sectionX    chunk / section X coordinate
     * @param sectionZ    chunk / section Z coordinate
     * @return an {@link AnchorInputs} record with real world-gen data
     */
    public static AnchorInputs sampleFromNoise(WorldNoiseAccess noiseAccess,
                                                int sectionX, int sectionZ) {
        // 1. Real heightmaps — single ChunkNoiseSampler pass yields both
        //    WORLD_SURFACE_WG and OCEAN_FLOOR_WG (~64× faster than 256
        //    individual getHeight() calls).
        float[][][] bothHmaps = noiseAccess.sampleBothHeightmaps(sectionX, sectionZ);
        float[][] hmap         = bothHmaps[0];  // WORLD_SURFACE_WG
        float[][] oceanFloorHm = bothHmaps[1];  // OCEAN_FLOOR_WG

        // 2. Real biomes from BiomeSource → canonical IDs
        String[][] biomeNames = noiseAccess.sampleBiomeNames(sectionX, sectionZ, hmap);
        int[][] biomeIdx = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                biomeIdx[x][z] = BiomeMapping.toCanonicalId(biomeNames[x][z]);
            }
        }

        // 3. Height-planes use BOTH surface and ocean-floor heightmaps (matches Python)
        float[][] heightPlanes = computeHeightPlanes(hmap, oceanFloorHm);

        return new AnchorInputs(heightPlanes, biomeIdx, hmap, oceanFloorHm);
    }

    // ------------------------------------------------------------------
    // Biome sampling
    // ------------------------------------------------------------------

    /**
     * Extract a [16][16] biome integer-index grid for the chunk.
     * Uses the surface-level biome at each column (y=64).
     *
     * <p>Uses {@link BiomeMapping#toCanonicalId} for stable, deterministic
     * encoding that matches the Python training pipeline.
     */
    public static int[][] sampleBiomes(Chunk chunk) {
        int[][] out = new int[16][16];
        if (chunk == null) return out;  // default 0

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Sample biome at sea level as representative
                RegistryEntry<Biome> biomeEntry = chunk.getBiomeForNoiseGen(lx >> 2, 4, lz >> 2);
                out[lx][lz] = BiomeMapping.toCanonicalId(biomeEntry);
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // Heightmap extraction
    // ------------------------------------------------------------------

    /**
     * Extract the raw WORLD_SURFACE heightmap [16][16] in block-Y coordinates.
     */
    public static float[][] sampleHeightmap(Chunk chunk) {
        float[][] hm = new float[16][16];
        if (chunk == null) {
            // Default flat terrain at sea level
            for (int x = 0; x < 16; x++)
                for (int z = 0; z < 16; z++)
                    hm[x][z] = SEA_LEVEL;
            return hm;
        }

        var heightmap = chunk.getHeightmap(Heightmap.Type.WORLD_SURFACE);
        for (int lx = 0; lx < 16; lx++)
            for (int lz = 0; lz < 16; lz++)
                hm[lx][lz] = heightmap.get(lx, lz);
        return hm;
    }

    // ------------------------------------------------------------------
    // Height-planes computation
    // ------------------------------------------------------------------

    /**
     * Derive the 5-plane height feature tensor from a raw heightmap.
     *
     * <p>The raw 16×16 block-resolution heightmap is first downsampled to 4×4
     * density-cell resolution (stride 4, matching vanilla's
     * {@code horizontalCellBlockCount = 4}).  The 16×16 heightmap contains only
     * ~4×4 independent samples; intermediate values are trilinear interpolation
     * of cell-corner densities.
     *
     * <p>Planes:
     * <ol>
     *   <li>surface         — normalised block-Y / 320</li>
     *   <li>ocean_floor     — real OCEAN_FLOOR_WG / 320 (or clamped approx if unavailable)</li>
     *   <li>slope_x         — finite difference dH/dx normalised</li>
     *   <li>slope_z         — finite difference dH/dz normalised</li>
     *   <li>curvature       — Laplacian (d²H/dx² + d²H/dz²), normalised</li>
     * </ol>
     *
     * @param hm           surface heightmap [16][16] in block-Y
     * @param oceanFloorHm ocean floor heightmap [16][16] in block-Y, or {@code null}
     *                     to fall back to {@code min(surface, SEA_LEVEL)} approximation
     * @return float[5][16] in row-major order (channel, cx*4+cz) at 4×4 density-cell resolution
     */
    public static float[][] computeHeightPlanes(float[][] hm, float[][] oceanFloorHm) {
        // Density-cell resolution: 4×4 (stride 4 from 16×16 block grid)
        final int S = 4;   // grid side
        final int STRIDE = 4;  // block stride matching horizontalCellBlockCount
        float[][] planes = new float[5][S * S];

        // Step 1: Downsample 16×16 → 4×4 and normalise surface + ocean floor
        float[][] surfNorm = new float[S][S];
        for (int cx = 0; cx < S; cx++) {
            for (int cz = 0; cz < S; cz++) {
                int bx = cx * STRIDE;
                int bz = cz * STRIDE;
                float h = hm[bx][bz];
                surfNorm[cx][cz] = h / HEIGHT_RANGE;
                planes[0][cx * S + cz] = surfNorm[cx][cz];                // surface

                float of = (oceanFloorHm != null)
                        ? oceanFloorHm[bx][bz]
                        : Math.min(h, SEA_LEVEL);
                planes[1][cx * S + cz] = of / HEIGHT_RANGE;              // ocean_floor
            }
        }

        // Step 2: slope_x = central/one-sided difference on 4×4 normalised surface
        float[][] slopeX = new float[S][S];
        for (int cx = 0; cx < S; cx++) {
            for (int cz = 0; cz < S; cz++) {
                if (cx == 0) {
                    slopeX[cx][cz] = surfNorm[1][cz] - surfNorm[0][cz];
                } else if (cx == S - 1) {
                    slopeX[cx][cz] = surfNorm[S - 1][cz] - surfNorm[S - 2][cz];
                } else {
                    slopeX[cx][cz] = (surfNorm[cx + 1][cz] - surfNorm[cx - 1][cz]) / 2f;
                }
                planes[2][cx * S + cz] = slopeX[cx][cz];
            }
        }

        // Step 3: slope_z = central/one-sided difference on 4×4 normalised surface
        float[][] slopeZ = new float[S][S];
        for (int cx = 0; cx < S; cx++) {
            for (int cz = 0; cz < S; cz++) {
                if (cz == 0) {
                    slopeZ[cx][cz] = surfNorm[cx][1] - surfNorm[cx][0];
                } else if (cz == S - 1) {
                    slopeZ[cx][cz] = surfNorm[cx][S - 1] - surfNorm[cx][S - 2];
                } else {
                    slopeZ[cx][cz] = (surfNorm[cx][cz + 1] - surfNorm[cx][cz - 1]) / 2f;
                }
                planes[3][cx * S + cz] = slopeZ[cx][cz];
            }
        }

        // Step 4: curvature = Laplacian on 4×4 grid
        for (int cx = 0; cx < S; cx++) {
            for (int cz = 0; cz < S; cz++) {
                float dsx;
                if (cx == 0) {
                    dsx = slopeX[1][cz] - slopeX[0][cz];
                } else if (cx == S - 1) {
                    dsx = slopeX[S - 1][cz] - slopeX[S - 2][cz];
                } else {
                    dsx = (slopeX[cx + 1][cz] - slopeX[cx - 1][cz]) / 2f;
                }
                float dsz;
                if (cz == 0) {
                    dsz = slopeZ[cx][1] - slopeZ[cx][0];
                } else if (cz == S - 1) {
                    dsz = slopeZ[cx][S - 1] - slopeZ[cx][S - 2];
                } else {
                    dsz = (slopeZ[cx][cz + 1] - slopeZ[cx][cz - 1]) / 2f;
                }
                planes[4][cx * S + cz] = dsx + dsz;
            }
        }

        return planes;
    }

}

