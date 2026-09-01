package com.rhythmatician.voxygen.generation.refinement;

import java.util.Objects;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;

/** One request to replace a covered parent with a complete set of finer children. */
public record ParentRefinementIntent(
        SectionPos parentOrigin,
        Level parentLevel,
        int demandedChildMask,
        ChildVolumeSource childVolumes) {

    @FunctionalInterface
    public interface ChildVolumeSource {
        VoxelVolume produce(Level childLevel, SectionPos childOrigin);
    }

    public ParentRefinementIntent {
        Objects.requireNonNull(parentOrigin, "parentOrigin");
        Objects.requireNonNull(parentLevel, "parentLevel");
        Objects.requireNonNull(childVolumes, "childVolumes");
        if (demandedChildMask <= 0 || (demandedChildMask & ~0xFF) != 0) {
            throw new IllegalArgumentException("demandedChildMask must contain child bits");
        }
        if (parentLevel.value() < Level.L1.value() || parentLevel.value() > Level.L4.value()) {
            throw new IllegalArgumentException("parent refinement level must be L1..L4");
        }
        if (!parentLevel.isAligned(parentOrigin)) {
            throw new IllegalArgumentException("parent origin is not aligned to " + parentLevel);
        }
    }

    public ParentRefinementIntent(
            SectionPos parentOrigin, Level parentLevel, ChildVolumeSource childVolumes) {
        this(parentOrigin, parentLevel, 0xFF, childVolumes);
    }
}
