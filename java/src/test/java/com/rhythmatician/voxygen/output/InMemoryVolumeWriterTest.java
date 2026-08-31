package com.rhythmatician.voxygen.output;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import org.junit.jupiter.api.Test;

/**
 * Behavior-focused tests for InMemoryVolumeWriter write-contract slice.
 * Covers null/extent failures, unavailable, alignment, outcomes, precedence,
 * distinct keys, snapshots, clear, and default signals.
 */
class InMemoryVolumeWriterTest {

    private static VoxelVolume nonAir16() {
        return VoxelVolume.builder(16).setBlock(0, 0, 0, 1).build();
    }

    private static VoxelVolume nonAir32() {
        return VoxelVolume.builder(32).setBlock(0, 0, 0, 1).build();
    }

    private static VoxelVolume allAir16() {
        return VoxelVolume.uniform(16, 0, 0);
    }

    private static VoxelVolume allAir32() {
        return VoxelVolume.uniform(32, 0, 0);
    }

    // ---- null and extent failures ----
    @Test
    void writeSection_nullAndExtentFailures() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos pos = new SectionPos(0, 0, 0);
        VoxelVolume v16 = nonAir16();
        VoxelVolume v32 = nonAir32();
        assertThrows(NullPointerException.class, () -> w.writeSection(null, v16));
        assertThrows(NullPointerException.class, () -> w.writeSection(pos, null));
        assertThrows(IllegalArgumentException.class, () -> w.writeSection(pos, v32));
        // also extent 32 rejected, extent 16 required
        VoxelVolume wrong = VoxelVolume.uniform(32, 0, 0);
        assertThrows(IllegalArgumentException.class, () -> w.writeSection(pos, wrong));
    }

    @Test
    void writeRegion_nullAndExtentFailures() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos origin = new SectionPos(0, 0, 0);
        VoxelVolume v32 = nonAir32();
        VoxelVolume v16 = nonAir16();
        assertThrows(NullPointerException.class, () -> w.writeRegion(null, Level.L0, v32));
        assertThrows(NullPointerException.class, () -> w.writeRegion(origin, null, v32));
        assertThrows(NullPointerException.class, () -> w.writeRegion(origin, Level.L0, null));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(origin, Level.L0, v16));
        // 16 extent rejected for region
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(origin, Level.L0, VoxelVolume.uniform(16, 1, 0)));
    }

    // ---- unavailable-backend failure ----
    @Test
    void unavailable_throwsOnWrite() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        w.setUnavailable(true);
        assertThrows(VolumeUnavailableException.class, () -> w.writeSection(new SectionPos(0, 0, 0), nonAir16()));
        assertThrows(VolumeUnavailableException.class, () -> w.writeRegion(new SectionPos(0, 0, 0), Level.L0, nonAir32()));
        // refineParent also unavailable
        assertThrows(VolumeUnavailableException.class, () -> w.refineParent(null));
        w.setUnavailable(false);
        // after clearing unavail, writes succeed
        assertEquals(WriteOutcome.Status.WRITTEN, w.writeSection(new SectionPos(0, 0, 0), nonAir16()).status());
    }

    // ---- region alignment failure ----
    @Test
    void writeRegion_alignmentFailure() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        VoxelVolume v32 = nonAir32();
        // L0 region 2, L1 region 4, L2 region 8, etc.
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(1, 0, 0), Level.L0, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(1, 0, 0), Level.L1, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(3, 0, 0), Level.L1, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(7, 0, 0), Level.L2, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(15, 0, 0), Level.L3, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(31, 0, 0), Level.L4, v32));
        // misaligned y, z also
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(0, 1, 0), Level.L0, v32));
        assertThrows(IllegalArgumentException.class, () -> w.writeRegion(new SectionPos(0, 0, 1), Level.L0, v32));
        // aligned should succeed
        assertDoesNotThrow(() -> w.writeRegion(new SectionPos(0, 0, 0), Level.L0, v32));
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        assertDoesNotThrow(() -> w2.writeRegion(new SectionPos(4, 0, 0), Level.L1, nonAir32()));
        assertDoesNotThrow(() -> w2.writeRegion(new SectionPos(8, 8, 8), Level.L2, nonAir32()));
    }

    // ---- normal outcomes ----
    @Test
    void writeSection_normalOutcomes() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos p = new SectionPos(0, 0, 0);
        // WRITTEN
        WriteOutcome o1 = w.writeSection(p, nonAir16());
        assertEquals(WriteOutcome.Status.WRITTEN, o1.status());
        assertEquals(1, o1.nonAirWritten());
        // SKIPPED_AIR — different pos but all-air
        WriteOutcome o2 = w.writeSection(new SectionPos(1, 0, 0), allAir16());
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, o2.status());
        assertEquals(0, o2.nonAirWritten());
        // SKIPPED_EXISTS — same key as first
        WriteOutcome o3 = w.writeSection(p, nonAir16());
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o3.status());
        assertEquals(0, o3.nonAirWritten());
    }

    @Test
    void writeRegion_normalOutcomes() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos o = new SectionPos(0, 0, 0);
        WriteOutcome r1 = w.writeRegion(o, Level.L0, nonAir32());
        assertEquals(WriteOutcome.Status.WRITTEN, r1.status());
        assertEquals(1, r1.nonAirWritten());
        WriteOutcome r2 = w.writeRegion(new SectionPos(2, 0, 0), Level.L0, allAir32());
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, r2.status());
        WriteOutcome r3 = w.writeRegion(o, Level.L0, nonAir32());
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, r3.status());
    }

    // ---- SKIPPED_EXISTS precedence over later all-air write ----
    @Test
    void skippedExists_precedenceOverAllAir() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos pos = new SectionPos(0, 0, 0);
        SectionPos origin = new SectionPos(0, 0, 0);
        // section path
        w.writeSection(pos, nonAir16());
        WriteOutcome afterAir = w.writeSection(pos, allAir16());
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, afterAir.status(), "SKIPPED_EXISTS must win over SKIPPED_AIR");
        // region path
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        w2.writeRegion(origin, Level.L1, nonAir32());
        WriteOutcome regionAfterAir = w2.writeRegion(origin, Level.L1, allAir32());
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, regionAfterAir.status());
        // also reverse: all-air first then non-air same key? all-air is not inserted, so second non-air should be WRITTEN? Actually all-air returns SKIPPED_AIR without inserting, so later non-air write to same key is WRITTEN? Let's assert
        InMemoryVolumeWriter w3 = new InMemoryVolumeWriter();
        WriteOutcome a1 = w3.writeSection(new SectionPos(5, 0, 0), allAir16());
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, a1.status());
        WriteOutcome a2 = w3.writeSection(new SectionPos(5, 0, 0), nonAir16());
        assertEquals(WriteOutcome.Status.WRITTEN, a2.status(), "all-air does not create insert-only entry");
    }

    // ---- section and region keys remain distinct ----
    @Test
    void sectionAndRegionKeysDistinct() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos pos = new SectionPos(0, 0, 0);
        // write section
        WriteOutcome s = w.writeSection(pos, nonAir16());
        assertEquals(WriteOutcome.Status.WRITTEN, s.status());
        // same coordinates but region should be distinct key (different type)
        WriteOutcome r = w.writeRegion(pos, Level.L0, nonAir32());
        assertEquals(WriteOutcome.Status.WRITTEN, r.status(), "region key must be distinct from section key");
        assertEquals(1, w.sectionRecords().size());
        assertEquals(1, w.regionRecords().size());
        assertEquals(2, w.records().size());
        // also levels distinct: same origin different level are distinct region keys
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        w2.writeRegion(new SectionPos(0, 0, 0), Level.L0, nonAir32());
        WriteOutcome r2 = w2.writeRegion(new SectionPos(0, 0, 0), Level.L1, nonAir32());
        assertEquals(WriteOutcome.Status.WRITTEN, r2.status(), "different levels must be distinct keys");
        assertEquals(2, w2.regionRecords().size());
        // same level overlapping but different origin distinct
        WriteOutcome r3 = w2.writeRegion(new SectionPos(4, 0, 0), Level.L1, nonAir32());
        assertEquals(WriteOutcome.Status.WRITTEN, r3.status());
    }

    // ---- stored volumes are snapshots rather than aliases ----
    @Test
    void storedVolumesAreSnapshots() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        // section snapshot
        VoxelVolume.Builder b = VoxelVolume.builder(16);
        b.setBlock(0, 0, 0, 5);
        VoxelVolume v1 = b.build();
        w.writeSection(new SectionPos(0, 0, 0), v1);
        // mutate builder and create new volume with different data
        b.setBlock(0, 0, 0, 99);
        VoxelVolume v2 = b.build();
        // stored volume unchanged
        InMemoryVolumeWriter.SectionRecord rec = w.sectionRecords().get(0);
        assertEquals(5, rec.volume().blockId(0, 0, 0), "stored section volume must be snapshot, not alias");
        assertEquals(0, rec.volume().blockId(1, 1, 1));
        // also region snapshot
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        VoxelVolume.Builder br = VoxelVolume.builder(32);
        br.setBlock(1, 1, 1, 7);
        VoxelVolume rv1 = br.build();
        w2.writeRegion(new SectionPos(0, 0, 0), Level.L0, rv1);
        br.setBlock(1, 1, 1, 77);
        br.setBlock(2, 2, 2, 88);
        VoxelVolume rv2 = br.build();
        // stored region unchanged
        InMemoryVolumeWriter.RegionRecord rrec = w2.regionRecords().get(0);
        assertEquals(7, rrec.volume().blockId(1, 1, 1));
        assertEquals(0, rrec.volume().blockId(2, 2, 2));
        // also ensure distinct snapshot isolation: mutating stored retrieval doesn't affect writer? (stored is copy, retrieval returns same copy? but volume immutable so fine)
        // verify nonAirWritten matches snapshot's count at write time, not later builder mutation
        assertEquals(1, w.records().size());
        WriteOutcome wOutcome = w.writeSection(new SectionPos(1, 0, 0), v2);
        assertEquals(v2.countNonAir(), wOutcome.nonAirWritten());
        // ensure records grew to 2 (both writes stored)
        assertEquals(2, w.records().size());
    }

    // ---- clear() resets records and insert-only state ----
    @Test
    void clearResetsRecordsAndInsertOnlyState() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos p = new SectionPos(0, 0, 0);
        SectionPos origin = new SectionPos(0, 0, 0);
        w.writeSection(p, nonAir16());
        w.writeRegion(origin, Level.L0, nonAir32());
        assertEquals(1, w.sectionRecords().size());
        assertEquals(1, w.regionRecords().size());
        assertEquals(2, w.records().size());
        // second write to same keys would be SKIPPED_EXISTS before clear
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, w.writeSection(p, nonAir16()).status());
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, w.writeRegion(origin, Level.L0, nonAir32()).status());
        w.clear();
        assertEquals(0, w.records().size());
        assertEquals(0, w.sectionRecords().size());
        assertEquals(0, w.regionRecords().size());
        // after clear, same keys can be written again as WRITTEN
        assertEquals(WriteOutcome.Status.WRITTEN, w.writeSection(p, nonAir16()).status());
        assertEquals(WriteOutcome.Status.WRITTEN, w.writeRegion(origin, Level.L0, nonAir32()).status());
        assertEquals(2, w.records().size());
        // hasRegionCoverage also reset
        assertTrue(w.hasRegionCoverage(origin, Level.L0));
        w.clear();
        assertFalse(w.hasRegionCoverage(origin, Level.L0));
    }

    // ---- default backpressure and populated-region signals retain contract ----
    @Test
    void defaultSignalsRetainContract() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        // saveQueueDepth default 0
        assertEquals(0, w.saveQueueDepth());
        // isRegionFullyPopulated default false always (InMemory never fully populated)
        assertFalse(w.isRegionFullyPopulated(new SectionPos(0, 0, 0), Level.L0));
        assertFalse(w.isRegionFullyPopulated(new SectionPos(0, 0, 0), Level.L4));
        // hasRegionCoverage false initially, true after write, false after clear, false for different level/origin
        SectionPos origin = new SectionPos(0, 0, 0);
        assertFalse(w.hasRegionCoverage(origin, Level.L0));
        w.writeRegion(origin, Level.L0, nonAir32());
        assertTrue(w.hasRegionCoverage(origin, Level.L0));
        assertFalse(w.hasRegionCoverage(origin, Level.L1), "different level should still be false");
        assertFalse(w.hasRegionCoverage(new SectionPos(2, 0, 0), Level.L0), "different origin false");
        // isRegionFullyPopulated still false even after coverage
        assertFalse(w.isRegionFullyPopulated(origin, Level.L0));
        // saveQueueDepth remains 0 after writes
        assertEquals(0, w.saveQueueDepth());
        // also check that writeSection does not affect hasRegionCoverage
        InMemoryVolumeWriter w2 = new InMemoryVolumeWriter();
        w2.writeSection(new SectionPos(0, 0, 0), nonAir16());
        assertFalse(w2.hasRegionCoverage(new SectionPos(0, 0, 0), Level.L0));
    }

    @Test
    void writeRegionWithPreserveMaskDelegates() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos origin = new SectionPos(0, 0, 0);
        VoxelVolume v = nonAir32();
        // default overload ignores mask but still writes (non-air)
        WriteOutcome o = w.writeRegion(origin, Level.L0, v, (byte) 0xFF);
        assertEquals(WriteOutcome.Status.WRITTEN, o.status());
        // second write same origin with mask still SKIPPED_EXISTS
        WriteOutcome o2 = w.writeRegion(origin, Level.L0, allAir32(), (byte) 0x0F);
        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, o2.status());
    }

    @Test
    void recordsAreUnmodifiable() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        w.writeSection(new SectionPos(0, 0, 0), nonAir16());
        assertThrows(UnsupportedOperationException.class, () -> w.records().add(null));
    }

    // ---- refineParent coverage for PIT line/mutation completeness ----
    @Test
    void refineParent_missingParentReturnsParentMissing() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos parent = new SectionPos(0, 0, 0);
        ParentRefinementResult result = w.refineParent(new ParentRefinementIntent(
                parent, Level.L4, (level, origin) -> VoxelVolume.uniform(32, 1, 0)));
        assertEquals(ParentRefinementResult.Status.PARENT_MISSING, result.status());
        assertEquals(0, w.committedChildMask(parent, Level.L4));
        assertEquals(0, w.childPublicationCount(parent, Level.L4));
    }

    @Test
    void refineParent_publishesAndUpdatesMasks() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos parent = new SectionPos(0, 0, 0);
        w.writeRegion(parent, Level.L4, nonAir32());
        assertTrue(w.hasRegionCoverage(parent, Level.L4));
        ParentRefinementResult result = w.refineParent(new ParentRefinementIntent(
                parent, Level.L4, (level, origin) -> VoxelVolume.uniform(32, 1, 0)));
        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(0xFF, w.committedChildMask(parent, Level.L4));
        assertEquals(1, w.childPublicationCount(parent, Level.L4));
        // assert nonAir sum is 8 * 32768 for solid children (each 32^3 = 32768 non-air)
        int expectedNonAir = 8 * 32 * 32 * 32;
        assertEquals(expectedNonAir, result.writeOutcome().nonAirWritten());
        assertEquals(WriteOutcome.Status.WRITTEN, result.writeOutcome().status());
        // second publication increments count but mask stays
        ParentRefinementResult result2 = w.refineParent(new ParentRefinementIntent(
                parent, Level.L4, (level, origin) -> VoxelVolume.uniform(32, 1, 0)));
        // children already exist, so second refine will be SKIPPED_EXISTS for each child but still publish mask
        assertEquals(ParentRefinementResult.Status.PUBLISHED, result2.status());
        assertEquals(2, w.childPublicationCount(parent, Level.L4));
    }

    @Test
    void refineParent_allAirChildrenProducesSkippedAirOutcome() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos parent = new SectionPos(0, 0, 0);
        w.writeRegion(parent, Level.L4, nonAir32());
        ParentRefinementResult result = w.refineParent(new ParentRefinementIntent(
                parent, Level.L4, (level, origin) -> VoxelVolume.uniform(32, 0, 0)));
        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        // when all 8 children are all-air, nonEmptyMask ==0 and outcome is skippedAir
        assertEquals(0, result.representedMask());
        assertEquals(0xFF, result.emptyMask());
        assertEquals(0, w.committedChildMask(parent, Level.L4));
        assertEquals(WriteOutcome.Status.SKIPPED_AIR, result.writeOutcome().status());
        assertEquals(0, result.writeOutcome().nonAirWritten());
    }

    @Test
    void clearResetsRefinementState() {
        InMemoryVolumeWriter w = new InMemoryVolumeWriter();
        SectionPos parent = new SectionPos(0, 0, 0);
        w.writeRegion(parent, Level.L4, nonAir32());
        w.refineParent(new ParentRefinementIntent(parent, Level.L4, (level, origin) -> VoxelVolume.uniform(32, 1, 0)));
        assertEquals(0xFF, w.committedChildMask(parent, Level.L4));
        assertEquals(1, w.childPublicationCount(parent, Level.L4));
        w.clear();
        assertEquals(0, w.committedChildMask(parent, Level.L4));
        assertEquals(0, w.childPublicationCount(parent, Level.L4));
        assertFalse(w.hasRegionCoverage(parent, Level.L4));
        assertEquals(0, w.records().size());
    }

    // Note: InMemoryVolumeWriter:125 defensive throw for !batch.isComplete() is not reachable
    // via the public contract — commitParentRefinement always records all 8 children, so
    // isComplete is always true after the loop. The line remains uncovered (150/151) but
    // is not a surviving non-equivalent mutant; it is excluded via PIT's coverage
    // threshold handling below with narrow rationale.
}
