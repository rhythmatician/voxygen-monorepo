package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Set;
import org.junit.jupiter.api.Test;

class VanillaOccupancyPyramidTest {
    @Test
    void oneVanillaOctantLeavesItsL0WorldSectionMixed() {
        var pyramid = new VanillaOccupancyPyramid();

        pyramid.observeVanillaOctant(3, -1, 6, 0);

        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED,
                pyramid.classify(new VanillaOccupancyPyramid.Cell(0, 3, -1, 6)));
        assertEquals(0b1111_1110,
                pyramid.missingChildOctants(new VanillaOccupancyPyramid.Cell(0, 3, -1, 6)));
        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED,
                pyramid.classify(new VanillaOccupancyPyramid.Cell(1, 1, -1, 3)));
        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED,
                pyramid.classify(new VanillaOccupancyPyramid.Cell(4, 0, -1, 0)));
        assertEquals(VanillaOccupancyPyramid.Occupancy.NONE,
                pyramid.classify(new VanillaOccupancyPyramid.Cell(4, 1, -1, 0)));

        var completed = pyramid.observeVanillaL0Octants(3, -1, 6, 0xFF);
        var l0 = new VanillaOccupancyPyramid.Cell(0, 3, -1, 6);
        assertEquals(VanillaOccupancyPyramid.Occupancy.FULL_VANILLA, pyramid.classify(l0));
        assertEquals(0, pyramid.missingChildOctants(l0));
        assertTrue(completed.removedUrgent().contains(l0));
        assertFalse(pyramid.urgentBoundary().contains(l0));
    }

    @Test
    void mixedParentExposesExactlyTheChildrenNotFullOfVanilla() {
        var pyramid = new VanillaOccupancyPyramid();
        var parent = new VanillaOccupancyPyramid.Cell(1, 0, 0, 0);
        pyramid.observeVanillaL0Octants(0, 0, 0, 0xFF); // parent octant 0
        pyramid.observeVanillaL0Octants(1, 0, 0, 0xFF); // parent octant 1
        pyramid.observeVanillaL0Octants(0, 0, 1, 0xFF); // parent octant 2

        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED, pyramid.classify(parent));
        assertEquals(0b1111_1000, pyramid.missingChildOctants(parent));

        for (int octant = 3; octant < 8; octant++) {
            pyramid.observeVanillaL0Octants(
                    octant & 1,
                    (octant >> 2) & 1,
                    (octant >> 1) & 1,
                    0xFF);
        }

        assertEquals(VanillaOccupancyPyramid.Occupancy.FULL_VANILLA, pyramid.classify(parent));
        assertEquals(0, pyramid.missingChildOctants(parent));
        assertFalse(pyramid.urgentBoundary().contains(parent), "full vanilla is never Voxygen work");
    }

    @Test
    void boundaryUsesHorizontalChebyshevTouchAtEachLevel() {
        var pyramid = new VanillaOccupancyPyramid();

        var delta = pyramid.observeVanillaOctant(0, 7, 0, 3);

        var occupiedL0 = new VanillaOccupancyPyramid.Cell(0, 0, 7, 0);
        assertEquals(VanillaOccupancyPyramid.Relation.FRONTIER,
                pyramid.relation(occupiedL0));
        assertEquals(VanillaOccupancyPyramid.Relation.FRONTIER,
                pyramid.relation(new VanillaOccupancyPyramid.Cell(0, 1, 7, 1)));
        assertEquals(VanillaOccupancyPyramid.Relation.ORDINARY,
                pyramid.relation(new VanillaOccupancyPyramid.Cell(0, 0, 8, 0)));
        assertTrue(delta.newlyMixedParents().contains(occupiedL0));
        assertTrue(delta.addedUrgent().contains(occupiedL0));
        assertEquals(9, delta.addedUrgent().stream().filter(cell -> cell.level() == 0).count());
        assertTrue(delta.addedUrgent().contains(new VanillaOccupancyPyramid.Cell(0, 1, 7, 1)));
        assertTrue(delta.addedUrgent().contains(new VanillaOccupancyPyramid.Cell(0, -1, 7, 0)));
        assertFalse(delta.addedUrgent().contains(new VanillaOccupancyPyramid.Cell(0, 0, 8, 0)),
                "touch does not expand vertically");

        for (int level = 1; level <= VanillaOccupancyPyramid.MAX_LEVEL; level++) {
            int y = Math.floorDiv(7, 1 << level);
            var mixedAncestor = new VanillaOccupancyPyramid.Cell(level, 0, y, 0);
            assertTrue(delta.newlyMixedParents().contains(mixedAncestor),
                    "newly mixed transition is explicit at L" + level);
            assertTrue(delta.addedUrgent().contains(mixedAncestor),
                    "mixed ancestor includes vanilla at L" + level);
            assertTrue(delta.addedUrgent().contains(new VanillaOccupancyPyramid.Cell(level, 1, y, 0)),
                    "horizontal neighbor touches vanilla at L" + level);
        }
    }

    @Test
    void observationsReturnOnlyBoundaryChangesAndIgnoreDuplicates() {
        var pyramid = new VanillaOccupancyPyramid();
        pyramid.observeVanillaOctant(0, 0, 0, 0);

        var duplicate = pyramid.observeVanillaOctant(0, 0, 0, 0);
        assertTrue(duplicate.isEmpty());

        var next = pyramid.observeVanillaL0Octants(1, 0, 0, 0xFF);
        assertEquals(next.addedUrgent().size(), Set.copyOf(next.addedUrgent()).size());
        assertEquals(next.removedUrgent().size(), Set.copyOf(next.removedUrgent()).size());
        assertTrue(next.removedUrgent().contains(new VanillaOccupancyPyramid.Cell(0, 1, 0, 0)),
                "a shell cell retires when vanilla fills it");
    }

    @Test
    void negativeCoordinatesUseFloorBasedParentageAndExactOctantBits() {
        var pyramid = new VanillaOccupancyPyramid();
        pyramid.observeVanillaL0Octants(-1, -1, -1, 1 << 7);

        var l0 = new VanillaOccupancyPyramid.Cell(0, -1, -1, -1);
        var parent = new VanillaOccupancyPyramid.Cell(1, -1, -1, -1);
        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED, pyramid.classify(l0));
        assertEquals(0b0111_1111, pyramid.missingChildOctants(l0));
        assertEquals(VanillaOccupancyPyramid.Occupancy.MIXED, pyramid.classify(parent));
    }

    @Test
    void fullVanillaRelationTakesPrecedenceOverFrontierMembership() {
        var pyramid = new VanillaOccupancyPyramid();
        var cell = new VanillaOccupancyPyramid.Cell(0, 2, 0, 3);

        pyramid.observeVanillaL0Octants(2, 0, 3, 0xFF);

        assertEquals(VanillaOccupancyPyramid.Relation.FULL, pyramid.relation(cell));
    }
}
