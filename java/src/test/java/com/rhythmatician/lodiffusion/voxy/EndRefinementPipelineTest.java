package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndRefinementPipelineTest {
    private static final DefaultEndRefinement.Config CONFIG =
            new DefaultEndRefinement.Config(1000, 64, 16, 16, 8192, 0, 3, 10, 0);

    @Test
    void modulePublishesFrontierRefinementThroughOneCompleteBatch() {
        BatchOnlyWriter writer = new BatchOnlyWriter();
        DefaultEndRefinement module = module(writer, (level, origin) -> solid());

        module.observeFrontier(List.of(parent(new SectionPos(0, 0, 0))));
        assertEquals(EndRefinement.StepResult.Status.PROGRESSED, module.advance(frame(1)).status());

        assertEquals(1, writer.batchCommits);
        assertEquals(0, writer.directRegionWrites);
        assertNotNull(writer.intent);
        assertEquals(Level.L1, writer.intent.parentLevel());
        assertEquals(new SectionPos(0, 0, 0), writer.intent.parentOrigin());
        assertEquals(0xFF, writer.intent.demandedChildMask());
    }

    @Test
    void l1FrontierTransactionMaterializesL0ChildrenAndPublishesExactMask() {
        BatchOnlyWriter writer = new BatchOnlyWriter();
        writer.materializeChildren = true;
        DefaultEndRefinement module = module(writer, (level, origin) -> solid());

        module.observeFrontier(List.of(parent(new SectionPos(0, 0, 0))));
        module.advance(frame(1));

        assertEquals(8, writer.childCalls.get());
        assertEquals(Level.L0, writer.lastChildLevel);
        assertEquals(0xFF, writer.intent.demandedChildMask());
        assertEquals(8, module.snapshot().representedChildren());
    }

    @Test
    void productionChildDispatchUsesExactSamplerOnlyForL2ToL1() {
        WorldNoiseAccess noise = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(noise.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        Mockito.doAnswer(invocation -> {
            int chunkX = invocation.getArgument(0);
            int chunkZ = invocation.getArgument(1);
            int minY = invocation.getArgument(2);
            ExactEndL1Candidate.SolidBlockConsumer consumer = invocation.getArgument(4);
            consumer.accept(chunkX << 4, minY, chunkZ << 4, true);
            return null;
        }).when(noise).sampleExactEndBaseTerrainChunk(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.any(), Mockito.any());
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(noise);

        VoxelVolume l1 = session.produceEndRefinementChild(Level.L1, new SectionPos(0, 0, 0));
        assertEquals(16, l1.countNonAir());
        Mockito.verify(noise, Mockito.times(16)).sampleExactEndBaseTerrainChunk(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.any(), Mockito.any());
        Mockito.verify(noise, Mockito.never()).sampleFinalDensity(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());

        VoxelVolume l2 = session.produceEndRefinementChild(Level.L2, new SectionPos(0, 0, 0));
        assertTrue(l2.countNonAir() > 0);
        Mockito.verify(noise, Mockito.atLeastOnce()).sampleFinalDensity(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void exactL1ChildUsesSixteenChunkColumnsBeforeOneBatchHandoff() {
        WorldNoiseAccess noise = Mockito.mock(WorldNoiseAccess.class);
        AtomicInteger columns = new AtomicInteger();
        Mockito.doAnswer(invocation -> {
            columns.incrementAndGet();
            return null;
        }).when(noise).sampleExactEndBaseTerrainChunk(
                Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt(),
                Mockito.any(), Mockito.any());
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(noise);
        BatchOnlyWriter writer = new BatchOnlyWriter();

        ParentRefinementIntent intent = new ParentRefinementIntent(
                new SectionPos(0, 0, 0), Level.L2, 1 << 3,
                session::produceEndRefinementChild);
        writer.materializeChildren = true;
        writer.refineParent(intent);

        assertEquals(1, writer.batchCommits);
        assertEquals(1, writer.childCalls.get());
        assertEquals(Level.L1, writer.lastChildLevel);
        assertEquals(16, columns.get());
        assertEquals(0, writer.directRegionWrites);
    }

    @Test
    void emptyPublicationRemainsTerminalAndIsNotAdvertised() {
        BatchOnlyWriter writer = new BatchOnlyWriter();
        writer.result = ParentRefinementResult.published(WriteOutcome.skippedAir(), 0, 0xFF);
        DefaultEndRefinement module = module(writer, (level, origin) -> solid());

        module.observeFrontier(List.of(parent(new SectionPos(0, 0, 0))));
        module.advance(frame(1));
        module.advance(frame(2));

        assertEquals(0, module.snapshot().representedChildren());
        assertEquals(8, module.snapshot().deterministicEmptyChildren());
        assertEquals(1, writer.batchCommits);
    }

    private static DefaultEndRefinement module(
            VoxelVolumeWriter writer, DefaultEndRefinement.ChildTerrain terrain) {
        return new DefaultEndRefinement(CONFIG, writer::refineParent, terrain,
                origin -> WriteOutcome.written(1), () -> true);
    }

    private static EndRefinement.Frame frame(long time) {
        return new EndRefinement.Frame(
                time, new SectionPos(0, 4, 0), List.of(), false);
    }

    private static VanillaFrontierGuardPlanner.ParentTransaction parent(SectionPos origin) {
        return new VanillaFrontierGuardPlanner.ParentTransaction(origin);
    }

    private static final class BatchOnlyWriter implements VoxelVolumeWriter {
        int batchCommits;
        int directRegionWrites;
        ParentRefinementIntent intent;
        ParentRefinementResult result;
        boolean materializeChildren;
        final AtomicInteger childCalls = new AtomicInteger();
        Level lastChildLevel;

        @Override
        public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
            throw new AssertionError("refinement must not write isolated sections");
        }

        @Override
        public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
            directRegionWrites++;
            throw new AssertionError("refinement must publish through one parent batch");
        }

        @Override
        public boolean hasRegionCoverage(SectionPos origin, Level level) {
            return true;
        }

        @Override
        public ParentRefinementResult refineParent(ParentRefinementIntent intent) {
            batchCommits++;
            this.intent = intent;
            if (materializeChildren) {
                Level child = Level.values()[intent.parentLevel().value() - 1];
                List<SectionPos> origins = ParentRefinementBatch.childOrigins(
                        intent.parentOrigin(), intent.parentLevel());
                for (int octant = 0; octant < 8; octant++) {
                    if ((intent.demandedChildMask() & (1 << octant)) == 0) continue;
                    intent.childVolumes().produce(child, origins.get(octant));
                    childCalls.incrementAndGet();
                    lastChildLevel = child;
                }
            }
            return result != null ? result : ParentRefinementResult.published(
                    WriteOutcome.written(Integer.bitCount(intent.demandedChildMask())),
                    intent.demandedChildMask(), 0);
        }
    }

    private static VoxelVolume solid() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
