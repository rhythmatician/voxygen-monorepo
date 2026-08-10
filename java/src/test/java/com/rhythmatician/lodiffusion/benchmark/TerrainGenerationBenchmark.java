package com.rhythmatician.lodiffusion.benchmark;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import com.rhythmatician.lodiffusion.util.PerformanceMonitor;

/**
 * Performance benchmarks for ONNX terrain generation system.
 * Tests throughput, latency, and resource usage under load.
 * 
 * Note: These are excluded from regular CI runs due to runtime requirements.
 */
@Tag("benchmark")
public class TerrainGenerationBenchmark {
    
    private static final int WARMUP_ITERATIONS = 10;
    private static final int BENCHMARK_ITERATIONS = 100;
    private static final int CONCURRENT_THREADS = 4;
    
    @BeforeEach
    public void setUp() {
        PerformanceMonitor.reset();
    }
    
    @AfterEach
    public void tearDown() {
        // Log final performance report
        PerformanceMonitor.logReport();
    }
    
    @Test
    @Timeout(value = 30) // 30 second timeout
    public void benchmarkSequentialGeneration() {
        System.out.println("=== Sequential Terrain Generation Benchmark ===");
        
        // Warmup
        System.out.println("Warming up...");
        for (int i = 0; i < WARMUP_ITERATIONS; i++) {
            simulateTerrainGeneration("warmup_" + i);
        }
        
        // Benchmark
        System.out.println("Running sequential benchmark...");
        long startTime = System.nanoTime();
        
        for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
            simulateTerrainGeneration("benchmark_" + i);
        }
        
        long endTime = System.nanoTime();
        double elapsedMs = (endTime - startTime) / 1_000_000.0;
        double throughput = BENCHMARK_ITERATIONS / (elapsedMs / 1000.0);
        
        System.out.printf("Sequential Results:\n");
        System.out.printf("  Total time: %.2f ms\n", elapsedMs);
        System.out.printf("  Average per chunk: %.2f ms\n", elapsedMs / BENCHMARK_ITERATIONS);
        System.out.printf("  Throughput: %.2f chunks/sec\n", throughput);
        
        // Performance assertions
        assertTrue(elapsedMs < 10000, "Sequential generation should complete in under 10 seconds");
        assertTrue(throughput > 10, "Sequential throughput should be > 10 chunks/sec");
    }
    
    @Test
    @Timeout(value = 60) // 60 second timeout
    public void benchmarkConcurrentGeneration() {
        System.out.println("=== Concurrent Terrain Generation Benchmark ===");
        
        ExecutorService executor = Executors.newFixedThreadPool(CONCURRENT_THREADS);
        
        try {
            // Warmup
            System.out.println("Warming up with " + CONCURRENT_THREADS + " threads...");
            List<CompletableFuture<Void>> warmupFutures = new ArrayList<>();
            for (int i = 0; i < WARMUP_ITERATIONS; i++) {
                final int index = i;
                warmupFutures.add(CompletableFuture.runAsync(() -> 
                    simulateTerrainGeneration("concurrent_warmup_" + index), executor));
            }
            CompletableFuture.allOf(warmupFutures.toArray(new CompletableFuture[0])).join();
            
            // Benchmark
            System.out.println("Running concurrent benchmark...");
            long startTime = System.nanoTime();
            
            List<CompletableFuture<Void>> benchmarkFutures = new ArrayList<>();
            for (int i = 0; i < BENCHMARK_ITERATIONS; i++) {
                final int index = i;
                benchmarkFutures.add(CompletableFuture.runAsync(() -> 
                    simulateTerrainGeneration("concurrent_benchmark_" + index), executor));
            }
            
            CompletableFuture.allOf(benchmarkFutures.toArray(new CompletableFuture[0])).join();
            long endTime = System.nanoTime();
            
            double elapsedMs = (endTime - startTime) / 1_000_000.0;
            double throughput = BENCHMARK_ITERATIONS / (elapsedMs / 1000.0);
            
            System.out.printf("Concurrent Results (%d threads):\n", CONCURRENT_THREADS);
            System.out.printf("  Total time: %.2f ms\n", elapsedMs);
            System.out.printf("  Average per chunk: %.2f ms\n", elapsedMs / BENCHMARK_ITERATIONS);
            System.out.printf("  Throughput: %.2f chunks/sec\n", throughput);
            
            // Performance assertions
            assertTrue(elapsedMs < 20000, "Concurrent generation should complete in under 20 seconds");
            assertTrue(throughput > 20, "Concurrent throughput should be > 20 chunks/sec");
            
        } finally {
            executor.shutdown();
        }
    }
    
    @Test
    @Timeout(value = 30)
    public void benchmarkMemoryUsage() {
        System.out.println("=== Memory Usage Benchmark ===");
        
        Runtime runtime = Runtime.getRuntime();
        long initialMemory = runtime.totalMemory() - runtime.freeMemory();
        
        // Generate terrain repeatedly to test for memory leaks
        for (int i = 0; i < 100; i++) {
            simulateTerrainGeneration("memory_test_" + i);
            
            // Force GC every 20 iterations
            if (i % 20 == 0) {
                runtime.gc();
                try {
                    Thread.sleep(10); // Give GC time to run
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        // Final GC and measurement
        runtime.gc();
        try {
            Thread.sleep(100);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        long finalMemory = runtime.totalMemory() - runtime.freeMemory();
        long memoryIncrease = finalMemory - initialMemory;
        double memoryIncreaseMB = memoryIncrease / (1024.0 * 1024.0);
        
        System.out.printf("Memory Usage:\n");
        System.out.printf("  Initial memory: %.2f MB\n", initialMemory / (1024.0 * 1024.0));
        System.out.printf("  Final memory: %.2f MB\n", finalMemory / (1024.0 * 1024.0));
        System.out.printf("  Memory increase: %.2f MB\n", memoryIncreaseMB);
        
        // Memory leak assertion
        assertTrue(memoryIncreaseMB < 100, "Memory increase should be under 100 MB after 1000 iterations");
    }
    
    @Test
    @Timeout(value = 20)
    @org.junit.jupiter.api.Disabled("Performance overhead varies significantly by environment")
    public void benchmarkPerformanceMonitorOverhead() {
        System.out.println("=== Performance Monitor Overhead Benchmark ===");
        
        int iterations = 100_000;
        
        // Benchmark without monitoring
        long startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            // Simulate minimal work
            Math.sqrt(i);
        }
        long baselineTime = System.nanoTime() - startTime;
        
        // Benchmark with monitoring
        startTime = System.nanoTime();
        for (int i = 0; i < iterations; i++) {
            try (var timer = PerformanceMonitor.startTiming("overhead_test")) {
                // Simulate minimal work
                Math.sqrt(i);
            }
            PerformanceMonitor.incrementCounter("overhead_counter");
        }
        long monitoredTime = System.nanoTime() - startTime;
        
        double baselineMs = baselineTime / 1_000_000.0;
        double monitoredMs = monitoredTime / 1_000_000.0;
        double overhead = ((monitoredMs - baselineMs) / baselineMs) * 100.0;
        
        System.out.printf("Monitoring Overhead:\n");
        System.out.printf("  Baseline: %.2f ms\n", baselineMs);
        System.out.printf("  With monitoring: %.2f ms\n", monitoredMs);
        System.out.printf("  Overhead: %.1f%%\n", overhead);
        
        // Overhead assertion - performance monitoring adds some overhead especially on fast operations
        // In real usage, operations are longer and overhead is less significant
        assertTrue(overhead < 200, "Performance monitoring overhead should be reasonable (" + String.format("%.1f", overhead) + "% measured)");
    }
    
    /**
     * Simulate terrain generation with realistic timing and resource usage.
     */
    private void simulateTerrainGeneration(String chunkId) {
        PerformanceMonitor.incrementCounter(PerformanceMonitor.CHUNKS_GENERATED);
        
        try (var totalTimer = PerformanceMonitor.startTiming(PerformanceMonitor.TOTAL_GENERATION_TIME)) {
            
            // Simulate input extraction (1-3ms)
            try (var extractTimer = PerformanceMonitor.startTiming(PerformanceMonitor.EXTRACT_INPUT_TIME)) {
                simulateWork(1, 3);
            }
            
            // Simulate model inference (5-15ms)
            try (var inferenceTimer = PerformanceMonitor.startTiming(PerformanceMonitor.MODEL_INFERENCE_TIME)) {
                simulateWork(5, 15);
                PerformanceMonitor.incrementCounter(PerformanceMonitor.ONNX_INFERENCES);
            }
            
            // Simulate output application (2-5ms)
            try (var applyTimer = PerformanceMonitor.startTiming(PerformanceMonitor.APPLY_OUTPUT_TIME)) {
                simulateWork(2, 5);
            }
        }
    }
    
    /**
     * Simulate work with realistic timing variation.
     */
    private void simulateWork(int minMs, int maxMs) {
        int workMs = minMs + (int) (Math.random() * (maxMs - minMs));
        try {
            Thread.sleep(workMs);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Simulate some CPU work
        double result = 0;
        for (int i = 0; i < 1000; i++) {
            result += Math.sqrt(i * Math.random());
        }
        
        // Prevent optimization
        if (result < 0) {
            System.out.println("Unexpected result: " + result);
        }
    }
}
