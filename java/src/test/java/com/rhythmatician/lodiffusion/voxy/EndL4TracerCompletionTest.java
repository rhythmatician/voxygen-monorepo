package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

import com.rhythmatician.lodiffusion.util.PerformanceMonitor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Fixed initial-horizon completion behavior driven through {@link EndRefinement}. */
class EndL4TracerCompletionTest {
    private static final DefaultEndRefinement.Config HORIZON_ONLY =
            new DefaultEndRefinement.Config(1000, 64, 0, 1, 8192, 0, 1, 1, 121);
    private static final SectionPos PLAYER = new SectionPos(0, 6, 0);

    @BeforeEach
    void resetMonitor() {
        PerformanceMonitor.reset();
    }

    @Test
    void exactly121WrittenTargetsProduceOneTimestampedSuccess() throws Exception {
        GenerationSession session = completionSession();
        DefaultEndRefinement module = module(session, WriteOutcome.written(1));

        drive(session, module, 121);

        GenerationSession.TracerCompletion completion = session.tracerCompletion();
        assertNotNull(completion);
        assertEquals("SUCCESS", completion.status());
        assertEquals(121, completion.written());
        assertEquals(0, completion.skipped());
        assertEquals(0, completion.failed());
        assertEquals(121, completion.written() + completion.skipped() + completion.failed());
        assertNotNull(java.time.Instant.parse(completion.atIsoInstant()));
        assertEquals(121, PerformanceMonitor.getCounter(
                PerformanceMonitor.TRACER_HORIZON_WRITTEN));
        assertEquals(1, PerformanceMonitor.getCounter(
                PerformanceMonitor.TRACER_HORIZON_STATUS_SUCCESS));

        advance(session, module, 122, targets());
        assertSame(completion, session.tracerCompletion(), "completion is emitted once");
    }

    @Test
    void exactly121SkippedTargetsStillProduceSuccess() throws Exception {
        GenerationSession session = completionSession();
        DefaultEndRefinement module = module(session, WriteOutcome.skippedAir());

        drive(session, module, 121);

        GenerationSession.TracerCompletion completion = session.tracerCompletion();
        assertNotNull(completion);
        assertEquals("SUCCESS", completion.status());
        assertEquals(0, completion.written());
        assertEquals(121, completion.skipped());
        assertEquals(0, completion.failed());
        assertEquals(121, PerformanceMonitor.getCounter(
                PerformanceMonitor.TRACER_HORIZON_SKIPPED));
    }

    @Test
    void failuresAreDisjointAndMakeTheFixedBatchFail() throws Exception {
        GenerationSession session = completionSession();
        AtomicInteger calls = new AtomicInteger();
        DefaultEndRefinement module = new DefaultEndRefinement(HORIZON_ONLY,
                intent -> ParentRefinementResult.parentMissing(),
                (level, origin) -> VoxelVolume.uniform(32, 0, 0), origin -> {
                    if (calls.getAndIncrement() >= 118) {
                        throw new IllegalStateException("expected failure");
                    }
                    return WriteOutcome.written(1);
                });

        drive(session, module, 121);

        GenerationSession.TracerCompletion completion = session.tracerCompletion();
        assertNotNull(completion);
        assertEquals("FAILED", completion.status());
        assertEquals(118, completion.written());
        assertEquals(0, completion.skipped());
        assertEquals(3, completion.failed());
        assertEquals(121, completion.written() + completion.skipped() + completion.failed());
        assertEquals(3, PerformanceMonitor.getCounter(
                PerformanceMonitor.TRACER_HORIZON_FAILED));
        assertEquals(0, PerformanceMonitor.getCounter(
                PerformanceMonitor.TRACER_HORIZON_STATUS_SUCCESS));
    }

    @Test
    void stoppingBefore121ProducesNoTerminalCompletion() throws Exception {
        GenerationSession session = completionSession();
        DefaultEndRefinement module = module(session, WriteOutcome.written(1));

        drive(session, module, 10);
        assertNull(session.tracerCompletion());
        assertEquals(EndRefinement.StepResult.Status.STOPPED,
                module.advance(new EndRefinement.Frame(11, PLAYER, List.of(), true)).status());
        assertNull(session.tracerCompletion());
    }

    private static DefaultEndRefinement module(
            GenerationSession session, WriteOutcome outcome) {
        return new DefaultEndRefinement(HORIZON_ONLY,
                intent -> ParentRefinementResult.parentMissing(),
                (level, origin) -> VoxelVolume.uniform(32, 0, 0), origin -> {
                    return outcome;
                });
    }

    private static void drive(
            GenerationSession session, DefaultEndRefinement module, int count) {
        List<SectionPos> targets = targets();
        for (int index = 0; index < count; index++) {
            advance(session, module, index + 1, targets);
        }
    }

    private static void advance(
            GenerationSession session,
            DefaultEndRefinement module,
            long monotonicMillis,
            List<SectionPos> targets) {
        module.advance(new EndRefinement.Frame(monotonicMillis, PLAYER, targets, false));
        session.observeEndRefinementSnapshotForTest(module.snapshot());
    }

    private static List<SectionPos> targets() {
        List<SectionPos> targets = new ArrayList<>(121);
        for (int z = -5; z <= 5; z++) {
            for (int x = -5; x <= 5; x++) {
                targets.add(new SectionPos(
                        x * Level.L4.regionSections(), 0,
                        z * Level.L4.regionSections()));
            }
        }
        return targets;
    }

    private static GenerationSession completionSession() throws Exception {
        GenerationSession session = new GenerationSession();
        session.resetTracerCompletionForTest();
        Field start = GenerationSession.class.getDeclaredField("tracerStartMs");
        start.setAccessible(true);
        start.setLong(session, System.currentTimeMillis());
        return session;
    }

}
