package com.voxeltree.harvester;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.voxeltree.harvester.ingest.IngestAllCommand;
import com.voxeltree.harvester.ingest.IngestPayload;
import com.voxeltree.harvester.noise.NoiseDumperCommand;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.world.TickRateManager;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.storage.LevelResource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public class DataHarvesterMod implements ModInitializer {

    public static final Logger LOGGER = LoggerFactory.getLogger("dataharvester");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    @Override
    public void onInitialize() {
        LOGGER.info("[DataHarvester] Registering server-side commands...");
        PayloadTypeRegistry.playS2C().register(IngestPayload.TYPE, IngestPayload.CODEC);
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            NoiseDumperCommand.register(dispatcher);
            LOGGER.info("[DataHarvester] /dumpnoise command registered.");
            IngestAllCommand.register(dispatcher);
            LOGGER.info("[DataHarvester] /ingestall command registered.");
        });
        ServerLifecycleEvents.SERVER_STARTED.register(DataHarvesterMod::onServerStarted);
    }

    private static void onServerStarted(MinecraftServer server) {
        Path marker = Path.of("config", "oracle_mode.json");
        boolean isOracle = false;
        String levelName = null;
        try {
            Path root = server.getWorldPath(LevelResource.ROOT);
            if (root != null && root.getFileName() != null) {
                levelName = root.getFileName().toString();
            }
            if (levelName != null && levelName.startsWith("oracle_")) isOracle = true;
        } catch (Exception ignored) {}

        if (!isOracle && Files.exists(marker)) {
            try {
                String json = Files.readString(marker);
                if (json.contains("oracle_end_chorus")) isOracle = true;
                if (levelName == null) {
                    int idx = json.indexOf("level_name");
                    if (idx >= 0) {
                        int start = json.indexOf("\"", idx + 12);
                        int end = json.indexOf("\"", start + 1);
                        if (start >= 0 && end > start) levelName = json.substring(start + 1, end);
                    }
                }
            } catch (IOException ignored) {}
        }
        if (!isOracle) return;

        LOGGER.info("[DataHarvester][Oracle] Detected oracle mode (level='{}'), applying hard freeze before first tick...", levelName);

        boolean frozen = false;
        int tickCount = -1;
        int randomTickSpeed = -1;
        String error = null;
        StringBuilder debug = new StringBuilder();

        try {
            // Typed Mojang APIs - verify state, not command results
            ServerTickRateManager serverTickManager = server.tickRateManager();
            serverTickManager.setFrozen(true);
            // Also freeze world tick rate manager
            TickRateManager worldTickManager = server.overworld().tickRateManager();
            worldTickManager.setFrozen(true);
            frozen = worldTickManager.isFrozen();
            debug.append(" isFrozen=").append(frozen).append(";");

            // Set randomTickSpeed to 0 via typed GameRules API
            server.overworld().getGameRules().set(GameRules.RANDOM_TICK_SPEED, 0, server);
            // Verify via typed getter
            Object rtsObj = server.overworld().getGameRules().get(GameRules.RANDOM_TICK_SPEED);
            if (rtsObj instanceof Integer) randomTickSpeed = (Integer) rtsObj;
            else if (rtsObj instanceof Number) randomTickSpeed = ((Number) rtsObj).intValue();
            else randomTickSpeed = Integer.parseInt(rtsObj.toString());
            debug.append(" randomTickSpeed=").append(randomTickSpeed).append(";");

            tickCount = server.getTickCount();
            debug.append(" tickCount=").append(tickCount).append(";");

            if (!frozen) {
                error = "tick manager not frozen isFrozen=false" + debug;
            } else if (randomTickSpeed != 0) {
                error = "randomTickSpeed != 0 actual=" + randomTickSpeed + debug;
            } else if (tickCount != 0) {
                error = "tickCount != 0 actual=" + tickCount + debug;
            }
        } catch (Exception e) {
            String msg = e.toString() + debug;
            error = (error == null ? msg : error + "; " + msg);
            LOGGER.error("[DataHarvester] Failed freeze verification {}", msg, e);
        }

        Map<String, Object> receipt = new LinkedHashMap<>();
        receipt.put("simulationFrozenBeforeFirstTick", frozen && error == null);
        receipt.put("randomTickSpeed", randomTickSpeed);
        receipt.put("tickCountAtFreeze", tickCount);
        receipt.put("levelName", levelName);
        receipt.put("timestamp", Instant.now().toString());
        if (error != null) receipt.put("error", error);
        receipt.put("frozen", frozen);
        if (debug.length() > 0) receipt.put("debug", debug.toString());

        Path receiptPath = Path.of("config", "oracle_startup_receipt.json");
        Path alt = Path.of("oracle_startup_receipt.json");
        try {
            Files.createDirectories(receiptPath.getParent());
            String json = GSON.toJson(receipt);
            Files.writeString(receiptPath, json);
            Files.writeString(alt, json);
            LOGGER.info("[DataHarvester] Wrote receipt {}: {}", receiptPath.toAbsolutePath(), json);
        } catch (IOException e) {
            LOGGER.error("[DataHarvester] Failed to write receipt", e);
        }

        if (!frozen || randomTickSpeed != 0 || tickCount != 0) {
            LOGGER.error("[DataHarvester] Invariant FAILED frozen={} rts={} tickCount={} err={} debug={}", frozen, randomTickSpeed, tickCount, error, debug);
        } else {
            LOGGER.info("[DataHarvester] Invariant OK frozen before first tick rts=0 tickCount={}", tickCount);
        }
    }
}
