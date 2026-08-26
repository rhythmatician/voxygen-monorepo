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
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        topology.attemptNativePromotion(2);
        topology.notifyRendererOfPublishedTopology();
        topology.assertFallbackOwnershipRetained();
        topology.assertAllOctantsEffectivelyRenderAtLevel(4);

        topology.completeStoredChild(5);
        topology.assertCoarseMeshAllocatedAndReferenced();
        topology.assertChildGeometryInstalledAndReferenced(5);
        topology.assertOnlyOctantEffectivelyRendersAtLevel(5, 3, 4);
        topology.assertNoNativeBufferLeak();
    }

}
