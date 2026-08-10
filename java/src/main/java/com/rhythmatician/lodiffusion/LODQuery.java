package com.rhythmatician.lodiffusion;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;

/**
 * Interface for querying Level of Detail (LOD) for chunks.
 * Implementations can provide different strategies for determining
 * appropriate LOD levels based on player position and chunk distance.
 */
public interface LODQuery {
    
    /**
     * Gets the Level of Detail (LOD) for a chunk relative to a player.
     * 
     * @param player The player to calculate distance from
     * @param chunkPos The position of the chunk to calculate LOD for
     * @return LOD level (0 = highest detail, higher numbers = lower detail)
     */
    int getLOD(ServerPlayerEntity player, ChunkPos chunkPos);
    
    /**
     * Gets the Level of Detail (LOD) for a chunk at specific coordinates
     * relative to a player position.
     * 
     * @param chunkX Chunk X coordinate
     * @param chunkZ Chunk Z coordinate  
     * @param playerChunkX Player's chunk X coordinate
     * @param playerChunkZ Player's chunk Z coordinate
     * @return LOD level (0 = highest detail, higher numbers = lower detail)
     */
    int getLOD(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ);
}
