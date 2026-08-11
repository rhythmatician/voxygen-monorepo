package com.rhythmatician.lodiffusion.world.noise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A decorator {@link NoiseRouterSampler} that wraps a <b>reference</b>
 * (vanilla CPU) and a <b>candidate</b> (GPU or learned) sampler.
 *
 * <p>On every call to {@link #sampleSection}, this sampler:
 * <ol>
 *   <li>Samples the reference backend (always).</li>
 *   <li>Samples the candidate backend (always — to keep GPU pipeline warm).</li>
 *   <li>If {@link ParityReporter#shouldSample()} fires, compares the two
 *       snapshots and accumulates statistics.</li>
 *   <li><b>Returns the reference result</b> — downstream consumers always
 *       see the ground-truth data.</li>
 * </ol>
 *
 * <p>This is a <b>validation-only</b> mode intended for development and
 * regression testing.  It is selected by setting
 * {@code "terrainBackend": "shadow"} in the config.  It is <i>not</i>
 * designed for production use (2× sampling cost).
 *
 * <h2>Thread safety</h2>
 * <p>The underlying samplers are assumed to be confined to the generation
 * thread.  {@link ParityReporter} is likewise single-threaded per instance.
 * If the generation service moves to a thread pool, each thread must get
 * its own {@code ShadowValidatingSampler} (and reporter).
 *
 * @see ParityConfig
 * @see ParityReporter
 * @see NoiseRouterSamplerFactory
 */
public final class ShadowValidatingSampler implements NoiseRouterSampler {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/Shadow");

    private final NoiseRouterSampler reference;
    private final NoiseRouterSampler candidate;
    private final ParityReporter reporter;

    /**
     * @param reference  the ground-truth sampler (always returned)
     * @param candidate  the sampler under test (sampled for comparison)
     * @param parityConfig controls sampling rate, thresholds, and logging
     */
    public ShadowValidatingSampler(NoiseRouterSampler reference,
                                   NoiseRouterSampler candidate,
                                   ParityConfig parityConfig) {
        this.reference = reference;
        this.candidate = candidate;
        this.reporter  = new ParityReporter(parityConfig);

        LOG.info("[Shadow] Initialised — reference={}, candidate={}, rate={}, window={}",
                reference.backendName(), candidate.backendName(),
                parityConfig.samplingRate(), parityConfig.aggregationWindow());
    }

    @Override
    public SectionNoiseData sampleSection(int sectionX, int sectionY, int sectionZ) {
        // Always sample both (keeps GPU warmed up and exercises the full path)
        SectionNoiseData refData  = reference.sampleSection(sectionX, sectionY, sectionZ);
        SectionNoiseData candData = candidate.sampleSection(sectionX, sectionY, sectionZ);

        // Probabilistic comparison
        if (reporter.shouldSample()) {
            reporter.compare(refData, candData, sectionX, sectionY, sectionZ);
        }

        // Always return the reference result
        return refData;
    }

    @Override
    public String backendName() {
        return "shadow(" + reference.backendName() + " vs " + candidate.backendName() + ")";
    }

    @Override
    public void close() {
        // Flush any partial window before shutdown
        reporter.flushWindow();

        reference.close();
        candidate.close();

        LOG.info("[Shadow] Closed — final stats flushed.");
    }

    /** Expose the reporter for testing or external stats collection. */
    public ParityReporter reporter() {
        return reporter;
    }

    /** Expose the reference sampler (e.g. for direct bypass). */
    public NoiseRouterSampler reference() {
        return reference;
    }

    /** Expose the candidate sampler. */
    public NoiseRouterSampler candidate() {
        return candidate;
    }
}
