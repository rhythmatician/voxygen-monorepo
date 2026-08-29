package com.rhythmatician.lodiffusion.voxy;

/**
 * Per-dimension half-open Y interval [minY, maxY) plus NoiseSettings shape (noiseSizeHorizontal,
 * noiseSizeVertical) that defines which rows are vanilla-real
 * and which side effects are dead code for that dimension. Actual cell size is 8x4 for End (2,1) and 4x8 for Overworld/Nether (1,2).
 * Derived via
 * {@link WorldSectionCoord#worldSectionToBlockMin} / {@link WorldSectionCoord#worldSectionToBlockMax}.
 */
public record DimensionGenerationDomain(int minY, int maxY, int noiseSizeHorizontal, int noiseSizeVertical, boolean aquifersEnabled) {
    public static final DimensionGenerationDomain END =
            new DimensionGenerationDomain(0, 128, 2, 1, false);
    public static final DimensionGenerationDomain OVERWORLD =
            new DimensionGenerationDomain(-64, 320, 1, 2, true);
    public static final DimensionGenerationDomain NETHER =
            new DimensionGenerationDomain(0, 128, 1, 2, false);

    public int height() {
        return maxY - minY;
    }
}
