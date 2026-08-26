package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class ParentRefinementBatchTest {
    private static final SectionPos PARENT = new SectionPos(0, 0, 0);

    @Test
    void intentExpandsAllChildrenInOctantOrder() {
        InMemoryVolumeWriter writer = writerWithParent();
        List<SectionPos> observed = new ArrayList<>();

        ParentRefinementResult result = writer.refineParent(new ParentRefinementIntent(
                PARENT, Level.L4, (level, origin) -> {
                    observed.add(origin);
                    return solid();
                }));

        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(List.of(
                new SectionPos(0, 0, 0), new SectionPos(16, 0, 0),
                new SectionPos(0, 0, 16), new SectionPos(16, 0, 16),
                new SectionPos(0, 16, 0), new SectionPos(16, 16, 0),
                new SectionPos(0, 16, 16), new SectionPos(16, 16, 16)), observed);
    }

    @Test
    void failedChildMaterializationNeverPublishesPartialMask() {
        InMemoryVolumeWriter writer = writerWithParent();

        assertThrows(IllegalStateException.class, () -> writer.refineParent(
                new ParentRefinementIntent(PARENT, Level.L4, (level, origin) -> {
                    if (origin.equals(new SectionPos(0, 0, 16))) {
                        throw new IllegalStateException("child failed");
                    }
                    return solid();
                })));

        assertEquals(0, writer.childPublicationCount(PARENT, Level.L4));
        assertEquals(0, writer.committedChildMask(PARENT, Level.L4));
        assertTrue(writer.hasRegionCoverage(PARENT, Level.L4));
    }

    @Test
    void existingVanillaChildIsPreservedAndStillAdvertised() {
        InMemoryVolumeWriter writer = writerWithParent();
        SectionPos vanillaChild = new SectionPos(16, 0, 0);
        writer.writeRegion(vanillaChild, Level.L3, solid());

        writer.refineParent(new ParentRefinementIntent(
                PARENT, Level.L4, (level, origin) -> solid()));

        assertEquals(0xFF, writer.committedChildMask(PARENT, Level.L4));
        assertEquals(9, writer.regionRecords().size());
        assertEquals(1, writer.regionRecords().stream()
                .filter(record -> record.origin().equals(vanillaChild))
                .count());
    }

    @Test
    void mixedGeneratedPreservedAndEmptyChildrenPublishOneExactMask() {
        InMemoryVolumeWriter writer = writerWithParent();
        writer.writeRegion(new SectionPos(16, 0, 0), Level.L3, solid());

        ParentRefinementResult result = writer.refineParent(new ParentRefinementIntent(
                PARENT, Level.L4, (level, origin) -> origin.y() == 16 && origin.z() == 16
                        ? VoxelVolume.uniform(32, CanonicalRegistries.BLOCK_AIR, 0)
                        : solid()));

        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(0b0011_1111, writer.committedChildMask(PARENT, Level.L4));
        assertEquals(1, writer.childPublicationCount(PARENT, Level.L4));
    }

    @Test
    void parentRemainsCoveredWhileCompleteChildrenBecomeObservable() {
        InMemoryVolumeWriter writer = writerWithParent();

        writer.refineParent(new ParentRefinementIntent(
                PARENT, Level.L4, (level, origin) -> solid()));

        assertTrue(writer.hasRegionCoverage(PARENT, Level.L4));
        for (SectionPos child : ParentRefinementBatch.childOrigins(PARENT, Level.L4)) {
            assertTrue(writer.hasRegionCoverage(child, Level.L3));
        }
        assertEquals(0xFF, writer.committedChildMask(PARENT, Level.L4));
    }

    @Test
    void missingParentStopsBeforeChildExpansion() {
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        int[] childCalls = {0};

        ParentRefinementResult result = writer.refineParent(new ParentRefinementIntent(
                PARENT, Level.L4, (level, origin) -> {
                    childCalls[0]++;
                    return solid();
                }));

        assertEquals(ParentRefinementResult.Status.PARENT_MISSING, result.status());
        assertEquals(0, childCalls[0]);
        assertEquals(0, writer.childPublicationCount(PARENT, Level.L4));
    }

    private static InMemoryVolumeWriter writerWithParent() {
        InMemoryVolumeWriter writer = new InMemoryVolumeWriter();
        writer.writeRegion(PARENT, Level.L4, solid());
        return writer;
    }

    private static VoxelVolume solid() {
        return VoxelVolume.uniform(32, EndL4DeterministicCandidate.BLOCK_END_STONE, 0);
    }
}
