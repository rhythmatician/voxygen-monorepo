package com.rhythmatician.voxygen.backend.voxy;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.voxygen.generation.scheduling.LodGenerationService;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Issue #225: Voxy request-retry registry must not leak across world/dimension lifecycle.
 *
 * <p>Proves disconnect/world-replacement/dimension-rebind cannot carry refused positions
 * into the next NodeManager/session, while same-session retry remains intact.</p>
 */
class VoxyNodeRequestRetryLifecycleTest {

    private static final RegistryKey<World> OVERWORLD_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
    private static final RegistryKey<World> END_KEY =
            RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_end"));

    @AfterEach
    void clearRegistry() {
        VoxyNodeRequestRetry.clear();
    }

    @Test
    void sameSession_retryStillWorks_whenChildrenLaterArrive() {
        long pos = 0x0302000500000005L;
        VoxyNodeRequestRetry.recordRefusal(pos);
        assertEquals(1, VoxyNodeRequestRetry.pendingCount(),
                "precondition: refusal must be pending");

        assertTrue(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x08),
                "same-session: empty-mask refusal must be retried when section later publishes children");
        assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                "retried entry must be consumed");
        assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x08),
                "second retry must not fire after consumption");
    }

    @Test
    void crossSession_noRetryAfterLifecycleClear_samePackedPosition() {
        long pos = 0x0302000500000005L;

        // Session/world A: record a refusal that would normally be retried.
        VoxyNodeRequestRetry.recordRefusal(pos);
        assertEquals(1, VoxyNodeRequestRetry.pendingCount());

        // Tear down session A — production lifecycle boundary (disconnect/world replacement/dimension rebind).
        VoxyNodeRequestRetry.clear();
        assertEquals(0, VoxyNodeRequestRetry.pendingCount(), "teardown must clear pending refusals");

        // Session/world B publishes the SAME packed position with non-empty children.
        assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x08),
                "a stale refusal from A must not cause a spurious retry in B after teardown");
        assertEquals(0, VoxyNodeRequestRetry.pendingCount());
    }

    @Test
    void generationSessionStop_clearsRegistry() {
        long pos = 0x03FFFC0000000004L;
        VoxyNodeRequestRetry.recordRefusal(pos);
        assertEquals(1, VoxyNodeRequestRetry.pendingCount());

        var session = new com.rhythmatician.voxygen.generation.session.GenerationSession();
        // Force running so stop() actually executes lifecycle clear.
        session.forceRunningForTest();
        session.stop();

        assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                "GenerationSession.stop() must clear the retry registry");
        assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0xFF));
    }

    @Test
    void generationSessionStart_clearsStaleRegistry() {
        long pos = 0x0200000000000000L;
        VoxyNodeRequestRetry.recordRefusal(pos);
        assertEquals(1, VoxyNodeRequestRetry.pendingCount());

        var session = new com.rhythmatician.voxygen.generation.session.GenerationSession();
        session.start(null, null);
        try {
            assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                    "GenerationSession.start() must clear stale refusals from prior session");
            assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x0F));
        } finally {
            session.stop();
        }
    }

    @Test
    void lodServiceRebind_clearsRegistryAcrossDimension() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        svc.getSessionForTest().forceRunningForTest();
        try {
            long pos = 0x0302000500000005L;
            VoxyNodeRequestRetry.recordRefusal(pos);
            assertEquals(1, VoxyNodeRequestRetry.pendingCount());

            boolean rebound = svc.checkAndRebindIfNeeded(END_KEY, null, null);
            assertTrue(rebound, "must rebind on Overworld->End");
            Thread.sleep(50);
            svc.getSessionForTest().forceRunningForTest();

            assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                    "dimension rebind must clear pending refusals");
            assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x08),
                    "rebind must not carry refused positions into next NodeManager/session");
        } finally {
            svc.stop();
            VoxyNodeRequestRetry.clear();
        }
    }

    @Test
    void lodServiceStop_clearsRegistry_onDisconnectWorldReplacement() throws Exception {
        LodGenerationService svc = new LodGenerationService();
        svc.startForTest(OVERWORLD_KEY, null);
        Thread.sleep(50);
        svc.getSessionForTest().forceRunningForTest();
        try {
            long pos = 0x010AFFFE00000007L;
            VoxyNodeRequestRetry.recordRefusal(pos);
            assertEquals(1, VoxyNodeRequestRetry.pendingCount());

            svc.stop();

            assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                    "disconnect/world-replacement (stop) must clear refused positions");
            // Simulate next world/session B publishing same position
            assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x01));
        } finally {
            VoxyNodeRequestRetry.clear();
        }
    }

    @Test
    void pendingCount_isObservableAndBounded() {
        VoxyNodeRequestRetry.clear();
        assertEquals(0, VoxyNodeRequestRetry.pendingCount());
        assertEquals(0, VoxyNodeRequestRetry.pendingCountForTest());

        long pos1 = 0x0400000000000000L;
        long pos2 = 0x0401000000000000L;
        VoxyNodeRequestRetry.recordRefusal(pos1);
        VoxyNodeRequestRetry.recordRefusal(pos2);
        assertEquals(2, VoxyNodeRequestRetry.pendingCount());
        assertEquals(2, VoxyNodeRequestRetry.pendingCountForTest());

        VoxyNodeRequestRetry.clear();
        assertEquals(0, VoxyNodeRequestRetry.pendingCount(),
                "stale entries must be observable via pendingCount and cleared on teardown");

        // Production clear must be idempotent and not depend on clearForTest
        VoxyNodeRequestRetry.clear();
        assertEquals(0, VoxyNodeRequestRetry.pendingCount());
    }

    @Test
    void shouldRetry_doesNotLeakWhenMaskStillEmpty() {
        long pos = 0x0302000500000005L;
        VoxyNodeRequestRetry.recordRefusal(pos);
        assertFalse(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0),
                "empty child mask must never trigger retry");
        assertEquals(1, VoxyNodeRequestRetry.pendingCount(),
                "empty mask must keep refusal pending for later non-empty publication");
        assertTrue(VoxyNodeRequestRetry.shouldRetry(pos, (byte) 0x01));
    }
}
