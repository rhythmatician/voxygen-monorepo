package com.rhythmatician.lodiffusion.gpu;

import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.FloatBuffer;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GL43;

/**
 * Manages OpenGL SSBO and UBO for the TerrainShaperMLP neural network.
 *
 * <p>The terrain shaper MLP has been trained to replace the vanilla Minecraft nested cubic splines
 * for computing (offset, factor, jaggedness) from (continents, erosion, ridges, weirdness).
 *
 * <p>This class loads the pre-trained weights and binds them to GPU memory for shader access.
 */
@SuppressWarnings("unused")
public class TerrainShaperMlpSsbo implements AutoCloseable {

  private static final String WEIGHTS_PATH = "assets/lodiffusion/models/terrain_shaper_weights.bin";
  private static final int WEIGHTS_SSBO_BINDING = 9;
  private static final int CONFIG_UBO_BINDING = 10;

  // Network architecture constants
  private static final int INPUT_SIZE = 4;
  private static final int HIDDEN_SIZE = 32;
  private static final int OUTPUT_SIZE = 3;

  // Total weights: W1(128) + b1(32) + W2(1024) + b2(32) + W3(96) + b3(3) = 1315
  private static final int TOTAL_WEIGHTS = 1315;
  private static final int WEIGHT_BUFFER_SIZE = TOTAL_WEIGHTS * 4; // bytes

  private int weightsSSBO = -1;
  private int configUBO = -1;

  /**
   * Initialize SSBO and UBO from the pre-trained weights file.
   *
   * @throws RuntimeException if weights cannot be loaded
   */
  public TerrainShaperMlpSsbo() {
    loadWeightsAndCreateBuffers();
  }

  /**
   * Load weights from binary file and create GPU buffers.
   */
  private void loadWeightsAndCreateBuffers() {
    // Load binary weights from resources
    float[] weightData = loadWeightsFromResource(WEIGHTS_PATH);
    if (weightData.length != TOTAL_WEIGHTS) {
      throw new RuntimeException(
          "Invalid weight file: expected "
              + TOTAL_WEIGHTS
              + " floats, got "
              + weightData.length);
    }

    // Create SSBO for weights
    weightsSSBO = GL15.glGenBuffers();
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, weightsSSBO);
    FloatBuffer weightBuffer = ByteBuffer.allocateDirect(WEIGHT_BUFFER_SIZE).asFloatBuffer();
    weightBuffer.put(weightData).flip();
    GL15.glBufferData(GL43.GL_SHADER_STORAGE_BUFFER, weightBuffer, GL15.GL_STATIC_DRAW);
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);

    // Create UBO for configuration
    configUBO = GL15.glGenBuffers();
    GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, configUBO);

    // Config structure: should match shader layout
    // layout(std140) uniform ShapeMlpConfig {
    //   ivec3 layer1_size;      // (32, 4, pad)
    //   ivec3 layer2_size;      // (32, 32, pad)
    //   ivec3 layer3_size;      // (3, 32, pad)
    //   ivec4 offsets;          // [w1, b1, w2, b2]
    // };
    ByteBuffer configBuffer = ByteBuffer.allocateDirect(64); // 4 x 16-byte aligned ivec4
    configBuffer.putInt(HIDDEN_SIZE).putInt(INPUT_SIZE).putInt(0); // layer1_size
    configBuffer.putInt(HIDDEN_SIZE).putInt(HIDDEN_SIZE).putInt(0); // layer2_size
    configBuffer.putInt(OUTPUT_SIZE).putInt(HIDDEN_SIZE).putInt(0); // layer3_size

    // Compute offsets into weight buffer (in floats)
    int w1_offset = 0;
    int b1_offset = w1_offset + INPUT_SIZE * HIDDEN_SIZE; // 128
    int w2_offset = b1_offset + HIDDEN_SIZE; // 160
    int b2_offset = w2_offset + HIDDEN_SIZE * HIDDEN_SIZE; // 1184
    int w3_offset = b2_offset + HIDDEN_SIZE; // 1216

    configBuffer.putInt(w1_offset).putInt(b1_offset).putInt(w2_offset).putInt(b2_offset); // offsets

    configBuffer.flip();
    GL15.glBufferData(GL43.GL_UNIFORM_BUFFER, configBuffer, GL15.GL_STATIC_DRAW);
    GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, 0);
  }

  /**
   * Load weights from the binary resource file.
   *
   * @param resourcePath The classpath resource path (e.g., "assets/lodiffusion/models/...")
   * @return Array of float weights
   */
  private float[] loadWeightsFromResource(String resourcePath) {
    try (InputStream is = getClass().getClassLoader().getResourceAsStream(resourcePath)) {
      if (is == null) {
        throw new RuntimeException("Resource not found: " + resourcePath);
      }

      byte[] bytes = is.readAllBytes();
      if (bytes.length != WEIGHT_BUFFER_SIZE) {
        throw new RuntimeException(
            "Invalid weight file size: expected "
                + WEIGHT_BUFFER_SIZE
                + " bytes, got "
                + bytes.length);
      }

      float[] weights = new float[TOTAL_WEIGHTS];
      ByteBuffer buffer = ByteBuffer.wrap(bytes);
      for (int i = 0; i < TOTAL_WEIGHTS; i++) {
        weights[i] = buffer.getFloat();
      }

      return weights;
    } catch (Exception e) {
      throw new RuntimeException("Failed to load terrain shaper weights", e);
    }
  }

  /**
   * Bind SSBO and UBO to their shader binding points. Call this before dispatching the compute
   * shader.
   */
  public void bind() {
    if (weightsSSBO < 0 || configUBO < 0) {
      throw new RuntimeException("SSBO/UBO not initialized");
    }

    // Bind SSBO
    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, weightsSSBO);
    GL30.glBindBufferBase(GL43.GL_SHADER_STORAGE_BUFFER, WEIGHTS_SSBO_BINDING, weightsSSBO);

    // Bind UBO
    GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, configUBO);
    GL30.glBindBufferBase(GL43.GL_UNIFORM_BUFFER, CONFIG_UBO_BINDING, configUBO);

    GL15.glBindBuffer(GL43.GL_SHADER_STORAGE_BUFFER, 0);
    GL15.glBindBuffer(GL43.GL_UNIFORM_BUFFER, 0);
  }

  /**
   * Clean up GPU resources.
   */
  public void cleanup() {
    if (weightsSSBO >= 0) {
      GL15.glDeleteBuffers(weightsSSBO);
      weightsSSBO = -1;
    }
    if (configUBO >= 0) {
      GL15.glDeleteBuffers(configUBO);
      configUBO = -1;
    }
  }

  @Override
  public void close() {
    cleanup();
  }
}
