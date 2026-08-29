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
        long seed,
        long wallNanos,
        long warmupIterations,
        long measurementIterations,
        String repetitionPolicy,
        long timestampMs
) {
    public double wallMillis() { return wallNanos / 1_000_000.0; }

    public static BenchmarkReceipt measure(Level level, int regionBlocks, OracleFixture fixture, Runnable candidate, int warmup, int iters, String policy) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(fixture, "fixture");
        Objects.requireNonNull(candidate, "candidate");
        for (int i = 0; i < warmup; i++) candidate.run();
        long start = System.nanoTime();
        for (int i = 0; i < iters; i++) candidate.run();
        long elapsed = System.nanoTime() - start;
        long perIter = elapsed / Math.max(1, iters);
        return new BenchmarkReceipt(level, regionBlocks, fixture.contract().oracleFixtureId(), fixture.contract().seed(), perIter, warmup, iters, policy, System.currentTimeMillis());
    }
}
