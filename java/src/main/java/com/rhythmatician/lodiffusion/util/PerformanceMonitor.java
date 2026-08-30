package com.rhythmatician.lodiffusion.util;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import com.rhythmatician.voxygen.semantic.Level;

/**
 * Performance monitoring utilities for ONNX terrain generation.
 * Tracks timing metrics, inference counts, and error rates.
 *
 * <p>Per-level octree counters are stored in fixed-size arrays indexed
 * by LOD level (0=L0 finest … 4=L4 coarsest).  They are updated via
 * lock-free atomics so no locking occurs on the hot path.
 */
public final class PerformanceMonitor {
    
    private static final Map<String, AtomicLong> COUNTERS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> TIMING_TOTALS = new ConcurrentHashMap<>();
    private static final Map<String, AtomicLong> TIMING_COUNTS = new ConcurrentHashMap<>();

    /** Number of octree LOD levels tracked (L0-L4). */
    public static final int NUM_LEVELS = 5;

    /** Per-level inference timing totals (nanoseconds). Indexed by LOD level. */
    private static final AtomicLong[] LEVEL_TIMING_TOTALS;
    /** Per-level inference timing sample counts. Indexed by LOD level. */
    private static final AtomicLong[] LEVEL_TIMING_COUNTS;

    static {
        LEVEL_TIMING_TOTALS = new AtomicLong[NUM_LEVELS];
        LEVEL_TIMING_COUNTS = new AtomicLong[NUM_LEVELS];
        for (int i = 0; i < NUM_LEVELS; i++) {
            LEVEL_TIMING_TOTALS[i] = new AtomicLong(0);
            LEVEL_TIMING_COUNTS[i] = new AtomicLong(0);
        }
    }
    
    // Performance counter names
    public static final String CHUNKS_GENERATED = "chunks_generated";
    public static final String ONNX_INFERENCES = "onnx_inferences";
    public static final String FALLBACK_USES = "fallback_uses";
    public static final String ADAPTER_ERRORS = "adapter_errors";
    public static final String MODEL_ERRORS = "model_errors";
    public static final String TRACER_HORIZON_WRITTEN = "tracer_horizon_written";
    public static final String TRACER_HORIZON_SKIPPED = "tracer_horizon_skipped";
    public static final String TRACER_HORIZON_FAILED = "tracer_horizon_failed";
    public static final String TRACER_HORIZON_ELAPSED_MS = "tracer_horizon_elapsed_ms";
    public static final String TRACER_HORIZON_STATUS_SUCCESS = "tracer_horizon_status_success";
    
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
     * Set a performance counter to an explicit value.
     */
    public static void setCounter(String counterName, long value) {
        COUNTERS.computeIfAbsent(counterName, k -> new AtomicLong(0)).set(value);
    }
    
    /**
     * Add timing measurement (in nanoseconds).
     */
    public static void addTiming(String operationName, long nanos) {
        TIMING_TOTALS.computeIfAbsent(operationName, k -> new AtomicLong(0)).addAndGet(nanos);
        TIMING_COUNTS.computeIfAbsent(operationName, k -> new AtomicLong(0)).incrementAndGet();
    }

    /**
     * Record per-level inference timing (in nanoseconds).
     *
     * @param level LOD level (0=L0 finest … 4=L4 coarsest)
     * @param nanos elapsed time in nanoseconds
     */
    public static void addLevelTiming(int level, long nanos) {
        if (level >= 0 && level < NUM_LEVELS) {
            LEVEL_TIMING_TOTALS[level].addAndGet(nanos);
            LEVEL_TIMING_COUNTS[level].incrementAndGet();
        }
    }

    /**
     * Average per-level inference latency in milliseconds.
     *
     * @param level LOD level (0-4)
     * @return average latency ms, or 0.0 if no samples recorded
     */
    public static double getAverageLevelTiming(int level) {
        if (level < 0 || level >= NUM_LEVELS) return 0.0;
        long count = LEVEL_TIMING_COUNTS[level].get();
        if (count == 0) return 0.0;
        return (LEVEL_TIMING_TOTALS[level].get() / (double) count) / 1_000_000.0;
    }

    /**
     * Total per-level inference time in milliseconds.
     *
     * @param level LOD level (0-4)
     * @return total ms, or 0.0 if no samples recorded
     */
    public static double getTotalLevelTiming(int level) {
        if (level < 0 || level >= NUM_LEVELS) return 0.0;
        return LEVEL_TIMING_TOTALS[level].get() / 1_000_000.0;
    }

    /**
     * Total number of timing samples recorded at {@code level}.
     */
    public static long getLevelTimingCount(int level) {
        if (level < 0 || level >= NUM_LEVELS) return 0L;
        return LEVEL_TIMING_COUNTS[level].get();
    }
    
    /**
     * Record timing for an operation using try-with-resources.
     */
    public static TimingScope startTiming(String operationName) {
        return new TimingScope(operationName);
    }

    /**
     * Record per-level timing for an operation using try-with-resources.
     *
     * @param level LOD level (0-4); also records to the named operation
     */
    public static LevelTimingScope startLevelTiming(int level, String operationName) {
        return new LevelTimingScope(level, operationName);
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

        // Per-level inference latency
        boolean anyLevelTiming = false;
        for (int lvl = 4; lvl >= 0; lvl--) {
            if (LEVEL_TIMING_COUNTS[lvl].get() > 0) { anyLevelTiming = true; break; }
        }
        if (anyLevelTiming) {
            report.append("\nPer-Level Inference Latency (ms avg):\n");
            for (int lvl = 4; lvl >= 0; lvl--) {
                long cnt = LEVEL_TIMING_COUNTS[lvl].get();
                if (cnt > 0) {
                    report.append(String.format("  L%d: %.2f ms avg (%d samples)\n",
                            lvl, getAverageLevelTiming(lvl), cnt));
                }
            }
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
        for (int i = 0; i < NUM_LEVELS; i++) {
            LEVEL_TIMING_TOTALS[i].set(0);
            LEVEL_TIMING_COUNTS[i].set(0);
        }
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

    /**
     * AutoCloseable timing scope that records both named and per-level timing.
     */
    public static class LevelTimingScope implements AutoCloseable {
        private final int level;
        private final String operationName;
        private final long startTime;

        LevelTimingScope(int level, String operationName) {
            this.level = level;
            this.operationName = operationName;
            this.startTime = System.nanoTime();
        }

        @Override
        public void close() {
            long elapsed = System.nanoTime() - startTime;
            addTiming(operationName, elapsed);
            addLevelTiming(level, elapsed);
        }
    }
    
    // Prevent instantiation
    private PerformanceMonitor() {}
}
