package com.rhythmatician.lodiffusion.onnx;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

/**
 * Translates between model vocabulary indices and Minecraft {@link BlockState}s.
 *
 * <p>The model was trained on 1 104 block types identified by their Minecraft
 * registry name (e.g. {@code "minecraft:stone"}).  At runtime we resolve each
 * name to a live {@link BlockState} (defaulting to the block's default state).
 *
 * <p>Index 0 is always {@code minecraft:air}.
 */
public @SuppressWarnings("null")
final class BlockVocabulary {

    private static final Logger LOGGER = LoggerFactory.getLogger(BlockVocabulary.class);

    private final int vocabSize;
    private final BlockState[] indexToState;           // [vocabSize]
    private final Map<Identifier, Integer> stateToIndex;
    private final String[] indexToName;                // for debug / Voxy mapper

    private BlockVocabulary(int vocabSize, BlockState[] indexToState,
                            Map<Identifier, Integer> stateToIndex, String[] indexToName) {
        this.vocabSize = vocabSize;
        this.indexToState = indexToState;
        this.stateToIndex = stateToIndex;
        this.indexToName = indexToName;
    }

    /**
     * Build a vocabulary from the sidecar's {@code block_mapping}
     * ({@code "minecraft:stone" → 42}).
     */
    public static BlockVocabulary fromMapping(Map<String, Integer> blockMapping) {
        if (blockMapping == null || blockMapping.isEmpty()) {
            throw new IllegalArgumentException("block_mapping is null or empty");
        }

        int maxIdx = blockMapping.values().stream().mapToInt(Integer::intValue).max().orElse(0);
        int size = maxIdx + 1;

        BlockState[] idx2state = new BlockState[size];
        String[] idx2name = new String[size];
        Map<Identifier, Integer> state2idx = new HashMap<>();

        // Fill with air as a safe default
        BlockState air = Blocks.AIR.getDefaultState();
        for (int i = 0; i < size; i++) {
            idx2state[i] = air;
            idx2name[i] = "minecraft:air";
        }

        int resolved = 0;
        int missing = 0;

        for (Map.Entry<String, Integer> e : blockMapping.entrySet()) {
            String name = e.getKey();
            int idx = e.getValue();
            if (idx < 0 || idx >= size) continue;

            Identifier id = Identifier.of(name);
            Block block = Registries.BLOCK.get(id);

            if (block == Blocks.AIR && !"minecraft:air".equals(name)) {
                // Registry didn't find it – keep air default, log once
                missing++;
            } else {
                idx2state[idx] = block.getDefaultState();
                state2idx.put(id, idx);
                resolved++;
            }
            idx2name[idx] = name;
        }

        LOGGER.info("BlockVocabulary: " + resolved + " resolved, " + missing
                + " missing (defaulted to air), vocab_size=" + size);

        return new BlockVocabulary(size, idx2state, Collections.unmodifiableMap(state2idx), idx2name);
    }

    /** Build from a {@link ModelConfig} (prefers embedded blockMapping, then falls back to blockPalette). */
    public static BlockVocabulary fromConfig(ModelConfig config) {
        if (config.blockMapping() != null && !config.blockMapping().isEmpty()) {
            return fromMapping(config.blockMapping());
        }
        throw new IllegalStateException("ModelConfig has no block_mapping – cannot build BlockVocabulary");
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    public int size() { return vocabSize; }

    /** Get the {@link BlockState} for a model output index. */
    public BlockState getState(int index) {
        if (index < 0 || index >= vocabSize) return Blocks.AIR.getDefaultState();
        return indexToState[index];
    }

    /** Get the Minecraft registry name for a model output index. */
    public String getName(int index) {
        if (index < 0 || index >= vocabSize) return "minecraft:air";
        return indexToName[index];
    }

    /** Get the model index for a given block identifier, or -1 if unknown. */
    public int getIndex(Identifier id) {
        Integer idx = stateToIndex.get(id);
        return idx != null ? idx : -1;
    }

    /** True when index 0 is air (sanity check). */
    public boolean isAirZero() {
        return indexToState[0].isOf(Blocks.AIR);
    }
}
