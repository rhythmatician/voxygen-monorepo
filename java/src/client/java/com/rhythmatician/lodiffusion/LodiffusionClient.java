package com.rhythmatician.lodiffusion;

import com.rhythmatician.lodiffusion.client.FlightTour;
import com.rhythmatician.lodiffusion.voxy.LodGenerationService;
import com.rhythmatician.lodiffusion.voxy.VoxyCompat;
import com.rhythmatician.lodiffusion.voxy.VoxyDatasetExportService;
import com.rhythmatician.lodiffusion.voxy.VoxyDebugState;
import com.rhythmatician.lodiffusion.voxy.LodOverlayState;
import com.rhythmatician.lodiffusion.voxy.VoxyNativeLodStats;
import com.rhythmatician.lodiffusion.world.noise.GpuNoiseDispatchQueue;


import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientChunkEvents;
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
 *
 * <p>Also manages the optional dataset export service for collecting training data.
 */
@Environment(EnvType.CLIENT)
@SuppressWarnings("null")
public class LodiffusionClient implements ClientModInitializer {

    private static final LodGenerationService LOD_SERVICE = new LodGenerationService();
    private static VoxyDatasetExportService DATASET_EXPORT_SERVICE = null;
    @Override
    public void onInitializeClient() {
        HelloTerrainMod.LOGGER.info("[LODiffusion] Client initializer starting");
        configureFlightTourFromLaunchEnvironment();

        // Publish the singleton so server-side command handlers can query stats.
        LodGenerationService.setInstance(LOD_SERVICE);

        // --- World join: start LOD generation and dataset export ---
        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (!VoxyCompat.isAvailable()) {
                HelloTerrainMod.LOGGER.warn("[LODiffusion] Voxy not available — LOD generation disabled");
                return;
            }
            if (!Config.useOnnxTerrain()) {
                HelloTerrainMod.LOGGER.info("[LODiffusion] ONNX terrain disabled in config");
            } else {
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
            }

            // Start dataset export service if enabled
            if (Config.isDatasetExportEnabled()) {
                startDatasetExportService();
            }
        });

        // --- World leave: stop LOD generation and dataset export ---
        ClientPlayConnectionEvents.DISCONNECT.register((handler, client) -> {
            HelloTerrainMod.LOGGER.info("[LODiffusion] Disconnected — stopping services");
            LOD_SERVICE.stop();
            stopDatasetExportService();
        });

        // This runs after vanilla has loaded a chunk. It records ownership only;
        // it neither queues nor delays vanilla's ingestion path.
        ClientChunkEvents.CHUNK_LOAD.register((world, chunk) ->
                LOD_SERVICE.observeVanillaChunkColumn(world, chunk.getPos().x, chunk.getPos().z));

        // --- Client tick: update player position + dimension-change-aware rebind + drain GPU noise queue ---
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            // Dimension-change-aware rebind — teleport to the_end activates tracer without rejoin.
            // Detects client.world.getRegistryKey() != last bound dimension and rebinds
            // via stop() + queue clear + start() so tracer-mode gate re-evaluates before
            // worker entry. Debounced via service lock.
            if (client.world != null) {
                LOD_SERVICE.checkAndRebindIfNeeded(client.world, client.getServer());
            }
            if (LOD_SERVICE.isRunning()) {
                var pl = client.player;
                if (pl != null) {
                    var velocity = pl.getVelocity();
                int viewDistance = client.options.getViewDistance().getValue();
                int simulationDistance = client.options.getSimulationDistance().getValue();
                LOD_SERVICE.updatePlayerPosition(pl.getBlockPos(), velocity.x, velocity.z,
                        viewDistance, simulationDistance);
                }
            }
            // Drain pending GPU noise requests on the render thread (GL context).
            // No-ops if the dispatch queue hasn't been initialised yet.
            GpuNoiseDispatchQueue.tickDrain();
            if (client.world != null && client.player != null
                    && client.currentScreen == null
                    && client.worldRenderer != null
                    && client.worldRenderer.getCompletedChunkCount() > 0
                    && client.worldRenderer.isTerrainRenderComplete()) {
                FlightTour.noteRenderedFrame();
            }
            FlightTour.tick(client);
        });

        // register debug toggle command in our own namespace
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                ClientCommandManager.literal("voxygen")
                    .then(ClientCommandManager.literal("tour")
                        .executes(ctx -> {
                            FlightTour.start();
                            ctx.getSource().sendFeedback(
                                    Text.literal("Voxygen flight tour started; use /voxygen tour stop to cancel."));
                            return 1;
                        })
                        .then(ClientCommandManager.literal("stop")
                            .executes(ctx -> {
                                FlightTour.stop();
                                ctx.getSource().sendFeedback(Text.literal("Voxygen flight tour stopped."));
                                return 1;
                            })
                        )
                    )
                    .then(ClientCommandManager.literal("debug")
                        .executes(ctx -> {
                            int next = VoxyDebugState.occlusionDebugState == 0 ? 1 : 0;
                            VoxyDebugState.occlusionDebugState = next;
                            @SuppressWarnings("null")
                            Text msg = Text.literal("Voxy occlusion debug " + (next != 0 ? "enabled" : "disabled"));
                            ctx.getSource().sendFeedback(msg);
                            return 1;
                        })
                    )
            );
        });

        // Oracle capture file trigger — deterministic process-level oracle regeneration (DataHarvester pipeline)
        try {
            com.rhythmatician.lodiffusion.oracle.capture.OracleFileTrigger.register();
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[LODiffusion] OracleFileTrigger register failed", e);
        }

        // Client command for manual oracle capture: /voxygen oracle capture
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> {
            dispatcher.register(
                net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("voxygen")
                    .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("oracle")
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("capture")
                            .executes(ctx -> {
                                try {
                                    var contract = com.rhythmatician.lodiffusion.oracle.EndChorusTracerContract.contract();
                                    var fixture = com.rhythmatician.lodiffusion.oracle.capture.WorldSectionOracleCapture.capture(contract);
                                    var out = com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter.defaultFixturePath(contract);
                                    com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter.write(fixture, out);
                                    ctx.getSource().sendFeedback(net.minecraft.text.Text.literal("Oracle fixture captured: " + fixture.provenanceId() + " sha=" + fixture.contentSha256().substring(0,12) + " -> " + out));
                                    return 1;
                                } catch (Exception e) {
                                    ctx.getSource().sendError(net.minecraft.text.Text.literal("Oracle capture failed: " + e.getMessage()));
                                    HelloTerrainMod.LOGGER.error("[Oracle] capture failed", e);
                                    return 0;
                                }
                            })
                        )
                        .then(net.fabricmc.fabric.api.client.command.v2.ClientCommandManager.literal("status")
                            .executes(ctx -> {
                                var level = net.minecraft.client.MinecraftClient.getInstance().world;
                                if (level == null) {
                                    ctx.getSource().sendError(net.minecraft.text.Text.literal("No world loaded"));
                                    return 0;
                                }
                                Object we = null;
                                try {
                                    Class<?> ve = Class.forName("com.rhythmatician.lodiffusion.voxy.VoxyEngine");
                                    var m = ve.getDeclaredMethod("getWorldEngine", net.minecraft.world.World.class);
                                    m.setAccessible(true);
                                    we = m.invoke(null, level);
                                } catch (Exception e) { we = null; }
                                String msg = we != null ? "Voxy WorldEngine available: " + we.getClass().getSimpleName() : "Voxy WorldEngine not available (Voxy not installed or world not loaded)";
                                ctx.getSource().sendFeedback(net.minecraft.text.Text.literal(msg));
                                return 1;
                            })
                        )
                    )
            );
        });

        HelloTerrainMod.LOGGER.info("[LODiffusion] Client initializer complete");
    }

    private static void configureFlightTourFromLaunchEnvironment() {
        FlightTourLaunchConfig.ParseResult parsed =
                FlightTourLaunchConfig.parse(System.getenv(), System.getProperties());
        if (parsed.invalidTimeout() != null) {
            HelloTerrainMod.LOGGER.warn("[LODiffusion] Invalid flight tour timeout '{}', using default timeout",
                    parsed.invalidTimeout());
        }
        if (parsed.invalidDwell() != null) {
            HelloTerrainMod.LOGGER.warn("[LODiffusion] Invalid flight tour dwell '{}', using launch default",
                    parsed.invalidDwell());
        }

        FlightTourLaunchConfig config = parsed.config();
        FlightTour.configureAutoStart(
                config.autoStart(), config.timeoutTicks(), config.dwellTicks(), config.runId());
        // Voxy selects the HAS_STATISTICS shader variant when its traverser is constructed.
        // Client entrypoints run before that renderer construction, so this is the last safe
        // opt-in point for both overlay and AFK evidence diagnostics.
        VoxyNativeLodStats.enableForDiagnostics(LodOverlayState.isEnabled(), config.autoStart());
        HelloTerrainMod.LOGGER.info(
                "[LODiffusion][FlightTourLaunch] resolved runId={} autoStart={} timeout={} dwell={} ticks",
                config.runId(), config.autoStart(), config.timeoutTicks(), config.dwellTicks());
        if (config.autoStart()) {
            HelloTerrainMod.LOGGER.info("[LODiffusion] Flight tour auto-start enabled; dwell={} ticks",
                    config.dwellTicks());
        }
    }

    /** Get the active LOD generation service (for status commands etc). */
    public static LodGenerationService getLodService() {
        return LOD_SERVICE;
    }

    /**
     * Start the dataset export service if it's not already running.
     */
    private static void startDatasetExportService() {
        if (DATASET_EXPORT_SERVICE != null && DATASET_EXPORT_SERVICE.isActive()) {
            HelloTerrainMod.LOGGER.info("[LODiffusion] Dataset export service already running");
            return;
        }

        try {
            var exportPath = Config.getDatasetExportPath();
            var formatStr = Config.getDatasetExportFormat();
            var format = VoxyDatasetExportService.Format.valueOf(formatStr.toUpperCase());

            DATASET_EXPORT_SERVICE = new VoxyDatasetExportService(exportPath, format);
            if (DATASET_EXPORT_SERVICE.start()) {
                HelloTerrainMod.LOGGER.info("[LODiffusion] Dataset export service started: {}",
                        DATASET_EXPORT_SERVICE);
            } else {
                HelloTerrainMod.LOGGER.error("[LODiffusion] Failed to start dataset export service");
            }
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.error("[LODiffusion] Error starting dataset export service", e);
        }
    }

    /**
     * Stop the dataset export service if it's running.
     */
    private static void stopDatasetExportService() {
        if (DATASET_EXPORT_SERVICE != null) {
            DATASET_EXPORT_SERVICE.stop();
            HelloTerrainMod.LOGGER.info("[LODiffusion] Dataset export service stopped");
            DATASET_EXPORT_SERVICE = null;
        }
    }
}
