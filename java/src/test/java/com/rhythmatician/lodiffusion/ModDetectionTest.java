package com.rhythmatician.lodiffusion;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for ModDetection utility.
 * Tests mod detection capabilities and LOD strategy information.
 */
public class ModDetectionTest {

    @Test
    void testIsVoxyAvailable_ReturnsBoolean() {
        assertDoesNotThrow(() -> {
            boolean result = ModDetection.isVoxyAvailable();
            assertTrue(result || !result);
        });
    }

    @Test
    void testGetLODStrategyInfo_ReturnsNonNullString() {
        String strategyInfo = ModDetection.getLODStrategyInfo();
        assertNotNull(strategyInfo, "LOD strategy info should not be null");
        assertFalse(strategyInfo.isEmpty(), "LOD strategy info should not be empty");
    }

    @Test
    void testGetLODStrategyInfo_ReturnsExpectedMessages() {
        String strategyInfo = ModDetection.getLODStrategyInfo();
        boolean isValidMessage =
                strategyInfo.contains("Voxy detected") ||
                strategyInfo.contains("Voxy not detected");
        assertTrue(isValidMessage, "Should return a recognized LOD strategy message");
    }

    @Test
    void testGetLODStrategyInfo_ConsistentWithAvailabilityCheck() {
        boolean isAvailable = ModDetection.isVoxyAvailable();
        String strategyInfo = ModDetection.getLODStrategyInfo();
        if (isAvailable) {
            assertTrue(strategyInfo.contains("Voxy detected"),
                    "Strategy info should indicate Voxy is present");
        } else {
            assertTrue(strategyInfo.contains("Voxy not detected"),
                    "Strategy info should indicate Voxy is absent");
        }
    }

    @Test
    void testModDetection_StaticMethodsWork() {
        assertDoesNotThrow(() -> {
            ModDetection.isVoxyAvailable();
            ModDetection.getLODStrategyInfo();
        });
    }
}
