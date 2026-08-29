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

/**
 * Client-side handler for {@link IngestPayload} packets.
 *
 * <p>When the server's {@code /ingestall} command sends a chunk's sections,
 * this handler deserialises them back into vanilla {@link LevelChunkSection}
 * objects and calls Voxy's {@code VoxelIngestService.rawIngest()} via
 * reflection — <em>the exact same code path</em> as VoxyWorldGen v2's
 * {@code NetworkClientHandler.handleLODData()}.
 *
 * <p>This guarantees <strong>absolute parity</strong> with Voxy's
 * ingestion algorithm:
 * <ol>
 *   <li>{@code WorldConversionFactory.convert()} — BlockState/Biome/Light → packed long[]</li>
 *   <li>{@code WorldConversionFactory.mipSection()} — build L0–L4 mip levels via {@code Mipper}</li>
 *   <li>{@code WorldUpdater.insertUpdate()} — write 32³ WorldSections + cascade up LOD tree</li>
 *   <li>{@code SectionSavingService} → ZSTD-compressed RocksDB persistence</li>
 * </ol>
 */
public class IngestClientHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("dataharvester");

    // ---- Voxy reflection bridge (initialised on first payload) ----
    private static boolean bridgeInitialized;
    private static MethodHandle rawIngestMethod;
    private static MethodHandle worldIdOfMethod;
    private static int sectionsIngested;
    // Batch tracking for oracle ingest barrier - true Voxy completion via insertUpdate RETURN
    private static String currentBatchId = "";
    private static int expectedChunksForBatch = 0;
    private static java.util.Set<net.minecraft.world.level.ChunkPos> receivedChunksForBatch = new java.util.HashSet<>();
    private static int completedSectionsForBatch = 0;
    private static int expectedEnqueuedSections = 0;
    private static int failedEnqueuesForBatch = 0;

    /**
     * Register the client-side payload handler.
     * Call from {@link com.voxeltree.harvester.DataHarvesterClient#onInitializeClient()}.
     */
    public static void init() {
        ClientPlayNetworking.registerGlobalReceiver(IngestPayload.TYPE,
                (payload, context) -> context.client().execute(
                        () -> handlePayload(payload)));
        LOGGER.info("[DataHarvester] Client ingest handler registered.");
    }

    // ==================== payload processing ====================

    /**
     * Deserialise each section from the payload and ingest it into Voxy.
     *
     * <p>This mirrors VoxyWorldGen v2's
     * {@code NetworkClientHandler.handleLODData()} line-for-line:
     * <ol>
     *   <li>Create an empty {@link LevelChunkSection} from a {@link PalettedContainerFactory}</li>
     *   <li>Read the serialised block-state palette into the section</li>
     *   <li>Read the serialised biome palette into the section</li>
     *   <li>Reconstruct light {@link DataLayer} objects</li>
     *   <li>Call Voxy's {@code VoxelIngestService.rawIngest()} via reflection</li>
     * </ol>
     */
    @SuppressWarnings("unchecked")
    private static void handlePayload(IngestPayload payload) {
        ClientLevel level = Minecraft.getInstance().level;
        if (level == null) return;

        if (!bridgeInitialized) initVoxyBridge();
        if (rawIngestMethod == null) return; // Voxy not available

        // Track batch for ack barrier
        String batchId = payload.batchId();
        int batchTotal = payload.batchTotal();
        if (batchId != null && !batchId.isEmpty()) {
            if (!batchId.equals(currentBatchId)) {
                currentBatchId = batchId;
                expectedChunksForBatch = batchTotal;
                receivedChunksForBatch.clear();
                completedSectionsForBatch = 0;
                expectedEnqueuedSections = 0;
                failedEnqueuesForBatch = 0;
                // Clear prior ack if exists
                try { java.nio.file.Files.deleteIfExists(java.nio.file.Path.of("config", "oracle_ingest_ack.json")); } catch (Exception ignored) {}
                LOGGER.info("[DataHarvester] Starting batch {} expecting {} chunks (true RETURN barrier)", batchId, batchTotal);
            }
            receivedChunksForBatch.add(payload.pos());
        }

        for (IngestPayload.SectionData sd : payload.sections()) {
            io.netty.buffer.ByteBuf statesRaw =
                    io.netty.buffer.Unpooled.wrappedBuffer(sd.states());
            io.netty.buffer.ByteBuf biomesRaw =
                    io.netty.buffer.Unpooled.wrappedBuffer(sd.biomes());
            try {
                // 1. Create empty section with correct registry backing
                PalettedContainerFactory factory =
                        PalettedContainerFactory.create(level.registryAccess());
                LevelChunkSection section = new LevelChunkSection(factory);

                // 2. Deserialise block-state palette
                RegistryFriendlyByteBuf statesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(statesRaw), level.registryAccess());
                ((PalettedContainer<BlockState>) section.getStates()).read(statesBuf);

                // 3. Deserialise biome palette
                RegistryFriendlyByteBuf biomesBuf = new RegistryFriendlyByteBuf(
                        new FriendlyByteBuf(biomesRaw), level.registryAccess());
                ((PalettedContainer<Holder<Biome>>) section.getBiomes()).read(biomesBuf);

                // 4. Reconstruct light layers
                DataLayer bl = sd.blockLight() != null
                        ? new DataLayer(sd.blockLight()) : null;
                DataLayer sl = sd.skyLight() != null
                        ? new DataLayer(sd.skyLight()) : null;

                // 5. Feed to Voxy's ingestion pipeline - oracle must fail closed
                boolean enqueued = invokeRawIngest(level, section,
                        payload.pos().x, sd.y(), payload.pos().z, bl, sl);
                if (enqueued) {
                    expectedEnqueuedSections++;
                } else {
                    failedEnqueuesForBatch++;
                    LOGGER.error("[DataHarvester] Oracle batch {} failed to enqueue section ({},{},{}) - batch will not ack success",
                            currentBatchId, payload.pos().x, sd.y(), payload.pos().z);
                }

                sectionsIngested++;
                // Do NOT increment completedSectionsForBatch here - wait for WorldUpdater.insertUpdate RETURN via mixin
                if (sectionsIngested % 5000 == 0) {
                    LOGGER.info("[DataHarvester] Client: {} sections ingested into Voxy (batch {} {}/{})",
                            sectionsIngested, currentBatchId, receivedChunksForBatch.size(), expectedChunksForBatch);
                }

            } catch (Exception e) {
                LOGGER.error("[DataHarvester] Section ({}, {}, {}) ingest failed",
                        payload.pos().x, sd.y(), payload.pos().z, e);
            } finally {
                statesRaw.release();
                biomesRaw.release();
            }
        }
        // Batch ack barrier: oracle must wait for true Voxy RETURN completion, not enqueue count.
        // Ack is now driven by notifyInsertUpdateReturn() when completedSections >= expectedEnqueuedSections.
        // If any enqueue failed, we must NOT ack success - write failure ack instead.
        if (batchId != null && !batchId.isEmpty() && expectedChunksForBatch > 0) {
            if (receivedChunksForBatch.size() >= expectedChunksForBatch) {
                if (failedEnqueuesForBatch > 0) {
                    LOGGER.error("[DataHarvester] Batch {} has {} failed enqueues - will not ack success, python must fail", batchId, failedEnqueuesForBatch);
                    writeIngestFailure(batchId, expectedChunksForBatch, receivedChunksForBatch.size(), completedSectionsForBatch, failedEnqueuesForBatch);
                } else {
                    // If we already have all enqueues and inserts returned, ack may have been written by RETURN handler.
                    // If not yet completed, log waiting
                    if (expectedEnqueuedSections > 0 && completedSectionsForBatch < expectedEnqueuedSections) {
                        LOGGER.info("[DataHarvester] Batch {} enqueued {}/{} chunks, waiting for RETURN {}/{} sections", batchId, receivedChunksForBatch.size(), expectedChunksForBatch, completedSectionsForBatch, expectedEnqueuedSections);
                    }
                }
            }
        }
    }

    private static void writeIngestAck(String batchId, int expected, int received, int sections) {
        try {
            java.nio.file.Path ackPath = java.nio.file.Path.of("config", "oracle_ingest_ack.json");
            java.nio.file.Files.createDirectories(ackPath.getParent());
            String json = String.format("{\"batchId\":\"%s\",\"expectedChunks\":%d,\"receivedChunks\":%d,\"completedSections\":%d,\"expectedEnqueuedSections\":%d,\"timestamp\":%d,\"success\":true}",
                    batchId, expected, received, sections, expectedEnqueuedSections, System.currentTimeMillis());
            java.nio.file.Files.writeString(ackPath, json);
            LOGGER.info("[DataHarvester] Wrote ingest ack {}", ackPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[DataHarvester] Failed to write ingest ack", e);
        }
    }

    private static void writeIngestFailure(String batchId, int expected, int received, int sections, int failed) {
        try {
            java.nio.file.Path ackPath = java.nio.file.Path.of("config", "oracle_ingest_ack.json");
            java.nio.file.Files.createDirectories(ackPath.getParent());
            String json = String.format("{\"batchId\":\"%s\",\"expectedChunks\":%d,\"receivedChunks\":%d,\"completedSections\":%d,\"failedEnqueues\":%d,\"timestamp\":%d,\"success\":false,\"error\":\"enqueue failed\"}",
                    batchId, expected, received, sections, failed, System.currentTimeMillis());
            java.nio.file.Files.writeString(ackPath, json);
            LOGGER.error("[DataHarvester] Wrote ingest FAILURE ack {}", ackPath.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("[DataHarvester] Failed to write ingest failure ack", e);
        }
    }

    // ==================== Voxy reflection bridge ====================

    /**
     * One-time reflection setup to find Voxy's ingest API.
     *
     * <p>Mirrors VoxyWorldGen v2's {@code VoxyIntegration} approach:
     * <ul>
     *   <li>{@code WorldIdentifier.of(Level)} — get dimension identity</li>
     *   <li>{@code VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection,
     *       cx, cy, cz, DataLayer, DataLayer)} — static ingest entry point</li>
     * </ul>
     */
    private static void initVoxyBridge() {
        bridgeInitialized = true;
        try {
            Class<?> ingestClass = Class.forName(
                    "me.cortex.voxy.common.world.service.VoxelIngestService");
            Class<?> worldIdClass = Class.forName(
                    "me.cortex.voxy.commonImpl.WorldIdentifier");

            MethodHandles.Lookup lookup = MethodHandles.lookup();

            // VoxelIngestService.rawIngest(WorldIdentifier, LevelChunkSection,
            //                              int cx, int cy, int cz,
            //                              DataLayer blockLight, DataLayer skyLight)
            Method raw = ingestClass.getMethod("rawIngest",
                    worldIdClass,
                    LevelChunkSection.class,
                    int.class, int.class, int.class,
                    DataLayer.class, DataLayer.class);
            rawIngestMethod = lookup.unreflect(raw);

            // WorldIdentifier.of(Level)
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

    /** Call Voxy's rawIngest via reflection. Returns true if enqueue succeeded, false if rejected. */
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

    // Called by OracleInsertUpdateTrackerMixin at RETURN of WorldUpdater.insertUpdate to count true Voxy completion
    public static void notifyInsertUpdateReturn(int cx, int cy, int cz) {
        // Only count if we have an active oracle batch
        if (currentBatchId == null || currentBatchId.isEmpty() || expectedChunksForBatch <= 0) return;
        completedSectionsForBatch++;
        // Log periodically
        if (completedSectionsForBatch % 1000 == 0) {
            LOGGER.info("[DataHarvester] Oracle batch {} insertUpdate RETURN {}/{} sections (expectedChunks {})",
                    currentBatchId, completedSectionsForBatch, expectedChunksForBatch * 8, expectedChunksForBatch);
        }
        // Check if all expected inserts have returned
        // For oracle, expectedSections is approximated as expectedChunks * sections per chunk for End (8 sections 0..128)
        // But we track via enqueue count: expectedEnqueuedSections holds number of sections we enqueued
        if (expectedEnqueuedSections > 0 && completedSectionsForBatch >= expectedEnqueuedSections && receivedChunksForBatch.size() >= expectedChunksForBatch) {
            // All enqueued sections have been inserted - ack true completion
            LOGGER.info("[DataHarvester] Batch {} TRUE completion: {}/{} chunks, {}/{} sections inserted via RETURN",
                    currentBatchId, receivedChunksForBatch.size(), expectedChunksForBatch, completedSectionsForBatch, expectedEnqueuedSections);
            writeIngestAck(currentBatchId, expectedChunksForBatch, receivedChunksForBatch.size(), completedSectionsForBatch);
        }
    }
}
