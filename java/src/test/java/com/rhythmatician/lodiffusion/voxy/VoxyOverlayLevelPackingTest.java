package com.rhythmatician.lodiffusion.voxy;

import me.cortex.voxy.common.world.WorldEngine;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

class VoxyOverlayLevelPackingTest {
    @ParameterizedTest
    @ValueSource(ints = {0, 1, 2, 3, 4})
    void shaderLevelExtractionMatchesPinnedVoxyNodePacking(int level) {
        long key = WorldEngine.getWorldSectionId(level, -37, 11, 0x54321);
        int firstNodeWord = (int) (key >>> 32);

        assertEquals(level, VoxyOverlayNodeEncoding.levelFromFirstNodeWord(firstNodeWord));
    }
}
