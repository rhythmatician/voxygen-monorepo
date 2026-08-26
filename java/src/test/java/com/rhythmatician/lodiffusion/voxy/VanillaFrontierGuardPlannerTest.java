package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import org.junit.jupiter.api.Test;

class VanillaFrontierGuardPlannerTest {
    @Test
    void plansOnlyTheL1TilesThatIntersectTheStationaryVanillaFrontier() {
        var plan = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 0, 0, 128, 64));

        assertFalse(plan.isEmpty());
        assertTrue(plan.stream().allMatch(parent -> parent.level() == Level.L1.value()));
        assertTrue(plan.stream().allMatch(parent -> parent.wsY() == 0 || parent.wsY() == 1));
        assertEquals(2, plan.stream().filter(parent -> parent.wsX() == 2 && parent.wsZ() == 0).count());
        assertEquals(2, plan.stream().filter(parent -> parent.wsX() == -2 && parent.wsZ() == 0).count());
        assertFalse(plan.stream().anyMatch(parent -> parent.wsX() == 0 && parent.wsZ() == 0));
        assertFalse(plan.stream().anyMatch(parent -> parent.wsX() == 4 && parent.wsZ() == 0));
    }

    @Test
    void enforcesOneL1TileOfLeadAndExpandsItByVelocity() {
        var minimum = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 0, 0, 128, 0));
        var moving = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 4, 0, 128, 128));

        assertTrue(minimum.stream().anyMatch(parent -> parent.wsX() == 2 && parent.wsZ() == 0));
        assertTrue(moving.stream().anyMatch(parent -> parent.wsX() == 4 && parent.wsZ() == 0));
        assertEquals(new HashSet<>(minimum).size(), minimum.size(), "transactions are unique");
        assertEquals(new HashSet<>(moving).size(), moving.size(), "transactions are unique");
    }

    @Test
    void ordersTheCompleteAnnulusTowardTheDirectionOfTravelWithoutDroppingTheRear() {
        var plan = VanillaFrontierGuardPlanner.plan(
                new VanillaFrontierGuardPlanner.Input(0, 0, 1, 0, 128, 64));

        int leading = indexOf(plan, 2, 0, 0);
        int trailing = indexOf(plan, -2, 0, 0);
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
            int x, int y, int z) {
        for (int index = 0; index < plan.size(); index++) {
            var transaction = plan.get(index);
            if (transaction.wsX() == x && transaction.wsY() == y && transaction.wsZ() == z) {
                return index;
            }
        }
        return -1;
    }
}
