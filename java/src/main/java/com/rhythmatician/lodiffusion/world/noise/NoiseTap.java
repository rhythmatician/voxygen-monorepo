package com.rhythmatician.lodiffusion.world.noise;

import java.util.EnumSet;
import java.util.Map;

import net.minecraft.world.Heightmap;
import net.minecraft.world.biome.source.BiomeAccess;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.gen.noise.NoiseConfig;

/**
 * NoiseTap — Efficient vanilla noise signal capture at native API granularities.
 *
 * All methods cache at the API's native resolution:
 * - Biomes: 4×4×4 lattice per chunk section
 * - Router fields (DensityFunction): full 16×16×16 block grid for the chunk
 * - Heightmaps: 16×16 per-heightmap type
 *
 * No upsampling is done here; LOD models can downsample on ingest if desired.
 * This ensures we fetch exactly the vanilla world-gen signals with zero naive
 * upsampling on our side.
 */
public interface NoiseTap {

    /**
     * Capture all requested noise signals for this chunk.
     *
     * @param whichRouterFields NoiseRouter fields to sample at 16×16×16 resolution
     * @param whichHeightmaps Heightmap types to capture at 16×16 resolution
     * @return immutable cache view for this chunk at native API resolutions
     */
    Cache captureAll(EnumSet<RouterField> whichRouterFields,
                     EnumSet<Heightmap.Type> whichHeightmaps);

    /**
     * Capture all requested noise signals for a specific 16-block Y section.
     *
     * <p>The router fields are sampled at {@code sectionAnchorY .. sectionAnchorY + 15}.
     * Use this overload when you need router data at a particular section (e.g., sea level).
     *
     * @param whichRouterFields NoiseRouter fields to sample at 16×16×16 resolution
     * @param whichHeightmaps   Heightmap types to capture at 16×16 resolution
     * @param sectionAnchorY    Y coordinate of the bottom block of the 16-block section to sample
     * @return immutable cache view for this chunk at native API resolutions
     */
    Cache captureAll(EnumSet<RouterField> whichRouterFields,
                     EnumSet<Heightmap.Type> whichHeightmaps,
                     int sectionAnchorY);

    /**
     * Router fields we can sample (exactly those exposed by NoiseRouter).
     * These correspond to the 15 DensityFunction fields in Yarn 1.21.4+ NoiseRouter.
     */
    enum RouterField {
        // Tier A - Surface & Climate Features (fast, essential)
        TEMPERATURE, VEGETATION, CONTINENTS, EROSION, DEPTH, RIDGES,

        // Tier B - Fluid & Environmental Features (moderate cost)
        FLUID_FLOODEDNESS, FLUID_SPREAD, LAVA, BARRIER,

        // Tier C - 3D Density Features (expensive but valuable for caves)
        INITIAL_DENSITY_NO_JAG, FINAL_DENSITY,

        // Tier D - Vein & Ore Features (very expensive, consider post-processing)
        VEIN_TOGGLE, VEIN_RIDGED, VEIN_GAP
    }

    /**
     * Immutable cache payload at native API shapes.
     *
     * Shapes:
     *  - router: float[channels][16][16][16] - per RouterField at block resolution
     *  - biomes: int[4][4][4] - biome registry IDs at 4×4×4 lattice granularity
     *  - heightmaps: short[heightmapType][16][16] - Y coordinates in block coords
     */
    record Cache(
        // NoiseRouter samples (per selected RouterField) at 16×16×16 block resolution
        Map<RouterField, float[][][]> router, // [16][16][16] each

        // 4×4×4 biome lattice (post-19w36a storage granularity)
        int[][][] biomes4, // [4][4][4] -> biome registry ID

        // heightmaps requested (each 16×16)
        Map<Heightmap.Type, short[][]> heightmaps16,

        // convenience metadata for positional features
        int chunkMinY, int chunkHeight, int chunkX, int chunkZ, long seed
    ) {
        
        /**
         * Get router field data with bounds checking.
         */
        public float[][][] getRouterField(RouterField field) {
            float[][][] data = router.get(field);
            if (data == null) {
                throw new IllegalArgumentException("Router field " + field + " was not captured");
            }
            return data;
        }
        
        /**
         * Get heightmap data with bounds checking.
         */
        public short[][] getHeightmap(Heightmap.Type type) {
            short[][] data = heightmaps16.get(type);
            if (data == null) {
                throw new IllegalArgumentException("Heightmap type " + type + " was not captured");
            }
            return data;
        }
        
        /**
         * Get biome ID at 4×4×4 lattice coordinates with bounds checking.
         */
        public int getBiomeId(int qx, int qz, int qy) {
            if (qx < 0 || qx >= 4 || qz < 0 || qz >= 4 || qy < 0 || qy >= 4) {
                throw new IndexOutOfBoundsException("Biome lattice coordinates out of bounds: " + qx + "," + qz + "," + qy);
            }
            return biomes4[qx][qz][qy];
        }

        /**
         * Get total number of captured router fields.
         */
        public int getRouterFieldCount() {
            return router.size();
        }

        /**
         * Get total memory footprint estimate in bytes.
         */
        public long getMemoryFootprint() {
            long bytes = 0;

            // Router fields: float[16][16][16] = 16KB each
            bytes += router.size() * 16 * 16 * 16 * Float.BYTES;

            // Biomes: int[4][4][4] = 256 bytes
            bytes += 4 * 4 * 4 * Integer.BYTES;

            // Heightmaps: short[16][16] = 512 bytes each
            bytes += heightmaps16.size() * 16 * 16 * Short.BYTES;

            return bytes;
        }
    }

    /**
     * Factory that binds the tap to a chunk context.
     *
     * @param chunk Chunk to sample from
     * @param noiseConfig NoiseConfig containing the NoiseRouter
     * @param biomeAccess BiomeAccess for biome sampling
     * @param worldSeed World seed for positional features
     * @return NoiseTap bound to this chunk context
     */
    static NoiseTap bind(Chunk chunk,
                         NoiseConfig noiseConfig,
                         BiomeAccess biomeAccess,
                         long worldSeed) {
        return new NoiseTapImpl(chunk, noiseConfig, biomeAccess, worldSeed);
    }
    
    /**
     * Convenience method to get default router fields for different performance tiers.
     */
    static EnumSet<RouterField> getTierFields(PerformanceTier tier) {
        return switch (tier) {
            case CORE -> EnumSet.of(
                RouterField.TEMPERATURE, RouterField.VEGETATION, RouterField.CONTINENTS,
                RouterField.EROSION, RouterField.DEPTH, RouterField.RIDGES
            );
            case EXTENDED -> EnumSet.of(
                RouterField.TEMPERATURE, RouterField.VEGETATION, RouterField.CONTINENTS,
                RouterField.EROSION, RouterField.DEPTH, RouterField.RIDGES,
                RouterField.FLUID_FLOODEDNESS, RouterField.FLUID_SPREAD, RouterField.LAVA, RouterField.BARRIER
            );
            case CAVE_AWARE -> EnumSet.of(
                RouterField.TEMPERATURE, RouterField.VEGETATION, RouterField.CONTINENTS,
                RouterField.EROSION, RouterField.DEPTH, RouterField.RIDGES,
                RouterField.FLUID_FLOODEDNESS, RouterField.FLUID_SPREAD, RouterField.LAVA, RouterField.BARRIER,
                RouterField.INITIAL_DENSITY_NO_JAG, RouterField.FINAL_DENSITY
            );
            case FULL -> EnumSet.allOf(RouterField.class);
        };
    }

    /**
     * Convenience method to get default heightmap types for terrain generation.
     */
    static EnumSet<Heightmap.Type> getDefaultHeightmaps() {
        return EnumSet.of(
            Heightmap.Type.WORLD_SURFACE_WG,     // Primary terrain surface
            Heightmap.Type.OCEAN_FLOOR_WG,       // Ocean floor elevation
            Heightmap.Type.MOTION_BLOCKING       // Top solid block for collision
        );
    }

    /**
     * Performance tiers for router field selection based on our noise analysis.
     */
    enum PerformanceTier {
        CORE,        // ~15ms - Essential terrain (Tier A)
        EXTENDED,    // ~32ms - + Environmental features (Tier A + B)
        CAVE_AWARE,  // ~66ms - + Underground density (Tier A + B + C)
        FULL         // ~137ms - All fields (too expensive for real-time)
    }
}
