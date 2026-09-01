package io.github.lodiffusion.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.lwjgl.opengl.GL20C;
import org.lwjgl.opengl.GL43C;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.stream.Collectors;

/**
 * Manages the compilation and linking of the GPU terrain compute pipeline.
 *
 * This class handles:
 * 1. Loading GLSL sources from resources.
 * 2. Compiling individual shader stages (noise primitives + compute kernel).
 * 3. Linking into a single GL program.
 * 4. Error reporting for GLSL compilation failures.
 */
public class ShaderProgramManager {
    private static final Logger LOGGER = LogManager.getLogger();
    private static final String SHADER_PATH = "/assets/lodiffusion/shaders/worldgen/";

    private int programId = 0;
    private boolean compiled = false;

    /**
     * Compiles and links the terrain compute program.
     * Must be called on a thread with an active GL context.
     */
    public synchronized void compile() {
        if (compiled) return;

        LOGGER.info("ShaderProgramManager: Initializing terrain compute pipeline...");

        try {
            // Load shader sources
            String improvedNoise = loadSource("improved_noise.glsl");
            String perlinNoise = loadSource("perlin_noise.glsl");
            String normalNoise = loadSource("normal_noise.glsl");
            String terrainKernel = loadSource("terrain_compute.comp");

            // Concatenate sources (Injected order: primitives first, kernel last)
            // This ensures the kernel has access to the noise functions.
            StringBuilder fullSource = new StringBuilder();
            fullSource.append("#version 450 core\n");
            fullSource.append(improvedNoise).append("\n");
            fullSource.append(perlinNoise).append("\n");
            fullSource.append(normalNoise).append("\n");
            fullSource.append(terrainKernel);

            // Create and compile compute shader
            int shaderId = GL20C.glCreateShader(GL43C.GL_COMPUTE_SHADER);
            GL20C.glShaderSource(shaderId, fullSource.toString());
            GL20C.glCompileShader(shaderId);

            // Check compilation status
            if (GL20C.glGetShaderi(shaderId, GL20C.GL_COMPILE_STATUS) == GL20C.GL_FALSE) {
                String log = GL20C.glGetShaderInfoLog(shaderId);
                LOGGER.error("GLSL Compilation Failed:\n{}", log);
                GL20C.glDeleteShader(shaderId);
                throw new RuntimeException("Compute shader compilation failed");
            }

            // Link program
            programId = GL20C.glCreateProgram();
            GL20C.glAttachShader(programId, shaderId);
            GL20C.glLinkProgram(programId);

            // Check link status
            if (GL20C.glGetProgrami(programId, GL20C.GL_LINK_STATUS) == GL20C.GL_FALSE) {
                String log = GL20C.glGetProgramInfoLog(programId);
                LOGGER.error("GLSL Program Link Failed:\n{}", log);
                GL20C.glDeleteProgram(programId);
                GL20C.glDeleteShader(shaderId);
                throw new RuntimeException("Compute program link failed");
            }

            // Cleanup shader stage after linking
            GL20C.glDetachShader(programId, shaderId);
            GL20C.glDeleteShader(shaderId);

            compiled = true;
            LOGGER.info("ShaderProgramManager: Terrain compute pipeline compiled successfully (ID: {})", programId);
        } catch (Exception e) {
            LOGGER.error("ShaderProgramManager: Critical failure during shader initialization", e);
            throw new RuntimeException("Shader initialization failed", e);
        }
    }

    /**
     * Loads a GLSL file from the mod's assets.
     */
    private String loadSource(String filename) {
        String path = SHADER_PATH + filename;
        try (InputStream is = getClass().getResourceAsStream(path)) {
            if (is == null) {
                throw new RuntimeException("Could not find shader resource: " + path);
            }
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8))) {
                return reader.lines().collect(Collectors.joining("\n"));
            }
        } catch (Exception e) {
            LOGGER.error("Failed to load GLSL source: {}", path, e);
            throw new RuntimeException(e);
        }
    }

    /**
     * Binds the compute program for dispatch.
     */
    public void use() {
        if (!compiled) compile();
        GL20C.glUseProgram(programId);
    }

    /**
     * Deletes the GL program.
     */
    public synchronized void cleanup() {
        if (programId != 0) {
            GL20C.glDeleteProgram(programId);
            programId = 0;
            compiled = false;
            LOGGER.info("ShaderProgramManager: Cleaned up GL compute program");
        }
    }

    public int getProgramId() {
        return programId;
    }

    public boolean isCompiled() {
        return compiled;
    }
}
