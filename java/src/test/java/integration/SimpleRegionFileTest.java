package integration;

import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

import fixtures.TestWorldFixtures;

public class SimpleRegionFileTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(SimpleRegionFileTest.class);

    @Test
    public void testDirectRegionFileOpen() throws Exception {
        File regionFilePath = TestWorldFixtures.getTestRegionFile("r.0.0.mca");
        assertTrue(regionFilePath.exists(), "Region file should exist at " + regionFilePath.getAbsolutePath());

        LOGGER.info("File exists: {}", regionFilePath.exists());
        LOGGER.info("File size: {} bytes", regionFilePath.length());

        // Since our test data contains 1.18+ format chunks that Hephaistos cannot read directly
        // (due to missing 'Level' fields), we'll use our ChunkDataExtractor which has fallback logic
        LOGGER.info("Testing chunk detection using ChunkDataExtractor (with 1.18+ fallback support)...");
        
        int[] validChunk = ChunkDataExtractor.findValidChunk(regionFilePath);
        assertNotNull(validChunk, "Should find at least one valid chunk using ChunkDataExtractor");
        
        LOGGER.info("SUCCESS: Found valid chunk at [{}, {}] using fallback extraction logic", 
            validChunk[0], validChunk[1]);
          // Verify we can also extract heightmap data from the chunk
        int[][] heightmap = ChunkDataExtractor.extractHeightmapFromChunk(regionFilePath, validChunk[0], validChunk[1]);
        assertNotNull(heightmap, "Should be able to extract heightmap from the found chunk");
        assertEquals(16, heightmap.length, "Heightmap should have 16 rows");
        assertEquals(16, heightmap[0].length, "Heightmap should have 16 columns");
        
        LOGGER.info("SUCCESS: Also extracted heightmap data with {}x{} values", heightmap.length, heightmap[0].length);
    }
}
