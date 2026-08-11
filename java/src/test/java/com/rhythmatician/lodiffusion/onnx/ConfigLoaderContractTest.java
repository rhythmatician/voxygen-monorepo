package com.rhythmatician.lodiffusion.onnx;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies that {@link ConfigLoader} correctly handles sparse-octree sidecar
 * configs across contract versions (v6, v7, future) without tripping the
 * {@code block_logits} validation gate meant for the standard octree pipeline.
 *
 * <p>Regression test for: <i>"Missing required output: block_logits"</i> when
 * loading a v7 sparse octree config.
 */
@Tag("ci")
class ConfigLoaderContractTest {

    // -- Minimal valid sparse-octree sidecar (v7 contract) ----------------

    private static final String V7_SPARSE_OCTREE_CONFIG = """
            {
              "modelName": "sparse_octree",
              "version": "lodiffusion.v7.sparse_octree",
              "contract": "lodiffusion.v7.sparse_octree",
              "blockVocabSize": 2048,
              "blockMapping": {},
              "splitThreshold": 0.6,
              "inputs": {
                "noise_3d":            [1, 15, 4, 2, 4],
                "biome_ids":           [1, 4, 2, 4],
                "heightmap_surface":   [1, 16, 16],
                "heightmap_ocean_floor": [1, 16, 16]
              },
              "outputs": {
                "split_L4": [1, 1],
                "label_L4": [1, 1, 2048],
                "split_L3": [1, 8],
                "label_L3": [1, 8, 2048],
                "split_L2": [1, 64],
                "label_L2": [1, 64, 2048],
                "split_L1": [1, 512],
                "label_L1": [1, 512, 2048],
                "split_L0": [1, 4096],
                "label_L0": [1, 4096, 2048]
              }
            }
            """;

    // -- Same shape, v6 contract (original that already worked) -----------

    private static final String V6_SPARSE_OCTREE_CONFIG = """
            {
              "modelName": "sparse_root",
              "version": "lodiffusion.v6.sparse_octree",
              "contract": "lodiffusion.v6.sparse_octree",
              "blockVocabSize": 1040,
              "blockMapping": {},
              "splitThreshold": 0.5,
              "inputs": {
                "noise_3d":            [1, 15, 4, 2, 4],
                "biome_ids":           [1, 4, 2, 4],
                "heightmap_surface":   [1, 16, 16],
                "heightmap_ocean_floor": [1, 16, 16]
              },
              "outputs": {
                "split_L4": [1, 1],
                "label_L4": [1, 1, 1040],
                "split_L3": [1, 8],
                "label_L3": [1, 8, 1040],
                "split_L2": [1, 64],
                "label_L2": [1, 64, 1040],
                "split_L1": [1, 512],
                "label_L1": [1, 512, 1040],
                "split_L0": [1, 4096],
                "label_L0": [1, 4096, 1040]
              }
            }
            """;

    // -- Standard octree pipeline config (requires block_logits) ----------

    private static final String STANDARD_OCTREE_CONFIG = """
            {
              "modelName": "octree_init",
              "version": "lodiffusion.v5.octree",
              "contract": "lodiffusion.v5.octree",
              "blockVocabSize": 256,
              "inputs": {
                "noise": [1, 6, 4, 4, 4]
              },
              "outputs": {
                "block_logits": [1, 256, 4, 4, 4]
              }
            }
            """;

    // =====================================================================
    // Tests
    // =====================================================================

    @Test
    void v7SparseOctreeConfig_loadsWithoutBlockLogitsError(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("sparse_octree_config.json");
        Files.writeString(cfg, V7_SPARSE_OCTREE_CONFIG);

        ModelConfig config = ConfigLoader.load(cfg);

        assertEquals("lodiffusion.v7.sparse_octree", config.contract());
        assertEquals("sparse_octree", config.modelName());
        assertEquals(2048, config.effectiveBlockVocabSize());
        assertNotNull(config.outputs());
        assertTrue(config.outputs().containsKey("split_L4"), "Should have split_L4 output");
        assertTrue(config.outputs().containsKey("label_L0"), "Should have label_L0 output");
        assertFalse(config.outputs().containsKey("block_logits"),
                "Sparse octree should NOT have block_logits");
    }

    @Test
    void v6SparseOctreeConfig_loadsSuccessfully(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("sparse_octree_config.json");
        Files.writeString(cfg, V6_SPARSE_OCTREE_CONFIG);

        ModelConfig config = ConfigLoader.load(cfg);

        assertEquals("lodiffusion.v6.sparse_octree", config.contract());
        assertEquals(1040, config.effectiveBlockVocabSize());
    }

    @Test
    void standardOctreeConfig_requiresBlockLogits(@TempDir Path tmp) throws IOException {
        // Standard pipeline MUST enforce the block_logits check
        Path cfg = tmp.resolve("octree_init_config.json");
        String missingBlockLogits = """
                {
                  "modelName": "octree_init",
                  "version": "lodiffusion.v5.octree",
                  "contract": "lodiffusion.v5.octree",
                  "inputs": {
                    "noise": [1, 6, 4, 4, 4]
                  },
                  "outputs": {
                    "some_other_tensor": [1, 256, 4, 4, 4]
                  }
                }
                """;
        Files.writeString(cfg, missingBlockLogits);

        IllegalStateException ex = assertThrows(IllegalStateException.class,
                () -> ConfigLoader.load(cfg));
        assertTrue(ex.getMessage().contains("block_logits"),
                "Error should mention missing block_logits, got: " + ex.getMessage());
    }

    @Test
    void standardOctreeConfig_loadsWhenBlockLogitsPresent(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("octree_init_config.json");
        Files.writeString(cfg, STANDARD_OCTREE_CONFIG);

        ModelConfig config = ConfigLoader.load(cfg);

        assertEquals("lodiffusion.v5.octree", config.contract());
        assertTrue(config.outputs().containsKey("block_logits"));
    }

    @Test
    void missingContractField_throwsIOException(@TempDir Path tmp) throws IOException {
        Path cfg = tmp.resolve("bad_config.json");
        Files.writeString(cfg, """
                {
                  "modelName": "test",
                  "inputs": { "x": [1, 2] },
                  "outputs": { "y": [1, 3] }
                }
                """);

        IOException ex = assertThrows(IOException.class, () -> ConfigLoader.load(cfg));
        assertTrue(ex.getMessage().contains("contract"),
                "Error should mention missing contract field");
    }

    @Test
    void futureSparseOctreeContract_alsoBypasses(@TempDir Path tmp) throws IOException {
        // Any .sparse_octree suffix should bypass block_logits validation
        String futureConfig = V7_SPARSE_OCTREE_CONFIG
                .replace("lodiffusion.v7.sparse_octree", "lodiffusion.v99.sparse_octree");
        Path cfg = tmp.resolve("sparse_octree_config.json");
        Files.writeString(cfg, futureConfig);

        ModelConfig config = ConfigLoader.load(cfg);

        assertEquals("lodiffusion.v99.sparse_octree", config.contract());
        assertNotNull(config.outputs());
    }
}
