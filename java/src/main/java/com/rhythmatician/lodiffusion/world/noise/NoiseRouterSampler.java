package com.rhythmatician.lodiffusion.world.noise;

/**
 * Abstraction over the noise source that feeds the sparse octree model.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link VanillaNoiseRouterSampler} — evaluates the 15 {@link RouterField}
 *       functions on the CPU via the Fabric/Yarn {@code DensityFunction} API.
 *       Bit-exact with vanilla Minecraft; used as the reference baseline.</li>
 *   <li>{@link GpuNoiseRouterSampler} — reads values computed by the shadow router
 *       GPU compute pipeline.  Faster, but may diverge from vanilla due to
 *       FP32 precision or incomplete field coverage.</li>
 * </ul>
 *
 * <p>The active implementation is selected at runtime via the
 * {@code "terrainBackend"} config key (values: {@code "vanilla"},
 * {@code "gpu"}, {@code "auto"}).  The toggle is hot-swappable:
 * {@link NoiseRouterSamplerFactory} checks the config on every call,
 * so switching takes effect on the next section request.
 *
 * @see SectionNoiseData
 * @see RouterField
 * @see NoiseRouterSamplerFactory
 */
public interface NoiseRouterSampler {

    /**
     * Sample all 15 {@link RouterField NoiseRouter fields} for a single
     * 16³-block section at the <b>Overworld lattice</b>:
     * {@code float[480]} in {@code [field][qx][qy][qz]} order where
     * {@code 4 × 2 × 4 = 32} cells per field (spacing 4 on X/Z, 8 on Y).
     *
     * <p>This sampler is <b>Overworld-only today</b>. The returned
     * {@link SectionNoiseData} is the Overworld conditioning shape
     * ({@link SectionNoiseData#FLAT_LENGTH} = 480). Unsupported dimensions
     * (Nether, End, or any non-Overworld lattice) must fail closed before
     * producing data — callers obtain the sampler via
     * {@link NoiseRouterSamplerFactory} which validates the bound dimension.
     * Direct sampler use outside the Overworld also throws
     * {@link UnsupportedOperationException}.
     *
     * <p>Layout: {@code flat[field * 32 + qx * 8 + qy * 4 + qz]}, ready to be
     * passed to the downstream sparse octree pipeline where supported.
     *
     * @param sectionX chunk-X coordinate (same as section-X at L0)
     * @param sectionY section-Y in native units (overworld: −4 to 19)
     * @param sectionZ chunk-Z coordinate (same as section-Z at L0)
     * @return noise data for the section, never {@code null}
     * @throws UnsupportedOperationException if the sampler is bound to an
     *         unsupported dimension/lattice
     */
    SectionNoiseData sampleSection(int sectionX, int sectionY, int sectionZ);

    /**
     * Human-readable name for logging/debug (e.g. {@code "vanilla_cpu"},
     * {@code "gpu"}).
     */
    String backendName();

    /**
     * Release any resources held by this sampler (GPU buffers, cached
     * DensityFunction handles, etc.).  Called on world unload.
     */
    default void close() { }
}
