package com.rhythmatician.lodiffusion.voxy;

import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.client.core.rendering.hierachical.HeadlessNodeManagerProbe;

import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/**
 * Headless coverage observations over real Voxy {@link WorldSection} state.
 *
 * <p>The harness drives the pinned real Voxy {@code NodeManager} through a
 * minimal real nonempty geometry result. Only GPU geometry storage and section
 * watching are replaced by deterministic test implementations.</p>
 */
final class VoxyTopologyHarness {
    private final WorldSection coarse;
    private final WorldSection[] children = new WorldSection[8];
    private final long nativeBytesAtStart = HeadlessNodeManagerProbe.allocatedNativeBytes();
    private HeadlessNodeManagerProbe nodeManager;

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

    void startRendererAtCoarseLeaf() {
        nodeManager = new HeadlessNodeManagerProbe(coarse.key);
        nodeManager.completeCoarseLeaf(coarse.getNonEmptyChildren());
        nodeManager.requestRefinement();
    }

    void notifyRendererOfPublishedTopology() {
        nodeManager.publishChildExistence(coarse.getNonEmptyChildren());
    }

    void assertCoarseCoverageRetained() {
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.coarseGeometryRetained());
    }

    void assertNoChildDescent() {
        assertEquals(java.util.Set.of(), nodeManager.watchedChildren());
    }

    void assertOnlyChildRequested(int octant) {
        assertEquals(java.util.Set.of(nodeManager.childPosition(octant)), nodeManager.watchedChildren());
    }

    void assertNoNativeBufferLeak() {
        assertEquals(nativeBytesAtStart, HeadlessNodeManagerProbe.allocatedNativeBytes());
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
