package com.rhythmatician.lodiffusion.world.noise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Rolling-window statistics collector that compares two
 * {@link SectionNoiseData} snapshots (reference vs. candidate) field by field.
 *
 * <p>Usage pattern (inside {@link ShadowValidatingSampler}):
 * <pre>{@code
 *   if (reporter.shouldSample()) {
 *       reporter.compare(referenceData, candidateData, sx, sy, sz);
 *   }
 * }</pre>
 *
 * <p>At the end of each aggregation window (configurable via
 * {@link ParityConfig#aggregationWindow()}), a summary log line is emitted
 * containing per-field max/mean absolute error, sign disagreement rates,
 * and whether any threshold was violated.
 *
 * <p>This class is <b>not thread-safe</b>; each generation thread should
 * own its own instance (or guard externally).
 *
 * @see ParityConfig
 * @see ShadowValidatingSampler
 */
public final class ParityReporter {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/Parity");

    private final ParityConfig config;

    // ── Per-field rolling accumulators ────────────────────────────────
    private final float[] maxAbsError;        // [RouterField.COUNT]
    private final double[] sumAbsError;       // [RouterField.COUNT]
    private final double[] sumSqError;        // [RouterField.COUNT]
    private final long[]   signDisagrees;     // [RouterField.COUNT]
    private final long[]   totalCells;        // [RouterField.COUNT]

    // ── Spatial divergence snapshots (ring buffer for current window) ──
    private final List<DivergenceSnapshot> windowSnapshots;

    private int sectionsInWindow;
    private long windowStartMs;

    public ParityReporter(ParityConfig config) {
        this.config = config;
        int n = RouterField.COUNT;
        this.maxAbsError    = new float[n];
        this.sumAbsError    = new double[n];
        this.sumSqError     = new double[n];
        this.signDisagrees  = new long[n];
        this.totalCells     = new long[n];
        this.windowSnapshots = new ArrayList<>(config.aggregationWindow());
        this.windowStartMs  = System.currentTimeMillis();
    }

    // ── Sampling gate ────────────────────────────────────────────────

    /**
     * Returns {@code true} if this section should be compared.
     * Uses the configured sampling rate with a thread-local random.
     */
    public boolean shouldSample() {
        double rate = config.samplingRate();
        if (rate >= 1.0) return true;
        if (rate <= 0.0) return false;
        return ThreadLocalRandom.current().nextDouble() < rate;
    }

    // ── Core comparison ──────────────────────────────────────────────

    /**
     * Compare a reference and candidate {@link SectionNoiseData} and
     * accumulate per-field statistics.
     *
     * @param reference  the ground-truth (vanilla CPU) data
     * @param candidate  the candidate (GPU / learned) data
     * @param sx section X
     * @param sy section Y
     * @param sz section Z
     */
    public void compare(SectionNoiseData reference,
                        SectionNoiseData candidate,
                        int sx, int sy, int sz) {

        // Capture spatial divergence snapshot
        DivergenceSnapshot snapshot = DivergenceSnapshot.compute(reference, candidate);
        windowSnapshots.add(snapshot);

        boolean anyViolation = false;

        for (RouterField field : RouterField.values()) {
            int idx = field.ordinal();
            float threshold = config.thresholdFor(field);
            int base = idx * SectionNoiseData.CELLS_PER_FIELD;

            float fieldMaxErr = 0f;
            double fieldSumAbs = 0.0;
            double fieldSumSq  = 0.0;
            int fieldSignDis = 0;

            for (int c = 0; c < SectionNoiseData.CELLS_PER_FIELD; c++) {
                float ref = reference.flat()[base + c];
                float cand = candidate.flat()[base + c];
                float err = Math.abs(ref - cand);

                fieldSumAbs += err;
                fieldSumSq  += (double) err * err;
                if (err > fieldMaxErr) fieldMaxErr = err;

                // Sign comparison (positive = solid, negative/zero = air)
                if ((ref > 0) != (cand > 0)) {
                    fieldSignDis++;
                }
            }

            // Accumulate into window
            if (fieldMaxErr > maxAbsError[idx]) maxAbsError[idx] = fieldMaxErr;
            sumAbsError[idx]  += fieldSumAbs;
            sumSqError[idx]   += fieldSumSq;
            signDisagrees[idx] += fieldSignDis;
            totalCells[idx]   += SectionNoiseData.CELLS_PER_FIELD;

            // Threshold check
            if (fieldMaxErr > threshold) {
                anyViolation = true;
            }
        }

        // Density sign agreement check
        int densityIdx = RouterField.FINAL_DENSITY.ordinal();
        if (totalCells[densityIdx] > 0) {
            float signAgreement = 1.0f - (float) signDisagrees[densityIdx]
                    / totalCells[densityIdx];
            if (signAgreement < config.densitySignAgreementMin()) {
                anyViolation = true;
            }
        }

        sectionsInWindow++;

        // Per-section log
        if (config.logLevel() == ParityConfig.LogLevel.PER_SECTION) {
            LOG.info("[Parity] sec({},{},{}) violations={}", sx, sy, sz, anyViolation);
        }

        // Violation-only log (QUIET mode)
        if (anyViolation && config.logLevel() == ParityConfig.LogLevel.QUIET) {
            LOG.warn("[Parity] VIOLATION sec({},{},{}) — run with PER_SECTION for details",
                    sx, sy, sz);
        }

        // Window boundary
        if (sectionsInWindow >= config.aggregationWindow()) {
            flushWindow();
        }
    }

    // ── Window summary ───────────────────────────────────────────────

    /**
     * Force-flush the current window stats to the log.
     * Automatically called when the window size is reached.
     */
    public void flushWindow() {
        if (sectionsInWindow == 0) return;

        long elapsed = System.currentTimeMillis() - windowStartMs;
        StringBuilder sb = new StringBuilder(512);
        sb.append("[Parity] Window: ").append(sectionsInWindow)
          .append(" sections, ").append(elapsed).append("ms\n");

        boolean anyViolation = false;
        for (RouterField field : RouterField.values()) {
            int idx = field.ordinal();
            long cells = totalCells[idx];
            if (cells == 0) continue;

            float maxErr  = maxAbsError[idx];
            float meanErr = (float) (sumAbsError[idx] / cells);
            float rmsErr  = (float) Math.sqrt(sumSqError[idx] / cells);
            float signAgr = 1.0f - (float) signDisagrees[idx] / cells;
            float thresh  = config.thresholdFor(field);

            boolean over = maxErr > thresh;
            if (over) anyViolation = true;

            sb.append(String.format("  %-28s max=%.6f mean=%.6f rms=%.6f signAgr=%.4f thresh=%.4f %s%n",
                    field.name(), maxErr, meanErr, rmsErr, signAgr, thresh,
                    over ? "!OVER!" : "OK"));
        }

        // Density sign summary
        int dIdx = RouterField.FINAL_DENSITY.ordinal();
        if (totalCells[dIdx] > 0) {
            float densitySignAgr = 1.0f - (float) signDisagrees[dIdx] / totalCells[dIdx];
            sb.append(String.format("  Density sign agreement: %.4f (min=%.4f) %s%n",
                    densitySignAgr, config.densitySignAgreementMin(),
                    densitySignAgr < config.densitySignAgreementMin() ? "!FAIL!" : "OK"));
        }

        if (anyViolation) {
            LOG.warn(sb.toString());
        } else {
            LOG.info(sb.toString());
        }

        resetWindow();
    }

    // ── Internal ─────────────────────────────────────────────────────

    private void resetWindow() {
        for (int i = 0; i < RouterField.COUNT; i++) {
            maxAbsError[i]   = 0f;
            sumAbsError[i]   = 0.0;
            sumSqError[i]    = 0.0;
            signDisagrees[i] = 0;
            totalCells[i]    = 0;
        }
        windowSnapshots.clear();
        sectionsInWindow = 0;
        windowStartMs = System.currentTimeMillis();
    }

    /** Number of sections accumulated in the current window (for testing). */
    public int sectionsInWindow() {
        return sectionsInWindow;
    }

    /**
     * Return the divergence snapshots collected in the current window.
     * The returned list is a defensive copy.
     *
     * <p>Each snapshot contains per-cell density error, solid/air mismatch
     * mask, and per-field error fields — exposing <i>where</i> the GPU
     * diverges, not just <i>how much</i>.
     *
     * @return immutable list of divergence snapshots
     */
    public List<DivergenceSnapshot> windowSnapshots() {
        return List.copyOf(windowSnapshots);
    }

    /** The config this reporter was created with. */
    public ParityConfig config() {
        return config;
    }
}
