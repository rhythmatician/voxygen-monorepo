package com.rhythmatician.lodiffusion;

import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.util.math.ChunkPos;

/**
 * Default implementation of LODQuery that calculates LOD based on 
 * distance from player position. This provides reasonable fallback
 * behavior when Distant Horizons is not available.
 */
public class DefaultLODQuery implements LODQuery {

    @Override
    public int getLOD(ServerPlayerEntity player, ChunkPos chunkPos) {
        ChunkPos playerPos = player.getChunkPos();
        return getLOD(chunkPos.x, chunkPos.z, playerPos.x, playerPos.z);
    }

    @Override
    public int getLOD(int chunkX, int chunkZ, int playerChunkX, int playerChunkZ) {
        // Calculate Manhattan distance (simpler than Euclidean for chunked world)
        int dx = Math.abs(chunkX - playerChunkX);
        int dz = Math.abs(chunkZ - playerChunkZ);
        int distance = Math.max(dx, dz); // Chebyshev distance
        
        // Progressive LOD levels based on distance
        if (distance <= 3) {
            return 0; // High detail for nearby chunks (within 3 chunks)
        } else if (distance <= 10) {
            return 1; // Medium detail for close chunks (4-10 chunks)
        } else if (distance <= 25) {
            return 2; // Low detail for distant chunks (11-25 chunks)
        } else {
            return 3; // Very low detail for far chunks (26+ chunks)
        }
    }
    
    /**
     * Simple distance-based LOD calculation for testing/fallback scenarios.
     * This is used when no player context is available.
     */
    public int getSimpleLOD(int chunkX, int chunkZ) {
        // Assume origin as reference point for simple calculations
        return getLOD(chunkX, chunkZ, 0, 0);
    }
}
