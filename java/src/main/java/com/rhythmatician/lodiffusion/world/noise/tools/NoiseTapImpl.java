package com.rhythmatician.lodiffusion.world.noise.tools;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;

import com.rhythmatician.lodiffusion.voxy.BiomeMapping;
import it.unimi.dsi.fastutil.objects.Object2ObjectArrayMap;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.HeightLimitView;
import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * NoiseTapImpl — Minimal implementation using exact Yarn 1.21.4+ API calls.
 *
 * <p><b>Data-harvesting tool only.</b> This captures vanilla noise signals at their
 * native API granularities for training data collection:
 * <ul>
 *   <li>NoiseRouter fields via DensityFunction.sample() at block resolution</li>
 *   <li>Biomes via BiomeAccess.getBiomeForNoiseGen() at 4×4×4 lattice points</li>
 *   <li>Heightmaps via Chunk.getHeightmap() at 16×16 resolution</li>
 * </ul>
 *
 * <p>No upsampling is performed — we respect the API's native resolutions.
 *
 * @see NoiseTap
 * @see com.rhythmatician.lodiffusion.world.noise.NoiseRouterSampler NoiseRouterSampler (production pipeline)
 */
final class NoiseTapImpl implements NoiseTap {
    private final Chunk chunk;
    private final HeightLimitView heightView;
    private final NoiseRouter router;
    private final BiomeAccess biomeAccess;
    private final int minY, height;
    private final int bx0, bz0; // block origin of chunk
    private final long seed;

    NoiseTapImpl(Chunk chunk, NoiseConfig noiseConfig, BiomeAccess biomeAccess, long seed) {
        this.chunk = chunk;
        this.heightView = chunk;
        this.router = noiseConfig.getNoiseRouter(); // NoiseConfig -> NoiseRouter
        this.biomeAccess = biomeAccess;
        this.minY = heightView.getBottomY();
        this.height = heightView.getHeight();
        ChunkPos pos = chunk.getPos();
        this.bx0 = pos.getStartX();
        this.bz0 = pos.getStartZ();
        this.seed = seed;
    }

    @Override
    public Cache captureAll(EnumSet<RouterField> whichRouter,
                            EnumSet<Heightmap.Type> whichHMs) {
        // Default to the first section above minY (section 0).
        // Callers can use captureAll(whichRouter, whichHMs, sectionAnchorY)
        // to capture a specific 16-block vertical range.
        return captureAll(whichRouter, whichHMs, minY);
    }

    @Override
    public Cache captureAll(EnumSet<RouterField> whichRouter,
                            EnumSet<Heightmap.Type> whichHMs,
                            int sectionAnchorY) {
        // 1) Router fields @ 16×16×16 (block-level sampling at the requested Y section)
        Map<RouterField, float[][][]> routerMaps = new EnumMap<>(RouterField.class);
        for (RouterField f : whichRouter) {
            DensityFunction df = switch (f) {
                // Tier A - Surface & Climate Features
                case TEMPERATURE -> router.temperature();
                case VEGETATION -> router.vegetation();
                case CONTINENTS -> router.continents();
                case EROSION -> router.erosion();
                case DEPTH -> router.depth();
                case RIDGES -> router.ridges();

                // Tier C - 3D Density Features
                case INITIAL_DENSITY_NO_JAG -> router.finalDensity(); // initialDensityWithoutJaggedness removed in 1.21.11
                case FINAL_DENSITY -> router.finalDensity();

                // Tier B - Fluid & Environmental Features
                case FLUID_FLOODEDNESS -> router.fluidLevelFloodednessNoise();
                case FLUID_SPREAD -> router.fluidLevelSpreadNoise();
                case LAVA -> router.lavaNoise();
                case BARRIER -> router.barrierNoise();

                // Tier D - Vein & Ore Features
                case VEIN_TOGGLE -> router.veinToggle();
                case VEIN_RIDGED -> router.veinRidged();
                case VEIN_GAP -> router.veinGap();
            };
            routerMaps.put(f, sampleDensityFunction16(df, sectionAnchorY));
        }

        // 2) Biomes @ 4×4×4 lattice (native storage granularity since 19w36a)
        int[][][] biomes4 = sampleBiomes4x4x4();

        // 3) Heightmaps (16×16) — only those requested
        Map<Heightmap.Type, short[][]> hms = new Object2ObjectArrayMap<>();
        if (!whichHMs.isEmpty()) {
            // Ensure the chunk has heightmaps populated for these types
            Heightmap.populateHeightmaps(chunk, whichHMs);
            for (Heightmap.Type t : whichHMs) {
                hms.put(t, readHeightmap16(t));
            }
        }

        ChunkPos cpos = chunk.getPos();
        return new Cache(routerMaps, biomes4, hms,
            minY, height, cpos.x, cpos.z, seed);
    }

    /**
     * Sample a DensityFunction at 16×16×16 block resolution for this chunk.
     *
     * @param df            the DensityFunction to sample
     * @param sectionAnchorY the Y coordinate of the bottom block in the 16-block
     *                       section to sample (e.g. {@code minY} for the bottom
     *                       section, or {@code 64} to start at sea level).
     *                       Use Y values that are multiples of 16 for section-aligned sampling.
     */
    private float[][][] sampleDensityFunction16(DensityFunction df, int sectionAnchorY) {
        float[][][] out = new float[16][16][16];
        for (int lx = 0; lx < 16; lx++) {
            int x = bx0 + lx;
            for (int lz = 0; lz < 16; lz++) {
                int z = bz0 + lz;
                for (int ly = 0; ly < 16; ly++) {
                    int y = sectionAnchorY + ly;
                    double v = df.sample(new DensityFunction.UnblendedNoisePos(x, y, z));
                    out[lx][lz][ly] = (float)v;
                }
            }
        }
        return out;
    }

    /**
     * Sample biomes at 4×4×4 lattice points (native storage since 19w36a).
     * Uses BiomeAccess.getBiomeForNoiseGen() - the noise-space biome sampler.
     */
    private int[][][] sampleBiomes4x4x4() {
        int[][][] out = new int[4][4][4];
        // Sample noise-space biome points (4×4×4 per chunk section)
        // Block->Biome coords quantize to 4-block steps
        for (int qx = 0; qx < 4; qx++) {
            int bx = bx0 + qx * 4;
            for (int qz = 0; qz < 4; qz++) {
                int bz = bz0 + qz * 4;
                for (int qy = 0; qy < 4; qy++) {
                    int by = minY + qy * 4;
                    var entry = biomeAccess.getBiomeForNoiseGen(bx, by, bz);
                    // Use canonical biome ID (0-53 for overworld biomes, 255 for unknown)
                    // from BiomeMapping, which provides a stable, bounded, non-negative ID.
                    out[qx][qz][qy] = BiomeMapping.toCanonicalId(entry);
                }
            }
        }
        return out;
    }

    /**
     * Read heightmap data at native 16×16 resolution.
     * Uses Chunk.getHeightmap(type).get(x,z) - direct heightmap access.
     */
    private short[][] readHeightmap16(Heightmap.Type type) {
        short[][] hm = new short[16][16];
        var h = chunk.getHeightmap(type);
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                hm[lx][lz] = (short) h.get(lx, lz); // Y in block coordinates
            }
        }
        return hm;
    }
}
