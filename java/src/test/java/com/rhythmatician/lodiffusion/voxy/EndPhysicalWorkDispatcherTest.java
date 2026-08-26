package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import net.lodiffusion.shadow.VoxyRequestDecoder;
import net.lodiffusion.shadow.VoxyWorkKind;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

class EndPhysicalWorkDispatcherTest {
    @Test
    void diagnosticAdmissionGateBlocksRefinementButLeavesHorizonMutationEnabled() {
        String property = RefinementAdmissionGate.DISABLE_PROPERTY;
        String previous = System.getProperty(property);
        System.setProperty(property, "true");
        try {
            GenerationSession session = session();
            CountingWriter writer = new CountingWriter();
            int[] horizonCalls = {0};

            assertEquals(GenerationSession.DemandProcessResult.SKIPPED,
                    session.processEndPhysicalWork(
                            request(Level.L4.value(), VoxyWorkKind.PARENT_REFINEMENT),
                            writer,
                            () -> GenerationSession.DemandProcessResult.FAILED));
            assertEquals(GenerationSession.DemandProcessResult.WRITTEN,
                    session.processEndPhysicalWork(
                            request(Level.L4.value(), VoxyWorkKind.HORIZON_LEAF),
                            writer,
                            () -> {
                                horizonCalls[0]++;
                                return GenerationSession.DemandProcessResult.WRITTEN;
                            }));

            assertEquals(0, writer.refinementIntents);
            assertEquals(1, horizonCalls[0]);
            assertEquals(summaryForLevel4(1, 0, 1, 0, 0, 0),
                    session.refinementLifecycleSummaryForTest());
        } finally {
            restoreProperty(property, previous);
        }
    }

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
        assertEquals(summaryForLevel4(1, 0, 0, 0, 1, 0),
                session.refinementLifecycleSummaryForTest());
    }

    @Test
    void workerBoundaryAttributesBlockedAndPreconditionFailureExactlyOnce() {
        GenerationSession blockedSession = session();
        CountingWriter blockedWriter = new CountingWriter();
        blockedWriter.result = ParentRefinementResult.parentMissing();

        assertEquals(GenerationSession.DemandProcessResult.DEFERRED,
                blockedSession.processEndPhysicalWork(
                        request(Level.L3.value(), VoxyWorkKind.PARENT_REFINEMENT),
                        blockedWriter,
                        () -> GenerationSession.DemandProcessResult.FAILED));
        assertEquals("L4[d0,b0,a0,e0,n0,f0] L3[d1,b1,a0,e0,n0,f0] "
                        + "L2[d0,b0,a0,e0,n0,f0] L1[d0,b0,a0,e0,n0,f0]",
                blockedSession.refinementLifecycleSummaryForTest());

        GenerationSession failedSession = session();
        assertEquals(GenerationSession.DemandProcessResult.FAILED,
                failedSession.processEndPhysicalWork(
                        request(Level.L2.value(), VoxyWorkKind.PARENT_REFINEMENT),
                        null,
                        () -> GenerationSession.DemandProcessResult.FAILED));
        assertEquals("L4[d0,b0,a0,e0,n0,f0] L3[d0,b0,a0,e0,n0,f0] "
                        + "L2[d1,b0,a0,e0,n0,f1] L1[d0,b0,a0,e0,n0,f0]",
                failedSession.refinementLifecycleSummaryForTest());
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
        ParentRefinementResult result = ParentRefinementResult.published(WriteOutcome.written(1));

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
            return result;
        }
    }

    private static String summaryForLevel4(
            int dequeued, int blocked, int already, int empty, int nonempty, int failed) {
        return "L4[d" + dequeued + ",b" + blocked + ",a" + already + ",e" + empty
                + ",n" + nonempty + ",f" + failed + "] L3[d0,b0,a0,e0,n0,f0] "
                + "L2[d0,b0,a0,e0,n0,f0] L1[d0,b0,a0,e0,n0,f0]";
    }

    private static void restoreProperty(String property, String previous) {
        if (previous == null) {
            System.clearProperty(property);
        } else {
            System.setProperty(property, previous);
        }
    }
}
