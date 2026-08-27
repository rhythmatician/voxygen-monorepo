package com.rhythmatician.lodiffusion.voxy;

import me.cortex.voxy.client.core.rendering.hierachical.HeadlessNodeManagerProbe;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The west-side void race: the render tree snapshots child existence BEFORE
 * our topology write lands, refuses the expansion ("Not creating a leaf
 * request with existence mask of 0"), and never retries — the position stays
 * void forever even after the write publishes real children.
 *
 * <p>Behavior under test: a NON-top-level leaf whose expansion was refused
 * for an empty existence mask MUST be re-issued once the section later
 * reports non-empty children.</p>
 */
class RacedRequestRetryTest {

    @AfterEach
    void clearRetryRegistry() {
        com.rhythmatician.lodiffusion.voxy.VoxyNodeRequestRetry.clearForTest();
    }

    @Test
    void refusedEmptyExpansionIsRetriedWhenChildrenLaterArrive() {
        var probe = new HeadlessNodeManagerProbe(key(3, 2, 0, 5));

        // Build the tree down to a real (non-top-level) leaf at octant 5.
        probe.completeCoarseLeaf((byte) 0x20);
        probe.requestRefinement();
        probe.completeChild(5, (byte) 0);

        // The raced snapshot: the renderer asks the child for its expansion
        // while its stored existence is still empty. Voxy refuses and never
        // schedules a retry — this is the permanent-void site.
        probe.requestChildRefinementWhileEmpty(5);
        assertFalse(probe.childRequestInFlight(5),
                "precondition: the empty-mask refusal must leave no request in flight");
        assertEquals(java.util.Set.of(), probe.watchedGrandchildren(5),
                "refused request must not watch any grandchildren");

        // Our write lands afterwards and publishes real grandchildren.
        probe.publishGrandchildExistence(5, (byte) 0x08);

        // The fix: the refused expansion must be retried now that children exist.
        assertTrue(probe.childRequestInFlight(5),
                "a leaf request refused for existence mask 0 must be re-issued "
                        + "once the section gains non-empty children");
        assertEquals(java.util.Set.of(probe.grandchildPosition(5, 3)),
                probe.watchedGrandchildren(5),
                "the retried request must watch the newly published grandchild");
    }

    @Test
    void retryDoesNotFireWhenPublishedMaskIsStillEmpty() {
        var probe = new HeadlessNodeManagerProbe(key(3, -4, 0, 4));

        probe.completeCoarseLeaf((byte) 0x10);
        probe.requestRefinement();
        probe.completeChild(4, (byte) 0);
        probe.requestChildRefinementWhileEmpty(4);

        // Another empty snapshot arrives (e.g. a redundant dirty event).
        probe.publishGrandchildExistence(4, (byte) 0);

        assertEquals(java.util.Set.of(), probe.watchedGrandchildren(4),
                "no retry should be issued while the mask remains empty");
    }

    private static long key(int lvl, int x, int y, int z) {
        return me.cortex.voxy.common.world.WorldEngine.getWorldSectionId(lvl, x, y, z);
    }
}
