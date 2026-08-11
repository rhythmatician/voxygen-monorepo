package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
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
import com.rhythmatician.lodiffusion.onnx.OctreeModelRunner;

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
 * 3-model octree pipeline and pushes results into Voxy for distant rendering.
 *
 * <h3>Architecture — Octree Pipeline</h3>
 * <p>Sections are generated breadth-first using three ONNX models:
 * <ol>
 *   <li><b>L4 (init)</b> — root sections, no parent context. Parallelised
 *       across {@code STAGE_0_PARALLELISM} workers.</li>
 *   <li><b>L3-L1 (refine)</b> — one shared model called with a {@code level}
 *       input. Single worker per level.</li>
 *   <li><b>L0 (leaf)</b> — 32³ block-resolution sections. Results are written
 *       to Voxy as 8 native 16³ sections. Single worker.</li>
 * </ol>
 * Sections are prioritised by Manhattan distance from the player.
 * The pipeline is driven by an {@link OctreeQueue} with one priority-queue
 * per LOD level (0-4).  After inference produces an occupancy mask, occupied
 * octants spawn child tasks at the next finer level.
 */
public final class LodGenerationService {

    /** How many sections of Y range to generate (from y=-64 upward). */
    private static final int Y_SECTIONS = 16;  // y sections -4..11 → blocks -64..191
    private static final int Y_BASE_SECTION = -4;  // start at y=-64

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
     * Debug/testing override: when true, treat every voxel as 1×1 blocks
     * (full resolution).  Can be enabled with JVM property
     * `-Dlodiffusion.forceFullRes=true` to force block-per-voxel behaviour.
     */
    private static final boolean FORCE_FULL_RES = Boolean.getBoolean("lodiffusion.forceFullRes");

    /**
     * Number of parallel worker threads for stage 0 (init → LOD4).
     * Stage 0 has no parent dependency so sections can run concurrently.
     *
     * <p>Defaults to {@link Config#inferenceThreads()} (typically 2).
     * Keeping this small is important: with N concurrent L4 workers, the
     * first to finish (not necessarily the closest to the player) seeds
     * the single-threaded L3→L0 cascade.  N=2 keeps the priority
     * inversion to at most one adjacent root.
     */
    private static final int STAGE_0_PARALLELISM = Config.inferenceThreads();

    /**
     * Worker counts per octree level — inverted pyramid.  L4 has few tasks
     * (one per root) but each takes ~20s of column-context sampling, so a
     * single worker keeps things simple.  L0 has the most tasks and benefits
     * most from parallelism.
     *
     * <p>Index is the level: {@code WORKERS_PER_LEVEL[0]} = L0 workers, etc.
     */
    private static final int[] WORKERS_PER_LEVEL = {
        Math.max(Config.inferenceThreads(), 2),  // L0 — most tasks, most workers
        2,                                        // L1
        1,                                        // L2
        1,                                        // L3
        1                                         // L4 — few tasks, single worker
    };

    /**
     * Maximum number of sections to batch into a single ONNX inference call.
     * Dynamic-batch ONNX models amortize per-call overhead across the batch,
     * improving throughput significantly.  Empirically, 8–16 gives a good
     * balance between throughput and latency.
     *
     * <p>Set to 1 to disable batching (falls back to single-sample mode).
     */
    private static final int MAX_BATCH_SIZE = 8;

    /**
     * Batch size for L4 (root) workers.  Kept small (1) so the closest
     * L4 root produces children immediately instead of being blocked
     * behind context-building for 7 other tasks in the same batch.
     */
    private static final int L4_BATCH_SIZE = 1;

    /**
     * Generation radius (in sections).  All sections within this Manhattan
     * distance from the player are generated, closest first.
     */
    private static final int GENERATION_RADIUS =
            Config.getInt("generationRadius", 32);

    /**
     * Extra margin (in sections) beyond GENERATION_RADIUS before tasks
     * are cancelled.  Prevents thrashing when the player oscillates
     * near the boundary.
     */
    private static final int CANCEL_MARGIN = Config.getInt("cancelMargin", 4);



    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean stopRequested = new AtomicBoolean(false);
    private final AtomicBoolean positionReady = new AtomicBoolean(false);
    private volatile Thread workerThread;

    /**
     * Optional pre-loaded model future started via {@link #preloadModel()}
     * before the world fully joins (e.g. during "Loading terrain...").
     * {@code null} if pre-loading was not requested.
     */
    private volatile CompletableFuture<OctreeModelRunner> preloadFuture;

    /** Updated each tick from the client thread. */
    private volatile int playerSectionX;
    private volatile int playerSectionY;
    private volatile int playerSectionZ;

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

    /** Server reference for noise access (integrated server in singleplayer). */
    private volatile MinecraftServer server;

    // ------------------------------------------------------------------ //
    //  Lifecycle
    // ------------------------------------------------------------------ //

    /**
     * Begin loading the three ONNX models on a background thread so they are
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

        HelloTerrainMod.LOGGER.info("[LodGen] Pre-loading ONNX models in background...");
        preloadFuture = CompletableFuture.supplyAsync(() -> {
            try {
                java.nio.file.Path modelDir = Config.modelDir();
                OctreeModelRunner runner = OctreeModelRunner.loadAll(modelDir);
                HelloTerrainMod.LOGGER.info("[LodGen] ONNX pre-load complete");
                return runner;
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn("[LodGen] ONNX pre-load failed (will retry in worker): {}",
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
        HelloTerrainMod.LOGGER.info("[LodGen] Service stopped");
    }

    /**
     * Update the player's current section position (called each client tick).
     */
    public void updatePlayerPosition(BlockPos pos) {
        this.playerSectionX = WorldSectionCoord.blockToSection(pos.getX());
        this.playerSectionY = WorldSectionCoord.blockToSection(pos.getY());
        this.playerSectionZ = WorldSectionCoord.blockToSection(pos.getZ());
        if (positionReady.compareAndSet(false, true)) {
            HelloTerrainMod.LOGGER.info("[LodGen] Player position initialized: section ({}, {}, {})",
                    playerSectionX, playerSectionY, playerSectionZ);
        }
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
            } else {
                HelloTerrainMod.LOGGER.warn("[LodGen] Noise access unavailable — " +
                        "will fall back to synthetic heightmap + biome for distant sections");
            }

            // Load model — use the pre-loaded future if available, otherwise synchronous
            OctreeModelRunner model = resolveModel();
            if (model == null) {
                // ── Heightmap fallback path ──────────────────────────────
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] No ONNX models found — using heightmap fallback generator");

                Object voxyMapper = VoxyCompat.getMapper(worldEngine);
                Registry<Biome> biomeRegistry =
                        world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
                HeightmapFallbackGenerator.FallbackBlockIds fallbackBlocks =
                        HeightmapFallbackGenerator.resolveBlockIds(voxyMapper);
                int[] fallbackBiomeMappings =
                        HeightmapFallbackGenerator.resolveBiomeMappings(voxyMapper, biomeRegistry);

                waitForPlayerPosition();
                if (stopRequested.get()) return;

                HelloTerrainMod.LOGGER.info(
                        "[LodGen] Starting FALLBACK generation from player section ({}, {})",
                        playerSectionX, playerSectionZ);

                int[] fallbackBlockMap = RealVoxyVolumeWriter.buildFallbackBlockMap(fallbackBlocks);
                VoxelVolumeWriter fallbackWriter = new RealVoxyVolumeWriter(worldEngine, voxyMapper,
                        fallbackBiomeMappings, fallbackBlockMap);
                runFallbackPipeline(world, fallbackWriter);
                return;
            }

            // ── Normal ONNX pipeline path ────────────────────────────────
            // Build Voxy block mapper
            Object voxyMapper = VoxyCompat.getMapper(worldEngine);
            Registry<Biome> biomeRegistry =
                    world.getRegistryManager().getOrThrow(RegistryKeys.BIOME);
            int[] canonicalBiomeToVoxy = RealVoxyVolumeWriter.buildBiomeMap(voxyMapper, biomeRegistry);
            int[] canonicalBlockToVoxy = RealVoxyVolumeWriter.buildBlockMap(model.vocabulary(), voxyMapper);
            VoxelVolumeWriter writer = new RealVoxyVolumeWriter(worldEngine, voxyMapper, canonicalBiomeToVoxy, canonicalBlockToVoxy);

            HelloTerrainMod.LOGGER.info("[LodGen] Ready — waiting for player position " +
                    "(vocab={}, biomeVoxyId={})", model.vocabulary().size(), canonicalBiomeToVoxy[BiomeMapping.toCanonicalId("minecraft:plains")]);

            // Wait for the client tick to supply the real player position
            waitForPlayerPosition();
            if (stopRequested.get()) return;

            HelloTerrainMod.LOGGER.info("[LodGen] Starting generation from player section ({}, {}, {})",
                    playerSectionX, playerSectionY, playerSectionZ);

            // Run the octree pipeline
            runOctreePipeline(world, model, writer, canonicalBiomeToVoxy, canonicalBlockToVoxy);

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
        float[][] hp5,            // [5][256] height-planes (row-major)
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
            // sampleFromNoise() returns rawHm inside AnchorInputs — no second
            // sampleHeightmap() call needed (eliminates 256 duplicate getHeight() calls).
            AnchorSampler.AnchorInputs anchor =
                    AnchorSampler.sampleFromNoise(noiseAccess, sectionX, sectionZ);
            rawHm        = anchor.rawHm();
            biomeIdx     = anchor.biomeIdx();
            hp5          = anchor.heightPlanes5();
            oceanFloorHm = anchor.oceanFloorHm();
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
    private static final float SEA_LEVEL = 62f;

    /** Amplitude of terrain height variation (blocks). */
    private static final float HEIGHT_AMPLITUDE = 24f;

    /**
     * Build a raw heightmap (in block Y coordinates) for a 16×16 section
     * column at the given section (x, z).  Uses deterministic multi-octave
     * sine/cosine noise so that adjacent sections share consistent terrain
     * shape.
     *
     * @return float[16][16] of raw block-Y heights (approx 40–90 range)
     */
    private float[][] buildHeightmap(int sectionX, int sectionZ) {
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
     * (from real chunk loading) are protected in the semantic writer (insert-only guard).
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

                    VoxelVolume vol = VoxelPredictionDecoder.fromFallback(sy, ctx.rawHm(), ctx.oceanFloorHm(), ctx.biomeIdx());
                                SectionPos pos = new SectionPos(sx, sy, sz);
                                WriteOutcome outcome;
                                try {
                                    outcome = writer.writeSection(pos, vol);
                                } catch (VolumeUnavailableException e) {
                                    HelloTerrainMod.LOGGER.warn("[LodGen] writer unavailable {}: {}", pos, e.getMessage());
                                    skippedExisting++;
                                    generatedSections.add(key);
                                    continue;
                                }
                                switch (outcome.status()) {
                                    case WRITTEN -> { totalSections++; anyNew = true; }
                                    case SKIPPED_AIR -> skippedAir++;
                                    case SKIPPED_EXISTS -> skippedExisting++;
                                }

                    generatedSections.add(key);
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

    /**
     * Resolve the ONNX model runner, preferring the pre-loaded future.
     *
     * <p>If {@link #preloadFuture} is set, block on it (it may already be
     * done by the time we get here).  On any error falls back to a fresh
     * synchronous load.
     *
     * @return the loaded runner, or {@code null} if models are absent / failed
     */
    private OctreeModelRunner resolveModel() {
        CompletableFuture<OctreeModelRunner> future = preloadFuture;
        preloadFuture = null;  // consume so we don't reuse a stale instance

        if (future != null) {
            try {
                HelloTerrainMod.LOGGER.info("[LodGen] Waiting for pre-loaded ONNX model...");
                OctreeModelRunner preloaded = future.get(60, TimeUnit.SECONDS);
                if (preloaded != null) {
                    HelloTerrainMod.LOGGER.info("[LodGen] Using pre-loaded ONNX model");
                    return preloaded;
                }
            } catch (Exception e) {
                HelloTerrainMod.LOGGER.warn(
                        "[LodGen] Pre-load future failed — falling back to synchronous load: {}",
                        e.getMessage());
            }
        }

        return loadModel();
    }

    /**
     * Load all three octree ONNX models from the configured model directory.
     *
     * @return the loaded runner, or {@code null} if models are absent / failed to load
     */
    private OctreeModelRunner loadModel() {
        try {
            java.nio.file.Path modelDir = Config.modelDir();
            HelloTerrainMod.LOGGER.info("[LodGen] Loading octree models from {}...", modelDir);
            return OctreeModelRunner.loadAll(modelDir);
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.error("[LodGen] Model load failed: {}", e.getMessage(), e);
            return null;
        }
    }

    // ------------------------------------------------------------------ //
    //  Octree pipeline
    // ------------------------------------------------------------------ //

    /**
     * Build an {@link OctreeColumnContext} for an octree section at
     * {@code level} with WorldSection coordinates {@code (wsX, wsY, wsZ)}.
     *
     * <p>Samples heightmap and biome data at 32x32 grid points whose
     * sub-voxel step-size equals {@code 1 << level} blocks.  Uses
     * {@link WorldNoiseAccess} if available, otherwise falls back to
     * the synthetic sine-wave heightmap.
     */
    private OctreeColumnContext buildOctreeColumnContext(int level, int wsX, int wsY, int wsZ) {
        // Allow forcing full-resolution sampling (1 block per sample)
        int blockStep = FORCE_FULL_RES ? 1 : (1 << level); // blocks per octree voxel
        float[][] rawHm = new float[32][32];
        int[][]   biomeIdx = new int[32][32];

        // Per-chunk cache for this call (chunk key -> float[2][16][16])
        HashMap<Long, float[][][]> chunkCache     = new HashMap<>();
        HashMap<Long, String[][]>  biomeNameCache = new HashMap<>();

        for (int cx = 0; cx < 32; cx++) {
            for (int cz = 0; cz < 32; cz++) {
                // Block coordinate of cell center
                int bx = wsX * 32 * blockStep + cx * blockStep + blockStep / 2;
                int bz = wsZ * 32 * blockStep + cz * blockStep + blockStep / 2;

                int chunkX = bx >> 4;
                int chunkZ = bz >> 4;
                int lx = bx & 15;
                int lz = bz & 15;

                long chunkKey = (long) chunkX << 32 | (chunkZ & 0xFFFFFFFFL);

                if (noiseAccess != null) {
                    float[][][] heights = chunkCache.computeIfAbsent(chunkKey,
                            k -> noiseAccess.sampleBothHeightmaps(chunkX, chunkZ));
                    rawHm[cz][cx] = heights[0][lx][lz];

                    String[][] names = biomeNameCache.computeIfAbsent(chunkKey,
                            k -> noiseAccess.sampleBiomeNames(chunkX, chunkZ, heights[0]));
                    biomeIdx[cz][cx] = BiomeMapping.toCanonicalId(names[lx][lz]);
                } else {
                    // Synthetic fallback (sine-wave height)
                    float h = buildSingleHeight(bx, bz);
                    rawHm[cz][cx] = h;
                    biomeIdx[cz][cx] = 1; // plains default
                }
            }
        }

        // Compute 5-plane heightmap from 32x32 rawHm (mirrors AnchorSampler.computeHeightPlanes)
        float[][][] heightmap5 = computeOctreeHeightPlanes(rawHm);

        return new OctreeColumnContext(heightmap5, biomeIdx, rawHm);
    }

    /**
     * Compute the 5-plane height tensor for a 32x32 heightmap.
     * Mirrors {@code AnchorSampler.computeHeightPlanes} extended to 32x32.
     */
    private static float[][][] computeOctreeHeightPlanes(float[][] rawHm) {
        final float HEIGHT_RANGE = 320f;
        final float SEA_LEVEL_PLANE    = 62f;
        float[][][] planes = new float[5][32][32];

        float[][] surfNorm = new float[32][32];
        float[][] slopeX   = new float[32][32];
        float[][] slopeZ   = new float[32][32];

        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                float h = rawHm[r][c];
                surfNorm[r][c] = h / HEIGHT_RANGE;
                planes[0][r][c] = surfNorm[r][c];                         // surface
                planes[1][r][c] = Math.min(h, SEA_LEVEL_PLANE) / HEIGHT_RANGE; // ocean_floor approx
            }
        }
        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                if (c == 0)     slopeX[r][c] = surfNorm[r][1] - surfNorm[r][0];
                else if (c==31) slopeX[r][c] = surfNorm[r][31] - surfNorm[r][30];
                else            slopeX[r][c] = (surfNorm[r][c+1] - surfNorm[r][c-1]) / 2f;
                planes[2][r][c] = slopeX[r][c];

                if (r == 0)     slopeZ[r][c] = surfNorm[1][c] - surfNorm[0][c];
                else if (r==31) slopeZ[r][c] = surfNorm[31][c] - surfNorm[30][c];
                else            slopeZ[r][c] = (surfNorm[r+1][c] - surfNorm[r-1][c]) / 2f;
                planes[3][r][c] = slopeZ[r][c];
            }
        }
        for (int r = 0; r < 32; r++) {
            for (int c = 0; c < 32; c++) {
                float dsx = (c == 0)  ? slopeX[r][1] - slopeX[r][0]
                          : (c == 31) ? slopeX[r][31] - slopeX[r][30]
                          : (slopeX[r][c+1] - slopeX[r][c-1]) / 2f;
                float dsz = (r == 0)  ? slopeZ[1][c] - slopeZ[0][c]
                          : (r == 31) ? slopeZ[31][c] - slopeZ[30][c]
                          : (slopeZ[r+1][c] - slopeZ[r-1][c]) / 2f;
                planes[4][r][c] = dsx + dsz;
            }
        }
        return planes;
    }

    /** Synthetic single-block height value. */
    private float buildSingleHeight(int bx, int bz) {
        float h = SEA_LEVEL;
        h += HEIGHT_AMPLITUDE * 0.50f * (float) Math.sin(bx * 0.005 + 1.7)
                                      * (float) Math.cos(bz * 0.007 + 0.3);
        h += HEIGHT_AMPLITUDE * 0.25f * (float) Math.sin(bx * 0.013 + 3.1)
                                      * (float) Math.sin(bz * 0.011 + 2.2);
        h += HEIGHT_AMPLITUDE * 0.12f * (float) Math.cos(bx * 0.037 + 0.9)
                                      * (float) Math.sin(bz * 0.029 + 4.1);
        return Math.max(0f, Math.min(320f, h));
    }

    /**
     * Run the continuous octree pipeline: start level workers, populate L4 roots
     * from the player position, and drive the scheduler until stop is requested.
     */
    private void runOctreePipeline(World world, OctreeModelRunner model,
            VoxelVolumeWriter writer, int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        OctreeQueue queue = new OctreeQueue();
        this.activeQueue = null; // octree queue is separate type; kept for fallback compat

        // Inverted pyramid: more workers at finer levels where there are
        // more tasks.  WORKERS_PER_LEVEL[lvl] gives the count per level.
        int numWorkers = 0;
        for (int wpl : WORKERS_PER_LEVEL) numWorkers += wpl;

        Thread[] workers = new Thread[numWorkers];
        @SuppressWarnings("unchecked")
        java.util.concurrent.atomic.AtomicInteger[] activeCounters =
                new java.util.concurrent.atomic.AtomicInteger[5];
        for (int lvl = 0; lvl < 5; lvl++) {
            activeCounters[lvl] =
                    new java.util.concurrent.atomic.AtomicInteger(WORKERS_PER_LEVEL[lvl]);
        }

        int wIdx = 0;
        for (int lvl = 4; lvl >= 0; lvl--) {
            for (int i = 0; i < WORKERS_PER_LEVEL[lvl]; i++) {
                final int level = lvl;
                final int idx = i;
                workers[wIdx] = new Thread(() -> {
                    try {
                        runOctreeLevelWorker(level, queue, model, writer, canonicalBiomeToVoxy, canonicalBlockToVoxy);
                    } finally {
                        if (activeCounters[level].decrementAndGet() == 0) {
                            queue.signalLevelComplete(level);
                        }
                    }
                }, "LODiffusion-L" + lvl
                        + (WORKERS_PER_LEVEL[lvl] > 1 ? "-" + idx : ""));
                workers[wIdx].setDaemon(true);
                wIdx++;
            }
        }

        for (Thread w : workers) w.start();

        HelloTerrainMod.LOGGER.info(
                "[LodGen] Octree pipeline starting — workers L4={} L3={} L2={} L1={} L0={}, "
                + "batch L4={} rest={}, radius={}, total threads={}",
                WORKERS_PER_LEVEL[4], WORKERS_PER_LEVEL[3], WORKERS_PER_LEVEL[2],
                WORKERS_PER_LEVEL[1], WORKERS_PER_LEVEL[0],
                L4_BATCH_SIZE, MAX_BATCH_SIZE, GENERATION_RADIUS, numWorkers);

        // Root-population loop
        waitForPlayerPosition();
        if (stopRequested.get()) {
            queue.signalPopulationDone();
            return;
        }

        // Log diagnostic trace so we can verify the coordinate chain from logs
        {
            int bx = playerSectionX << 4;  // approximate block X from section
            int by = playerSectionY << 4;
            int bz = playerSectionZ << 4;
            HelloTerrainMod.LOGGER.info("[LodGen] Coordinate trace:\n{}",
                    WorldSectionCoord.traceBlock(bx, by, bz));
        }

        // L4 sections cover 32 L0-sections per axis -> L4 radius = L0_radius / 32
        int l4Radius = Math.max(1, GENERATION_RADIUS / 32);

        int lastCenterX = Integer.MIN_VALUE;
        int lastCenterZ = Integer.MIN_VALUE;

        while (!stopRequested.get()) {
            int px = playerSectionX;
            int py = playerSectionY;
            int pz = playerSectionZ;
            int l4Cx = WorldSectionCoord.sectionToWorldSection(px, 4);
            int l4Cy = WorldSectionCoord.sectionToWorldSection(py, 4);
            int l4Cz = WorldSectionCoord.sectionToWorldSection(pz, 4);

            if (l4Cx != lastCenterX || l4Cz != lastCenterZ) {
                lastCenterX = l4Cx;
                lastCenterZ = l4Cz;

                // Collect all roots first, then sort by priority so the
                // center (closest) roots are enqueued first.  Workers are
                // already blocking on poll(), so the first roots enqueued
                // are grabbed immediately — if we enqueue in loop order
                // (corners first), workers waste 20s on distant L4 roots
                // before the center roots are even offered to the queue.
                List<OctreeTask> roots = new ArrayList<>();
                int wyMin = WorldSectionCoord.sectionToWorldSection(Y_BASE_SECTION, 4);
                int wyMax = WorldSectionCoord.sectionToWorldSection(Y_BASE_SECTION + Y_SECTIONS - 1, 4);
                for (int dx = -l4Radius; dx <= l4Radius; dx++) {
                    for (int dz = -l4Radius; dz <= l4Radius; dz++) {
                        int wx = l4Cx + dx;
                        int wz = l4Cz + dz;

                        // ── Vanilla-border detection ───────────────────
                        // Each L4 section covers 32 chunks per axis.
                        // Check 1 chunk just outside each of the 4 faces
                        // (midpoint of the face) for a loaded vanilla chunk.
                        // Cost: 4 hash-map lookups per L4 root per 500 ms.
                        int cMinX = wx * 32;  // west-edge chunk X
                        int cMinZ = wz * 32;  // north-edge chunk Z
                        int cMidX = cMinX + 15;
                        int cMidZ = cMinZ + 15;
                        boolean nearVanilla =
                                tryGetLoadedChunk(world, cMinX - 1,  cMidZ    ) != null
                             || tryGetLoadedChunk(world, cMinX + 32, cMidZ    ) != null
                             || tryGetLoadedChunk(world, cMidX,      cMinZ - 1) != null
                             || tryGetLoadedChunk(world, cMidX,      cMinZ + 32) != null;

                        for (int wy = wyMin; wy <= wyMax; wy++) {
                            int priority = Math.abs(dx) + Math.abs(dz)
                                         + Math.abs(wy - l4Cy);
                            OctreeTask root = new OctreeTask(4, wx, wy, wz, -1, priority);
                            root.nearVanilla = nearVanilla;
                            roots.add(root);
                        }
                    }
                }
                roots.sort(null); // natural ordering: lowest priority first
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] Enqueuing {} L4 roots — first: ({},{},{}) pri={}, last: ({},{},{}) pri={}",
                        roots.size(),
                        roots.get(0).wsX, roots.get(0).wsY, roots.get(0).wsZ,
                        roots.get(0).priority,
                        roots.get(roots.size() - 1).wsX,
                        roots.get(roots.size() - 1).wsY,
                        roots.get(roots.size() - 1).wsZ,
                        roots.get(roots.size() - 1).priority);
                for (OctreeTask root : roots) {
                    if (stopRequested.get()) break;
                    queue.enqueueRoot(root);
                }
            }

            // Re-prioritise all pending tasks based on current player position
            // so the closest tasks are always processed first
            queue.reprioritise(px, pz);

            // Cancel tasks beyond radius
            queue.cancelBeyondRadius(px, pz, GENERATION_RADIUS + CANCEL_MARGIN);

            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                break;
            }
        }

        queue.signalPopulationDone();

        // Wait for all workers to drain
        for (Thread w : workers) {
            try {
                w.join(10_000);
            } catch (InterruptedException ignored) {
                break;
            }
        }

        HelloTerrainMod.LOGGER.info(
                "[LodGen] Octree pipeline complete — {} done, {} failed",
                queue.completedCount(), queue.failedCount());
    }

    /**
     * Worker loop for a single octree LOD level.  Drains batches of tasks from
     * the level queue, runs BATCHED inference, writes L0 results to Voxy, and
     * spawns children via occupancy masks.
     *
     * <p>Batch inference amortizes per-call ONNX overhead and enables better
     * CPU vectorization, giving 2-4× throughput improvement over single-sample
     * calls.
     */
    private void runOctreeLevelWorker(int level, OctreeQueue queue,
                                       OctreeModelRunner model,
                                       VoxelVolumeWriter writer,
                                       int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        String tName = Thread.currentThread().getName();
        HelloTerrainMod.LOGGER.info("[LodGen] {} starting", tName);
        int processed = 0;

        // Column-context cache: key = (wsX, wsZ) packed as long.
        // Column context depends only on level + XZ (not Y), so tasks at the
        // same level sharing XZ coordinates can reuse the same context.
        Map<Long, OctreeColumnContext> ctxCache = new HashMap<>();

        while (!stopRequested.get()) {
            List<OctreeTask> batch;
            try {
                int batchSize = (level == 4) ? L4_BATCH_SIZE : MAX_BATCH_SIZE;
                batch = queue.drainLevel(level, batchSize, 200, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                break;
            }

            if (batch.isEmpty()) {
                // Use tryFinalDrain to atomically check exit conditions and
                // drain remaining tasks, preventing a race with reprioritise().
                List<OctreeTask> finalBatch = queue.tryFinalDrain(level);
                if (finalBatch != null) {
                    if (finalBatch.isEmpty()) break;
                    batch = finalBatch;
                } else {
                    continue;
                }
            }

            List<OctreeTask> claimed = new ArrayList<>(batch.size());
            for (OctreeTask t : batch) {
                if (t.claimForProcessing()) claimed.add(t);
            }
            if (claimed.isEmpty()) continue;

            // Skip tasks whose Y range is entirely outside the world
            claimed.removeIf(t -> {
                if (isOutOfWorldY(t.level, t.wsY)) {
                    t.markReady();
                    queue.markCompleted();
                    queue.propagateAdjacency(t);
                    return true;
                }
                return false;
            });
            if (claimed.isEmpty()) continue;

            // ── Pre-inference fully-claimed check ────────────────────
            // Skip tasks only when Voxy has fully populated ALL 8 octants
            // (nonEmptyChildren == 0xFF).  Partially populated sections still
            // need model predictions for their empty octants.
            // writeFullWorldSection / writeAtLevel handle per-octant merging
            // and will skip any sub-cubes Voxy already owns.
            {
                claimed.removeIf(t -> {
                    if (writer.isRegionFullyPopulated(Level.values()[t.level], t.wsX, t.wsY, t.wsZ)) {
                        t.markReady();
                        queue.markCompleted();
                        queue.propagateAdjacency(t);
                        return true;
                    }
                    return false;
                });
                if (claimed.isEmpty()) continue;
            }

            // ── Save-queue backpressure ──────────────────────────────
            // If Voxy's SectionSavingService is backed up (≥1200 pending
            // tasks — its internal rate-limiter threshold), pause briefly
            // so we don't pile up faster than Voxy can persist.
            {
                int depth = writer.saveQueueDepth();
                if (depth >= 1200) {
                    HelloTerrainMod.LOGGER.warn(
                            "[LodGen] {} save-queue backpressure: {} pending — throttling 200 ms",
                            tName, depth);
                    try {
                        Thread.sleep(200);
                    } catch (InterruptedException ie) {
                        break;
                    }
                }
            }

            // ── Ensure column context is set for every task ──────────
            for (OctreeTask task : claimed) {
                if (task.columnContext == null) {
                    long xzKey = ((long) task.wsX << 32) | (task.wsZ & 0xFFFFFFFFL);
                    OctreeColumnContext cached = ctxCache.get(xzKey);
                    if (cached != null) {
                        task.columnContext = cached;
                    } else {
                        OctreeColumnContext ctx = buildOctreeColumnContext(
                                task.level, task.wsX, task.wsY, task.wsZ);
                        task.columnContext = ctx;
                        ctxCache.put(xzKey, ctx);
                    }
                }
            }

            // ── Pre-inference surface clip ────────────────────────────
            // Skip tasks whose Y range is entirely above or below the
            // heightmap surface.  The rawHm values are "first air Y"
            // (topSolid + 1).  Zero-margin: if the task's block-Y range
            // doesn't overlap [surfMin, surfMax), skip it entirely.
            // This catches L4 roots and any children that slipped through
            // the spawnChildren pruning at a coarser resolution.
            {
                Iterator<OctreeTask> it = claimed.iterator();
                while (it.hasNext()) {
                    OctreeTask task = it.next();
                    float[][] taskRawHm = task.columnContext.rawHm();
                    if (taskRawHm == null) continue;
                    int taskMinBlockY = WorldSectionCoord.worldSectionToBlockMin(task.wsY, level);
                    int taskMaxBlockY = WorldSectionCoord.worldSectionToBlockMax(task.wsY, level) + 1;
                    float surfMin = Float.MAX_VALUE;
                    float surfMax = -Float.MAX_VALUE;
                    for (float[] row : taskRawHm) {
                        for (float h : row) {
                            if (h < surfMin) surfMin = h;
                            if (h > surfMax) surfMax = h;
                        }
                    }
                    if (taskMaxBlockY < surfMin || taskMinBlockY >= surfMax) {
                        task.markReady();
                        queue.markCompleted();
                        queue.propagateAdjacency(task);
                        it.remove();
                    }
                }
                if (claimed.isEmpty()) continue;
            }

            // ── Batched inference ────────────────────────────────────
            try {
                List<OctreeModelRunner.OctreeOutput> outputs;
                if (level == 4) {
                    outputs = model.runInitBatch(claimed);
                } else if (level > 0) {
                    outputs = model.runRefineBatch(level, claimed);
                } else {
                    outputs = model.runLeafBatch(claimed);
                }

                // ── Post-process each result ─────────────────────────
                for (int i = 0; i < claimed.size(); i++) {
                    OctreeTask task = claimed.get(i);
                    OctreeModelRunner.OctreeOutput output = outputs.get(i);

                    // Write to Voxy for progressive visibility at every level.
                    //
                    // L0: write 32³ directly as a single WorldSection at level 0.
                    // L1-L4: write 32³ at the model's native resolution for this
                    //     level.  Every level needs renderable voxel data — without
                    //     it Voxy's GPU traversal sees EMPTY_MESH on intermediate
                    //     parent nodes and stops descending, making far-distance
                    //     LOD invisible.  Coarse levels (L4/L3) are progressively
                    //     replaced by finer data as the octree expands.
                    if (true) {
                                VoxelVolume vol = VoxelPredictionDecoder.fromOctreeArgmax(output.blockArgmax(), task.columnContext.biomeIdx());
                                // VoxelVolume is 32^3 XYZ; task (wsX,wsY,wsZ) is WorldSection coord at `level`.
                                // Writer expects origin in SectionPos (16-block) and Level. Convert ws->section.
                                int sx0 = WorldSectionCoord.worldSectionToBlockMin(task.wsX, level) >> 4;
                                int sy0 = WorldSectionCoord.worldSectionToBlockMin(task.wsY, level) >> 4;
                                int sz0 = WorldSectionCoord.worldSectionToBlockMin(task.wsZ, level) >> 4;
                                SectionPos origin = new SectionPos(sx0, sy0, sz0);
                                Level lvl = Level.values()[level];
                                try {
                                    writer.writeRegion(origin, lvl, vol);
                                } catch (VolumeUnavailableException e) {
                                    HelloTerrainMod.LOGGER.warn("[LodGen] writer region unavailable {} L{}: {}", origin, lvl, e.getMessage());
                                }
                            }

                    // Spawn children for non-leaf levels
                    if (level > 0) {
                        int spawned = queue.spawnChildren(task, output.occMask(),
                                output.blockArgmax(),
                                playerSectionX, playerSectionZ);
                        if (diagnosticCount.get() < 20) {
                            HelloTerrainMod.LOGGER.info(
                                    "[LodGen] L{} ({},{},{}) pri={} — occMask=0x{} spawned={} {}ms",
                                    task.level, task.wsX, task.wsY, task.wsZ,
                                    task.priority,
                                    Integer.toHexString(output.occMask() & 0xFF),
                                    spawned, output.elapsedMs());
                            diagnosticCount.incrementAndGet();
                        }
                    }

                    task.markReady();
                    queue.markCompleted();
                    queue.propagateAdjacency(task);
                    processed++;
                }
                // after we have finished the batch, update priorities so any
                // neighbours boosted via propagateAdjacency are reordered
                queue.reprioritise(playerSectionX, playerSectionZ);
            } catch (Exception e) {
                for (OctreeTask task : claimed) {
                    if (task.state() == OctreeTask.State.PROCESSING) {
                        task.markFailed(e.getMessage());
                        queue.markFailed();
                    }
                }
                if (!stopRequested.get()) {
                    HelloTerrainMod.LOGGER.warn(
                            "[LodGen] {} batch of {} tasks failed: {}",
                            tName, claimed.size(), e.getMessage(), e);
                }
            }

            if (processed % 200 < claimed.size()) {
                HelloTerrainMod.LOGGER.info(
                        "[LodGen] {} progress: {} processed,  queues: {}",
                        tName, processed, queue.queueSizeSummary());
            }

            // Periodically trim context cache to avoid unbounded growth
            if (ctxCache.size() > 256) ctxCache.clear();
        }

        HelloTerrainMod.LOGGER.info("[LodGen] {} exiting — processed {} tasks", tName, processed);
    }

}
