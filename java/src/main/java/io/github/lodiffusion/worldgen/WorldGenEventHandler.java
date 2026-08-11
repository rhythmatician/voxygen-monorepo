package io.github.lodiffusion.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL;

import java.lang.reflect.Method;
import java.nio.FloatBuffer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerWorldEvents;
import java.nio.IntBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.WeakHashMap;

import com.rhythmatician.lodiffusion.voxy.VoxyCompat;
import com.rhythmatician.lodiffusion.world.noise.GpuNoiseDispatchQueue;

/**
 * World-load event handler that bootstraps the <em>shadow router</em> pipeline.
 *
 * <p>The shadow router is the GPU-side reimplementation of Minecraft's vanilla
 * {@code NoiseRouter}: a set of GLSL compute shaders that reproduce terrain density
 * in parallel on the GPU, bypassing per-chunk CPU world-gen for far-LOD terrain.
 *
 * <p>This handler hooks into ServerLevelEvents to initialise the shadow router when
 * a world is loaded:
 * <ol>
 *   <li><b>LOAD</b>: Extract the live {@code NoiseRouter} from the server level,
 *       mirror its noise parameters to the GPU via {@link ShadowRouterExtractor}
 *       + {@link ShaderSSBOManager}, and build the biome palette SSBO.</li>
 *   <li><b>UNLOAD</b>: Clean up all GPU resources to prevent VRAM leaks.</li>
 * </ol>
 *
 * All Minecraft class references use reflection to avoid compile-time classpath issues.
 */
public class WorldGenEventHandler {
    private static final Logger LOGGER = LogManager.getLogger();

    /** Map of ServerLevel (Object) → active ShaderSSBOManager (for lifecycle tracking) */
    private static final Map<Object, ShaderSSBOManager> activeLevels = new WeakHashMap<>();

    /** Current singleton instance (one per server lifecycle) */
    private static WorldGenEventHandler instance;

    /**
     * Debug readback toggles (all disabled by default for production runtime).
     *
     * <p>-Dlodiffusion.worldgen.debugDensityReadback=true  -> reads full density buffer
     * <p>-Dlodiffusion.worldgen.debugBlockReadback=true    -> reads full block-material buffer
     * <p>-Dlodiffusion.worldgen.writeParityReport=true     -> writes parity JSON (implies density readback)
     */
    private static final boolean DEBUG_DENSITY_READBACK = Boolean.getBoolean("lodiffusion.worldgen.debugDensityReadback");
    private static final boolean DEBUG_BLOCK_READBACK = Boolean.getBoolean("lodiffusion.worldgen.debugBlockReadback");
    private static final boolean WRITE_PARITY_REPORT = Boolean.getBoolean("lodiffusion.worldgen.writeParityReport");

    // Cached reflection metadata for Minecraft classes (loaded lazily)
    private final Class<?> serverLevelClass;
    private final Class<?> minecraftServerClass;
    private final Class<?> chunkSourceClass;
    private final Class<?> chunkGeneratorClass;
    private final Class<?> noiseBasedChunkGeneratorClass;
    private final Class<?> noiseRouterClass;
    private final Class<?> dimensionTypeClass;
    private final Class<?> resourceKeyClass;

    private final Method getChunkSourceMethod;
    private final Method getGeneratorMethod;
    private final Method getNoiseRouterMethod;
    private final Method getDimensionMethod;

    private WorldGenEventHandler() {
        // Load Minecraft class references via reflection (Yarn-mapped names for MC 1.21)
        this.serverLevelClass = loadClassOrNull("net.minecraft.server.world.ServerWorld");
        this.minecraftServerClass = loadClassOrNull("net.minecraft.server.MinecraftServer");
        this.chunkSourceClass = loadClassOrNull("net.minecraft.server.world.ServerChunkManager");
        this.chunkGeneratorClass = loadClassOrNull("net.minecraft.world.gen.chunk.ChunkGenerator");
        this.noiseBasedChunkGeneratorClass = loadClassOrNull("net.minecraft.world.gen.chunk.NoiseChunkGenerator");
        this.noiseRouterClass = loadClassOrNull("net.minecraft.world.gen.noise.NoiseRouter");
        this.dimensionTypeClass = loadClassOrNull("net.minecraft.world.dimension.DimensionType");
        this.resourceKeyClass = loadClassOrNull("net.minecraft.registry.RegistryKey");

        // Cache methods for performance
        // getChunkSourceMethod → ServerWorld.getChunkManager()
        this.getChunkSourceMethod = findMethodOrNull(serverLevelClass, "getChunkManager");
        // getGeneratorMethod → ServerChunkManager.getChunkGenerator()
        this.getGeneratorMethod = findMethodOrNull(chunkSourceClass, "getChunkGenerator");
        // getNoiseRouterMethod → ServerChunkManager.getNoiseConfig() (NoiseRouter comes from noiseConfig.getNoiseRouter())
        this.getNoiseRouterMethod = findMethodOrNull(chunkSourceClass, "getNoiseConfig");
        this.getDimensionMethod = findMethodOrNull(serverLevelClass, "getRegistryKey");
    }

    /**
     * Initializes event handlers. Call once during mod initialization.
     */
    public static synchronized void initialize() {
        if (instance != null) {
            LOGGER.warn("WorldGenEventHandler already initialized");
            return;
        }

        instance = new WorldGenEventHandler();

        ServerWorldEvents.LOAD.register((server, world) -> {
            if (instance != null) instance.onWorldLoad(server, world);
        });
        ServerWorldEvents.UNLOAD.register((server, world) -> {
            if (instance != null) instance.onWorldUnload(server, world);
        });

        LOGGER.info("WorldGenEventHandler initialized — listening for ServerWorldEvents.LOAD/UNLOAD");
    }

    /**
     * Triggered when a ServerLevel is loaded.
     */
    private void onWorldLoad(Object server, Object level) {
        long startTime = System.currentTimeMillis();
        LOGGER.info("=== WorldGenEventHandler.onWorldLoad ===");

        try {
            // Get dimension info for logging
            String dimensionInfo = getDimensionInfo(level);
            LOGGER.info("Level: {}", dimensionInfo);

            // Check for orphaned SSBO state
            if (activeLevels.containsKey(level)) {
                ShaderSSBOManager orphaned = activeLevels.get(level);
                if (orphaned.isValid()) {
                    LOGGER.warn("Orphaned ShaderSSBOManager found for level {} — cleaning up", dimensionInfo);
                    orphaned.cleanup();
                }
            }

            // Extract NoiseRouter from the chunk generator
            Object router = extractNoiseRouter(level);
            if (router == null) {
                LOGGER.warn("Unable to extract NoiseRouter from level {} — skipping GPU setup", dimensionInfo);
                return;
            }

            // Extract parameters using reflection-driven extractor
            LOGGER.info("Extracting shadow router parameters from vanilla NoiseRouter...");
            ShadowRouterExtractor extractor = new ShadowRouterExtractor();
            ShadowRouterExtractor.ShadowRouterData data = extractor.extract(router);

            // Verify extracted data is non-null
            if (data == null) {
                LOGGER.error("ShadowRouterExtractor returned null data — aborting GPU setup");
                return;
            }
            LOGGER.info("Extraction complete: {} noise instances discovered", 
                    countExtractedInstances(data));

            // Integrated server world-load callbacks run on the server thread,
            // which does not necessarily own a current OpenGL context.
            if (!hasCurrentOpenGlContext()) {
                LOGGER.warn("No current OpenGL context on world-load thread; skipping GPU shadow-router initialization for {}", dimensionInfo);
                return;
            }

            // Upload to GPU SSBOs
            LOGGER.info("Creating ShaderSSBOManager and uploading to GPU...");
            ShaderSSBOManager manager = new ShaderSSBOManager();
            manager.uploadNoiseData(data); // Also compiles shader program

            // Wire GPU biome palette (world-load-time, biome entries are static per dimension)
            Object biomeSource = extractBiomeSource(level);
            if (biomeSource != null) {
                LOGGER.info("Wiring GPU biome palette from {} ...",
                        biomeSource.getClass().getSimpleName());
                manager.initBiomePalette(biomeSource);
            } else {
                LOGGER.warn("Could not extract BiomeSource — GPU biome classification pass disabled");
            }

            activeLevels.put(level, manager);

            // Initialise the async GPU noise dispatch queue so GpuNoiseRouterSampler
            // can enqueue section requests from the gen thread.
            QuartNoiseCompute quartCompute = manager.getQuartCompute();
            if (quartCompute != null && quartCompute.isReady()) {
                GpuNoiseDispatchQueue.init(quartCompute);
            } else {
                LOGGER.warn("QuartNoiseCompute not ready — GpuNoiseDispatchQueue will not be initialised");
            }

            // Production path: dispatch and hand off GPU buffer handles for staging.
            LOGGER.info("Dispatching GPU compute for startup chunk (0,0) without CPU readback...");
            ShaderSSBOManager.ChunkGpuOutputs outputs = manager.dispatchForStaging(0, 0);
            stageGpuOutputs(outputs, dimensionInfo);

            // Optional debug/parity validation path (explicitly gated).
            if (DEBUG_DENSITY_READBACK || WRITE_PARITY_REPORT) {
                FloatBuffer allDensity = manager.readDensityDebug();
                if (allDensity != null && allDensity.hasRemaining()) {
                    StringBuilder log = new StringBuilder("GPU Density Samples [0-9]: ");
                    for (int i = 0; i < 10 && allDensity.hasRemaining(); i++) {
                        log.append(String.format("%.4f", allDensity.get())).append(" ");
                    }
                    LOGGER.info(log.toString());

                    if (WRITE_PARITY_REPORT) {
                        writeGpuParityFile(allDensity, dimensionInfo);
                    }
                } else {
                    LOGGER.warn("Unable to read density output from GPU (null or empty buffer)");
                }
            }

            // WS-2 debug path: only read block materials when explicitly enabled.
            if (DEBUG_BLOCK_READBACK) {
                writeBlocksToVoxy(manager, level, dimensionInfo);
            } else {
                LOGGER.debug("WS-2: block readback disabled (set -Dlodiffusion.worldgen.debugBlockReadback=true to enable)");
            }

            long elapsedMs = System.currentTimeMillis() - startTime;
            LOGGER.info("WorldGenEventHandler.onWorldLoad complete in {} ms", elapsedMs);

        } catch (Exception e) {
            LOGGER.error("WorldGenEventHandler.onWorldLoad failed", e);
        }
    }

    /**
     * Reads the block-material output from the GPU and pushes chunk (0,0) into Voxy
     * as an initial proof-of-concept (WS-2.4).
     *
     * <p>Skipped when Voxy is unavailable or the dimension is not the overworld.
     * Catches all exceptions so a Voxy API change never crashes world load.
     */
    private void writeBlocksToVoxy(ShaderSSBOManager manager, Object level, String dimensionInfo) {
        if (!VoxyCompat.isAvailable()) {
            LOGGER.debug("WS-2: Voxy not available — skipping block write");
            return;
        }
        if (!dimensionInfo.contains("overworld") && !dimensionInfo.contains("(unknown)")) {
            LOGGER.debug("WS-2: Skipping block write for non-overworld dimension: {}", dimensionInfo);
            return;
        }
        try {
            IntBuffer blockMat = manager.readBlockOutputDebug();
            if (blockMat == null) {
                LOGGER.warn("WS-2: block output buffer is null — skipping Voxy write");
                return;
            }

            // Get Voxy world engine from the level (ServerLevel extends World)
            Object worldEngine = VoxyCompat.getWorldEngine((net.minecraft.world.World) level);
            if (worldEngine == null) {
                LOGGER.warn("WS-2: Voxy world engine not available for this level");
                return;
            }

            // Get default biome ID (plains) from the mapper
            Object mapper = VoxyCompat.getMapper(worldEngine);
            int defaultBiome = resolveDefaultBiome(mapper);

            ShaderSectionWriter writer = new ShaderSectionWriter(worldEngine, defaultBiome);
            int nonAir = writer.writeColumn(blockMat, 0, 0);
            LOGGER.info("WS-2: wrote GPU chunk (0,0) to Voxy — {} non-air voxels", nonAir);

        } catch (Exception e) {
            LOGGER.warn("WS-2: writeBlocksToVoxy failed (non-fatal): {}", e.getMessage(), e);
        }
    }

    private boolean hasCurrentOpenGlContext() {
        try {
            return GL.getCapabilities() != null;
        } catch (IllegalStateException e) {
            return false;
        }
    }

    /**
     * Resolves the Voxy biome ID for {@code minecraft:plains} via the Mapper.
     * Returns 0 if resolution fails.
     */
    private int resolveDefaultBiome(Object mapper) {
        try {
            // The plains biome identifier is used as a fallback; just try to call
            // Mapper.getIdForBiome with the identifier string if the Voxy API supports it.
            Class<?> identifierClass = Class.forName("net.minecraft.util.Identifier");
            Object plainsId = identifierClass.getMethod("of", String.class).invoke(null, "minecraft:plains");
            Method m = mapper.getClass().getMethod("getIdForBiome", Object.class);
            return (int) m.invoke(mapper, plainsId);
        } catch (Exception e) {
            LOGGER.debug("WS-2: could not resolve plains biome ID ({}), using 0", e.getMessage());
            return 0;
        }
    }

    /**
     * Writes the GPU density output for chunk (0,0) to a JSON file for WS-1.3 parity validation.
     *
     * <p>File: {@code run/parity_reports/gpu_chunk_0_0.json}<br>
     * Format: {@code {"chunk_x":0,"chunk_z":0,"source":"gpu",
     * "density":[...98304 floats...]}}
     *
     * <p>Compare against {@code java_chunk_0_0.json} (written by {@code /dumpnoise parity 0 0})
     * using {@code tools/validate_shader_parity.py}.
     */
    private void writeGpuParityFile(FloatBuffer densityBuf, String dimensionInfo) {
        // Only write parity for the overworld (avoids nether/end false positives)
        if (!dimensionInfo.contains("overworld") && !dimensionInfo.contains("(unknown)")) {
            LOGGER.debug("Skipping parity file for non-overworld dimension: {}", dimensionInfo);
            return;
        }
        try {
            Path dir = Path.of("parity_reports");
            Files.createDirectories(dir);
            Path out = dir.resolve("gpu_chunk_0_0.json");

            densityBuf.rewind();
            StringBuilder sb = new StringBuilder(2 * 1024 * 1024);
            sb.append("{\n");
            sb.append("  \"chunk_x\": 0,\n");
            sb.append("  \"chunk_z\": 0,\n");
            sb.append("  \"source\": \"gpu\",\n");
            sb.append("  \"y_min\": -64,\n");
            sb.append("  \"y_levels\": 384,\n");
            sb.append("  \"note\": \"density[lx + 16*lz][by - y_min]\",\n");
            sb.append("  \"density\": [");
            boolean first = true;
            while (densityBuf.hasRemaining()) {
                if (!first) sb.append(',');
                first = false;
                sb.append(String.format("%.6g", densityBuf.get()));
            }
            sb.append("]\n}\n");

            Files.writeString(out, sb);
            LOGGER.info("WS-1.3 parity: wrote GPU density to {}", out.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.warn("WS-1.3 parity: failed to write GPU density file", e);
        }
    }


    /**
     * Production dataflow boundary: pass GPU-resident chunk outputs to downstream staging.
     *
     * <p>This method intentionally does not materialize density/block payloads on CPU.
     * It defines the contract needed for model input staging (or a direct GPU-resident
     * ingest path) to consume compute outputs by buffer ID + metadata.
     */
    private void stageGpuOutputs(ShaderSSBOManager.ChunkGpuOutputs outputs, String dimensionInfo) {
        LOGGER.debug(
                "GPU staging contract ready for {} chunk ({}, {}): density[binding={}, id={}, floats={}], block[binding={}, id={}, ints={}]",
                dimensionInfo,
                outputs.chunkX(),
                outputs.chunkZ(),
                outputs.densityBinding(),
                outputs.densityBufferId(),
                outputs.densityFloatCount(),
                outputs.blockBinding(),
                outputs.blockBufferId(),
                outputs.blockIntCount()
        );
    }

    /**
     * Triggered when a ServerLevel is unloaded.
     */
    private void onWorldUnload(Object server, Object level) {
        String dimensionInfo = getDimensionInfo(level);
        LOGGER.info("=== WorldGenEventHandler.onWorldUnload ===");
        LOGGER.info("Level: {}", dimensionInfo);

        try {
            // Shut down the GPU noise dispatch queue first (cancels pending futures
            // so the gen thread doesn't hang waiting for GPU results).
            GpuNoiseDispatchQueue.shutdown();

            ShaderSSBOManager manager = activeLevels.remove(level);
            if (manager != null) {
                LOGGER.info("Cleaning up ShaderSSBOManager for level {}", dimensionInfo);
                manager.cleanup();
            } else {
                LOGGER.debug("No active ShaderSSBOManager for level {} to clean up", dimensionInfo);
            }
        } catch (Exception e) {
            LOGGER.error("Error during WorldGenEventHandler.onWorldUnload", e);
        }
    }

    /**
     * Extracts the {@code BiomeSource} from a ServerLevel using reflection.
     *
     * <p>The chain is: {@code level → getChunkSource() → getGenerator() → getBiomeSource()}.
     * For overworld levels this is a {@code MultiNoiseBiomeSource}.
     *
     * @param level ServerLevel instance
     * @return the BiomeSource object, or {@code null} if unavailable
     */
    private Object extractBiomeSource(Object level) {
        try {
            if (level == null || getChunkSourceMethod == null || getGeneratorMethod == null) return null;

            Object chunkSource = getChunkSourceMethod.invoke(level);
            if (chunkSource == null) return null;

            Object generator = getGeneratorMethod.invoke(chunkSource);
            if (generator == null) return null;

            // ChunkGenerator.getBiomeSource() is stable across Mojmap/Yarn mappings
            Method getBiomeSourceMethod = findMethodOrNull(generator.getClass(), "getBiomeSource");
            if (getBiomeSourceMethod == null) {
                // Walk the class hierarchy in case the method is declared on a superclass
                Class<?> cls = generator.getClass().getSuperclass();
                while (cls != null && getBiomeSourceMethod == null) {
                    getBiomeSourceMethod = findMethodOrNull(cls, "getBiomeSource");
                    cls = cls.getSuperclass();
                }
            }
            if (getBiomeSourceMethod != null) {
                getBiomeSourceMethod.setAccessible(true);
                return getBiomeSourceMethod.invoke(generator);
            }

            LOGGER.warn("extractBiomeSource: getBiomeSource() not found on {}",
                    generator.getClass().getName());
            return null;
        } catch (Exception e) {
            LOGGER.warn("Failed to extract BiomeSource: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extracts the NoiseRouter from a ServerWorld using reflection.
     * Chain: ServerWorld.getChunkManager() → ServerChunkManager.getNoiseConfig() → NoiseConfig.getNoiseRouter()
     */
    private Object extractNoiseRouter(Object level) {
        try {
            if (level == null || getChunkSourceMethod == null || getNoiseRouterMethod == null) {
                return null;
            }

            // level.getChunkManager() returns ServerChunkManager
            Object chunkManager = getChunkSourceMethod.invoke(level);
            if (chunkManager == null) return null;

            // chunkManager.getNoiseConfig() returns NoiseConfig
            Object noiseConfig = getNoiseRouterMethod.invoke(chunkManager);
            if (noiseConfig == null) return null;

            // noiseConfig.getNoiseRouter() returns NoiseRouter
            Method getNoiseRouterFromConfig = findMethodOrNull(noiseConfig.getClass(), "getNoiseRouter");
            if (getNoiseRouterFromConfig == null) {
                LOGGER.warn("getNoiseRouter() not found on {} — cannot extract NoiseRouter",
                        noiseConfig.getClass().getName());
                return null;
            }
            return getNoiseRouterFromConfig.invoke(noiseConfig);

        } catch (Exception e) {
            LOGGER.error("Failed to extract NoiseRouter", e);
            return null;
        }
    }

    /**
     * Gets a human-readable dimension identifier for logging.
     */
    private String getDimensionInfo(Object level) {
        try {
            if (level == null || getDimensionMethod == null) {
                return "(unknown)";
            }

            Object dimensionKey = getDimensionMethod.invoke(level);
            if (dimensionKey == null) return "(null)";

            // Try to extract the path/identifier
            String keyStr = dimensionKey.toString();
            if (keyStr.contains("minecraft:")) {
                return keyStr.substring(keyStr.lastIndexOf("minecraft:") + 10);
            }
            return keyStr;

        } catch (Exception e) {
            return "(error)";
        }
    }

    /**
     * Helper to estimate the number of noise instances extracted.
     */
    private int countExtractedInstances(ShadowRouterExtractor.ShadowRouterData data) {
        int count = 0;
        if (data.improvedOrigins != null) count += data.improvedOrigins.capacity() / 4;
        if (data.improvedPerms != null) count += data.improvedPerms.capacity() / 256;
        if (data.perlinInts != null) count += Math.max(0, data.perlinInts.capacity() / 16);
        return Math.max(count, 1);
    }

    /**
     * Returns the active ShaderSSBOManager for a level (Object), or null.
     */
    public static ShaderSSBOManager getManagerForLevel(Object level) {
        if (instance == null) return null;
        return activeLevels.get(level);
    }

    /**
     * Cleanup all managed SSBOs.
     */
    public static synchronized void cleanupAll() {
        if (instance == null) return;

        LOGGER.info("WorldGenEventHandler.cleanupAll() — cleaning up {} levels", activeLevels.size());
        for (ShaderSSBOManager manager : activeLevels.values()) {
            try {
                manager.cleanup();
            } catch (Exception e) {
                LOGGER.error("Error during SSBO cleanup", e);
            }
        }
        activeLevels.clear();
    }

    // ============================================================================
    // Reflection Utilities
    // ============================================================================

    private static Class<?> loadClassOrNull(String className) {
        try {
            return Class.forName(className);
        } catch (Throwable t) {
            // Catch Errors and Exceptions (e.g., ExceptionInInitializerError) to avoid
            // failing unit tests or running outside of a full Minecraft bootstrap.
            return null;
        }
    }

    private static Method findMethodOrNull(Class<?> clazz, String methodName) {
        if (clazz == null) return null;
        try {
            for (Method m : clazz.getDeclaredMethods()) {
                if (m.getName().equals(methodName)) {
                    m.setAccessible(true);
                    return m;
                }
            }
        } catch (Exception ignored) {}
        return null;
    }
}

