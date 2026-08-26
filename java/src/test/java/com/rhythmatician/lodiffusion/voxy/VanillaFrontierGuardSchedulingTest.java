package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyDemandKind;
import net.lodiffusion.shadow.VoxyDemandSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VanillaFrontierGuardSchedulingTest {
    @BeforeEach void setUp() { ShadowRouterJobQueue.clear(); }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void queuesEachL1TransactionOncePerFrontierEpoch() {
        GenerationSession session = new GenerationSession();
        var snapshot = new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 8, 8);

        int first = session.enqueueVanillaFrontierGuardForTest(snapshot, 0);
        int duplicate = session.enqueueVanillaFrontierGuardForTest(snapshot, 0);
        int nextTile = session.enqueueVanillaFrontierGuardForTest(
                new VanillaFrontierGuardPlanner.FrontierSnapshot(64, 0, 0, 0, 8, 8), 0);

        assertEquals(0, duplicate);
        assertEquals(true, first > nextTile && nextTile > 0,
                "moving one L1 tile queues only the newly exposed guard strip");
        var request = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(request);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, request.demandKind);
        assertEquals(VoxyDemandSource.VANILLA_RADIUS_ANNULUS, request.demandSource);
        assertEquals(Level.L1.value(), request.lodLevel);
        assertEquals(0, request.worldY);
    }

    @Test
    void failedGuardIsEligibleAgainAfterTheFrontierEpochChanges() {
        GenerationSession session = new GenerationSession();
        var initial = new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 8, 8);
        session.enqueueVanillaFrontierGuardForTest(initial, 0);
        var failed = takeGuard(2, 0, 0);
        assertNotNull(failed);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, failed.demandKind);
        ShadowRouterJobQueue.markCompleted(failed);
        session.recordFrontierGuardOutcomeForTest(failed, RefinementOutcome.failed());

        int retryEpoch = session.enqueueVanillaFrontierGuardForTest(
                new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 9, 8), 0);

        assertTrue(retryEpoch > 0);
        assertTrue(drainContains(ShadowRouterJobQueue.size(), failed));
    }

    private static boolean drainContains(int count, net.lodiffusion.shadow.VoxyRequestDecoder.VoxyNodeRequest target) {
        for (int index = 0; index < count; index++) {
            var request = ShadowRouterJobQueue.dequeueAny();
            if (request == null) return false;
            ShadowRouterJobQueue.markCompleted(request);
            if (request.lodLevel == target.lodLevel && request.worldX == target.worldX
                    && request.worldY == target.worldY && request.worldZ == target.worldZ
                    && request.demandKind == target.demandKind) {
                return true;
            }
        }
        return false;
    }

    private static net.lodiffusion.shadow.VoxyRequestDecoder.VoxyNodeRequest takeGuard(
            int x, int y, int z) {
        int count = ShadowRouterJobQueue.size();
        for (int index = 0; index < count; index++) {
            var request = ShadowRouterJobQueue.dequeueAny();
            if (request == null) return null;
            if (request.worldX == x && request.worldY == y && request.worldZ == z) {
                return request;
            }
            ShadowRouterJobQueue.markCompleted(request);
        }
        return null;
    }
}
