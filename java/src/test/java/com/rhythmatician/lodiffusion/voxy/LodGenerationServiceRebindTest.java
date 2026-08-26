package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Dimension-change-aware rebind tests for issue #151.
 *
 * <p>Proves teleport Overworld → the_end activates tracer without rejoin,
 * and that rebind clears {@link ShadowRouterJobQueue} so old-dimension demand
 * cannot survive. Uses RegistryKey (identifier) without mocking World
 * to avoid ByteBuddy retransform limits in full suite.
 */
class LodGenerationServiceRebindTest {

    private static final RegistryKey<World> OVERWORLD_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> END_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_end"));

    @AfterEach
    void clearQueueAndStop() {
        ShadowRouterJobQueue.clear();
    }

    private static void enqueueDummy(int lod, int x, int y, int z) {
        VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
        req.lodLevel = lod;
        req.worldX = x;
        req.worldY = y;
        req.worldZ = z;
        ShadowRouterJobQueue.enqueue(req);
    }

    private static void forceRunning(GenerationSession s) {
        s.forceRunningForTest();
    }

    @Test
    void rebind_overworldToEnd_activatesTracerAndClearsQueue() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        try {
            assertTrue(svc.isRunning(), "service should be running after start");
            assertEquals(OVERWORLD_KEY, svc.getBoundDimensionForTest());
            assertFalse(svc.getSessionForTest().isEndL4TracerMode(), "Overworld must not be tracer");

            ShadowRouterJobQueue.clear();
            enqueueDummy(4, 0, 0, 0);
            enqueueDummy(4, 1, 0, 0);
            enqueueDummy(4, 0, 0, 1);
            assertEquals(3, ShadowRouterJobQueue.size());
            VoxyRequestDecoder.VoxyNodeRequest inFlight = ShadowRouterJobQueue.dequeueAny();
            assertNotNull(inFlight);
            assertEquals(2, ShadowRouterJobQueue.size());
            assertEquals(1, ShadowRouterJobQueue.inFlightSize());

            boolean rebound = svc.checkAndRebindIfNeeded(END_KEY, null, null);
            assertTrue(rebound, "must rebind on Overworld->End");

            assertEquals(0, ShadowRouterJobQueue.size(), "queue must be empty after rebind");
            assertEquals(0, ShadowRouterJobQueue.inFlightSize(), "in-flight must be cleared after rebind");

            Thread.sleep(50);
            forceRunning(svc.getSessionForTest());
            assertEquals(END_KEY, svc.getBoundDimensionForTest());
            assertTrue(svc.getSessionForTest().isEndL4TracerMode(), "End dimension must be tracer");
            assertEquals(121, svc.getSessionForTest().enqueueEndL4TracerRequests());
            ShadowRouterJobQueue.clear();

            boolean second = svc.checkAndRebindIfNeeded(END_KEY, null, null);
            assertFalse(second, "same dimension must not rebind");
            assertEquals(END_KEY, svc.getBoundDimensionForTest());
        } finally {
            svc.stop();
            assertEquals(0, ShadowRouterJobQueue.size());
            assertEquals(0, ShadowRouterJobQueue.inFlightSize());
            assertFalse(svc.isRunning());
            assertFalse(svc.checkAndRebindIfNeeded(OVERWORLD_KEY, null, null));
        }
    }

    @Test
    void rebind_endToOverworld_clearsTracerState() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(END_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        try {
            assertTrue(svc.getSessionForTest().isEndL4TracerMode());
            enqueueDummy(4, 0, 0, 0);
            assertEquals(1, ShadowRouterJobQueue.size());
            boolean rebound = svc.checkAndRebindIfNeeded(OVERWORLD_KEY, null, null);
            assertTrue(rebound);
            Thread.sleep(50);
            forceRunning(svc.getSessionForTest());
            assertEquals(0, ShadowRouterJobQueue.size());
            assertEquals(0, ShadowRouterJobQueue.inFlightSize());
            assertEquals(OVERWORLD_KEY, svc.getBoundDimensionForTest());
            assertFalse(svc.getSessionForTest().isEndL4TracerMode(), "Overworld after End must not be tracer");
        } finally {
            svc.stop();
            ShadowRouterJobQueue.clear();
        }
    }

    @Test
    void rebindBackToEndReplaysDurableVanillaObservationsIntoTheFreshSession() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(END_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        try {
            svc.observeVanillaChunkColumnForTest(END_KEY, 0, 0);
            svc.observeVanillaChunkColumnForTest(END_KEY, 1, 0);
            svc.observeVanillaChunkColumnForTest(END_KEY, 0, 1);
            svc.observeVanillaChunkColumnForTest(END_KEY, 1, 1);
            assertTrue(svc.getSessionForTest().isFullyVanillaForTest(0, 0, 0, 0));

            assertTrue(svc.checkAndRebindIfNeeded(OVERWORLD_KEY, null, null));
            Thread.sleep(50);
            forceRunning(svc.getSessionForTest());
            assertTrue(svc.checkAndRebindIfNeeded(END_KEY, null, null));
            Thread.sleep(50);
            forceRunning(svc.getSessionForTest());

            assertTrue(svc.getSessionForTest().isFullyVanillaForTest(0, 0, 0, 0),
                    "a new End session must replay observations loaded before the dimension round-trip");
        } finally {
            svc.stop();
        }
    }

    @Test
    void noRebind_whenSameDimensionOrNotRunning() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        assertFalse(svc.checkAndRebindIfNeeded(OVERWORLD_KEY, null, null));

        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        try {
            ShadowRouterJobQueue.clear();
            enqueueDummy(4, 0, 0, 0);
            assertEquals(1, ShadowRouterJobQueue.size());
            assertFalse(svc.checkAndRebindIfNeeded(OVERWORLD_KEY, null, null));
            assertEquals(1, ShadowRouterJobQueue.size());
            assertFalse(svc.checkAndRebindIfNeeded((RegistryKey<World>) null, null, null));
        } finally {
            svc.stop();
            ShadowRouterJobQueue.clear();
        }
    }

    @Test
    void stop_clearsQueue_evenWithoutRebind() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        enqueueDummy(4, 0, 0, 0);
        enqueueDummy(4, 1, 0, 0);
        ShadowRouterJobQueue.dequeueAny();
        assertEquals(1, ShadowRouterJobQueue.size());
        assertEquals(1, ShadowRouterJobQueue.inFlightSize());
        svc.stop();
        assertEquals(0, ShadowRouterJobQueue.size());
        assertEquals(0, ShadowRouterJobQueue.inFlightSize());
    }

    @Test
    void worldOverload_delegatesToKeyOverload() throws Exception {
        // ByteBuddy retransform limit can occur in the full suite when many World mocks are created.
        // If mocking fails, escalate as a test assumption rather than a vacuous pass.
        net.minecraft.world.World mockWorld;
        try {
            mockWorld = org.mockito.Mockito.mock(net.minecraft.world.World.class);
            org.mockito.Mockito.when(mockWorld.getRegistryKey()).thenReturn(END_KEY);
        } catch (Throwable t) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,
                    "Skipped worldOverload_delegates: ByteBuddy/mock limit hit: " + t.getMessage());
            return;
        }
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        forceRunning(svc.getSessionForTest());
        try {
            boolean rebound = svc.checkAndRebindIfNeeded(mockWorld, null);
            assertTrue(rebound, "World overload must delegate to key overload and rebind on dimension change");
            Thread.sleep(50);
            forceRunning(svc.getSessionForTest());
            assertEquals(END_KEY, svc.getBoundDimensionForTest());
        } finally {
            svc.stop();
        }
    }
}
