package com.rhythmatician.lodiffusion.onnx;

import java.util.Collections;
import java.util.Map;

/**
 * Model configuration loaded from model_config.json sidecar.
 *
 * <p>Supports two contract versions:
 * <ul>
 *   <li><b>lodiffusion.v1</b> – single unified model with simple inputs
 *       ({@code x_parent}, {@code x_biome}, {@code x_height}, {@code x_lod})</li>
 *   <li><b>future rich contract</b> – {@link com.rhythmatician.lodiffusion.world.noise.tools.NoiseTap NoiseTap}-based inputs (router6, height planes, etc.)</li>
 * </ul>
 *
 * <p>When the sidecar uses the {@code lodiffusion.v1} contract, the
 * {@code normalization} and {@code optionalInputs} fields may be {@code null}.
 */
public record ModelConfig(
    /* ---- common fields ---- */
    String modelName,
    String version,
    Map<String, int[]> inputs,
    Map<String, int[]> optionalInputs,   // nullable for v1 contract
    Map<String, int[]> outputs,

    /* ---- rich-contract fields (nullable for v1) ---- */
    NormalizationConfig normalization,
    BlockPalette blockPalette,           // nullable for v1 – use blockVocabSize + blockMapping instead

    /* ---- v1 contract fields ---- */
    String contract,                      // e.g. "lodiffusion.v1"
    Map<String, Object> assumptions,      // e.g. {y_index_fixed: 12, river_patch: "zeros", ...}
    Integer biomeVocabSize,
    Integer blockVocabSize,
    Map<String, Integer> blockMapping,    // "minecraft:stone" -> 42
    Map<String, String> blockIdToName,    // "42" -> "minecraft:stone"  (keys are string-ified ints)

    /* ---- sparse-octree sidecar fields (nullable) ---- */
    Double splitThreshold                  // sigmoid decision boundary for octree expansion
) {

    // ------------------------------------------------------------------
    // Nested records (kept for future rich-contract compatibility)
    // ------------------------------------------------------------------

    public record NormalizationConfig(
        HeightNormalization heights,
        RouterNormalization router6,
        BiomeNormalization biome,
        CoordNormalization coords
    ) {}

    public record HeightNormalization(
        String type,
        int bottomY,
        int height
    ) {
        public float normalize(int rawHeight) {
            return (float) (rawHeight - bottomY) / height;
        }
        public int denormalize(float normalized) {
            return Math.round(normalized * height + bottomY);
        }
    }

    public record RouterNormalization(
        String type,
        float[] mean,
        float[] std
    ) {
        public float normalize(float rawValue, int channel) {
            if (channel < 0 || channel >= mean.length)
                throw new IllegalArgumentException("Invalid channel: " + channel);
            return (rawValue - mean[channel]) / std[channel];
        }
    }

    public record BiomeNormalization(String type) {}

    public record CoordNormalization(
        String type,
        float scale
    ) {
        public float normalize(int rawCoord) {
            return (float) Math.tanh(rawCoord / scale);
        }
    }

    public record BlockPalette(
        int size,
        String mapping
    ) {}

    // ------------------------------------------------------------------
    // Convenience helpers
    // ------------------------------------------------------------------

    /** True when the sidecar declares itself as lodiffusion.v1 (simple 4-input contract). */
    public boolean isV1Contract() {
        return "lodiffusion.v1".equals(contract);
    }

    /** Number of block types the model was trained on. */
    public int effectiveBlockVocabSize() {
        if (blockVocabSize != null) return blockVocabSize;
        if (blockPalette != null) return blockPalette.size();
        int[] bl = outputs != null ? outputs.get("block_logits") : null;
        if (bl != null && bl.length >= 2) return bl[1];
        return 0;
    }

    /** Number of biome types the model supports. */
    public int effectiveBiomeVocabSize() {
        if (biomeVocabSize != null) return biomeVocabSize;
        return 256; // safe default
    }

    public int[] getInputShape(String tensorName) {
        int[] shape = inputs != null ? inputs.get(tensorName) : null;
        if (shape == null && optionalInputs != null)
            shape = optionalInputs.get(tensorName);
        if (shape == null)
            throw new IllegalArgumentException("Unknown input tensor: " + tensorName);
        return shape.clone();
    }

    public int[] getOutputShape(String tensorName) {
        int[] shape = outputs != null ? outputs.get(tensorName) : null;
        if (shape == null)
            throw new IllegalArgumentException("Unknown output tensor: " + tensorName);
        return shape.clone();
    }

    public boolean hasInput(String tensorName) {
        if (inputs != null && inputs.containsKey(tensorName)) return true;
        return optionalInputs != null && optionalInputs.containsKey(tensorName);
    }

    public boolean isOptionalInput(String tensorName) {
        return optionalInputs != null && optionalInputs.containsKey(tensorName);
    }

    public int getOutputResolution() {
        int[] blockLogitsShape = outputs != null ? outputs.get("block_logits") : null;
        if (blockLogitsShape == null || blockLogitsShape.length != 5)
            throw new IllegalStateException("Invalid block_logits shape");
        return blockLogitsShape[2];
    }

    /**
     * Parent resolution for the unified v1 contract, read from {@code x_parent}.
     * Returns 0 when no parent input is declared (init-only model).
     */
    public int getParentResolution() {
        int[] parentShape = inputs != null ? inputs.get("x_parent") : null;
        if (parentShape == null && inputs != null)
            parentShape = inputs.get("x_parent_prev");
        if (parentShape == null) return 0;
        if (parentShape.length != 5)
            throw new IllegalStateException("Invalid parent shape");
        return parentShape[2];
    }

    /** Safe accessor – never returns null. */
    public Map<String, int[]> safeOptionalInputs() {
        return optionalInputs != null ? optionalInputs : Collections.emptyMap();
    }

    // ------------------------------------------------------------------
    // Validation
    // ------------------------------------------------------------------

    /** Validate internal consistency.  Lenient for v1 sidecars. */
    public void validate() {
        if (inputs == null || inputs.isEmpty())
            throw new IllegalStateException("ModelConfig has no inputs");

        if (outputs == null)
            throw new IllegalStateException("ModelConfig has no outputs");
        for (String req : new String[]{"block_logits"}) {
            if (!outputs.containsKey(req))
                throw new IllegalStateException("Missing required output: " + req);
        }

        // Only enforce router6 channel check when normalization is present
        if (normalization != null && normalization.router6() != null) {
            if (normalization.router6().mean().length != 6
                    || normalization.router6().std().length != 6) {
                throw new IllegalStateException("Router normalization must have 6 channels");
            }
        }

        // Block vocab size vs output shape (whichever source we have)
        int vocabSize = effectiveBlockVocabSize();
        int[] bl = outputs.get("block_logits");
        if (vocabSize > 0 && bl != null && bl.length >= 2 && bl[1] != vocabSize)
            throw new IllegalStateException("Block vocab size (" + vocabSize
                    + ") mismatches block_logits dim 1 (" + bl[1] + ")");
    }
}
