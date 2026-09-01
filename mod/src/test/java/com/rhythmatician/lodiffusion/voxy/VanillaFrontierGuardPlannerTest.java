package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner;
import com.rhythmatician.voxygen.semantic.SectionPos;

class VanillaFrontierGuardPlannerTest {
    @Test
    void plansOnlyTheL1TilesThatIntersectTheStationaryVanillaFrontier() {
        var plan = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 0, 0, 128, 64));

        assertFalse(plan.isEmpty());
        assertTrue(plan.stream().allMatch(parent ->
                parent.origin().y() == 0 || parent.origin().y() == 4));
        assertEquals(2, plan.stream().filter(parent ->
                parent.origin().x() == 8 && parent.origin().z() == 0).count());
        assertEquals(2, plan.stream().filter(parent ->
                parent.origin().x() == -8 && parent.origin().z() == 0).count());
        assertFalse(plan.stream().anyMatch(parent ->
                parent.origin().x() == 0 && parent.origin().z() == 0));
        assertFalse(plan.stream().anyMatch(parent ->
                parent.origin().x() == 16 && parent.origin().z() == 0));
    }

    @Test
    void enforcesOneL1TileOfLeadAndExpandsItByVelocity() {
        var minimum = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 0, 0, 128, 0));
        var moving = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 4, 0, 128, 128));

        assertTrue(minimum.stream().anyMatch(parent ->
                parent.origin().x() == 8 && parent.origin().z() == 0));
        assertTrue(moving.stream().anyMatch(parent ->
                parent.origin().x() == 16 && parent.origin().z() == 0));
        assertEquals(new HashSet<>(minimum).size(), minimum.size(), "transactions are unique");
        assertEquals(new HashSet<>(moving).size(), moving.size(), "transactions are unique");
    }

    @Test
    void ordersTheCompleteAnnulusTowardTheDirectionOfTravelWithoutDroppingTheRear() {
        var plan = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 1, 0, 128, 64));

        int leading = indexOf(plan, new SectionPos(8, 0, 0));
        int trailing = indexOf(plan, new SectionPos(-8, 0, 0));
        assertTrue(leading < trailing, "leading frontier is scheduled first");
        assertTrue(trailing >= 0, "the complete annulus preserves turn-safe coverage");
    }

    @Test
    void snapshotUsesTheLargerVanillaDistanceAndVelocityLead() {
        var input = new VanillaFrontierGuardPlanner.FrontierSnapshot(
                0, 0, 3, 4, 8, 12).toInput(10);

        assertEquals(192, input.vanillaRadiusBlocks());
        assertEquals(114, input.leadBlocks());
    }

    private static int indexOf(
            java.util.List<VanillaFrontierGuardPlanner.ParentTransaction> plan,
            SectionPos origin) {
        for (int index = 0; index < plan.size(); index++) {
            var transaction = plan.get(index);
            if (transaction.origin().equals(origin)) {
                return index;
            }
        }
        return -1;
    }
}
