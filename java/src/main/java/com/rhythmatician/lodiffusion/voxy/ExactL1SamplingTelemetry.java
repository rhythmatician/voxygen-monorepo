package com.rhythmatician.lodiffusion.voxy;

import java.util.concurrent.atomic.LongAdder;

/** Session counters for diagnosing exact End L1 sampling and reduction. */
final class ExactL1SamplingTelemetry {
    private final LongAdder childCalls = new LongAdder();
    private final LongAdder retainedCallbacks = new LongAdder();
    private final LongAdder rawAir = new LongAdder();
    private final LongAdder rawExplicitNonAir = new LongAdder();
    private final LongAdder acceptedCallbacks = new LongAdder();
    private final LongAdder reducedSolidVoxels = new LongAdder();

    void recordChildCall() {
        childCalls.increment();
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
    String compact() {
        return "child=" + childCalls.sum()
                + ",callback=" + retainedCallbacks.sum()
                + ",air=" + rawAir.sum()
                + ",nonair=" + rawExplicitNonAir.sum()
                + ",accepted=" + acceptedCallbacks.sum()
                + ",solid=" + reducedSolidVoxels.sum();
    }

    void reset() {
        childCalls.reset();
        retainedCallbacks.reset();
        rawAir.reset();
        rawExplicitNonAir.reset();
        acceptedCallbacks.reset();
        reducedSolidVoxels.reset();
    }
}
