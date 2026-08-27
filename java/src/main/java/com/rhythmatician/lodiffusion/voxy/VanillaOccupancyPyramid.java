package com.rhythmatician.lodiffusion.voxy;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Sparse vanilla-occupancy index for End refinement scheduling.
 *
 * <p>An observation says vanilla already owns one or more 16-cubed octants of
 * an L0 Voxy WorldSection. It cannot be beaten retroactively. The resulting
 * mixed cells identify transitions that need topology protection now. The
 * outward boundary identifies nearby non-full cells where Voxygen can still
 * get ahead of vanilla.
 *
 * <p>"Touching" means the eight horizontal Chebyshev neighbors at the same Y
 * and level. Vertical neighbors do not join the visual boundary. Parent/child
 * occupancy remains fully 3D, with octant bits {@code X | Z << 1 | Y << 2}.
 */
final class VanillaOccupancyPyramid {
    static final int MAX_LEVEL = 4;

    enum Occupancy {
        NONE,
        MIXED,
        FULL_VANILLA
    }

    enum Relation {
        FULL,
        FRONTIER,
        ORDINARY
    }

    record Cell(int level, int x, int y, int z) {
        Cell {
            if (level < 0 || level > MAX_LEVEL) {
                throw new IllegalArgumentException("level must be 0.." + MAX_LEVEL + ": " + level);
            }
        }

        Cell parent() {
            if (level == MAX_LEVEL) {
                throw new IllegalStateException("L" + MAX_LEVEL + " has no tracked parent");
            }
            return new Cell(level + 1,
                    Math.floorDiv(x, 2), Math.floorDiv(y, 2), Math.floorDiv(z, 2));
        }

        Cell child(int octant) {
            if (level == 0) {
                throw new IllegalStateException("L0 children are base octants, not WorldSections");
            }
            if (octant < 0 || octant > 7) {
                throw new IllegalArgumentException("octant must be 0..7: " + octant);
            }
            return new Cell(level - 1,
                    x * 2 + (octant & 1),
                    y * 2 + ((octant >> 2) & 1),
                    z * 2 + ((octant >> 1) & 1));
        }
    }

    record Delta(Set<Cell> addedUrgent, Set<Cell> removedUrgent, Set<Cell> newlyMixedParents) {
        Delta {
            addedUrgent = immutableCopy(addedUrgent);
            removedUrgent = immutableCopy(removedUrgent);
            newlyMixedParents = immutableCopy(newlyMixedParents);
        }

        boolean isEmpty() {
            return addedUrgent.isEmpty() && removedUrgent.isEmpty() && newlyMixedParents.isEmpty();
        }

        private static Set<Cell> immutableCopy(Set<Cell> cells) {
            return Collections.unmodifiableSet(new LinkedHashSet<>(cells));
        }
    }

    private static final Delta EMPTY_DELTA = new Delta(Set.of(), Set.of(), Set.of());

    /* Number of vanilla-owned 16-cubed base octants below each cell. */
    private final Map<Cell, Integer> occupiedDescendants = new HashMap<>();
    private final Map<Cell, Integer> l0OctantMasks = new HashMap<>();
    private final Set<Cell> urgentBoundary = new HashSet<>();

    Delta observeVanillaOctant(int l0X, int l0Y, int l0Z, int octant) {
        if (octant < 0 || octant > 7) {
            throw new IllegalArgumentException("octant must be 0..7: " + octant);
        }
        return observeVanillaL0Octants(l0X, l0Y, l0Z, 1 << octant);
    }

    /**
     * Observes the current vanilla-owned octant mask for one L0 WorldSection.
     * This is the direct seam for {@code loadedVanillaL0OctantMask}.
     */
    Delta observeVanillaL0Octants(int x, int y, int z, int ownedOctantMask) {
        if ((ownedOctantMask & ~0xFF) != 0 || ownedOctantMask < 0) {
            throw new IllegalArgumentException(
                    "owned octant mask must use only eight bits: " + ownedOctantMask);
        }
        Cell leaf = new Cell(0, x, y, z);
        int previousMask = l0OctantMasks.getOrDefault(leaf, 0);
        int newBits = ownedOctantMask & ~previousMask;
        if (newBits == 0) {
            return EMPTY_DELTA;
        }

        Set<Cell> affected = affectedBoundaryCells(leaf);
        Map<Cell, Boolean> wasUrgent = new HashMap<>(affected.size());
        for (Cell cell : affected) {
            wasUrgent.put(cell, isUrgent(cell));
        }

        Set<Cell> newlyMixed = new HashSet<>();
        int newlyOccupiedCount = Integer.bitCount(newBits);
        l0OctantMasks.put(leaf, previousMask | ownedOctantMask);
        Cell cell = leaf;
        for (int level = 0; level <= MAX_LEVEL; level++) {
            Occupancy before = classify(cell);
            occupiedDescendants.merge(cell, newlyOccupiedCount, (a, b) -> Integer.sum(a, b));
            Occupancy after = classify(cell);
            if (before == Occupancy.NONE && after == Occupancy.MIXED) {
                newlyMixed.add(cell);
            }
            if (level < MAX_LEVEL) {
                cell = cell.parent();
            }
        }

        Set<Cell> added = new HashSet<>();
        Set<Cell> removed = new HashSet<>();
        for (Cell candidate : affected) {
            boolean nowUrgent = isUrgent(candidate);
            if (!wasUrgent.get(candidate) && nowUrgent) {
                urgentBoundary.add(candidate);
                added.add(candidate);
            } else if (wasUrgent.get(candidate) && !nowUrgent) {
                urgentBoundary.remove(candidate);
                removed.add(candidate);
            }
        }
        return new Delta(added, removed, newlyMixed);
    }

    Occupancy classify(Cell cell) {
        int occupied = occupiedDescendants.getOrDefault(cell, 0);
        if (occupied == 0) {
            return Occupancy.NONE;
        }
        return occupied == capacity(cell.level()) ? Occupancy.FULL_VANILLA : Occupancy.MIXED;
    }

    Relation relation(Cell cell) {
        Occupancy occupancy = classify(cell);
        if (occupancy == Occupancy.FULL_VANILLA) {
            return Relation.FULL;
        }
        return urgentBoundary.contains(cell) ? Relation.FRONTIER : Relation.ORDINARY;
    }

    /** Bit set means that child octant is not completely owned by vanilla. */
    int missingChildOctants(Cell parent) {
        if (parent.level() == 0) {
            return (~l0OctantMasks.getOrDefault(parent, 0)) & 0xFF;
        }
        int missing = 0;
        for (int octant = 0; octant < 8; octant++) {
            if (classify(parent.child(octant)) != Occupancy.FULL_VANILLA) {
                missing |= 1 << octant;
            }
        }
        return missing;
    }

    Set<Cell> urgentBoundary() {
        return Collections.unmodifiableSet(new HashSet<>(urgentBoundary));
    }

    void clear() {
        occupiedDescendants.clear();
        l0OctantMasks.clear();
        urgentBoundary.clear();
    }

    private Set<Cell> affectedBoundaryCells(Cell leaf) {
        Set<Cell> affected = new HashSet<>(9 * (MAX_LEVEL + 1));
        Cell ancestor = leaf;
        for (int level = 0; level <= MAX_LEVEL; level++) {
            addHorizontalNeighborhood(affected, ancestor);
            if (level < MAX_LEVEL) {
                ancestor = ancestor.parent();
            }
        }
        return affected;
    }

    private boolean isUrgent(Cell cell) {
        if (classify(cell) == Occupancy.FULL_VANILLA) {
            return false;
        }
        if (classify(cell) == Occupancy.MIXED) {
            return true;
        }
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                if (dx == 0 && dz == 0) {
                    continue;
                }
                Cell neighbor = new Cell(cell.level(), cell.x() + dx, cell.y(), cell.z() + dz);
                if (classify(neighbor) != Occupancy.NONE) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void addHorizontalNeighborhood(Set<Cell> result, Cell center) {
        for (int dx = -1; dx <= 1; dx++) {
            for (int dz = -1; dz <= 1; dz++) {
                result.add(new Cell(center.level(), center.x() + dx, center.y(), center.z() + dz));
            }
        }
    }

    private static int capacity(int level) {
        return 1 << (3 * (level + 1));
    }
}
