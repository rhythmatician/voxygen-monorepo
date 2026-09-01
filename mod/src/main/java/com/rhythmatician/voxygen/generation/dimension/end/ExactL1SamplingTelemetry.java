package com.rhythmatician.voxygen.generation.dimension.end;

import java.util.concurrent.atomic.LongAdder;

/** Session counters for diagnosing exact End L1 sampling and reduction. */
public final class ExactL1SamplingTelemetry {
    public ExactL1SamplingTelemetry() {}

    private final LongAdder childCalls = new LongAdder();
    private final LongAdder retainedCallbacks = new LongAdder();
    private final LongAdder rawAir = new LongAdder();
    private final LongAdder rawExplicitNonAir = new LongAdder();
    private final LongAdder acceptedCallbacks = new LongAdder();
    private final LongAdder reducedSolidVoxels = new LongAdder();

    public void recordChildCall() {
        childCalls.increment();
    }

    public void recordRetainedCallback() {
        retainedCallbacks.increment();
    }

    public void recordRawAir() {
        rawAir.increment();
    }

    public void recordRawExplicitNonAir() {
        rawExplicitNonAir.increment();
    }

    public void recordAcceptedCallback() {
        acceptedCallbacks.increment();
    }

    public void recordReducedSolidVoxels(long count) {
        reducedSolidVoxels.add(count);
    }
    public String compact() {
        return "child=" + childCalls.sum()
                + ",callback=" + retainedCallbacks.sum()
                + ",air=" + rawAir.sum()
                + ",nonair=" + rawExplicitNonAir.sum()
                + ",accepted=" + acceptedCallbacks.sum()
                + ",solid=" + reducedSolidVoxels.sum();
    }

    public void reset() {
        childCalls.reset();
        retainedCallbacks.reset();
        rawAir.reset();
        rawExplicitNonAir.reset();
        acceptedCallbacks.reset();
        reducedSolidVoxels.reset();
    }
}
