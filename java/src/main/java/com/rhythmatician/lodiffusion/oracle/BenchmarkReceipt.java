package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.Level;
import java.util.Objects;

/**
 * Immutable benchmark receipt for oracle-independent candidate cost (separate from correctness).
 * Records Level, region size, seed/fixture identity, wall time, allocations if measurable,
 * warmup/repetition policy. Correctness and speed are separate axes.
 */
public record BenchmarkReceipt(
        Level level,
        int regionBlocks,
        String fixtureId,
        String captureProtocolSha256,
        long seed,
        long wallNanos,
        long warmupIterations,
        long measurementIterations,
        String repetitionPolicy,
        long timestampMs
) {
    public double wallMillis() { return wallNanos / 1_000_000.0; }

    /**
     * Measures wall time per candidate execution. Warmup iterations run
     * without measurement. Each measurement iteration is timed individually
     * and the median is recorded (not the mean), so the stored
     * {@code wallNanos} is truthfully the median per-volume time consistent
     * with the contract policy “median of 20 after 5 warmup”.
     */
    public static BenchmarkReceipt measure(Level level, int regionBlocks, OracleFixture fixture, Runnable candidate, int warmup, int iters, String policy) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(candidate, "candidate");
        if (iters <= 0) throw new IllegalArgumentException("measurementIterations must be >0, was " + iters);
        if (warmup < 0) throw new IllegalArgumentException("warmup must be >=0, was " + warmup);
        for (int i = 0; i < warmup; i++) candidate.run();
        long[] samples = new long[iters];
        for (int i = 0; i < iters; i++) {
            long start = System.nanoTime();
            candidate.run();
            samples[i] = System.nanoTime() - start;
        }
        java.util.Arrays.sort(samples);
        long median;
        if (iters % 2 == 1) {
            median = samples[iters / 2];
        } else {
            // Even: average of two middle values to avoid bias
            median = (samples[iters / 2 - 1] + samples[iters / 2]) / 2;
        }
        return new BenchmarkReceipt(level, regionBlocks, fixture.contract().oracleFixtureId(), fixture.contract().captureProtocolSha256(), fixture.contract().seed(), median, warmup, iters, policy, System.currentTimeMillis());
    }

    /** Legacy constructor without captureProtocolSha256 — for test migration only. */
    public BenchmarkReceipt(Level level, int regionBlocks, String fixtureId, long seed, long wallNanos, long warmupIterations, long measurementIterations, String repetitionPolicy, long timestampMs) {
        this(level, regionBlocks, fixtureId, null, seed, wallNanos, warmupIterations, measurementIterations, repetitionPolicy, timestampMs);
    }
}
