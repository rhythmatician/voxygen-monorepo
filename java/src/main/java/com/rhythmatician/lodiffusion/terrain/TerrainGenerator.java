package com.rhythmatician.lodiffusion.terrain;

import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.chunk.Chunk;

/**
 * Abstraction for terrain generation algorithms.
 * Allows runtime switching between ONNX-based and vanilla-like generation.
 */
public interface TerrainGenerator {
    /**
     * Generate terrain for a chunk.
     * @param pos Chunk position
     * @param chunk Chunk access for reading/writing blocks
     * @param seed Random seed for deterministic generation
     */
    void generateChunk(ChunkPos pos, Chunk chunk, long seed);
}
