package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class RefinementDemandSelectorTest {
    private static RefinementDemandSelector.Params params(int finest) {
        return new RefinementDemandSelector.Params(
                256, 96, 256, 1000, 64, finest, 1e9, Integer.MAX_VALUE,
                List.of(new SectionPos(0, 0, 0)));
    }

    @Test
    void selectsParentTransactionsInsteadOfIsolatedChildren() {
        var out = RefinementDemandSelector.select(params(Level.L1.value()));
        assertEquals(73, out.size());
        assertEquals(1, out.stream().filter(e -> e.request().level() == 4).count());
        assertEquals(8, out.stream().filter(e -> e.request().level() == 3).count());
        assertEquals(64, out.stream().filter(e -> e.request().level() == 2).count());
        assertTrue(out.stream().allMatch(e -> e.request().level() >= 2));
    }

    @Test
    void configuredFinestLevelStopsBeforeL1Transactions() {
        var out = RefinementDemandSelector.select(params(Level.L2.value()));
        assertEquals(9, out.size());
        assertTrue(out.stream().noneMatch(e -> e.request().level() < 3));
    }

    @Test
    void l0FinestLevelIncludesL1ParentTransactions() {
        var out = RefinementDemandSelector.select(params(Level.L0.value()));
        assertEquals(585, out.size());
        assertEquals(512, out.stream().filter(e -> e.request().level() == Level.L1.value()).count());
        assertTrue(out.stream().allMatch(e -> e.request().level() >= Level.L1.value()));
    }

    @Test
    void parentCoordinatesCollapseEightChildDemands() {
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                448, 300, 448, 10, 64, Level.L1.value(), 1e9, Integer.MAX_VALUE,
                List.of(new SectionPos(0, 0, 0))));
        assertEquals(3, out.size());
        assertTrue(out.stream().anyMatch(e -> e.request().equals(
                new RefinementDemandSelector.NodeRequest(4, 0, 0, 0))));
        assertTrue(out.stream().anyMatch(e -> e.request().equals(
                new RefinementDemandSelector.NodeRequest(3, 1, 1, 1))));
        assertTrue(out.stream().anyMatch(e -> e.request().equals(
                new RefinementDemandSelector.NodeRequest(2, 3, 2, 3))));
    }

    @Test
    void orderingIsNormalizedAndDeterministic() {
        var a = RefinementDemandSelector.select(params(Level.L1.value()));
        var b = RefinementDemandSelector.select(params(Level.L1.value()));
        assertEquals(a, b);
        for (int i = 1; i < a.size(); i++) {
            double previous = a.get(i - 1).distBlocks()
                    / (32.0 * (1 << a.get(i - 1).request().level()));
            double current = a.get(i).distBlocks()
                    / (32.0 * (1 << a.get(i).request().level()));
            assertTrue(previous <= current);
        }
    }
}
