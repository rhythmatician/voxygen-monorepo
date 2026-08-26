package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.lodiffusion.shadow.VoxyRequestDecoder;
import net.lodiffusion.shadow.VoxyWorkKind;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndPhysicalWorkDispatcherTest {
    @Test
    void finerHorizonLeafCannotReachDirectRegionWrite() {
        GenerationSession session = session();
        CountingWriter writer = new CountingWriter();
        int[] leafCalls = {0};

        for (int level = Level.L0.value(); level <= Level.L3.value(); level++) {
            GenerationSession.DemandProcessResult result = session.processEndPhysicalWork(
                    request(level, VoxyWorkKind.HORIZON_LEAF), writer, () -> {
                        leafCalls[0]++;
                        return GenerationSession.DemandProcessResult.WRITTEN;
                    });
            assertEquals(GenerationSession.DemandProcessResult.SKIPPED, result);
        }

        assertEquals(0, leafCalls[0]);
        assertEquals(0, writer.regionWrites);
    }

    @Test
    void parentRefinementSubmitsExactlyOneIntent() {
        GenerationSession session = session();
        CountingWriter writer = new CountingWriter();

        GenerationSession.DemandProcessResult result = session.processEndPhysicalWork(
                request(Level.L4.value(), VoxyWorkKind.PARENT_REFINEMENT),
                writer,
                () -> GenerationSession.DemandProcessResult.FAILED);

        assertEquals(GenerationSession.DemandProcessResult.WRITTEN, result);
        assertEquals(1, writer.refinementIntents);
        assertEquals(0, writer.regionWrites);
    }

    @Test
    void l4HorizonLeafUsesTheDirectLeafRoute() {
        GenerationSession session = session();
        CountingWriter writer = new CountingWriter();
        int[] leafCalls = {0};

        GenerationSession.DemandProcessResult result = session.processEndPhysicalWork(
                request(Level.L4.value(), VoxyWorkKind.HORIZON_LEAF), writer, () -> {
                    leafCalls[0]++;
                    return GenerationSession.DemandProcessResult.WRITTEN;
                });

        assertEquals(GenerationSession.DemandProcessResult.WRITTEN, result);
        assertEquals(1, leafCalls[0]);
        assertEquals(0, writer.refinementIntents);
    }

    private static GenerationSession session() {
        GenerationSession session = new GenerationSession();
        WorldNoiseAccess noise = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(noise.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        session.setNoiseAccessForTest(noise);
        return session;
    }

    private static VoxyRequestDecoder.VoxyNodeRequest request(int level, VoxyWorkKind kind) {
        VoxyRequestDecoder.VoxyNodeRequest request = new VoxyRequestDecoder.VoxyNodeRequest();
        request.lodLevel = level;
        request.workKind = kind;
        request.worldY = 0;
        return request;
    }

    private static final class CountingWriter implements VoxelVolumeWriter {
        int regionWrites;
        int refinementIntents;

        @Override
        public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
            throw new AssertionError("End physical work must not write sections");
        }

        @Override
        public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
            regionWrites++;
            return WriteOutcome.written(1);
        }

        @Override
        public ParentRefinementResult refineParent(ParentRefinementIntent intent) {
            refinementIntents++;
            return ParentRefinementResult.published(WriteOutcome.written(1));
        }
    }
}
