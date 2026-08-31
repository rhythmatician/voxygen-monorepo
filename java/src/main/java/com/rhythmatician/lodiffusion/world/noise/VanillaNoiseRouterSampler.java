package com.rhythmatician.lodiffusion.world.noise;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;

/**
 * CPU-side {@link NoiseRouterSampler} that evaluates all 15 {@link RouterField}s
 * directly via the vanilla Fabric/Yarn {@code DensityFunction} API.
 *
 * <p>This is the <b>reference baseline</b>: results are bit-exact with what
 * vanilla Minecraft would compute at the same (x, y, z) positions.  It requires
 * the integrated server to be running (singleplayer / LAN) and a valid
 * {@link NoiseConfig}.
 *
 * <h2>Sampling strategy</h2>
 * All 15 fields are sampled at <b>quart resolution</b> for a single 16³ section.
 * Vanilla uses cellWidth=4 (4-block quart spacing on X/Z) and cellHeight=8
 * (8-block cell spacing on Y), yielding 4×2×4 cells per section:
 * <pre>
 *   qx ∈ [0, 3]  →  blockX = sectionX * 16 + qx * 4 + 2   (cell centre)
 *   qy ∈ [0, 1]  →  blockY = sectionY * 16 + qy * 8 + 4   (cell centre)
 *   qz ∈ [0, 3]  →  blockZ = sectionZ * 16 + qz * 4 + 2   (cell centre)
 * </pre>
 *
 * <p>Each field is evaluated via
 * {@link DensityFunction#sample(DensityFunction.NoisePos)} with an
 * {@link DensityFunction.UnblendedNoisePos}.  No caching beyond what
 * vanilla internally performs (flatCache, cacheOnce, etc.) is applied;
 * those in-graph caching wrappers activate automatically.
 *
 * @see NoiseRouterSampler
 * @see SectionNoiseData
 */
public final class VanillaNoiseRouterSampler implements NoiseRouterSampler {

    private final NoiseConfig noiseConfig;
    private final RegistryKey<World> dimension;

    /**
     * Lazily resolved and cached density functions for the 15 router fields.
     * Index matches {@link RouterField#ordinal()}.
     */
    private volatile DensityFunction[] resolvedFunctions;

    /**
     * @param noiseConfig the server's NoiseConfig (never null)
     */
    public VanillaNoiseRouterSampler(NoiseConfig noiseConfig) {
        this(noiseConfig, null);
    }

    /**
     * @param noiseConfig the server's NoiseConfig (never null)
     * @param dimension   bound dimension; null means Overworld (legacy/test), non-Overworld fails closed
     */
    public VanillaNoiseRouterSampler(NoiseConfig noiseConfig, RegistryKey<World> dimension) {
        this.noiseConfig = noiseConfig;
        this.dimension = dimension;
    }

    /**
     * Test-only constructor that injects resolved functions directly without requiring
     * a live {@link NoiseConfig}. Avoids mocking Minecraft registry classes in headless tests.
     *
     * @param functions pre-resolved 15 density functions (index matches {@link RouterField#ordinal()})
     * @param dimension bound dimension; null means Overworld
     */
    VanillaNoiseRouterSampler(DensityFunction[] functions, RegistryKey<World> dimension, boolean direct) {
        this.noiseConfig = null;
        this.dimension = dimension;
        this.resolvedFunctions = functions.clone();
    }

    @Override
    public SectionNoiseData sampleSection(int sectionX, int sectionY, int sectionZ) {
        ensureSupportedDimension();
        DensityFunction[] dfs = getResolvedFunctions();
        float[] flat = new float[SectionNoiseData.FLAT_LENGTH];

        int baseX = sectionX * 16;
        int baseY = sectionY * 16;
        int baseZ = sectionZ * 16;

        int flatIdx = 0;
        for (int field = 0; field < RouterField.COUNT; field++) {
            DensityFunction df = dfs[field];
            for (int qx = 0; qx < 4; qx++) {
                int x = baseX + qx * 4 + 2;  // cell centre
                for (int qy = 0; qy < 2; qy++) {
                    int y = baseY + qy * 8 + 4;  // cell centre (cellHeight=8)
                    for (int qz = 0; qz < 4; qz++) {
                        int z = baseZ + qz * 4 + 2;  // cell centre
                        flat[flatIdx++] = (float) df.sample(
                                new DensityFunction.UnblendedNoisePos(x, y, z));
                    }
                }
            }
        }

        return new SectionNoiseData(flat, sectionX, sectionY, sectionZ);
    }

    @Override
    public String backendName() {
        return "vanilla_cpu";
    }

    // ── Overworld-only validation ───────────────────────────────────────

    private void ensureSupportedDimension() {
        if (dimension == null) return; // legacy/test path treated as Overworld
        Identifier id = dimension.getValue();
        if (!id.equals(Identifier.of("minecraft", "overworld"))) {
            throw new UnsupportedOperationException(
                    "SectionNoiseData Overworld-only lattice (4×2×4 → 480 floats, spacing 4/8/4) "
                    + "not supported for dimension " + id
                    + " — supported: " + SectionNoiseData.SUPPORTED_DIMENSION
                    + ". Nether/End must not use the Overworld sampler; see NoiseRouterSamplerFactory.");
        }
    }

    // ── internals ─────────────────────────────────────────────────────

    /**
     * Resolve (once) all 15 {@link DensityFunction} handles from the
     * {@link NoiseRouter}.
     */
    private DensityFunction[] getResolvedFunctions() {
        if (resolvedFunctions != null) return resolvedFunctions;
        synchronized (this) {
            if (resolvedFunctions != null) return resolvedFunctions;

            NoiseRouter router = noiseConfig.getNoiseRouter();
            DensityFunction[] dfs = new DensityFunction[RouterField.COUNT];

            for (RouterField field : RouterField.values()) {
                dfs[field.ordinal()] = switch (field) {
                    case TEMPERATURE              -> router.temperature();
                    case VEGETATION               -> router.vegetation();
                    case CONTINENTS               -> router.continents();
                    case EROSION                  -> router.erosion();
                    case DEPTH                    -> router.depth();
                    case RIDGES                   -> router.ridges();
                    case PRELIMINARY_SURFACE_LEVEL -> router.preliminarySurfaceLevel();
                    case FINAL_DENSITY            -> router.finalDensity();
                    case BARRIER                  -> router.barrierNoise();
                    case FLUID_LEVEL_FLOODEDNESS  -> router.fluidLevelFloodednessNoise();
                    case FLUID_LEVEL_SPREAD       -> router.fluidLevelSpreadNoise();
                    case LAVA                     -> router.lavaNoise();
                    case VEIN_TOGGLE              -> router.veinToggle();
                    case VEIN_RIDGED              -> router.veinRidged();
                    case VEIN_GAP                 -> router.veinGap();
                };
            }

            resolvedFunctions = dfs;
            HelloTerrainMod.LOGGER.info(
                    "[VanillaNoiseRouterSampler] Resolved {} density functions",
                    RouterField.COUNT);
            return dfs;
        }
    }
}
