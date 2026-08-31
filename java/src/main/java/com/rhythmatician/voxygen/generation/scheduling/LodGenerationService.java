package com.rhythmatician.voxygen.generation.scheduling;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import net.lodiffusion.shadow.ShadowRouterJobQueue;

import net.minecraft.registry.RegistryKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import com.rhythmatician.voxygen.generation.TerrainPublicationRoute;
import com.rhythmatician.voxygen.generation.session.GenerationSession;

/**
 * Thin per-world lifecycle facade that delegates to a {@link GenerationSession}.
 *
 * <p>Owns no candidate-specific resources; all per-world bindings
 * ({@code WorldNoiseAccess}, {@code NoiseRouterSamplerFactory},
 * {@code VoxyModelRunner}, caches, queues) live in the session.
 * This class retains only global singleton publication and lifecycle
 * delegation (start/stop/rebind/position/observations) around the session.
 */
@SuppressWarnings("unused")
public final class LodGenerationService {

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
    public void startForTest(RegistryKey<World> key, MinecraftServer server) {
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
     * {@link TerrainPublicationRoute#forWorld(World)} before
     * model resolution or worker entry.
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
    public boolean checkAndRebindIfNeeded(RegistryKey<World> newKey, World worldForStart, MinecraftServer server) {
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
            // GenerationSession.start treats a null test world as compatibility mode.
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
    public RegistryKey<World> getBoundDimensionForTest() {
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

    public void observeVanillaChunkColumnForTest(RegistryKey<World> key, int chunkX, int chunkZ) {
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
    public GenerationSession getSessionForTest() {
        return session;
    }
}
