package net.lodiffusion.shadow;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ShadowRouterJobQueueTest {
    @BeforeEach void setUp() {
        ShadowRouterJobQueue.clear();
        ShadowRouterJobQueue.updatePlayerSection(0, 0);
    }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void regularWorkIsStrictlyCoarseFirst() {
        enqueue(4, 0);
        enqueue(3, 0);
        enqueue(2, 0);
        enqueue(1, 0);
        assertEquals(4, take().lodLevel);
        assertEquals(3, take().lodLevel);
        assertEquals(2, take().lodLevel);
        assertEquals(1, take().lodLevel);
    }

    @Test
    void distancePriorityRemainsWithinOneLod() {
        enqueue(2, 5);
        enqueue(2, 0);
        assertEquals(0, take().worldX);
    }

    @Test
    void horizonAndGuardAlternateBeforeVisualRefinement() {
        enqueue(4, 0, VoxyDemandKind.VISUAL_REFINEMENT);
        enqueue(4, 5, VoxyDemandKind.HORIZON_COVERAGE);
        enqueue(1, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind,
                "horizon coverage starts the pair");
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, take().demandKind,
                "a pending guard cannot be starved by watch traffic");
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, take().demandKind);
    }

    @Test
    void nearestGuardBeatsAFartherCoarseGuard() {
        enqueue(4, 1, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        enqueue(1, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);

        var request = take();
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, request.demandKind);
        assertEquals(1, request.lodLevel);
        assertEquals(0, request.worldX);
    }

    @Test
    void equalDistanceGuardsBreakTiesCoarserFirst() {
        enqueue(1, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        enqueue(4, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);

        assertEquals(4, take().lodLevel);
    }

    @Test
    void playerMovementReprioritizesAlreadyQueuedGuards() {
        enqueue(1, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        enqueue(1, 10, VoxyDemandKind.VANILLA_FRONTIER_GUARD);

        ShadowRouterJobQueue.updatePlayerSection(42, 0);

        assertEquals(10, take().worldX);
    }

    @Test
    void guardUpgradesQueuedVisualParentWithoutDuplicatingThePhysicalTransaction() {
        var visual = request(1, 2, VoxyDemandKind.VISUAL_REFINEMENT);
        visual.demandedChildMask = 0x01;
        ShadowRouterJobQueue.enqueue(visual);
        var guard = request(1, 2, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        guard.demandedChildMask = 0x80;

        assertEquals(ShadowRouterJobQueue.EnqueueResult.UPGRADED, ShadowRouterJobQueue.enqueue(guard));
        assertEquals(1, ShadowRouterJobQueue.size());
        var merged = take();
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, merged.demandKind);
        assertEquals(0x81, merged.demandedChildMask);
    }

    @Test
    void duplicateParentRequestsUnionDemandedOctantsWithoutDuplicatingWork() {
        var first = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        first.demandedChildMask = 0x04;
        var second = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        second.demandedChildMask = 0x20;

        assertEquals(ShadowRouterJobQueue.EnqueueResult.QUEUED, ShadowRouterJobQueue.enqueue(first));
        assertEquals(ShadowRouterJobQueue.EnqueueResult.DUPLICATE, ShadowRouterJobQueue.enqueue(second));
        assertEquals(1, ShadowRouterJobQueue.size());
        assertEquals(0x24, take().demandedChildMask);
    }

    @Test
    void inFlightParentRemainsImmutableAndLateUrgencyBecomesFollowUpWork() {
        var visual = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        visual.demandedChildMask = 0x02;
        ShadowRouterJobQueue.enqueue(visual);
        var inFlight = ShadowRouterJobQueue.dequeueAny();

        var guard = request(2, 3, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        guard.demandedChildMask = 0x40;
        assertEquals(ShadowRouterJobQueue.EnqueueResult.IN_FLIGHT,
                ShadowRouterJobQueue.enqueue(guard));

        assertEquals(0, ShadowRouterJobQueue.size());
        assertEquals(0x02, inFlight.demandedChildMask);
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, inFlight.demandKind);
        ShadowRouterJobQueue.markCompleted(inFlight);

        var followUp = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(followUp);
        assertEquals(0x40, followUp.demandedChildMask);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, followUp.demandKind);
        ShadowRouterJobQueue.markCompleted(followUp);

        var metrics = ShadowRouterJobQueue.demandMetrics();
        assertEquals(1, metrics.visual().queued());
        assertEquals(1, metrics.visual().dequeued());
        assertEquals(1, metrics.visual().completed());
        assertEquals(1, metrics.guard().queued());
        assertEquals(1, metrics.guard().dequeued());
        assertEquals(1, metrics.guard().completed());
    }

    @Test
    void urgencyOnlyArrivalDoesNotRepeatCompletedOctants() {
        var visual = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        visual.demandedChildMask = 0x10;
        ShadowRouterJobQueue.enqueue(visual);
        var active = ShadowRouterJobQueue.dequeueAny();

        var guard = request(2, 3, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        guard.demandedChildMask = 0x10;
        assertEquals(ShadowRouterJobQueue.EnqueueResult.IN_FLIGHT,
                ShadowRouterJobQueue.enqueue(guard));
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, active.demandKind);
        ShadowRouterJobQueue.markCompleted(active);
        assertEquals(0, ShadowRouterJobQueue.size());
    }

    @Test
    void urgencyOnlyArrivalUpgradesABlockedRetry() {
        var visual = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        visual.demandedChildMask = 0x10;
        ShadowRouterJobQueue.enqueue(visual);
        var active = ShadowRouterJobQueue.dequeueAny();

        var guard = request(2, 3, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        guard.demandedChildMask = 0x10;
        assertEquals(ShadowRouterJobQueue.EnqueueResult.IN_FLIGHT,
                ShadowRouterJobQueue.enqueue(guard));
        ShadowRouterJobQueue.requeue(active);

        var retry = ShadowRouterJobQueue.dequeueAny();
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, retry.demandKind);
        assertEquals(0x10, retry.demandedChildMask);
        ShadowRouterJobQueue.markCompleted(retry);
    }

    @Test
    void failedInFlightParentStillDispatchesPendingFollowUp() {
        var active = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        active.demandedChildMask = 0x01;
        ShadowRouterJobQueue.enqueue(active);
        active = ShadowRouterJobQueue.dequeueAny();

        var late = request(2, 3, VoxyDemandKind.VISUAL_REFINEMENT);
        late.demandedChildMask = 0x08;
        assertEquals(ShadowRouterJobQueue.EnqueueResult.IN_FLIGHT,
                ShadowRouterJobQueue.enqueue(late));
        ShadowRouterJobQueue.markFailed(active);

        var followUp = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(followUp);
        assertEquals(0x08, followUp.demandedChildMask);
        ShadowRouterJobQueue.markCompleted(followUp);
    }

    @Test
    void occupancyGuardUpgradesTheSameHorizonLeafAndRunsAtGuardPriority() {
        var horizon = request(4, 2, VoxyDemandKind.HORIZON_COVERAGE);
        horizon.workKind = VoxyWorkKind.HORIZON_LEAF;
        var guard = request(4, 2, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        guard.workKind = VoxyWorkKind.HORIZON_LEAF;

        assertEquals(ShadowRouterJobQueue.EnqueueResult.QUEUED, ShadowRouterJobQueue.enqueue(horizon));
        assertEquals(ShadowRouterJobQueue.EnqueueResult.UPGRADED, ShadowRouterJobQueue.enqueue(guard));
        assertEquals(1, ShadowRouterJobQueue.size());
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, take().demandKind);
    }

    @Test
    void visualRunsOnTheFifthEligibleDequeueWhileCoverageRemainsQueued() {
        for (int x = 0; x < 5; x++) {
            enqueue(4, x, VoxyDemandKind.HORIZON_COVERAGE);
        }
        enqueue(1, 0, VoxyDemandKind.VISUAL_REFINEMENT);

        for (int dispatch = 0; dispatch < 4; dispatch++) {
            assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind);
        }
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, take().demandKind);
        assertEquals(1, ShadowRouterJobQueue.size(), "coverage must still remain after the visual turn");
    }

    @Test
    void guardBeatsContinuedHorizonTrafficWithoutAdmittingVisualRefinement() {
        enqueue(4, 0, VoxyDemandKind.HORIZON_COVERAGE);
        enqueue(4, 1, VoxyDemandKind.HORIZON_COVERAGE);
        enqueue(4, 2, VoxyDemandKind.HORIZON_COVERAGE);
        enqueue(1, 0, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        enqueue(2, 0, VoxyDemandKind.VISUAL_REFINEMENT);

        assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, take().demandKind);
        assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind);
    }

    @Test
    void replenishedHorizonTrafficCannotStarveAQueuedVisualRequest() {
        enqueue(1, 0, VoxyDemandKind.VISUAL_REFINEMENT);
        for (int dispatch = 0; dispatch < 5; dispatch++) {
            enqueue(4, dispatch, VoxyDemandKind.HORIZON_COVERAGE);
            VoxyDemandKind actual = take().demandKind;
            if (dispatch < 4) {
                assertEquals(VoxyDemandKind.HORIZON_COVERAGE, actual);
            } else {
                assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, actual,
                        "the fifth eligible dispatch must not be captured by new horizon work");
            }
        }
    }

    @Test
    void emptyVisualSlotFallsBackToCoverageAndClearResetsTheFairnessCycle() {
        for (int x = 0; x < 5; x++) {
            enqueue(4, x, VoxyDemandKind.HORIZON_COVERAGE);
        }
        for (int dispatch = 0; dispatch < 5; dispatch++) {
            assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind);
        }

        ShadowRouterJobQueue.clear();
        enqueue(4, 1, VoxyDemandKind.HORIZON_COVERAGE);
        enqueue(1, 1, VoxyDemandKind.VISUAL_REFINEMENT);
        assertEquals(VoxyDemandKind.HORIZON_COVERAGE, take().demandKind,
                "clear must not leave a stale visual turn pending");
    }

    @Test
    void demandMetricsSeparateQueueLifecycleAndDepthByDemandKind() {
        var horizon = request(4, 0, VoxyDemandKind.HORIZON_COVERAGE);
        var guard = request(1, 1, VoxyDemandKind.VANILLA_FRONTIER_GUARD);
        var visual = request(2, 2, VoxyDemandKind.VISUAL_REFINEMENT);
        ShadowRouterJobQueue.enqueue(horizon);
        ShadowRouterJobQueue.enqueue(guard);
        ShadowRouterJobQueue.enqueue(visual);

        var first = ShadowRouterJobQueue.dequeueAny();
        ShadowRouterJobQueue.markCompleted(first);
        var second = ShadowRouterJobQueue.dequeueAny();
        ShadowRouterJobQueue.markFailed(second);
        var third = ShadowRouterJobQueue.dequeueAny();
        ShadowRouterJobQueue.markSkippedFull(third);

        var metrics = ShadowRouterJobQueue.demandMetrics();
        assertEquals(1, metrics.horizon().queued());
        assertEquals(1, metrics.horizon().dequeued());
        assertEquals(1, metrics.horizon().completed());
        assertEquals(0, metrics.horizon().failed());
        assertEquals(0, metrics.horizon().queuedDepth());
        assertEquals(1, metrics.guard().queued());
        assertEquals(1, metrics.guard().dequeued());
        assertEquals(0, metrics.guard().completed());
        assertEquals(1, metrics.guard().failed());
        assertEquals(1, metrics.visual().skippedFull());
        assertEquals(0, metrics.visual().queuedDepth());
    }

    private static VoxyRequestDecoder.VoxyNodeRequest take() {
        var request = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(request);
        ShadowRouterJobQueue.markCompleted(request);
        return request;
    }

    private static void enqueue(int lod, int x) {
        enqueue(lod, x, VoxyDemandKind.HORIZON_COVERAGE);
    }

    private static void enqueue(int lod, int x, VoxyDemandKind demandKind) {
        ShadowRouterJobQueue.enqueue(request(lod, x, demandKind));
    }

    private static VoxyRequestDecoder.VoxyNodeRequest request(
            int lod, int x, VoxyDemandKind demandKind) {
        var request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = lod;
        request.worldX = x;
        request.demandKind = demandKind;
        request.workKind = switch (demandKind) {
            case HORIZON_COVERAGE -> VoxyWorkKind.HORIZON_LEAF;
            case VANILLA_FRONTIER_GUARD, VISUAL_REFINEMENT -> VoxyWorkKind.PARENT_REFINEMENT;
        };
        return request;
    }
}
