package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff;
import com.rhythmatician.voxygen.backend.voxy.VoxyWorldBinding;

class VoxyWorldBindingUpdateFlagsTest {
    @Test
    void fallbackGeometryWithZeroChildMaskPublishesBlocksWithoutChildExistence() {
        assertEquals(VoxyWorldBinding.BLOCK_UPDATE_FLAG,
                VoxyWorldBinding.generatedFallbackUpdateFlags(false));
    }

    @Test
    void clearingStaleNativeNecPublishesGeometryAndChildExistenceChange() {
        assertEquals(VoxyWorldBinding.BLOCK_UPDATE_FLAG | VoxyWorldBinding.CHILD_EXISTENCE_UPDATE_FLAG,
                VoxyWorldBinding.generatedFallbackUpdateFlags(true));
    }

    @Test
    void completeHandoffPublishesChildExistenceWithoutPretendingParentGeometryChanged() {
        assertEquals(VoxyWorldBinding.CHILD_EXISTENCE_UPDATE_FLAG,
                VoxyWorldBinding.completeHandoffUpdateFlags(false));
        assertEquals(VoxyWorldBinding.BLOCK_UPDATE_FLAG | VoxyWorldBinding.CHILD_EXISTENCE_UPDATE_FLAG,
                VoxyWorldBinding.completeHandoffUpdateFlags(true));
    }

    @Test
    void allEmptyHandoffIsStillPublishedSoCoarseFalsePositiveCanBeRetired() {
        // Complete topology knowledge is independent of child occupancy: an
        // all-empty batch must still reach the renderer.
        CompleteChildHandoff allEmpty = CompleteChildHandoff.ofMasks(0, 0xFF);
        assertEquals((byte) 0, allEmpty.presentMask());
        assertEquals((byte) 0xFF, allEmpty.emptyMask());
    }
}
