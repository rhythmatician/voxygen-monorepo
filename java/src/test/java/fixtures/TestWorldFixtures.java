package fixtures;

import java.io.File;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Test fixtures for world data and region files.
 * Provides utilities for discovering and accessing test data files.
 */
public class TestWorldFixtures {

    /**
     * Path to the test data directory containing example world files.
     * Resolves to the VoxelTree training data which contains real Minecraft
     * region files for integration testing.
     */
    public static final Path TEST_DATA_PATH = resolveTestDataPath();

    /**
     * Path to the example world directory (This is ignored by git)
     */
    public static final Path EXAMPLE_WORLD_PATH = Paths.get("example-world");

    /**
     * Resolve the test data path, preferring VoxelTree training data.
     * Falls back to local test-data/ if VoxelTree is not available.
     */
    private static Path resolveTestDataPath() {
        // Primary: VoxelTree training data in the parent workspace
        Path voxelTreeData = Paths.get("..", "VoxelTree", "data", "test_world");
        if (voxelTreeData.toFile().exists()) {
            return voxelTreeData;
        }
        // Fallback: local test-data directory
        return Paths.get("test-data");
    }

    /**
     * Get a test region file from the test-data directory.
     * @param filename The region filename (e.g., "r.0.0.mca")
     * @return File object for the test region file
     */
    public static File getTestRegionFile(String filename) {
        return TEST_DATA_PATH.resolve("region").resolve(filename).toFile();
    }

    /**
     * Get a region file from the example world (DO NOT USE IN UNIT TESTS)
     * @param filename The region filename (e.g., "r.0.0.mca")
     * @return File object for the example world region file
     */
    public static File getExampleWorldRegionFile(String filename) {
        return EXAMPLE_WORLD_PATH.resolve("region").resolve(filename).toFile();
    }

    /**
     * Check if example world data is available.
     * @return true if example world exists and has region files
     */
    public static boolean isExampleWorldAvailable() {
        File worldDir = EXAMPLE_WORLD_PATH.toFile();
        if (!worldDir.exists() || !worldDir.isDirectory()) {
            return false;
        }

        File regionDir = EXAMPLE_WORLD_PATH.resolve("region").toFile();
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return false;
        }

        File[] regionFiles = regionDir.listFiles((dir, name) ->
            name.endsWith(".mca") && name.startsWith("r."));

        return regionFiles != null && regionFiles.length > 0;
    }

    /**
     * Check if test data is available.
     * @return true if test-data directory exists and has region files
     */
    public static boolean isTestDataAvailable() {
        File testDataDir = TEST_DATA_PATH.toFile();
        if (!testDataDir.exists() || !testDataDir.isDirectory()) {
            return false;
        }

        File regionDir = TEST_DATA_PATH.resolve("region").toFile();
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return false;
        }

        File[] regionFiles = regionDir.listFiles((dir, name) ->
            name.endsWith(".mca") && name.startsWith("r."));

        return regionFiles != null && regionFiles.length > 0;
    }

    /**
     * Get all available region files from example world.
     * @return Array of region files, or empty array if none found
     */
    public static File[] getExampleWorldRegionFiles() {
        File regionDir = EXAMPLE_WORLD_PATH.resolve("region").toFile();
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return new File[0];
        }

        File[] regionFiles = regionDir.listFiles((dir, name) ->
            name.endsWith(".mca") && name.startsWith("r."));

        return regionFiles != null ? regionFiles : new File[0];
    }

    /**
     * Get all available region files from test data.
     * @return Array of region files, or empty array if none found
     */
    public static File[] getTestDataRegionFiles() {
        File regionDir = TEST_DATA_PATH.resolve("region").toFile();
        if (!regionDir.exists() || !regionDir.isDirectory()) {
            return new File[0];
        }

        File[] regionFiles = regionDir.listFiles((dir, name) ->
            name.endsWith(".mca") && name.startsWith("r."));

        return regionFiles != null ? regionFiles : new File[0];
    }

    /**
     * Get summary of available test world data.
     * @return Summary string describing available data
     */
    public static String getWorldDataSummary() {
        StringBuilder summary = new StringBuilder();

        if (isTestDataAvailable()) {
            File[] testFiles = getTestDataRegionFiles();
            summary.append("Test data available:\n");
            summary.append("- Region files: ").append(testFiles.length).append("\n");
            summary.append("- Total chunks: ~").append(testFiles.length * 1024).append("\n");
        }

        if (isExampleWorldAvailable()) {
            File[] exampleFiles = getExampleWorldRegionFiles();
            if (summary.length() > 0) summary.append("\n");
            summary.append("Example world data available:\n");
            summary.append("- Region files: ").append(exampleFiles.length).append("\n");
            summary.append("- Total chunks: ~").append(exampleFiles.length * 1024).append("\n");
            summary.append("- Coverage: Real Minecraft terrain data\n");
        }

        if (summary.length() == 0) {
            summary.append("No world data available");
        } else {
            summary.append("- Use case: Integration testing and algorithm validation");
        }

        return summary.toString();
    }
}
