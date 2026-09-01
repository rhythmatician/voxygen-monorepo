package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.RefinementLifecycleTelemetry;
import com.rhythmatician.voxygen.generation.refinement.RefinementOutcome;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.output.WriteOutcome;

class RefinementLifecycleTelemetryTest {
    @Test
    void attributesLifecycleOutcomesToRequestedParentLevel() {
        RefinementLifecycleTelemetry telemetry = new RefinementLifecycleTelemetry();

        telemetry.recordDequeued(2);
        telemetry.recordOutcome(2, RefinementOutcome.blockedParent(new SectionPos(0, 0, 0)));
        telemetry.recordDequeued(2);
        telemetry.recordOutcome(2, RefinementOutcome.published(WriteOutcome.skippedAir()));
        telemetry.recordDequeued(2);
        telemetry.recordOutcome(2, RefinementOutcome.published(WriteOutcome.written(7)));
        telemetry.recordDequeued(2);
        telemetry.recordOutcome(2, RefinementOutcome.failed());

        assertEquals(
                "L4[d0,b0,a0,e0,n0,f0] L3[d0,b0,a0,e0,n0,f0] "
                        + "L2[d4,b1,a0,e1,n1,f1] L1[d0,b0,a0,e0,n0,f0]",
                telemetry.compact());
    }

    @Test
    void alreadyCoveredCountsWithPublishedEmptyAndResetClearsSessionState() {
        RefinementLifecycleTelemetry telemetry = new RefinementLifecycleTelemetry();
        telemetry.recordDequeued(1);
        telemetry.recordOutcome(1, RefinementOutcome.alreadyCovered());

        assertEquals("L4[d0,b0,a0,e0,n0,f0] L3[d0,b0,a0,e0,n0,f0] "
                + "L2[d0,b0,a0,e0,n0,f0] L1[d1,b0,a1,e0,n0,f0]", telemetry.compact());

        telemetry.reset();
        assertEquals("L4[d0,b0,a0,e0,n0,f0] L3[d0,b0,a0,e0,n0,f0] "
                + "L2[d0,b0,a0,e0,n0,f0] L1[d0,b0,a0,e0,n0,f0]", telemetry.compact());
    }

    @Test
    void preservedExistingPublicationCountsAsNonemptyCoverage() {
        RefinementLifecycleTelemetry telemetry = new RefinementLifecycleTelemetry();
        telemetry.recordDequeued(3);
        telemetry.recordOutcome(3, RefinementOutcome.published(WriteOutcome.skippedExists()));

        assertEquals("L4[d0,b0,a0,e0,n0,f0] L3[d1,b0,a0,e0,n1,f0] "
                + "L2[d0,b0,a0,e0,n0,f0] L1[d0,b0,a0,e0,n0,f0]", telemetry.compact());
    }
}
