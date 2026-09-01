package com.rhythmatician.lodiffusion.world.noise.tools;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.EnumSet;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;

import com.rhythmatician.lodiffusion.world.noise.tools.NoiseTap.PerformanceTier;
import com.rhythmatician.lodiffusion.world.noise.tools.NoiseTap.RouterField;

import net.minecraft.world.Heightmap;

/**
 * Unit tests for the NoiseTap interface and implementation.
 * Tests the efficient vanilla noise signal capture at native API granularities.
 *
 * <p>NoiseTap is a data-harvesting tool, not part of the production inference pipeline.
 */
public class NoiseTapTest {

    @Test
    void testRouterFieldTierClassification() {
        // Test that our performance tier system correctly categorizes router fields
        EnumSet<RouterField> coreFields = NoiseTap.getTierFields(PerformanceTier.CORE);
        
        // Core tier should include the 6 essential Tier A fields
        assertEquals(6, coreFields.size());
        assertTrue(coreFields.contains(RouterField.TEMPERATURE));
        assertTrue(coreFields.contains(RouterField.VEGETATION));
        assertTrue(coreFields.contains(RouterField.CONTINENTS));
        assertTrue(coreFields.contains(RouterField.EROSION));
        assertTrue(coreFields.contains(RouterField.DEPTH));
        assertTrue(coreFields.contains(RouterField.RIDGES));
        
        // Core tier should NOT include expensive fields
        assertFalse(coreFields.contains(RouterField.VEIN_TOGGLE));
        assertFalse(coreFields.contains(RouterField.FINAL_DENSITY));
    }
    
    @Test
    void testExtendedTierIncludesFluidFields() {
        EnumSet<RouterField> extendedFields = NoiseTap.getTierFields(PerformanceTier.EXTENDED);
        
        // Extended should include all core fields plus fluid/environmental fields
        assertEquals(10, extendedFields.size());
        
        // Should include core fields
        assertTrue(extendedFields.contains(RouterField.TEMPERATURE));
        assertTrue(extendedFields.contains(RouterField.RIDGES));
        
        // Should include Tier B fluid fields
        assertTrue(extendedFields.contains(RouterField.FLUID_FLOODEDNESS));
        assertTrue(extendedFields.contains(RouterField.FLUID_SPREAD));
        assertTrue(extendedFields.contains(RouterField.LAVA));
        assertTrue(extendedFields.contains(RouterField.BARRIER));
    }
    
    @Test
    void testCaveAwareTierIncludesDensity() {
        EnumSet<RouterField> caveFields = NoiseTap.getTierFields(PerformanceTier.CAVE_AWARE);
        
        // Cave-aware should include extended fields plus density fields
        assertEquals(12, caveFields.size());
        
        // Should include density fields for underground context
        assertTrue(caveFields.contains(RouterField.INITIAL_DENSITY_NO_JAG));
        assertTrue(caveFields.contains(RouterField.FINAL_DENSITY));
        
        // Should still include all extended fields
        assertTrue(caveFields.contains(RouterField.FLUID_FLOODEDNESS));
        assertTrue(caveFields.contains(RouterField.TEMPERATURE));
    }
    
    @Test
    void testFullTierIncludesAllFields() {
        EnumSet<RouterField> allFields = NoiseTap.getTierFields(PerformanceTier.FULL);
        
        // Full tier should include all 15 router fields
        assertEquals(15, allFields.size());
        assertEquals(EnumSet.allOf(RouterField.class), allFields);
        
        // Should include expensive vein fields
        assertTrue(allFields.contains(RouterField.VEIN_TOGGLE));
        assertTrue(allFields.contains(RouterField.VEIN_RIDGED));
        assertTrue(allFields.contains(RouterField.VEIN_GAP));
    }
    
    @Test
    void testDefaultHeightmaps() {
        EnumSet<Heightmap.Type> defaultHMs = NoiseTap.getDefaultHeightmaps();
        
        // Should include the 3 essential heightmap types for terrain generation
        assertEquals(3, defaultHMs.size());
        assertTrue(defaultHMs.contains(Heightmap.Type.WORLD_SURFACE_WG));
        assertTrue(defaultHMs.contains(Heightmap.Type.OCEAN_FLOOR_WG));
        assertTrue(defaultHMs.contains(Heightmap.Type.MOTION_BLOCKING));
    }
    
    @Test
    void testCacheRecordMethods() {
        // Create a minimal cache for testing convenience methods
        var cache = new NoiseTap.Cache(
            java.util.Map.of(), // empty router fields
            new int[4][4][4],   // empty biomes
            java.util.Map.of(), // empty heightmaps
            -64, 384, 0, 0, 12345L
        );
        
        // Test metadata access
        assertEquals(-64, cache.chunkMinY());
        assertEquals(384, cache.chunkHeight());
        assertEquals(0, cache.chunkX());
        assertEquals(0, cache.chunkZ());
        assertEquals(12345L, cache.seed());
        
        // Test field count
        assertEquals(0, cache.getRouterFieldCount());
        
        // Test memory footprint calculation (should be minimal for empty cache)
        long footprint = cache.getMemoryFootprint();
        assertTrue(footprint > 0); // Should include biome array size
        assertEquals(4 * 4 * 4 * Integer.BYTES, footprint); // Just biome array
    }
    
    @Test
    void testBiomeBoundsChecking() {
        var cache = new NoiseTap.Cache(
            java.util.Map.of(),
            new int[4][4][4],
            java.util.Map.of(),
            -64, 384, 0, 0, 12345L
        );
        
        // Valid coordinates should work
        assertDoesNotThrow(() -> cache.getBiomeId(0, 0, 0));
        assertDoesNotThrow(() -> cache.getBiomeId(3, 3, 3));
        
        // Invalid coordinates should throw
        assertThrows(IndexOutOfBoundsException.class, () -> cache.getBiomeId(-1, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.getBiomeId(4, 0, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.getBiomeId(0, 4, 0));
        assertThrows(IndexOutOfBoundsException.class, () -> cache.getBiomeId(0, 0, 4));
    }
    
    @Test
    void testMemoryFootprintCalculation() {
        // Test memory footprint calculation with known data sizes
        var routerFields = java.util.Map.of(
            RouterField.TEMPERATURE, new float[16][16][16],
            RouterField.FINAL_DENSITY, new float[16][16][16]
        );
        var heightmaps = java.util.Map.of(
            Heightmap.Type.WORLD_SURFACE_WG, new short[16][16],
            Heightmap.Type.OCEAN_FLOOR_WG, new short[16][16]
        );
        
        var cache = new NoiseTap.Cache(
            routerFields,
            new int[4][4][4],
            heightmaps,
            -64, 384, 0, 0, 12345L
        );
        
        long expectedBytes = 
            2 * 16 * 16 * 16 * Float.BYTES +  // 2 router fields
            4 * 4 * 4 * Integer.BYTES +       // biome array
            2 * 16 * 16 * Short.BYTES;        // 2 heightmaps
            
        assertEquals(expectedBytes, cache.getMemoryFootprint());
    }
    
    /**
     * Integration test for real noise capture - disabled until we have proper Minecraft test environment.
     */
    @Test
    @Disabled("Requires Minecraft chunk context - enable when test environment is ready")
    void testRealNoiseCapture() {
        // TODO: Once we have proper Minecraft test setup, this will test:
        // 1. NoiseTap.bind() with real chunk/noiseConfig/biomeAccess
        // 2. captureAll() with various field combinations
        // 3. Verification that cached data matches expected shapes and ranges
        
        // Example structure:
        // var noiseTap = NoiseTap.bind(testChunk, testNoiseConfig, testBiomeAccess, 12345L);
        // var cache = noiseTap.captureAll(
        //     NoiseTap.getTierFields(PerformanceTier.CORE),
        //     NoiseTap.getDefaultHeightmaps()
        // );
        // assertNotNull(cache);
        // assertEquals(6, cache.getRouterFieldCount());
        
        System.out.println("Real noise capture test requires Minecraft environment - use NoiseSpeedProbe for actual measurement");
    }
}
