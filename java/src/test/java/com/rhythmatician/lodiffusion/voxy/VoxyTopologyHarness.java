package com.rhythmatician.lodiffusion.voxy;

import me.cortex.voxy.common.world.WorldSection;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Headless coverage observations over real Voxy {@link WorldSection} state.
 *
 * <p>The pinned Voxy NodeManager cannot yet be included in this harness because
 * completing a geometry request requires an LWJGL native {@code MemoryBuffer}.
 * This wrapper deliberately stops at the real WorldSection topology consumed by
 * NodeManager, leaving geometry allocation as the only missing boundary.</p>
 */
final class VoxyTopologyHarness {
    private final WorldSection coarse;
    private final WorldSection[] children = new WorldSection[8];

    VoxyTopologyHarness(int level, int x, int y, int z) {
        coarse = WorldSection._createRawUntrackedUnsafeSection(level, x, y, z);
    }

    void writeSolidCoarseGeometry(long voxel) {
        Arrays.fill(coarse._unsafeGetRawDataArray(), voxel);
        VoxyWorldBinding.claimGeneratedFallbackForTest(coarse, coarse.lvl);
    }

    void storeSolidChild(int octant, long voxel) {
        int childX = (coarse.x << 1) | (octant & 1);
        int childY = (coarse.y << 1) | ((octant >>> 2) & 1);
        int childZ = (coarse.z << 1) | ((octant >>> 1) & 1);
        WorldSection child = WorldSection._createRawUntrackedUnsafeSection(
                coarse.lvl - 1, childX, childY, childZ);
        Arrays.fill(child._unsafeGetRawDataArray(), voxel);
        VoxyWorldBinding.claimGeneratedFallbackForTest(child, child.lvl);
        children[octant] = child;
    }

    void publishStoredChildren() {
        byte mask = 0;
        for (int octant = 0; octant < children.length; octant++) {
            if (children[octant] != null) {
                mask |= (byte) (1 << octant);
            }
        }
        VoxyWorldBinding.publishCompleteChildMaskForTest(coarse, mask);
    }

    void assertGeometryBearingLeaf() {
        assertNotEquals(0L, coarse._unsafeGetRawDataArray()[0]);
        assertEquals(0, Byte.toUnsignedInt(coarse.getNonEmptyChildren()));
    }

    void assertSelectedLevel(int octant, int expectedLevel) {
        int selectedLevel = (coarse.getNonEmptyChildren() & (1 << octant)) == 0
                ? coarse.lvl
                : children[octant].lvl;
        assertEquals(expectedLevel, selectedLevel, "selected level for octant " + octant);
    }

    byte childExistenceMask() {
        return coarse.getNonEmptyChildren();
    }
}
