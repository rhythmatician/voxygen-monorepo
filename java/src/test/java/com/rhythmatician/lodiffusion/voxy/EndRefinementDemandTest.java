package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyDemandKind;
import net.lodiffusion.shadow.VoxyDemandSource;
import net.lodiffusion.shadow.VoxyWorkKind;
import net.minecraft.util.math.BlockPos;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndRefinementDemandTest {
    @BeforeEach void setUp() {
        ShadowRouterJobQueue.clear();
        ShadowRouterJobQueue.updatePlayerSection(0, 0);
    }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void selectorQueuesParentTransactionsWithCoarseCoverageFirst() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        int enqueued = session.enqueueEndRefinementsForTest(
                List.of(new SectionPos(0, 0, 0)), 256, 96, 256,
                Level.L1.value(), 1e9, 64);
        assertEquals(64, enqueued);
        var first = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(first);
        assertEquals(4, first.lodLevel);
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, first.demandKind);
        ShadowRouterJobQueue.markCompleted(first);
        var next = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(next);
        assertEquals(3, next.lodLevel);
    }

    @Test
    void frontierSelectorDemandIsAdmittedBeforeOrdinaryVisualWork() {
        GenerationSession session = new GenerationSession();
        session.observeVanillaChunkColumnForTest(0, 0);
        ShadowRouterJobQueue.clear();

        assertEquals(1, session.enqueueEndRefinementsForTest(
                List.of(new SectionPos(0, 0, 0)), 256, 96, 256,
                Level.L1.value(), 1e9, 1));

        var first = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(first);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, first.demandKind);
        assertEquals(VoxyDemandSource.SCREEN_SPACE_SELECTOR, first.demandSource);
    }

    @Test
    void fullyVanillaSelectorParentIsSkippedRegardlessOfSource() {
        GenerationSession session = new GenerationSession();
        for (int chunkX = 0; chunkX < 4; chunkX++) {
            for (int chunkZ = 0; chunkZ < 4; chunkZ++) {
                session.observeVanillaChunkColumnForTest(chunkX, chunkZ);
            }
        }
        ShadowRouterJobQueue.clear();

        session.enqueueEndRefinementsForTest(
                List.of(new SectionPos(0, 0, 0)), 8, 8, 8,
                Level.L0.value(), 1e9, Integer.MAX_VALUE);

        while (ShadowRouterJobQueue.hasWork()) {
            var request = ShadowRouterJobQueue.dequeueAny();
            assertFalse(request.lodLevel == Level.L1.value()
                            && request.worldX == 0 && request.worldY == 0 && request.worldZ == 0,
                    "fully vanilla parent must not consume refinement work");
            ShadowRouterJobQueue.markCompleted(request);
        }
    }

    @Test
    void budgetAppliesAfterDedupAndLaterPassAdvances() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        var regions = List.of(new SectionPos(0, 0, 0));
        int first = session.enqueueEndRefinementsForTest(regions, 256, 96, 256,
                Level.L1.value(), 1e9, 8);
        var firstKeys = drain(first);
        int second = session.enqueueEndRefinementsForTest(regions, 256, 96, 256,
                Level.L1.value(), 1e9, 8);
        var secondKeys = drain(second);
        assertEquals(8, first);
        assertEquals(8, second);
        assertTrue(firstKeys.stream().noneMatch(secondKeys::contains));
    }

    @Test
    void scheduledSelectionAdmitsBoundedVisualWorkWhileCoverageQueueIsBusy() {
        GenerationSession session = new GenerationSession();
        session.updatePlayerPosition(new BlockPos(0, 96, 0));
        for (int x = 0; x < 32; x++) {
            ShadowRouterJobQueue.enqueue(
                    request(Level.L4.value(), x, VoxyDemandKind.HORIZON_COVERAGE));
        }

        int first = session.runScheduledEndSelectionForTest(10_000L, 7);
        int depthAfterFirst = ShadowRouterJobQueue.size();
        int throttled = session.runScheduledEndSelectionForTest(10_001L, 7);
        int second = session.runScheduledEndSelectionForTest(
                10_000L + GenerationSession.selectionIntervalMsForTest(), 7);

        assertEquals(7, first);
        assertEquals(0, throttled, "a busy worker loop must not select on every iteration");
        assertEquals(depthAfterFirst, ShadowRouterJobQueue.size() - second);
        assertEquals(7, second, "the next bounded pass should advance after the cadence interval");
        assertEquals(14, ShadowRouterJobQueue.demandMetrics().visual().queued());
        assertTrue(ShadowRouterJobQueue.demandMetrics().horizon().queuedDepth() > 0,
                "visual admission must not require an empty coverage queue");
    }

    @Test
    void scheduledSelectionCapsVisualOutstandingAtConfiguredTarget() {
        GenerationSession session = new GenerationSession();
        session.updatePlayerPosition(new BlockPos(0, 96, 0));

        int first = session.runScheduledEndSelectionForTest(10_000L, 256);
        int whileFull = session.runScheduledEndSelectionForTest(
                10_000L + GenerationSession.selectionIntervalMsForTest(), 256);
        var visual = ShadowRouterJobQueue.demandMetrics().visual();

        assertEquals(GenerationSession.visualWorkingSetTargetForTest(), first);
        assertEquals(0, whileFull);
        assertEquals(GenerationSession.visualWorkingSetTargetForTest(),
                visual.queuedDepth() + visual.inFlightDepth());
    }

    @Test
    void scheduledSelectionRefillsOnlyCapacityOpenedByCompletions() {
        GenerationSession session = new GenerationSession();
        session.updatePlayerPosition(new BlockPos(0, 96, 0));
        assertEquals(16, session.runScheduledEndSelectionForTest(10_000L, 256));

        for (int completed = 0; completed < 5; completed++) {
            var request = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(request);
            assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, request.demandKind);
            ShadowRouterJobQueue.markCompleted(request);
        }

        int refill = session.runScheduledEndSelectionForTest(
                10_000L + GenerationSession.selectionIntervalMsForTest(), 256);
        var visual = ShadowRouterJobQueue.demandMetrics().visual();
        assertEquals(5, refill);
        assertEquals(16, visual.queuedDepth() + visual.inFlightDepth());
    }

    @Test
    void guardOverlapIsRememberedWithoutConsumingVisualCapacityOrFlooding() {
        GenerationSession session = new GenerationSession();
        session.updatePlayerPosition(new BlockPos(0, 96, 0));
        for (var emission : originSelections().subList(0, 6)) {
            var node = emission.request();
            var guard = request(node.level(), node.wsX(), VoxyDemandKind.VANILLA_FRONTIER_GUARD);
            guard.worldY = node.wsY();
            guard.worldZ = node.wsZ();
            guard.workKind = VoxyWorkKind.PARENT_REFINEMENT;
            guard.demandSource = VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY;
            assertEquals(ShadowRouterJobQueue.EnqueueResult.QUEUED,
                    ShadowRouterJobQueue.enqueue(guard));
        }

        int scheduled = session.runScheduledEndSelectionForTest(10_000L, 256);
        int repeated = session.runScheduledEndSelectionForTest(
                10_000L + GenerationSession.selectionIntervalMsForTest(), 256);
        var visual = ShadowRouterJobQueue.demandMetrics().visual();

        assertEquals(16, scheduled);
        assertEquals(0, repeated);
        assertEquals(16, visual.queuedDepth() + visual.inFlightDepth());
        assertEquals(22, ShadowRouterJobQueue.size(),
                "six represented guards plus the sixteen-item visual working set");
    }

    @Test
    void rejectedSelectorCandidateCanBeRetriedAfterPlayerMovesIntoRange() {
        GenerationSession session = new GenerationSession();
        var farRegion = List.of(new SectionPos(10, 0, 0));
        ShadowRouterJobQueue.updatePlayerSection(0, 0);

        int rejected = session.enqueueEndRefinementsForTest(
                farRegion, 5_376, 96, 256, Level.L3.value(), 1e9, 1);
        ShadowRouterJobQueue.updatePlayerSection(320, 0);
        int accepted = session.enqueueEndRefinementsForTest(
                farRegion, 5_376, 96, 256, Level.L3.value(), 1e9, 1);

        assertEquals(0, rejected);
        assertEquals(1, accepted,
                "a rejected candidate must not enter permanent selector dedup");
    }

    @Test
    void blockedSelectorBitsAreEligibleForARealRetry() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        var regions = List.of(new SectionPos(0, 0, 0));
        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
        var request = ShadowRouterJobQueue.dequeueAny();

        assertEquals(GenerationSession.DemandProcessResult.DEFERRED,
                session.processEndPhysicalWork(
                        request, new InMemoryVolumeWriter(),
                        () -> GenerationSession.DemandProcessResult.FAILED));
        ShadowRouterJobQueue.markCompleted(request);

        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
    }

    @Test
    void failedSelectorBitsAreEligibleForARealRetry() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        var regions = List.of(new SectionPos(0, 0, 0));
        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
        var request = ShadowRouterJobQueue.dequeueAny();

        assertEquals(GenerationSession.DemandProcessResult.FAILED,
                session.processEndPhysicalWork(
                        request, null,
                        () -> GenerationSession.DemandProcessResult.FAILED));
        ShadowRouterJobQueue.markFailed(request);

        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
    }

    @Test
    void deterministicEmptyPublicationRemainsRepresented() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        var regions = List.of(new SectionPos(0, 0, 0));
        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
        var request = ShadowRouterJobQueue.dequeueAny();
        VoxelVolumeWriter writer = Mockito.mock(VoxelVolumeWriter.class);
        Mockito.when(writer.refineParent(Mockito.any()))
                .thenReturn(ParentRefinementResult.published(WriteOutcome.skippedAir()));

        assertEquals(GenerationSession.DemandProcessResult.WRITTEN,
                session.processEndPhysicalWork(
                        request, writer,
                        () -> GenerationSession.DemandProcessResult.FAILED));
        ShadowRouterJobQueue.markCompleted(request);

        assertEquals(1, session.enqueueEndRefinementsForTest(
                regions, 256, 96, 256, Level.L1.value(), 1e9, 1));
        var next = ShadowRouterJobQueue.dequeueAny();
        assertFalse(next.lodLevel == request.lodLevel
                        && next.worldX == request.worldX
                        && next.worldY == request.worldY
                        && next.worldZ == request.worldZ,
                "deterministic empty bits must remain represented");
    }

    @Test
    void queuedL0RefinementReachesAnL1ToL0BatchDespiteRenewedL4Coverage() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L1, solidVolume());

        ShadowRouterJobQueue.enqueue(request(Level.L1.value(), 0, VoxyDemandKind.VISUAL_REFINEMENT));
        ShadowRouterJobQueue.enqueue(request(Level.L4.value(), 0, VoxyDemandKind.HORIZON_COVERAGE));
        ShadowRouterJobQueue.markCompleted(ShadowRouterJobQueue.dequeueAny());

        var refinement = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(refinement);
        assertEquals(Level.L1.value(), refinement.lodLevel);
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, refinement.demandKind);
        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(refinement, writer).status());
        ShadowRouterJobQueue.markCompleted(refinement);

        assertEquals(0xFF, writer.committedChildMask(new SectionPos(0, 0, 0), Level.L1));
        assertEquals(9, writer.regionRecords().size());
        assertTrue(writer.regionRecords().stream().skip(1).allMatch(r -> r.level() == Level.L0));
    }

    private static net.lodiffusion.shadow.VoxyRequestDecoder.VoxyNodeRequest request(
            int level, int x, VoxyDemandKind demandKind) {
        var request = new net.lodiffusion.shadow.VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = level;
        request.worldX = x;
        request.demandKind = demandKind;
        request.workKind = demandKind == VoxyDemandKind.HORIZON_COVERAGE
                ? net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF
                : net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT;
        return request;
    }

    private static List<RefinementDemandSelector.Emission> originSelections() {
        return RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                8, 104, 8,
                GenerationSession.REFINEMENT_FOCAL_PX,
                GenerationSession.REFINEMENT_SUB_DIV_PX,
                Level.L0.value(), GenerationSession.DEFAULT_REFINEMENT_RENDER_DISTANCE,
                256, List.of(new SectionPos(0, 0, 0))));
    }

    private static java.util.Set<String> drain(int count) {
        var keys = new java.util.HashSet<String>();
        for (int i = 0; i < count; i++) {
            var request = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(request);
            keys.add(request.lodLevel + ":" + request.worldX + ":"
                    + request.worldY + ":" + request.worldZ);
            ShadowRouterJobQueue.markCompleted(request);
        }
        return keys;
    }

    private static WorldNoiseAccess solidNoise() {
        WorldNoiseAccess noise = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(noise.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        return noise;
    }

    private static VoxelVolume solidVolume() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
