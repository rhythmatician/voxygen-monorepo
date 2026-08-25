package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Stage 2 pipeline behavior (ADR 0011): the End tracer pipeline services
 * L3..L1 refinement requests from the queue via the multi-level producer,
 * and a single entry point runs ring + selection passes together.
 */
class EndRefinementPipelineTest {

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
    void processRefinementRequest_writesRegionAtRequestedLevel() {
        GenerationSession session = new GenerationSession();
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
        req.lodLevel = 3;
        req.worldX = 0;
        req.worldY = 0;
        req.worldZ = 0;

        WriteOutcome out = session.processRefinementRequestForTest(req, writer);
        assertNotNull(out, "refinement request must be processed");
        assertEquals(WriteOutcome.Status.WRITTEN, out.status());
        assertEquals(1, writer.regionRecords().size());
        var rec = writer.regionRecords().get(0);
        assertEquals(Level.L3, rec.level(), "written at requested level");
        assertFalse(rec.volume().isAllAir());
    }

    @Test
    void processRefinementRequest_rejectsL4AndL0() {
        GenerationSession session = new GenerationSession();
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();

        for (int lvl : new int[] {4, 0}) {
            VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
            req.lodLevel = lvl;
            req.worldX = 0;
            req.worldY = 0;
            req.worldZ = 0;
            assertNull(session.processRefinementRequestForTest(req, writer),
                    "level " + lvl + " is not a refinement level");
        }
        assertEquals(0, writer.regionRecords().size());
    }

    @Test
    void runSelectionPass_usesPlayerDerivedCamera_andEnqueues() throws Exception {
        GenerationSession session = new GenerationSession();
        setSection(session, 0, 0);
        setFieldF(session, "playerSectionY", 6); // y section 6 -> block 96
        session.setEndL4TracerModeForTest(true);
        session.setNoiseAccessForTest(solidNoise());

        // Covered regions: the 11x11 L4 ring in player-section coords
        // (-80..80 step 16). Camera at player position.
        int enqueued = session.runEndSelectionPassForTest(
                Level.L1.value(), 1e9, 4096);
        assertTrue(enqueued > 0, "selection pass must enqueue refinements");
        assertEquals(enqueued, ShadowRouterJobQueue.size());
        for (int i = 0; i < Math.min(5, enqueued); i++) {
            var req = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(req);
            assertTrue(req.lodLevel >= 1 && req.lodLevel <= 3,
                    "refinement levels only");
        }
    }

    private void setSection(GenerationSession session, int x, int z) throws Exception {
        setFieldF(session, "playerSectionX", x);
        setFieldF(session, "playerSectionZ", z);
    }

    private void setFieldF(GenerationSession session, String name, int value) throws Exception {
        var f = GenerationSession.class.getDeclaredField(name);
        f.setAccessible(true);
        f.setInt(session, value);
    }
}
