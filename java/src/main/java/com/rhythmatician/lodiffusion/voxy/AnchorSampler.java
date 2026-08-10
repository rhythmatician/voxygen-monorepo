package com.rhythmatician.lodiffusion.voxy;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;

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
 * fallback pipeline and the {@code OctreeColumnContext} builder.
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
        float[][]  heightPlanes5,  // [5][256] row-major (will be reshaped to [5,16,16])
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
    static int[][] sampleBiomes(Chunk chunk) {
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
    static float[][] sampleHeightmap(Chunk chunk) {
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
     * @return float[5][256] in row-major order (channel, lx*16+lz)
     */
    static float[][] computeHeightPlanes(float[][] hm, float[][] oceanFloorHm) {
        float[][] planes = new float[5][256];

        // Step 1: Normalise surface (matches Python: surf = height / 320.0)
        float[][] surfNorm = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float h = hm[lx][lz];
                surfNorm[lx][lz] = h / HEIGHT_RANGE;
                planes[0][lx * 16 + lz] = surfNorm[lx][lz];                // surface
                // Ocean floor: use real OCEAN_FLOOR_WG data when available,
                // otherwise fall back to the min(surface, SEA_LEVEL) approximation.
                float of = (oceanFloorHm != null)
                        ? oceanFloorHm[lx][lz]
                        : Math.min(h, SEA_LEVEL);
                planes[1][lx * 16 + lz] = of / HEIGHT_RANGE;              // ocean_floor
            }
        }

        // Step 2: slope_x = np.gradient(surfNorm, axis=x)
        // Matches Python: central differences on NORMALISED surface,
        // forward/backward one-sided at boundaries (np.gradient edge_order=1).
        float[][] slopeX = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (lx == 0) {
                    slopeX[lx][lz] = surfNorm[1][lz] - surfNorm[0][lz];
                } else if (lx == 15) {
                    slopeX[lx][lz] = surfNorm[15][lz] - surfNorm[14][lz];
                } else {
                    slopeX[lx][lz] = (surfNorm[lx + 1][lz] - surfNorm[lx - 1][lz]) / 2f;
                }
                planes[2][lx * 16 + lz] = slopeX[lx][lz];
            }
        }

        // Step 3: slope_z = np.gradient(surfNorm, axis=z)
        float[][] slopeZ = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                if (lz == 0) {
                    slopeZ[lx][lz] = surfNorm[lx][1] - surfNorm[lx][0];
                } else if (lz == 15) {
                    slopeZ[lx][lz] = surfNorm[lx][15] - surfNorm[lx][14];
                } else {
                    slopeZ[lx][lz] = (surfNorm[lx][lz + 1] - surfNorm[lx][lz - 1]) / 2f;
                }
                planes[3][lx * 16 + lz] = slopeZ[lx][lz];
            }
        }

        // Step 4: curvature = np.gradient(slope_x, axis=x) + np.gradient(slope_z, axis=z)
        // This is the Laplacian computed as gradient-of-gradient, matching Python exactly.
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // d(slope_x)/dx
                float dsx;
                if (lx == 0) {
                    dsx = slopeX[1][lz] - slopeX[0][lz];
                } else if (lx == 15) {
                    dsx = slopeX[15][lz] - slopeX[14][lz];
                } else {
                    dsx = (slopeX[lx + 1][lz] - slopeX[lx - 1][lz]) / 2f;
                }
                // d(slope_z)/dz
                float dsz;
                if (lz == 0) {
                    dsz = slopeZ[lx][1] - slopeZ[lx][0];
                } else if (lz == 15) {
                    dsz = slopeZ[lx][15] - slopeZ[lx][14];
                } else {
                    dsz = (slopeZ[lx][lz + 1] - slopeZ[lx][lz - 1]) / 2f;
                }
                planes[4][lx * 16 + lz] = dsx + dsz;
            }
        }

        return planes;
    }

}

