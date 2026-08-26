package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

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
    void allAirChildBatchKeepsTheOwnedFallbackLeaf() {
        assertEquals(false, VoxyWorldBinding.shouldPublishCompleteHandoff((byte) 0));
        assertEquals(true, VoxyWorldBinding.shouldPublishCompleteHandoff((byte) 1));
    }
}
