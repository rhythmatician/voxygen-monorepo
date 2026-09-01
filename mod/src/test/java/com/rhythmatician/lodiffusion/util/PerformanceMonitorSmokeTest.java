package com.rhythmatician.lodiffusion.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Smoke tests for performance monitoring utilities.
 * Validates basic counter and timing functionality.
 */
@Tag("ci")
public class PerformanceMonitorSmokeTest {
    
    @BeforeEach
    public void setUp() {
        // Reset metrics before each test
        PerformanceMonitor.reset();
    }
    
    @Test
    public void testCounterIncrement() {
        // Test counter starts at 0
        assertEquals(0, PerformanceMonitor.getCounter("test_counter"));
        
        // Test increment
        PerformanceMonitor.incrementCounter("test_counter");
        assertEquals(1, PerformanceMonitor.getCounter("test_counter"));
        
        // Test multiple increments
        PerformanceMonitor.incrementCounter("test_counter");
        PerformanceMonitor.incrementCounter("test_counter");
        assertEquals(3, PerformanceMonitor.getCounter("test_counter"));
    }
    
    @Test
    public void testTiming() {
        // Test timing measurement
        PerformanceMonitor.addTiming("test_operation", 1_000_000); // 1ms in nanoseconds
        PerformanceMonitor.addTiming("test_operation", 2_000_000); // 2ms in nanoseconds
        
        // Test average (should be 1.5ms)
        double avgTiming = PerformanceMonitor.getAverageTiming("test_operation");
        assertEquals(1.5, avgTiming, 0.1);
        
        // Test total (should be 3ms)
        double totalTiming = PerformanceMonitor.getTotalTiming("test_operation");
        assertEquals(3.0, totalTiming, 0.1);
    }
    
    @SuppressWarnings("unused")
    @Test
    public void testTimingScope() {
        // Test try-with-resources timing
        try (var scope = PerformanceMonitor.startTiming("scope_test")) {
            // Simulate some work
            try {
                Thread.sleep(10); // 10ms
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }
        
        // Should have recorded timing > 0
        double avgTiming = PerformanceMonitor.getAverageTiming("scope_test");
        assertTrue(avgTiming > 0, "Should have recorded non-zero timing");
    }
    
    @Test
    public void testPerformanceReport() {
        // Set up some test data
        PerformanceMonitor.incrementCounter(PerformanceMonitor.CHUNKS_GENERATED);
        PerformanceMonitor.incrementCounter(PerformanceMonitor.ONNX_INFERENCES);
        PerformanceMonitor.addTiming(PerformanceMonitor.MODEL_INFERENCE_TIME, 5_000_000); // 5ms
        
        // Test report generation
        String report = PerformanceMonitor.getPerformanceReport();
        assertNotNull(report, "Report should not be null");
        assertTrue(report.contains("Performance Report"), "Report should contain title");
        assertTrue(report.contains("chunks_generated"), "Report should contain counter data");
        assertTrue(report.contains("model_inference_time"), "Report should contain timing data");
    }
    
    @Test
    public void testReset() {
        // Set up some data
        PerformanceMonitor.incrementCounter("test_counter");
        PerformanceMonitor.addTiming("test_timing", 1_000_000);
        
        // Verify data exists
        assertEquals(1, PerformanceMonitor.getCounter("test_counter"));
        assertTrue(PerformanceMonitor.getAverageTiming("test_timing") > 0);
        
        // Reset and verify cleared
        PerformanceMonitor.reset();
        assertEquals(0, PerformanceMonitor.getCounter("test_counter"));
        assertEquals(0.0, PerformanceMonitor.getAverageTiming("test_timing"));
    }
    
    @Test
    public void testMultipleCounters() {
        // Test different counter types
        PerformanceMonitor.incrementCounter(PerformanceMonitor.CHUNKS_GENERATED);
        PerformanceMonitor.incrementCounter(PerformanceMonitor.ONNX_INFERENCES);
        PerformanceMonitor.incrementCounter(PerformanceMonitor.FALLBACK_USES);
        PerformanceMonitor.incrementCounter(PerformanceMonitor.ADAPTER_ERRORS);
        
        assertEquals(1, PerformanceMonitor.getCounter(PerformanceMonitor.CHUNKS_GENERATED));
        assertEquals(1, PerformanceMonitor.getCounter(PerformanceMonitor.ONNX_INFERENCES));
        assertEquals(1, PerformanceMonitor.getCounter(PerformanceMonitor.FALLBACK_USES));
        assertEquals(1, PerformanceMonitor.getCounter(PerformanceMonitor.ADAPTER_ERRORS));
    }
}
