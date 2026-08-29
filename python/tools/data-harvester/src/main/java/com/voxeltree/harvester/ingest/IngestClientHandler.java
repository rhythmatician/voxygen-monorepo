package com.voxeltree.harvester.ingest;

import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.Holder;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.RegistryFriendlyByteBuf;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.PalettedContainer;
import net.minecraft.world.level.chunk.PalettedContainerFactory;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Method;
import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

public class IngestClientHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("dataharvester");

    private static boolean bridgeInitialized;
    private static MethodHandle rawIngestMethod;
    private static MethodHandle worldIdOfMethod;
    private static int sectionsIngested;

    // Hardened BatchState: exact expected (cx,cy,cz) keys, synchronized, before rawIngest registration
    private static final Object BATCH_LOCK = new Object();
    private static String currentBatchId = "";
    private static int expectedChunksForBatch = 0;
    private static final Set<net.minecraft.world.level.ChunkPos> receivedChunksForBatch = Collections.synchronizedSet(new HashSet<>());
    private static final Set<Long> pendingKeys = ConcurrentHashMap.newKeySet();
    private static final Set<Long> completedKeys = ConcurrentHashMap.newKeySet();
    private static volatile boolean allPayloadsProcessed = false;
    private static int failedEnqueuesForBatch = 0;
    private static int expectedEnqueuedSections = 0;

    private static long keyFor(int cx, int cy, int cz) {
        return ((long) cx << 32) ^ ((long) cy << 16) ^ (cz & 0xFFFFL);
    }

    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(IngestPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> handlePayload(payload)));
        LOGGER.info("[DataHarvester] Client ingest handler registered.");
    }

    @SuppressWarnings("unchecked")
    private static void handlePayload(IngestPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        if (!bridgeInitialized) initVoxyBridge();
        if (rawIngestMethod == null) return;

        String batchId = payload.batchId();
        int batchTotal = payload.batchTotal();
        boolean isOracle = batchId != null && !batchId.isEmpty();
        if (isOracle) {
            synchronized (BATCH_LOCK) {
                if (!batchId.equals(currentBatchId)) {
                    currentBatchId = batchId;
                    expectedChunksForBatch = batchTotal;
                    receivedChunksForBatch.clear();
                    pendingKeys.clear();
                    completedKeys.clear();
                    allPayloadsProcessed = false;
                    failedEnqueuesForBatch = 0;
                    expectedEnqueuedSections = 0;
                    try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("config", "oracle_ingest_ack.json")); } catch (Exception ignored) {}
                    LOGGER.info("[DataHarvester] Starting oracle batch {} expecting {} chunks (exact keys, RETURN barrier)", batchId, batchTotal);
                }
            }
        }

        // For oracle, we will track pending keys before enqueue
        // Note: receivedChunks should only be considered after all sections for this payload are enqueued, to avoid race where RETURN sees all chunks before final enqueue
        boolean payloadEnqueueFailed = false;
        int enqueuedThisPayload = 0;
        for (IngestPayload.SectionData sd : payload.sections()) {
            io.netty.buffer.ByteBuf statesRaw =
                    io.netty.buffer.Unpooled.wrappedBuffer(sd.states());
            io.netty.buffer.ByteBuf biomesRaw =
                    io.netty.buffer.Unpooled.wrappedBuffer(sd.biomes());
            try {
                PalettedContainerFactory factory =
                        PalettedContainerFactory.create(level.registryAccess());
                LevelChunkSection section = new LevelChunkSection(factory);
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(statesRaw), level.registryAccess());
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);
                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(biomesRaw), level.registryAccess());
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);
                DataLayer bl = sd.blockLight() != null
                        ? new DataLayer(sd.blockLight()) : null;
                DataLayer sl = sd.skyLight() != null
                        ? new DataLayer(sd.skyLight()) : null;

                if (isOracle) {
                    long key = keyFor(payload.pos().x, sd.y(), payload.pos().z);
                    // Register before enqueue
                    pendingKeys.add(key);
                    synchronized (BATCH_LOCK) { expectedEnqueuedSections++; }
                }
                boolean enqueued = invokeRawIngest(level, section,
                        payload.pos().x, sd.y(), payload.pos().z, bl, sl);
                if (isOracle) {
                    if (enqueued) {
                        enqueuedThisPayload++;
                    } else {
                        long key = keyFor(payload.pos().x, sd.y(), payload.pos().z);
                        pendingKeys.remove(key);
                        synchronized (BATCH_LOCK) { failedEnqueuesForBatch++; }
                        LOGGER.error("[DataHarvester] Oracle batch {} failed to enqueue section ({},{},{})", currentBatchId, payload.pos().x, sd.y(), payload.pos().z);
                        payloadEnqueueFailed = true;
                    }
                }
                sectionsIngested++;
                if (sectionsIngested % 5000 == 0) {
                    LOGGER.info("[DataHarvester] Client: {} sections ingested (batch {} {}/{})",
                            sectionsIngested, currentBatchId, receivedChunksForBatch.size(), expectedChunksForBatch);
                }
            } catch (Exception e) {
                LOGGER.error("[DataHarvester] Section ({}, {}, {}) ingest failed",
                        payload.pos().x, sd.y(), payload.pos().z, e);
                if (isOracle) {
                    long key = keyFor(payload.pos().x, sd.y(), payload.pos().z);
                    pendingKeys.remove(key);
                    synchronized (BATCH_LOCK) { failedEnqueuesForBatch++; }
                }
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
        if (isOracle) {
            // Now mark this chunk as received, after all its sections enqueued
            receivedChunksForBatch.add(payload.pos());
            synchronized (BATCH_LOCK) {
                if (receivedChunksForBatch.size() >= expectedChunksForBatch) {
                    allPayloadsProcessed = true;
                }
            }
            // Check for completion or failure
            synchronized (BATCH_LOCK) {
                if (failedEnqueuesForBatch > 0) {
                    LOGGER.error("[DataHarvester] Batch {} has {} failed enqueues - writing failure ack", batchId, failedEnqueuesForBatch);
                    writeIngestFailure(batchId, expectedChunksForBatch, receivedChunksForBatch.size(), completedKeys.size(), failedEnqueuesForBatch, expectedEnqueuedSections);
                } else if (allPayloadsProcessed && pendingKeys.isEmpty() && receivedChunksForBatch.size() >= expectedChunksForBatch) {
                    // All enqueued and all RETURNed
                    LOGGER.info("[DataHarvester] Batch {} TRUE completion: {}/{} chunks, {}/{} sections via RETURN", batchId, receivedChunksForBatch.size(), expectedChunksForBatch, completedKeys.size(), expectedEnqueuedSections);
                    writeIngestAck(batchId, expectedChunksForBatch, receivedChunksForBatch.size(), completedKeys.size(), expectedEnqueuedSections);
                } else if (allPayloadsProcessed) {
                    LOGGER.info("[DataHarvester] Batch {} enqueued {}/{} chunks, pending {} completed {} / {}", batchId, receivedChunksForBatch.size(), expectedChunksForBatch, pendingKeys.size(), completedKeys.size(), expectedEnqueuedSections);
                }
            }
        }
    }

    private static void writeIngestAck(String batchId, int expected, int received, int completed, int expectedSections) {
        try {
            java.nio.file.Path ackPath = java.nio.file.Path.of("config", "oracle_ingest_ack.json");
            java.nio.file.Files.createDirectories(ackPath.getParent());
            String json = String.format("{\"batchId\":\"%s\",\"expectedChunks\":%d,\"receivedChunks\":%d,\"completedSections\":%d,\"expectedEnqueuedSections\":%d,\"timestamp\":%d,\"success\":true}",
                    batchId, expected, received, completed, expectedSections, System.currentTimeMillis());
            java.nio.file.Files.writeString(ackPath, json);
            LOGGER.info("[DataHarvester] Wrote ingest ack {}", ackPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[DataHarvester] Failed to write ingest ack", e);
        }
    }

    private static void writeIngestFailure(String batchId, int expected, int received, int completed, int failed, int expectedSections) {
        try {
            java.nio.file.Path ackPath = java.nio.file.Path.of("config", "oracle_ingest_ack.json");
            java.nio.file.Files.createDirectories(ackPath.getParent());
            String json = String.format("{\"batchId\":\"%s\",\"expectedChunks\":%d,\"receivedChunks\":%d,\"completedSections\":%d,\"expectedEnqueuedSections\":%d,\"failedEnqueues\":%d,\"timestamp\":%d,\"success\":false,\"error\":\"enqueue failed\"}",
                    batchId, expected, received, completed, expectedSections, failed, System.currentTimeMillis());
            java.nio.file.Files.writeString(ackPath, json);
            LOGGER.error("[DataHarvester] Wrote ingest FAILURE ack {}", ackPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[DataHarvester] Failed to write ingest failure ack", e);
        }
    }

    private static void initVoxyBridge() {
        bridgeInitialized = true;
        try {
            Class<?> ingestClass = Class.forName(
                    "me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdClass = Class.forName(
                    "me.cortex.voxy.commonImpl.WorldIdentifier");
            MethodHandles.Lookup lookup = MethodHandles.lookup();
            Method raw = ingestClass.getMethod("rawIngest",
                    worldIdClass,
                    LevelChunkSection.class,
                    int.class, int.class, int.class,
                    DataLayer.class, DataLayer.class);
            rawIngestMethod = lookup.unreflect(raw);
            Method of = worldIdClass.getMethod("of", Level.class);
            worldIdOfMethod = lookup.unreflect(of);
            LOGGER.info("[DataHarvester] Voxy ingest bridge initialised (rawIngest found).");
        } catch (ClassNotFoundException e) {
            LOGGER.warn("[DataHarvester] Voxy not found — ingest bridge disabled. "
                    + "Install Voxy to enable /ingestall client-side ingestion.");
        } catch (Exception e) {
            LOGGER.error("[DataHarvester] Failed to initialise Voxy bridge", e);
        }
    }

    private static boolean invokeRawIngest(Level level, LevelChunkSection section,
                                        int cx, int cy, int cz,
                                        DataLayer blockLight, DataLayer skyLight) {
        try {
            Object worldId = worldIdOfMethod.invoke(level);
            if (worldId == null) return false;
            Object result = rawIngestMethod.invoke(worldId, section, cx, cy, cz, blockLight, skyLight);
            if (result instanceof Boolean b) return b;
            return true;
        } catch (Throwable e) {
            LOGGER.error("[DataHarvester] rawIngest call failed for ({}, {}, {})",
                    cx, cy, cz, e);
            return false;
        }
    }

    public static void notifyInsertUpdateReturn(int cx, int cy, int cz) {
        if (currentBatchId == null || currentBatchId.isEmpty() || expectedChunksForBatch <= 0) return;
        long key = keyFor(cx, cy, cz);
        // Only complete if key was expected for this batch
        boolean wasPending = pendingKeys.remove(key);
        if (!wasPending) {
            // Unrelated Voxy insert (e.g., other chunks, duplicates) - ignore
            return;
        }
        completedKeys.add(key);
        if (completedKeys.size() % 1000 == 0) {
            LOGGER.info("[DataHarvester] Oracle batch {} RETURN {}/{} sections", currentBatchId, completedKeys.size(), expectedEnqueuedSections);
        }
        synchronized (BATCH_LOCK) {
            if (allPayloadsProcessed && pendingKeys.isEmpty() && failedEnqueuesForBatch == 0 && receivedChunksForBatch.size() >= expectedChunksForBatch) {
                LOGGER.info("[DataHarvester] Batch {} TRUE completion via RETURN: {}/{} chunks, {}/{} sections", currentBatchId, receivedChunksForBatch.size(), expectedChunksForBatch, completedKeys.size(), expectedEnqueuedSections);
                writeIngestAck(currentBatchId, expectedChunksForBatch, receivedChunksForBatch.size(), completedKeys.size(), expectedEnqueuedSections);
            }
        }
    }
}
