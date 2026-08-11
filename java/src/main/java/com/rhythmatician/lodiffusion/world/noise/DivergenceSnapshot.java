package com.rhythmatician.lodiffusion.world.noise;

/**
 * Spatial divergence snapshot between CPU (reference) and GPU (candidate)
 * noise backends for a single section.
 *
 * <p>Rather than collapsing divergence into aggregate metrics that hide
 * spatial failure modes (boundary drift, cave discontinuities, vertical
 * bias), this captures <b>per-cell divergence fields</b> that reveal
 * <i>where</i> the GPU diverges, not just <i>how much</i>.
 *
 * <h2>Included fields</h2>
 * <ul>
 *   <li>{@link #densityError} — {@code CPU_density - GPU_density} at each cell</li>
 *   <li>{@link #solidMismatchMask} — cells where {@code sign(CPU) != sign(GPU)}</li>
 *   <li>{@link #perFieldError} — abs error for all 15 router fields × 32 cells</li>
 * </ul>
 *
 * <h2>Future extensions</h2>
 * <ul>
 *   <li>{@code caveMaskMismatch} — when cave carving is promoted from first-order</li>
 *   <li>{@code biomeMismatch} — biome ID disagreement at quart resolution</li>
 *   <li>{@code inputDivergence} — per-channel router field error (isolates
 *       upstream sampling errors from downstream modeling errors)</li>
 * </ul>
 *
 * @see ParityReporter
 * @see ShadowValidatingSampler
 */
public record DivergenceSnapshot(
        /** Section coordinates. */
        int sectionX, int sectionY, int sectionZ,

        /**
         * Per-cell density error: {@code ref[FINAL_DENSITY] - cand[FINAL_DENSITY]}.
         * Shape: {@code float[4 * 2 * 4]} = 32 cells.
         * Positive = CPU denser than GPU, negative = GPU denser.
         */
        float[] densityError,

        /**
         * Boolean mask: {@code true} where solid/air sign differs.
         * Shape: {@code boolean[32]}.
         * A true value at cell {@code i} means the CPU and GPU disagree
         * on whether the block is solid or air at that position.
         */
        boolean[] solidMismatchMask,

        /**
         * Per-field absolute error for all 15 RouterField channels.
         * Shape: {@code float[15 * 32]} = 480 floats, same layout as
         * {@link SectionNoiseData#flat()}.
         */
        float[] perFieldError,

        /** Number of solid/air mismatches in this section. */
        int solidMismatchCount,

        /** Max absolute density error in this section. */
        float maxDensityError,

        /** Mean absolute density error in this section. */
        float meanDensityError
) {

    /** Number of spatial cells per field (4×2×4). */
    public static final int CELLS = SectionNoiseData.CELLS_PER_FIELD;

    /**
     * Build a divergence snapshot by comparing reference and candidate data.
     *
     * @param ref  reference (vanilla CPU) section data
     * @param cand candidate (GPU) section data
     * @return divergence snapshot
     */
    public static DivergenceSnapshot compute(SectionNoiseData ref, SectionNoiseData cand) {
        int densityBase = RouterField.FINAL_DENSITY.ordinal() * CELLS;
        float[] densityErr = new float[CELLS];
        boolean[] solidMask = new boolean[CELLS];
        int mismatchCount = 0;
        float maxErr = 0f;
        double sumErr = 0.0;

        for (int c = 0; c < CELLS; c++) {
            float r = ref.flat()[densityBase + c];
            float g = cand.flat()[densityBase + c];
            float err = r - g;
            densityErr[c] = err;
            float absErr = Math.abs(err);
            if (absErr > maxErr) maxErr = absErr;
            sumErr += absErr;

            boolean refSolid = r > 0;
            boolean candSolid = g > 0;
            if (refSolid != candSolid) {
                solidMask[c] = true;
                mismatchCount++;
            }
        }

        // Per-field absolute error (all 15 channels)
        float[] allFieldErr = new float[RouterField.COUNT * CELLS];
        for (int i = 0; i < allFieldErr.length; i++) {
            allFieldErr[i] = Math.abs(ref.flat()[i] - cand.flat()[i]);
        }

        return new DivergenceSnapshot(
                ref.sectionX(), ref.sectionY(), ref.sectionZ(),
                densityErr,
                solidMask,
                allFieldErr,
                mismatchCount,
                maxErr,
                (float) (sumErr / CELLS)
        );
    }

    /**
     * Extract the error field for a specific {@link RouterField}.
     *
     * @param field the router field
     * @return {@code float[32]} absolute error values
     */
    public float[] fieldError(RouterField field) {
        float[] out = new float[CELLS];
        int base = field.ordinal() * CELLS;
        System.arraycopy(perFieldError, base, out, 0, CELLS);
        return out;
    }

    /** Solid/air agreement ratio for this section (1.0 = perfect). */
    public float solidAirAgreement() {
        return 1.0f - (float) solidMismatchCount / CELLS;
    }
}
