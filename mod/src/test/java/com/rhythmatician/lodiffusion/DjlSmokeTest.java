package com.rhythmatician.lodiffusion;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Test;

import ai.djl.Model;

/**
 * Smoke test to verify DJL dependency is available at runtime.
 */
public class DjlSmokeTest {
    
    @Test
    public void testDjlModelClassAvailable() {
        // Verify DJL Model class can be loaded
        assertNotNull(Model.class, "DJL Model class should be available");
        System.out.println("DJL Model class: " + Model.class);
    }
}
