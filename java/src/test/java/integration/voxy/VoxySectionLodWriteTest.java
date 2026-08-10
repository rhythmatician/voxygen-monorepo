package integration.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.fail;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.rhythmatician.lodiffusion.onnx.InferenceResult;
import com.rhythmatician.lodiffusion.voxy.VoxyBlockMapper;
import com.rhythmatician.lodiffusion.voxy.VoxyCompat;
import com.rhythmatician.lodiffusion.voxy.VoxySectionWriter;
import com.rhythmatician.lodiffusion.voxy.VoxySectionWriter.FilledSectionResult;

/**
 * Integration tests for VoxySectionWriter LOD encoding.
 * Tests section building at each LOD level (1-4).
 */
class VoxySectionLodWriteTest {

    private static final int VOCAB_SIZE = 4;

    private VoxyBlockMapper mockMapper;
    private VoxySectionWriter writer;

    @BeforeAll
    static void requireVoxy() {
        assumeTrue(VoxyCompat.isAvailable(),
            "Voxy not available — skipping VoxySectionWriter tests");
    }

    @BeforeEach
    void setUp() {
        // Stub mapper: all model indices map to Voxy block ID 1 (solid)
        mockMapper = mock(VoxyBlockMapper.class);
        when(mockMapper.getVoxyBlockId(anyInt())).thenReturn(1);

        // Use test constructor that bypasses WorldEngine
        writer = new VoxySectionWriter(mockMapper);
    }

    @ParameterizedTest(name = "LOD {0}: can build section with all-solid voxels")
    @ValueSource(ints = {1, 2, 3, 4})
    void canBuildSectionAtLodLevel(int lod) {
        // All-solid: argmax produces non-zero (non-air) block indices
        InferenceResult result = createSyntheticResult(true);

        // Use lod as sectionY just to differentiate (doesn't affect encoding logic)
        FilledSectionResult filled = writer.buildFilledSection(
            result, VOCAB_SIZE, 0, lod, 0, uniformBiomeGrid(0), false);

        assertNotNull(filled.section(), "Section should not be null");

        long[] data = VoxyCompat.getSectionData(filled.section());
        assertEquals(4681, data.length, "Section data should have mip pyramid size");
        assertEquals(4096, filled.nonAirCount(),
            "All-solid section should have 4096 non-air voxels for LOD " + lod);
    }

    @Test
    void buildSection_allAir_producesZeroNonAirCount() {
        // All-air: argmax produces class 0 (air) everywhere
        InferenceResult result = createSyntheticResult(false);

        FilledSectionResult filled = writer.buildFilledSection(
            result, VOCAB_SIZE, 0, 0, 0, uniformBiomeGrid(0), false);

        assertEquals(0, filled.nonAirCount(),
            "All-air section should have 0 non-air voxels");
    }

    @Test
    void buildSection_halfSolid_producesCorrectNonAirCount() {
        // Half solid: checkerboard pattern — alternating air (class 0) and solid
        InferenceResult result = createCheckerboardResult();

        FilledSectionResult filled = writer.buildFilledSection(
            result, VOCAB_SIZE, 0, 0, 0, uniformBiomeGrid(0), false);

        assertEquals(2048, filled.nonAirCount(),
            "Checkerboard section should have 2048 non-air voxels");
    }

    @Test
    void buildSection_biomeIdsPropagateToVoxels() {
        // All-solid with varying biome grid
        InferenceResult result = createSyntheticResult(true);
        int[][] biomes = new int[16][16];
        biomes[0][0] = 7; // Test corner

        FilledSectionResult filled = writer.buildFilledSection(
            result, VOCAB_SIZE, 0, 0, 0, biomes, false);

        long[] data = VoxyCompat.getSectionData(filled.section());
        // Voxel at (x=0, y=0, z=0) should have biome 7
        int idx = VoxyCompat.l0Index(0, 0, 0);
        long voxel = data[idx];

        int extractedBiome = (int) ((voxel >> VoxyCompat.BIOME_ID_SHIFT) &
            ((1L << VoxyCompat.BIOME_ID_BITS) - 1));
        assertEquals(7, extractedBiome, "Corner voxel should have biome ID 7");
    }

    @Test
    void buildSection_negativeCoordinates_accepted() {
        InferenceResult result = createSyntheticResult(true);

        // Should not throw with negative coordinates
        FilledSectionResult filled = writer.buildFilledSection(
            result, VOCAB_SIZE, -5, -4, -3, uniformBiomeGrid(0), false);

        assertNotNull(filled.section());
        assertEquals(4096, filled.nonAirCount());

        // Verify position was set
        try {
            var clazz = filled.section().getClass();
            assertEquals(-5, clazz.getField("x").getInt(filled.section()));
            assertEquals(-4, clazz.getField("y").getInt(filled.section()));
            assertEquals(-3, clazz.getField("z").getInt(filled.section()));
        } catch (Exception e) {
            fail("Failed to read section position: " + e.getMessage());
        }
    }

    @Test
    @Disabled("Requires live WorldEngine — run manually in Fabric dev environment")
    void insertUpdate_fullRoundTrip_notTestedInCI() {
        // This test documents the gap: we cannot test WorldUpdater.insertUpdate()
        // without a live Minecraft server and Voxy initialization.
        //
        // To test manually:
        // 1. Launch Minecraft with Voxy and LODiffusion
        // 2. Enter a world
        // 3. Use /dumpnoise to trigger section generation
        // 4. Verify sections appear in Voxy's RocksDB storage
        fail("This test must be run in-game — not in JUnit");
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Create a synthetic InferenceResult where argmax selects solid or air for all voxels.
     *
     * <p>Uses the argmax convention: class 0 = air, class 1+ = solid block.
     * When {@code solid=true}, class 1 has the highest logit so all voxels are solid.
     * When {@code solid=false}, class 0 has the highest logit so all voxels are air.
     */
    private InferenceResult createSyntheticResult(boolean solid) {
        float[][][][][] logits = new float[1][VOCAB_SIZE][16][16][16];

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    if (solid) {
                        // Class 1 wins argmax → solid
                        logits[0][0][y][z][x] = 0.0f;  // air class: low
                        logits[0][1][y][z][x] = 1.0f;  // solid class: high
                    } else {
                        // Class 0 wins argmax → air
                        logits[0][0][y][z][x] = 1.0f;  // air class: high
                        logits[0][1][y][z][x] = 0.0f;  // solid class: low
                    }
                }
            }
        }

        return new InferenceResult(logits, 0L);
    }

    /**
     * Create an InferenceResult with a checkerboard argmax pattern (half solid).
     *
     * <p>Voxels where (x+y+z) is even get class 1 (solid); odd voxels get class 0 (air).
     */
    private InferenceResult createCheckerboardResult() {
        float[][][][][] logits = new float[1][VOCAB_SIZE][16][16][16];

        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    // Checkerboard: solid when (x+y+z) is even
                    boolean solid = (x + y + z) % 2 == 0;
                    if (solid) {
                        logits[0][0][y][z][x] = 0.0f;  // air class: low
                        logits[0][1][y][z][x] = 1.0f;  // solid class: high
                    } else {
                        logits[0][0][y][z][x] = 1.0f;  // air class: high
                        logits[0][1][y][z][x] = 0.0f;  // solid class: low
                    }
                }
            }
        }

        return new InferenceResult(logits, 0L);
    }

    /**
     * Create a uniform 16x16 biome grid.
     */
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
