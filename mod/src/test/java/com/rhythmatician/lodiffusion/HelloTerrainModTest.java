package com.rhythmatician.lodiffusion;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for HelloTerrainMod initialization.
 * Follows TDD approach - testing mod initialization behavior.
 */
class HelloTerrainModTest {

    private HelloTerrainMod mod;

    @BeforeEach
    void setUp() {
        mod = new HelloTerrainMod();
    }

    @Test
    void testModInitialization() {
        // Given: A HelloTerrainMod instance
        // When: onInitialize is called
        // Then: Should complete without throwing exceptions

        // Act & Assert - this verifies the method executes successfully
        assertDoesNotThrow(() -> mod.onInitialize());
    }

    @Test
    void testModIdConstant() {
        // Given/When/Then: MOD_ID should be correctly defined
        assertEquals("lodiffusion", HelloTerrainMod.MOD_ID);
    }

    @Test
    void testLoggerExists() {
        // Given/When/Then: Logger should be properly initialized
        assertNotNull(HelloTerrainMod.LOGGER);
        assertEquals("lodiffusion", HelloTerrainMod.LOGGER.getName());
    }

    @Test
    void testImplementsModInitializer() {
        // Given/When/Then: HelloTerrainMod should implement ModInitializer
        assertTrue(mod instanceof net.fabricmc.api.ModInitializer);
    }
}
