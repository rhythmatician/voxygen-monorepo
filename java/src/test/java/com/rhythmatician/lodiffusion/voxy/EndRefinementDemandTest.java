package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Stage 2 session wiring (ADR 0011): in End tracer mode the session runs
 * screen-space-error selection passes that enqueue L3..L1 refinement
 * requests for covered L4 regions. The fixed 11×11 L4 ring remains the only
 * radius; finer demand comes from the selector, budgeted and deduplicated
 * by {@link ShadowRouterJobQueue}.
 */
class EndRefinementDemandTest {

    @BeforeEach
    void setUp() {
        ShadowRouterJobQueue.clear();
    }

    @AfterEach
    void tearDown() {
        ShadowRouterJobQueue.clear();
    }

    private static WorldNoiseAccess solidNoise() {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        return mockNa;
    }

    @Test
    void selectionPass_enqueuesRefinements_forCoveredL4Regions_only() {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        // Covered L4 regions: just the centre region (player-section coords).
        int enqueued = session.enqueueEndRefinementsForTest(
                List.of(new SectionPos(0, 0, 0)),
                256.0 /* camX blocks */, 96.0, 256.0,
                Level.L1.value(), 1e9 /* render distance */, 4096 /* budget */);

        assertTrue(enqueued > 0, "refinements must be enqueued");
        assertEquals(enqueued, ShadowRouterJobQueue.size());

        // All requests must be L3..L1 — never L4 (ring owns that) or L0.
        boolean[] seenLevel = new boolean[5];
        for (int i = 0; i < enqueued; i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            int lvl = req.lodLevel;
            assertTrue(lvl >= 1 && lvl <= 3, "level must be 1..3, got " + lvl);
            seenLevel[lvl] = true;
        }
        assertTrue(seenLevel[3], "L3 refinements present");
        assertTrue(seenLevel[2], "L2 refinements present");
        assertTrue(seenLevel[1], "L1 refinements present");
    }

    @Test
    void selectionPass_budgetCapsEnqueue() {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        int enqueued = session.enqueueEndRefinementsForTest(
                List.of(new SectionPos(0, 0, 0)),
                256.0, 96.0, 256.0,
                Level.L1.value(), 1e9, 10 /* tiny budget */);
        assertEquals(10, enqueued, "budget caps emissions");
        assertEquals(10, ShadowRouterJobQueue.size());
    }

    @Test
    void selectionPass_deduplicatesAcrossPasses() {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        var regions = List.of(new SectionPos(0, 0, 0));
        int first = session.enqueueEndRefinementsForTest(
                regions, 256.0, 96.0, 256.0, Level.L1.value(), 1e9, 4096);
        // Drain into a side set to simulate completed work, then re-run the
        // pass with an empty queue: dedup state must suppress re-emission.
        ShadowRouterJobQueue.clear();

        int second = session.enqueueEndRefinementsForTest(
                regions, 256.0, 96.0, 256.0, Level.L1.value(), 1e9, 4096);
        assertEquals(0, second, "same camera + same coverage must not re-enqueue");
    }

    @Test
    void selectionPass_extendedDepth_emitsNewDemand() {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        var regions = List.of(new SectionPos(0, 0, 0));
        // Pass 1 refines only to L2: emits the L3 (8) + L2 (64) cascade.
        int first = session.enqueueEndRefinementsForTest(
                regions, 256.0, 96.0, 256.0, Level.L2.value(), 1e9, 4096);
        assertEquals(72, first, "L3+L2 cascade from region centre");
        ShadowRouterJobQueue.clear();

        // Repeat pass adds nothing — dedup holds across passes.
        int repeat = session.enqueueEndRefinementsForTest(
                regions, 256.0, 96.0, 256.0, Level.L2.value(), 1e9, 4096);
        assertEquals(0, repeat, "same depth must not re-enqueue");

        // Extending refinement depth to L1 emits genuinely new demand
        // (512 L1 nodes) without re-emitting L3/L2.
        int deeper = session.enqueueEndRefinementsForTest(
                regions, 256.0, 96.0, 256.0, Level.L1.value(), 1e9, 4096);
        assertEquals(512, deeper, "only the new L1 layer is emitted");
    }

    private void setSection(GenerationSession session, int x, int z) {
        try {
            var f = GenerationSession.class.getDeclaredField("playerSectionX");
            f.setAccessible(true);
            f.setInt(session, x);
            var g = GenerationSession.class.getDeclaredField("playerSectionZ");
            g.setAccessible(true);
            g.setInt(session, z);
        } catch (ReflectiveOperationException e) {
            throw new RuntimeException(e);
        }
    }
}
