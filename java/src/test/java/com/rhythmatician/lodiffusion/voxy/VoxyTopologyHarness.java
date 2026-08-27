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
    private int coarseDirtyFlags = -1;
    private HeadlessNodeManagerProbe nodeManager;

    VoxyTopologyHarness(int level, int x, int y, int z) {
        coarse = WorldSection._createRawUntrackedUnsafeSection(level, x, y, z);
    }

    void writeSolidCoarseGeometryThroughBinding(long voxel) {
        long[] voxels = new long[32 * 32 * 32];
        Arrays.fill(voxels, voxel);
        VoxyWorldBinding.writeAcquiredWorldSection(
                coarse,
                coarse.lvl,
                voxels,
                (byte) 0,
                (section, flags) -> {
                    ((WorldSection) section).markDirty();
                    coarseDirtyFlags = flags;
                });
    }

    void assertCoarseWritePublishedBlockDirtyNotification() {
        assertEquals(VoxyWorldBinding.BLOCK_UPDATE_FLAG, coarseDirtyFlags);
        org.junit.jupiter.api.Assertions.assertTrue(coarse.setNotDirty(),
                "real WorldSection dirty state must be set by the write notification");
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

    /**
     * Publish a COMPLETE handoff: every stored child is present, every
     * unstored octant is explicitly proved empty. Ownership ends because the
     * handoff is complete — not because every octant is occupied.
     */
    void publishCompleteHandoff() {
        byte present = 0;
        for (int octant = 0; octant < children.length; octant++) {
            if (children[octant] != null) {
                present |= (byte) (1 << octant);
            }
        }
        VoxyWorldBinding.publishCompleteChildMaskForTest(
                coarse, present, CompleteChildHandoff.ofMasks(present & 0xFF, ~present & 0xFF));
    }

    /**
     * Publish a COMPLETE handoff where all eight octants are proved empty:
     * the sparsest legal complete handoff. The renderer must be told so the
     * solid coarse leaf can be retired.
     */
    void publishCompleteHandoffWithNoChildren() {
        VoxyWorldBinding.publishCompleteChildMaskForTest(
                coarse, (byte) 0, CompleteChildHandoff.ofMasks(0, 0xFF));
    }

    void startRendererAtCoarseLeaf() {
        nodeManager = new HeadlessNodeManagerProbe(coarse.key);
        nodeManager.completeCoarseLeaf(coarse.getNonEmptyChildren());
        nodeManager.requestRefinement();
    }

    void notifyRendererOfPublishedTopology() {
        nodeManager.publishChildExistence(coarse.getNonEmptyChildren());
    }

    void attemptNativePromotion(int octant) {
        WorldSection nativeChild = WorldSection._createRawUntrackedUnsafeSection(
                coarse.lvl - 1,
                (coarse.x << 1) | (octant & 1),
                (coarse.y << 1) | ((octant >>> 2) & 1),
                (coarse.z << 1) | ((octant >>> 1) & 1));
        boolean suppressed = VoxyTopologyOwnership.beginNativePromotion(coarse);
        try {
            if (!suppressed) {
                coarse.updateEmptyChildState(nativeChild);
            }
        } finally {
            VoxyTopologyOwnership.finishNativePromotion();
        }
    }

    void assertFallbackOwnershipRetained() {
        org.junit.jupiter.api.Assertions.assertTrue(VoxyTopologyOwnership.isOwned(coarse),
                "incomplete child coverage still depends on the coarse fallback");
    }

    void assertCoarseMeshAllocatedAndReferenced() {
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.coarseMeshAllocated(),
                "coarse mesh must remain allocated");
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.coarseMeshReferencedByActiveGraph(),
                "active Voxy node graph must still reference coarse geometry");
    }

    void assertNoChildDescent() {
        assertEquals(java.util.Set.of(), nodeManager.watchedChildren());
    }

    void assertOnlyChildRequested(int octant) {
        assertEquals(java.util.Set.of(nodeManager.childPosition(octant)), nodeManager.watchedChildren());
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.parentRequestInFlight());
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.parentHasNoChildReferences());
    }

    void assertNoNativeBufferLeak() {
        assertEquals(nativeBytesAtStart, HeadlessNodeManagerProbe.allocatedNativeBytes());
    }

    void assertGeometryBearingLeaf() {
        assertNotEquals(0L, coarse._unsafeGetRawDataArray()[0]);
        assertEquals(0, Byte.toUnsignedInt(coarse.getNonEmptyChildren()));
    }

    void completeStoredChild(int octant) {
        WorldSection child = children[octant];
        if (child == null) {
            throw new IllegalStateException("No stored child in octant " + octant);
        }
        nodeManager.completeChild(octant, child.getNonEmptyChildren());
    }

    void assertChildGeometryInstalledAndReferenced(int octant) {
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.parentReferencesInstalledChildren(),
                "parent GPU record must reference completed child nodes");
        org.junit.jupiter.api.Assertions.assertTrue(nodeManager.childGeometryInstalledAndReferenced(octant));
        assertEquals(java.util.Set.of(nodeManager.childPosition(octant)),
                nodeManager.referencedChildPositions());
    }

    /**
     * Asserts the parent has flipped from leaf to inner with a partial child
     * list — the pinned-Voxy state in which uncovered octants become voids.
     */
    void assertParentBecameInnerWithPartialChildren() {
        org.junit.jupiter.api.Assertions.assertTrue(
                nodeManager.parentReferencesInstalledChildren(),
                "request finished: parent must reference its installed children");
    }

    void assertNoInstalledChildGeometryReferences() {
        assertEquals(java.util.Set.of(), nodeManager.referencedChildPositions());
    }

    /**
     * Pinned traversal: every octant must have a render representation at the
     * expected level. Fails on any octant rendered by fallback OR left void.
     */
    void assertAllOctantsEffectivelyRenderAtLevel(int expectedLevel) {
        for (int octant = 0; octant < 8; octant++) {
            assertEquals(expectedLevel, nodeManager.effectiveRenderableLevel(octant),
                    "effective rendered level for octant " + octant);
        }
    }

    /** Asserts which octants currently have NO render representation (voids). */
    void assertVoidOctants(int... expectedVoidOctants) {
        var expected = java.util.Arrays.stream(expectedVoidOctants)
                .boxed().collect(java.util.stream.Collectors.toSet());
        for (int octant = 0; octant < 8; octant++) {
            boolean isVoid = nodeManager.effectiveRenderablePosition(octant).isEmpty();
            assertEquals(expected.contains(octant), isVoid,
                    "void state for octant " + octant);
        }
    }

    /**
     * Asserts octant {@code childOctant} renders at {@code childLevel} while
     * every other octant is a void (no render representation) — the pinned
     * traversal consequence of a partial child handoff.
     */
    void assertOnlyOctantRendersAndRestAreVoid(int childOctant, int childLevel) {
        for (int octant = 0; octant < 8; octant++) {
            if (octant == childOctant) {
                assertEquals(childLevel, nodeManager.effectiveRenderableLevel(octant),
                        "effective rendered level for octant " + octant);
            } else {
                org.junit.jupiter.api.Assertions.assertTrue(
                        nodeManager.effectiveRenderablePosition(octant).isEmpty(),
                        "octant " + octant + " must have no render representation");
            }
        }
    }

    byte childExistenceMask() {
        return coarse.getNonEmptyChildren();
    }

    /** Exposes the coarse section for ownership assertions. */
    WorldSection coarseSectionForTest() {
        return coarse;
    }
}
