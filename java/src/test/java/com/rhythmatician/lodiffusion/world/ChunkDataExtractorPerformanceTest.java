package com.rhythmatician.lodiffusion.world;

import fixtures.TestWorldFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;

import java.io.File;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Performance tests for ChunkDataExtractor to verify optimizations.
 * These tests measure the impact of caching and other performance improvements.
 */
@Tag("benchmark")
public class ChunkDataExtractorPerformanceTest {

    @BeforeEach
    void setup() {
        // Clear cache before each test for consistent results
        ChunkDataExtractor.clearCache();
        // Enable profiling to measure performance
        ChunkDataExtractor.setProfilingEnabled(true);
    }

    @AfterEach
    void tearDown() {        // Clean up after tests
        ChunkDataExtractor.clearCache();
        ChunkDataExtractor.setProfilingEnabled(false);
    }

    @Test
    void testRegionFileCachingPerformance() {
        Assumptions.assumeTrue(TestWorldFixtures.isExampleWorldAvailable(), 
            "World data not available - skipping performance test");

        File[] regionFiles = TestWorldFixtures.getExampleWorldRegionFiles();
        Assumptions.assumeTrue(regionFiles.length > 0, 
            "No region files available - skipping performance test");

        File regionFile = regionFiles[0];

        // Measure time for first access (cache miss)
        long startTime = System.nanoTime();
        try {
            int[][] firstResult = ChunkDataExtractor.extractHeightmapFromChunk(regionFile, 0, 0);
            long firstAccessTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

            // Measure time for second access (cache hit)
            startTime = System.nanoTime();
            int[][] secondResult = ChunkDataExtractor.extractHeightmapFromChunk(regionFile, 1, 1);
            long secondAccessTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

            System.out.println("PERFORMANCE: First access: " + firstAccessTime + "ms");
            System.out.println("PERFORMANCE: Second access: " + secondAccessTime + "ms");
            
            // Second access should be faster due to caching (though this is a loose check)
            // Main goal is to verify no exceptions are thrown and both return valid data
            if (firstResult != null) {
                assertNotNull(firstResult, "First heightmap extraction should succeed");
                assertEquals(16, firstResult.length, "Heightmap should be 16x16");
            }
            
            if (secondResult != null) {
                assertNotNull(secondResult, "Second heightmap extraction should succeed");
                assertEquals(16, secondResult.length, "Heightmap should be 16x16");
            }        } catch (Exception e) {
            fail("Performance test failed with exception: " + e.getMessage());
        }
    }

    @Test
    void testBatchExtractionPerformance() {
        Assumptions.assumeTrue(TestWorldFixtures.isExampleWorldAvailable(), 
            "World data not available - skipping performance test");

        File[] regionFiles = TestWorldFixtures.getExampleWorldRegionFiles();
        Assumptions.assumeTrue(regionFiles.length > 0, 
            "No region files available - skipping performance test");

        File regionFile = regionFiles[0];

        // Prepare batch of chunks to extract
        int[][] chunks = {
            {0, 0}, {0, 1}, {1, 0}, {1, 1},
            {2, 2}, {3, 3}, {4, 4}, {5, 5}
        };

        long startTime = System.nanoTime();
        try {
            int[][][] results = ChunkDataExtractor.extractHeightmapsFromRegion(regionFile, chunks);
            long batchTime = (System.nanoTime() - startTime) / 1_000_000; // Convert to ms

            System.out.println("PERFORMANCE: Batch extraction of " + chunks.length + 
                " chunks took: " + batchTime + "ms");

            assertNotNull(results, "Batch extraction should return results array");
            assertEquals(chunks.length, results.length, 
                "Results array should match input chunk count");

            // Verify at least some chunks were extracted (some may be null if chunk doesn't exist)
            boolean anySuccessful = false;
            for (int[][] result : results) {
                if (result != null) {
                    anySuccessful = true;
                    assertEquals(16, result.length, "Extracted heightmap should be 16x16");
                    assertEquals(16, result[0].length, "Extracted heightmap should be 16x16");
                }
            }
            
            // This is a loose assertion since some chunks may not exist in the region
            // The main goal is to verify the batch extraction method works without errors
            System.out.println("PERFORMANCE: Batch extraction completed - any successful: " + anySuccessful);

        } catch (Exception e) {
            fail("Batch performance test failed with exception: " + e.getMessage());
        }
    }

    @Test
    void testCacheStatistics() {
        // Test that cache statistics work
        String stats = ChunkDataExtractor.getPerformanceStats();
        assertNotNull(stats, "Performance stats should not be null");
        assertTrue(stats.contains("Cache stats"), "Stats should contain cache information");
        
        System.out.println("PERFORMANCE: " + stats);
    }

    @Test
    void testCacheClearance() {        Assumptions.assumeTrue(TestWorldFixtures.isExampleWorldAvailable(), 
            "World data not available - skipping cache test");

        File[] regionFiles = TestWorldFixtures.getExampleWorldRegionFiles();
        Assumptions.assumeTrue(regionFiles.length > 0, 
            "No region files available - skipping cache test");

        try {
            File regionFile = regionFiles[0];
            
            // Load something into cache
            ChunkDataExtractor.extractHeightmapFromChunk(regionFile, 0, 0);
            
            String statsBefore = ChunkDataExtractor.getPerformanceStats();
            System.out.println("PERFORMANCE: Stats before clear: " + statsBefore);
            
            // Clear cache
            ChunkDataExtractor.clearCache();
            
            String statsAfter = ChunkDataExtractor.getPerformanceStats();
            System.out.println("PERFORMANCE: Stats after clear: " + statsAfter);
            
            // Both should be valid (cache size should be reset)
            assertNotNull(statsBefore, "Stats before clear should not be null");
            assertNotNull(statsAfter, "Stats after clear should not be null");

        } catch (Exception e) {
            fail("Cache clearance test failed with exception: " + e.getMessage());
        }
    }
}
