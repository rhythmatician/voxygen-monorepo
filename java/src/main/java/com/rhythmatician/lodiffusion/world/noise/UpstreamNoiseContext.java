package com.rhythmatician.lodiffusion.world.noise;
import com.rhythmatician.voxygen.generation.scheduling.LodGenerationService;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;

/**
 * Bundles the three upstream provider interfaces into a single object that
 * downstream code receives at construction time.
 *
 * <p>This is the <b>sole contact surface</b> between the noise generation
 * layer and the rest of the LOD pipeline.  No downstream class may import
 * {@code NoiseRouter}, {@code DensityFunction}, {@code WorldNoiseAccess},
 * or any other vanilla noise API.
 *
 * <p>All three providers are guaranteed to use the same backend (vanilla CPU,
 * GPU, or shadow-validating) for a given world lifecycle.  The bundle is
 * created once per world load by {@link UpstreamProviderFactory} and handed
 * to {@link com.rhythmatician.voxygen.generation.scheduling.LodGenerationService} and
 * its dependents.
 *
 * @see NoiseRouterSampler
 * @see HeightmapProvider
 * @see BiomeProvider
 * @see UpstreamProviderFactory
 */
public record UpstreamNoiseContext(
        /**
         * Provides 15-field quart-resolution noise tensors per section.
         * Never {@code null}.
         */
        NoiseRouterSampler noiseSampler,

        /**
         * Provides world-surface and ocean-floor heightmaps per chunk column.
         * Never {@code null}.
         */
        HeightmapProvider heightmapProvider,

        /**
         * Provides biome classification per section at quart resolution.
         * Never {@code null}.
         */
        BiomeProvider biomeProvider
) implements AutoCloseable {

    /**
     * Validate all providers are non-null.
     */
    public UpstreamNoiseContext {
        if (noiseSampler == null)
            throw new IllegalArgumentException("noiseSampler must not be null");
        if (heightmapProvider == null)
            throw new IllegalArgumentException("heightmapProvider must not be null");
        if (biomeProvider == null)
            throw new IllegalArgumentException("biomeProvider must not be null");
    }

    /**
     * Composite backend name for logging (e.g. {@code "vanilla_cpu / vanilla_cpu / vanilla_cpu"}).
     */
    public String backendName() {
        return noiseSampler.backendName()
                + " / " + heightmapProvider.backendName()
                + " / " + biomeProvider.backendName();
    }

    /**
     * Close all three providers.
     */
    @Override
    public void close() {
        noiseSampler.close();
        heightmapProvider.close();
        biomeProvider.close();
    }
}
