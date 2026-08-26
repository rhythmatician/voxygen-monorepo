package com.rhythmatician.lodiffusion.voxy;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;

/** Session counters for diagnosing exact End L1 sampling and reduction. */
final class ExactL1SamplingTelemetry {
    private static final int HEIGHT_ORACLE_LIMIT = 16;
    private final AtomicInteger heightOracleClaims = new AtomicInteger();
    private final LongAdder childCalls = new LongAdder();
    private final LongAdder protoChunks = new LongAdder();
    private final LongAdder retainedCallbacks = new LongAdder();
    private final LongAdder rawAir = new LongAdder();
    private final LongAdder rawExplicitNonAir = new LongAdder();
    private final LongAdder acceptedCallbacks = new LongAdder();
    private final LongAdder reducedSolidVoxels = new LongAdder();
    private final LongAdder heightOraclePositive = new LongAdder();
    private final AtomicInteger heightOracleMax = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger heightOracleMinChunkX = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger heightOracleMaxChunkX = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger heightOracleMinChunkZ = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger heightOracleMaxChunkZ = new AtomicInteger(Integer.MIN_VALUE);
    private final AtomicInteger heightOracleMinY = new AtomicInteger(Integer.MAX_VALUE);
    private final AtomicInteger heightOracleMaxY = new AtomicInteger(Integer.MIN_VALUE);

    void recordChildCall() {
        childCalls.increment();
    }

    void recordProtoChunk() {
        protoChunks.increment();
    }

    void recordRetainedCallback() {
        retainedCallbacks.increment();
    }

    void recordRawAir() {
        rawAir.increment();
    }

    void recordRawExplicitNonAir() {
        rawExplicitNonAir.increment();
    }

    void recordAcceptedCallback() {
        acceptedCallbacks.increment();
    }

    void recordReducedSolidVoxels(long count) {
        reducedSolidVoxels.add(count);
    }
    boolean claimHeightOracleProbe() {
        return heightOracleClaims.getAndIncrement() < HEIGHT_ORACLE_LIMIT;
    }
    void recordHeightOracle(
            int chunkX, int chunkZ, int minY, int maxY, int height) {
        if (height > EndL4DeterministicCandidate.END_MIN_Y) {
            heightOraclePositive.increment();
        }
        heightOracleMax.accumulateAndGet(height, Math::max);
        heightOracleMinChunkX.accumulateAndGet(chunkX, Math::min);
        heightOracleMaxChunkX.accumulateAndGet(chunkX, Math::max);
        heightOracleMinChunkZ.accumulateAndGet(chunkZ, Math::min);
        heightOracleMaxChunkZ.accumulateAndGet(chunkZ, Math::max);
        heightOracleMinY.accumulateAndGet(minY, Math::min);
        heightOracleMaxY.accumulateAndGet(maxY, Math::max);
    }

    String compact() {
        return "child=" + childCalls.sum()
                + ",proto=" + protoChunks.sum()
                + ",callback=" + retainedCallbacks.sum()
                + ",air=" + rawAir.sum()
                + ",nonair=" + rawExplicitNonAir.sum()
                + ",accepted=" + acceptedCallbacks.sum()
                + ",solid=" + reducedSolidVoxels.sum()
                + ",heightOracle=" + Math.min(heightOracleClaims.get(), HEIGHT_ORACLE_LIMIT)
                + "/" + heightOraclePositive.sum()
                + "/" + heightOracleMax.get()
                + "@x" + heightOracleMinChunkX.get() + ".." + heightOracleMaxChunkX.get()
                + ",z" + heightOracleMinChunkZ.get() + ".." + heightOracleMaxChunkZ.get()
                + ",y" + heightOracleMinY.get() + ".." + heightOracleMaxY.get();
    }

    void reset() {
        childCalls.reset();
        protoChunks.reset();
        retainedCallbacks.reset();
        rawAir.reset();
        rawExplicitNonAir.reset();
        acceptedCallbacks.reset();
        reducedSolidVoxels.reset();
        heightOracleClaims.set(0);
        heightOraclePositive.reset();
        heightOracleMax.set(Integer.MIN_VALUE);
        heightOracleMinChunkX.set(Integer.MAX_VALUE);
        heightOracleMaxChunkX.set(Integer.MIN_VALUE);
        heightOracleMinChunkZ.set(Integer.MAX_VALUE);
        heightOracleMaxChunkZ.set(Integer.MIN_VALUE);
        heightOracleMinY.set(Integer.MAX_VALUE);
        heightOracleMaxY.set(Integer.MIN_VALUE);
    }
}
