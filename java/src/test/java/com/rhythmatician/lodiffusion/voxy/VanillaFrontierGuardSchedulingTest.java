package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class VanillaFrontierGuardSchedulingTest {
    @BeforeEach void setUp() { ShadowRouterJobQueue.clear(); }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void routesEachL1TransactionDirectlyIntoTheEndModuleOncePerFrontierEpoch() {
        GenerationSession session = endSession();
        var snapshot = new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 8, 8);

        int first = session.enqueueVanillaFrontierGuardForTest(snapshot, 0);
        int duplicate = session.enqueueVanillaFrontierGuardForTest(snapshot, 0);
        int nextTile = session.enqueueVanillaFrontierGuardForTest(
                new VanillaFrontierGuardPlanner.FrontierSnapshot(64, 0, 0, 0, 8, 8), 0);

        assertEquals(0, duplicate);
        assertTrue(first > nextTile && nextTile > 0,
                "moving one L1 tile admits only the newly exposed guard strip");
        assertEquals(0, ShadowRouterJobQueue.size(),
                "End frontier guards must not create an ignored Shadow queue lane");
        EndRefinement.Snapshot state = session.endRefinementSnapshotForTest();
        assertNotNull(state);
        assertEquals(first + nextTile, state.refinement().admitted());
    }

    @Test
    void changedFrontierEpochIsDeduplicatedByParentInsideTheModule() {
        GenerationSession session = endSession();
        int first = session.enqueueVanillaFrontierGuardForTest(
                new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 8, 8), 0);
        long admitted = session.endRefinementSnapshotForTest().refinement().admitted();

        int expanded = session.enqueueVanillaFrontierGuardForTest(
                new VanillaFrontierGuardPlanner.FrontierSnapshot(0, 0, 0, 0, 9, 8), 0);

        assertTrue(first > 0);
        assertTrue(expanded > 0);
        assertEquals(admitted + expanded,
                session.endRefinementSnapshotForTest().refinement().admitted());
        assertEquals(0, ShadowRouterJobQueue.size());
    }

    @Test
    void failedFrontierWorkRetriesAfterBackoffAndStopClearsLifecycleState() {
        AtomicInteger attempts = new AtomicInteger();
        DefaultEndRefinement refinement = new DefaultEndRefinement(
                new DefaultEndRefinement.Config(1000, 64, 0, 1, 8192, 0,
                        2, 1, 0),
                intent -> {
                    if (attempts.getAndIncrement() == 0) {
                        throw new IllegalStateException("first attempt fails");
                    }
                    return ParentRefinementResult.published(
                            WriteOutcome.written(1), intent.demandedChildMask(), 0);
                },
                (level, origin) -> VoxelVolume.uniform(32,
                        EndL4DeterministicCandidate.BLOCK_END_STONE, 0),
                origin -> WriteOutcome.written(1));
        var transaction = new VanillaFrontierGuardPlanner.ParentTransaction(
                new SectionPos(0, 0, 0));

        assertEquals(1, refinement.observeFrontier(List.of(transaction)));
        assertEquals(EndRefinement.StepResult.Status.FAILED,
                refinement.advance(frame(1, false)).status());
        assertEquals(8, refinement.snapshot().retryableChildren());
        assertEquals(EndRefinement.StepResult.Status.IDLE,
                refinement.advance(frame(1, false)).status(), "retry waits for backoff");
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED,
                refinement.advance(frame(2, false)).status());
        assertEquals(2, attempts.get());
        assertEquals(0, refinement.snapshot().retryableChildren());
        assertEquals(8, refinement.snapshot().representedChildren());

        assertEquals(EndRefinement.StepResult.Status.STOPPED,
                refinement.advance(frame(3, true)).status());
        assertEquals(0, refinement.snapshot().pendingChildren());
        assertEquals(0, refinement.snapshot().executingChildren());
        assertEquals(0, refinement.snapshot().representedChildren());
        assertEquals(0, refinement.snapshot().retryableChildren());
    }

    private static EndRefinement.Frame frame(long now, boolean stopped) {
        return new EndRefinement.Frame(
                now, new SectionPos(0, 6, 0), List.of(), stopped);
    }

    private static GenerationSession endSession() {
        GenerationSession session = new GenerationSession();
        session.setEndL4TracerModeForTest(true);
        return session;
    }
}
