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
    void parentDemandRetainsTheExactDemandedChildOctants() {
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                448, 300, 448, 10, 64, Level.L1.value(), 1e9, Integer.MAX_VALUE,
                List.of(new SectionPos(0, 0, 0))));

        var l2 = out.stream()
                .filter(e -> e.request().equals(
                        new RefinementDemandSelector.NodeRequest(2, 3, 2, 3)))
                .findFirst().orElseThrow();
        assertEquals(0x0F, l2.demandedChildMask());
    }

    @Test
    void siblingChildDemandsMergeIntoOneParentMask() {
        var out = RefinementDemandSelector.select(params(Level.L1.value()));

        var containingCamera = out.stream()
                .filter(e -> e.request().equals(
                        new RefinementDemandSelector.NodeRequest(2, 0, 0, 0)))
                .findFirst().orElseThrow();
        assertEquals(0xFF, containingCamera.demandedChildMask());
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

    // ── Canonical SectionPos → WorldSection lattice conversion ────────────

    /**
     * Production render distance (8192 blocks). A covered L4 origin of
     * canonical SectionPos x=32 (block origin 512, Voxy WorldSection wsX=1)
     * must be treated as the adjacent 512-block region — not as wsX=32,
     * which would place its centre at block 16640 and cull it.
     */
    @Test
    void positiveCanonicalOriginMapsToWorldSectionOne() {
        // SectionPos x=32 is canonical section 32 (block origin 512) = wsX 1.
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                768, 96, 256, 1000, 64, Level.L1.value(), 8192, Integer.MAX_VALUE,
                List.of(new SectionPos(32, 0, 0))));
        assertFalse(out.isEmpty(),
                "adjacent L4 region at wsX=1 must not be culled by render distance");
        assertTrue(out.stream().anyMatch(e -> e.request().level() == 4
                        && e.request().wsX() == 1 && e.request().wsY() == 0
                        && e.request().wsZ() == 0),
                "L4 parent transaction must be emitted at WorldSection (1,0,0)");
    }

    /** Negative canonical origins convert to negative WorldSection indices. */
    @Test
    void negativeCanonicalOriginMapsToNegativeWorldSection() {
        // SectionPos x=-32 (block origin -512) = wsX -1; z=-16 (block -256) = wsZ -1.
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                -768, 96, -256, 1000, 64, Level.L1.value(), 8192, Integer.MAX_VALUE,
                List.of(new SectionPos(-32, 0, -16))));
        assertFalse(out.isEmpty());
        assertTrue(out.stream().anyMatch(e -> e.request().level() == 4
                        && e.request().wsX() == -1 && e.request().wsZ() == -1),
                "L4 parent transaction must be emitted at WorldSection (-1,0,-1)");
    }

    /** A region beyond the production render distance stays culled. */
    @Test
    void distantCanonicalOriginRemainsCulledAtProductionScale() {
        // wsX = 20 => centre at block ~10368 > 8192 from a camera near origin.
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                256, 96, 256, 1000, 64, Level.L1.value(), 8192, Integer.MAX_VALUE,
                List.of(new SectionPos(20 << 5, 0, 0))));
        assertTrue(out.isEmpty(), "region beyond render distance must be culled");
    }
}
