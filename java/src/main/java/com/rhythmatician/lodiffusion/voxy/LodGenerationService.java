package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.HelloTerrainMod;

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
    private static final int Y_SECTIONS = 16;
    private static final int Y_BASE_SECTION = -4;

    /** Minimum block Y in the Minecraft world (floor of bedrock). */
    static final int MIN_WORLD_BLOCK_Y = Y_BASE_SECTION * 16;
    /** Maximum block Y in the Minecraft world (exclusive). */
    static final int MAX_WORLD_BLOCK_Y = (Y_BASE_SECTION + Y_SECTIONS) * 16;

    /**
     * Returns {@code true} if the entire world-section at the given level and
     * Y coordinate falls outside the Minecraft world Y range.
     */
    static boolean isOutOfWorldY(int level, int wsY) {
        int blockYMin = WorldSectionCoord.worldSectionToBlockMin(wsY, level);
        int blockYMaxExcl = WorldSectionCoord.worldSectionToBlockMax(wsY, level) + 1;
        return blockYMaxExcl <= MIN_WORLD_BLOCK_Y || blockYMin >= MAX_WORLD_BLOCK_Y;
    }

    /** Generation radius (in sections). */
    private static final int GENERATION_RADIUS = Config.getInt("generationRadius", 32);

    // Synthetic heightmap constants for test-visible fallback (mirrors GenerationSession)
    private static final float SEA_LEVEL = 62f;
    private static final float HEIGHT_AMPLITUDE = 24f;

    /**
     * Pre-sampled conditioning data for a single 16x16 column.
     * Retained here for {@code ChunkScheduler.ColumnContextProvider} and {@code SectionTask}.
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
    private final Object lock = new Object();

    /**
     * Pack section coordinates into a single long key for deduplication.
     * Each axis uses 20 bits, supporting +/-524287 sections.
     */
    static long sectionKey(int x, int y, int z) {
        return ((long) (x & 0xFFFFF) << 40) | ((long) (y & 0xFFFFF) << 20) | (z & 0xFFFFFL);
    }

    // Synthetic fallback retained for L1AvailabilityContractTest reflection.
    private float[][] buildHeightmap(int sectionX, int sectionZ) {
        float[][] hm = new float[16][16];
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float bx = sectionX * 16f + lx;
                float bz = sectionZ * 16f + lz;
                float h = SEA_LEVEL;
                h += HEIGHT_AMPLITUDE * 0.50f * (float) Math.sin(bx * 0.005 + 1.7)
                                              * (float) Math.cos(bz * 0.007 + 0.3);
                h += HEIGHT_AMPLITUDE * 0.25f * (float) Math.sin(bx * 0.013 + 3.1)
                                              * (float) Math.sin(bz * 0.011 + 2.2);
                h += HEIGHT_AMPLITUDE * 0.12f * (float) Math.cos(bx * 0.037 + 0.9)
                                              * (float) Math.sin(bz * 0.029 + 4.1);
                hm[lx][lz] = Math.max(0f, Math.min(320f, h));
            }
        }
        return hm;
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
            HelloTerrainMod.LOGGER.info("[LodGen] Service started via GenerationSession");
        }
    }

    /** Stop the service and wait for the worker to finish. */
    public void stop() {
        synchronized (lock) {
            GenerationSession s = session;
            if (s == null) return;
            s.stop();
            session = null;
            HelloTerrainMod.LOGGER.info("[LodGen] Service stopped via GenerationSession");
        }
    }

    /** Update the player position (called each client tick). */
    public void updatePlayerPosition(BlockPos pos) {
        GenerationSession s = session;
        if (s != null) {
            s.updatePlayerPosition(pos);
        }
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
