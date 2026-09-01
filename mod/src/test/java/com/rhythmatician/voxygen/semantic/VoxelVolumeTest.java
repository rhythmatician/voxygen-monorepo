package com.rhythmatician.voxygen.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Behavior-focused tests for VoxelVolume write-contract slice.
 * Covers extents, validation, coordinate ordering, bounds, builder isolation,
 * copy independence, isAllAir and countNonAir.
 */
class VoxelVolumeTest {

    // ---- extent acceptance ----
    @Test
    void uniform_acceptsExtent16And32() {
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(32, 0, 0));
        assertEquals(16, VoxelVolume.uniform(16, 0, 0).extent());
        assertEquals(32, VoxelVolume.uniform(32, 0, 0).extent());
    }

    @Test
    void builder_acceptsExtent16And32() {
        assertDoesNotThrow(() -> VoxelVolume.builder(16));
        assertDoesNotThrow(() -> VoxelVolume.builder(32));
        assertEquals(16, VoxelVolume.builder(16).build().extent());
        assertEquals(32, VoxelVolume.builder(32).build().extent());
    }

    @Test
    void extent_rejectsOtherValues() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(15, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(17, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(0, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(-1, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(33, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(64, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(15));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(31));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateExtent(17));
    }

    // ---- canonical block/biome validation ----
    @Test
    void blockValidation_acceptsBoundariesAndRejectsOthers() {
        // valid: 0, 1, 1103
        assertDoesNotThrow(() -> VoxelVolume.validateBlockId(0));
        assertDoesNotThrow(() -> VoxelVolume.validateBlockId(1));
        assertDoesNotThrow(() -> VoxelVolume.validateBlockId(CanonicalRegistries.BLOCK_ID_MAX));
        // via uniform/builder
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, CanonicalRegistries.BLOCK_ID_MAX, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBlockId(-1));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBlockId(CanonicalRegistries.BLOCK_COUNT));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBlockId(1104));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 1104, 0));
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        assertThrows(IllegalArgumentException.class, () -> b.setBlock(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> b.setBlock(0, 0, 0, 9999));
        assertThrows(IllegalArgumentException.class, () -> b.fill(-1, 0));
    }

    @Test
    void biomeValidation_acceptsBoundariesAndRejectsOthers() {
        assertDoesNotThrow(() -> VoxelVolume.validateBiomeId(0));
        assertDoesNotThrow(() -> VoxelVolume.validateBiomeId(53));
        assertDoesNotThrow(() -> VoxelVolume.validateBiomeId(255));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 255));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBiomeId(-1));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBiomeId(54));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBiomeId(100));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.validateBiomeId(256));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 0, 54));
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        assertThrows(IllegalArgumentException.class, () -> b.setBiome(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> b.setBiome(0, 0, 0, 54));
        assertThrows(IllegalArgumentException.class, () -> b.fill(0, 54));
        assertThrows(IllegalArgumentException.class, () -> b.fill(0, 256));
    }

    // ---- asymmetric XYZ sentinel proving coordinate ordering ----
    @Test
    void coordinateOrdering_asymmetricSentinels() {
        // Use distinct values to prove x,y,z ordering is not swapped
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        b.setBlock(1, 2, 3, 10);
        b.setBiome(1, 2, 3, 11);
        b.setBlock(3, 2, 1, 20);
        b.setBiome(3, 2, 1, 21);
        b.setBlock(2, 3, 1, 30);
        b.setBiome(2, 3, 1, 31);
        VoxelVolume v = b.build();
        // correct positions return correct sentinels
        assertEquals(10, v.blockId(1, 2, 3));
        assertEquals(11, v.biomeId(1, 2, 3));
        assertEquals(20, v.blockId(3, 2, 1));
        assertEquals(21, v.biomeId(3, 2, 1));
        assertEquals(30, v.blockId(2, 3, 1));
        assertEquals(31, v.biomeId(2, 3, 1));
        // swapped coordinates return different values (air/unknown default)
        assertNotEquals(10, v.blockId(3, 2, 1) == 10 ? 20 : v.blockId(3, 1, 2));
        // explicitly check that (1,2,3) != (3,2,1)
        assertNotEquals(v.blockId(1, 2, 3), v.blockId(3, 2, 1));
        // and (1,2,3) via y/z swap
        assertEquals(0, v.blockId(1, 3, 2));
        assertEquals(CanonicalRegistries.BIOME_UNKNOWN, v.biomeId(1, 3, 2));
        // also verify internal index formula via multiple distinct points
        VoxelVolume.Builder b2 = VoxelVolume.builder(16);
        b2.setBlock(0, 0, 1, 100);
        b2.setBlock(0, 1, 0, 101);
        b2.setBlock(1, 0, 0, 102);
        VoxelVolume v2 = b2.build();
        assertEquals(100, v2.blockId(0, 0, 1));
        assertEquals(101, v2.blockId(0, 1, 0));
        assertEquals(102, v2.blockId(1, 0, 0));
        // ensure they don't alias
        assertNotEquals(v2.blockId(0, 0, 1), v2.blockId(0, 1, 0));
        assertNotEquals(v2.blockId(0, 1, 0), v2.blockId(1, 0, 0));
    }

    // ---- bounds checks on every axis ----
    @Test
    void boundsChecks_onEveryAxis() {
        VoxelVolume v = VoxelVolume.uniform(16, 1, 0);
        // valid corners
        assertDoesNotThrow(() -> v.blockId(0, 0, 0));
        assertDoesNotThrow(() -> v.blockId(15, 15, 15));
        assertDoesNotThrow(() -> v.biomeId(15, 15, 15));
        // x negative / overflow — assert message to distinguish checkBounds from array OOB
        assertThrowsWithMessage(() -> v.blockId(-1, 0, 0));
        assertThrowsWithMessage(() -> v.blockId(16, 0, 0));
        assertThrowsWithMessage(() -> v.biomeId(-1, 0, 0));
        assertThrowsWithMessage(() -> v.biomeId(16, 0, 0));
        // y negative / overflow
        assertThrowsWithMessage(() -> v.blockId(0, -1, 0));
        assertThrowsWithMessage(() -> v.blockId(0, 16, 0));
        assertThrowsWithMessage(() -> v.biomeId(0, -1, 0));
        assertThrowsWithMessage(() -> v.biomeId(0, 16, 0));
        // z negative / overflow
        assertThrowsWithMessage(() -> v.blockId(0, 0, -1));
        assertThrowsWithMessage(() -> v.blockId(0, 0, 16));
        assertThrowsWithMessage(() -> v.biomeId(0, 0, -1));
        assertThrowsWithMessage(() -> v.biomeId(0, 0, 16));
        // builder bounds
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        assertThrowsWithMessage(() -> b.setBlock(-1, 0, 0, 1));
        assertThrowsWithMessage(() -> b.setBlock(16, 0, 0, 1));
        assertThrowsWithMessage(() -> b.setBlock(0, -1, 0, 1));
        assertThrowsWithMessage(() -> b.setBlock(0, 16, 0, 1));
        assertThrowsWithMessage(() -> b.setBlock(0, 0, -1, 1));
        assertThrowsWithMessage(() -> b.setBlock(0, 0, 16, 1));
        assertThrowsWithMessage(() -> b.setBiome(-1, 0, 0, 1));
        assertThrowsWithMessage(() -> b.setBiome(0, -1, 0, 1));
        assertThrowsWithMessage(() -> b.setBiome(0, 0, 16, 1));
        // edge valid still works
        assertDoesNotThrow(() -> b.setBlock(15, 15, 15, 1));
        assertDoesNotThrow(() -> b.setBiome(0, 0, 0, 255));
        // also test extent 32 boundaries to kill mutants for that size
        VoxelVolume v32 = VoxelVolume.uniform(32, 1, 0);
        assertDoesNotThrow(() -> v32.blockId(31, 31, 31));
        assertThrowsWithMessage(() -> v32.blockId(32, 0, 0));
        assertThrowsWithMessage(() -> v32.blockId(0, 32, 0));
        assertThrowsWithMessage(() -> v32.blockId(0, 0, 32));
        assertThrowsWithMessage(() -> v32.blockId(-1, 0, 0));
        VoxelVolume.Builder b32 = VoxelVolume.builder(32);
        assertDoesNotThrow(() -> b32.setBlock(31, 31, 31, 1));
        assertThrowsWithMessage(() -> b32.setBlock(32, 0, 0, 1));
        assertThrowsWithMessage(() -> b32.setBlock(0, 32, 0, 1));
        assertThrowsWithMessage(() -> b32.setBlock(0, 0, 32, 1));
    }

    private static void assertThrowsWithMessage(ThrowingRunnable r) {
        IndexOutOfBoundsException ex = assertThrows(IndexOutOfBoundsException.class, r::run);
        assertTrue(ex.getMessage().contains("coords out of bounds"),
                "expected coords out of bounds message but got: " + ex.getMessage());
    }

    @FunctionalInterface
    private interface ThrowingRunnable { void run() throws Exception; }

    @Test
    void boundsChecks_allAxesExtent32AndBoundaryValues() {
        // Directly test each boundary value for both extents to kill ConditionalBoundary mutants
        for (int extent : new int[]{16, 32}) {
            VoxelVolume v = VoxelVolume.uniform(extent, 1, 0);
            // x axis: -1 throw, 0 ok, extent-1 ok, extent throw
            assertThrowsWithMessage(() -> v.blockId(-1, 0, 0));
            assertDoesNotThrow(() -> v.blockId(0, 0, 0));
            assertDoesNotThrow(() -> v.blockId(extent - 1, 0, 0));
            assertThrowsWithMessage(() -> v.blockId(extent, 0, 0));
            // y axis
            assertThrowsWithMessage(() -> v.blockId(0, -1, 0));
            assertDoesNotThrow(() -> v.blockId(0, 0, 0));
            assertDoesNotThrow(() -> v.blockId(0, extent - 1, 0));
            assertThrowsWithMessage(() -> v.blockId(0, extent, 0));
            // z axis
            assertThrowsWithMessage(() -> v.blockId(0, 0, -1));
            assertDoesNotThrow(() -> v.blockId(0, 0, 0));
            assertDoesNotThrow(() -> v.blockId(0, 0, extent - 1));
            assertThrowsWithMessage(() -> v.blockId(0, 0, extent));
            // builder same
            VoxelVolume.Builder b = VoxelVolume.builder(extent);
            assertThrowsWithMessage(() -> b.setBlock(-1, 0, 0, 1));
            assertThrowsWithMessage(() -> b.setBlock(extent, 0, 0, 1));
            assertThrowsWithMessage(() -> b.setBiome(0, extent, 0, 1));
            assertDoesNotThrow(() -> b.setBlock(extent - 1, extent - 1, extent - 1, 1));
        }
    }

    // ---- builder mutation does not alter already built volume ----
    @Test
    void builderMutation_doesNotAlterAlreadyBuiltVolume() {
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        b.setBlock(0, 0, 0, 5);
        b.setBiome(0, 0, 0, 10);
        VoxelVolume v1 = b.build();
        // mutate builder after build
        b.setBlock(0, 0, 0, 99);
        b.setBiome(0, 0, 0, 20);
        b.setBlock(1, 1, 1, 77);
        VoxelVolume v2 = b.build();
        // v1 unchanged
        assertEquals(5, v1.blockId(0, 0, 0));
        assertEquals(10, v1.biomeId(0, 0, 0));
        assertEquals(0, v1.blockId(1, 1, 1));
        // v2 reflects mutations
        assertEquals(99, v2.blockId(0, 0, 0));
        assertEquals(20, v2.biomeId(0, 0, 0));
        assertEquals(77, v2.blockId(1, 1, 1));
        // builder fill after build also isolated
        VoxelVolume.Builder b2 = VoxelVolume.builder(16);
        b2.fill(1, 2);
        VoxelVolume vb1 = b2.build();
        b2.fill(5, 6);
        VoxelVolume vb2 = b2.build();
        assertEquals(1, vb1.blockId(0, 0, 0));
        assertEquals(2, vb1.biomeId(0, 0, 0));
        assertEquals(5, vb2.blockId(0, 0, 0));
    }

    // ---- copy() is independent ----
    @Test
    void copy_isIndependent() {
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        b.setBlock(2, 3, 4, 42);
        b.setBiome(2, 3, 4, 7);
        VoxelVolume original = b.build();
        VoxelVolume copy = original.copy();
        // not same instance
        assertNotSame(original, copy);
        // same content initially
        assertEquals(42, copy.blockId(2, 3, 4));
        assertEquals(7, copy.biomeId(2, 3, 4));
        assertEquals(original.blockId(0, 0, 0), copy.blockId(0, 0, 0));
        // mutating builder that produced original does not affect either
        b.setBlock(2, 3, 4, 99);
        VoxelVolume after = b.build();
        assertEquals(42, original.blockId(2, 3, 4));
        assertEquals(42, copy.blockId(2, 3, 4));
        assertEquals(99, after.blockId(2, 3, 4));
        // copy has same extent
        assertEquals(original.extent(), copy.extent());
    }

    // ---- isAllAir and countNonAir ----
    @Test
    void isAllAir_andCountNonAir_distinguishOneNonAirAtMultipleCoords() {
        VoxelVolume allAir16 = VoxelVolume.uniform(16, 0, CanonicalRegistries.BIOME_UNKNOWN);
        assertTrue(allAir16.isAllAir());
        assertEquals(0, allAir16.countNonAir());
        VoxelVolume allAir32 = VoxelVolume.uniform(32, 0, 0);
        assertTrue(allAir32.isAllAir());
        assertEquals(0, allAir32.countNonAir());

        // one non-air at (0,0,0)
        VoxelVolume.Builder b1 = VoxelVolume.builder(16);
        b1.fill(0, 0);
        b1.setBlock(0, 0, 0, 1);
        VoxelVolume v1 = b1.build();
        assertFalse(v1.isAllAir());
        assertEquals(1, v1.countNonAir());
        assertEquals(1, v1.blockId(0, 0, 0));

        // one non-air at far corner (15,15,15) for 16
        VoxelVolume.Builder b2 = VoxelVolume.builder(16);
        b2.setBlock(15, 15, 15, 1);
        VoxelVolume v2 = b2.build();
        assertFalse(v2.isAllAir());
        assertEquals(1, v2.countNonAir());

        // one non-air at middle (7,7,7)
        VoxelVolume.Builder b3 = VoxelVolume.builder(16);
        b3.setBlock(7, 7, 7, 5);
        VoxelVolume v3 = b3.build();
        assertFalse(v3.isAllAir());
        assertEquals(1, v3.countNonAir());

        // extent 32 variants
        for (int[] coord : new int[][]{{0, 0, 0}, {31, 31, 31}, {15, 15, 15}, {0, 31, 0}}) {
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            b.setBlock(coord[0], coord[1], coord[2], 2);
            VoxelVolume v = b.build();
            assertFalse(v.isAllAir(), "should not be all air at " + coord[0] + "," + coord[1] + "," + coord[2]);
            assertEquals(1, v.countNonAir());
        }

        // two non-air voxels count 2, not all air
        VoxelVolume.Builder b4 = VoxelVolume.builder(16);
        b4.setBlock(0, 0, 0, 1);
        b4.setBlock(1, 1, 1, 1);
        VoxelVolume v4 = b4.build();
        assertFalse(v4.isAllAir());
        assertEquals(2, v4.countNonAir());

        // fill with non-air: all voxels non-air
        VoxelVolume filled = VoxelVolume.uniform(16, 1, 0);
        assertFalse(filled.isAllAir());
        assertEquals(16 * 16 * 16, filled.countNonAir());
    }

    @Test
    void uniform_fill_blockAndBiome() {
        VoxelVolume v = VoxelVolume.uniform(16, 7, 42);
        assertEquals(7, v.blockId(5, 5, 5));
        assertEquals(42, v.biomeId(5, 5, 5));
        assertEquals(16, v.extent());
        // builder fill similarly
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        b.fill(11, 22);
        VoxelVolume v2 = b.build();
        assertEquals(11, v2.blockId(0, 0, 0));
        assertEquals(11, v2.blockId(31, 31, 31));
        assertEquals(22, v2.biomeId(10, 10, 10));
    }
}
