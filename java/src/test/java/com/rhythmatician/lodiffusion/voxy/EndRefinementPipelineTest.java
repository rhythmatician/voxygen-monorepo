package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.HashSet;
import java.util.Set;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyDemandKind;
import net.lodiffusion.shadow.VoxyDemandSource;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import net.lodiffusion.shadow.VoxyWorkKind;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndRefinementPipelineTest {
    @BeforeEach void setUp() { ShadowRouterJobQueue.clear(); }
    @AfterEach void tearDown() { ShadowRouterJobQueue.clear(); }

    @Test
    void parentTransactionWritesEightChildrenAndPublishesExactMask() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L4, solidVolume());
        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(4, true), writer).status());
        assertEquals(5, writer.regionRecords().size());
        assertEquals(0x0F, writer.committedChildMask(new SectionPos(0, 0, 0), Level.L4));
        assertTrue(writer.regionRecords().stream().skip(1).allMatch(r -> r.level() == Level.L3));
        Set<SectionPos> childOrigins = new HashSet<>();
        writer.regionRecords().stream().skip(1).forEach(r -> childOrigins.add(r.origin()));
        assertEquals(Set.of(
                new SectionPos(0, 0, 0), new SectionPos(16, 0, 0),
                new SectionPos(0, 0, 16), new SectionPos(16, 0, 16)), childOrigins);
    }

    @Test
    void finerTransactionIsBlockedUntilCoarserTransactionHasWrittenChildren() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        assertEquals(RefinementOutcome.Status.BLOCKED_PARENT,
                session.processEndRefinementRequest(request(3, true), writer).status());
        assertEquals(0, writer.regionRecords().size());
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L4, solidVolume());
        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(4, true), writer).status());
        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(3, true), writer).status());
    }

    @Test
    void missingGuardParentQueuesGuardPrerequisiteAheadOfBlockedDescendant() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        VoxyRequestDecoder.VoxyNodeRequest guard = request(3, true);
        guard.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;
        guard.demandSource = VoxyDemandSource.VANILLA_OCCUPANCY_BOUNDARY;

        assertEquals(RefinementOutcome.Status.BLOCKED_PARENT,
                session.processEndRefinementRequest(guard, writer).status());
        ShadowRouterJobQueue.requeue(guard);

        VoxyRequestDecoder.VoxyNodeRequest prerequisite = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(prerequisite);
        assertEquals(Level.L4.value(), prerequisite.lodLevel);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, prerequisite.demandKind);
        assertEquals(VoxyWorkKind.PARENT_REFINEMENT, prerequisite.workKind);
        assertEquals(VoxyDemandSource.PARENT_DEPENDENCY, prerequisite.demandSource);
    }

    @Test
    void missingL4ForGuardQueuesGuardHorizonLeaf() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        VoxyRequestDecoder.VoxyNodeRequest guard = request(4, true);
        guard.demandKind = VoxyDemandKind.VANILLA_FRONTIER_GUARD;

        assertEquals(RefinementOutcome.Status.BLOCKED_PARENT,
                session.processEndRefinementRequest(guard, writer).status());

        VoxyRequestDecoder.VoxyNodeRequest prerequisite = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(prerequisite);
        assertEquals(VoxyDemandKind.VANILLA_FRONTIER_GUARD, prerequisite.demandKind);
        assertEquals(VoxyWorkKind.HORIZON_LEAF, prerequisite.workKind);
    }

    @Test
    void missingVisualParentKeepsVisualPrerequisite() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();

        assertEquals(RefinementOutcome.Status.BLOCKED_PARENT,
                session.processEndRefinementRequest(request(3, true), writer).status());

        VoxyRequestDecoder.VoxyNodeRequest prerequisite = ShadowRouterJobQueue.dequeueAny();
        assertNotNull(prerequisite);
        assertEquals(VoxyDemandKind.VISUAL_REFINEMENT, prerequisite.demandKind);
        assertEquals(VoxyWorkKind.PARENT_REFINEMENT, prerequisite.workKind);
    }

    @Test
    void unflaggedLegacyChildDemandIsConvertedToParentTransaction() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L4, solidVolume());
        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(3, false), writer).status());
        assertEquals(5, writer.regionRecords().size());
    }

    @Test
    void l1ParentTransactionPublishesItsL0Children() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L1, solidVolume());

        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(1, true), writer).status());
        assertEquals(9, writer.regionRecords().size());
        assertEquals(0xFF, writer.committedChildMask(new SectionPos(0, 0, 0), Level.L1));
        assertTrue(writer.regionRecords().stream().skip(1).allMatch(r -> r.level() == Level.L0));
    }

    @Test
    void nativeL0DemandIsConvertedToAnL1ParentTransaction() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L1, solidVolume());

        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(0, false), writer).status());
        assertEquals(0xFF, writer.committedChildMask(new SectionPos(0, 0, 0), Level.L1));
    }

    @Test
    void sessionPublishesRefinementOnlyThroughOneCompleteBatch() {
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(solidNoise());
        BatchOnlyWriter writer = new BatchOnlyWriter();

        assertEquals(RefinementOutcome.Status.PUBLISHED,
                session.processEndRefinementRequest(request(4, true), writer).status());
        assertEquals(1, writer.batchCommits);
        assertEquals(0, writer.directRegionWrites);
        assertNotNull(writer.intent);
        assertEquals(Level.L4, writer.intent.parentLevel());
        assertEquals(new SectionPos(0, 0, 0), writer.intent.parentOrigin());
    }

    private static final class BatchOnlyWriter implements VoxelVolumeWriter {
        int batchCommits;
        int directRegionWrites;
        ParentRefinementIntent intent;

        @Override
        public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
            throw new AssertionError("refinement must not write sections");
        }

        @Override
        public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
            directRegionWrites++;
            throw new AssertionError("GenerationSession must delegate child writes to the batch");
        }

        @Override
        public boolean hasRegionCoverage(SectionPos origin, Level level) {
            return true;
        }

        @Override
        public ParentRefinementResult refineParent(ParentRefinementIntent intent) {
            batchCommits++;
            this.intent = intent;
            return ParentRefinementResult.published(WriteOutcome.written(1));
        }
    }

    private static VoxyRequestDecoder.VoxyNodeRequest request(int level, boolean transaction) {
        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = level;
        request.demandKind = transaction
                ? VoxyDemandKind.VISUAL_REFINEMENT : VoxyDemandKind.HORIZON_COVERAGE;
        request.workKind = transaction
                ? net.lodiffusion.shadow.VoxyWorkKind.PARENT_REFINEMENT
                : net.lodiffusion.shadow.VoxyWorkKind.HORIZON_LEAF;
        return request;
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
