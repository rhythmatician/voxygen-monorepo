package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Stage 2 behavior spec (ADR 0011): the model-free End scaffold produces
 * semantic regions at every Level L1..L4 via centre-sample rasterization at
 * that Level's voxel size ({@code 16 << level} blocks/voxel). Same honest
 * omissions and Y-padding rules as Stage 1's L4-only tracer.
 */
class EndScaffoldMultiLevelTest {

    private static WorldNoiseAccess solidNoise() {
        WorldNoiseAccess mockNa = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(mockNa.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        return mockNa;
    }

    @Test
    void producesRegions_atEveryLevelL1ToL4() {
        var cand = new EndL4DeterministicCandidate(solidNoise());
        for (Level level : new Level[] {Level.L4, Level.L3, Level.L2, Level.L1}) {
            int rs = level.regionSections();
            SectionPos origin = new SectionPos(0, 0, 0); // aligned to all levels
            VoxelVolume vol = cand.produceRegion(level, origin);
            assertEquals(32, vol.extent(), "extent 32 at " + level);
            assertFalse(vol.isAllAir(), "solid noise must produce terrain at " + level);
        }
    }

    @Test
    void rejectsLevelsFinerThanL1() {
        var cand = new EndL4DeterministicCandidate(solidNoise());
        assertThrows(IllegalArgumentException.class,
                () -> cand.produceRegion(Level.L0, new SectionPos(0, 0, 0)));
    }

    @Test
    void alignmentGuard_respectsEachLevelRegionSections() {
        var cand = new EndL4DeterministicCandidate(solidNoise());
        // L3 regionSections=16: origin x=8 is misaligned.
        assertThrows(IllegalArgumentException.class,
                () -> cand.produceRegion(Level.L3, new SectionPos(8, 0, 0)));
        // L2 regionSections=8: origin x=8 IS aligned; x=4 is not.
        assertDoesNotThrow(() -> cand.produceRegion(Level.L2, new SectionPos(8, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> cand.produceRegion(Level.L2, new SectionPos(4, 0, 0)));
        // L1 regionSections=4: origin x=4 aligned; x=2 not.
        assertDoesNotThrow(() -> cand.produceRegion(Level.L1, new SectionPos(4, 0, 0)));
        assertThrows(IllegalArgumentException.class,
                () -> cand.produceRegion(Level.L1, new SectionPos(2, 0, 0)));
    }

    @Test
    void voxelSize_scalesWithLevel_centreSampleCoordinates() {
        WorldNoiseAccess na = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(na.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        var cand = new EndL4DeterministicCandidate(na);

        // Voxy geometry: node at level L = (32<<L) blocks, always 32^3 voxels,
        // so voxel = 2^L blocks: L4=16, L3=8, L2=4, L1=2.
        // L3: origin (16,0,0) -> baseBlockX = 256; first centre = 256+0*8+4 = 260; y=z=4.
        cand.produceRegion(Level.L3, new SectionPos(16, 0, 0));
        Mockito.verify(na).sampleFinalDensity(260, 4, 4);

        // L1: origin (4,0,0) -> baseBlockX = 64; first centre = 64+0*2+1 = 65; y=z=1.
        Mockito.reset(na);
        Mockito.when(na.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        cand.produceRegion(Level.L1, new SectionPos(4, 0, 0));
        Mockito.verify(na).sampleFinalDensity(65, 1, 1);
    }

    @Test
    void evaluationCount_scalesWithActiveVoxels() {
        WorldNoiseAccess na = Mockito.mock(WorldNoiseAccess.class);
        Mockito.when(na.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        var cand = new EndL4DeterministicCandidate(na);

        // Region at level L spans (32<<L) blocks; active Y slices per region
        // = min(32, ceil(128 / voxelBlocks)) with voxelBlocks = 2^L:
        //   L3 (8-block voxels, 256-block region): 16 slices -> 16384 evals.
        cand.produceRegion(Level.L3, new SectionPos(0, 0, 0));
        Mockito.verify(na, Mockito.times(16384))
                .sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());

        //   L1 (2-block voxels, 64-block region): region [0,64) inside [0,128)
        //   -> all 32 slices active -> 32768 evals.
        Mockito.reset(na);
        Mockito.when(na.sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt()))
                .thenReturn(1.0);
        cand.produceRegion(Level.L1, new SectionPos(0, 0, 0));
        Mockito.verify(na, Mockito.times(32768))
                .sampleFinalDensity(Mockito.anyInt(), Mockito.anyInt(), Mockito.anyInt());
    }

    @Test
    void yPaddingOutsideEndRange_airAtEveryLevel() {
        var cand = new EndL4DeterministicCandidate(solidNoise());
        for (Level level : new Level[] {Level.L3, Level.L2, Level.L1}) {
            VoxelVolume vol = cand.produceRegion(level, new SectionPos(0, 0, 0));
            int voxelBlocks = 1 << level.value();
            int activeSlices = Math.min(32, (128 + voxelBlocks - 1) / voxelBlocks);
            for (int y = activeSlices; y < 32; y++) {
                assertEquals(0, vol.blockId(0, y, 0),
                        "Y outside [0,128) must be air at " + level + " y=" + y);
            }
            assertEquals(359, vol.blockId(0, 0, 0),
                    "active slice end_stone at " + level);
        }
    }

    @Test
    void vocabulary_reducedToAirAndEndStone_atEveryLevel() {
        var cand = new EndL4DeterministicCandidate(solidNoise());
        for (Level level : new Level[] {Level.L4, Level.L3, Level.L2, Level.L1}) {
            VoxelVolume vol = cand.produceRegion(level, new SectionPos(0, 0, 0));
            for (int y = 0; y < 32; y += 7) {
                for (int z = 0; z < 32; z += 5) {
                    for (int x = 0; x < 32; x += 3) {
                        int bid = vol.blockId(x, y, z);
                        assertTrue(bid == 0 || bid == 359,
                                "only air|end_stone at " + level + ", got " + bid);
                    }
                }
            }
        }
    }
}
