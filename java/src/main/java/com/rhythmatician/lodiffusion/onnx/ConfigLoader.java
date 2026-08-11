package com.rhythmatician.lodiffusion.onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.reflect.TypeToken;

/**
 * Loads {@link ModelConfig} from a JSON sidecar produced alongside ONNX models.
 *
 * <p>Handles both the <b>lodiffusion.v1</b> format (produced by VoxelTree's
 * {@code export_lod.py}) and the richer multi-model format used by the
 * progressive pipeline.
 */
public final class ConfigLoader {

    private static final Logger LOGGER = LoggerFactory.getLogger(ConfigLoader.class);
    private static final Gson GSON = new GsonBuilder().create();

    private ConfigLoader() {}

    /**
     * Load and validate a model config from the given JSON path.
     * 
     * @param jsonPath Path to the config JSON file
     * @return Parsed ModelConfig
     * @throws IOException If the config is missing the required 'contract' field
     *         or if parsing fails
     */
    public static ModelConfig load(Path jsonPath) throws IOException {
        String json = Files.readString(jsonPath);
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();

        // Validate contract field exists to prevent silent mislabeling
        if (!root.has("contract")) {
            throw new IOException("Config file " + jsonPath 
                    + " is missing required 'contract' field; cannot determine version. "
                    + "Ensure export_octree.py includes the contract field in the sidecar JSON.");
        }

        String contract = root.get("contract").getAsString();
        
        // Detect contract version
        if ("lodiffusion.v1".equals(contract)) {
            return loadV1(root, jsonPath);
        } else if (contract.startsWith("lodiffusion.v")) {
            return loadRich(root, jsonPath);
        } else {
            throw new IOException("Config file " + jsonPath 
                    + " has unknown contract version: " + contract + ". "
                    + "Expected 'lodiffusion.v1' or 'lodiffusion.v5.octree'.");
        }
    }

    // ------------------------------------------------------------------
    // lodiffusion.v1 sidecar  (VoxelTree export_lod.py)
    // ------------------------------------------------------------------

    private static ModelConfig loadV1(JsonObject root, Path jsonPath) throws IOException {
        String version = getString(root, "version", "unknown");
        String contract = getString(root, "contract", "lodiffusion.v1");

        // inputs  – e.g. {x_parent:[1,1,8,8,8], x_biome:[1,256,16,16,1], ...}
        Map<String, int[]> inputs = parseShapeMap(root.getAsJsonObject("inputs"));

        // outputs – e.g. {block_logits:[1,1104,8,8,8]} (air = class 0 in unified softmax)
        Map<String, int[]> outputs = parseShapeMap(root.getAsJsonObject("outputs"));

        // assumptions – opaque key/value bag
        Map<String, Object> assumptions = null;
        if (root.has("assumptions")) {
            assumptions = GSON.fromJson(root.get("assumptions"),
                    new TypeToken<Map<String, Object>>(){}.getType());
        }

        Integer biomeVocab = getInt(root, "biome_vocab_size");
        Integer blockVocab = getInt(root, "block_vocab_size");

        // block_mapping: { "minecraft:air": 0, ... }
        Map<String, Integer> blockMapping = null;
        if (root.has("block_mapping")) {
            blockMapping = GSON.fromJson(root.get("block_mapping"),
                    new TypeToken<Map<String, Integer>>(){}.getType());
        }

        // block_id_to_name: { "0": "minecraft:air", ... }
        Map<String, String> blockIdToName = null;
        if (root.has("block_id_to_name")) {
            blockIdToName = GSON.fromJson(root.get("block_id_to_name"),
                    new TypeToken<Map<String, String>>(){}.getType());
        }

        ModelConfig config = new ModelConfig(
            /* modelName */       getString(root, "model_name", "voxeltree-v1"),
            /* version */         version,
            /* inputs */          inputs,
            /* optionalInputs */  Collections.emptyMap(),
            /* outputs */         outputs,
            /* normalization */   null,   // v1 has no normalization block
            /* blockPalette */    null,   // v1 embeds blockMapping directly
            /* contract */        contract,
            /* assumptions */     assumptions,
            /* biomeVocabSize */  biomeVocab,
            /* blockVocabSize */  blockVocab,
            /* blockMapping */    blockMapping,
            /* blockIdToName */   blockIdToName,
            /* splitThreshold */  null    // v1 does not carry split threshold
        );
        config.validate();
        LOGGER.info("Loaded v1 model config from " + jsonPath
                + "  blocks=" + config.effectiveBlockVocabSize()
                + "  biomes=" + config.effectiveBiomeVocabSize());
        return config;
    }

    // ------------------------------------------------------------------
    // Rich / progressive pipeline sidecar
    // ------------------------------------------------------------------

    private static ModelConfig loadRich(JsonObject root, Path jsonPath) throws IOException {
        // Normalize snake_case top-level keys
        renameKey(root, "model_name", "modelName");
        renameKey(root, "optional_inputs", "optionalInputs");
        renameKey(root, "block_palette", "blockPalette");
        renameKey(root, "biome_vocab_size", "biomeVocabSize");
        renameKey(root, "block_vocab_size", "blockVocabSize");
        renameKey(root, "block_mapping", "blockMapping");
        renameKey(root, "block_id_to_name", "blockIdToName");

        // Resolve symbolic "N_blocks" dim in block_logits
        try {
            JsonObject paletteObj = root.has("blockPalette")
                    ? root.getAsJsonObject("blockPalette")
                    : root.has("block_palette") ? root.getAsJsonObject("block_palette") : null;

            if (paletteObj != null && root.has("outputs")) {
                int paletteSize = paletteObj.get("size").getAsInt();
                JsonObject outputs = root.getAsJsonObject("outputs");
                if (outputs.has("block_logits")) {
                    JsonArray bl = outputs.getAsJsonArray("block_logits");
                    if (bl.size() >= 2) {
                        JsonElement dim1 = bl.get(1);
                        if (dim1.isJsonPrimitive() && dim1.getAsJsonPrimitive().isString()) {
                            String s = dim1.getAsString();
                            if ("N_blocks".equalsIgnoreCase(s) || "n_blocks".equalsIgnoreCase(s)) {
                                bl.set(1, GSON.toJsonTree(paletteSize));
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            LOGGER.debug("Config preprocessing warning for " + jsonPath + ": " + e.getMessage());
        }

        ModelConfig config = GSON.fromJson(root, ModelConfig.class);
        if (config == null) {  // Defensive: should not happen, but GSON might fail
            throw new IOException("Failed to parse config: " + jsonPath);
        }
        // Sparse-root uses a different output layout (split/label tensors) and
        // does not include the usual "block_logits" output expected by the
        // standard octree pipeline.  Skip strict validation for this contract.
        String contract = config.contract();
        boolean isSparseOctree = contract != null && contract.endsWith(".sparse_octree");
        if (!isSparseOctree) {
            config.validate();
        }
        LOGGER.info("Loaded model config: " + config.modelName() + " from " + jsonPath);
        return config;
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void renameKey(JsonObject obj, String from, String to) {
        if (obj.has(from) && !obj.has(to)) {
            JsonElement v = obj.remove(from);
            obj.add(to, v);
        }
    }

    private static Map<String, int[]> parseShapeMap(JsonObject obj) {
        if (obj == null) return Collections.emptyMap();
        Map<String, int[]> result = new HashMap<>();
        for (String key : obj.keySet()) {
            JsonArray arr = obj.getAsJsonArray(key);
            int[] shape = new int[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                shape[i] = arr.get(i).getAsInt();
            }
            result.put(key, shape);
        }
        return result;
    }

    private static String getString(JsonObject obj, String key, String def) {
        return obj.has(key) ? obj.get(key).getAsString() : def;
    }

    private static Integer getInt(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsInt() : null;
    }
}
