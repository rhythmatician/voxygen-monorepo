package com.rhythmatician.lodiffusion.voxy;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import static org.junit.jupiter.api.Assertions.*;
import com.rhythmatician.voxygen.worldgen.heightmap.HeightmapFallbackGenerator;

/**
 * Unit tests for the heightmap fallback terrain generator.
 *
 * <p>Tests the block-selection logic ({@link HeightmapFallbackGenerator#pickBlockId})
 * and biome surface classification ({@link HeightmapFallbackGenerator#surfaceTypeForBiome})
 * without requiring Voxy or Minecraft runtime bindings.
 */
class HeightmapFallbackGeneratorTest {

    private static final HeightmapFallbackGenerator.SurfaceType GRASS =
            HeightmapFallbackGenerator.SurfaceType.GRASS;
    private static final HeightmapFallbackGenerator.SurfaceType SAND =
            HeightmapFallbackGenerator.SurfaceType.SAND;
    private static final HeightmapFallbackGenerator.SurfaceType RED_SAND =
            HeightmapFallbackGenerator.SurfaceType.RED_SAND;
    private static final HeightmapFallbackGenerator.SurfaceType GRAVEL =
            HeightmapFallbackGenerator.SurfaceType.GRAVEL;
    private static final HeightmapFallbackGenerator.SurfaceType STONE =
            HeightmapFallbackGenerator.SurfaceType.STONE;
    private static final HeightmapFallbackGenerator.SurfaceType SNOW =
            HeightmapFallbackGenerator.SurfaceType.SNOW;
    private static final HeightmapFallbackGenerator.SurfaceType PODZOL =
            HeightmapFallbackGenerator.SurfaceType.PODZOL;
    private static final HeightmapFallbackGenerator.SurfaceType MYCELIUM =
            HeightmapFallbackGenerator.SurfaceType.MYCELIUM;

    // Dummy Voxy block IDs for testing (arbitrary distinct integers).
    // Matches FallbackBlockIds field order:
    //   air=0, stone=1, deepslate=2, dirt=3, grassBlock=4, sand=5, water=6,
    //   redSand=7, gravel=8, snowyGrassBlock=9, snowLayer=10, podzol=11, mycelium=12
    private static final HeightmapFallbackGenerator.FallbackBlockIds IDS =
            new HeightmapFallbackGenerator.FallbackBlockIds(
                    0,   // air
                    1,   // stone
                    2,   // deepslate
                    3,   // dirt
                    4,   // grassBlock
                    5,   // sand
                    6,   // water
                    7,   // redSand
                    8,   // gravel
                    9,   // snowyGrassBlock
                    10,  // snowLayer
                    11,  // podzol
                    12   // mycelium
            );

    // ------------------------------------------------------------------ //
    //  Biome → SurfaceType classification
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Desert (12) → SAND")
    void desertIsSand() {
        assertEquals(SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(12));
    }

    @Test
    @DisplayName("Beach (2) → SAND")
    void beachIsSand() {
        assertEquals(SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(2));
    }

    @Test
    @DisplayName("Warm ocean (48) → SAND")
    void warmOceanIsSand() {
        assertEquals(SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(48));
    }

    @Test
    @DisplayName("Snowy beach (38) → SAND")
    void snowyBeachIsSand() {
        assertEquals(SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(38));
    }

    @Test
    @DisplayName("Badlands (0) → RED_SAND")
    void badlandsIsRedSand() {
        assertEquals(RED_SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(0));
    }

    @Test
    @DisplayName("Eroded badlands (14) → RED_SAND")
    void erodedBadlandsIsRedSand() {
        assertEquals(RED_SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(14));
    }

    @Test
    @DisplayName("Wooded badlands (53) → RED_SAND")
    void woodedBadlandsIsRedSand() {
        assertEquals(RED_SAND, HeightmapFallbackGenerator.surfaceTypeForBiome(53));
    }

    @Test
    @DisplayName("Ocean (29) → GRAVEL")
    void oceanIsGravel() {
        assertEquals(GRAVEL, HeightmapFallbackGenerator.surfaceTypeForBiome(29));
    }

    @Test
    @DisplayName("Stony shore (44) → GRAVEL")
    void stonyShorIsGravel() {
        assertEquals(GRAVEL, HeightmapFallbackGenerator.surfaceTypeForBiome(44));
    }

    @Test
    @DisplayName("Windswept gravelly hills (50) → GRAVEL")
    void gravellyHillsIsGravel() {
        assertEquals(GRAVEL, HeightmapFallbackGenerator.surfaceTypeForBiome(50));
    }

    @Test
    @DisplayName("Windswept hills (51) → STONE")
    void windsweptHillsIsStone() {
        assertEquals(STONE, HeightmapFallbackGenerator.surfaceTypeForBiome(51));
    }

    @Test
    void snowyPlainsIsSnow() {
        assertEquals(SNOW, HeightmapFallbackGenerator.surfaceTypeForBiome(39));
    }

    @Test
    @DisplayName("Frozen peaks (18) → SNOW")
    void frozenPeaksIsSnow() {
        assertEquals(SNOW, HeightmapFallbackGenerator.surfaceTypeForBiome(18));
    }

    @Test
    @DisplayName("Ice spikes (21) → SNOW")
    void iceSpikesIsSnow() {
        assertEquals(SNOW, HeightmapFallbackGenerator.surfaceTypeForBiome(21));
    }

    @Test
    @DisplayName("Old growth pine taiga (31) → PODZOL")
    void oldGrowthPineTaigaIsPodzol() {
        assertEquals(PODZOL, HeightmapFallbackGenerator.surfaceTypeForBiome(31));
    }

    @Test
    @DisplayName("Old growth spruce taiga (32) → PODZOL")
    void oldGrowthSpruceTaigaIsPodzol() {
        assertEquals(PODZOL, HeightmapFallbackGenerator.surfaceTypeForBiome(32));
    }

    @Test
    @DisplayName("Mushroom fields (28) → MYCELIUM")
    void mushroomFieldsIsMycelium() {
        assertEquals(MYCELIUM, HeightmapFallbackGenerator.surfaceTypeForBiome(28));
    }

    @Test
    @DisplayName("Plains (34) → GRASS")
    void plainsIsGrass() {
        assertEquals(GRASS, HeightmapFallbackGenerator.surfaceTypeForBiome(34));
    }

    @Test
    @DisplayName("Forest (16) → GRASS")
    void forestIsGrass() {
        assertEquals(GRASS, HeightmapFallbackGenerator.surfaceTypeForBiome(16));
    }

    @Test
    @DisplayName("Taiga (47) → GRASS")
    void taigaIsGrass() {
        assertEquals(GRASS, HeightmapFallbackGenerator.surfaceTypeForBiome(47));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — GRASS biome, dry land (ground == water surface = 70)
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GRASS y=69 (surface-1) → grass_block")
    void grassTopIsGrassBlock() {
        assertEquals(IDS.grassBlock(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, GRASS, IDS));
    }

    @Test
    @DisplayName("GRASS y=68 (surface-2) → dirt")
    void grassSecondIsDirt() {
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, GRASS, IDS));
    }

    @Test
    @DisplayName("GRASS y=67 (surface-3) → dirt")
    void grassThirdIsDirt() {
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, GRASS, IDS));
    }

    @Test
    @DisplayName("GRASS y=66 (below top 3) → stone")
    void grassBelowTopThreeIsStone() {
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(66, 70, 70, GRASS, IDS));
    }

    @Test
    @DisplayName("y=-1 → deepslate")
    void belowZeroIsDeepslate() {
        assertEquals(IDS.deepslate(),
                HeightmapFallbackGenerator.pickBlockId(-1, 70, 70, GRASS, IDS));
    }

    @Test
    @DisplayName("GRASS y=70 (at dry surface, above sea level) → air (no snow layer)")
    void grassAtDrySurfaceIsAir() {
        assertEquals(IDS.air(),
                HeightmapFallbackGenerator.pickBlockId(70, 70, 70, GRASS, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — SAND biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("SAND: top 3 blocks all sand")
    void sandTopThreeIsSand() {
        assertEquals(IDS.sand(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, SAND, IDS));
        assertEquals(IDS.sand(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, SAND, IDS));
        assertEquals(IDS.sand(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, SAND, IDS));
    }

    @Test
    @DisplayName("SAND: below top 3 → stone")
    void sandBelowTopThreeIsStone() {
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(66, 70, 70, SAND, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — RED_SAND biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("RED_SAND: top 3 blocks all red sand")
    void redSandTopThreeIsRedSand() {
        assertEquals(IDS.redSand(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, RED_SAND, IDS));
        assertEquals(IDS.redSand(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, RED_SAND, IDS));
        assertEquals(IDS.redSand(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, RED_SAND, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — GRAVEL biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("GRAVEL: top 3 blocks all gravel")
    void gravelTopThreeIsGravel() {
        assertEquals(IDS.gravel(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, GRAVEL, IDS));
        assertEquals(IDS.gravel(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, GRAVEL, IDS));
        assertEquals(IDS.gravel(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, GRAVEL, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — STONE biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("STONE: top 3 blocks all stone")
    void stoneTopThreeIsStone() {
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, STONE, IDS));
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, STONE, IDS));
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, STONE, IDS));
    }

    @Test
    @DisplayName("STONE: below top 3 → stone")
    void stoneBelowTopThreeIsStone() {
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(66, 70, 70, STONE, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — SNOW biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("SNOW: top solid block (depth=0) → snowy grass_block")
    void snowTopSolidIsSnowyGrass() {
        assertEquals(IDS.snowyGrassBlock(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: second solid (depth=1) → dirt")
    void snowSecondIsDirt() {
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: third solid (depth=2) → dirt")
    void snowThirdIsDirt() {
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: y=groundBlockY (one above solid surface) → snow layer")
    void snowAtSurfaceIsSnowLayer() {
        // groundBlockY=70 is the first air slot; the snow layer sits there
        assertEquals(IDS.snowLayer(),
                HeightmapFallbackGenerator.pickBlockId(70, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: y=groundBlockY+1 (two above solid surface) → air")
    void snowAboveLayerIsAir() {
        assertEquals(IDS.air(),
                HeightmapFallbackGenerator.pickBlockId(71, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: below top 3 → stone")
    void snowBelowTopThreeIsStone() {
        assertEquals(IDS.stone(),
                HeightmapFallbackGenerator.pickBlockId(66, 70, 70, SNOW, IDS));
    }

    @Test
    @DisplayName("SNOW: underwater column — no snow layer, water above instead")
    void snowUnderwaterNoSnowLayer() {
        // ground=55, waterSurface=62 (snowy river: frozen top, underwater body)
        // worldY=54 (top solid) → dirt (not snowy grass, because underwater)
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(54, 55, 62, SNOW, IDS));
        // worldY=55 (at ground) → water (not snow layer, because underwater)
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(55, 55, 62, SNOW, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — PODZOL biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("PODZOL: top → podzol, second and third → dirt")
    void podzolTopIsPodzol() {
        assertEquals(IDS.podzol(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, PODZOL, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, PODZOL, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, PODZOL, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Block selection — MYCELIUM biome
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("MYCELIUM: top → mycelium, second and third → dirt")
    void myceliumTopIsMycelium() {
        assertEquals(IDS.mycelium(),
                HeightmapFallbackGenerator.pickBlockId(69, 70, 70, MYCELIUM, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(68, 70, 70, MYCELIUM, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(67, 70, 70, MYCELIUM, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Water columns (river / ocean) — two-heightmap logic
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("River (ground=58, water=62): solid below ground, water in column, air above")
    void riverWaterColumn() {
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(57, 58, 62, GRASS, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(58, 58, 62, GRASS, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(61, 58, 62, GRASS, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(62, 58, 62, GRASS, IDS));
        assertEquals(IDS.air(),
                HeightmapFallbackGenerator.pickBlockId(63, 58, 62, GRASS, IDS));
    }

    @Test
    @DisplayName("River: sandy biome uses sand below waterline")
    void riverSandBiome() {
        assertEquals(IDS.sand(),
                HeightmapFallbackGenerator.pickBlockId(57, 58, 62, SAND, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(58, 58, 62, SAND, IDS));
    }

    @Test
    @DisplayName("Underwater (no ocean floor): surface below sea level fills with water above")
    void underwaterNoFloor() {
        assertEquals(IDS.grassBlock(),
                HeightmapFallbackGenerator.pickBlockId(54, 55, 55, GRASS, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(55, 55, 55, GRASS, IDS));
        assertEquals(IDS.water(),
                HeightmapFallbackGenerator.pickBlockId(62, 55, 55, GRASS, IDS));
        assertEquals(IDS.air(),
                HeightmapFallbackGenerator.pickBlockId(63, 55, 55, GRASS, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Edge case: very low surface (surface at y=2)
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Low surface (y=2): top-3 spans into negative Y, deepslate only below y=-2")
    void lowSurfaceTransition() {
        assertEquals(IDS.grassBlock(),
                HeightmapFallbackGenerator.pickBlockId(1,  2, 2, GRASS, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(0,  2, 2, GRASS, IDS));
        assertEquals(IDS.dirt(),
                HeightmapFallbackGenerator.pickBlockId(-1, 2, 2, GRASS, IDS));
        assertEquals(IDS.deepslate(),
                HeightmapFallbackGenerator.pickBlockId(-2, 2, 2, GRASS, IDS));
    }

    // ------------------------------------------------------------------ //
    //  Sea level constant
    // ------------------------------------------------------------------ //

    @Test
    @DisplayName("Sea level is 63")
    void seaLevelIs63() {
        assertEquals(63, HeightmapFallbackGenerator.SEA_LEVEL);
    }
}
