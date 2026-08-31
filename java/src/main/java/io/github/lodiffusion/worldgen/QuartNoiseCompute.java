package io.github.lodiffusion.worldgen;

import com.rhythmatician.lodiffusion.world.noise.SectionNoiseData;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL15C;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL30C;
import org.lwjgl.opengl.GL43C;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * GPU dispatcher for the quart-resolution NoiseRouter field evaluator.
 *
 * <p>Compiles and manages the {@code quart_noise_compute.comp} compute shader,
 * which evaluates all 15 {@code RouterField} values at the Overworld
 * {@code 4×2×4} quart resolution for batched sections.  This is the data source
 * that will back {@code GpuNoiseRouterSampler} once the async dispatch queue
 * is in place.  The output is {@code float[N × 480]} matching
 * {@link SectionNoiseData#FLAT_LENGTH}.
 *
 * <h3>GPU resource layout</h3>
 * <ul>
 *   <li>Bindings 0–6: Noise data SSBOs (shared with terrain_compute.comp, managed by
 *       {@link ShaderSSBOManager})</li>
 *   <li>Binding 8: RouterConfig UBO (shared, 144 bytes, managed by
 *       {@link TerrainComputeDispatcher})</li>
 *   <li>Binding 9: TerrainShaperMLP weights SSBO (shared)</li>
 *   <li>Binding 10: TerrainShaperMLP config UBO (shared)</li>
 *   <li>Binding 14: Section origins SSBO (owned by this class)</li>
 *   <li>Binding 15: Quart noise output SSBO (owned by this class)</li>
 * </ul>
 *
 * <h3>Workgroup layout</h3>
 * {@code layout(local_size_x=4, local_size_y=2, local_size_z=4)} — 32 threads per
 * section (Overworld 4×2×4).  {@code glDispatchCompute(N, 1, 1)} for a batch of N sections.
 *
 * <h3>Lifecycle</h3>
 * <pre>
 *   QuartNoiseCompute quart = new QuartNoiseCompute();
 *   quart.init();
 *   // per batch:
 *   quart.dispatch(sectionOrigins, count);
 *   SectionNoiseData[] results = quart.readBack(sectionOrigins, count);
 *   // on world unload:
 *   quart.cleanup();
 * </pre>
 */
public class QuartNoiseCompute {
    private static final Logger LOGGER = LogManager.getLogger();

    private static final String SHADER_PATH = "/assets/lodiffusion/shaders/worldgen/";
    private static final String INCLUDE_CUT = "// --- INCLUDE CUT ---";

    // SSBO bindings owned by this class
    private static final int SECTION_ORIGINS_BINDING = 14;
    private static final int QUART_OUTPUT_BINDING = 15;

    // SectionNoiseData constants — Overworld lattice: 4×2×4 = 32 cells, 15×32 = 480 floats
    private static final int FLOATS_PER_SECTION = SectionNoiseData.FLAT_LENGTH;  // 480

    /** Maximum sections per single dispatch.  Limits GPU memory and latency. */
    private static final int MAX_BATCH_SIZE = 256;

    // GL handles
    private int programId = 0;
    private int sectionOriginsSSBO = 0;
    private int quartOutputSSBO = 0;
    private boolean ready = false;

    /** Current capacity of the output SSBO (in sections). */
    private int allocatedBatchCapacity = 0;

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    /**
     * Compiles the quart compute shader and allocates owned SSBOs.
     * Must be called on a thread with an active GL context, after the shared
     * noise SSBOs (0–6) and RouterConfig UBO (8) have been uploaded.
     */
    public void init() {
        if (ready) return;
        LOGGER.info("QuartNoiseCompute: Initialising quart-resolution compute pipeline...");

        try {
            compileShader();
            allocateSSBOs(MAX_BATCH_SIZE);
            ready = true;
            LOGGER.info("QuartNoiseCompute: Pipeline ready (program={}, maxBatch={})",
                    programId, MAX_BATCH_SIZE);
        } catch (Exception e) {
            LOGGER.error("QuartNoiseCompute: Initialisation failed", e);
            cleanup();
            throw new RuntimeException("Quart compute pipeline initialisation failed", e);
        }
    }

    // -------------------------------------------------------------------------
    // Dispatch
    // -------------------------------------------------------------------------

    /**
     * Dispatches the quart compute shader for a batch of sections.
     *
     * <p>Section origins must be in <b>block coordinates</b>:
     * {@code (sectionX * 16, sectionY * 16, sectionZ * 16)}.
     *
     * <p>After this returns, the output is ready in SSBO binding 15.
     * Call {@link #readBack(int[][], int)} to materialise the results.
     *
     * @param sectionOrigins array of int[3] — {blockX, blockY, blockZ} per section
     * @param count          number of sections to dispatch (1..MAX_BATCH_SIZE)
     * @throws IllegalStateException if not initialised
     * @throws IllegalArgumentException if count exceeds MAX_BATCH_SIZE
     */
    public void dispatch(int[][] sectionOrigins, int count) {
        if (!ready) {
            throw new IllegalStateException("QuartNoiseCompute.dispatch called before init()");
        }
        if (count <= 0) return;
        if (count > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Batch size " + count + " exceeds maximum " + MAX_BATCH_SIZE);
        }

        // Ensure SSBO capacity
        if (count > allocatedBatchCapacity) {
            reallocateSSBOs(count);
        }

        // Upload section origins: N × 4 ints (x, y, z, pad)
        ByteBuffer originBuf = ByteBuffer.allocateDirect(count * 4 * 4)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < count; i++) {
            originBuf.putInt(sectionOrigins[i][0]);  // blockX
            originBuf.putInt(sectionOrigins[i][1]);  // blockY
            originBuf.putInt(sectionOrigins[i][2]);  // blockZ
            originBuf.putInt(0);                      // _pad
        }
        originBuf.flip();

        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sectionOriginsSSBO);
        GL15C.glBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, originBuf);

        // Bind owned SSBOs
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, SECTION_ORIGINS_BINDING, sectionOriginsSSBO);
        GL30C.glBindBufferBase(GL43C.GL_SHADER_STORAGE_BUFFER, QUART_OUTPUT_BINDING, quartOutputSSBO);

        // Execute
        GL20C.glUseProgram(programId);
        GL43C.glDispatchCompute(count, 1, 1);
        GL43C.glMemoryBarrier(GL43C.GL_SHADER_STORAGE_BARRIER_BIT);
    }

    // -------------------------------------------------------------------------
    // Readback
    // -------------------------------------------------------------------------

    /**
     * Reads back the quart noise output from the GPU and constructs
     * {@link SectionNoiseData} objects.
     *
     * <p>Must be called after {@link #dispatch(int[][], int)} on the same GL thread.
     *
     * @param sectionOrigins the same origins array passed to dispatch (for section coordinates)
     * @param count          number of sections to read
     * @return array of SectionNoiseData, one per section
     */
    public SectionNoiseData[] readBack(int[][] sectionOrigins, int count) {
        if (!ready) {
            throw new IllegalStateException("QuartNoiseCompute.readBack called before init()");
        }

        int totalFloats = count * FLOATS_PER_SECTION;
        ByteBuffer raw = ByteBuffer.allocateDirect(totalFloats * 4)
                .order(ByteOrder.nativeOrder());

        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, quartOutputSSBO);
        GL15C.glGetBufferSubData(GL43C.GL_SHADER_STORAGE_BUFFER, 0, raw);
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);

        FloatBuffer fb = raw.asFloatBuffer();
        SectionNoiseData[] results = new SectionNoiseData[count];

        for (int i = 0; i < count; i++) {
            float[] flat = new float[FLOATS_PER_SECTION];
            fb.get(flat);

            // Convert block origins back to section/chunk coordinates
            int sectionX = sectionOrigins[i][0] >> 4;   // blockX / 16
            int sectionY = sectionOrigins[i][1] >> 4;   // blockY / 16
            int sectionZ = sectionOrigins[i][2] >> 4;   // blockZ / 16

            results[i] = new SectionNoiseData(flat, sectionX, sectionY, sectionZ);
        }

        return results;
    }

    /**
     * Convenience: dispatch + readBack in one call.
     *
     * @param sectionOrigins array of int[3] — {blockX, blockY, blockZ} per section
     * @param count          number of sections
     * @return array of SectionNoiseData
     */
    public SectionNoiseData[] compute(int[][] sectionOrigins, int count) {
        dispatch(sectionOrigins, count);
        return readBack(sectionOrigins, count);
    }

    // -------------------------------------------------------------------------
    // Cleanup
    // -------------------------------------------------------------------------

    /**
     * Releases all GL resources owned by this class.
     * Safe to call multiple times.
     */
    public void cleanup() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
            programId = 0;
        }
        if (sectionOriginsSSBO != 0) {
            GL15C.glDeleteBuffers(new int[]{sectionOriginsSSBO});
            sectionOriginsSSBO = 0;
        }
        if (quartOutputSSBO != 0) {
            GL15C.glDeleteBuffers(new int[]{quartOutputSSBO});
            quartOutputSSBO = 0;
        }
        allocatedBatchCapacity = 0;
        ready = false;
        LOGGER.info("QuartNoiseCompute: Cleaned up GL resources");
    }

    public boolean isReady() {
        return ready;
    }

    public int getProgramId() {
        return programId;
    }

    // -------------------------------------------------------------------------
    // Shader compilation
    // -------------------------------------------------------------------------

    /**
     * Compiles the quart_noise_compute.comp shader with concatenated noise includes.
     *
     * <p>Source assembly order:
     * <ol>
     *   <li>{@code quart_noise_compute.comp} — portion before {@code // --- INCLUDE CUT ---}</li>
     *   <li>{@code improved_noise.glsl}</li>
     *   <li>{@code perlin_noise.glsl}</li>
     *   <li>{@code normal_noise.glsl}</li>
     *   <li>{@code quart_noise_compute.comp} — portion after {@code // --- INCLUDE CUT ---}</li>
     * </ol>
     */
    private void compileShader() {
        String quartSource = loadSource("quart_noise_compute.comp");
        String improvedNoise = loadSource("improved_noise.glsl");
        String perlinNoise = loadSource("perlin_noise.glsl");
        String normalNoise = loadSource("normal_noise.glsl");

        // Split quart source at the INCLUDE CUT marker
        int cutIdx = quartSource.indexOf(INCLUDE_CUT);
        if (cutIdx < 0) {
            throw new RuntimeException("quart_noise_compute.comp missing '" + INCLUDE_CUT + "' marker");
        }

        // Find the end of the line containing the marker
        int afterCut = quartSource.indexOf('\n', cutIdx);
        if (afterCut < 0) afterCut = quartSource.length();
        else afterCut += 1;  // skip the newline

        String beforeCut = quartSource.substring(0, cutIdx);
        String afterCutStr = quartSource.substring(afterCut);

        // Assemble: [before cut] + noise primitives + [after cut]
        StringBuilder fullSource = new StringBuilder(beforeCut.length() + afterCutStr.length()
                + improvedNoise.length() + perlinNoise.length() + normalNoise.length() + 100);
        fullSource.append(beforeCut);
        fullSource.append("\n// ---- BEGIN improved_noise.glsl ----\n");
        fullSource.append(improvedNoise);
        fullSource.append("\n// ---- END improved_noise.glsl ----\n");
        fullSource.append("\n// ---- BEGIN perlin_noise.glsl ----\n");
        fullSource.append(perlinNoise);
        fullSource.append("\n// ---- END perlin_noise.glsl ----\n");
        fullSource.append("\n// ---- BEGIN normal_noise.glsl ----\n");
        fullSource.append(normalNoise);
        fullSource.append("\n// ---- END normal_noise.glsl ----\n");
        fullSource.append(afterCutStr);

        // Create and compile compute shader
        int shaderId = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
        GL20C.glShaderSource(shaderId, fullSource.toString());
        GL20C.glCompileShader(shaderId);

        if (GL20C.glGetShaderi(shaderId, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetShaderInfoLog(shaderId);
            LOGGER.error("Quart shader compilation failed:\n{}", log);
            GL20C.glDeleteShader(shaderId);
            throw new RuntimeException("Quart compute shader compilation failed:\n" + log);
        }

        // Link program
        programId = GL20C.glCreateProgram();
        GL20C.glAttachShader(programId, shaderId);
        GL20C.glLinkProgram(programId);

        if (GL20C.glGetProgrami(programId, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
            String log = GL20C.glGetProgramInfoLog(programId);
            LOGGER.error("Quart shader link failed:\n{}", log);
            GL20C.glDeleteProgram(programId);
            GL20C.glDeleteShader(shaderId);
            programId = 0;
            throw new RuntimeException("Quart compute program link failed:\n" + log);
        }

        // Detach and delete shader stage after linking
        GL20C.glDetachShader(programId, shaderId);
        GL20C.glDeleteShader(shaderId);

        LOGGER.info("QuartNoiseCompute: Shader compiled and linked (program={})", programId);
    }

    private String loadSource(String filename) {
        String path = SHADER_PATH + filename;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Could not find shader resource: " + path);
            }
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            LOGGER.error("Failed to load GLSL source: {}", path, e);
            throw new RuntimeException("Failed to load GLSL source: " + path, e);
        }
    }

    // -------------------------------------------------------------------------
    // SSBO management
    // -------------------------------------------------------------------------

    private void allocateSSBOs(int batchCapacity) {
        // Section origins: N × 4 ints
        sectionOriginsSSBO = genBuffer();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, sectionOriginsSSBO);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) batchCapacity * 4 * 4,   // 4 ints × 4 bytes each × N sections
                GL15C.GL_DYNAMIC_DRAW);

        // Quart noise output: N × 480 floats (Overworld 4×2×4 lattice)
        quartOutputSSBO = genBuffer();
        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, quartOutputSSBO);
        GL15C.glBufferData(GL43C.GL_SHADER_STORAGE_BUFFER,
                (long) batchCapacity * FLOATS_PER_SECTION * 4,  // 480 floats × 4 bytes × N
                GL15C.GL_DYNAMIC_READ);

        GL15C.glBindBuffer(GL43C.GL_SHADER_STORAGE_BUFFER, 0);
        allocatedBatchCapacity = batchCapacity;

        LOGGER.debug("QuartNoiseCompute: Allocated SSBOs for {} sections (origins={}B, output={}B)",
                batchCapacity,
                (long) batchCapacity * 16,
                (long) batchCapacity * FLOATS_PER_SECTION * 4);
    }

    private void reallocateSSBOs(int newCapacity) {
        LOGGER.debug("QuartNoiseCompute: Reallocating SSBOs {} → {} sections",
                allocatedBatchCapacity, newCapacity);

        // Delete old
        if (sectionOriginsSSBO != 0) GL15C.glDeleteBuffers(new int[]{sectionOriginsSSBO});
        if (quartOutputSSBO != 0) GL15C.glDeleteBuffers(new int[]{quartOutputSSBO});

        allocateSSBOs(newCapacity);
    }

    private static int genBuffer() {
        int[] ids = new int[1];
        GL15C.glGenBuffers(ids);
        if (ids[0] == 0) {
            throw new RuntimeException("QuartNoiseCompute: glGenBuffers returned 0");
        }
        return ids[0];
    }
}
