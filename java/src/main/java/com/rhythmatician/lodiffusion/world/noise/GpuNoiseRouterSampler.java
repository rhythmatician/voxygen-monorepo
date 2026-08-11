package com.rhythmatician.lodiffusion.world.noise;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * GPU-backed {@link NoiseRouterSampler} that dispatches section noise requests
 * to the render-thread via {@link GpuNoiseDispatchQueue} and waits for GPU
 * results.
 *
 * <h2>Threading</h2>
 * {@link #sampleSection} is called from the {@code LODiffusion-Gen} daemon
 * thread, which has no GL context.  The method enqueues a request on the
 * dispatch queue and blocks on a {@link CompletableFuture} with a configurable
 * timeout.  The render thread drains the queue each client tick, dispatches the
 * GPU compute shader, and completes the futures.
 *
 * <h2>CPU fallback</h2>
 * If the GPU queue is not initialised (e.g. before world load completes), or
 * the future times out / fails, the request falls back transparently to a
 * {@link VanillaNoiseRouterSampler} CPU path.  A rate-limited warning is
 * logged so the fallback is visible but not spammy.
 *
 * @see GpuNoiseDispatchQueue
 * @see NoiseRouterSampler
 * @see VanillaNoiseRouterSampler
 */
public final class GpuNoiseRouterSampler implements NoiseRouterSampler {

    /** Maximum time (ms) to wait for the GPU future before falling back to CPU. */
    private static final long GPU_TIMEOUT_MS = 500;

    /**
     * Minimum interval (ms) between CPU-fallback warning log messages.
     * Prevents log spam when the GPU path is consistently slow or down.
     */
    private static final long WARN_LOG_INTERVAL_MS = 5_000;

    /** CPU fallback sampler (always available). */
    private final VanillaNoiseRouterSampler cpuFallback;

    // ── Metrics ─────────────────────────────────────────────────────────
    private final AtomicLong gpuHits = new AtomicLong();
    private final AtomicLong cpuFallbackHits = new AtomicLong();
    private volatile long lastWarnLogMs = 0;

    /**
     * @param noiseConfig the server's NoiseConfig (used for CPU fallback)
     */
    public GpuNoiseRouterSampler(NoiseConfig noiseConfig) {
        this.cpuFallback = new VanillaNoiseRouterSampler(noiseConfig);
    }

    @Override
    public SectionNoiseData sampleSection(int sectionX, int sectionY, int sectionZ) {
        GpuNoiseDispatchQueue queue = GpuNoiseDispatchQueue.instance();

        // If the dispatch queue isn't up yet, go straight to CPU
        if (queue == null) {
            return cpuFallbackSample(sectionX, sectionY, sectionZ, "queue not initialised");
        }

        // Enqueue and wait for the render-thread to dispatch on GPU
        CompletableFuture<SectionNoiseData> future = queue.enqueue(sectionX, sectionY, sectionZ);
        try {
            SectionNoiseData result = future.get(GPU_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            gpuHits.incrementAndGet();
            return result;
        } catch (TimeoutException e) {
            future.cancel(false);
            return cpuFallbackSample(sectionX, sectionY, sectionZ, "GPU timeout (" + GPU_TIMEOUT_MS + "ms)");
        } catch (Exception e) {
            return cpuFallbackSample(sectionX, sectionY, sectionZ, "GPU error: " + e.getMessage());
        }
    }

    /**
     * Transparent CPU fallback with rate-limited warning.
     */
    private SectionNoiseData cpuFallbackSample(int sectionX, int sectionY, int sectionZ, String reason) {
        cpuFallbackHits.incrementAndGet();

        // Rate-limited warning log
        long now = System.currentTimeMillis();
        if (now - lastWarnLogMs > WARN_LOG_INTERVAL_MS) {
            lastWarnLogMs = now;
            HelloTerrainMod.LOGGER.warn(
                    "[GpuNoiseRouterSampler] CPU fallback for section ({},{},{}) — {} " +
                    "(gpuHits={}, cpuFallbacks={})",
                    sectionX, sectionY, sectionZ, reason,
                    gpuHits.get(), cpuFallbackHits.get());
        }

        return cpuFallback.sampleSection(sectionX, sectionY, sectionZ);
    }

    @Override
    public String backendName() {
        return "gpu";
    }

    @Override
    public void close() {
        cpuFallback.close();
        HelloTerrainMod.LOGGER.info(
                "[GpuNoiseRouterSampler] Closed — gpuHits={}, cpuFallbacks={}",
                gpuHits.get(), cpuFallbackHits.get());
    }

    // ── Metrics accessors (for status commands / debugging) ─────────────

    public long getGpuHits()          { return gpuHits.get(); }
    public long getCpuFallbackHits()  { return cpuFallbackHits.get(); }
}
