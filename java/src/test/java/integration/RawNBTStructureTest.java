package integration;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.RandomAccessFile;

import static org.junit.jupiter.api.Assertions.*;

import fixtures.TestWorldFixtures;

/**
 * Low-level test to examine raw NBT structure in region files
 * to understand the chunk format we're dealing with.
 */
public class RawNBTStructureTest {

    private static final Logger LOGGER = LoggerFactory.getLogger(RawNBTStructureTest.class);

    @Test
    public void testRawRegionFileStructure() throws Exception {
        File regionFilePath = TestWorldFixtures.getTestRegionFile("r.0.0.mca");
        Assumptions.assumeTrue(regionFilePath.exists(), "Region file not available at " + regionFilePath.getAbsolutePath() + " -- skipping integration test (requires external test_world fixture)");
        assertTrue(regionFilePath.exists(), "Region file should exist at " + regionFilePath.getAbsolutePath());
          LOGGER.info("File exists: {}", regionFilePath.exists());
        LOGGER.info("File size: {} bytes", regionFilePath.length());
        
        // Try to examine the raw structure without Hephaistos parsing
        try (RandomAccessFile file = new RandomAccessFile(regionFilePath, "r")) {
            
            // Read the header to see if any chunks exist
            // First 4096 bytes contain chunk location table
            // Each entry is 4 bytes: 3 bytes for offset + 1 byte for sector count
            
            boolean foundChunk = false;
            int chunkCount = 0;
            
            LOGGER.info("Scanning chunk location table...");
            
            for (int i = 0; i < 1024; i++) { // 1024 entries (32x32 chunks)
                file.seek(i * 4);

                // Read 4 bytes for this chunk entry
                int offset = file.readUnsignedByte() << 16 | file.readUnsignedByte() << 8 | file.readUnsignedByte();
                int sectorCount = file.readUnsignedByte();

                if (offset > 0 && sectorCount > 0) {
                    foundChunk = true;
                    chunkCount++;

                    // Calculate chunk coordinates
                    int chunkX = i % 32;
                    int chunkZ = i / 32;

                    LOGGER.info("Found chunk at [{}, {}] - offset: {}, sectors: {}", chunkX, chunkZ, offset, sectorCount);

                    // Only print first few chunks to avoid spam
                    if (chunkCount >= 10) {
                        LOGGER.info("... (stopping after 10 chunks)");
                        break;
                    }
                }
            }
            
            LOGGER.info("Total chunks found in location table: {}", chunkCount);
            
            assertTrue(foundChunk, "Should find at least one chunk in the location table");
            
            // Now let's try to read the raw NBT data of the first chunk we found
            file.seek(0);
            for (int i = 0; i < 1024; i++) {
                file.seek(i * 4);
                int offset = file.readUnsignedByte() << 16 | file.readUnsignedByte() << 8 | file.readUnsignedByte();
                int sectorCount = file.readUnsignedByte();
                
                if (offset > 0 && sectorCount > 0) {
                    int chunkX = i % 32;
                    int chunkZ = i / 32;

                    LOGGER.info("Reading raw chunk data for [{}, {}]...", chunkX, chunkZ);

                    // Seek to chunk data (offset is in 4KB sectors)
                    file.seek(offset * 4096L);

                    // Read chunk header: length (4 bytes) + compression type (1 byte)
                    int chunkLength = file.readInt();
                    byte compressionType = file.readByte();
                    LOGGER.info("Chunk length: {} bytes, compression: {}", chunkLength, compressionType);

                    if (chunkLength > 0 && chunkLength < 1024 * 1024) { // Sanity check
                        // We found valid chunk data - this proves the region file contains chunks
                        LOGGER.info("SUCCESS: Found valid chunk data at [{}, {}]", chunkX, chunkZ);
                        return; // Exit test successfully
                    }
                }
            }
            
            fail("No valid chunk data found despite location table entries");
        }
    }
}
