package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/** One request to replace a covered parent with a complete set of finer children. */
public record ParentRefinementIntent(
        SectionPos parentOrigin,
        Level parentLevel,
        ChildVolumeSource childVolumes) {

    @FunctionalInterface
    public interface ChildVolumeSource {
        VoxelVolume produce(Level childLevel, SectionPos childOrigin);
    }

    public ParentRefinementIntent {
        Objects.requireNonNull(parentOrigin, "parentOrigin");
        Objects.requireNonNull(parentLevel, "parentLevel");
        Objects.requireNonNull(childVolumes, "childVolumes");
        if (parentLevel.value() < Level.L1.value() || parentLevel.value() > Level.L4.value()) {
            throw new IllegalArgumentException("parent refinement level must be L1..L4");
        }
        if (!parentLevel.isAligned(parentOrigin)) {
            throw new IllegalArgumentException("parent origin is not aligned to " + parentLevel);
        }
    }
}
