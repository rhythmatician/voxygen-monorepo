package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.util.PerformanceMonitor;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Finite completion telemetry for End L4 tracer — 121/121 terminal outcomes.
 * Barrier-free observation via TracerCompletion record and PerformanceMonitor.
 */
class EndL4TracerCompletionTest {

    @BeforeEach
    void setUp() {
        ShadowRouterJobQueue.clear();
        PerformanceMonitor.reset();
    }

    @AfterEach
    void tearDown() {
        ShadowRouterJobQueue.clear();
    }

    @Test
    void tracer_success_121_written() throws Exception {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        ShadowRouterJobQueue.clear();
        int enqueued = session.enqueueEndL4TracerRequests();
        assertEquals(121, enqueued);
        assertNull(session.tracerCompletion(), "no terminal before processing");

        // Drive via lightweight record helper to avoid 8192*121 mock invocations (heap)
        for (int i = 0; i < 121; i++) {
            VoxyRequestDecoder.VoxyNodeRequest req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req, "queue should have 121");
            session.recordTracerWrittenForTest();
            ShadowRouterJobQueue.markCompleted(req);
            if (i < 120) {
                assertNull(session.tracerCompletion(), "not terminal before 121 at i=" + i);
            }
        }
        var c = session.tracerCompletion();
        assertNotNull(c, "terminal after 121");
        assertEquals("SUCCESS", c.status());
        assertEquals(121, c.written());
        assertEquals(0, c.skipped());
        assertEquals(0, c.failed());
        assertEquals(121, c.written() + c.skipped() + c.failed(), "processed ==121");
        assertTrue(c.elapsedMs() >= 0);
        assertNotNull(c.atIsoInstant());
        java.time.Instant.parse(c.atIsoInstant());

        assertEquals(121, PerformanceMonitor.getCounter(PerformanceMonitor.TRACER_HORIZON_WRITTEN));
        assertEquals(0, PerformanceMonitor.getCounter(PerformanceMonitor.TRACER_HORIZON_FAILED));

        for (int i = 0; i < 3; i++) {
            VoxyRequestDecoder.VoxyNodeRequest idle = ShadowRouterJobQueue.dequeueAny();
            assertNull(idle, "queue empty after 121");
            assertSame(c, session.tracerCompletion(), "no duplicate terminal on idle tick " + i);
        }

        // Also prove single WRITTEN via real candidate path (one sample, not 121)
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        GenerationSession single = new GenerationSession();
        single.setNoiseAccessForTest(mockNa);
        single.enqueueEndL4TracerRequests(); // reset
        // drain queue but process one via heavy path
        ShadowRouterJobQueue.clear();
        single.enqueueEndL4TracerRequests();
        var oneReq = ShadowRouterJobQueue.dequeueAny();
        single.resetTracerCompletionForTest();
        single.setNoiseAccessForTest(mockNa);
        // need to re-set startMs via record helper? Instead directly test heavy path once
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        WriteOutcome out = single.processTracerRequestForTest(oneReq, w, null);
        assertEquals(WriteOutcome.Status.WRITTEN, out.status());
    }

    @Test
    void tracer_success_withSkippedAir_countsTowardSuccess() throws Exception {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        ShadowRouterJobQueue.clear();
        session.enqueueEndL4TracerRequests();
        assertNull(session.tracerCompletion());
        // 121 SKIPPED_AIR via lightweight helper counts toward success per #127
        for (int i = 0; i < 121; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            session.recordTracerSkippedForTest();
            ShadowRouterJobQueue.markCompleted(req);
        }
        var c = session.tracerCompletion();
        assertNotNull(c);
        assertEquals("SUCCESS", c.status(), "all-air (SKIPPED_AIR) counts toward success");
        assertEquals(0, c.written());
        assertEquals(121, c.skipped());
        assertEquals(0, c.failed());

        // Also verify single SKIPPED_AIR via heavy path (one air region)
        WorldNoiseAccess airMock = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(airMock.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(-1.0);
        GenerationSession airSession = new GenerationSession();
        airSession.setNoiseAccessForTest(airMock);
        ShadowRouterJobQueue.clear();
        airSession.enqueueEndL4TracerRequests();
        airSession.resetTracerCompletionForTest();
        airSession.setNoiseAccessForTest(airMock);
        var one = ShadowRouterJobQueue.dequeueAny();
        // re-enqueue single for heavy test
        ShadowRouterJobQueue.clear();
        airSession.enqueueEndL4TracerRequests();
        one = ShadowRouterJobQueue.dequeueAny();
        airSession.resetTracerCompletionForTest();
        airSession.setNoiseAccessForTest(airMock);
        InMemoryVolumeWriter airWriter = new InMemoryVolumeWriter();
        WriteOutcome out = airSession.processTracerRequestForTest(one, airWriter, null);
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, out.status());
    }

    @Test
    void tracer_failed_whenSomeWritesFail() throws Exception {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        ShadowRouterJobQueue.clear();
        session.enqueueEndL4TracerRequests();
        // 118 WRITTEN + 3 FAILED -> FAILED status
        for (int i = 0; i < 118; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            session.recordTracerWrittenForTest();
            ShadowRouterJobQueue.markCompleted(req);
            assertNull(session.tracerCompletion());
        }
        for (int i = 0; i < 3; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            session.recordTracerFailureForTest();
            ShadowRouterJobQueue.markCompleted(req);
        }
        var c = session.tracerCompletion();
        assertNotNull(c);
        assertEquals("FAILED", c.status(), "failed>0 yields FAILED");
        assertEquals(118, c.written());
        assertEquals(0, c.skipped());
        assertEquals(3, c.failed());
        assertEquals(121, c.written() + c.skipped() + c.failed());
        assertNotEquals("SUCCESS", c.status());
        assertEquals(3, PerformanceMonitor.getCounter(PerformanceMonitor.TRACER_HORIZON_FAILED));

        // Also verify single FAILED via heavy path throws
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        GenerationSession single = new GenerationSession();
        single.setNoiseAccessForTest(mockNa);
        ShadowRouterJobQueue.clear();
        single.enqueueEndL4TracerRequests();
        single.resetTracerCompletionForTest();
        single.setNoiseAccessForTest(mockNa);
        var one = ShadowRouterJobQueue.dequeueAny();
        VoxelVolumeWriter failingWriter = new VoxelVolumeWriter() {
            @Override public int saveQueueDepth() { return 0; }
            @Override public boolean isRegionFullyPopulated(SectionPos o, Level l) { return false; }
            @Override public WriteOutcome writeSection(SectionPos p, VoxelVolume v) { throw new VolumeUnavailableException("fail"); }
            @Override public WriteOutcome writeRegion(SectionPos o, Level l, VoxelVolume v) { throw new VolumeUnavailableException("fail"); }
        };
        WriteOutcome out = single.processTracerRequestForTest(one, failingWriter, null);
        assertNull(out, "failed write returns null");
    }

    @Test
    void tracer_failed_doesNotCountTowardWrittenSkipped() throws Exception {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        GenerationSession session = new GenerationSession();
        session.setNoiseAccessForTest(mockNa);
        setSection(session, 0, 0);
        ShadowRouterJobQueue.clear();
        session.enqueueEndL4TracerRequests();
        // 121 failures via direct helper
        for (int i = 0; i < 121; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            // Use recordTracerFailureForTest to simulate exception path without writer
            ShadowRouterJobQueue.markCompleted(req);
            session.recordTracerFailureForTest();
        }
        var c = session.tracerCompletion();
        assertNotNull(c);
        assertEquals("FAILED", c.status());
        assertEquals(0, c.written() + c.skipped(), "failed never counts toward written+skipped");
        assertEquals(121, c.failed());
    }

    @Test
    void tracer_stopBefore121_emitsNothing() throws Exception {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        ShadowRouterJobQueue.clear();
        session.enqueueEndL4TracerRequests();
        for (int i = 0; i < 10; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            session.recordTracerWrittenForTest();
            ShadowRouterJobQueue.markCompleted(req);
        }
        assertNull(session.tracerCompletion(), "stop before 121 should not have terminal");
        ShadowRouterJobQueue.clear();
        assertNull(session.tracerCompletion());
    }

    @Test
    void tracer_logContainsTerminalLine() throws Exception {
        java.nio.file.Path p = findPath("java/src/main/java/com/rhythmatician/lodiffusion/voxy/GenerationSession.java");
        String src = java.nio.file.Files.readString(p);
        assertTrue(src.contains("[LodGen][Tracer] terminal 121/121 status="),
                "Must contain single terminal log with status=");
        assertTrue(src.contains("elapsedMs=") && src.contains("at="),
                "Must contain elapsedMs and at");
        // No blocking barrier
        assertFalse(src.contains("CountDownLatch") || src.contains("countDownLatch"),
                "Must not use CountDownLatch");
        // Verify TracerCompletion record exists
        assertTrue(src.contains("record TracerCompletion"), "Must expose TracerCompletion");
    }

    private void setSection(GenerationSession s, int x, int z) throws Exception {
        var fX = GenerationSession.class.getDeclaredField("playerSectionX");
        var fZ = GenerationSession.class.getDeclaredField("playerSectionZ");
        fX.setAccessible(true);
        fZ.setAccessible(true);
        fX.set(s, x);
        fZ.set(s, z);
    }

    private java.nio.file.Path findPath(String relative) {
        java.nio.file.Path r = java.nio.file.Path.of(relative);
        if (java.nio.file.Files.exists(r)) return r;
        java.nio.file.Path alt = java.nio.file.Path.of("").toAbsolutePath();
        for (int i = 0; i < 5; i++) {
            java.nio.file.Path tryP = alt.resolve(relative);
            if (java.nio.file.Files.exists(tryP)) return tryP;
            alt = alt.getParent();
            if (alt == null) break;
        }
        return r;
    }
}
