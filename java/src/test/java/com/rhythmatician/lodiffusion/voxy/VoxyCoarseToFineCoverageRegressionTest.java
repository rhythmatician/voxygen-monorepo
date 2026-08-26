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
    void coarseTerrainRemainsCoveredUntilAStoredChildIsExplicitlyPublished() {
        var topology = new VoxyTopologyHarness(4, -3, 1, 5);

        topology.writeSolidCoarseGeometry(1L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.assertGeometryBearingLeaf();
        for (int octant = 0; octant < 8; octant++) {
            topology.assertSelectedLevel(octant, 4);
        }

        topology.storeSolidChild(5, 2L << VoxyWorldBinding.BLOCK_ID_SHIFT);
        topology.assertGeometryBearingLeaf();
        topology.assertSelectedLevel(5, 4);

        topology.publishStoredChildren();
        assertEquals(0x20, Byte.toUnsignedInt(topology.childExistenceMask()));
        topology.assertSelectedLevel(5, 3);
        for (int octant = 0; octant < 8; octant++) {
            if (octant != 5) {
                topology.assertSelectedLevel(octant, 4);
            }
        }
    }
}
