package integration;

import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;
import org.junit.jupiter.api.Test;

import java.io.File;
import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

import fixtures.TestWorldFixtures;

public class RealWorldBiomeExtractionTest {

    @Test
    public void testRegionFileAccess() throws IOException {
        File regionFilePath = TestWorldFixtures.getTestRegionFile("r.-1.0.mca");
        assertTrue(regionFilePath.exists(), "Region file should exist at " + regionFilePath.getAbsolutePath());
        assertTrue(regionFilePath.canRead(), "Region file should be readable");
        assertTrue(regionFilePath.length() > 0, "Region file should not be empty");
        
        // Test if we can parse region coordinates
        int[] coords = ChunkDataExtractor.parseRegionCoordinates(regionFilePath);
        assertNotNull(coords, "Should be able to parse region coordinates");
        assertEquals(-1, coords[0], "Region X should be -1");
        assertEquals(0, coords[1], "Region Z should be 0");
    }

    @Test
    public void testExtractBiomesFromRealChunk() throws IOException {
        File regionFilePath = TestWorldFixtures.getTestRegionFile("r.-1.0.mca");
        assertTrue(regionFilePath.exists(), "Region file should exist at " + regionFilePath.getAbsolutePath());

        // Find a valid chunk in the region instead of hardcoding coordinates
        int[] validChunk = ChunkDataExtractor.findValidChunk(regionFilePath);
        assertNotNull(validChunk, "Should find at least one valid chunk in the region");

        String[] biomes = ChunkDataExtractor.extractBiomesFromChunk(regionFilePath, validChunk[0], validChunk[1]);

        assertNotNull(biomes, "Biome array should not be null");
        assertTrue(biomes.length == 256 || biomes.length == 1024,
            "Expected 256 or 1024 biome entries, got " + biomes.length);
        assertTrue(biomes[0].startsWith("minecraft:"), "Expected Minecraft biome namespace");
    }
}
