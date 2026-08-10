package com.rhythmatician.lodiffusion.voxy;

import java.lang.reflect.Method;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.state.property.Properties;
import net.minecraft.util.Identifier;
import net.minecraft.world.biome.Biome;

/**
 * Ultra-fast fallback terrain generator for when no ONNX models are available.
 *
 * <p>Fills sections with a simple heightmap-based algorithm:
 * <ul>
 *   <li>Below y=0 → deepslate</li>
 *   <li>y=0 to (surface − 3) → stone</li>
 *   <li>Top 3 solid blocks → biome-dependent (sand, red sand, gravel, snow block, podzol, mycelium, or grass/dirt)</li>
 *   <li>Above surface, below sea level → water</li>
 *   <li>Above surface, at or above sea level → air</li>
 * </ul>
 *
 * <p>This bypasses the entire ONNX pipeline and {@link VoxySectionWriter},
 * composing 64-bit Voxy voxels directly via {@link VoxyCompat#composeVoxel}
 * for maximum throughput.  With no compute bottleneck, performance is
 * limited only by Voxy I/O ({@code insertUpdate}).
 *
 * <p>The generator is stateless — all mutable state (block IDs, biome IDs)
 * is held externally in {@link FallbackBlockIds} and passed per call.
 */
public final class HeightmapFallbackGenerator {

    private static final Logger LOGGER = LoggerFactory.getLogger(HeightmapFallbackGenerator.class);

    /** Minecraft sea level in block Y coordinates. */
    static final int SEA_LEVEL = 63;

    /** Default light value: full sky light, no block light → 0x0F. */
    private static final int DEFAULT_LIGHT = 0x0F;

    private HeightmapFallbackGenerator() {}

    // ------------------------------------------------------------------ //
    //  Block ID resolution
    // ------------------------------------------------------------------ //

    /**
     * Pre-resolved Voxy block IDs for the 7 block types used by the fallback.
     * Resolved once at startup via {@link #resolveBlockIds(Object)}.
     */
    /**
     * Surface material category used to select the top 3 solid blocks per column.
     * Determined once per column from the biome's canonical index.
     */
    enum SurfaceType {
        /** Grass block over dirt — most temperate biomes. */
        GRASS,
        /** Sand for all 3 layers — deserts, beaches, warm/lukewarm ocean floors. */
        SAND,
        /** Red sand — badlands variants. */
        RED_SAND,
        /** Gravel — cold/regular ocean floors, stony shores, gravelly hills. */
        GRAVEL,
        /** Stone for all 3 layers — windswept hills and other rocky formations. */
        STONE,
        /** Snow layer on top of snowy grass_block over dirt — frozen peaks, snowy plains, taigas, etc. */
        SNOW,
        /** Podzol over dirt — old-growth pine and spruce taigas. */
        PODZOL,
        /** Mycelium over dirt — mushroom fields. */
        MYCELIUM
    }

    public record FallbackBlockIds(
        int air,
        int stone,
        int deepslate,
        int dirt,
        int grassBlock,
        int sand,
        int water,
        int redSand,
        int gravel,
        int snowyGrassBlock,
        int snowLayer,
        int podzol,
        int mycelium
    ) {}

    /**
     * Resolve Voxy internal block IDs for the 7 block types needed by the
     * fallback generator.  Uses the same {@code Mapper.getIdForBlockState}
     * reflection pattern as {@link VoxyBlockMapper#build}.
     *
     * @param voxyMapper the Voxy Mapper object (from {@link VoxyCompat#getMapper})
     * @return pre-resolved block IDs
     * @throws RuntimeException if reflection fails
     */
    public static FallbackBlockIds resolveBlockIds(Object voxyMapper) {
        try {
            Method getIdMethod = voxyMapper.getClass().getMethod("getIdForBlockState",
                    BlockState.class);

            int air            = (int) getIdMethod.invoke(voxyMapper, Blocks.AIR.getDefaultState());
            int stone          = (int) getIdMethod.invoke(voxyMapper, Blocks.STONE.getDefaultState());
            int deepslate      = (int) getIdMethod.invoke(voxyMapper, Blocks.DEEPSLATE.getDefaultState());
            int dirt           = (int) getIdMethod.invoke(voxyMapper, Blocks.DIRT.getDefaultState());
            int grassBlock     = (int) getIdMethod.invoke(voxyMapper, Blocks.GRASS_BLOCK.getDefaultState());
            int sand           = (int) getIdMethod.invoke(voxyMapper, Blocks.SAND.getDefaultState());
            int water          = (int) getIdMethod.invoke(voxyMapper, Blocks.WATER.getDefaultState());
            int redSand        = (int) getIdMethod.invoke(voxyMapper, Blocks.RED_SAND.getDefaultState());
            int gravel         = (int) getIdMethod.invoke(voxyMapper, Blocks.GRAVEL.getDefaultState());
            int snowyGrassBlock = (int) getIdMethod.invoke(voxyMapper,
                    Blocks.GRASS_BLOCK.getDefaultState().with(Properties.SNOWY, true));
            int snowLayer      = (int) getIdMethod.invoke(voxyMapper, Blocks.SNOW.getDefaultState());
            int podzol         = (int) getIdMethod.invoke(voxyMapper, Blocks.PODZOL.getDefaultState());
            int mycelium       = (int) getIdMethod.invoke(voxyMapper, Blocks.MYCELIUM.getDefaultState());

            LOGGER.info("Fallback block IDs resolved: air=" + air + " stone=" + stone
                    + " deepslate=" + deepslate + " dirt=" + dirt + " grass=" + grassBlock
                    + " sand=" + sand + " water=" + water + " redSand=" + redSand
                    + " gravel=" + gravel + " snowyGrass=" + snowyGrassBlock
                    + " snowLayer=" + snowLayer
                    + " podzol=" + podzol + " mycelium=" + mycelium);

            return new FallbackBlockIds(air, stone, deepslate, dirt, grassBlock, sand, water,
                    redSand, gravel, snowyGrassBlock, snowLayer, podzol, mycelium);
        } catch (Exception e) {
            throw new RuntimeException("Failed to resolve fallback block IDs from Voxy Mapper", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Biome ID resolution (shared with VoxyBlockMapper)
    // ------------------------------------------------------------------ //

    /**
     * Resolve canonical biome IDs → Voxy biome IDs.
     *
     * <p>For each of the 54 canonical overworld biomes, looks up the
     * {@link RegistryEntry} from the game's biome registry and calls Voxy's
     * {@code Mapper.getIdForBiome(RegistryEntry)} via reflection.  This
     * proactively registers any biome Voxy hasn't seen yet.
     *
     * @param voxyMapper     the Voxy Mapper object
     * @param biomeRegistry  the game's biome registry
     * @return int[54] mapping canonical biome index → Voxy biome ID
     */
    public static int[] resolveBiomeMappings(Object voxyMapper,
                                              Registry<Biome> biomeRegistry) {
        int[] map = new int[BiomeMapping.size()];
        try {
            // Find Mapper.getIdForBiome via reflection (generic-erased signature)
            Method getIdForBiome = null;
            for (Method m : voxyMapper.getClass().getMethods()) {
                if (m.getName().equals("getIdForBiome") && m.getParameterCount() == 1) {
                    getIdForBiome = m;
                    break;
                }
            }

            if (getIdForBiome == null) {
                LOGGER.warn("Mapper.getIdForBiome not found — " +
                        "falling back to getBiomeEntries (biome tints may be wrong)");
                return resolveBiomeMappingsLegacy(voxyMapper);
            }

            int resolved = 0;
            for (int i = 0; i < BiomeMapping.size(); i++) {
                String name = BiomeMapping.getCanonicalName(i);
                if (name == null) continue;

                Optional<RegistryEntry.Reference<Biome>> entry =
                        biomeRegistry.getEntry(Identifier.of(name));
                if (entry.isPresent()) {
                    int voxyId = (int) getIdForBiome.invoke(voxyMapper, entry.get());
                    map[i] = voxyId;
                    resolved++;
                }
            }

            LOGGER.info("Fallback biome mappings: proactively registered " + resolved
                    + "/" + BiomeMapping.size() + " canonical biomes with Voxy");

        } catch (Exception e) {
            LOGGER.warn("getIdForBiome failed: " + e.getMessage()
                    + " — falling back to getBiomeEntries");
            return resolveBiomeMappingsLegacy(voxyMapper);
        }
        return map;
    }

    /**
     * Legacy biome resolution via {@code getBiomeEntries()}.  Only returns IDs
     * for biomes Voxy has already seen.
     */
    private static int[] resolveBiomeMappingsLegacy(Object voxyMapper) {
        int[] map = new int[BiomeMapping.size()];
        try {
            Method getBiomeEntries = voxyMapper.getClass().getMethod("getBiomeEntries");
            Object[] entries = (Object[]) getBiomeEntries.invoke(voxyMapper);

            if (entries == null || entries.length == 0) {
                LOGGER.warn("No biome entries from Voxy — all biomes map to 0");
                return map;
            }

            int resolved = 0;
            for (Object entry : entries) {
                String biomeName = (String) entry.getClass().getField("biome").get(entry);
                int voxyId = entry.getClass().getField("id").getInt(entry);

                int canonicalId = BiomeMapping.toCanonicalId(biomeName);
                if (canonicalId != BiomeMapping.UNKNOWN_BIOME_ID) {
                    map[canonicalId] = voxyId;
                    resolved++;
                }
            }

            LOGGER.info("Fallback biome mappings (legacy): resolved " + resolved + "/"
                    + BiomeMapping.size() + " from " + entries.length + " Voxy entries");

        } catch (Exception e) {
            LOGGER.warn("Legacy biome resolution also failed: " + e.getMessage());
        }
        return map;
    }

    // ------------------------------------------------------------------ //
    //  Section generation
    // ------------------------------------------------------------------ //

    /**
     * Generate a single 16³ section filled according to the heightmap rules.
     *
     * <p>This method creates a {@code VoxelizedSection}, fills its L0 data,
     * computes the mip pyramid, and is ready for {@link VoxyCompat#insertUpdate}.
     *
     * <p>When {@code oceanFloorHm} is provided (non-null), it is used as the
     * real solid ground surface for columns where water is present.  Water is
     * placed between the ocean/river floor and the water surface ({@code rawHm}).
     * The top 3 solid blocks are placed relative to the floor, not the water
     * surface, so riverbeds get sand/dirt/grass correctly.
     *
     * @param sectionX      section X coordinate (blockX / 16)
     * @param sectionY      section Y coordinate (blockY / 16)
     * @param sectionZ      section Z coordinate (blockZ / 16)
     * @param rawHm         [16][16] surface heightmap (water surface) in block Y, indexed [x][z]
     * @param oceanFloorHm  [16][16] ocean/river floor heightmap in block Y, or null
     * @param biomeIdx      [16][16] canonical biome indices, indexed [x][z]
     * @param biomeVoxyIds  [16][16] Voxy biome IDs, indexed [x][z]
     * @param blockIds      pre-resolved Voxy block IDs
     * @param voxyMapper    Voxy Mapper for mip computation
     * @return the filled and mipped {@code VoxelizedSection}, or {@code null}
     *         if the section is entirely air (skip insertion)
     */
    public static Object generateSection(int sectionX, int sectionY, int sectionZ,
                                          float[][] rawHm, float[][] oceanFloorHm,
                                          int[][] biomeIdx,
                                          int[][] biomeVoxyIds,
                                          FallbackBlockIds blockIds,
                                          Object voxyMapper) {
        int baseY = sectionY * 16;

        // Quick check: if the entire section is above the max heightmap AND
        // above sea level, it's all air — skip it.
        if (baseY >= SEA_LEVEL) {
            boolean allAboveSurface = true;
            for (int lx = 0; lx < 16 && allAboveSurface; lx++) {
                for (int lz = 0; lz < 16 && allAboveSurface; lz++) {
                    if (baseY < rawHm[lx][lz]) {
                        allAboveSurface = false;
                    }
                }
            }
            if (allAboveSurface) return null;
        }

        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, sectionX, sectionY, sectionZ);
        long[] data = VoxyCompat.getSectionData(section);

        int nonAir = 0;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                float waterSurfaceY = rawHm[lx][lz];
                // If ocean floor data is available, use it as the solid ground.
                // Otherwise, fall back to the surface heightmap (no water distinction).
                float groundY = oceanFloorHm != null ? oceanFloorHm[lx][lz] : waterSurfaceY;
                int waterSurfaceBlockY = (int) Math.floor(waterSurfaceY);
                int groundBlockY = (int) Math.floor(groundY);

                int canonBiome = biomeIdx[lx][lz];
                int voxyBiome = biomeVoxyIds[lx][lz];

                SurfaceType surfaceType = surfaceTypeForBiome(canonBiome);

                for (int ly = 0; ly < 16; ly++) {
                    int worldY = baseY + ly;
                    int idx = VoxyCompat.l0Index(lx, ly, lz);

                    int blockId = pickBlockId(worldY, groundBlockY,
                            waterSurfaceBlockY, surfaceType, blockIds);

                    data[idx] = VoxyCompat.composeVoxel(blockId, voxyBiome, DEFAULT_LIGHT);

                    if (blockId != blockIds.air()) {
                        nonAir++;
                    }
                }
            }
        }

        if (nonAir == 0) return null;

        VoxyCompat.setNonAirCount(section, nonAir);
        VoxyCompat.mipSection(section, voxyMapper);
        return section;
    }

    /**
     * Map a canonical biome index to a {@link SurfaceType} for the top 3 solid blocks.
     * Package-private for testing.
     *
     * <p>Biome indices follow the alphabetical ordering in {@link BiomeMapping}.
     *
     * @param canonicalBiomeIdx biome index from {@link BiomeMapping}
     * @return the surface material category for this biome
     */
    static SurfaceType surfaceTypeForBiome(int canonicalBiomeIdx) {
        return switch (canonicalBiomeIdx) {
            // Sand — deserts, beaches, warm/lukewarm ocean floors, snowy beach
            case 2, 10, 12, 24, 38, 48 -> SurfaceType.SAND;
            // beach(2), deep_lukewarm_ocean(10), desert(12),
            // lukewarm_ocean(24), snowy_beach(38), warm_ocean(48)

            // Red sand — badlands variants
            case 0, 14, 53 -> SurfaceType.RED_SAND;
            // badlands(0), eroded_badlands(14), wooded_badlands(53)

            // Gravel — cold/regular ocean floors, stony shores, gravelly hills
            case 5, 7, 9, 11, 17, 29, 43, 44, 50 -> SurfaceType.GRAVEL;
            // cold_ocean(5), deep_cold_ocean(7), deep_frozen_ocean(9),
            // deep_ocean(11), frozen_ocean(17), ocean(29),
            // stony_peaks(43), stony_shore(44), windswept_gravelly_hills(50)

            // Stone — rocky windswept hills
            case 51 -> SurfaceType.STONE;
            // windswept_hills(51)

            // Snow block — frozen and snowy biomes
            case 18, 19, 20, 21, 22, 39, 40, 41 -> SurfaceType.SNOW;
            // frozen_peaks(18), frozen_river(19), grove(20), ice_spikes(21),
            // jagged_peaks(22), snowy_plains(39), snowy_slopes(40), snowy_taiga(41)

            // Podzol — old-growth taigas
            case 31, 32 -> SurfaceType.PODZOL;
            // old_growth_pine_taiga(31), old_growth_spruce_taiga(32)

            // Mycelium — mushroom fields
            case 28 -> SurfaceType.MYCELIUM;
            // mushroom_fields(28)

            // Grass/dirt — all remaining biomes
            default -> SurfaceType.GRASS;
        };
    }

    /**
     * Determine the Voxy block ID for a voxel at a given world Y, considering
     * both the solid ground surface and the water surface.
     *
     * <p>When {@code groundBlockY < waterSurfaceBlockY}, water exists in that
     * column (river, ocean, lake).  Voxels between the ground and the water
     * surface are filled with water.
     *
     * <p>Package-private for testing.
     *
     * @param worldY              absolute block Y coordinate
     * @param groundBlockY        the solid ground height (floor of ocean floor or surface heightmap)
     * @param waterSurfaceBlockY  the water surface height (floor of WORLD_SURFACE_WG heightmap)
     * @param surfaceType         surface material category for the top 3 solid blocks
     * @param blockIds            pre-resolved block IDs
     * @return the Voxy block ID to use
     */
    static int pickBlockId(int worldY, int groundBlockY, int waterSurfaceBlockY,
                            SurfaceType surfaceType, FallbackBlockIds blockIds) {
        if (worldY >= groundBlockY) {
            // Above solid ground — could be snow layer, water, or air
            if (worldY < waterSurfaceBlockY) {
                return blockIds.water();
            }
            if (worldY >= SEA_LEVEL) {
                // Dry air zone — place a snow layer directly on snowy terrain
                if (worldY == groundBlockY && surfaceType == SurfaceType.SNOW) {
                    return blockIds.snowLayer();
                }
                return blockIds.air();
            }
            return blockIds.water();
        } else if (worldY >= groundBlockY - 3) {
            // Top 3 solid blocks — material depends on surface type.
            // Soft surface materials (grass, podzol, mycelium, snowy grass) only appear
            // on dry columns; underwater tops are always dirt.
            int depth = groundBlockY - 1 - worldY; // 0=topmost, 1=second, 2=third
            boolean underwater = groundBlockY < waterSurfaceBlockY;
            return switch (surfaceType) {
                case SAND     -> blockIds.sand();
                case RED_SAND -> blockIds.redSand();
                case GRAVEL   -> blockIds.gravel();
                case STONE    -> blockIds.stone();
                case SNOW     -> (depth == 0 && !underwater) ? blockIds.snowyGrassBlock() : blockIds.dirt();
                case PODZOL   -> (depth == 0 && !underwater) ? blockIds.podzol()    : blockIds.dirt();
                case MYCELIUM -> (depth == 0 && !underwater) ? blockIds.mycelium()  : blockIds.dirt();
                default       -> (depth == 0 && !underwater) ? blockIds.grassBlock() : blockIds.dirt();
            };
        } else if (worldY < 0) {
            return blockIds.deepslate();
        } else {
            return blockIds.stone();
        }
    }
}
