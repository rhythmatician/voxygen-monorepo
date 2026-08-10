package com.rhythmatician.lodiffusion.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

/**
 * Performance monitoring utilities for ONNX terrain generation.
 * Tracks timing metrics, inference counts, and error rates.
 */
public final class PerformanceMonitor {
    
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> TIMING_TOTALS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> TIMING_COUNTS = new ConcurrentHashMap<>();
    
    // Performance counter names
    public static final String CHUNKS_GENERATED = "chunks_generated";
    public static final String ONNX_INFERENCES = "onnx_inferences";
    public static final String FALLBACK_USES = "fallback_uses";
    public static final String ADAPTER_ERRORS = "adapter_errors";
    public static final String MODEL_ERRORS = "model_errors";
    
    // Timing operation names
    public static final String EXTRACT_INPUT_TIME = "extract_input_time";
    public static final String MODEL_INFERENCE_TIME = "model_inference_time";
    public static final String APPLY_OUTPUT_TIME = "apply_output_time";
    public static final String TOTAL_GENERATION_TIME = "total_generation_time";
    
    /**
     * Increment a performance counter.
     */
    public static void incrementCounter(String counterName) {
        COUNTERS.computeIfAbsent(counterName, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Add timing measurement (in nanoseconds).
     */
    public static void addTiming(String operationName, long nanos) {
        TIMING_TOTALS.computeIfAbsent(operationName, k -> new AtomicLong(0)).addAndGet(nanos);
        TIMING_COUNTS.computeIfAbsent(operationName, k -> new AtomicLong(0)).incrementAndGet();
    }
    
    /**
     * Record timing for an operation using try-with-resources.
     */
    public static TimingScope startTiming(String operationName) {
        return new TimingScope(operationName);
    }
    
    /**
     * Get current counter value.
     */
    public static long getCounter(String counterName) {
        return COUNTERS.getOrDefault(counterName, new AtomicLong(0)).get();
    }
    
    /**
     * Get average timing for an operation (in milliseconds).
     */
    public static double getAverageTiming(String operationName) {
        AtomicLong total = TIMING_TOTALS.get(operationName);
        AtomicLong count = TIMING_COUNTS.get(operationName);
        
        if (total == null || count == null || count.get() == 0) {
            return 0.0;
        }
        
        return (total.get() / (double) count.get()) / 1_000_000.0; // Convert ns to ms
    }
    
    /**
     * Get total timing for an operation (in milliseconds).
     */
    public static double getTotalTiming(String operationName) {
        AtomicLong total = TIMING_TOTALS.get(operationName);
        return total != null ? total.get() / 1_000_000.0 : 0.0; // Convert ns to ms
    }
    
    /**
     * Get comprehensive performance report.
     */
    public static String getPerformanceReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== LODiffusion Performance Report ===\n");
        
        report.append("\nCounters:\n");
        for (Map.Entry<String, AtomicLong> entry : COUNTERS.entrySet()) {
            report.append(String.format("  %s: %d\n", entry.getKey(), entry.getValue().get()));
        }
        
        report.append("\nTiming Averages (ms):\n");
        for (String operation : TIMING_TOTALS.keySet()) {
            report.append(String.format("  %s: %.2f ms (%.2f ms total)\n", 
                operation, getAverageTiming(operation), getTotalTiming(operation)));
        }
        
        // Calculate derived metrics
        long chunksGenerated = getCounter(CHUNKS_GENERATED);
        long onnxInferences = getCounter(ONNX_INFERENCES);
        long fallbackUses = getCounter(FALLBACK_USES);
        
        if (chunksGenerated > 0) {
            report.append("\nDerived Metrics:\n");
            report.append(String.format("  ONNX success rate: %.1f%%\n", 
                (onnxInferences * 100.0) / chunksGenerated));
            report.append(String.format("  Fallback rate: %.1f%%\n", 
                (fallbackUses * 100.0) / chunksGenerated));
        }
        
        return report.toString();
    }
    
    /**
     * Reset all performance counters and timings.
     */
    public static void reset() {
        COUNTERS.clear();
        TIMING_TOTALS.clear();
        TIMING_COUNTS.clear();
        HelloTerrainMod.LOGGER.info("[PerformanceMonitor] Reset all metrics");
    }
    
    /**
     * Log performance report at INFO level.
     */
    public static void logReport() {
        HelloTerrainMod.LOGGER.info("[PerformanceMonitor] Performance Report:\n{}", getPerformanceReport());
    }
    
    /**
     * AutoCloseable timing scope for measuring operation duration.
     */
    public static class TimingScope implements AutoCloseable {
        private final String operationName;
        private final long startTime;
        
        TimingScope(String operationName) {
            this.operationName = operationName;
            this.startTime = System.nanoTime();
        }
        
        @Override
        public void close() {
            long elapsed = System.nanoTime() - startTime;
            addTiming(operationName, elapsed);
        }
    }
    
    // Prevent instantiation
    private PerformanceMonitor() {}
}
