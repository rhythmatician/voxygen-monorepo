package com.rhythmatician.lodiffusion.world.noise;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;

import net.minecraft.server.world.ServerWorld;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * Creates and manages {@link NoiseRouterSampler} instances based on the
 * {@code "terrainBackend"} config key.
 *
 * <p>The factory supports <b>hot-swapping</b>: it re-reads the config on every
 * call to {@link #getSampler()}.  When the requested backend changes, the old
 * sampler is closed and a new one is created.  This allows switching between
 * {@code "vanilla"}, {@code "gpu"}, and {@code "shadow"} at runtime without
 * restarting the world.
 *
 * <h2>Lifecycle</h2>
 * <ol>
 *   <li>{@link #create(ServerWorld, ChunkGenerator, BiomeSource, NoiseConfig)}
 *       — called once at world load.</li>
 *   <li>{@link #getSampler()} — called per-section by the generation service.</li>
 *   <li>{@link #getUpstreamContext()} — returns the full upstream bundle
 *       (sampler + heightmap + biome providers), creating them lazily.</li>
 *   <li>{@link #close()} — called on world unload to release resources.</li>
 * </ol>
 *
 * @see NoiseRouterSampler
 * @see UpstreamNoiseContext
 * @see Config#terrainBackend()
 */
public final class NoiseRouterSamplerFactory implements AutoCloseable {

    private final ServerWorld serverWorld;
    private final ChunkGenerator generator;
    private final BiomeSource biomeSource;
    private final NoiseConfig noiseConfig;

    /** Currently active sampler (volatile for hot-swap visibility). */
    private volatile NoiseRouterSampler activeSampler;

    /** The backend name of the currently active sampler. */
    private volatile String activeBackendKey;

    /** Full upstream context — created lazily alongside the sampler. */
    private volatile UpstreamNoiseContext activeContext;

    /** The active pipeline mode — tracks which sampler feeds the model. */
    private volatile SamplerMode activeSamplerMode;

    private NoiseRouterSamplerFactory(ServerWorld serverWorld,
                                      ChunkGenerator generator,
                                      BiomeSource biomeSource,
                                      NoiseConfig noiseConfig) {
        this.serverWorld = serverWorld;
        this.generator   = generator;
        this.biomeSource = biomeSource;
        this.noiseConfig = noiseConfig;
    }

    /**
     * Create a factory bound to the given world state.
     *
     * <p>The first sampler is created lazily on the first call to
     * {@link #getSampler()}.
     *
     * @param serverWorld the server-side world (for heightmap provider)
     * @param generator   the chunk generator (for heightmap provider)
     * @param biomeSource the biome source (for biome provider)
     * @param noiseConfig the server's NoiseConfig (never null)
     * @return a new factory
     */
    public static NoiseRouterSamplerFactory create(ServerWorld serverWorld,
                                                    ChunkGenerator generator,
                                                    BiomeSource biomeSource,
                                                    NoiseConfig noiseConfig) {
        return new NoiseRouterSamplerFactory(serverWorld, generator, biomeSource, noiseConfig);
    }

    /**
     * Backward-compatible overload — creates a factory without world context.
     * The factory can produce samplers but not full {@link UpstreamNoiseContext}
     * bundles (heightmap and biome providers will be {@code null}).
     *
     * @deprecated Use the 4-arg overload for full upstream context support.
     */
    @Deprecated
    public static NoiseRouterSamplerFactory create(NoiseConfig noiseConfig) {
        return new NoiseRouterSamplerFactory(null, null, null, noiseConfig);
    }

    /**
     * Return the active {@link NoiseRouterSampler}, creating or swapping it if
     * the {@code "terrainBackend"} config has changed since the last call.
     *
     * <p>This method is safe to call from any thread.  Sampler creation (which
     * may resolve DensityFunction handles) is synchronized; subsequent reads
     * are lock-free.
     *
     * @return the active sampler, never null
     */
    public NoiseRouterSampler getSampler() {
        String requested = resolveBackendKey(Config.terrainBackend());

        // Fast path: no change
        if (requested.equals(activeBackendKey) && activeSampler != null) {
            return activeSampler;
        }

        // Slow path: create / swap
        synchronized (this) {
            // Double-check after acquiring lock
            if (requested.equals(activeBackendKey) && activeSampler != null) {
                return activeSampler;
            }

            NoiseRouterSampler oldSampler = activeSampler;
            NoiseRouterSampler newSampler = createSampler(requested);

            activeSampler = newSampler;
            activeBackendKey = requested;
            activeSamplerMode = SamplerMode.fromBackendKey(requested);
            // Invalidate the context so it gets rebuilt on next request
            activeContext = null;

            if (oldSampler != null) {
                oldSampler.close();
                HelloTerrainMod.LOGGER.info(
                        "[NoiseRouterSamplerFactory] Switched backend: {} → {} (mode={})",
                        oldSampler.backendName(), newSampler.backendName(),
                        activeSamplerMode.configKey());
            } else {
                HelloTerrainMod.LOGGER.info(
                        "[NoiseRouterSamplerFactory] Initialized backend: {} (mode={})",
                        newSampler.backendName(), activeSamplerMode.configKey());
            }

            return newSampler;
        }
    }

    /**
     * Return the full {@link UpstreamNoiseContext} bundle containing the
     * active sampler, heightmap provider, and biome provider.
     *
     * <p>Lazily creates the bundle on first call (or after a backend swap).
     * Requires that this factory was created with the 4-arg constructor
     * (i.e. world context is available).
     *
     * @return the upstream context, never null
     * @throws IllegalStateException if world context is not available
     */
    public UpstreamNoiseContext getUpstreamContext() {
        // Ensure sampler is current
        getSampler();

        UpstreamNoiseContext ctx = activeContext;
        if (ctx != null) return ctx;

        synchronized (this) {
            if (activeContext != null) return activeContext;

            if (serverWorld == null || generator == null || biomeSource == null) {
                throw new IllegalStateException(
                        "UpstreamNoiseContext requires world context — use the 4-arg create()");
            }

            // Use GPU providers when the backend involves GPU sampling
            boolean useGpu = activeSampler instanceof GpuNoiseRouterSampler
                    || (activeSampler instanceof ShadowValidatingSampler);

            HeightmapProvider hmp = useGpu
                    ? new GpuHeightmapProvider()
                    : new VanillaHeightmapProvider(serverWorld, generator, noiseConfig);
            BiomeProvider bp = useGpu
                    ? new GpuBiomeProvider(biomeSource, noiseConfig)
                    : new VanillaBiomeProvider(biomeSource, noiseConfig);

            activeContext = new UpstreamNoiseContext(activeSampler, hmp, bp);
            HelloTerrainMod.LOGGER.info(
                    "[NoiseRouterSamplerFactory] Built UpstreamNoiseContext: {}",
                    activeContext.backendName());
            return activeContext;
        }
    }

    @Override
    public void close() {
        synchronized (this) {
            if (activeContext != null) {
                activeContext.close();
                activeContext = null;
            } else if (activeSampler != null) {
                activeSampler.close();
            }
            HelloTerrainMod.LOGGER.info(
                    "[NoiseRouterSamplerFactory] Closed (backend was: {}, mode was: {})",
                    activeBackendKey, activeSamplerMode != null ? activeSamplerMode.configKey() : "none");
            activeSampler = null;
            activeBackendKey = null;
            activeSamplerMode = null;
        }
    }

    /**
     * Return the current {@link SamplerMode}, which defines which sampler
     * feeds the model and which is authoritative.
     *
     * <p>This answers the critical question: "which sampler feeds the model
     * during validation?"  In {@link SamplerMode#CPU_VS_GPU_COMPARE} mode,
     * the model always receives CPU-sourced signals.
     *
     * @return current mode, or {@code null} before first sampler creation
     */
    public SamplerMode activeSamplerMode() {
        return activeSamplerMode;
    }

    // ── internals ─────────────────────────────────────────────────────

    /**
     * Resolve {@code "auto"} to a concrete backend name.
     *
     * <p>Currently {@code "auto"} maps to {@code "vanilla"}.  When the shadow
     * router GPU pipeline covers all 15 fields at quart resolution, this will
     * prefer {@code "gpu"} if a GL context is available.
     */
    static String resolveBackendKey(String raw) {
        if (raw == null || raw.isBlank()) raw = "auto";
        return switch (raw.toLowerCase(java.util.Locale.ROOT)) {
            case "auto"    -> "vanilla";   // TODO: prefer "gpu" when shader is complete
            case "vanilla" -> "vanilla";
            case "gpu"     -> "gpu";
            case "shadow"  -> "shadow";
            default -> {
                HelloTerrainMod.LOGGER.warn(
                        "[NoiseRouterSamplerFactory] Unknown terrainBackend '{}', falling back to vanilla",
                        raw);
                yield "vanilla";
            }
        };
    }

    private NoiseRouterSampler createSampler(String backendKey) {
        return switch (backendKey) {
            case "gpu"    -> new GpuNoiseRouterSampler(noiseConfig);
            case "shadow" -> new ShadowValidatingSampler(
                    new VanillaNoiseRouterSampler(noiseConfig),
                    new GpuNoiseRouterSampler(noiseConfig),
                    Config.parityConfig());
            default       -> new VanillaNoiseRouterSampler(noiseConfig);
        };
    }
}
