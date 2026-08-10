package integration.voxy;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.rhythmatician.lodiffusion.voxy.VoxyCompat;

/**
 * Integration tests for VoxyCompat raw API.
 * Requires Voxy jar on the test classpath.
 */
class VoxyCompatApiTest {

    @BeforeAll
    static void requireVoxy() {
        assumeTrue(VoxyCompat.isAvailable(),
            "Voxy not available — skipping VoxyCompat API tests");
    }

    @Test
    void createEmptySection_hasCorrectArraySize() {
        Object section = VoxyCompat.createEmptySection();
        assertNotNull(section, "Section should not be null");

        long[] data = VoxyCompat.getSectionData(section);
        // VoxelizedSection.section[] = 16³ + 8³ + 4³ + 2³ + 1 = 4096 + 512 + 64 + 8 + 1 = 4681
        assertEquals(4681, data.length,
            "Section data array should have 4681 elements (mip pyramid)");
    }

    @Test
    void setSectionPosition_fieldsRoundTrip() throws Exception {
        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, 3, -4, 7);

        // Read back via reflection
        var clazz = section.getClass();
        int x = clazz.getField("x").getInt(section);
        int y = clazz.getField("y").getInt(section);
        int z = clazz.getField("z").getInt(section);

        assertEquals(3, x, "Section X should be 3");
        assertEquals(-4, y, "Section Y should be -4");
        assertEquals(7, z, "Section Z should be 7");
    }

    @Test
    void composeVoxel_encodesBlockBiomeLightCorrectly() {
        // Known values
        int blockId = 5;
        int biomeId = 3;
        int light = 15;

        long voxel = VoxyCompat.composeVoxel(blockId, biomeId, light);

        // Extract and verify each field
        // Block ID at bits 27-46 (20 bits)
        int extractedBlock = (int) ((voxel >> VoxyCompat.BLOCK_ID_SHIFT) &
            ((1L << VoxyCompat.BLOCK_ID_BITS) - 1));
        assertEquals(blockId, extractedBlock, "Block ID should round-trip");

        // Biome ID at bits 47-55 (9 bits)
        int extractedBiome = (int) ((voxel >> VoxyCompat.BIOME_ID_SHIFT) &
            ((1L << VoxyCompat.BIOME_ID_BITS) - 1));
        assertEquals(biomeId, extractedBiome, "Biome ID should round-trip");

        // Light at bits 56-63 (8 bits)
        int extractedLight = (int) (voxel >> VoxyCompat.LIGHT_SHIFT);
        assertEquals(light, extractedLight, "Light should round-trip");
    }

    @Test
    void l0Index_packsYZXOrder() {
        // l0Index(x, y, z) = (y << 8) | (z << 4) | x
        int x = 1, y = 2, z = 3;
        int expected = (y << 8) | (z << 4) | x; // = 512 + 48 + 1 = 561

        assertEquals(expected, VoxyCompat.l0Index(x, y, z),
            "l0Index should pack in YZX order");
    }

    @Test
    void isAir_detectsZeroBlockId() {
        long airVoxel = VoxyCompat.composeVoxel(0, 5, 15);
        long solidVoxel = VoxyCompat.composeVoxel(1, 5, 15);

        assertTrue(VoxyCompat.isAir(airVoxel), "Block ID 0 should be air");
        assertFalse(VoxyCompat.isAir(solidVoxel), "Block ID 1 should not be air");
    }
}
