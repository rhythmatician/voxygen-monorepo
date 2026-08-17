package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;
import com.rhythmatician.lodiffusion.onnx.VoxyModelRunner;
import com.rhythmatician.lodiffusion.world.noise.BiomeProvider;
import com.rhythmatician.lodiffusion.world.noise.RouterField;
import com.rhythmatician.lodiffusion.world.noise.HeightmapData;
import com.rhythmatician.lodiffusion.world.noise.NoiseRouterSampler;
import com.rhythmatician.lodiffusion.world.noise.NoiseRouterSamplerFactory;
import com.rhythmatician.lodiffusion.world.noise.SectionNoiseData;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;

import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkStatus;

/**
 * Background service that generates terrain around the player using the
 * Voxy ONNX model set and pushes results into Voxy for distant rendering.
 *
 * <h3>Architecture — Demand-driven Voxy Pipeline</h3>
 * <p>Generates sections from demand requests around the player. Results are
 * written to Voxy through {@link VoxelVolumeWriter}.
 *
 * <p>Sections are prioritised by Manhattan distance from the player.
 */
@SuppressWarnings("deprecation")
public final class GenerationSession {

    /** How many sections of Y range to generate (from y=-64 upward). */
    static final int Y_SECTIONS = 16;  // y sections -4..11 → blocks -64..191
    static final int Y_BASE_SECTION = -4;  // start at y=-64

    /**
     * Extra margin (in sections) above and below the surface to generate.
     * Ensures caves near the surface, tree canopies, and hilly terrain are
     * captured.  Sections outside surface ± margin are skipped entirely.
     */
    private static final int SURFACE_MARGIN = 1;  // 1 section = 16 blocks

    /** Minimum block Y in the Minecraft world (floor of bedrock). */
    static final int MIN_WORLD_BLOCK_Y = Y_BASE_SECTION * 16;  // -64
    /** Maximum block Y in the Minecraft world (exclusive). */
    static final int MAX_WORLD_BLOCK_Y = (Y_BASE_SECTION + Y_SECTIONS) * 16;  // 192

    /**
     * Returns {@code true} if the entire world-section at the given level and
     * Y coordinate falls outside the Minecraft world Y range
     * [{@value #MIN_WORLD_BLOCK_Y}, {@value #MAX_WORLD_BLOCK_Y}).
     */
    static boolean isOutOfWorldY(int level, int wsY) {
        int blockYMin = WorldSectionCoord.worldSectionToBlockMin(wsY, level);
        // exclusive upper bound: one past the last block in this world section
        int blockYMaxExcl = WorldSectionCoord.worldSectionToBlockMax(wsY, level) + 1;
        return blockYMaxExcl <= MIN_WORLD_BLOCK_Y || blockYMin >= MAX_WORLD_BLOCK_Y;
    }

    /**
     * Generation radius (in sections).  All sections within this Manhattan
     * distance from the player are generated, closest first.
     */
    static final int GENERATION_RADIUS =
            Config.getInt("generationRadius", 32);

        /** Demand-driven queue poll sleep when there is no pending job. */
        private static final int DEMAND_IDLE_SLEEP_MS =
            Config.getInt("demandIdleSleepMs", 25);

            /** Throttle interval for demand-pipeline progress diagnostics. */
            private static final int DEMAND_PROGRESS_LOG_MS =
                Config.getInt("demandProgressLogMs", 5000);

            /** Periodic near-player seed to avoid local LOD starvation on zoom/FOV changes. */
            private static final int NEAR_SEED_INTERVAL_MS =
                Config.getInt("nearSeedIntervalMs", 1000);

            /** Avoid flooding demand queue with seed traffic when backlog already exists. */
            private static final int NEAR_SEED_MAX_QUEUE_DEPTH =
                Config.getInt("nearSeedMaxQueueDepth", 384);

            /** Interval between partial-fill scans (ms). */
            private static final int PARTIAL_FILL_SCAN_INTERVAL_MS =
                Config.getInt("partialFillScanIntervalMs", 5000);

            /** Minimum time between re-seeding the exact same (lod, wsX, wsY, wsZ). */
            private static final int NEAR_SEED_REENQUEUE_MS =
                Config.getInt("nearSeedReenqueueMs", 8000);

            /** Horizontal ring radius in world-sections for L4 seed requests. */
            private static final int NEAR_SEED_L4_RADIUS =
                Config.getInt("nearSeedL4Radius", 4);

            /**
             * Regular seeding uses the same world-section count per LOD by default.
             * Because finer levels have smaller world-sections, this naturally shrinks
             * the block radius at each finer level without creating giant gaps.
             */
            private static final int NEAR_SEED_L3_RADIUS =
                Math.max(1, Config.getInt("nearSeedL3Radius", NEAR_SEED_L4_RADIUS));

            private static final int NEAR_SEED_L2_RADIUS =
                Math.max(1, Config.getInt("nearSeedL2Radius", NEAR_SEED_L4_RADIUS));

            private static final int NEAR_SEED_L1_RADIUS =
                Math.max(1, Config.getInt("nearSeedL1Radius", NEAR_SEED_L4_RADIUS));

            private static final int NEAR_SEED_L0_RADIUS =
                Math.max(1, Config.getInt("nearSeedL0Radius", NEAR_SEED_L4_RADIUS));

    /**
     * Mysterious-Name fix: {@code +4} is the voxel-center offset.
     * One WorldSection is 32 blocks; L0 is 16 blocks. Centering the sample
     * avoids seam bias. Named to replace magic {@code +4} in yPosition calc.
     */
    private static final int VOXEL_CENTER_OFFSET = 4;

            private static final int[] L2_CLIMATE_CHANNELS = {
                RouterField.TEMPERATURE.ordinal(),
                RouterField.VEGETATION.ordinal(),
                RouterField.CONTINENTS.ordinal(),
                RouterField.EROSION.ordinal(),
                RouterField.DEPTH.ordinal(),
                RouterField.RIDGES.ordinal(),
                RouterField.FINAL_DENSITY.ordinal(),
            };

            private static final int[] L3_L4_CLIMATE_CHANNELS = {
                RouterField.TEMPERATURE.ordinal(),
                RouterField.VEGETATION.ordinal(),
                RouterField.CONTINENTS.ordinal(),
                RouterField.EROSION.ordinal(),
                RouterField.DEPTH.ordinal(),
                RouterField.RIDGES.ordinal(),
            };

    /**
     * Internal terrain-candidate seam — package-private, not a public SPI.
     * Result is a semantic {@link VoxelVolume} (extent 16 for L0 sections,
     * 32 for regions). Demand/scheduling variation stays internal to the
     * session; no public scheduler strategy is exposed.
     *
     * <p>Two production adapters exist: fallback (heightmap) and learned
     * (ONNX via {@link VoxelPredictionDecoder}). Tests may supply a double
     * via {@link #GenerationSession(VoxelVolumeWriter, TerrainCandidate)}.
     */
    interface TerrainCandidate {
        VoxelVolume produceSection(SectionPos pos, ColumnContext ctx);
        VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput);
    }

    /** Fallback candidate — stateless heightmap path. */
    private static final class FallbackCandidate implements TerrainCandidate {
        @Override
        public VoxelVolume produceSection(SectionPos pos, ColumnContext ctx) {
            return VoxelPredictionDecoder.fromFallback(
                    pos.y(), ctx.rawHm(), ctx.oceanFloorHm(), ctx.biomeIdx());
        }
        @Override
        public VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput) {
            throw new UnsupportedOperationException("Fallback candidate does not produce regions");
        }
    }

    /** Test-visible accessor for candidate seam verification. */
    TerrainCandidate candidateForTest() {
        if (candidateOverride != null) return candidateOverride;
        return selectCandidate();
    }

    private TerrainCandidate selectCandidate() {
        if (voxyModelRunner != null && noiseAccess != null && voxyModelRunner.vocabulary() != null) {
            return learnedCandidate;
        }
        return fallbackCandidate;
    }

    private final TerrainCandidate fallbackCandidate = new FallbackCandidate();
    private final TerrainCandidate learnedCandidate = new TerrainCandidate() {
        @Override
        public VoxelVolume produceSection(SectionPos pos, ColumnContext ctx) {
            // L0 heightmap path is shared between fallback and learned for the simple
            // produceSection seam (ColumnContext conditioning). True learned inference
            // for L0-L4 with 3D noise/climate and parentInput is exercised via the
            // demand pipeline: processDemandRequest -> voxyModelRunner.runL* ->
            // VoxelPredictionDecoder.fromOctreeArgmax, which writes regions (extent 32)
            // through VoxelVolumeWriter.writeRegion. Keeping produceSection on the
            // fallback decoder here preserves the internal seam shape without broadening
            // its conditioning inputs; the demand pipeline remains the ONNX entry point.
            return VoxelPredictionDecoder.fromFallback(
                    pos.y(), ctx.rawHm(), ctx.oceanFloorHm(), ctx.biomeIdx());
        }
        @Override
        public VoxelVolume produceRegion(Level level, SectionPos origin, long[] parentInput) {
            throw new UnsupportedOperationException("Learned region via demand pipeline");
        }
    };

    private final VoxelVolumeWriter writerOverride;
    private final TerrainCandidate candidateOverride;

    /** Production constructor. */
    public GenerationSession() {
        this(null, null);
    }

    /** Package-private test constructor with injected writer/candidate. */
    GenerationSession(VoxelVolumeWriter writerOverride, TerrainCandidate candidateOverride) {
        this.writerOverride = writerOverride;
        this.candidateOverride = candidateOverride;
    }

    /**
     * Compose helper for tests: route a section produce through the internal
     * candidate seam and write via the provided writer. No Voxy jar required.
     */
    WriteOutcome produceAndWriteSection(SectionPos pos, ColumnContext ctx, VoxelVolumeWriter writer) {
        TerrainCandidate c = candidateOverride != null ? candidateOverride : selectCandidate();
        VoxelVolume vol = c.produceSection(pos, ctx);
        if (vol.extent() != 16) {
            throw new IllegalArgumentException("Section candidate must return extent 16");
        }
        return writer.writeSection(pos, vol);
    }

    /**
     * Compose helper for tests: produce a region via candidate and write.
     */
    WriteOutcome produceAndWriteRegion(SectionPos origin, Level level, VoxelVolumeWriter writer) {
        TerrainCandidate c = candidateOverride != null ? candidateOverride : selectCandidate();
        VoxelVolume vol;
        try {
            vol = c.produceRegion(level, origin, null);
        } catch (UnsupportedOperationException e) {
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            b.fill(CanonicalRegistries.BLOCK_AIR, 0);
            vol = b.build();
        }
        if (vol.extent() != 32) {
            throw new IllegalArgumentException("Region candidate must return extent 32");
        }
        return writer.writeRegion(origin, level, vol);
    }

    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean positionReady = new AtomicBoolean(false);
    private volatile Thread workerThread;

    /**
     * Optional pre-loaded model future started via {@link #preloadModel()}
     * before the world fully joins (e.g. during "Loading terrain...").
     * {@code null} if pre-loading was not requested.
     */
    private volatile CompletableFuture<VoxyModelRunner> preloadFuture;

    /** New 5-model hierarchical runner (L4→L0). */
    private volatile VoxyModelRunner voxyModelRunner;

    /** Updated each tick from the client thread. */
    private volatile int playerSectionX;
    private volatile int playerSectionY;
    private volatile int playerSectionZ;

    /** Local near-player demand seed throttling state. */
    private volatile long lastNearSeedMs;
    private volatile int lastSeedPlayerSectionX = Integer.MIN_VALUE;
    private volatile int lastSeedPlayerSectionZ = Integer.MIN_VALUE;
    private final ConcurrentHashMap<Long, Long> recentSeededAtMs = new ConcurrentHashMap<>();

    /** Tracks which (section) positions we've already generated. Thread-safe. */
    private final Set<Long> generatedSections = ConcurrentHashMap.newKeySet();

    /** Cached column conditioning data — avoids redundant noise sampling. Thread-safe. */
    private final ConcurrentHashMap<Long, ColumnContext> columnContextCache =
            new ConcurrentHashMap<>();

    /** Active pipeline queue (set during pipeline execution). */
    private volatile LodGenerationQueue activeQueue;

    /** Stats: how many sections used real vs synthetic conditioning data. Thread-safe. */
    private final AtomicInteger realDataSections = new AtomicInteger();
    private final AtomicInteger syntheticDataSections = new AtomicInteger();
    private final AtomicInteger noiseAccessSections = new AtomicInteger();
    private final AtomicInteger skippedAirSections = new AtomicInteger();

    /** Server-side noise access — null if unavailable (dedicated server). */
    private volatile WorldNoiseAccess noiseAccess;

    /**
     * Hot-swappable sampler factory for the 15 NoiseRouter fields.
     * Reads {@code terrainBackend} config on each call to
     * {@link NoiseRouterSamplerFactory#getSampler()}.
     * Null until the world's {@link net.minecraft.world.gen.noise.NoiseConfig}
     * is available.
     */
    private volatile NoiseRouterSamplerFactory samplerFactory;

    /** Server reference for noise access (integrated server in singleplayer). */
    private volatile MinecraftServer server;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
    * Begin loading the Voxy ONNX model set on a background thread so they are
     * ready (or nearly so) by the time {@link #start} is called.
     *
     * <p>Safe to call multiple times — a second call is a no-op while a
     * previous future is still pending.  The future is consumed (and nulled)
     * by {@link #resolveModel}; calling {@code preloadModel()} again after
     * the session ends will restart pre-loading for the next join.
     *
     * <p>Intended to be wired to {@code ClientPlayConnectionEvents.INIT},
     * which fires as soon as the network connection is established — well
     * before the "Loading terrain..." screen appears.
     */
    public void preloadModel() {
        if (preloadFuture != null && !preloadFuture.isDone()) {
            HelloTerrainMod.LOGGER.debug("[LodGen] preloadModel() — already in progress, skipping");
            return;
        }
        if (!Config.useOnnxTerrain()) return;

        HelloTerrainMod.LOGGER.info("[LodGen] Pre-loading VoxyModelRunner in background...");
        preloadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                java.nio.file.Path modelDir = Config.modelDir();
                VoxyModelRunner runner = VoxyModelRunner.tryLoad(modelDir);
                if (runner != null) {
                    HelloTerrainMod.LOGGER.info("[LodGen] VoxyModelRunner pre-load complete");
                } else {
                    HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner pre-load: model set not found");
                }
                return runner;
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner pre-load failed (will retry in worker): {}",
                        e.getMessage());
                return null;
            }
        });
    }

    /**
     * Start the LOD generation service for a given world.
     *
     * @param world  the Minecraft world (client-side)
     * @param server the Minecraft server (integrated server for singleplayer;
     *               null for dedicated-server clients)
     */
    public void start(World world, MinecraftServer server) {
        if (running.getAndSet(true)) {
            HelloTerrainMod.LOGGER.warn("[LodGen] Service already running");
            return;
        }

        stopRequested.set(false);
        positionReady.set(false);
        generatedSections.clear();
        columnContextCache.clear();
        activeQueue = null;
        realDataSections.set(0);
        syntheticDataSections.set(0);
        noiseAccessSections.set(0);
        skippedAirSections.set(0);
        diagnosticCount.set(0);
        if (samplerFactory != null) {
            try { samplerFactory.close(); } catch (Exception ignored) {}
            samplerFactory = null;
        }
        noiseAccess = null;
        this.server = server;

        workerThread = new Thread(() -> runWorker(world), "LODiffusion-Gen");
        workerThread.setDaemon(true);
        workerThread.start();

        HelloTerrainMod.LOGGER.info("[LodGen] Service started");
    }

    /**
     * Stop the service and wait for the worker to finish.
     */
    public void stop() {
        if (!running.get()) return;

        stopRequested.set(true);

        // Signal population done so stage workers can drain and exit
        LodGenerationQueue q = activeQueue;
        if (q != null) q.signalPopulationDone();

        Thread t = workerThread;
        if (t != null) {
            t.interrupt();
            try {
                t.join(5000);
            } catch (InterruptedException ignored) {}
        }
        running.set(false);
        generatedSections.clear();
        columnContextCache.clear();
        if (q != null) q.clear();
        activeQueue = null;
        if (samplerFactory != null) {
            try { samplerFactory.close(); } catch (Exception ignored) {}
            samplerFactory = null;
        }
        if (voxyModelRunner != null) {
            try { voxyModelRunner.close(); } catch (Exception ignored) {}
            voxyModelRunner = null;
        }
        HelloTerrainMod.LOGGER.info("[LodGen] Service stopped");
    }

    /**
     * Update the player's current section position (called each client tick).
     */
    public void updatePlayerPosition(BlockPos pos) {
        this.playerSectionX = WorldSectionCoord.blockToSection(pos.getX());
        this.playerSectionY = WorldSectionCoord.blockToSection(pos.getY());
        this.playerSectionZ = WorldSectionCoord.blockToSection(pos.getZ());
        ShadowRouterJobQueue.updatePlayerSection(this.playerSectionX, this.playerSectionZ);

        seedNearPlayerDemandIfNeeded();

        if (positionReady.compareAndSet(false, true)) {
            HelloTerrainMod.LOGGER.info("[LodGen] Player position initialized: section ({}, {}, {})",
                    playerSectionX, playerSectionY, playerSectionZ);
        }
    }

    private void seedNearPlayerDemandIfNeeded() {
        if (!running.get()) {
            return;
        }

        int queueDepth = ShadowRouterJobQueue.size();
        if (queueDepth > NEAR_SEED_MAX_QUEUE_DEPTH) {
            return;
        }

        long now = System.currentTimeMillis();
        boolean movedSection = (playerSectionX != lastSeedPlayerSectionX)
                || (playerSectionZ != lastSeedPlayerSectionZ);
        if (!movedSection && (now - lastNearSeedMs) < NEAR_SEED_INTERVAL_MS) {
            return;
        }

        int enqueuedL4 = enqueueLocalSeedRing(4, NEAR_SEED_L4_RADIUS, now);
        int enqueuedL3 = enqueueLocalSeedRing(3, NEAR_SEED_L3_RADIUS, now);
        int enqueuedL2 = enqueueLocalSeedRing(2, NEAR_SEED_L2_RADIUS, now);
        int enqueuedL1 = enqueueLocalSeedRing(1, NEAR_SEED_L1_RADIUS, now);
        int enqueuedL0 = enqueueLocalSeedRing(0, NEAR_SEED_L0_RADIUS, now);
        int enqueued = enqueuedL4 + enqueuedL3 + enqueuedL2 + enqueuedL1 + enqueuedL0;

        lastSeedPlayerSectionX = playerSectionX;
        lastSeedPlayerSectionZ = playerSectionZ;
        lastNearSeedMs = now;

        if (recentSeededAtMs.size() > 8192) {
            long cutoff = now - (NEAR_SEED_REENQUEUE_MS * 3L);
            recentSeededAtMs.entrySet().removeIf(e -> e.getValue() < cutoff);
        }

        if (enqueued > 0 && (movedSection || (now % 5000L) < NEAR_SEED_INTERVAL_MS)) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][Seed] enqueued={} L4={} L3={} L2={} L1={} L0={} radii=[{},{},{},{},{}] queueDepth={} player=({}, {}, {})",
                    enqueued,
                    enqueuedL4, enqueuedL3, enqueuedL2, enqueuedL1, enqueuedL0,
                    NEAR_SEED_L4_RADIUS, NEAR_SEED_L3_RADIUS, NEAR_SEED_L2_RADIUS,
                    NEAR_SEED_L1_RADIUS, NEAR_SEED_L0_RADIUS,
                    queueDepth,
                    playerSectionX, playerSectionY, playerSectionZ);
        }
    }

    private int enqueueLocalSeedRing(int level, int radius, long nowMs) {
        int centerX = WorldSectionCoord.sectionToWorldSection(playerSectionX, level);
        int centerZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, level);
        int centerY = WorldSectionCoord.sectionToWorldSection(playerSectionY, level);

        int enqueued = 0;
        // At L4, only wsY=0 produces a valid yPosition for the model.
        // At finer levels, scan ±1 in Y.
        int minDy = level >= 4 ? 0 : -1;
        int maxDy = level >= 4 ? 0 : 1;
        for (int dy = minDy; dy <= maxDy; dy++) {
            int wsY = centerY + dy;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int wsX = centerX + dx;
                    int wsZ = centerZ + dz;
                    long seedKey = seedKey(level, wsX, wsY, wsZ);
                    Long last = recentSeededAtMs.get(seedKey);
                    if (last != null && (nowMs - last) < NEAR_SEED_REENQUEUE_MS) {
                        continue;
                    }

                    VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
                    req.lodLevel = level;
                    req.worldX = wsX;
                    req.worldY = wsY;
                    req.worldZ = wsZ;
                    ShadowRouterJobQueue.enqueue(req);
                    recentSeededAtMs.put(seedKey, nowMs);
                    enqueued++;
                }
            }
        }
        return enqueued;
    }

    private static long seedKey(int level, int wsX, int wsY, int wsZ) {
        long x = ((long) wsX) & 0x1FFFFFL;
        long y = ((long) wsY) & 0x1FFFFFL;
        long z = ((long) wsZ) & 0x1FFFFFL;
        long l = ((long) level) & 0x7L;
        return (l << 63) ^ (x << 42) ^ (y << 21) ^ z;
    }

    public boolean isRunning() {
        return running.get();
    }

    // ------------------------------------------------------------------ //
    //  Worker loop
    // ------------------------------------------------------------------ //

    private void runWorker(World world) {
        try {
            HelloTerrainMod.LOGGER.info("[LodGen] Worker starting — waiting for Voxy WorldEngine...");

            // Get Voxy world engine (may need to wait for Voxy to initialize)
            Object worldEngine = waitForWorldEngine(world);
            if (worldEngine == null) {
                HelloTerrainMod.LOGGER.error("[LodGen] Could not obtain Voxy WorldEngine — aborting");
                return;
            }

            HelloTerrainMod.LOGGER.info("[LodGen] Got Voxy WorldEngine — loading model...");

            // Try to bind to the integrated server's noise pipeline for real
            // heightmap / biome / router data at any coordinate.
            noiseAccess = WorldNoiseAccess.tryCreate(server, world);
            if (noiseAccess != null) {
                HelloTerrainMod.LOGGER.info("[LodGen] Using REAL noise access — " +
                        "no synthetic fallback needed");

                // Create the hot-swappable sampler factory (reads terrainBackend config).
                // Full world context enables UpstreamNoiseContext (heightmap + biome providers).
                samplerFactory = NoiseRouterSamplerFactory.create(
                        noiseAccess.serverWorld(),
                        noiseAccess.generator(),
                        noiseAccess.biomeSource(),
                        noiseAccess.noiseConfig());
                HelloTerrainMod.LOGGER.info("[LodGen] NoiseRouterSamplerFactory ready — backend={}",
                        samplerFactory.getSampler().backendName());
            } else {
                HelloTerrainMod.LOGGER.warn("[LodGen] Noise access unavailable — " +
                        "will fall back to heightmap-only generation");
            }

            // ── Primary path: demand-driven 5-model voxy runtime ────────
            voxyModelRunner = resolveVoxyModel();

            Object voxyMapper = VoxyCompat.getMapper(worldEngine);
            Registry<Biome> biomeRegistry =
                    world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);

                    if (voxyModelRunner != null && noiseAccess != null
                        && voxyModelRunner.vocabulary() != null) {
                int[] canonicalBiomeToVoxy = RealVoxyVolumeWriter.buildBiomeMap(voxyMapper, biomeRegistry);
                int[] canonicalBlockToVoxy = RealVoxyVolumeWriter.buildBlockMap(
                        voxyModelRunner.vocabulary(), voxyMapper);
                VoxyIdMaps idMaps = new VoxyIdMaps(canonicalBiomeToVoxy, canonicalBlockToVoxy);
                // Message-Chains fix: factory encapsulates VoxyCompat.getMapper chain.
                VoxelVolumeWriter writer = RealVoxyVolumeWriter.create(worldEngine, idMaps);

                waitForPlayerPosition();
                if (stopRequested.get()) return;

                HelloTerrainMod.LOGGER.info(
                    "[LodGen] VoxyModelRunner loaded — starting demand-driven generation "
                    + "(vocab={}, inFlight={})",
                    voxyModelRunner.vocabulary() != null
                        ? voxyModelRunner.vocabulary().size() : "none",
                    ShadowRouterJobQueue.inFlightSize());

                boolean produced = runDemandVoxyPipeline(world, writer);
                if (produced) return;

                HelloTerrainMod.LOGGER.warn(
                    "[LodGen] Demand-driven Voxy pipeline produced no terrain — "
                    + "falling back to heightmap generator");
                generatedSections.clear();
            }

            // ── Heightmap fallback path ──────────────────────────────────
                if (voxyModelRunner != null && noiseAccess == null) {
                HelloTerrainMod.LOGGER.warn(
                    "[LodGen] VoxyModelRunner loaded but noise access unavailable — " +
                        "falling back to heightmap generator");
                } else if (voxyModelRunner == null) {
                HelloTerrainMod.LOGGER.info(
                    "[LodGen] No Voxy model set found — using heightmap fallback generator");
            }

            HeightmapFallbackGenerator.FallbackBlockIds fallbackBlocks =
                    HeightmapFallbackGenerator.resolveBlockIds(voxyMapper);
            int[] fallbackBiomeMappings =
                    HeightmapFallbackGenerator.resolveBiomeMappings(voxyMapper, biomeRegistry);
            int[] fallbackBlockMap = RealVoxyVolumeWriter.buildFallbackBlockMap(fallbackBlocks);
            VoxyIdMaps fallbackMaps = new VoxyIdMaps(fallbackBiomeMappings, fallbackBlockMap);
            VoxelVolumeWriter fallbackWriter = RealVoxyVolumeWriter.create(worldEngine, fallbackMaps);

            waitForPlayerPosition();
            if (stopRequested.get()) return;

            HelloTerrainMod.LOGGER.info(
                    "[LodGen] Starting FALLBACK generation from player section ({}, {})",
                    playerSectionX, playerSectionZ);

            runFallbackPipeline(world, fallbackWriter);

        } catch (Exception e) {
            if (!stopRequested.get()) {
                HelloTerrainMod.LOGGER.error("[LodGen] Worker crashed: {}", e.getMessage(), e);
            }
        } finally {
            running.set(false);
            HelloTerrainMod.LOGGER.info("[LodGen] Worker exited");
        }
    }


        /**
     * Heightmap clip for native-resolution logits at a specific LOD level.
     *
     * <p>At LOD level {@code voxyLvl}, each voxel covers {@code 2^voxyLvl}
     * blocks along each axis.  A voxel is clipped to air if its <em>lowest</em>
     * block Y is at or above the surface height for the center of its XZ
     * footprint.
     *
     * @param logits    [1][N][D][D][D] where D = 16 >> voxyLvl
     * @param rawHm     [16][16] surface heightmap in [x][z] order
     * @param sectionY  L0 section Y coordinate
     * @param voxyLvl   Voxy storage level (1-4)
     */

    /**
     * Translate canonical biome IDs to Voxy biome IDs for a column.
     */

    /** Counter for detailed diagnostics (reset per LOD pass). Thread-safe. */
    private final AtomicInteger diagnosticCount = new AtomicInteger();

    // ------------------------------------------------------------------ //
    //  Per-column conditioning context
    // ------------------------------------------------------------------ //

    /**
     * Pre-sampled conditioning data for a single 16×16 column (chunk).
     * Sampled once per column and reused for all Y sections in that column.
     */
    record ColumnContext(
        float[][] rawHm,          // [16][16] surface heightmap in block Y
        int[][]   biomeIdx,       // [16][16] biome indices
        float[][] hp5,            // [5][16] height-planes (4×4, row-major)
        float[][] oceanFloorHm    // [16][16] ocean/river floor block-Y (may be null)
    ) {}

    /**
     * Build the conditioning context for a column, using the best available
     * data source.
     *
     * <p>Priority:
     * <ol>
     *   <li>{@link WorldNoiseAccess} — real heightmap + biome at any coordinate
     *       (no chunk needed)</li>
     *   <li>Loaded chunk — real heightmap + biome</li>
     *   <li>Synthetic — sine-wave heightmap + constant biome (last resort)</li>
     * </ol>
     */
    private ColumnContext buildColumnContext(World world, int sectionX, int sectionZ) {
        float[][] rawHm;
        int[][]   biomeIdx;
        float[][] hp5;
        float[][] oceanFloorHm = null;

        if (noiseAccess != null) {
            // *** PRIMARY PATH: Real noise data at any coordinate ***
            // Use HeightmapProvider from the upstream context when available;
            // fall back to AnchorSampler + WorldNoiseAccess for backward compat.
            if (samplerFactory != null) {
                try {
                    var ctx = samplerFactory.getUpstreamContext();
                    HeightmapData hmData = ctx.heightmapProvider()
                            .sampleHeightmaps(sectionX, sectionZ);
                    rawHm        = hmData.worldSurface();
                    oceanFloorHm = hmData.oceanFloor();

                        // Column biomes (2D, 16×16) for Voxy block writes.
                        // We sample at surface-Y at quart centres, expanding to block res.
                    String[][] biomeNames = noiseAccess.sampleBiomeNames(
                            sectionX, sectionZ, rawHm);
                    biomeIdx = new int[16][16];
                    for (int x = 0; x < 16; x++)
                        for (int z = 0; z < 16; z++)
                            biomeIdx[x][z] = BiomeMapping.toCanonicalId(biomeNames[x][z]);

                    hp5 = AnchorSampler.computeHeightPlanes(rawHm, oceanFloorHm);
                } catch (Exception e) {
                    // UpstreamNoiseContext unavailable (e.g. legacy 1-arg factory);
                    // fall back to original AnchorSampler path.
                    AnchorSampler.AnchorInputs anchor =
                            AnchorSampler.sampleFromNoise(noiseAccess, sectionX, sectionZ);
                    rawHm        = anchor.rawHm();
                    biomeIdx     = anchor.biomeIdx();
                    hp5          = anchor.heightPlanes5();
                    oceanFloorHm = anchor.oceanFloorHm();
                }
            } else {
                AnchorSampler.AnchorInputs anchor =
                        AnchorSampler.sampleFromNoise(noiseAccess, sectionX, sectionZ);
                rawHm        = anchor.rawHm();
                biomeIdx     = anchor.biomeIdx();
                hp5          = anchor.heightPlanes5();
                oceanFloorHm = anchor.oceanFloorHm();
            }
            noiseAccessSections.incrementAndGet();
            if (diagnosticCount.get() < 3) {
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] Using NOISE ACCESS data for column ({},{}) — " +
                        "real heightmap + biome",
                        sectionX, sectionZ);
            }
        } else {
            Chunk chunk = tryGetLoadedChunk(world, sectionX, sectionZ);
            if (chunk != null) {
                rawHm    = AnchorSampler.sampleHeightmap(chunk);
                biomeIdx = AnchorSampler.sampleBiomes(chunk);
                realDataSections.incrementAndGet();
                if (diagnosticCount.get() < 3) {
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Using REAL chunk data for column ({},{})",
                            sectionX, sectionZ);
                }
            } else {
                // Last resort — synthetic (should rarely happen with noise access)
                rawHm    = buildHeightmap(sectionX, sectionZ);
                biomeIdx = new int[16][16];
                for (int[] row : biomeIdx) java.util.Arrays.fill(row, 1);
                syntheticDataSections.incrementAndGet();
                if (diagnosticCount.get() < 3) {
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Using SYNTHETIC data for column ({},{}) — " +
                            "chunk not loaded, no noise access.",
                            sectionX, sectionZ);
                }
            }
            hp5 = AnchorSampler.computeHeightPlanes(rawHm, null);  // no ocean floor in synthetic path
        }

        return new ColumnContext(rawHm, biomeIdx, hp5, oceanFloorHm);
    }

    /**
     * Get or build column context with caching. Thread-safe.
     * Avoids redundant noise sampling when multiple Y sections share
     * the same column.
     */
    private ColumnContext getOrBuildColumnContext(World world, int sx, int sz) {
        long key = ((long) (sx & 0xFFFF) << 16) | (sz & 0xFFFFL);
        return columnContextCache.computeIfAbsent(key, k -> buildColumnContext(world, sx, sz));
    }

    // ------------------------------------------------------------------ //
    //  Coarsen & diagnostics
    // ------------------------------------------------------------------ //



    // ------------------------------------------------------------------ //
    //  Input building (position-dependent synthetic conditioning)
    // ------------------------------------------------------------------ //

    /** Sea level in block Y coordinates. */
    static final float SEA_LEVEL = 62f;

    /** Amplitude of terrain height variation (blocks). */
    static final float HEIGHT_AMPLITUDE = 24f;

    /**
     * Build a raw heightmap (in block Y coordinates) for a 16×16 section
     * column at the given section (x, z).  Uses deterministic multi-octave
     * sine/cosine noise so that adjacent sections share consistent terrain
     * shape.
     *
     * @return float[16][16] of raw block-Y heights (approx 40–90 range)
     */
    static float[][] buildHeightmap(int sectionX, int sectionZ) {
        float[][] hm = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                // Global block coordinates
                float bx = sectionX * 16f + lx;
                float bz = sectionZ * 16f + lz;

                // Multi-octave sine noise for gentle rolling hills
                float h = SEA_LEVEL;
                h += HEIGHT_AMPLITUDE * 0.50f * (float) Math.sin(bx * 0.005 + 1.7)
                                              * (float) Math.cos(bz * 0.007 + 0.3);
                h += HEIGHT_AMPLITUDE * 0.25f * (float) Math.sin(bx * 0.013 + 3.1)
                                              * (float) Math.sin(bz * 0.011 + 2.2);
                h += HEIGHT_AMPLITUDE * 0.12f * (float) Math.cos(bx * 0.037 + 0.9)
                                              * (float) Math.sin(bz * 0.029 + 4.1);
                // Clamp to valid MC range
                hm[lx][lz] = Math.max(0f, Math.min(320f, h));
            }
        }
        return hm;
    }

    // ------------------------------------------------------------------ //
    //  Section spiral ordering
    // ------------------------------------------------------------------ //

    /**
     * Build a list of (x, z) section coordinates ordered by distance
     * from the center, limited to the given radius.
     *
     * <p>Every pass covers a <em>full disc</em> — finer LOD passes
     * naturally overwrite our earlier coarser data.  Voxy-native sections
     * (from real chunk loading) are protected in {@link VoxySectionWriter}.
     *
     * @param distantFirst if true, sort furthest-from-center first so
     *                     distant horizon terrain appears immediately.
     */
    private List<int[]> buildSpiralSections(int centerX, int centerZ,
                                             int radius, boolean distantFirst) {
        List<int[]> sections = new ArrayList<>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                sections.add(new int[]{centerX + dx, centerZ + dz});
            }
        }
        // Sort by Manhattan distance — ascending (center-first) or
        // descending (horizon-first) depending on the pass.
        Comparator<int[]> cmp = Comparator.comparingInt(s ->
                Math.abs(s[0] - centerX) + Math.abs(s[1] - centerZ));
        sections.sort(distantFirst ? cmp.reversed() : cmp);
        return sections;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    /**
     * Try to get a loaded chunk from the world without blocking or
     * triggering generation.  Returns null if the chunk is not currently
     * loaded in the client (or server) chunk manager.
     *
     * <p>This is called from the worker thread; access is read-only so
     * it is safe on both client and server chunk managers.
     */
    private Chunk tryGetLoadedChunk(World world, int chunkX, int chunkZ) {
        try {
            return world.getChunkManager().getChunk(
                    chunkX, chunkZ, ChunkStatus.FULL, false);
        } catch (Exception e) {
            // Chunk manager threw — treat as not loaded
            return null;
        }
    }

    /**
     * True when a full vanilla chunk is currently loaded at this section X/Z.
     * Section coordinates on X/Z are 16-block aligned and match chunk coords.
     */
    private boolean isVanillaChunkLoaded(World world, int sectionX, int sectionZ) {
        return tryGetLoadedChunk(world, sectionX, sectionZ) != null;
    }

    /**
     * Pack section coordinates into a single long key for deduplication.
     * Each axis uses 20 bits, supporting ±524,287 sections.
     */
    static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFFL);
    }

    /**
     * Block until the client tick handler has supplied the player's real position.
     */
    private void waitForPlayerPosition() {
        for (int i = 0; i < 300; i++) {   // 30 seconds max
            if (stopRequested.get()) return;
            if (positionReady.get()) return;
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                return;
            }
        }
        HelloTerrainMod.LOGGER.warn("[LodGen] Timed out waiting for player position — using (0, 0)");
    }

    /**
     * Wait for the Voxy WorldEngine to become available (with timeout).
     */
    private Object waitForWorldEngine(World world) {
        for (int attempt = 0; attempt < 60; attempt++) {
            if (stopRequested.get()) return null;

            Object engine = VoxyCompat.getWorldEngine(world);
            if (engine != null) return engine;

            HelloTerrainMod.LOGGER.debug("[LodGen] Waiting for Voxy WorldEngine (attempt {})", attempt);
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                return null;
            }
        }
        return null;
    }

    // ------------------------------------------------------------------ //
    //  Heightmap fallback pipeline
    // ------------------------------------------------------------------ //

    /**
     * Ultra-fast fallback pipeline that generates terrain from heightmap data
     * alone, without any ONNX model.  Processes one column at a time on a
     * single thread — I/O ({@code insertUpdate}) is the bottleneck, not compute.
     *
     * <p>Reuses all existing infrastructure: spiral ordering, column context
     * caching, surface Y-range filtering, and deduplication.
     */
    private void runFallbackPipeline(World world, VoxelVolumeWriter writer) {
        Object worldEngine = (writer instanceof RealVoxyVolumeWriter r) ? r.getWorldEngine() : null;
        int totalSections = 0;
        int skippedAir = 0;
        int skippedExisting = 0;
        int columnsProcessed = 0;
        long startTime = System.currentTimeMillis();

        HelloTerrainMod.LOGGER.info(
                "[LodGen] Fallback pipeline starting — continuous mode, radius={}",
                GENERATION_RADIUS);

        // ── Continuous loop: re-spiral from player position each pass ───
        while (!stopRequested.get()) {
            int centerX = playerSectionX;
            int centerZ = playerSectionZ;

            List<int[]> columns = buildSpiralSections(centerX, centerZ,
                    GENERATION_RADIUS, false);

            boolean anyNew = false;

            for (int[] col : columns) {
                if (stopRequested.get()) break;

                int sx = col[0], sz = col[1];

                // If player moved far, restart spiral from new position
                int drift = Math.abs(playerSectionX - centerX)
                          + Math.abs(playerSectionZ - centerZ);
                if (drift > 2) break;

                // NOTE: We intentionally do NOT skip columns loaded by vanilla.
                // The per-section sectionExists() check below prevents overwriting
                // any sections Voxy has already ingested from real chunks, and
                // filling the rest avoids a visible gap between vanilla render
                // distance and the LOD terrain.

                // Get or build column context (cached across Y sections)
                ColumnContext ctx = getOrBuildColumnContext(world, sx, sz);


                // Compute Y range from surface heightmap
                float minH = Float.MAX_VALUE, maxH = -Float.MAX_VALUE;
                for (int lx = 0; lx < 16; lx++) {
                    for (int lz = 0; lz < 16; lz++) {
                        float h = ctx.rawHm()[lx][lz];
                        if (h < minH) minH = h;
                        if (h > maxH) maxH = h;
                    }
                }

                // Extend range down to cover water if surface is below sea level
                float effectiveMax = Math.max(maxH, HeightmapFallbackGenerator.SEA_LEVEL);

                int minSectionY = Math.max(
                        Math.floorDiv((int) Math.floor(minH), 16) - SURFACE_MARGIN,
                        Y_BASE_SECTION);
                int maxSectionY = Math.min(
                        Math.floorDiv((int) Math.ceil(effectiveMax), 16) + SURFACE_MARGIN,
                        Y_BASE_SECTION + Y_SECTIONS - 1);

                // Process all Y sections in this column
                for (int sy = minSectionY; sy <= maxSectionY; sy++) {
                    if (stopRequested.get()) break;

                    long key = sectionKey(sx, sy, sz);
                    if (generatedSections.contains(key)) continue;

                    // Never generate fallback terrain into sections backed by
                    // loaded vanilla chunks.
                    if (isVanillaChunkLoaded(world, sx, sz)) {
                        skippedExisting++;
                        generatedSections.add(key);
                        continue;
                    }

                    // Skip if Voxy already has real data for this section
                    if (VoxyCompat.sectionExists(worldEngine, sx, sy, sz)) {
                        skippedExisting++;
                        generatedSections.add(key);
                        continue;
                    }

                    SectionPos pos = new SectionPos(sx, sy, sz);
                    // Route through internal candidate seam (currently fallback for L0 sections)
                    VoxelVolume vol = selectCandidate().produceSection(pos, ctx);
                    WriteOutcome outcome;
                    try {
                        outcome = writer.writeSection(pos, vol);
                    } catch (VolumeUnavailableException e) {
                        HelloTerrainMod.LOGGER.warn("[LodGen] Fallback write unavailable: {}", e.getMessage());
                        generatedSections.add(key);
                        continue;
                    }
                    switch (outcome.status()) {
                        case WRITTEN -> {
                            totalSections++;
                            anyNew = true;
                            generatedSections.add(key);
                        }
                        case SKIPPED_AIR -> {
                            skippedAir++;
                            generatedSections.add(key);
                        }
                        case SKIPPED_EXISTS -> {
                            skippedExisting++;
                            generatedSections.add(key);
                        }
                        default -> throw new IllegalStateException("Unknown outcome: " + outcome.status());
                    }
                }

                columnsProcessed++;

                // Progress logging
                if (columnsProcessed % 100 == 0) {
                    long elapsed = System.currentTimeMillis() - startTime;
                    double sectionsPerSec = totalSections > 0
                            ? totalSections / (elapsed / 1000.0) : 0;
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen] Fallback progress: {} columns, {} sections written, "
                            + "{} air-skipped, {} existing-skipped ({} sec, {} sections/s)",
                            columnsProcessed, totalSections,
                            skippedAir, skippedExisting,
                            elapsed / 1000, (int) sectionsPerSec);
                }

                // Track skipped sections above/below surface
                int generatedRange = maxSectionY - minSectionY + 1;
                skippedAirSections.addAndGet(Y_SECTIONS - generatedRange);
            }

            // If nothing new was generated (all in radius already done), idle
            if (!anyNew && !stopRequested.get()) {
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    if (!stopRequested.get()) {
                        HelloTerrainMod.LOGGER.warn("[LodGen] Fallback interrupted");
                    }
                    break;
                }
            }

            // Periodically evict distant tracked sections to bound memory
            ChunkScheduler.evictDistantSections(
                    generatedSections, playerSectionX, playerSectionZ,
                    GENERATION_RADIUS * 10);
        }

        // Free cached column context
        columnContextCache.clear();

        long elapsed = System.currentTimeMillis() - startTime;
        HelloTerrainMod.LOGGER.info(
                "[LodGen] Fallback pipeline stopped — {} sections in {}.{}s "
                + "({} columns, {} air-skipped, {} existing-skipped)",
                totalSections, elapsed / 1000, elapsed % 1000,
                columnsProcessed, skippedAir, skippedExisting);
    }

    private enum DemandProcessResult {
        WRITTEN,
        SKIPPED,
        DEFERRED,
        FAILED,
    }

    private record Noise3dBundle(float[] noise3d, long[] biome3d, int[][] biomeForWrite) {}

    private record Climate2dBundle(float[] climate2d, long[] biome2d, int[][] biomeForWrite) {}

    /**
     * Demand-driven runtime pipeline consuming Voxy traversal requests from
     * ShadowRouterJobQueue and servicing them through VoxyModelRunner.
     */
    private boolean runDemandVoxyPipeline(World world, VoxelVolumeWriter writer) {
        Object worldEngine = (writer instanceof RealVoxyVolumeWriter r) ? r.getWorldEngine() : null;
        int dequeued = 0;
        int written = 0;
        int skipped = 0;
        int deferred = 0;
        int failed = 0;
        long startMs = System.currentTimeMillis();
        long lastProgressLogMs = startMs;
        long lastPartialFillScanMs = 0;

        // Initial partial-fill scan across ALL levels before entering the main loop.
        int initialFill = scanAndEnqueuePartialFillAllLevels(world, worldEngine, NEAR_SEED_L4_RADIUS);
        if (initialFill > 0) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][PartialFill] initial scan enqueued {} fill requests across all levels",
                    initialFill);
        }

        while (!stopRequested.get()) {
            long now = System.currentTimeMillis();
            if (!ShadowRouterJobQueue.hasPartialFillWork()
                    && now - lastPartialFillScanMs >= PARTIAL_FILL_SCAN_INTERVAL_MS) {
                int filled = scanAndEnqueuePartialFillAllLevels(world, worldEngine, NEAR_SEED_L4_RADIUS);
                lastPartialFillScanMs = now;
                if (filled > 0) {
                    HelloTerrainMod.LOGGER.info(
                            "[LodGen][PartialFill] rescan enqueued {} fill requests across all levels",
                            filled);
                }
            }

            VoxyRequestDecoder.VoxyNodeRequest req = ShadowRouterJobQueue.dequeueAny();
            if (req == null) {
                try {
                    Thread.sleep(DEMAND_IDLE_SLEEP_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                continue;
            }

            dequeued++;

            boolean requeued = false;
            try {
                DemandProcessResult result =
                        processDemandRequest(world, writer, req);
                switch (result) {
                    case WRITTEN -> written++;
                    case SKIPPED -> skipped++;
                    case DEFERRED -> {
                        deferred++;
                        ShadowRouterJobQueue.requeue(req);
                        requeued = true;
                    }
                    case FAILED -> failed++;
                }

                long progressNow = System.currentTimeMillis();
                if (written == 1
                        || dequeued == 1
                        || progressNow - lastProgressLogMs >= DEMAND_PROGRESS_LOG_MS) {
                    logDemandProgress(startMs, dequeued, written, skipped, deferred, failed);
                    lastProgressLogMs = progressNow;
                }
            } finally {
                if (!requeued) {
                    ShadowRouterJobQueue.markCompleted(req);
                }
            }
        }

        logDemandProgress(startMs, dequeued, written, skipped, deferred, failed);
        HelloTerrainMod.LOGGER.info(
                "[LodGen] Demand pipeline stopped: dequeued={} written={} skipped={} deferred={} failed={}",
                dequeued, written, skipped, deferred, failed);
        return written > 0;
    }

    /**
     * Scan ALL levels for partial WorldSections and enqueue fill requests.
     * L0 must be discovered from actual voxel occupancy; L1-L4 can use child masks.
     */
    private int scanAndEnqueuePartialFillAllLevels(World world, Object worldEngine, int radius) {
        int total = scanAndEnqueueL0PartialFill(world, worldEngine, NEAR_SEED_L0_RADIUS);
        for (int parentLevel = 1; parentLevel <= 4; parentLevel++) {
            total += scanAndEnqueuePartialFill(worldEngine, parentLevel, radius);
        }
        return total;
    }

    /**
     * L0 has no child-existence mask for its internal 16^3 octants, so hybrid
     * vanilla sections must be discovered by looking at actual voxel occupancy.
     */
    private int scanAndEnqueueL0PartialFill(World world, Object worldEngine, int radius) {
        int centerX = WorldSectionCoord.sectionToWorldSection(playerSectionX, 0);
        int centerZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, 0);
        int centerY = WorldSectionCoord.sectionToWorldSection(playerSectionY, 0);

        int enqueued = 0;
        for (int dy = -1; dy <= 1; dy++) {
            int wsY = centerY + dy;
            if (isOutOfWorldY(0, wsY)) {
                continue;
            }
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int wsX = centerX + dx;
                    int wsZ = centerZ + dz;

                    byte loadedMask = loadedChunkOctantMask(world, 0, wsX, wsY, wsZ);
                    if (loadedMask == 0 || loadedMask == (byte) 0xFF) {
                        continue;
                    }

                    // Non-vanilla octants: the ones LODiffusion is responsible for filling.
                    // getOccupiedOctantMask returns 0 when the section doesn't exist yet, so
                    // we also enqueue when Voxy hasn't created the section yet (which is correct
                    // — processDemandRequest will create it, writing only the non-vanilla octants).
                    byte nonVanillaMask = (byte)(~loadedMask & 0xFF);
                    byte occupiedMask = VoxyCompat.getOccupiedOctantMask(worldEngine, 0, wsX, wsY, wsZ);
                    if ((occupiedMask & nonVanillaMask) == nonVanillaMask) {
                        // All non-vanilla octants already have LODiffusion predictions.
                        continue;
                    }

                    VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
                    req.lodLevel = 0;
                    req.worldX = wsX;
                    req.worldY = wsY;
                    req.worldZ = wsZ;
                    req.isPartialFill = true;
                    ShadowRouterJobQueue.enqueue(req);
                    enqueued++;
                }
            }
        }
        return enqueued;
    }

    /**
     * Scan existing WorldSections at {@code parentLevel} around the player and
     * enqueue child-level generation requests for any octant that is missing.
     *
     * <p>This fills the "holes" in WorldSections that have partial child
     * existence (0 &lt; NEC &lt; 0xFF), such as L4 sections with some vanilla
     * L3 children.  The GPU descends into children when NEC bits are set; if
     * only some children exist, the missing octants render as blank terrain.
     *
     * @return number of child-level requests enqueued
     */
    private int scanAndEnqueuePartialFill(Object worldEngine,
                                           int parentLevel, int radius) {
        if (parentLevel < 1 || parentLevel > 4) return 0;

        int centerX = WorldSectionCoord.sectionToWorldSection(playerSectionX, parentLevel);
        int centerZ = WorldSectionCoord.sectionToWorldSection(playerSectionZ, parentLevel);
        int centerY = WorldSectionCoord.sectionToWorldSection(playerSectionY, parentLevel);

        int childLevel = parentLevel - 1;
        int enqueued = 0;

        // At L4, only wsY=0 is valid for the model.
        int minDy = parentLevel >= 4 ? 0 : -1;
        int maxDy = parentLevel >= 4 ? 0 : 1;

        for (int dy = minDy; dy <= maxDy; dy++) {
            int wsY = centerY + dy;
            for (int dz = -radius; dz <= radius; dz++) {
                for (int dx = -radius; dx <= radius; dx++) {
                    int wsX = centerX + dx;
                    int wsZ = centerZ + dz;

                    byte childMask = VoxyCompat.getChildExistenceMask(
                            worldEngine, parentLevel, wsX, wsY, wsZ);
                    if (childMask == (byte) 0xFF) {
                        continue; // fully populated — nothing to fill
                    }
                    // childMask==0 could mean truly empty OR a written parent
                    // with no children yet.  Only descend when the parent
                    // section actually exists in Voxy.
                    if (childMask == 0
                            && !VoxyCompat.sectionExistsAtLevel(
                                    worldEngine, parentLevel, wsX, wsY, wsZ)) {
                        continue;
                    }

                    // Partial section — enqueue generation for missing children.
                    for (int octant = 0; octant < 8; octant++) {
                        if ((childMask & (1 << octant)) != 0) {
                            continue; // this child already exists
                        }

                        int childWsX = (wsX << 1) + (octant & 1);
                        int childWsY = (wsY << 1) + ((octant >> 2) & 1);
                        int childWsZ = (wsZ << 1) + ((octant >> 1) & 1);

                        if (isOutOfWorldY(childLevel, childWsY)) {
                            continue;
                        }

                        VoxyRequestDecoder.VoxyNodeRequest req =
                                new VoxyRequestDecoder.VoxyNodeRequest();
                        req.lodLevel = childLevel;
                        req.worldX = childWsX;
                        req.worldY = childWsY;
                        req.worldZ = childWsZ;
                        req.isPartialFill = true;
                        ShadowRouterJobQueue.enqueue(req);
                        enqueued++;
                    }
                }
            }
        }
        return enqueued;
    }

    private void logDemandProgress(long startMs, int dequeued,
                                   int written, int skipped,
                                   int deferred, int failed) {
        long elapsedMs = Math.max(1L, System.currentTimeMillis() - startMs);
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Demand] dequeued={} written={} skipped={} deferred={} failed={} inFlight={} elapsed={}s",
                dequeued, written, skipped, deferred, failed,
                ShadowRouterJobQueue.inFlightSize(), elapsedMs / 1000);
    }

    /**
     * Orchestrator for one demand request. Reaches into {@link VoxyCompat}
     * / {@link VoxyWorldBinding} intentionally — this is the demand
     * pipeline coordinator, not Feature Envy. Fine-grained helpers
     * ({@code isRegionReady}, {@code computeOccupancy}) live on the
     * binding; this method sequences them. Mask/octant logic stays on
     * {@code VoxyWorldBinding} (see {@code computeChildExistenceMask},
     * {@code computeOccupiedOctantMask}); this method only decides
     * skip/defer/write.
     */
    private DemandProcessResult processDemandRequest(World world,
                                                     VoxelVolumeWriter writer,
                                                     VoxyRequestDecoder.VoxyNodeRequest req) {
        Object worldEngine = (writer instanceof RealVoxyVolumeWriter r) ? r.getWorldEngine() : null;
        if (req == null || voxyModelRunner == null || samplerFactory == null) {
            return DemandProcessResult.FAILED;
        }

        int level = req.lodLevel;
        int wsX = req.worldX;
        int wsY = req.worldY;
        int wsZ = req.worldZ;

        if (level < 0 || level > 4) return DemandProcessResult.SKIPPED;
        if (!voxyModelRunner.hasLevel(level)) return DemandProcessResult.SKIPPED;
        if (isOutOfWorldY(level, wsY)) return DemandProcessResult.SKIPPED;
        if (VoxyCompat.allOctantsPopulated(worldEngine, level, wsX, wsY, wsZ)) {
            return DemandProcessResult.SKIPPED;
        }

        // Dependency scheduling: finer levels require a parent section to exist first.
        long[] parentInput = null;
        if (level < 4) {
            int parentLevel = level + 1;
            int pX = wsX >> 1;
            int pY = wsY >> 1;
            int pZ = wsZ >> 1;

            if (!VoxyCompat.sectionExistsAtLevel(worldEngine, parentLevel, pX, pY, pZ)) {
                enqueueParentRequest(parentLevel, pX, pY, pZ);
                return DemandProcessResult.DEFERRED;
            }

            int octant = (wsX & 1) | ((wsZ & 1) << 1) | ((wsY & 1) << 2);
            parentInput = VoxyWorldBinding.readAndUpsampleParentOctant(
                    worldEngine, parentLevel, pX, pY, pZ, octant);
            if (parentInput == null) {
                enqueueParentRequest(parentLevel, pX, pY, pZ);
                return DemandProcessResult.DEFERRED;
            }
        }

        // +4 voxel-center offset (see VOXEL_CENTER_OFFSET): centers sample in 32^3 WorldSection
        int yPosition = (wsY << (level + 1)) + VOXEL_CENTER_OFFSET;
        VoxyModelRunner.LevelResult modelOut = null;
        int[][] biome32;
        long inferStartNs;

        if (level <= 1) {
            Noise3dBundle bundle = buildNoise3dInput(level, wsX, wsY, wsZ);
            if (bundle == null) return DemandProcessResult.FAILED;

            inferStartNs = System.nanoTime();
            logInferenceStart(level, wsX, wsY, wsZ, yPosition);
            try {
                modelOut = level == 1
                        ? voxyModelRunner.runL1(bundle.noise3d(), bundle.biome3d(), yPosition, parentInput)
                        : voxyModelRunner.runL0(bundle.noise3d(), bundle.biome3d(), yPosition, parentInput);
            } finally {
                logInferenceEnd(level, wsX, wsY, wsZ, yPosition, inferStartNs, modelOut != null);
            }
            biome32 = upsampleBiomeTo32(bundle.biomeForWrite());
        } else {
            Climate2dBundle bundle = buildClimate2dInput(level, wsX, wsY, wsZ);
            if (bundle == null) return DemandProcessResult.FAILED;

            inferStartNs = System.nanoTime();
            logInferenceStart(level, wsX, wsY, wsZ, yPosition);
            try {
                modelOut = switch (level) {
                    case 4 -> voxyModelRunner.runL4(bundle.climate2d(), bundle.biome2d(), yPosition);
                    case 3 -> voxyModelRunner.runL3(bundle.climate2d(), bundle.biome2d(), yPosition, parentInput);
                    case 2 -> voxyModelRunner.runL2(bundle.climate2d(), bundle.biome2d(), yPosition, parentInput);
                    default -> null;
                };
            } finally {
                logInferenceEnd(level, wsX, wsY, wsZ, yPosition, inferStartNs, modelOut != null);
            }
            biome32 = upsampleBiomeTo32(bundle.biomeForWrite());
        }

        if (modelOut == null) {
            return DemandProcessResult.FAILED;
        }

        // Do not skip writing the requested level when occupancy predicts no child expansion.
        // Voxy can still render this coarse level as fallback while finer children are absent.

        byte preserveMask = loadedChunkOctantMask(world, level, wsX, wsY, wsZ);
        if (preserveMask == (byte) 0xFF) {
            return DemandProcessResult.SKIPPED;
        }
        VoxelVolume vol = VoxelPredictionDecoder.fromOctreeArgmax(modelOut.blocks(), biome32);
        int sx0 = WorldSectionCoord.worldSectionToBlockMin(wsX, level) >> 4;
        int sy0 = WorldSectionCoord.worldSectionToBlockMin(wsY, level) >> 4;
        int sz0 = WorldSectionCoord.worldSectionToBlockMin(wsZ, level) >> 4;
        SectionPos origin = new SectionPos(sx0, sy0, sz0);
        Level lvl = Level.values()[level];
        WriteOutcome outcome;
        try {
            outcome = writer.writeRegion(origin, lvl, vol);
        } catch (VolumeUnavailableException e) {
            HelloTerrainMod.LOGGER.warn("[LodGen] Octree write unavailable: {}", e.getMessage());
            return DemandProcessResult.SKIPPED;
        }
        return outcome.status() == WriteOutcome.Status.WRITTEN ? DemandProcessResult.WRITTEN : DemandProcessResult.SKIPPED;
    }

    private void logInferenceStart(int level, int wsX, int wsY, int wsZ, int yPosition) {
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Infer] start lod={} ws=({}, {}, {}) yPos={}",
                level, wsX, wsY, wsZ, yPosition);
    }

    private void logInferenceEnd(int level, int wsX, int wsY, int wsZ,
                                 int yPosition, long startNs, boolean ok) {
        long elapsedMs = Math.max(0L, (System.nanoTime() - startNs) / 1_000_000L);
        HelloTerrainMod.LOGGER.info(
                "[LodGen][Infer] end lod={} ws=({}, {}, {}) yPos={} ok={} elapsedMs={}",
                level, wsX, wsY, wsZ, yPosition, ok, elapsedMs);
    }

    private Noise3dBundle buildNoise3dInput(int level, int wsX, int wsY, int wsZ) {
        int axisTiles = 1 << (level + 1); // L0=2, L1=4
        int xCells = axisTiles * 4;
        int yCells = axisTiles * 2;
        int zCells = axisTiles * 4;

        float[] noise = new float[RouterField.COUNT * xCells * yCells * zCells];
        long[] biome = new long[xCells * yCells * zCells];

        Map<Long, SectionNoiseData> noiseCache = new HashMap<>();
        Map<Long, int[][][]> biomeCache = new HashMap<>();

        NoiseRouterSampler sampler = samplerFactory.getSampler();
        BiomeProvider bp = null;
        try {
            bp = samplerFactory.getUpstreamContext().biomeProvider();
        } catch (Exception ignored) {
        }

        int sxBase = wsX << (level + 1);
        int syBase = wsY << (level + 1);
        int szBase = wsZ << (level + 1);

        for (int tx = 0; tx < axisTiles; tx++) {
            for (int ty = 0; ty < axisTiles; ty++) {
                for (int tz = 0; tz < axisTiles; tz++) {
                    int sx = sxBase + tx;
                    int sy = syBase + ty;
                    int sz = szBase + tz;
                    long key = sectionKey(sx, sy, sz);

                    SectionNoiseData snd = noiseCache.get(key);
                    if (snd == null) {
                        snd = sampler.sampleSection(sx, sy, sz);
                        noiseCache.put(key, snd);
                    }

                    int[][][] b = biomeCache.get(key);
                    if (b == null) {
                        b = new int[4][2][4];
                        if (bp != null) {
                            try {
                                b = bp.classifyBiomes(sx, sy, sz, snd);
                            } catch (Exception ignored) {
                            }
                        }
                        biomeCache.put(key, b);
                    }

                    for (int qx = 0; qx < 4; qx++) {
                        for (int qy = 0; qy < 2; qy++) {
                            for (int qz = 0; qz < 4; qz++) {
                                int gx = tx * 4 + qx;
                                int gy = ty * 2 + qy;
                                int gz = tz * 4 + qz;

                                int dstBiome = ((gx * yCells) + gy) * zCells + gz;
                                biome[dstBiome] = b[qx][qy][qz];

                                int srcOff = qx * 8 + qy * 4 + qz;
                                for (int c = 0; c < RouterField.COUNT; c++) {
                                    int src = c * SectionNoiseData.CELLS_PER_FIELD + srcOff;
                                    int dst = (((c * xCells) + gx) * yCells + gy) * zCells + gz;
                                    noise[dst] = snd.flat()[src];
                                }
                            }
                        }
                    }
                }
            }
        }

        int midY = yCells / 2;
        int[][] biome2d = new int[zCells][xCells];
        for (int x = 0; x < xCells; x++) {
            for (int z = 0; z < zCells; z++) {
                int idx = ((x * yCells) + midY) * zCells + z;
                biome2d[z][x] = (int) biome[idx];
            }
        }

        return new Noise3dBundle(noise, biome, biome2d);
    }

    private Climate2dBundle buildClimate2dInput(int level, int wsX, int wsY, int wsZ) {
        int[] channels = level == 2 ? L2_CLIMATE_CHANNELS : L3_L4_CLIMATE_CHANNELS;
        float[] climate = new float[channels.length * 8 * 8];
        long[] biome2d = new long[8 * 8];
        int[][] biomeForWrite = new int[8][8];

        int axisTiles = 1 << (level + 1);
        int nativeX = axisTiles * 4;
        int nativeY = axisTiles * 2;
        int nativeZ = axisTiles * 4;

        int sxBase = wsX << (level + 1);
        int syBase = wsY << (level + 1);
        int szBase = wsZ << (level + 1);
        int srcY = nativeY / 2;

        NoiseRouterSampler sampler = samplerFactory.getSampler();
        BiomeProvider bp = null;
        try {
            bp = samplerFactory.getUpstreamContext().biomeProvider();
        } catch (Exception ignored) {
        }

        Map<Long, SectionNoiseData> noiseCache = new HashMap<>();
        Map<Long, int[][][]> biomeCache = new HashMap<>();

        for (int oz = 0; oz < 8; oz++) {
            for (int ox = 0; ox < 8; ox++) {
                int srcX = (ox * (nativeX - 1)) / 7;
                int srcZ = (oz * (nativeZ - 1)) / 7;

                int tx = srcX / 4;
                int ty = srcY / 2;
                int tz = srcZ / 4;
                int qx = srcX % 4;
                int qy = srcY % 2;
                int qz = srcZ % 4;

                int sx = sxBase + tx;
                int sy = syBase + ty;
                int sz = szBase + tz;
                long key = sectionKey(sx, sy, sz);

                SectionNoiseData snd = noiseCache.get(key);
                if (snd == null) {
                    snd = sampler.sampleSection(sx, sy, sz);
                    noiseCache.put(key, snd);
                }

                int[][][] b = biomeCache.get(key);
                if (b == null) {
                    b = new int[4][2][4];
                    if (bp != null) {
                        try {
                            b = bp.classifyBiomes(sx, sy, sz, snd);
                        } catch (Exception ignored) {
                        }
                    }
                    biomeCache.put(key, b);
                }

                int off = qx * 8 + qy * 4 + qz;
                int spatial = oz * 8 + ox;
                for (int c = 0; c < channels.length; c++) {
                    climate[(c * 64) + spatial] =
                            snd.flat()[channels[c] * SectionNoiseData.CELLS_PER_FIELD + off];
                }

                int biomeId = b[qx][qy][qz];
                biome2d[spatial] = biomeId;
                biomeForWrite[oz][ox] = biomeId;
            }
        }

        return new Climate2dBundle(climate, biome2d, biomeForWrite);
    }

    private int[][] upsampleBiomeTo32(int[][] smallBiomeZx) {
        int srcZ = smallBiomeZx.length;
        int srcX = smallBiomeZx[0].length;
        int[][] out = new int[32][32];
        for (int z = 0; z < 32; z++) {
            int sz = (z * srcZ) / 32;
            for (int x = 0; x < 32; x++) {
                int sx = (x * srcX) / 32;
                out[z][x] = smallBiomeZx[sz][sx];
            }
        }
        return out;
    }


    private void enqueueParentRequest(int parentLevel, int pX, int pY, int pZ) {
        VoxyRequestDecoder.VoxyNodeRequest parentReq = new VoxyRequestDecoder.VoxyNodeRequest();
        parentReq.lodLevel = parentLevel;
        parentReq.worldX = pX;
        parentReq.worldY = pY;
        parentReq.worldZ = pZ;
        ShadowRouterJobQueue.enqueue(parentReq);
    }

    private byte loadedChunkOctantMask(World world, int level, int wsX, int wsY, int wsZ) {
        if (level < 0 || level > 4) {
            return 0;
        }

        if (level == 0) {
            return loadedVanillaL0OctantMask(world, wsX, wsY, wsZ);
        }

        int chunkSpanPerAxis = 1 << (level + 1);
        int chunkSpanPerOctant = 1 << level;
        int baseChunkX = wsX * chunkSpanPerAxis;
        int baseChunkZ = wsZ * chunkSpanPerAxis;
        byte mask = 0;

        // Preserve any octant whose X/Z footprint overlaps a currently loaded
        // vanilla chunk column. We intentionally ignore Y here: if vanilla owns
        // any part of the column, do not let coarse LOD writes replace it.
        for (int octant = 0; octant < 8; octant++) {
            int chunkStartX = baseChunkX + ((octant & 1) * chunkSpanPerOctant);
            int chunkStartZ = baseChunkZ + (((octant >> 1) & 1) * chunkSpanPerOctant);
            boolean loaded = false;
            for (int dx = 0; dx < chunkSpanPerOctant && !loaded; dx++) {
                for (int dz = 0; dz < chunkSpanPerOctant; dz++) {
                    if (tryGetLoadedChunk(world, chunkStartX + dx, chunkStartZ + dz) != null) {
                        loaded = true;
                        break;
                    }
                }
            }
            if (loaded) {
                mask |= (byte) (1 << octant);
            }
        }
        return mask;
    }

    /**
     * At L0, preserve only octants whose corresponding loaded vanilla 16^3 slice
     * actually contains non-air blocks.
     *
     * <p>Using mere chunk-column presence here creates a one-chunk moat around the
     * detailed terrain: any loaded chunk overlapping the 32^3 WorldSection causes
     * its whole 16x16 XZ octant to be skipped, even if that particular Y half is
     * empty or not rendered by vanilla. That produces the persistent 16-block gap
     * seen at the edge of the detailed region.
     */
    private byte loadedVanillaL0OctantMask(World world, int wsX, int wsY, int wsZ) {
        int baseChunkX = wsX << 1;
        int baseChunkZ = wsZ << 1;
        int baseBlockY = wsY << 5; // wsY * 32
        byte mask = 0;
        BlockPos.Mutable probePos = new BlockPos.Mutable();

        for (int octant = 0; octant < 8; octant++) {
            int chunkX = baseChunkX + (octant & 1);
            int chunkZ = baseChunkZ + ((octant >> 1) & 1);
            int blockYStart = baseBlockY + (((octant >> 2) & 1) << 4);

            Chunk chunk = tryGetLoadedChunk(world, chunkX, chunkZ);
            if (chunk == null) {
                continue;
            }

            boolean occupied = false;
            int blockXStart = chunkX << 4;
            int blockZStart = chunkZ << 4;
            for (int localY = 0; localY < 16 && !occupied; localY++) {
                int blockY = blockYStart + localY;
                for (int localZ = 0; localZ < 16 && !occupied; localZ++) {
                    int blockZ = blockZStart + localZ;
                    for (int localX = 0; localX < 16; localX++) {
                        int blockX = blockXStart + localX;
                        if (!chunk.getBlockState(probePos.set(blockX, blockY, blockZ)).isAir()) {
                            occupied = true;
                            break;
                        }
                    }
                }
            }

            if (occupied) {
                mask |= (byte) (1 << octant);
            }
        }

        return mask;
    }

    private VoxyModelRunner resolveVoxyModel() {
        CompletableFuture<VoxyModelRunner> future = preloadFuture;
        preloadFuture = null;  // consume so we don't reuse a stale instance

        if (future != null) {
            try {
                HelloTerrainMod.LOGGER.info("[LodGen] Waiting for pre-loaded VoxyModelRunner...");
                VoxyModelRunner preloaded = future.get(60, TimeUnit.SECONDS);
                if (preloaded != null) {
                    HelloTerrainMod.LOGGER.info("[LodGen] Using pre-loaded VoxyModelRunner");
                    return preloaded;
                }
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn(
                        "[LodGen] Pre-load future failed — falling back to synchronous load: {}",
                        e.getMessage());
            }
        }

        java.nio.file.Path modelDir = Config.modelDir();
        try {
            HelloTerrainMod.LOGGER.info("[LodGen] Loading VoxyModelRunner from {}...", modelDir);
            return VoxyModelRunner.tryLoad(modelDir);
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[LodGen] VoxyModelRunner load failed: {}", e.getMessage());
            return null;
        }
    }

}
