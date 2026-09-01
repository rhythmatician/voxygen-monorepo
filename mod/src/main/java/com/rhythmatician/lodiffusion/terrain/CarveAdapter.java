package com.rhythmatician.lodiffusion.terrain;

import net.minecraft.world.chunk.Chunk;

/**
 * Abstraction for invoking vanilla carve() at LOD0.
 * Implementations can wire into Minecraft's chunk generator as needed.
 */
public interface CarveAdapter {
    void carve(Chunk chunk);

    CarveAdapter NOOP = chunk -> {};
}
