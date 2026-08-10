package com.rhythmatician.lodiffusion.util;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;

import ai.djl.ndarray.NDArray;
import net.minecraft.util.math.ChunkPos;

/**
 * Debug utilities for ONNX terrain generation.
 * Provides tensor dumping, chunk analysis, and diagnostic logging.
 */
public final class DebugUtils {
    
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss");
    
    /**
     * Dump NDArray tensor to CSV file for external analysis.
     * Only active when debug.dumpCsv is configured.
     */
    public static void dumpTensor(NDArray tensor, String label, ChunkPos chunkPos) {
        Config.metricsCsv().ifPresent(basePath -> {
            try {
                String timestamp = LocalDateTime.now().format(TIMESTAMP_FORMAT);
                String filename = String.format("%s_chunk_%d_%d_%s.csv", 
                    label, chunkPos.x, chunkPos.z, timestamp);
                Path outputPath = basePath.getParent().resolve(filename);
                
                Files.createDirectories(outputPath.getParent());
                
                StringBuilder csv = new StringBuilder();
                csv.append("# Tensor dump: ").append(label).append("\n");
                csv.append("# Chunk: (").append(chunkPos.x).append(", ").append(chunkPos.z).append(")\n");
                csv.append("# Shape: ").append(Arrays.toString(tensor.getShape().getShape())).append("\n");
                csv.append("# Timestamp: ").append(timestamp).append("\n");
                csv.append("\n");
                
                // Convert tensor to CSV format
                float[] data = tensor.toFloatArray();
                long[] shape = tensor.getShape().getShape();
                
                if (shape.length == 4) { // [batch, channels, height, width]
                    dumpTensor4D(csv, data, shape);
                } else if (shape.length == 5) { // [batch, channels, depth, height, width]
                    dumpTensor5D(csv, data, shape);
                } else {
                    // Fallback: dump as flat array
                    for (int i = 0; i < data.length; i++) {
                        csv.append(data[i]);
                        if (i < data.length - 1) csv.append(",");
                    }
                    csv.append("\n");
                }
                
                Files.write(outputPath, csv.toString().getBytes(), 
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
                
                HelloTerrainMod.LOGGER.debug("[DebugUtils] Dumped tensor '{}' to: {}", label, outputPath);
                
            } catch (IOException e) {
                HelloTerrainMod.LOGGER.warn("[DebugUtils] Failed to dump tensor '{}': {}", label, e.getMessage());
            }
        });
    }
    
    /**
     * Log tensor summary (shape, min/max/mean) for debugging.
     */
    public static void logTensorSummary(NDArray tensor, String label) {
        if (!Config.logTimings()) return; // Only log when debug timing is enabled
        
        try {
            float[] data = tensor.toFloatArray();
            float min = Float.MAX_VALUE;
            float max = Float.MIN_VALUE;
            double sum = 0.0;
            
            for (float value : data) {
                min = Math.min(min, value);
                max = Math.max(max, value);
                sum += value;
            }
            
            double mean = sum / data.length;
            
            HelloTerrainMod.LOGGER.debug("[DebugUtils] Tensor '{}' - Shape: {}, Min: {}, Max: {}, Mean: {}", 
                label, Arrays.toString(tensor.getShape().getShape()),
                String.format("%.3f", min), String.format("%.3f", max), String.format("%.3f", mean));
                
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[DebugUtils] Failed to analyze tensor '{}': {}", label, e.getMessage());
        }
    }
    
    /**
     * Log chunk generation status and configuration.
     */
    public static void logChunkStatus(ChunkPos pos, String generatorType, long elapsedMs) {
        if (Config.logTimings()) {
            HelloTerrainMod.LOGGER.info("[DebugUtils] Chunk ({}, {}) generated using {} in {}ms", 
                pos.x, pos.z, generatorType, elapsedMs);
        }
    }
    
    /**
     * Create a debug report with current system state.
     */
    public static String createSystemReport() {
        StringBuilder report = new StringBuilder();
        report.append("=== LODiffusion System Debug Report ===\n");
        report.append("Timestamp: ").append(LocalDateTime.now()).append("\n\n");
        
        // Configuration status
        report.append("Configuration:\n");
        report.append("  ONNX Terrain Enabled: ").append(Config.useOnnxTerrain()).append("\n");
        report.append("  Model Path: ").append(Config.modelPath()).append("\n");
        report.append("  Adapter: ").append(Config.adapter()).append("\n");
        report.append("  Inference Threads: ").append(Config.inferenceThreads()).append("\n");
        report.append("  Threshold: ").append(Config.threshold()).append("\n");
        report.append("  Occ Threshold: ").append(Config.getDouble("occThreshold", 0.3)).append("\n");
        report.append("  Log Timings: ").append(Config.logTimings()).append("\n");
        report.append("  CSV Output: ").append(Config.metricsCsv().map(Path::toString).orElse("disabled")).append("\n");
        report.append("\n");
        
        // System resources
        Runtime runtime = Runtime.getRuntime();
        report.append("System Resources:\n");
        report.append("  Available Processors: ").append(runtime.availableProcessors()).append("\n");
        report.append("  Max Memory: ").append(runtime.maxMemory() / (1024 * 1024)).append(" MB\n");
        report.append("  Total Memory: ").append(runtime.totalMemory() / (1024 * 1024)).append(" MB\n");
        report.append("  Free Memory: ").append(runtime.freeMemory() / (1024 * 1024)).append(" MB\n");
        report.append("\n");
        
        // Performance metrics
        report.append(PerformanceMonitor.getPerformanceReport());
        
        return report.toString();
    }
    
    private static void dumpTensor4D(StringBuilder csv, float[] data, long[] shape) {
        // [batch, channels, height, width]
        int batch = (int) shape[0];
        int channels = (int) shape[1];
        int height = (int) shape[2];
        int width = (int) shape[3];
        
        for (int b = 0; b < batch; b++) {
            for (int c = 0; c < channels; c++) {
                csv.append("# Batch ").append(b).append(", Channel ").append(c).append("\n");
                for (int h = 0; h < height; h++) {
                    for (int w = 0; w < width; w++) {
                        int index = b * channels * height * width + c * height * width + h * width + w;
                        csv.append(data[index]);
                        if (w < width - 1) csv.append(",");
                    }
                    csv.append("\n");
                }
                csv.append("\n");
            }
        }
    }
    
    private static void dumpTensor5D(StringBuilder csv, float[] data, long[] shape) {
        // [batch, channels, depth, height, width]
        int batch = (int) shape[0];
        int channels = (int) shape[1];
        int depth = (int) shape[2];
        int height = (int) shape[3];
        int width = (int) shape[4];
        
        for (int b = 0; b < batch; b++) {
            for (int c = 0; c < channels; c++) {
                for (int d = 0; d < depth; d++) {
                    csv.append("# Batch ").append(b).append(", Channel ").append(c).append(", Depth ").append(d).append("\n");
                    for (int h = 0; h < height; h++) {
                        for (int w = 0; w < width; w++) {
                            int index = b * channels * depth * height * width + 
                                       c * depth * height * width + 
                                       d * height * width + 
                                       h * width + w;
                            csv.append(data[index]);
                            if (w < width - 1) csv.append(",");
                        }
                        csv.append("\n");
                    }
                    csv.append("\n");
                }
            }
        }
    }
    
    // Prevent instantiation
    private DebugUtils() {}
}
