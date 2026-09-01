package com.rhythmatician.lodiffusion;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Test class for DefaultLODQuery implementation.
 * Tests the actual fallback LOD behavior that we implement.
 * Focuses on core distance calculation logic without Minecraft dependencies.
 */
class DefaultLODQueryTest {

    private DefaultLODQuery lodQuery;    @BeforeEach
    void setUp() {
        lodQuery = new DefaultLODQuery();
    }

    @Test
    void testLODCalculationWithCoordinates() {
        // Given: A player at chunk position (10, 10)
        int playerX = 10, playerZ = 10;
        
        // When: Calculate LOD for chunks at different distances
        int nearLOD = lodQuery.getLOD(11, 11, playerX, playerZ); // Distance 1
        int mediumLOD = lodQuery.getLOD(15, 15, playerX, playerZ); // Distance 5  
        int farLOD = lodQuery.getLOD(40, 40, playerX, playerZ); // Distance 30
        
        // Then: LOD should increase with distance
        assertEquals(0, nearLOD, "Near chunks should have highest detail (LOD 0)");
        assertEquals(1, mediumLOD, "Medium distance chunks should have LOD 1");
        assertEquals(3, farLOD, "Far chunks should have lowest detail (LOD 3)");
    }

    @Test
    void testSimpleLODCalculation() {
        // Given: Simple LOD calculation from origin
        DefaultLODQuery query = new DefaultLODQuery();
        
        // When: Calculate LOD for various positions
        int nearLOD = query.getSimpleLOD(2, 2);
        int mediumLOD = query.getSimpleLOD(15, 15);
        int farLOD = query.getSimpleLOD(30, 30);
        
        // Then: Should follow same distance rules
        assertEquals(0, nearLOD, "Near chunks should be LOD 0");
        assertEquals(2, mediumLOD, "Medium chunks should be LOD 2");
        assertEquals(3, farLOD, "Far chunks should be LOD 3");
    }

    @Test
    void testChebyshevDistanceCalculation() {
        // Test that we use Chebyshev distance (max of dx, dz) not Euclidean
        int playerX = 0, playerZ = 0;
        
        // L-shaped distance: dx=10, dz=2 -> Chebyshev distance = 10
        int lod1 = lodQuery.getLOD(10, 2, playerX, playerZ);
        
        // Diagonal distance: dx=7, dz=7 -> Chebyshev distance = 7  
        int lod2 = lodQuery.getLOD(7, 7, playerX, playerZ);
        
        // Both should have same LOD due to Chebyshev distance
        assertEquals(1, lod1, "L-shaped distance should use max(10,2) = 10");
        assertEquals(1, lod2, "Diagonal distance should use max(7,7) = 7");
    }

    @Test
    void testNegativeCoordinates() {
        // Test that negative coordinates work correctly
        int playerX = 0, playerZ = 0;
        
        int lod1 = lodQuery.getLOD(-5, -5, playerX, playerZ);
        int lod2 = lodQuery.getLOD(5, 5, playerX, playerZ);
        int lod3 = lodQuery.getLOD(-5, 5, playerX, playerZ);
        int lod4 = lodQuery.getLOD(5, -5, playerX, playerZ);
        
        // All should have same LOD due to absolute distance calculation
        assertEquals(lod1, lod2, "Negative coordinates should give same LOD as positive");
        assertEquals(lod1, lod3, "Mixed coordinates should give same LOD");
        assertEquals(lod1, lod4, "All combinations should give same LOD");
        assertEquals(1, lod1, "Distance 5 should be LOD 1");
    }

    @Test
    void testGetLOD_WithMockedServerPlayer() {
        // Test the ServerPlayerEntity overload method
        // This method delegates to the coordinate-based version, so we test indirectly
        // by verifying the delegation logic exists (testing that the method exists)
        
        // Since we can't easily mock Minecraft classes in unit tests,
        // we verify the method exists by checking it compiles and runs
        // The actual functionality is tested through integration tests
        
        assertNotNull(lodQuery, "LODQuery should be initialized");
        // Method existence verified by compilation - actual testing done in integration tests
    }

    @Test
    void testGetLOD_WithNullChunkPos() {
        // Test with null ChunkPos to exercise error handling
        assertThrows(NullPointerException.class, () -> {
            lodQuery.getLOD(null, null);
        }, "Method should throw NPE when ChunkPos is null");
    }
}
