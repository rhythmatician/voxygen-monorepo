package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;
import net.lodiffusion.shadow.ShadowRouterJobQueue;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;

/**
 * Thin per-world lifecycle facade that delegates to a {@link GenerationSession}.
 *
 * <p>Owns no candidate-specific resources; all per-world bindings
 * ({@code WorldNoiseAccess}, {@code NoiseRouterSamplerFactory},
 * {@code VoxyModelRunner}, caches, queues) live in the session.
 * This class retains only global singleton publication and a few static
 * helpers ({@link #isOutOfWorldY}, {@link #sectionKey}, {@link ColumnContext})
 * required by external callers ({@code ChunkScheduler}, {@code SectionTask},
 * tests).
 */
@SuppressWarnings("deprecation")
public final class LodGenerationService {

    /** How many sections of Y range to generate (from y=-64 upward). */
    // Source of truth: GenerationSession.Y_SECTIONS
    static final int Y_SECTIONS = GenerationSession.Y_SECTIONS;
    static final int Y_BASE_SECTION = GenerationSession.Y_BASE_SECTION;

    /** Minimum block Y in the Minecraft world (floor of bedrock). */
    static final int MIN_WORLD_BLOCK_Y = GenerationSession.MIN_WORLD_BLOCK_Y;
    /** Maximum block Y in the Minecraft world (exclusive). */
    static final int MAX_WORLD_BLOCK_Y = GenerationSession.MAX_WORLD_BLOCK_Y;

    /**
     * Returns {@code true} if the entire world-section at the given level and
     * Y coordinate falls outside the Minecraft world Y range.
     */
    static boolean isOutOfWorldY(int level, int wsY) {
        return GenerationSession.isOutOfWorldY(level, wsY);
    }

    /** Generation radius (in sections). */
    // Mirrors GenerationSession.GENERATION_RADIUS — same config key
    private static final int GENERATION_RADIUS = GenerationSession.GENERATION_RADIUS;

    // Synthetic heightmap constants for test-visible fallback (mirrors GenerationSession)
    // Mirrors GenerationSession.SEA_LEVEL — source of truth in session
    private static final float SEA_LEVEL = GenerationSession.SEA_LEVEL;
    private static final float HEIGHT_AMPLITUDE = GenerationSession.HEIGHT_AMPLITUDE;

    /**
     * Pre-sampled conditioning data for a single 16x16 column.
     * Retained here for {@code ChunkScheduler.ColumnContextProvider} and {@code SectionTask}.
     * Shape mirrors {@link GenerationSession.ColumnContext} — source of truth is the session;
     * this public record is kept for external callers and is kept in sync via the shared
     * field names and fallback heightmap logic. A future extraction to a shared
     * {@code ColumnContext} type can replace both if the API stabilizes.
     */
    public record ColumnContext(
        float[][] rawHm,
        int[][] biomeIdx,
        float[][] hp5,
        float[][] oceanFloorHm
    ) {}

    /**
     * Mod-wide singleton reference — set by {@code LodiffusionClient} during
     * client initialisation.
     */
    private static final java.util.concurrent.atomic.AtomicReference<LodGenerationService>
            INSTANCE_REF = new java.util.concurrent.atomic.AtomicReference<>(null);

    /** Called by {@code LodiffusionClient} to register the singleton. */
    public static void setInstance(LodGenerationService svc) {
        INSTANCE_REF.set(svc);
    }

    /** Return the singleton service, or {@code null} if not yet initialised. */
    public static LodGenerationService getInstance() {
        return INSTANCE_REF.get();
    }

    // Per-world session delegation — owns all candidate resources.
    private volatile GenerationSession session;
    private volatile RegistryKey<World> boundDimension;
    private final Object lock = new Object();
    /** Durable per-world observations replayed into every newly bound End session. */
    private final java.util.Map<RegistryKey<World>, java.util.Set<Long>> observedVanillaChunks =
            new java.util.HashMap<>();

    /**
     * Pack section coordinates into a single long key for deduplication.
     * Each axis uses 20 bits, supporting +/-524287 sections.
     */
    static long sectionKey(int x, int y, int z) {
        return GenerationSession.sectionKey(x, y, z);
    }

    // Synthetic fallback retained for L1AvailabilityContractTest reflection.
    // Delegates to GenerationSession — source of truth for synthetic fallback
    float[][] buildHeightmap(int sectionX, int sectionZ) {
        return GenerationSession.buildHeightmap(sectionX, sectionZ);
    }

    /**
     * Begin loading the Voxy ONNX model set — delegates to the active session
     * if already started; otherwise logs and defers to {@link #start}.
     * Retained for backward compatibility; new code should rely on session lifecycle.
     */
    public void preloadModel() {
        GenerationSession s = session;
        if (s != null) {
            s.preloadModel();
        } else {
            HelloTerrainMod.LOGGER.debug("[LodGen] preloadModel() deferred — no active session yet");
        }
    }

    /**
     * Start the LOD generation service for a given world.
     *
     * @param world the Minecraft world (client-side)
     * @param server the Minecraft server (integrated server for singleplayer; null for dedicated)
     */
    public void start(World world, MinecraftServer server) {
        synchronized (lock) {
            if (session != null && session.isRunning()) {
                HelloTerrainMod.LOGGER.warn("[LodGen] Service already running");
                return;
            }
            GenerationSession s = new GenerationSession();
            session = s;
            s.start(world, server);
            try {
                boundDimension = world != null ? world.getRegistryKey() : null;
            } catch (Exception ignored) {
                boundDimension = null;
            }
            flushVanillaChunkObservations(s, boundDimension);
            HelloTerrainMod.LOGGER.info("[LodGen] Service started via GenerationSession");
        }
    }

    // Test-visible start that avoids mocking World (ByteBuddy retransform limit in full suite).
    // Package-private; uses GenerationSession test helpers instead of reflection.
    void startForTest(RegistryKey<World> key, MinecraftServer server) {
        synchronized (lock) {
            if (session != null && session.isRunning()) {
                HelloTerrainMod.LOGGER.warn("[LodGen] Service already running");
                return;
            }
            GenerationSession s = new GenerationSession();
            session = s;
            s.start(null, server);
            boolean isEnd = isEndDimension(key);
            s.setEndL4TracerModeForTest(isEnd);
            s.forceRunningForTest();
            boundDimension = key;
            flushVanillaChunkObservations(s, boundDimension);
            HelloTerrainMod.LOGGER.info("[LodGen] Service startedForTest via GenerationSession key={} tracer={}", key, s.isEndL4TracerMode());
        }
    }

    /** Stop the service and wait for the worker to finish. */
    public void stop() {
        synchronized (lock) {
            GenerationSession s = session;
            if (s == null) return;
            s.stop();
            // Clear static queue so old-dimension demand cannot survive into next session
            ShadowRouterJobQueue.clear();
            session = null;
            boundDimension = null;
            observedVanillaChunks.clear();
            HelloTerrainMod.LOGGER.info("[LodGen] Service stopped via GenerationSession");
        }
    }

    /**
     * Dimension-change-aware rebind — teleport to the_end activates tracer without rejoin.
     *
     * <p>Detects {@code client.world.getRegistryKey()} != last bound dimension and rebinds
     * a fresh {@link GenerationSession} via {@code stop() + ShadowRouterJobQueue.clear() + start()}
     * on the render thread. Re-executes the early gate
     * {@code endL4TracerMode = decideEndL4TracerMode(world)} before
     * {@code preloadModel()}/{@code resolveVoxyModel()}/worker entry.
     * Debounced: ignore repeated ticks while stopping (synchronized on lock).
     *
     * @return true if a rebind was performed
     */
    public boolean checkAndRebindIfNeeded(World world, MinecraftServer server) {
        if (world == null) return false;
        RegistryKey<World> newKey;
        try {
            newKey = world.getRegistryKey();
        } catch (Exception e) {
            return false;
        }
        return checkAndRebindIfNeeded(newKey, world, server);
    }

    // Test-visible overload that avoids mocking World (avoids ByteBuddy retransform limits in full suite)
    boolean checkAndRebindIfNeeded(RegistryKey<World> newKey, World worldForStart, MinecraftServer server) {
        if (newKey == null) return false;
        synchronized (lock) {
            if (boundDimension != null && boundDimension.equals(newKey)) {
                return false;
            }
            GenerationSession s = session;
            if (s == null || !s.isRunning()) {
                return false;
            }
            HelloTerrainMod.LOGGER.info(
                    "[LodGen] Dimension change detected {} -> {}, rebinding (ShadowRouterJobQueue.clear())",
                    boundDimension, newKey);
            s.stop();
            // ShadowRouterJobQueue.clear() wipes queued + in-flight so old-dimension demand cannot survive
            ShadowRouterJobQueue.clear();
            session = null;
            boundDimension = null;
            GenerationSession next = new GenerationSession();
            session = next;
            // worldForStart may be null in test when using RegistryKey overload; pass null or fake world
            // GenerationSession.start handles null world as non-tracer safely (decide returns false)
            // but we still set boundDimension to newKey afterwards so tracer flag is correct via next.start's decide.
            // If worldForStart == null we manually set tracer mode via reflection is not needed because
            // decideEndL4TracerMode(null) is false; so for test we set boundDimension directly after start.
            if (worldForStart != null) {
                next.start(worldForStart, server);
                try {
                    boundDimension = worldForStart.getRegistryKey();
                } catch (Exception ignored) {
                    boundDimension = newKey;
                }
            } else {
                next.start(null, server);
                boolean isEnd = isEndDimension(newKey);
                next.setEndL4TracerModeForTest(isEnd);
                boundDimension = newKey;
            }
            HelloTerrainMod.LOGGER.info(
                    "[LodGen] Rebound complete to {} tracer={}", newKey, next.isEndL4TracerMode());
            flushVanillaChunkObservations(next, boundDimension);
            return true;
        }
    }

    /** Test-visible bound dimension. */
    RegistryKey<World> getBoundDimensionForTest() {
        return boundDimension;
    }

    /** Update the player position (called each client tick). */
    public void updatePlayerPosition(BlockPos pos) {
        GenerationSession s = session;
        if (s != null) {
            s.updatePlayerPosition(pos);
        }
    }

    /** Client-tick frontier snapshot expressed as scalar values at the service boundary. */
    public void updatePlayerPosition(BlockPos pos, double horizontalVelocityX,
                                     double horizontalVelocityZ, int clientViewDistanceChunks,
                                     int simulationDistanceChunks) {
        GenerationSession s = session;
        if (s != null) {
            s.updatePlayerPosition(pos, horizontalVelocityX, horizontalVelocityZ,
                    clientViewDistanceChunks, simulationDistanceChunks);
        }
    }

    /** Records a loaded vanilla chunk after Fabric has loaded it; never waits on generation. */
    public void observeVanillaChunkColumn(World world, int chunkX, int chunkZ) {
        if (world == null) return;
        RegistryKey<World> key;
        try {
            key = world.getRegistryKey();
        } catch (Exception ignored) {
            return;
        }
        observeVanillaChunkColumn(key, chunkX, chunkZ);
    }

    void observeVanillaChunkColumnForTest(RegistryKey<World> key, int chunkX, int chunkZ) {
        observeVanillaChunkColumn(key, chunkX, chunkZ);
    }

    private void observeVanillaChunkColumn(RegistryKey<World> key, int chunkX, int chunkZ) {
        if (key == null) return;
        synchronized (lock) {
            java.util.Set<Long> observations = observedVanillaChunks.computeIfAbsent(key,
                    ignored -> new java.util.HashSet<>());
            long chunkKey = (((long) chunkX) << 32) ^ (chunkZ & 0xFFFF_FFFFL);
            if (!observations.add(chunkKey)) return;
            GenerationSession active = session;
            if (active != null && active.isRunning() && key.equals(boundDimension)
                    && isEndDimension(key)) {
                active.observeVanillaChunkColumn(chunkX, chunkZ);
            }
        }
    }

    private void flushVanillaChunkObservations(GenerationSession target, RegistryKey<World> key) {
        if (target == null || key == null || !isEndDimension(key)) return;
        java.util.Set<Long> observations = observedVanillaChunks.get(key);
        if (observations == null) return;
        for (long chunkKey : observations) {
            target.observeVanillaChunkColumn((int) (chunkKey >> 32), (int) chunkKey);
        }
    }

    private static boolean isEndDimension(RegistryKey<World> key) {
        return key != null && key.getValue().equals(net.minecraft.util.Identifier.of("minecraft", "the_end"));
    }

    public boolean isRunning() {
        GenerationSession s = session;
        return s != null && s.isRunning();
    }

    // Test-visible accessor
    GenerationSession getSessionForTest() {
        return session;
    }
}
