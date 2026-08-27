package com.rhythmatician.lodiffusion.voxy;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxyCoarseToFineCoverageRegressionTest {
    @AfterEach
    void clearOwnership() {
        VoxyTopologyOwnership.clearForTest();
    }

    @Test
    void coarseGeometryRemainsReferencedUntilPublishedChildGeometryIsInstalled() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);

        topology.writeSolidCoarseGeometryThroughBinding(1L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.assertCoarseWritePublishedBlockDirtyNotification();
        topology.assertGeometryBearingLeaf();
        topology.startRendererAtCoarseLeaf();
        topology.assertCoarseMeshAllocatedAndReferenced();
        topology.assertNoChildDescent();
        topology.assertNoInstalledChildGeometryReferences();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        topology.storeSolidChild(5, 2L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.assertGeometryBearingLeaf();
        topology.assertCoarseMeshAllocatedAndReferenced();
        topology.assertNoChildDescent();
        topology.assertNoInstalledChildGeometryReferences();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        topology.publishStoredChildren();
        topology.notifyRendererOfPublishedTopology();
        assertEquals(0x20, Byte.toUnsignedInt(topology.childExistenceMask()));
        topology.assertFallbackOwnershipRetained();
        topology.assertCoarseMeshAllocatedAndReferenced();
        topology.assertOnlyChildRequested(5);
        // Pinned traversal: while the expansion request is in flight the
        // parent is still a leaf and renders itself for every octant. This
        // is Voxy's legitimate fallback DURING refinement.
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        topology.attemptNativePromotion(2);
        topology.notifyRendererOfPublishedTopology();
        topology.assertFallbackOwnershipRetained();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        // Completing the one demanded child satisfies the request: the parent
        // flips to an inner node whose child list contains ONLY octant 5.
        // From this moment pinned traversal renders octant 5 at L3 and leaves
        // the seven siblings with NO render representation — the visible void
        // ADR 0012 forbids. This is precisely what a partial publication
        // would expose to players if ownership were released here.
        topology.completeStoredChild(5);
        topology.assertParentBecameInnerWithPartialChildren();
        topology.assertVoidOctants(0, 1, 2, 3, 4, 6, 7);
        topology.assertOnlyOctantRendersAndRestAreVoid(5, 3);
        topology.assertNoNativeBufferLeak();
    }

    /**
     * The production publisher must never expose a partial handoff: after
     * publishing a complete eight-octant handoff (generated + explicit empty
     * outcomes), every octant has a render representation and ownership is
     * released. This is the counterpart to the void proof above.
     */
    @Test
    void completeHandoffLeavesNoVoidOctantsUnderPinnedTraversal() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);

        topology.writeSolidCoarseGeometryThroughBinding(1L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.startRendererAtCoarseLeaf();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        // Store all eight children (mixed solid/empty like production batches).
        for (int octant = 0; octant < 8; octant++) {
            topology.storeSolidChild(octant, 2L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        }
        topology.publishCompleteHandoff();
        topology.notifyRendererOfPublishedTopology();
        assertEquals(0xFF, Byte.toUnsignedInt(topology.childExistenceMask()));
        // Full handoff releases fallback ownership...
        org.junit.jupiter.api.Assertions.assertFalse(VoxyTopologyOwnership.isOwned(
                topology.coarseSectionForTest()));
        // ...and every octant now renders at the finer level — no voids.
        for (int octant = 0; octant < 8; octant++) {
            topology.completeStoredChild(octant);
        }
        topology.assertAllOctantsEffectivelyRenderAtLevel(3);
        topology.assertNoNativeBufferLeak();
    }

    /**
     * A complete handoff whose eight outcomes are ALL proved empty is still a
     * complete handoff: ownership must end and the solid coarse parent must
     * stop being advertised so the renderer can retire the false-positive
     * leaf. The published NEC is legitimately sparse (zero here) — that is
     * complete topology knowledge, not an incomplete handoff.
     */
    @Test
    void allTerminalEmptyHandoffReleasesOwnershipAndRetiresCoarseLeaf() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);

        topology.writeSolidCoarseGeometryThroughBinding(1L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.startRendererAtCoarseLeaf();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        // No children stored: every octant is proved empty by the scheduler.
        topology.publishCompleteHandoffWithNoChildren();
        org.junit.jupiter.api.Assertions.assertFalse(VoxyTopologyOwnership.isOwned(
                topology.coarseSectionForTest()),
                "complete handoff with all-empty outcomes must release ownership");
        assertEquals(0x00, Byte.toUnsignedInt(topology.childExistenceMask()),
                "all-empty handoff publishes a zero present mask");
    }

}
