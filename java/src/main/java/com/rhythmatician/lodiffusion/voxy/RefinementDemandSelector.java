package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * CPU-side screen-space-error refinement demand selector (ADR 0011).
 *
 * <p>Replaces fixed per-Level radii with Voxy-style projected screen-space
 * descent: from each covered L4 region, a child node emits a refinement
 * request at the next-finer Level iff its projected screen area exceeds the
 * subdivision threshold. Recurses L4→L3→L2→L1 (never finer than configured).
 *
 * <p>Geometry mirrors Voxy's {@code screenspace.glsl}:
 * <ul>
 *   <li>Node at Level L spans {@code 32 << L} blocks per axis; region index r
 *       covers blocks {@code [r * (32<<L), (r+1) * (32<<L))}.</li>
 *   <li>Projected area ≈ {@code size² · focal² / dist²} using closest-point
 *       distance in 3D; descend iff area &gt; {@code subDivisionPx²}.</li>
 *   <li>Coverage culling is XZ-cylindrical ({@code renderDistanceBlocks}).</li>
 * </ul>
 *
 * <p>Pure function: no world, no queue, no clock. The caller owns enqueueing,
 * dedup, and budget feedback. Package-private, not a public SPI.
 */
final class RefinementDemandSelector {

    /** A demanded refinement: region index == world-section coord at level. */
    public record NodeRequest(int level, int wsX, int wsY, int wsZ) { }

    /** One emitted demand with its selection-time distance (for ordering). */
    public record Emission(NodeRequest request, double distBlocks) { }

    /** Selection inputs. Covered L4 regions are region-index SectionPos values. */
    public record Params(
            double camX, double camY, double camZ,
            double focalPx, double subDivisionPx,
            int finestLevelValue,
            double renderDistanceBlocks,
            int budget,
            List<SectionPos> coveredL4Regions) {}

    private RefinementDemandSelector() { }

    /**
     * Select refinement demands for covered L4 regions, nearest-first,
     * capped at {@code budget}. Deterministic.
     */
    public static List<Emission> select(Params p) {
        List<Emission> out = new ArrayList<>();
        // Threshold as squared distance per unit size²: descend iff
        // size² * focal² / dist² > subDiv²  <=>  dist < size * focal / subDiv.
        double refDistPerSize = p.focalPx() / p.subDivisionPx();

        for (SectionPos l4 : p.coveredL4Regions()) {
            long xzDistSq = xzDistanceSquaredToRegionCentre(p, l4.x(), l4.z(), 4);
            double rd = p.renderDistanceBlocks();
            if (xzDistSq > rd * rd) {
                continue;
            }
            // All 8 L3 children of this L4 region.
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    for (int cx = 0; cx < 2; cx++) {
                        descend(p, 3, l4.x() * 2 + cx, l4.y() * 2 + cy,
                                l4.z() * 2 + cz, refDistPerSize, out);
                    }
                }
            }
        }

        out.sort(Comparator.comparingDouble((Emission e) -> e.distBlocks())
                .thenComparingInt(e -> e.request().hashCode()));
        if (out.size() > p.budget()) {
            out = new ArrayList<>(out.subList(0, p.budget()));
        }
        return out;
    }

    /** Recursive descent: emit request for node (level,x,y,z), then children. */
    private static void descend(Params p, int level, int x, int y, int z,
                                double refDistPerSize, List<Emission> out) {
        if (level < p.finestLevelValue()) {
            return;
        }
        int size = 32 << level;
        double dist = closestPointDistance(p, x * size, y * size, z * size, size);
        boolean shouldDescend = dist < size * refDistPerSize;
        if (!shouldDescend) {
            return;
        }
        out.add(new Emission(new NodeRequest(level, x, y, z), dist));
        if (level - 1 >= p.finestLevelValue()) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    for (int cx = 0; cx < 2; cx++) {
                        descend(p, level - 1, x * 2 + cx, y * 2 + cy, z * 2 + cz,
                                refDistPerSize, out);
                    }
                }
            }
        }
    }

    /** Closest distance from camera to the AABB [min, min+size)³. */
    private static double closestPointDistance(Params p, int minX, int minY, int minZ, int size) {
        double dx = Math.max(minX - p.camX(), Math.max(0.0, p.camX() - (minX + size)));
        double dy = Math.max(minY - p.camY(), Math.max(0.0, p.camY() - (minY + size)));
        double dz = Math.max(minZ - p.camZ(), Math.max(0.0, p.camZ() - (minZ + size)));
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private static long xzDistanceSquaredToRegionCentre(
            Params p, int regionX, int regionZ, int level) {
        int size = 32 << level;
        double cx = (regionX + 0.5) * size;
        double cz = (regionZ + 0.5) * size;
        double dx = cx - p.camX();
        double dz = cz - p.camZ();
        return (long) (dx * dx + dz * dz);
    }
}
