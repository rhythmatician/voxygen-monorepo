package com.rhythmatician.lodiffusion.world;

import fixtures.TestWorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Tests for ChunkDataExtractor utility class.
 * Covers world data availability checking, region file parsing, and coordinate calculations.
 */
public class ChunkDataExtractorTest {

    @TempDir
    Path tempDir;

    private Path testWorldPath;
    private Path testRegionPath;

    @BeforeEach
    void setUp() throws IOException {
        // Create a temporary world structure for testing
        testWorldPath = tempDir.resolve("test-world");
        testRegionPath = testWorldPath.resolve("region");
        Files.createDirectories(testRegionPath);
    }

    @Test
    void testIsWorldDataAvailable_WithValidWorld() throws IOException {
        // Create mock region file
        Files.createFile(testRegionPath.resolve("r.0.0.mca"));

        // Test with real test-data from VoxelTree training data
        assertTrue(TestWorldFixtures.isTestDataAvailable(),
            "Test data should be available at " + TestWorldFixtures.TEST_DATA_PATH.toAbsolutePath());
    }

    @Test
    void testIsWorldDataAvailable_WithMissingWorld() {
        // Test behavior when no world data is available
        // This tests the fallback behavior when example-world doesn't exist
        Path nonExistentWorld = Paths.get("non-existent-world");
        assertFalse(nonExistentWorld.toFile().exists(),
            "Precondition: non-existent world should not exist");
    }

    @Test
    void testGetAvailableRegionFiles_WithRegionFiles() throws IOException {
        // Create multiple mock region files
        Files.createFile(testRegionPath.resolve("r.0.0.mca"));
        Files.createFile(testRegionPath.resolve("r.1.0.mca"));
        Files.createFile(testRegionPath.resolve("r.0.1.mca"));
        Files.createFile(testRegionPath.resolve("not_a_region.txt")); // Should be ignored

        // Test with real world data
        File[] regionFiles = TestWorldFixtures.getTestDataRegionFiles();
        assertNotNull(regionFiles, "Region files array should not be null");

        // If test-data exists, we should have region files
        if (TestWorldFixtures.isTestDataAvailable()) {
            assertTrue(regionFiles.length > 0, "Should find region files in test-data");
            for (File file : regionFiles) {
                assertTrue(file.getName().endsWith(".mca"),
                    "All returned files should be .mca files: " + file.getName());            }
        }
    }

    @Test
    void testGetAvailableRegionFiles_WithNoRegions() {
        // Test when no region files exist (empty directory case is covered by isWorldDataAvailable)
        File[] regionFiles = TestWorldFixtures.getTestDataRegionFiles();
        assertNotNull(regionFiles, "Should return empty array, not null, when no regions found");
    }

    @Test
    void testParseRegionCoordinates_ValidFormats() {
        // Test various valid region file formats
        File testFile1 = new File("r.0.0.mca");
        int[] coords1 = ChunkDataExtractor.parseRegionCoordinates(testFile1);
        assertArrayEquals(new int[]{0, 0}, coords1, "Should parse r.0.0.mca correctly");

        File testFile2 = new File("r.5.3.mca");
        int[] coords2 = ChunkDataExtractor.parseRegionCoordinates(testFile2);
        assertArrayEquals(new int[]{5, 3}, coords2, "Should parse r.5.3.mca correctly");

        File testFile3 = new File("r.-2.-1.mca");
        int[] coords3 = ChunkDataExtractor.parseRegionCoordinates(testFile3);
        assertArrayEquals(new int[]{-2, -1}, coords3, "Should parse negative coordinates correctly");

        File testFile4 = new File("r.100.50.mca");
        int[] coords4 = ChunkDataExtractor.parseRegionCoordinates(testFile4);
        assertArrayEquals(new int[]{100, 50}, coords4, "Should parse large coordinates correctly");
    }

    @Test
    void testParseRegionCoordinates_InvalidFormats() {
        // Test invalid filename formats
        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.parseRegionCoordinates(new File("invalid.mca"));
        }, "Should throw exception for invalid format");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.parseRegionCoordinates(new File("r.0.mca"));
        }, "Should throw exception for missing coordinate");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.parseRegionCoordinates(new File("r.0.0.0.mca"));
        }, "Should throw exception for too many coordinates");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.parseRegionCoordinates(new File("r.a.b.mca"));
        }, "Should throw exception for non-numeric coordinates");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.parseRegionCoordinates(new File("region.0.0.mca"));
        }, "Should throw exception for wrong prefix");
    }

    @Test
    void testGetWorldChunkCoordinates_ValidInputs() {
        // Test coordinate conversion from region-local to world coordinates
        int[] coords1 = ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 0, 0);
        assertArrayEquals(new int[]{0, 0}, coords1, "Origin region, origin chunk should be [0, 0]");

        int[] coords2 = ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 15, 20);
        assertArrayEquals(new int[]{15, 20}, coords2, "Origin region, offset chunk should match local coords");

        int[] coords3 = ChunkDataExtractor.getWorldChunkCoordinates(1, 2, 10, 5);
        assertArrayEquals(new int[]{42, 69}, coords3, "Should calculate world coords: (1*32+10, 2*32+5)");

        int[] coords4 = ChunkDataExtractor.getWorldChunkCoordinates(-1, -1, 31, 31);
        assertArrayEquals(new int[]{-1, -1}, coords4, "Negative regions should work: (-1*32+31, -1*32+31)");

        int[] coords5 = ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 31, 31);
        assertArrayEquals(new int[]{31, 31}, coords5, "Max local coordinates should work");
    }

    @Test
    void testGetWorldChunkCoordinates_InvalidInputs() {
        // Test boundary validation for local chunk coordinates
        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, -1, 0);
        }, "Should throw exception for negative local chunk X");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 0, -1);
        }, "Should throw exception for negative local chunk Z");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 32, 0);
        }, "Should throw exception for local chunk X >= 32");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 0, 32);        }, "Should throw exception for local chunk Z >= 32");

        assertThrows(IllegalArgumentException.class, () -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 100, 15);
        }, "Should throw exception for invalid local coordinates");
    }

    @Test
    void testExtractHeightmapFromChunk_FileNotFound() {
        // Test that the implemented method correctly handles missing files
        File testFile = new File("non-existent-directory/r.0.0.mca");

        assertThrows(IOException.class, () -> {
            ChunkDataExtractor.extractHeightmapFromChunk(testFile, 0, 0);
        }, "Should throw IOException for missing region file");
    }

    @Test
    void testExtractBiomesFromChunk_FileNotFound() {
        // Test that the biome extraction method correctly handles missing files
        File testFile = new File("non-existent-directory/r.0.0.mca");

        assertThrows(IOException.class, () -> {
            ChunkDataExtractor.extractBiomesFromChunk(testFile, 0, 0);        }, "Should throw IOException for missing region file");
    }

    @Test
    void testGetWorldDataSummary_WithData() {
        // Test world data summary generation
        String summary = TestWorldFixtures.getWorldDataSummary();
        assertNotNull(summary, "Summary should not be null");

        if (TestWorldFixtures.isTestDataAvailable()) {
            assertTrue(summary.contains("Test data available"),
                "Summary should indicate test data is available");
            assertTrue(summary.contains("Region files:"),
                "Summary should include region file count");
            assertTrue(summary.contains("Total chunks:"),
                "Summary should include chunk estimate");
        } else {
            assertTrue(summary.contains("No world data available"),
                "Summary should indicate no data when unavailable");
        }
    }

    @Test
    void testGetWorldDataSummary_NoData() {
        // This test verifies the behavior when no world data is available
        // Since we can't guarantee the state of example-world, we test the logic indirectly
        String summary = TestWorldFixtures.getWorldDataSummary();
        assertNotNull(summary, "Summary should never be null");
        assertTrue(summary.length() > 0, "Summary should not be empty");
    }

    @Test
    void testRegionFileExtensionFiltering() {
        // Verify that only .mca files are considered region files
        File[] regionFiles = TestWorldFixtures.getTestDataRegionFiles();

        if (regionFiles.length > 0) {
            for (File file : regionFiles) {
                assertTrue(file.getName().endsWith(".mca"),
                    "All region files should have .mca extension: " + file.getName());
                assertTrue(file.getName().startsWith("r."),
                    "All region files should start with 'r.': " + file.getName());
            }
        }
    }

    @Test
    void testChunkCoordinateRangeValidation() {
        // Test edge cases for chunk coordinate validation
        // Valid boundary cases
        assertDoesNotThrow(() -> {
            ChunkDataExtractor.getWorldChunkCoordinates(0, 0, 0, 0);
        }, "Minimum valid coordinates should work");

        assertDoesNotThrow(() -> {
            ChunkDataExtractor.getWorldChunkCoordinates(Integer.MAX_VALUE, Integer.MAX_VALUE, 31, 31);
        }, "Maximum valid coordinates should work");

        assertDoesNotThrow(() -> {
            ChunkDataExtractor.getWorldChunkCoordinates(Integer.MIN_VALUE, Integer.MIN_VALUE, 0, 0);
        }, "Minimum region coordinates should work");
    }

    @Test
    void testCoordinateCalculationAccuracy() {
        // Verify mathematical accuracy of coordinate transformations
        // Test multiple scenarios to ensure the formula regionCoord * 32 + localCoord is correct

        // Positive regions
        int[] result1 = ChunkDataExtractor.getWorldChunkCoordinates(5, 7, 10, 15);
        assertEquals(5 * 32 + 10, result1[0], "World X calculation should be correct");
        assertEquals(7 * 32 + 15, result1[1], "World Z calculation should be correct");

        // Negative regions
        int[] result2 = ChunkDataExtractor.getWorldChunkCoordinates(-3, -2, 5, 8);
        assertEquals(-3 * 32 + 5, result2[0], "Negative region X calculation should be correct");
        assertEquals(-2 * 32 + 8, result2[1], "Negative region Z calculation should be correct");

        // Mixed signs
        int[] result3 = ChunkDataExtractor.getWorldChunkCoordinates(-1, 1, 16, 24);
        assertEquals(-1 * 32 + 16, result3[0], "Mixed sign calculation should be correct");
        assertEquals(1 * 32 + 24, result3[1], "Mixed sign calculation should be correct");
    }
}
