package com.rhythmatician.lodiffusion;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.Optional;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for the configuration system.
 * Validates that config loading and parsing works correctly.
 */
@Tag("ci")
public class ConfigSmokeTest {
    
    @Test
    public void testDefaultConfig() {
        // Ensure isolated default state (previous tests may have toggled useOnnxTerrain)
        try {
            java.lang.reflect.Field f = Config.class.getDeclaredField("CACHED");
            f.setAccessible(true);
            ((java.util.concurrent.atomic.AtomicReference<?>) f.get(null)).set(null);
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("config/lodiffusion/runtime.json"));
            java.nio.file.Files.deleteIfExists(java.nio.file.Paths.get("java/config/lodiffusion/runtime.json"));
        } catch (Exception ignored) {}
        // Test that defaults are loaded correctly
        assertTrue(Config.useOnnxTerrain(), "ONNX terrain should be enabled by default");
        assertEquals("unified_v1", Config.adapter(), "Default adapter should be unified_v1");
        assertEquals(2, Config.inferenceThreads(), "Default inference threads should be 2");
        assertEquals(0.5, Config.threshold(), 0.001, "Default threshold should be 0.5");
        // logTimings defaults to false unless debug.logTimings is set in runtime.json
        assertFalse(Config.logTimings(), "Log timings should be false by default");
    }
    
    @Test
    public void testModelPath() {
        Path modelPath = Config.modelPath();
        assertNotNull(modelPath, "Model path should not be null");
        assertTrue(modelPath.toString().contains("model.onnx"), "Model path should reference model.onnx");
    }
    
    @Test
    public void testMetricsCsv() {
        Optional<Path> csvPath = Config.metricsCsv();
        // CSV output is disabled by default (requires debug.dumpCsv in runtime.json)
        assertTrue(csvPath.isEmpty(), "CSV output should be disabled by default");
    }

    @Test
    public void testMetricsCsvWhenEnabled() {
        // Verify that CSV output can be enabled via runtime config
        try {
            Config.setDebugDumpCsv("lodiffusion_metrics.csv");
            Optional<Path> csvPath = Config.metricsCsv();
            assertFalse(csvPath.isEmpty(), "CSV output should be enabled after setting debug.dumpCsv");
            assertTrue(csvPath.get().toString().contains("lodiffusion_metrics.csv"),
                    "CSV path should reference the configured file");
        } finally {
            Config.setDebugDumpCsv(null); // restore: disable CSV output
        }
    }
    
    @Test
    public void testRuntimeMutation() {
        // Test that runtime config changes work
        boolean originalState = Config.useOnnxTerrain();
        String originalAdapter = Config.adapter();
        double originalThreshold = Config.threshold();
        
        try {
            // Test toggle
            Config.setUseOnnxTerrain(!originalState);
            assertEquals(!originalState, Config.useOnnxTerrain(), "ONNX terrain state should be toggled");
            
            // Test adapter change
            Config.setAdapter("voxel8x8x8");
            assertEquals("voxel8x8x8", Config.adapter(), "Adapter should be changed");
            
            // Test threshold change
            Config.setThreshold(0.75);
            assertEquals(0.75, Config.threshold(), 0.001, "Threshold should be changed");
            
        } finally {
            // Restore original values
            Config.setUseOnnxTerrain(originalState);
            Config.setAdapter(originalAdapter);
            Config.setThreshold(originalThreshold);
        }
    }
    
    @Test
    public void testInferenceDevice() {
        assertEquals("auto", Config.inferenceDevice(),
                "Default inferenceDevice should be 'auto'");
    }

    @Test
    public void testSetInferenceDevice() {
        String original = Config.inferenceDevice();
        try {
            Config.setInferenceDevice("directml");
            assertEquals("directml", Config.inferenceDevice(),
                    "inferenceDevice should be 'directml' after setInferenceDevice");
        } finally {
            Config.setInferenceDevice(original);
        }
    }

    @Test
    public void testInferenceThreadsBounds() {
        int threads = Config.inferenceThreads();
        int maxCpus = Runtime.getRuntime().availableProcessors();
        
        assertTrue(threads >= 1, "Inference threads should be at least 1");
        assertTrue(threads <= maxCpus, "Inference threads should not exceed CPU count");
    }
    
    @Test
    public void testThresholdBounds() {
        double threshold = Config.threshold();
        
        assertTrue(threshold >= 0.0, "Threshold should be >= 0.0");
        assertTrue(threshold <= 1.0, "Threshold should be <= 1.0");
    }
}
