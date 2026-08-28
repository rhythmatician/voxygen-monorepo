package com.rhythmatician.lodiffusion.voxy;

/**
 * Per-dimension closed Y interval plus NoiseSettings shape that defines which rows are vanilla-real
 * and which side effects are dead code for that dimension. Derived via
 * {@link WorldSectionCoord#worldSectionToBlockMin} / {@link WorldSectionCoord#worldSectionToBlockMax}.
 */
public record DimensionGenerationDomain(int minY, int maxY, int cellWidth, int cellHeight, boolean aquifersEnabled) {
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
