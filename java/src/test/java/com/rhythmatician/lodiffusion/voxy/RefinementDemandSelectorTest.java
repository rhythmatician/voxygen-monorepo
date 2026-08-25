package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Behavior spec for screen-space-error refinement demand selection (ADR 0011).
 *
 * <p>The selector answers: "given covered L4 regions and a camera, which
 * finer-Level nodes are visually needed now?" It replaces fixed per-Level
 * radii with Voxy-style projected screen-space area descent, CPU-side.
 *
 * <p>Geometry conventions (mirroring Voxy):
 * <ul>
 *   <li>A node at Level L spans {@code 32 << L} blocks per axis; region
 *       index r covers blocks {@code [r * (32<<L), (r+1) * (32<<L))}.</li>
 *   <li>Requests are emitted in world-section-at-level coordinates, which
 *       equal the region index ({@code block >> (5 + L)}).</li>
 *   <li>Render-distance culling is XZ-cylindrical; screen-space area uses
 *       true 3D closest-point distance.</li>
 *   <li>A node descends (emits a refinement request for its child) iff the
 *       child's projected area {@code size² · focal² / dist²} exceeds
 *       {@code subDivisionSizePx²}.</li>
 * </ul>
 */
class RefinementDemandSelectorTest {

    /** Focal length in px; threshold distance ≈ size · focal / subDiv. */
    private static final double FOCAL_PX = 1000.0;
    private static final double SUB_DIV_PX = 64.0;

    private static RefinementDemandSelector.Params params(
            double camX, double camY, double camZ,
            int finestLevelValue, double renderDistanceBlocks, int budget,
            List<SectionPos> coveredL4) {
        return new RefinementDemandSelector.Params(
                camX, camY, camZ, FOCAL_PX, SUB_DIV_PX,
                finestLevelValue, renderDistanceBlocks, budget, coveredL4);
    }

    private static RefinementDemandSelector.Params singleRegion(
            double camX, double camY, double camZ,
            int finestLevelValue, double renderDistanceBlocks, int budget) {
        return params(camX, camY, camZ, finestLevelValue, renderDistanceBlocks,
                budget, List.of(new SectionPos(0, 0, 0)));
    }

    @Test
    void distantRegion_producesNoRefinements_whenChildrenProjectBelowThreshold() {
        // Camera ~50k blocks away: even L3 children (256 blocks) project to
        // ~27 px², well under the 64² = 4096 px² threshold.
        var out = RefinementDemandSelector.select(
                singleRegion(50_000, 96, 0, Level.L1.value(), 1e9, Integer.MAX_VALUE));
        assertTrue(out.isEmpty(), "distant region must not demand refinement, got " + out);
    }

    @Test
    void nearbyRegion_cascadesRefinements_throughEveryLevelDownToFinest() {
        // Camera at the centre of region 0: every descendant projects huge,
        // so the full cascade L3 (8) + L2 (64) + L1 (512) is demanded.
        var out = RefinementDemandSelector.select(
                singleRegion(256, 96, 256, Level.L1.value(), 1e9, Integer.MAX_VALUE));
        long l3 = out.stream().filter(r -> r.request().level() == 3).count();
        long l2 = out.stream().filter(r -> r.request().level() == 2).count();
        long l1 = out.stream().filter(r -> r.request().level() == 1).count();
        assertEquals(8, l3, "8 L3 children");
        assertEquals(64, l2, "64 L2 grandchildren");
        assertEquals(512, l1, "512 L1 great-grandchildren");
        assertEquals(584, out.size());
    }

    @Test
    void refinement_neverGoesFinerThanConfiguredLevel() {
        var out = RefinementDemandSelector.select(
                singleRegion(256, 96, 256, Level.L2.value(), 1e9, Integer.MAX_VALUE));
        assertEquals(72, out.size(), "8 L3 + 64 L2 only");
        assertTrue(out.stream().noneMatch(r -> r.request().level() < 2),
                "no requests finer than L2");
    }

    @Test
    void requests_useRegionIndexAsWorldSectionCoordAtTheirLevel() {
        // Camera at block (448,300,448) inside region 0 with a tiny focal
        // length so only camera-containing nodes descend:
        //   L3 band: 448/256=1, 300/256=1 -> child (1,1,1)
        //   L2 band: 448/128=3, 300/128=2 -> grandchild (3,2,3)
        //   L1 band: 300/64=4            -> great-grandchildren x,z in {6,7},
        //                                   y=4: four nodes surround the camera.
        var out = RefinementDemandSelector.select(new RefinementDemandSelector.Params(
                448, 300, 448, 10.0, SUB_DIV_PX,
                Level.L1.value(), 1e9, Integer.MAX_VALUE,
                List.of(new SectionPos(0, 0, 0))));
        assertEquals(6, out.size());
        assertTrue(out.stream().anyMatch(e -> e.request().equals(
                        new RefinementDemandSelector.NodeRequest(3, 1, 1, 1))),
                "L3 child region (1,1,1)");
        assertTrue(out.stream().anyMatch(e -> e.request().equals(
                        new RefinementDemandSelector.NodeRequest(2, 3, 2, 3))),
                "L2 grandchild region (3,2,3)");
        for (int cx = 6; cx <= 7; cx++) {
            for (int cz = 6; cz <= 7; cz++) {
                final int fx = cx;
                final int fz = cz;
                assertTrue(out.stream().anyMatch(e -> e.request().equals(
                                new RefinementDemandSelector.NodeRequest(1, fx, 4, fz))),
                        "L1 node (" + fx + ",4," + fz + ")");
            }
        }
    }

    @Test
    void regionsBeyondXZRenderDistance_areCulled() {
        // Camera at block (256,96,256) with a 200-block XZ cylinder.
        // Region A = SectionPos(0,0,0) covers blocks [0,512): centre is the
        // camera itself, so it must refine. Region B = SectionPos(32,0,0)
        // (region index 1, blocks [512,1024)) has its centre 512 blocks away
        // in XZ — beyond the cylinder — so it must contribute nothing.
        var out = RefinementDemandSelector.select(params(
                256, 96, 256, Level.L1.value(), 200, Integer.MAX_VALUE,
                List.of(new SectionPos(0, 0, 0), new SectionPos(32, 0, 0))));
        assertFalse(out.isEmpty(), "nearby region still refines");

        var onlyFar = RefinementDemandSelector.select(params(
                256, 96, 256, Level.L1.value(), 200, Integer.MAX_VALUE,
                List.of(new SectionPos(32, 0, 0))));
        assertTrue(onlyFar.isEmpty(),
                "region beyond XZ render distance must be culled entirely");
    }

    @Test
    void budget_capsEmissions_nearestFirst() {
        var out = RefinementDemandSelector.select(
                singleRegion(256, 96, 256, Level.L1.value(), 1e9, 12));
        assertEquals(12, out.size());
        for (int i = 1; i < out.size(); i++) {
            assertTrue(out.get(i - 1).distBlocks() <= out.get(i).distBlocks(),
                    "requests must be ordered nearest-first");
        }
    }

    @Test
    void selection_isDeterministic() {
        var a = RefinementDemandSelector.select(
                singleRegion(256, 96, 256, Level.L1.value(), 1e9, 100));
        var b = RefinementDemandSelector.select(
                singleRegion(256, 96, 256, Level.L1.value(), 1e9, 100));
        assertEquals(a, b);
    }
}
