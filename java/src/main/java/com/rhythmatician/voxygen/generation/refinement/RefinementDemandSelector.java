package com.rhythmatician.voxygen.generation.refinement;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.WorldSectionCoord;

/**
 * CPU-side screen-space-error refinement demand selector (ADR 0011).
 *
 * <p>Replaces fixed per-Level radii with Voxy-style projected screen-space
 * descent: from each covered L4 region, a child node emits a refinement
 * request at the next-finer Level iff its projected screen area exceeds the
 * subdivision threshold. Recurses L4→L3→L2→L1→L0 (never finer than configured).
 *
 * <p>Geometry mirrors Voxy's {@code screenspace.glsl}:
 * <ul>
 *   <li>Covered L4 regions arrive as canonical {@link SectionPos} origins on the
 *       16-block section lattice; they are converted to Voxy WorldSection indices
 *       (one WorldSection spans {@code 32 << L} blocks at Level L) exactly once,
 *       on entry to {@link #select}.</li>
 *   <li>Node at Level L spans {@code 32 << L} blocks per axis; WorldSection index r
 *       covers blocks {@code [r * (32<<L), (r+1) * (32<<L))}.</li>
 *   <li>Projected area ≈ {@code size² · focal² / dist²} using closest-point
 *       distance in 3D; descend iff area &gt; {@code subDivisionPx²}.</li>
 *   <li>Coverage culling is XZ-cylindrical ({@code renderDistanceBlocks}).</li>
 * </ul>
 *
 * <p>Pure function: no world, no queue, no clock. The caller owns enqueueing,
 * dedup, and budget feedback. Package-private, not a public SPI.
 */
public final class RefinementDemandSelector {

    /** A demanded transaction: this parent is replaced by all eight children. */
    public record NodeRequest(int level, int wsX, int wsY, int wsZ) { }

    /** One emitted demand with its selection-time distance (for ordering). */
    public record Emission(NodeRequest request, double distBlocks, int demandedChildMask) {
        public Emission {
            if (demandedChildMask <= 0 || (demandedChildMask & ~0xFF) != 0) {
                throw new IllegalArgumentException("demandedChildMask must contain child bits");
            }
        }
    }

    private record Selection(double distBlocks, int demandedChildMask) {
        Selection merge(double distance, int childMask) {
            return new Selection(Math.min(distBlocks, distance), demandedChildMask | childMask);
        }
    }

    /** Selection inputs. Covered L4 regions are canonical SectionPos origins. */
    public record Params(
            double camX, double camY, double camZ,
            double focalPx, double subDivisionPx,
            int finestLevelValue,
            double renderDistanceBlocks,
            int budget,
            List<SectionPos> coveredL4Regions) {}

    private RefinementDemandSelector() { }

    /**
     * Select all eligible refinement demands for covered L4 regions in
     * normalized screen-space order. The caller owns deduplication and the
     * per-pass budget, so truncation is deliberately not performed here.
     */
    public static List<Emission> select(Params p) {
        Map<NodeRequest, Selection> selected = new LinkedHashMap<>();
        // Threshold as squared distance per unit size²: descend iff
        // size² * focal² / dist² > subDiv²  <=>  dist < size * focal / subDiv.
        double refDistPerSize = p.focalPx() / p.subDivisionPx();

        for (SectionPos l4 : p.coveredL4Regions()) {
            // Canonical section lattice (16-block units) -> Voxy WorldSection
            // index at L4 (512-block cells). Convert exactly once here; the
            // rest of the selector operates purely in WorldSection indices.
            long wsX = WorldSectionCoord.sectionToWorldSection(l4.x(), Level.L4.value());
            long wsZ = WorldSectionCoord.sectionToWorldSection(l4.z(), Level.L4.value());
            long xzDistSq = xzDistanceSquaredToRegionCentre(p, wsX, wsZ, 4);
            double rd = p.renderDistanceBlocks();
            if (xzDistSq > rd * rd) {
                continue;
            }
            // All 8 L3 children of this L4 region.
            int wsY = WorldSectionCoord.sectionToWorldSection(l4.y(), Level.L4.value());
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    for (int cx = 0; cx < 2; cx++) {
                        descend(p, 3, (int) (wsX * 2 + cx), wsY * 2 + cy,
                                (int) (wsZ * 2 + cz), refDistPerSize, selected);
                    }
                }
            }
        }

        List<Emission> out = new ArrayList<>();
        selected.forEach((request, selection) -> out.add(new Emission(
                request, selection.distBlocks(), selection.demandedChildMask())));
        out.sort(Comparator
                .comparingDouble(RefinementDemandSelector::normalizedDistance)
                .thenComparing(Comparator.comparingInt(
                        (Emission e) -> e.request().level()).reversed())
                .thenComparingInt(e -> e.request().wsX())
                .thenComparingInt(e -> e.request().wsY())
                .thenComparingInt(e -> e.request().wsZ()));
        return out;
    }

    private static double normalizedDistance(Emission emission) {
        return emission.distBlocks() / (32.0 * (1 << emission.request().level()));
    }

    /** Recursive descent: select the parent transaction for each demanded child. */
    private static void descend(Params p, int level, int x, int y, int z,
                                double refDistPerSize, Map<NodeRequest, Selection> selected) {
        if (level < p.finestLevelValue()) {
            return;
        }
        int size = 32 << level;
        double dist = closestPointDistance(p, x * size, y * size, z * size, size);
        boolean shouldDescend = dist < size * refDistPerSize;
        if (!shouldDescend) {
            return;
        }
        NodeRequest parent = new NodeRequest(level + 1, x >> 1, y >> 1, z >> 1);
        int octant = Math.floorMod(x, 2)
                | (Math.floorMod(z, 2) << 1)
                | (Math.floorMod(y, 2) << 2);
        int childMask = 1 << octant;
        selected.compute(parent, (ignored, existing) -> existing == null
                ? new Selection(dist, childMask)
                : existing.merge(dist, childMask));
        if (level - 1 >= p.finestLevelValue()) {
            for (int cy = 0; cy < 2; cy++) {
                for (int cz = 0; cz < 2; cz++) {
                    for (int cx = 0; cx < 2; cx++) {
                        descend(p, level - 1, x * 2 + cx, y * 2 + cy, z * 2 + cz,
                                refDistPerSize, selected);
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
            Params p, long regionX, long regionZ, int level) {
        int size = 32 << level;
        double cx = (regionX + 0.5) * size;
        double cz = (regionZ + 0.5) * size;
        double dx = cx - p.camX();
        double dz = cz - p.camZ();
        return (long) (dx * dx + dz * dz);
    }
}
