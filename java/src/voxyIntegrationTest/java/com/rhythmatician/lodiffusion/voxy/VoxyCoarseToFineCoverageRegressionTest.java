package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.voxygen.backend.voxy.VoxyTopologyOwnership;
import com.rhythmatician.voxygen.backend.voxy.VoxyWorldBinding;
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

    /**
     * A fallback claim on a section that ALREADY advertises children (e.g. a
     * post-handoff parent touched again by a vanilla-triggered demand write)
     * must not wipe its nonEmptyChildren mask. Clearing NEC bits makes Voxy's
     * NodeManager recursively delete rendered child nodes — the transient
     * total-terrain-disappearance bug — until the next complete handoff
     * republishes the mask seconds later.
     */
    @Test
    void fallbackClaimOnPopulatedSectionPreservesAdvertisedChildren() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);

        topology.writeSolidCoarseGeometryThroughBinding(1L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        for (int octant = 0; octant < 8; octant++) {
            topology.storeSolidChild(octant, 2L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        }
        topology.publishCompleteHandoff();
        assertEquals(0xFF, Byte.toUnsignedInt(topology.childExistenceMask()));

        // A later demand write re-acquires and claims this now-populated
        // section as a "generated fallback". The advertised children must
        // survive the claim.
        topology.reclaimAsGeneratedFallback();
        assertEquals(0xFF, Byte.toUnsignedInt(topology.childExistenceMask()),
                "fallback claim on a populated section must preserve advertised children");
    }

    /**
     * A candidate write whose octants are pure air (a coarse rasterizer miss
     * over steep island edges) must not erase existing terrain in those
     * octants. Coarse-but-present terrain beats a void.
     */
    @Test
    void allAirCandidateOctantDoesNotEraseExistingTerrain() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);
        long solid = 1L << VoxyWorldBinding.BLOCK_ID_SHIFT;

        // Existing section: every octant has real terrain.
        topology.writeSolidCoarseGeometryThroughBinding(solid);

        // Candidate: octant 0 is pure air (rasterizer missed the edge).
        topology.writeCandidateWithAirOctants(solid, 0);

        org.junit.jupiter.api.Assertions.assertTrue(topology.octantHasNonAir(0),
                "all-air candidate octant must not erase existing terrain");
    }

    /**
     * The write-path skip gate must verify actual child voxel data, not trust
     * the parent's advertised nonEmptyChildren bits. A stale NEC=0xFF over
     * empty octants would otherwise make written==0 map to preservedExisting()
     * with advertiseToParent()==true — advertising a permanent void.
     */
    @Test
    void skipGateRequiresVerifiedChildDataNotStaleAdvertisement() {
        // Stale advertisement claims all children, but voxel verification
        // finds none of them backed by real data: must NOT skip.
        assertEquals(false, VoxyWorldBinding.shouldSkipWriteForVerifiedChildren(
                (byte) 0xFF, (byte) 0x00));

        // Verified full coverage genuinely has nothing to contribute: skip.
        assertEquals(true, VoxyWorldBinding.shouldSkipWriteForVerifiedChildren(
                (byte) 0xFF, (byte) 0xFF));

        // Partial verified coverage: never skip.
        assertEquals(false, VoxyWorldBinding.shouldSkipWriteForVerifiedChildren(
                (byte) 0xFF, (byte) 0x41));
        assertEquals(false, VoxyWorldBinding.shouldSkipWriteForVerifiedChildren(
                (byte) 0x00, (byte) 0x00));
    }

}
