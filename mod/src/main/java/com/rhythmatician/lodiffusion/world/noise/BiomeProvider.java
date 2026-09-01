package com.rhythmatician.lodiffusion.world.noise;

/**
 * Abstraction over the biome classification source.
 *
 * <p>Implementations:
 * <ul>
 *   <li>{@link VanillaBiomeProvider} — performs the vanilla
 *       {@code MultiNoiseBiomeSource} 6-parameter lookup on the CPU,
 *       using climate values from {@link SectionNoiseData}.</li>
 *   <li>{@link GpuBiomeProvider} — GPU-paired biome classification using
 *       the same vanilla lookup, semantically paired with the GPU noise
 *       sampler for logging/metrics and future GPU readback.</li>
 * </ul>
 *
 * <p>The biome IDs returned are <b>canonical palette indices</b> matching the
 * training vocabulary, not raw Minecraft registry IDs.
 *
 * @see NoiseRouterSampler
 * @see HeightmapProvider
 */
public interface BiomeProvider {

    /**
     * Classify biomes for a single 16³-block section at 4×2×4 noise-cell resolution.
     *
     * <p>Returns {@code int[4][2][4]} in {@code [qx][qy][qz]} order, where
     * each value is a canonical biome palette index.  The spatial layout
     * matches {@link SectionNoiseData}: vanilla uses {@code cellHeight=8}, so there
     * are only 2 Y cells per section, sampled at block offsets {@code qy*8+4} (i.e.
     * y=4 and y=12 relative to the section bottom).
     *
     * @param sectionX chunk-X coordinate
     * @param sectionY section-Y (overworld: −4 to 19)
     * @param sectionZ chunk-Z coordinate
     * @param noiseData noise data for this section (provides climate fields for
     *                  vanilla biome lookup; GPU implementations may ignore this)
     * @return {@code int[4][2][4]} biome palette indices, never {@code null}
     */
    int[][][] classifyBiomes(int sectionX, int sectionY, int sectionZ,
                             SectionNoiseData noiseData);

    /**
     * Human-readable name for logging.
     */
    String backendName();

    /**
     * Release any resources held by this provider.
     */
    default void close() { }
}
