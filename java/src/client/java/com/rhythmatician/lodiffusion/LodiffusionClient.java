package com.rhythmatician.lodiffusion;

import com.rhythmatician.lodiffusion.voxy.LodGenerationService;
import com.rhythmatician.lodiffusion.voxy.VoxyCompat;
import com.rhythmatician.lodiffusion.voxy.VoxyDebugState;


import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.minecraft.text.Text;

/**
 * Client-side entrypoint for LODiffusion.
 *
 * <p>Registers lifecycle events to start/stop the background LOD generation
 * service when the player joins/leaves a world.  The service runs on a
 * daemon thread and feeds ONNX-generated terrain into Voxy for distant
 * LOD rendering.
 */
@Environment(EnvType.CLIENT)
public class LodiffusionClient implements ClientModInitializer {

    private static final LodGenerationService LOD_SERVICE = new LodGenerationService();

    @Override
    public void onInitializeClient() {
        HelloTerrainMod.LOGGER.info("[LODiffusion] Client initializer starting");

        // --- Connection init: pre-load ONNX models during "Logging in..." screen ---
        ClientPlayConnectionEvents.INIT.register((handler, client) -> {
            if (VoxyCompat.isAvailable() && Config.useOnnxTerrain()) {
                HelloTerrainMod.LOGGER.info("[LODiffusion] Connection init — pre-loading ONNX models");
                LOD_SERVICE.preloadModel();
            }
        });

        // --- World join: start LOD generation ---
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!VoxyCompat.isAvailable()) {
                HelloTerrainMod.LOGGER.warn("[LODiffusion] Voxy not available — LOD generation disabled");
                return;
            }
            if (!Config.useOnnxTerrain()) {
                HelloTerrainMod.LOGGER.info("[LODiffusion] ONNX terrain disabled in config");
                return;
            }

            // JOIN fires on the render thread with client.world already set.
            // start() just spawns a daemon thread and returns — no need to defer.

            // Seed position immediately from the player entity.
            if (client.player != null) {
                LOD_SERVICE.updatePlayerPosition(client.player.getBlockPos());
            }

            if (client.world != null) {
                HelloTerrainMod.LOGGER.info("[LODiffusion] World joined — starting LOD generation service");
                LOD_SERVICE.start(client.world, client.getServer());
            }
        });

        // --- World leave: stop LOD generation ---
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HelloTerrainMod.LOGGER.info("[LODiffusion] Disconnected — stopping LOD generation service");
            LOD_SERVICE.stop();
        });

        // --- Client tick: update player position ---
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            if (LOD_SERVICE.isRunning() && client.player != null) {
                LOD_SERVICE.updatePlayerPosition(client.player.getBlockPos());
            }
        });

        // register debug toggle command in our own namespace
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("voxygen")
                    .then(ClientCommandManager.literal("debug")
                        .executes(ctx -> {
                            int next = VoxyDebugState.occlusionDebugState == 0 ? 1 : 0;
                            VoxyDebugState.occlusionDebugState = next;
                            ctx.getSource().sendFeedback(
                                Text.literal("Voxy occlusion debug " + (next != 0 ? "enabled" : "disabled"))
                            );
                            return 1;
                        })
                    )
            );
        });

        HelloTerrainMod.LOGGER.info("[LODiffusion] Client initializer complete");
    }

    /** Get the active LOD generation service (for status commands etc). */
    public static LodGenerationService getLodService() {
        return LOD_SERVICE;
    }
}
