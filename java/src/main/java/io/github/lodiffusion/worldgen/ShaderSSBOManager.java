package io.github.lodiffusion.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL43C;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * GPU memory manager for the <em>shadow router</em> pipeline.
 *
 * <p>The shadow router is a GLSL compute-shader re-implementation of Minecraft's
 * vanilla {@code NoiseRouter} that evaluates terrain density in parallel on the GPU,
 * enabling far-LOD terrain generation without per-chunk CPU world-gen.
 *
 * <p>This class allocates and uploads the 8 Shader Storage Buffer Objects (SSBOs)
 * that feed the shadow router compute shaders.  The buffer data is produced once per
 * world load by {@link ShadowRouterExtractor} (which walks the live {@code NoiseRouter}
 * via reflection), and then lives on the GPU for the lifetime of that world.
 *
 * <h3>Memory Layout (std430 alignment rules)</h3>
 * <ul>
 *   <li>Binding 0: ImprovedNoise origins (vec3 packed as vec4 for alignment)</li>
 *   <li>Binding 1: ImprovedNoise permutation tables (uint arrays)</li>
 *   <li>Binding 2: PerlinNoise octave indices (int array)</li>
 *   <li>Binding 3: PerlinNoise amplitudes (float array)</li>
 *   <li>Binding 4: NormalNoise perlin indices (int array)</li>
 *   <li>Binding 5: NormalNoise value factors (float array)</li>
 *   <li>Binding 6: Spline control point flattening (float array)</li>
 *   <li>Binding 7: Output density grid (read-write, target for compute dispatch)</li>
 * </ul>
 */
public class ShaderSSBOManager {
    private static final Logger LOGGER = LogManager.getLogger();

    // SSBO Binding points (must match GLSL shader definitions)
    private static final int IMPROVED_ORIGINS_BINDING = 0;
    private static final int IMPROVED_PERMS_BINDING = 1;
    private static final int PERLIN_INT_BINDING = 2;
    private static final int PERLIN_FLOAT_BINDING = 3;
    private static final int NORMAL_NOISE_INT_BINDING = 4;
    private static final int NORMAL_NOISE_FLOAT_BINDING = 5;
    private static final int SPLINE_DATA_BINDING = 6;
    private static final int DENSITY_OUTPUT_BINDING = 7;
    /** Block material output SSBO (binding 11, written by pass 2 of the compute shader). */
    private static final int BLOCK_OUTPUT_BINDING = 11;

    /** bufferIds array must be large enough to hold binding 11 as a direct index. */
    private static final int BUFFER_COUNT = 12;

    /** Number of floats in the density output grid: 16 (X) × 384 (Y_LEVELS) × 16 (Z). */
    private static final int DENSITY_FLOATS = 16 * 384 * 16;  // = 98,304

    /** Number of ints in the block-material output grid (same element count as density). */
    private static final int BLOCK_INT_COUNT = DENSITY_FLOATS;  // = 98,304

    // Shader program manager for compute operations
    private ShaderProgramManager shaderManager = new ShaderProgramManager();

    // Per-chunk compute dispatcher (owns the RouterConfig UBO at binding 8)
    private TerrainComputeDispatcher dispatcher = new TerrainComputeDispatcher();

    // Quart-resolution compute dispatcher (bindings 14–15, evaluates all 15 RouterFields)
    private QuartNoiseCompute quartCompute = new QuartNoiseCompute();

    /** RouterConfig in effect after uploadNoiseData(); kept for late biome-palette wiring. */
    private TerrainComputeDispatcher.RouterConfig currentConfig;

    // OpenGL buffer IDs (one per binding)
    private int[] bufferIds = new int[BUFFER_COUNT];
    private boolean initialized = false;
    private long lastUploadTime = 0L;
    private int lastDispatchedChunkX = 0;
    private int lastDispatchedChunkZ = 0;

    /** Readback telemetry counters (debug/parity path only). */
    private long readbackWindowStartMs = System.currentTimeMillis();
    private long readbackWindowCalls = 0;
    private double readbackCallsPerSec = 0.0d;
    private final Map<Long, Long> readbackBytesByChunk = new HashMap<>();

    /**
     * Production contract between compute output buffers and model-input staging.
     *
     * <p>This object intentionally contains GPU buffer handles only (no CPU copies),
     * so call sites can wire GPU-resident pipelines and materialize to CPU only when
     * explicitly requested by debug tooling.
     */
    public record ChunkGpuOutputs(
            int chunkX,
            int chunkZ,
            int densityBinding,
            int densityBufferId,
            int densityFloatCount,
            int blockBinding,
            int blockBufferId,
            int blockIntCount
    ) {}

    public ShaderSSBOManager() {
        // Buffers will be created on-demand during first upload
    }

    /**
     * Uploads {@link ShadowRouterExtractor.ShadowRouterData} to GPU SSBOs and
     * prepares the compute pipeline.
     *
     * <p>Call this on the render thread after the shadow router bootstrap
     * ({@link ShadowRouterExtractor#extract(Object)}) completes at world load.
     * Use GL_STATIC_DRAW for typical dimension/gameplay scenarios.
     *
     * ALIGNMENT NOTE (std430):
     * - vec3 is treated as vec4 for alignment purposes (12 bytes + 4 bytes padding)
     * - This method handles padding automatically for improvedOrigins
     */
    public void uploadNoiseData(ShadowRouterExtractor.ShadowRouterData data) {
        if (data == null) {
            LOGGER.warn("uploadNoiseData called with null ShadowRouterData");
            return;
        }

        LOGGER.info("ShaderSSBOManager: Uploading shadow router data to GPU...");

        // Ensure buffers and shaders are allocated
        if (!initialized) {
            allocateBuffers();
        }

        try {
            // Upload each buffer with appropriate GL settings
            uploadBuffer(IMPROVED_ORIGINS_BINDING, padImprovedOrigins(data.improvedOrigins),
                    "ImprovedNoise Origins (vec3→vec4 padded)");
            uploadBuffer(IMPROVED_PERMS_BINDING, data.improvedPerms,
                    "ImprovedNoise Permutations");
            uploadBuffer(PERLIN_INT_BINDING, data.perlinInts,
                    "PerlinNoise Octave Indices");
            uploadBuffer(PERLIN_FLOAT_BINDING, data.perlinFloats,
                    "PerlinNoise Amplitudes");
            uploadBuffer(NORMAL_NOISE_INT_BINDING, data.normalNoiseInts,
                    "NormalNoise Perlin Indices");
            uploadBuffer(NORMAL_NOISE_FLOAT_BINDING, data.normalNoiseFloats,
                    "NormalNoise Value Factors");
            uploadBuffer(SPLINE_DATA_BINDING, data.splineData,
                    "Spline Control Points");

            // Binding 7 (Density Output) is allocated but left uninitialized (written by compute shader)
            allocateDensityOutput();

            // Binding 11 (Block Material Output) — same layout as density, written by pass 2
            allocateBlockOutput();

            // Compile shaders after SSBOs are ready
            shaderManager.compile();

            // Build RouterConfig from extracted named indices (wires continents, erosion, ridges, shift).
            // Falls back to -1 for any index not yet resolved (shader uses simplified path).
            // Temperature and vegetation indices are wired here; biome palette count starts at 0
            // and is updated later by initBiomePalette().
            TerrainComputeDispatcher.RouterConfig config = TerrainComputeDispatcher.RouterConfig.overworldDefaults()
                    .withNamedIndices(
                            data.nnContinents,
                            data.nnErosion,
                            data.nnRidges,
                            data.nnDepthNoise,
                            data.nnJagged,
                            data.shiftNoiseIndex,  // nn_shift_a: SHIFT noise at (bx*0.25, 0, bz*0.25)
                            data.shiftNoiseIndex   // nn_shift_b: same noise, coords swapped in GLSL
                    )
                    .withBiomePalette(data.nnTemperature, data.nnVegetation, 0)
                    .withAquiferOreIndices(
                            data.nnBarrier,
                            data.nnFloodedness,
                            data.nnSpread,
                            data.nnLava,
                            data.nnVeinToggle,
                            data.nnVeinRidged,
                            data.nnVeinGap
                    );
            currentConfig = config;

            // Initialise the per-chunk dispatcher (uploads RouterConfig UBO)
            dispatcher.init(shaderManager, config);

            // Initialise the quart-resolution compute pipeline (bindings 14+15)
            // This shares the noise SSBOs (0–6), RouterConfig UBO (8), and MLP weights (9+10)
            // already uploaded above.
            try {
                quartCompute.init();
                LOGGER.info("ShaderSSBOManager: QuartNoiseCompute pipeline initialised");
            } catch (Exception e) {
                LOGGER.warn("ShaderSSBOManager: QuartNoiseCompute init failed — GPU quart path disabled", e);
            }

            lastUploadTime = System.currentTimeMillis();
            LOGGER.info("ShaderSSBOManager: GPU upload, shader compilation, and dispatcher init complete");
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to initialize GPU pipeline", e);
            throw new RuntimeException("GPU pipeline initialization failed", e);
        }
    }

    /**
     * Initialises the GPU biome palette from the world's {@code BiomeSource}.
     *
     * <p>Call this after {@link #uploadNoiseData} when the world's biome source is
     * available (typically in the same {@code onWorldLoad} handler, right after the
     * chunk generator is resolved).  On success the RouterConfig UBO is updated with
     * the palette entry count, enabling Pass 0 of the compute shader.
     *
     * <p>Safe to call even if reflection fails — the GPU biome pass is simply
     * left disabled (biome_palette_count stays 0).
     *
     * @param biomeSource the runtime {@code MultiNoiseBiomeSource} instance (passed as Object)
     */
    public void initBiomePalette(Object biomeSource) {
        if (!initialized || !dispatcher.isReady() || currentConfig == null) {
            LOGGER.warn("ShaderSSBOManager: initBiomePalette called before uploadNoiseData — skipping");
            return;
        }
        try {
            currentConfig = dispatcher.initBiomePalette(biomeSource, currentConfig);
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: initBiomePalette failed — GPU biome pass disabled", e);
        }
    }

    /**
     * Dispatches the compute shader for a single 16×16 chunk column.
     *
     * Sets the chunk origin in the RouterConfig UBO, dispatches one workgroup
     * (matching the shader's local_size 16×1×16 layout), then issues a
     * storage barrier so Binding 7 is readable immediately after this returns.
     *
     * @param chunkX chunk coordinate X (block origin = chunkX * 16)
     * @param chunkZ chunk coordinate Z (block origin = chunkZ * 16)
     */
    public void dispatch(int chunkX, int chunkZ) {
        if (!initialized || !shaderManager.isCompiled() || !dispatcher.isReady()) return;
        lastDispatchedChunkX = chunkX;
        lastDispatchedChunkZ = chunkZ;
        dispatcher.dispatch(chunkX, chunkZ);
    }

    /**
     * Production dispatch path: executes terrain compute and returns GPU buffer handles
     * for downstream staging without any CPU readback.
     */
    public ChunkGpuOutputs dispatchForStaging(int chunkX, int chunkZ) {
        dispatch(chunkX, chunkZ);
        return new ChunkGpuOutputs(
                chunkX,
                chunkZ,
                DENSITY_OUTPUT_BINDING,
                bufferIds[DENSITY_OUTPUT_BINDING],
                DENSITY_FLOATS,
                BLOCK_OUTPUT_BINDING,
                bufferIds[BLOCK_OUTPUT_BINDING],
                BLOCK_INT_COUNT
        );
    }

    /**
     * Pads improvedOrigins FloatBuffer for std430 alignment.
     *
     * Input: 3 floats per instance (x, y, z)
     * Output: 4 floats per instance (x, y, z, padding)
     *
     * This prevents the GPU from misaligning the next instance's X coordinate
     * with the current instance's padding slot.
     */
    private FloatBuffer padImprovedOrigins(FloatBuffer origins) {
        if (origins == null || origins.capacity() == 0) {
            LOGGER.warn("improvedOrigins buffer is empty or null");
            return FloatBuffer.allocate(0);
        }

        int elementCount = origins.capacity() / 3;
        FloatBuffer padded = FloatBuffer.allocate(elementCount * 4);

        origins.rewind();
        for (int i = 0; i < elementCount; i++) {
            padded.put(origins.get()); // x
            padded.put(origins.get()); // y
            padded.put(origins.get()); // z
            padded.put(0.0f);          // padding (unused, but required for alignment)
        }
        padded.rewind();
        return padded;
    }

    /**
     * Allocates GPU buffers on first upload.
     * Individual buffer IDs are created lazily in reinterpret_cast_obtainBufferId().
     */
    private synchronized void allocateBuffers() {
        if (initialized) {
            return;
        }

        try {
            LOGGER.info("ShaderSSBOManager: Buffer allocation system initialized (lazy allocation on first upload)");
            initialized = true;
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to initialize buffer allocation", e);
            throw new RuntimeException("Buffer allocation failed", e);
        }
    }

    /**
     * Generic buffer upload handler.
     *
     * Binds buffer, allocates storage, and uploads data with GL_STATIC_DRAW.
     * This method is designed to be safe even if called from non-render threads
     * (via RenderSystem queue if needed).
     */
    private void uploadBuffer(int bindingPoint, FloatBuffer data, String debugName) {
        if (data == null || data.capacity() == 0) {
            LOGGER.debug("Skipping empty buffer: {} (binding {})", debugName, bindingPoint);
            return;
        }

        try {
            int bufferId = reinterpret_cast_obtainBufferId(bindingPoint);
            long sizeBytes = (long) data.capacity() * Float.BYTES;

            // Bind buffer to SSBO target and upload
            glBindBuffer(GL_COPY_WRITE_BUFFER, bufferId);
            glBufferData(GL_COPY_WRITE_BUFFER, sizeBytes, data, GL_STATIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, bufferId);

            LOGGER.info("ShaderSSBOManager: Uploaded {} ({} KB) to binding {}", 
                    debugName, sizeBytes / 1024, bindingPoint);
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to upload {} to binding {}", debugName, bindingPoint, e);
            throw new RuntimeException("SSBO upload failed for " + debugName, e);
        }
    }

    /**
     * Uploads int-based buffer (permutations, octave indices, etc.)
     */
    private void uploadBuffer(int bindingPoint, IntBuffer data, String debugName) {
        if (data == null || data.capacity() == 0) {
            LOGGER.debug("Skipping empty buffer: {} (binding {})", debugName, bindingPoint);
            return;
        }

        try {
            int bufferId = reinterpret_cast_obtainBufferId(bindingPoint);
            long sizeBytes = (long) data.capacity() * Integer.BYTES;

            glBindBuffer(GL_COPY_WRITE_BUFFER, bufferId);
            glBufferData(GL_COPY_WRITE_BUFFER, sizeBytes, data, GL_STATIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, bindingPoint, bufferId);

            LOGGER.info("ShaderSSBOManager: Uploaded {} ({} KB) to binding {}", 
                    debugName, sizeBytes / 1024, bindingPoint);
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to upload {} to binding {}", debugName, bindingPoint, e);
            throw new RuntimeException("SSBO upload failed for " + debugName, e);
        }
    }

    /**
     * Allocates output density buffer (binding 7, RW by compute shader).
     * Pre-allocation prevents issues if the shader writes before we read results.
     */
    private void allocateDensityOutput() {
        try {
            // 16×384×16 floats, indexed [lx + 16*lz] * 384 + (by + 64)
            // = 98,304 floats = 393,216 bytes ≈ 384 KB
            int bufferId = reinterpret_cast_obtainBufferId(DENSITY_OUTPUT_BINDING);
            long sizeBytes = (long) DENSITY_FLOATS * Float.BYTES;

            glBindBuffer(GL_COPY_WRITE_BUFFER, bufferId);
            glBufferDataNull(GL_COPY_WRITE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, DENSITY_OUTPUT_BINDING, bufferId);

            LOGGER.info("ShaderSSBOManager: Allocated density output buffer ({} KB) at binding {}", 
                    sizeBytes / 1024, DENSITY_OUTPUT_BINDING);
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to allocate density output buffer", e);
            throw new RuntimeException("Density output allocation failed", e);
        }
    }

    /**
     * Allocates block-material output buffer (binding 11, written by pass 2 of compute shader).
     * Same element count as density output but stores {@code int} material codes.
     * Material codes: 0=AIR, 1=STONE, 2=WATER, 3=GRASS, 4=DIRT.
     */
    private void allocateBlockOutput() {
        try {
            int bufferId = reinterpret_cast_obtainBufferId(BLOCK_OUTPUT_BINDING);
            long sizeBytes = (long) BLOCK_INT_COUNT * Integer.BYTES;  // = 393,216 bytes

            glBindBuffer(GL_COPY_WRITE_BUFFER, bufferId);
            glBufferDataNull(GL_COPY_WRITE_BUFFER, sizeBytes, GL_DYNAMIC_DRAW);
            glBindBufferBase(GL_SHADER_STORAGE_BUFFER, BLOCK_OUTPUT_BINDING, bufferId);

            LOGGER.info("ShaderSSBOManager: Allocated block output buffer ({} KB) at binding {}",
                    sizeBytes / 1024, BLOCK_OUTPUT_BINDING);
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to allocate block output buffer", e);
            throw new RuntimeException("Block output allocation failed", e);
        }
    }

    /**
     * Dispatches the terrain compute shader for one chunk and reads back the full
     * density field from Binding 7.
     *
     * <p><b>Threading:</b> Must be called from a thread with an active GL context
     * (render thread in singleplayer, server-with-GL-context in test environments).
     *
     * @param chunkX chunk X coordinate
     * @param chunkZ chunk Z coordinate
     * @return FloatBuffer of {@value #DENSITY_FLOATS} floats, indexed
     *         {@code [lx + 16*lz] * 384 + (by + 64)}, or {@code null} on failure.
     */
    public FloatBuffer dispatchAndReadDebug(int chunkX, int chunkZ) {
        dispatch(chunkX, chunkZ);
        return readDensityDebug();
    }

    /** Number of floats in one dispatched chunk density field ({@value}). */
    public static int getDensityFloats() { return DENSITY_FLOATS; }

    /** Number of ints in one dispatched chunk block-material field ({@value}). */
    public static int getBlockIntCount() { return BLOCK_INT_COUNT; }

    /**
     * Reads back block-material data from the block output SSBO (binding 11).
     *
     * <p>Call after {@link #dispatch(int, int)} to retrieve the material codes written
     * by pass 2 of the compute shader.  Material codes: 0=AIR, 1=STONE, 2=WATER,
     * 3=GRASS, 4=DIRT.
     *
     * @return IntBuffer of {@value #BLOCK_INT_COUNT} ints (positioned at 0), or
     *         {@code null} on error.
     */
    public IntBuffer readBlockOutputDebug() {
        return readIntBufferDebug(BLOCK_OUTPUT_BINDING, BLOCK_INT_COUNT);
    }

    /** Read density output from binding 7 for parity/debug use only. */
    public FloatBuffer readDensityDebug() {
        return readBufferDebug(DENSITY_OUTPUT_BINDING, DENSITY_FLOATS);
    }

    /**
     * Reads back data from an SSBO into an IntBuffer.
     *
     * @param bindingPoint SSBO binding index
     * @param elementCount number of ints to read
     * @return IntBuffer positioned at 0, or {@code null} on error
     */
    public IntBuffer readIntBufferDebug(int bindingPoint, int elementCount) {
        if (bindingPoint < 0 || bindingPoint >= BUFFER_COUNT || bufferIds[bindingPoint] == 0) {
            return null;
        }
        try {
            IntBuffer result = IntBuffer.allocate(elementCount);
            org.lwjgl.opengl.GL43C.glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferIds[bindingPoint]);
            org.lwjgl.opengl.GL43C.glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, result);
            result.rewind();
            recordReadback((long) elementCount * Integer.BYTES);
            return result;
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to read int buffer from binding {}", bindingPoint, e);
            return null;
        }
    }

    /**
     * Reads back data from an SSBO into a FloatBuffer.
     * Useful for validation (e.g., checking density output at binding 7).
     *
     * @param bindingPoint The SSBO binding index (0-7)
     * @param elementCount Number of floats to read
     * @return A FloatBuffer containing the GPU-side data
     */
    public FloatBuffer readBufferDebug(int bindingPoint, int elementCount) {
        if (bindingPoint < 0 || bindingPoint >= BUFFER_COUNT || bufferIds[bindingPoint] == 0) {
            return null;
        }

        try {
            FloatBuffer result = FloatBuffer.allocate(elementCount);
            glBindBuffer(GL_SHADER_STORAGE_BUFFER, bufferIds[bindingPoint]);
            GL43C.glGetBufferSubData(GL_SHADER_STORAGE_BUFFER, 0, result);
            result.rewind();
            recordReadback((long) elementCount * Float.BYTES);
            return result;
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Failed to read buffer from binding {}", bindingPoint, e);
            return null;
        }
    }

    /**
     * Retrieves or creates a buffer ID for a binding point.
     * Uses glGenBuffers() to allocate GPU-side space on first access.
     */
    private int reinterpret_cast_obtainBufferId(int bindingPoint) {
        if (bufferIds[bindingPoint] == 0) {
            bufferIds[bindingPoint] = glGenBuffers();
            LOGGER.debug("ShaderSSBOManager: Generated buffer ID {} for binding {}", 
                    bufferIds[bindingPoint], bindingPoint);
        }
        return bufferIds[bindingPoint];
    }

    /**
     * Cleans up GPU resources.
     * Call this when the world is unloaded or the player switches servers.
     */
    public synchronized void cleanup() {
        if (!initialized) {
            return;
        }

        try {
            // Cleanup quart compute first, then dispatcher UBO, then shader program
            quartCompute.cleanup();
            dispatcher.cleanup();
            shaderManager.cleanup();

            for (int i = 0; i < bufferIds.length; i++) {
                if (bufferIds[i] != 0) {
                    glDeleteBuffers(bufferIds[i]);
                    LOGGER.debug("ShaderSSBOManager: Deleted buffer {} at binding {}", bufferIds[i], i);
                }
            }
            bufferIds = new int[BUFFER_COUNT];
            initialized = false;
            LOGGER.info("ShaderSSBOManager: GPU resources cleaned up");
        } catch (Exception e) {
            LOGGER.error("ShaderSSBOManager: Error during cleanup", e);
        }
    }

    /**
     * Checks if buffers are currently allocated and valid.
     */
    public boolean isValid() {
        return initialized && System.currentTimeMillis() - lastUploadTime < 300000; // 5 min timeout
    }

    /**
     * Returns the quart-resolution compute dispatcher.
     * Callers can use this to dispatch batched section evaluations and read back
     * {@link com.rhythmatician.lodiffusion.world.noise.SectionNoiseData} results.
     *
     * @return the QuartNoiseCompute instance (may not be ready if init failed)
     */
    public QuartNoiseCompute getQuartCompute() {
        return quartCompute;
    }

    /**
     * Returns the buffer ID for a specific binding (for debugging/inspection).
     */
    public int getBufferId(int bindingPoint) {
        if (bindingPoint < 0 || bindingPoint >= BUFFER_COUNT) {
            return 0;
        }
        return bufferIds[bindingPoint];
    }

    private void recordReadback(long bytes) {
        long now = System.currentTimeMillis();
        readbackWindowCalls++;
        long elapsed = now - readbackWindowStartMs;
        if (elapsed >= 1000) {
            readbackCallsPerSec = (readbackWindowCalls * 1000.0d) / Math.max(1L, elapsed);
            readbackWindowCalls = 0;
            readbackWindowStartMs = now;
        }

        long chunkKey = (((long) lastDispatchedChunkX) << 32) | (lastDispatchedChunkZ & 0xffffffffL);
        long chunkBytes = readbackBytesByChunk.merge(chunkKey, bytes, (a, b) -> Long.sum(a, b));

        LOGGER.info(
                "ShaderSSBOManager metrics: readback_bytes_chunk[{},{}]={} readback_calls_sec={}",
                lastDispatchedChunkX,
                lastDispatchedChunkZ,
                chunkBytes,
                String.format("%.2f", readbackCallsPerSec)
        );
    }

    // ============================================================================
    // GL Operations (LWJGL3 GL43C backend — direct calls for buffer management)
    // ============================================================================
    // Called during world load (main thread context), so no RenderSystem wrapping needed

    private void glBindBuffer(int target, int buffer) {
        GL43C.glBindBuffer(target, buffer);
    }

    private void glBufferData(int target, long size, FloatBuffer data, int usage) {
        if (data != null) {
            data.position(0);
            GL43C.glBufferData(target, data, usage);
        }
    }

    private void glBufferData(int target, long size, IntBuffer data, int usage) {
        if (data != null) {
            data.position(0);
            GL43C.glBufferData(target, data, usage);
        }
    }

    private void glBindBufferBase(int target, int index, int buffer) {
        GL43C.glBindBufferBase(target, index, buffer);
    }

    private void glBufferDataNull(int target, long size, int usage) {
        GL43C.glBufferData(target, size, usage);
    }

    private int glGenBuffers() {
        // Generate one buffer and return its ID
        int[] ids = new int[1];
        GL43C.glGenBuffers(ids);
        if (ids[0] == 0) {
            throw new RuntimeException("Failed to generate OpenGL buffer");
        }
        return ids[0];
    }

    private void glDeleteBuffers(int buffer) {
        if (buffer != 0) {
            int[] ids = { buffer };
            GL43C.glDeleteBuffers(ids);
        }
    }

    // ============================================================================
    // GL Constants (LWJGL3 GL43C)
    // ============================================================================
    // These match the OpenGL 4.3 specification values
    private static final int GL_SHADER_STORAGE_BUFFER = GL43C.GL_SHADER_STORAGE_BUFFER;
    private static final int GL_COPY_WRITE_BUFFER = GL43C.GL_COPY_WRITE_BUFFER;
    private static final int GL_STATIC_DRAW = GL43C.GL_STATIC_DRAW;
    private static final int GL_DYNAMIC_DRAW = GL43C.GL_DYNAMIC_DRAW;
}
