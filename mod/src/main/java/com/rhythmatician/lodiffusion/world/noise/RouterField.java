package com.rhythmatician.lodiffusion.world.noise;

/**
 * The 15 named {@code DensityFunction} fields of the vanilla {@code NoiseRouter}.
 *
 * <p>This is the <b>canonical interface boundary</b> between step 1 (noise generation)
 * and step 2 (everything else) of Minecraft terrain generation.  Every downstream
 * system&mdash;biome selection, terrain density, aquifers, ore veins, surface
 * rules&mdash;consumes one or more of these 15 fields via point queries.
 *
 * <p>The ordering here defines the channel index used in
 * {@link SectionNoiseData} tensors and the ONNX model's {@code noise_3d} input.
 *
 * @see SectionNoiseData
 * @see NoiseRouterSampler
 */
public enum RouterField {

    // ── Climate (6 fields, mostly 2D) ──────────────────────────────────
    /** Controls biome temperature axis.  2D, quart-cached. */
    TEMPERATURE,
    /** Controls biome humidity axis.  2D, quart-cached. */
    VEGETATION,
    /** Controls ocean / inland axis.  2D, quart-cached. */
    CONTINENTS,
    /** Controls terrain smoothness.  2D, quart-cached. */
    EROSION,
    /**
     * Depth below surface (Y-gradient + offset).
     * The only 3D climate parameter; also wired into {@code finalDensity}.
     */
    DEPTH,
    /** Weirdness / peaks-and-valleys.  2D, quart-cached. */
    RIDGES,

    // ── Terrain density (2 fields, 3D) ─────────────────────────────────
    /** Estimated surface Y before full density; used by surface rules &amp; aquifer. */
    PRELIMINARY_SURFACE_LEVEL,
    /**
     * The final combined terrain density.
     * Positive &rarr; solid (stone), zero/negative &rarr; air or fluid.
     */
    FINAL_DENSITY,

    // ── Aquifer (4 fields, 3D at various cell spacings) ────────────────
    /** Pressure barrier between adjacent aquifer cells. */
    BARRIER,
    /** Controls how flooded an aquifer cell is. */
    FLUID_LEVEL_FLOODEDNESS,
    /** Randomises the fluid surface within partially-flooded cells. */
    FLUID_LEVEL_SPREAD,
    /** Selects lava vs. water in deep aquifer cells. */
    LAVA,

    // ── Ore veins (3 fields, cell-interpolated / block-level) ──────────
    /** Sign selects copper vs. iron; magnitude controls density. */
    VEIN_TOGGLE,
    /** Ridged shape of ore veins (negative = inside vein). */
    VEIN_RIDGED,
    /** Gap / hole control for ore veins. */
    VEIN_GAP;

    /** Total number of NoiseRouter fields (always 15). */
    public static final int COUNT = values().length;

    /** Ordinal-indexed lookup (avoids repeated {@code values()} allocation). */
    private static final RouterField[] BY_ORDINAL = values();

    /** Return the field at the given channel index. */
    public static RouterField byIndex(int index) {
        return BY_ORDINAL[index];
    }
}
