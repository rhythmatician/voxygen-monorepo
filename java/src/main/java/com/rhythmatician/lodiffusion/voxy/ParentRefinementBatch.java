package com.rhythmatician.lodiffusion.voxy;

import java.util.List;
import java.util.Objects;

/**
 * Complete handoff of one parent section to its eight finer children.
 *
 * <p>A child outcome is terminal only after its geometry has been written or
 * proved empty. The parent mask is therefore not a promise: it is the exact
 * set of children that now have renderable geometry.
 */
final class ParentRefinementBatch {
    public record Child(int octant, SectionPos origin, VoxelVolume volume) {
        public Child {
            if (octant < 0 || octant > 7) {
                throw new IllegalArgumentException("octant must be 0..7: " + octant);
            }
            Objects.requireNonNull(origin, "origin");
            Objects.requireNonNull(volume, "volume");
            if (volume.extent() != 32) {
                throw new IllegalArgumentException("child volume must have extent 32");
            }
        }
    }

    private final SectionPos parentOrigin;
    private final Level parentLevel;
    private final List<Child> children;
    private final int requiredMask;
    private final ChildMaterializationOutcome[] outcomes = new ChildMaterializationOutcome[8];
    private int terminalMask;
    private int nonEmptyMask;

    private ParentRefinementBatch(SectionPos parentOrigin, Level parentLevel, List<Child> children) {
        this.parentOrigin = Objects.requireNonNull(parentOrigin, "parentOrigin");
        this.parentLevel = Objects.requireNonNull(parentLevel, "parentLevel");
        if (parentLevel.value() < Level.L1.value() || parentLevel.value() > Level.L4.value()) {
            throw new IllegalArgumentException("parent refinement level must be L1..L4");
        }
        if (!parentLevel.isAligned(parentOrigin)) {
            throw new IllegalArgumentException("parent origin is not aligned to " + parentLevel);
        }
        Objects.requireNonNull(children, "children");
        if (children.size() != 8) {
            throw new IllegalArgumentException("a parent refinement requires all 8 child outcomes");
        }
        int mask = 0;
        for (Child child : children) {
            if (child.volume().extent() != 32) {
                throw new IllegalArgumentException("child volume must have extent 32");
            }
            int bit = 1 << child.octant();
            if ((mask & bit) != 0) {
                throw new IllegalArgumentException("duplicate child octant: " + child.octant());
            }
            SectionPos expectedOrigin = childOrigin(parentOrigin, parentLevel, child.octant());
            if (!expectedOrigin.equals(child.origin())) {
                throw new IllegalArgumentException("child " + child.octant()
                        + " origin must be " + expectedOrigin + ", got " + child.origin());
            }
            mask |= bit;
        }
        this.children = List.copyOf(children);
        this.requiredMask = mask;
    }

    static ParentRefinementBatch materialize(ParentRefinementIntent intent) {
        Objects.requireNonNull(intent, "intent");
        Level childLevel = Level.values()[intent.parentLevel().value() - 1];
        List<SectionPos> origins = childOrigins(intent.parentOrigin(), intent.parentLevel());
        java.util.ArrayList<Child> children = new java.util.ArrayList<>(8);
        for (int octant = 0; octant < 8; octant++) {
            SectionPos origin = origins.get(octant);
            VoxelVolume volume = Objects.requireNonNull(
                    intent.childVolumes().produce(childLevel, origin),
                    "child volume for octant " + octant);
            children.add(new Child(octant, origin, volume));
        }
        return new ParentRefinementBatch(intent.parentOrigin(), intent.parentLevel(), children);
    }

    static List<SectionPos> childOrigins(SectionPos parentOrigin, Level parentLevel) {
        Objects.requireNonNull(parentOrigin, "parentOrigin");
        Objects.requireNonNull(parentLevel, "parentLevel");
        java.util.ArrayList<SectionPos> origins = new java.util.ArrayList<>(8);
        for (int octant = 0; octant < 8; octant++) {
            origins.add(childOrigin(parentOrigin, parentLevel, octant));
        }
        return List.copyOf(origins);
    }

    SectionPos parentOrigin() {
        return parentOrigin;
    }

    Level parentLevel() {
        return parentLevel;
    }

    int childLevel() {
        return parentLevel.value() - 1;
    }

    List<Child> children() {
        return children;
    }

    int requiredMask() {
        return requiredMask;
    }

    int terminalMask() {
        return terminalMask;
    }

    int nonEmptyMask() {
        return nonEmptyMask;
    }

    boolean isComplete() {
        return terminalMask == requiredMask;
    }

    private static SectionPos childOrigin(SectionPos parentOrigin, Level parentLevel, int octant) {
        int childRegionSections = Level.values()[parentLevel.value() - 1].regionSections();
        return new SectionPos(
                parentOrigin.x() + ((octant & 1) * childRegionSections),
                parentOrigin.y() + (((octant >> 2) & 1) * childRegionSections),
                parentOrigin.z() + (((octant >> 1) & 1) * childRegionSections));
    }

    /** Record one finished child; only the writer seam should call this. */
    void recordTerminal(int octant, ChildMaterializationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        int bit = 1 << octant;
        if ((requiredMask & bit) == 0) {
            throw new IllegalArgumentException("octant is not part of this batch: " + octant);
        }
        if ((terminalMask & bit) != 0) {
            throw new IllegalStateException("child outcome already terminal: " + octant);
        }
        outcomes[octant] = outcome;
        terminalMask |= bit;
        if (outcome.advertiseToParent()) {
            nonEmptyMask |= bit;
        }
    }

    List<ChildMaterializationOutcome> terminalOutcomes() {
        if (!isComplete()) {
            throw new IllegalStateException("child outcomes are not complete");
        }
        return List.of(outcomes.clone());
    }
}
