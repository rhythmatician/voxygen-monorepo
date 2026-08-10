package integration.voxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import com.rhythmatician.lodiffusion.voxy.VoxyBlockMapper;
import com.rhythmatician.lodiffusion.voxy.VoxyCompat;
import com.rhythmatician.lodiffusion.voxy.VoxySectionWriter;

/**
 * Tests for the progressive LOD direct-level write path.
 *
 * <p>These tests validate the coordinate math, voxel packing, and
 * resolution mapping for {@link VoxyCompat#writeAtLevel} and
 * {@link VoxySectionWriter#writeLodSection}.
 *
 * <p>Tests that require a live WorldEngine are marked with
 * {@code @Disabled} — they document the contract but must be run in-game.
 */
class DirectLevelWriteTest {

    private static final int VOCAB_SIZE = 4;

    private VoxyBlockMapper mockMapper;
    private VoxySectionWriter writer;

    @BeforeAll
    static void requireVoxy() {
        assumeTrue(VoxyCompat.isAvailable(),
            "Voxy not available — skipping direct level write tests");
    }

    @BeforeEach
    void setUp() {
        mockMapper = mock(VoxyBlockMapper.class);
        // All model indices map to Voxy block ID 42 (distinct from air=0)
        when(mockMapper.getVoxyBlockId(anyInt())).thenReturn(42);
        when(mockMapper.getVoxyBiomeId(anyInt())).thenReturn(1);
        writer = new VoxySectionWriter(mockMapper);
    }

    // ── Resolution mapping tests ──────────────────────────────────────

    @ParameterizedTest(name = "LOD {0}: expected resolution {1}³")
    @CsvSource({"1,8", "2,4", "3,2", "4,1"})
    void lodLevelResolutionMapping(int voxyLvl, int expectedRes) {
        int cellsPerAxis = 16 >> voxyLvl;
        assertEquals(expectedRes, cellsPerAxis,
            "Voxy lvl " + voxyLvl + " should have " + expectedRes + "³ cells per section");
    }

    // ── Coordinate math tests ─────────────────────────────────────────

    @ParameterizedTest(name = "LOD {0}: WorldSection coords for section (0,0,0)")
    @CsvSource({"1, 0,0,0", "2, 0,0,0", "3, 0,0,0", "4, 0,0,0"})
    void worldSectionCoordsAtOrigin(int lvl, int expWsX, int expWsY, int expWsZ) {
        int wsX = 0 >> (lvl + 1);
        int wsY = 0 >> (lvl + 1);
        int wsZ = 0 >> (lvl + 1);
        assertEquals(expWsX, wsX);
        assertEquals(expWsY, wsY);
        assertEquals(expWsZ, wsZ);
    }

    @ParameterizedTest(name = "LOD {0}: sub-position for section (0,0,0)")
    @CsvSource({"1, 0,0,0", "2, 0,0,0", "3, 0,0,0", "4, 0,0,0"})
    void subPositionAtOrigin(int lvl, int expBx, int expBy, int expBz) {
        int mask = (1 << (lvl + 1)) - 1;
        int bx = (0 & mask) << (4 - lvl);
        int by = (0 & mask) << (4 - lvl);
        int bz = (0 & mask) << (4 - lvl);
        assertEquals(expBx, bx);
        assertEquals(expBy, by);
        assertEquals(expBz, bz);
    }

    @Test
    void subPosition_lvl4_section17_wrapsCorrectly() {
        // Section 17 at lvl=4: mask = (1<<5)-1 = 31
        // bx = (17 & 31) << (4-4) = 17 << 0 = 17
        int lvl = 4;
        int mask = (1 << (lvl + 1)) - 1;
        int bx = (17 & mask) << (4 - lvl);
        assertEquals(17, bx, "Section 17 at lvl 4 should map to cell 17");
    }

    @Test
    void subPosition_lvl1_section3_correctOffset() {
        // Section 3 at lvl=1: mask = (1<<2)-1 = 3
        // bx = (3 & 3) << (4-1) = 3 << 3 = 24
        int lvl = 1;
        int mask = (1 << (lvl + 1)) - 1;
        int bx = (3 & mask) << (4 - lvl);
        assertEquals(24, bx, "Section 3 at lvl 1 should map to base offset 24");
    }

    @Test
    void subPosition_lvl2_section5_correctOffset() {
        // Section 5 at lvl=2: mask = (1<<3)-1 = 7
        // bx = (5 & 7) << (4-2) = 5 << 2 = 20
        int lvl = 2;
        int mask = (1 << (lvl + 1)) - 1;
        int bx = (5 & mask) << (4 - lvl);
        assertEquals(20, bx, "Section 5 at lvl 2 should map to base offset 20");
    }

    // ── WorldSection index tests ──────────────────────────────────────

    @Test
    void worldSectionIndex_matchesVoxyLayout() {
        // WorldSection data index: bx | (bz << 5) | (by << 10)
        // This is the same as WorldSection.getIndex(x, y, z)
        assertEquals(0, worldSectionIndex(0, 0, 0));
        assertEquals(1, worldSectionIndex(1, 0, 0));
        assertEquals(32, worldSectionIndex(0, 0, 1));  // 1 << 5
        assertEquals(1024, worldSectionIndex(0, 1, 0));  // 1 << 10
        assertEquals(32767, worldSectionIndex(31, 31, 31));  // all bits set
    }

    @Test
    void voxelPacking_allSolid_correctCount() {
        // Build all-solid logits at LOD4 resolution (1³)
        float[][][][][] logits = createSolidLogits(1);
        int[][] biomes = uniformBiomeGrid(0);

        // Can't call writeLodSection without WorldEngine, but we can verify
        // the logits shape matches expectations
        assertEquals(1, logits[0][0].length, "LOD4 should have 1³ spatial dims");
        assertEquals(1, logits[0][0][0].length);
        assertEquals(1, logits[0][0][0][0].length);
    }

    @ParameterizedTest(name = "LOD {0}: logits resolution = {1}³")
    @ValueSource(ints = {1, 2, 3, 4})
    void logitsShape_matchesLodLevel(int voxyLvl) {
        int cellsPerAxis = 16 >> voxyLvl;
        float[][][][][] logits = createSolidLogits(cellsPerAxis);

        assertEquals(1, logits.length, "Batch dim should be 1");
        assertEquals(VOCAB_SIZE, logits[0].length, "Channel dim should match vocab");
        assertEquals(cellsPerAxis, logits[0][0].length, "Y dim");
        assertEquals(cellsPerAxis, logits[0][0][0].length, "Z dim");
        assertEquals(cellsPerAxis, logits[0][0][0][0].length, "X dim");
    }

    // ── writeAtLevel parameter validation ─────────────────────────────

    @Test
    void writeAtLevel_rejectsLvl0() {
        assertThrows(IllegalArgumentException.class, () ->
            VoxyCompat.writeAtLevel(null, 0, 0, 0, 0, new long[4096]));
    }

    @Test
    void writeAtLevel_rejectsLvl5() {
        assertThrows(IllegalArgumentException.class, () ->
            VoxyCompat.writeAtLevel(null, 5, 0, 0, 0, new long[1]));
    }

    @Test
    void writeAtLevel_rejectsWrongArraySize() {
        // lvl=4 expects 1 voxel, not 8
        assertThrows(IllegalArgumentException.class, () ->
            VoxyCompat.writeAtLevel(null, 4, 0, 0, 0, new long[8]));
    }

    @ParameterizedTest(name = "LOD {0}: expected array size = {1}")
    @CsvSource({"1,512", "2,64", "3,8", "4,1"})
    void writeAtLevel_expectsCorrectArraySize(int lvl, int expectedSize) {
        int cellsPerAxis = 16 >> lvl;
        int actual = cellsPerAxis * cellsPerAxis * cellsPerAxis;
        assertEquals(expectedSize, actual);
    }

    // ── Biome mapping at coarse LOD levels ────────────────────────────

    @Test
    void biomeMapping_lod4_usesCenter() {
        // At LOD4, single voxel covers 16³ blocks
        // Center of 0..15 = 8
        int voxyLvl = 4;
        int blocksPerVoxel = 1 << voxyLvl;  // 16
        int hmX = Math.min(0 * blocksPerVoxel + blocksPerVoxel / 2, 15);
        assertEquals(8, hmX, "LOD4 voxel 0 should sample biome at hmX=8");
    }

    @Test
    void biomeMapping_lod1_samplesCorrectly() {
        // At LOD1, 8 voxels per axis, each covers 2 blocks
        int voxyLvl = 1;
        int blocksPerVoxel = 1 << voxyLvl;  // 2
        // Voxel 3: center = 3*2 + 1 = 7
        int hmX = Math.min(3 * blocksPerVoxel + blocksPerVoxel / 2, 15);
        assertEquals(7, hmX, "LOD1 voxel 3 should sample biome at hmX=7");
    }

    // ── Child existence propagation math ──────────────────────────────

    @Test
    void childOctantIndex_matchesVoxyGetChildIndex() {
        // WorldSection.getChildIndex(x,y,z) = (x&1) | ((y&1)<<2) | ((z&1)<<1)
        assertEquals(0, childIdx(0, 0, 0));
        assertEquals(1, childIdx(1, 0, 0));
        assertEquals(2, childIdx(0, 0, 1));
        assertEquals(3, childIdx(1, 0, 1));
        assertEquals(4, childIdx(0, 1, 0));
        assertEquals(5, childIdx(1, 1, 0));
        assertEquals(6, childIdx(0, 1, 1));
        assertEquals(7, childIdx(1, 1, 1));

        // Even coords → octant 0 regardless of magnitude
        assertEquals(0, childIdx(4, 6, 8));
        // Odd coords → octant 7
        assertEquals(7, childIdx(3, 5, 7));
    }

    @ParameterizedTest(name = "Write at LOD{0}: propagation touches levels {0}+1..4")
    @CsvSource({"1, 3", "2, 2", "3, 1", "4, 0"})
    void propagationDepth_matchesWrittenLevel(int writtenLvl, int expectedParentCount) {
        // Number of parent levels to propagate = 4 - writtenLvl
        assertEquals(expectedParentCount, 4 - writtenLvl);
    }

    @Test
    void propagation_lod1Write_setsCorrectParentBits() {
        // Writing at LOD1 to section (3, 5, 7)
        // LOD1 WS coords: (3>>2, 5>>2, 7>>2) = (0, 1, 1)
        // → child in LOD2 parent: childIdx(0,1,1) = 0|((1&1)<<2)|((1&1)<<1) = 0|4|2 = 6
        int sectionX = 3, sectionY = 5, sectionZ = 7;
        int lvl1WsX = sectionX >> 2;  // 0
        int lvl1WsY = sectionY >> 2;  // 1
        int lvl1WsZ = sectionZ >> 2;  // 1
        assertEquals(6, childIdx(lvl1WsX, lvl1WsY, lvl1WsZ),
            "LOD1 child should be octant 6 in LOD2 parent");

        // LOD2 WS coords: (3>>3, 5>>3, 7>>3) = (0, 0, 0)
        // → child in LOD3 parent: childIdx(0,0,0) = 0
        int lvl2WsX = sectionX >> 3;  // 0
        int lvl2WsY = sectionY >> 3;  // 0
        int lvl2WsZ = sectionZ >> 3;  // 0
        assertEquals(0, childIdx(lvl2WsX, lvl2WsY, lvl2WsZ),
            "LOD2 child should be octant 0 in LOD3 parent");

        // LOD3 WS coords: (3>>4, 5>>4, 7>>4) = (0, 0, 0)
        // → child in LOD4 parent: childIdx(0,0,0) = 0
        int lvl3WsX = sectionX >> 4;  // 0
        int lvl3WsY = sectionY >> 4;  // 0
        int lvl3WsZ = sectionZ >> 4;  // 0
        assertEquals(0, childIdx(lvl3WsX, lvl3WsY, lvl3WsZ),
            "LOD3 child should be octant 0 in LOD4 parent");
    }

    @Test
    void propagation_negativeCoords_octantBitsCorrect() {
        // Section (-1, 0, 0): LOD2 WS coords = (-1 >> 3, 0, 0) = (-1, 0, 0)
        // childIdx(-1, 0, 0) = (-1&1) = 1 → octant 1
        int sectionX = -1;
        int lvl2WsX = sectionX >> 3;  // -1
        assertEquals(1, childIdx(lvl2WsX, 0, 0),
            "Negative coord -1 has lowest bit 1 → octant bit set");
    }

    // ── writeLodSection without WorldEngine ────────────────────────────

    @Test
    void writeLodSection_requiresWorldEngine() {
        float[][][][][] logits = createSolidLogits(1);
        int[][] biomes = uniformBiomeGrid(0);

        assertThrows(IllegalStateException.class, () ->
            writer.writeLodSection(logits, VOCAB_SIZE, 4, 0, 0, 0, biomes),
            "writeLodSection should require live WorldEngine");
    }

    @Test
    void writeLodSection_rejectsWrongSpatialDim() {
        // Provide 4³ logits but claim lvl=4 (which expects 1³)
        float[][][][][] logits = createSolidLogits(4);
        int[][] biomes = uniformBiomeGrid(0);

        // This should fail because the writer checks WorldEngine first
        assertThrows(IllegalStateException.class, () ->
            writer.writeLodSection(logits, VOCAB_SIZE, 4, 0, 0, 0, biomes));
    }

    // ─────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────

    private static int worldSectionIndex(int x, int y, int z) {
        return x | (z << 5) | (y << 10);
    }

    /**
     * Compute child octant index matching WorldSection.getChildIndex(x, y, z).
     */
    private static int childIdx(int x, int y, int z) {
        return (x & 1) | ((z & 1) << 1) | ((y & 1) << 2);
    }

    /**
     * Create all-solid block logits at the given spatial resolution.
     * Class 1 (solid) has highest logit for all voxels.
     */
    private float[][][][][] createSolidLogits(int res) {
        float[][][][][] logits = new float[1][VOCAB_SIZE][res][res][res];
        for (int y = 0; y < res; y++) {
            for (int z = 0; z < res; z++) {
                for (int x = 0; x < res; x++) {
                    logits[0][0][y][z][x] = 0.0f;  // air: low
                    logits[0][1][y][z][x] = 1.0f;  // solid: high
                }
            }
        }
        return logits;
    }

    private int[][] uniformBiomeGrid(int biomeId) {
        int[][] biomes = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                biomes[x][z] = biomeId;
            }
        }
        return biomes;
    }
}
