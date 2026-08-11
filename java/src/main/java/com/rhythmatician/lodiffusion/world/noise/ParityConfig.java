package com.rhythmatician.lodiffusion.world.noise;

/**
 * Configuration for the shadow-mode parity validation between vanilla (CPU)
 * and GPU noise backends.
 *
 * <p>All values are immutable after construction.  Loaded from the
 * {@code "parity"} config block in {@code lodiffusion.defaults.json} /
 * {@code runtime.json} via {@link com.rhythmatician.lodiffusion.Config}.
 *
 * @see ParityReporter
 * @see ShadowValidatingSampler
 */
public record ParityConfig(
        /**
         * Fraction of sections to validate (0.0 = none, 1.0 = all).
         * Default: 0.10 (10%).
         */
        double samplingRate,

        /**
         * Number of sections in each rolling-stats aggregation window.
         * A summary line is logged at the end of every window.
         * Default: 1000.
         */
        int aggregationWindow,

        /**
         * Log level for parity output.
         * <ul>
         *   <li>{@code PER_SECTION} — one log line per compared section</li>
         *   <li>{@code SUMMARY} — one line per aggregation window</li>
         *   <li>{@code QUIET} — only log threshold violations</li>
         * </ul>
         */
        LogLevel logLevel,

        // ── Per-field absolute error thresholds ──────────────────────
        /** Climate fields (TEMPERATURE, VEGETATION, CONTINENTS, EROSION, RIDGES). */
        float climateThreshold,
        /** DEPTH and PRELIMINARY_SURFACE_LEVEL. */
        float depthThreshold,
        /** FINAL_DENSITY. */
        float densityThreshold,
        /** Aquifer fields (BARRIER, FLOOD, SPREAD, LAVA). */
        float aquiferThreshold,
        /** Ore vein fields (VEIN_TOGGLE, VEIN_RIDGED, VEIN_GAP). */
        float oreThreshold,
        /** Minimum fraction of cells where FINAL_DENSITY sign must agree. */
        float densitySignAgreementMin
) {

    /** Controls how much parity data is logged. */
    public enum LogLevel {
        PER_SECTION,
        SUMMARY,
        QUIET
    }

    /**
     * Conservative defaults suitable for initial GPU validation.
     */
    public static ParityConfig defaults() {
        return new ParityConfig(
                /* samplingRate            */ 0.10,
                /* aggregationWindow       */ 1000,
                /* logLevel                */ LogLevel.SUMMARY,
                /* climateThreshold        */ 0.01f,
                /* depthThreshold          */ 0.05f,
                /* densityThreshold        */ 0.10f,
                /* aquiferThreshold        */ 0.05f,
                /* oreThreshold            */ 0.05f,
                /* densitySignAgreementMin */ 0.995f
        );
    }

    /**
     * Return the per-field error threshold for the given {@link RouterField}.
     */
    public float thresholdFor(RouterField field) {
        return switch (field) {
            case TEMPERATURE, VEGETATION, CONTINENTS, EROSION, RIDGES
                    -> climateThreshold;
            case DEPTH, PRELIMINARY_SURFACE_LEVEL
                    -> depthThreshold;
            case FINAL_DENSITY
                    -> densityThreshold;
            case BARRIER, FLUID_LEVEL_FLOODEDNESS, FLUID_LEVEL_SPREAD, LAVA
                    -> aquiferThreshold;
            case VEIN_TOGGLE, VEIN_RIDGED, VEIN_GAP
                    -> oreThreshold;
        };
    }
}
