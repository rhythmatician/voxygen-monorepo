package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Deterministic unit tests for the semantic writer contract and
 * {@link InMemoryVolumeWriter}. No Voxy/Minecraft runtime classes are loaded.
 */
class VoxelVolumeWriterTest {

    private InMemoryVolumeWriter writer;

    @BeforeEach
    void setUp() {
        writer = new InMemoryVolumeWriter();
    }

    // ------------------------------------------------------------------
    // VoxelVolume invariants
    // ------------------------------------------------------------------

    @Test
    void voxelVolume_extentMustBe16Or32() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(8, 0, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(24, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(32, 0, 0));
    }

    @Test
    void voxelVolume_blockAndBiomeAccessors() {
        VoxelVolume v = VoxelVolume.builder(16).setBlock(1, 2, 3, 42).setBiome(1, 2, 3, 7).build();
        assertEquals(42, v.blockId(1, 2, 3));
        assertEquals(7, v.biomeId(1, 2, 3));
        assertEquals(0, v.blockId(0, 0, 0));
    }

    @Test
    void voxelVolume_copyIsDefensive() {
        VoxelVolume a = VoxelVolume.uniform(16, 5, 7);
        VoxelVolume b = a.copy();
        assertEquals(a.extent(), b.extent());
        assertEquals(a.blockId(0, 0, 0), b.blockId(0, 0, 0));
        // mutating source builder after build must not affect built volume
        VoxelVolume.Builder builder = VoxelVolume.builder(16).setBlock(0, 0, 0, 5);
        VoxelVolume v1 = builder.build();
        builder.setBlock(0, 0, 0, 9);
        VoxelVolume v2 = builder.build();
        assertEquals(5, v1.blockId(0, 0, 0));
        assertEquals(9, v2.blockId(0, 0, 0));
    }

    // ------------------------------------------------------------------
    // Canonical ID validation
    // ------------------------------------------------------------------

    @Test
    void voxelVolume_rejectsInvalidBlockId_uniform() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, -1, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, -999, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, CanonicalRegistries.BLOCK_COUNT, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 9999, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(32, Integer.MAX_VALUE, 0));
    }

    @Test
    void voxelVolume_rejectsInvalidBiomeId_uniform() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 0, 54));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 0, 200));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(16, 0, 256));
        // valid boundaries
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 0));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 53));
        assertDoesNotThrow(() -> VoxelVolume.uniform(16, 0, 255));
    }

    @Test
    void voxelVolume_builder_rejectsInvalidBlockId() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).setBlock(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).setBlock(0, 0, 0, 2000));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).fill(-1, 0));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).fill(9999, 0));
    }

    @Test
    void voxelVolume_builder_rejectsInvalidBiomeId() {
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).setBiome(0, 0, 0, -1));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).setBiome(0, 0, 0, 54));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.builder(16).fill(0, 999));
        // valid
        assertDoesNotThrow(() -> VoxelVolume.builder(16).setBiome(0, 0, 0, 255));
        assertDoesNotThrow(() -> VoxelVolume.builder(32).setBiome(31, 31, 31, 53));
    }

    @Test
    void canonicalRegistries_bounds_derivedFromContract() {
        assertEquals(1104, CanonicalRegistries.BLOCK_COUNT);
        assertEquals(1103, CanonicalRegistries.BLOCK_ID_MAX);
        assertEquals(54, CanonicalRegistries.BIOME_COUNT);
        assertEquals(255, CanonicalRegistries.BIOME_UNKNOWN);
        assertTrue(CanonicalRegistries.isValidBlockId(0));
        assertTrue(CanonicalRegistries.isValidBlockId(1103));
        assertFalse(CanonicalRegistries.isValidBlockId(1104));
        assertFalse(CanonicalRegistries.isValidBlockId(-1));
        assertTrue(CanonicalRegistries.isValidBiomeId(0));
        assertTrue(CanonicalRegistries.isValidBiomeId(53));
        assertTrue(CanonicalRegistries.isValidBiomeId(255));
        assertFalse(CanonicalRegistries.isValidBiomeId(54));
        assertFalse(CanonicalRegistries.isValidBiomeId(200));
    }

    // ------------------------------------------------------------------
    // SectionPos / Level
    // ------------------------------------------------------------------

    @Test
    void level_voxelSizeAndRegionSections() {
        assertEquals(1, Level.L0.voxelSize());
        assertEquals(32, Level.L0.regionBlocks());
        assertEquals(2, Level.L0.regionSections());
        assertEquals(4, Level.L1.regionSections());
        assertEquals(64, Level.L1.regionBlocks());
        assertEquals(512, Level.L4.regionBlocks());
        assertEquals(32, Level.L4.regionSections());
    }

    @ParameterizedTest
    @EnumSource(Level.class)
    void level_allAlignmentsAtMultiples(Level lvl) {
        int s = lvl.regionSections();
        assertTrue(lvl.isAligned(new SectionPos(0, 0, 0)));
        assertTrue(lvl.isAligned(new SectionPos(s, s, s)));
        assertTrue(lvl.isAligned(new SectionPos(-s, 0, 2 * s)));
    }

    @ParameterizedTest
    @EnumSource(Level.class)
    void level_rejectsOffByOne(Level lvl) {
        int s = lvl.regionSections();
        if (s > 1) {
            assertFalse(lvl.isAligned(new SectionPos(1, 0, 0)));
            assertFalse(lvl.isAligned(new SectionPos(0, 1, 0)));
            assertFalse(lvl.isAligned(new SectionPos(0, 0, 1)));
        }
    }

    // ------------------------------------------------------------------
    // writeSection contract
    // ------------------------------------------------------------------

    @Test
    void writeSection_writesAndCountsNonAir() {
        VoxelVolume v = VoxelVolume.uniform(16, 0, 0);
        WriteOutcome o0 = writer.writeSection(new SectionPos(0, 0, 0), v);
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, o0.status());
        assertEquals(0, o0.nonAirWritten());

        VoxelVolume solid = VoxelVolume.builder(16).setBlock(0, 0, 0, 1).build();
        WriteOutcome o1 = writer.writeSection(new SectionPos(1, 0, 0), solid);
        assertEquals(WriteOutcome.Status.WRITTEN, o1.status());
        assertEquals(1, o1.nonAirWritten());
        assertEquals(1, writer.sectionRecords().size());
    }

    @Test
    void writeSection_secondWriteSamePos_skippedExists() {
        VoxelVolume solid = VoxelVolume.builder(16).setBlock(0, 0, 0, 1).build();
        writer.writeSection(new SectionPos(2, 0, 0), solid);
        WriteOutcome o2 = writer.writeSection(new SectionPos(2, 0, 0), solid);
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o2.status());
        assertEquals(1, writer.sectionRecords().size());
    }

    @Test
    void writeSection_existsPrecedenceOverAllAir() {
        // Solid at P then all-air at same P must return SKIPPED_EXISTS not SKIPPED_AIR
        SectionPos p = new SectionPos(20, 0, 0);
        VoxelVolume solid = VoxelVolume.builder(16).setBlock(0, 0, 0, 1).build();
        VoxelVolume air = VoxelVolume.uniform(16, 0, 0);
        writer.writeSection(p, solid);
        WriteOutcome o = writer.writeSection(p, air);
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o.status());
        assertEquals(1, writer.sectionRecords().size());
    }

    @Test
    void writeSection_rejectsWrongExtent() {
        VoxelVolume v32 = VoxelVolume.uniform(32, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> writer.writeSection(new SectionPos(0, 0, 0), v32));
    }

    @Test
    void writeSection_rejectsNullArgs_npe() {
        VoxelVolume v = VoxelVolume.uniform(16, 0, 0);
        assertThrows(NullPointerException.class, () -> writer.writeSection(null, v));
        assertThrows(NullPointerException.class, () -> writer.writeSection(new SectionPos(0, 0, 0), null));
    }

    @Test
    void writeSection_defensiveCopy() {
        VoxelVolume.Builder b = VoxelVolume.builder(16).setBlock(0, 0, 0, 5);
        VoxelVolume v = b.build();
        writer.writeSection(new SectionPos(3, 0, 0), v);
        b.setBlock(0, 0, 0, 9);
        VoxelVolume captured = writer.sectionRecords().get(0).volume();
        assertEquals(5, captured.blockId(0, 0, 0));
    }

    @Test
    void writeSection_unavailableThrows() {
        writer.setUnavailable(true);
        assertThrows(VolumeUnavailableException.class,
                () -> writer.writeSection(new SectionPos(0, 0, 0), VoxelVolume.uniform(16, 1, 0)));
        assertInstanceOf(IllegalStateException.class,
                assertThrows(VolumeUnavailableException.class,
                        () -> writer.writeSection(new SectionPos(0, 0, 0), VoxelVolume.uniform(16, 1, 0))));
        // VolumeUnavailableException carries no Voxy types
        assertEquals("x", new VolumeUnavailableException("x").getMessage());
    }

    // ------------------------------------------------------------------
    // writeRegion contract
    // ------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(Level.class)
    void writeRegion_alignedWrites(Level lvl) {
        int s = lvl.regionSections();
        SectionPos origin = new SectionPos(s, 0, 0);
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        WriteOutcome o = writer.writeRegion(origin, lvl, v);
        assertEquals(WriteOutcome.Status.WRITTEN, o.status());
    }

    @Test
    void writeRegion_allAirSkipped() {
        VoxelVolume v = VoxelVolume.uniform(32, 0, 0);
        WriteOutcome o = writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, v);
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, o.status());
        assertTrue(writer.regionRecords().isEmpty());
    }

    @Test
    void writeRegion_secondSameOriginAndLevel_skippedExists() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        WriteOutcome o2 = writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o2.status());
        assertEquals(1, writer.regionRecords().size());
    }

    @Test
    void writeRegion_existsPrecedenceOverAllAir() {
        SectionPos origin = new SectionPos(0, 0, 0);
        VoxelVolume solid = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        VoxelVolume air = VoxelVolume.uniform(32, 0, 0);
        writer.writeRegion(origin, Level.L2, solid);
        WriteOutcome o = writer.writeRegion(origin, Level.L2, air);
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o.status());
        assertEquals(1, writer.regionRecords().size());
    }

    @Test
    void writeRegion_sameOriginDifferentLevel_isDistinct() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        WriteOutcome o2 = writer.writeRegion(new SectionPos(0, 0, 0), Level.L3, v);
        assertEquals(WriteOutcome.Status.WRITTEN, o2.status());
        assertEquals(2, writer.regionRecords().size());
    }

    @Test
    void writeRegion_rejectsMisaligned() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        // L1 regionSections=4, origin x=1 is misaligned
        assertThrows(IllegalArgumentException.class, () -> writer.writeRegion(new SectionPos(1, 0, 0), Level.L1, v));
        assertThrows(IllegalArgumentException.class, () -> writer.writeRegion(new SectionPos(0, 1, 0), Level.L1, v));
        assertThrows(IllegalArgumentException.class, () -> writer.writeRegion(new SectionPos(0, 0, 1), Level.L1, v));
    }

    @Test
    void writeRegion_rejectsWrongExtent() {
        VoxelVolume v16 = VoxelVolume.uniform(16, 1, 0);
        assertThrows(IllegalArgumentException.class, () -> writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, v16));
    }

    @Test
    void writeRegion_rejectsNullArgs_npe() {
        VoxelVolume v = VoxelVolume.uniform(32, 1, 0);
        assertThrows(NullPointerException.class, () -> writer.writeRegion(null, Level.L0, v));
        assertThrows(NullPointerException.class, () -> writer.writeRegion(new SectionPos(0, 0, 0), null, v));
        assertThrows(NullPointerException.class, () -> writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, null));
    }

    @Test
    void writeRegion_unavailableThrows() {
        writer.setUnavailable(true);
        assertThrows(VolumeUnavailableException.class,
                () -> writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, VoxelVolume.uniform(32, 1, 0)));
    }

    @Test
    void writeRegion_capturesSemanticNotPackedLongs() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(1, 2, 3, 42).setBiome(1, 2, 3, 7).build();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        InMemoryVolumeWriter.RegionRecord rec = writer.regionRecords().get(0);
        assertEquals(new SectionPos(0, 0, 0), rec.origin());
        assertEquals(Level.L2, rec.level());
        assertEquals(42, rec.volume().blockId(1, 2, 3));
        assertEquals(7, rec.volume().biomeId(1, 2, 3));
    }

    @Test
    void writeRegion_negativeAlignedOrigins_allowed() {
        // L2 regionSections=8, -8 is aligned
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        WriteOutcome o = writer.writeRegion(new SectionPos(-8, -8, -8), Level.L2, v);
        assertEquals(WriteOutcome.Status.WRITTEN, o.status());
    }

    @Test
    void writeRegion_clearResetsState() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        writer.clear();
        WriteOutcome o2 = writer.writeRegion(new SectionPos(0, 0, 0), Level.L2, v);
        assertEquals(WriteOutcome.Status.WRITTEN, o2.status());
    }

    @ParameterizedTest
    @ValueSource(ints = {8, 24})
    void voxelVolume_rejectsInvalidExtents(int extent) {
        assertThrows(IllegalArgumentException.class, () -> writer.writeSection(new SectionPos(0, 0, 0), VoxelVolume.uniform(32, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> writer.writeRegion(new SectionPos(0, 0, 0), Level.L0, VoxelVolume.uniform(16, 1, 0)));
        assertThrows(IllegalArgumentException.class, () -> VoxelVolume.uniform(extent, 0, 0));
    }

    // ------------------------------------------------------------------
    // Neighboring writes remain independent (no fallback narrative)
    // ------------------------------------------------------------------

    @Test
    void independentNeighboringWrites_areIndependent() {
        SectionPos base = new SectionPos(0, 0, 0);
        for (int dy = 0; dy < 2; dy++) {
            for (int dz = 0; dz < 2; dz++) {
                for (int dx = 0; dx < 2; dx++) {
                    VoxelVolume v = VoxelVolume.builder(16).setBlock(0, 0, 0, 1).build();
                    writer.writeSection(new SectionPos(base.x() + dx, base.y() + dy, base.z() + dz), v);
                }
            }
        }
        assertEquals(8, writer.sectionRecords().size());
        assertTrue(writer.regionRecords().isEmpty());
    }

    // ------------------------------------------------------------------
    // Backend independence -- no Voxy long[] leaked
    // ------------------------------------------------------------------

    @Test
    void recordTypes_exposeOnlySemanticFields() {
        VoxelVolume v16 = VoxelVolume.builder(16).setBlock(5, 5, 5, 9).build();
        writer.writeSection(new SectionPos(10, 20, 30), v16);
        var sec = writer.sectionRecords().get(0);
        assertEquals(new SectionPos(10, 20, 30), sec.pos());
        assertEquals(9, sec.volume().blockId(5, 5, 5));
        assertEquals(v16.extent(), sec.volume().extent());
        // No getPackedLongs(), no Voxy coordinate, no LevelCoord, no long[] on the record
    }

    // ------------------------------------------------------------------
    // Negative alignment edge
    // ------------------------------------------------------------------

    @Test
    void writeRegion_negativeOneNotAlignedForAnyCoarseLevel() {
        VoxelVolume v = VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
        for (Level lvl : new Level[]{Level.L1, Level.L2, Level.L3, Level.L4}) {
            assertThrows(IllegalArgumentException.class,
                    () -> writer.writeRegion(new SectionPos(-1, 0, 0), lvl, v),
                    "level " + lvl + " should reject origin -1");
        }
    }
}
