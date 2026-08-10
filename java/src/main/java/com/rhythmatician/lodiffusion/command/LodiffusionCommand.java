package com.rhythmatician.lodiffusion.command;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.rhythmatician.lodiffusion.Config;
import java.nio.file.Files;
import com.rhythmatician.lodiffusion.util.DebugUtils;
import com.rhythmatician.lodiffusion.util.PerformanceMonitor;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;

/**
 * Command interface for LODiffusion management and debugging.
 * Provides runtime control over ONNX terrain generation.
 */
public final class LodiffusionCommand {
    
    /**
     * Register the /lodiffusion command with the server.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("lodiffusion")
            .requires(source -> source.getPermissions().hasPermission(new Permission.Level(PermissionLevel.GAMEMASTERS))) // Requires OP permissions
            
            // Status subcommand
            .then(CommandManager.literal("status")
                .executes(context -> executeStatus(context)))
            
            // Toggle ONNX terrain generation
            .then(CommandManager.literal("toggle")
                .executes(context -> executeToggle(context)))
            
            // Performance report
            .then(CommandManager.literal("performance")
                .executes(context -> executePerformance(context)))
            
            // Reset metrics
            .then(CommandManager.literal("reset")
                .executes(context -> executeReset(context)))
            
            // System debug report
            .then(CommandManager.literal("debug")
                .executes(context -> executeDebug(context)))
            
            // Reload model
            .then(CommandManager.literal("reload")
                .executes(context -> executeReload(context)))
        );
    }
    
    private static int executeStatus(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        StringBuilder status = new StringBuilder();
        status.append("§6=== LODiffusion Status ===§r\n");
        status.append("§7ONNX Terrain: §").append(Config.useOnnxTerrain() ? "aEnabled" : "cDisabled").append("§r\n");
        status.append("§7Current Adapter: §f").append(Config.adapter()).append("§r\n");
        java.nio.file.Path modelDir = Config.modelDir();
        boolean modelsPresent = Files.isRegularFile(modelDir.resolve("init_to_lod4.onnx"))
                && Files.isRegularFile(modelDir.resolve("refine_lod2_to_lod1.onnx"));
        status.append("§7Models Present: §").append(modelsPresent ? "aYes" : "cNo").append("§r\n");
        status.append("§7Model Dir: §f").append(modelDir).append("§r\n");
        
        long chunksGenerated = PerformanceMonitor.getCounter(PerformanceMonitor.CHUNKS_GENERATED);
        long onnxInferences = PerformanceMonitor.getCounter(PerformanceMonitor.ONNX_INFERENCES);
        long fallbackUses = PerformanceMonitor.getCounter(PerformanceMonitor.FALLBACK_USES);
        
        status.append("§7Chunks Generated: §f").append(chunksGenerated).append("§r\n");
        status.append("§7ONNX Inferences: §f").append(onnxInferences).append("§r\n");
        status.append("§7Fallback Uses: §f").append(fallbackUses).append("§r\n");
        
        if (chunksGenerated > 0) {
            double onnxRate = (onnxInferences * 100.0) / chunksGenerated;
            status.append("§7ONNX Success Rate: §f").append(String.format("%.1f%%", onnxRate)).append("§r");
        }
        
        source.sendFeedback(() -> Text.literal(status.toString()), false);
        return 1;
    }
    
    private static int executeToggle(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        boolean newState = !Config.useOnnxTerrain();
        Config.setUseOnnxTerrain(newState);
        
        String message = String.format("§6LODiffusion ONNX terrain generation §%s%s§6.§r", 
            newState ? "a" : "c", newState ? "enabled" : "disabled");
        source.sendFeedback(() -> Text.literal(message), true);
        
        return 1;
    }
    
    private static int executePerformance(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        String report = PerformanceMonitor.getPerformanceReport();
        String[] lines = report.split("\n");
        
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                source.sendFeedback(() -> Text.literal("§7" + line + "§r"), false);
            }
        }
        
        return 1;
    }
    
    private static int executeReset(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        PerformanceMonitor.reset();
        source.sendFeedback(() -> Text.literal("§6Reset all performance metrics.§r"), true);
        
        return 1;
    }
    
    private static int executeDebug(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        
        String report = DebugUtils.createSystemReport();
        String[] lines = report.split("\n");
        
        // Send report in chunks to avoid chat spam
        int chunkSize = 10;
        for (int i = 0; i < lines.length; i += chunkSize) {
            StringBuilder chunk = new StringBuilder();
            for (int j = i; j < Math.min(i + chunkSize, lines.length); j++) {
                if (!lines[j].trim().isEmpty()) {
                    chunk.append("§7").append(lines[j]).append("§r\n");
                }
            }
            
            if (chunk.length() > 0) {
                final String finalChunk = chunk.toString();
                source.sendFeedback(() -> Text.literal(finalChunk), false);
                
                // Small delay between chunks to prevent flooding
                try {
                    Thread.sleep(100);
                } catch (InterruptedException ignored) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        
        return 1;
    }
    
    private static int executeReload(CommandContext<ServerCommandSource> context) {
        ServerCommandSource source = context.getSource();
        // Models are managed by LodGenerationService lifecycle — stop/restart the
        // service to pick up new ONNX files.  For now we just validate the files exist.
        java.nio.file.Path modelDir = Config.modelDir();
        boolean modelsPresent = Files.isRegularFile(modelDir.resolve("init_to_lod4.onnx"))
                && Files.isRegularFile(modelDir.resolve("refine_lod2_to_lod1.onnx"));
        if (modelsPresent) {
            source.sendFeedback(() -> Text.literal("§aProgressive model files found in " + modelDir + ". Restart the world to reload.§r"), true);
        } else {
            source.sendFeedback(() -> Text.literal("§cModel files not found in " + modelDir + "§r"), true);
        }
        return 1;
    }
    
    // Prevent instantiation
    private LodiffusionCommand() {}
}
