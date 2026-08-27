package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/** Observable result of one parent refinement intent. */
public record ParentRefinementResult(
        Status status, WriteOutcome writeOutcome, int representedMask, int emptyMask) {
    public enum Status {
        PUBLISHED,
        PARENT_MISSING
    }

    public ParentRefinementResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.PUBLISHED) {
            Objects.requireNonNull(writeOutcome, "writeOutcome");
            if ((representedMask & emptyMask) != 0
                    || ((representedMask | emptyMask) & ~0xFF) != 0) {
                throw new IllegalArgumentException("terminal child masks must be disjoint eight-bit masks");
            }
        } else if (writeOutcome != null) {
            throw new IllegalArgumentException("missing parent cannot have a write outcome");
        } else if (representedMask != 0 || emptyMask != 0) {
            throw new IllegalArgumentException("missing parent cannot have terminal child masks");
        }
    }

    static ParentRefinementResult published(WriteOutcome outcome) {
        return new ParentRefinementResult(Status.PUBLISHED, outcome,
                outcome.status() == WriteOutcome.Status.SKIPPED_AIR ? 0 : 0xFF,
                outcome.status() == WriteOutcome.Status.SKIPPED_AIR ? 0xFF : 0);
    }

    static ParentRefinementResult published(
            WriteOutcome outcome, int representedMask, int emptyMask) {
        return new ParentRefinementResult(
                Status.PUBLISHED, outcome, representedMask, emptyMask);
    }

    static ParentRefinementResult parentMissing() {
        return new ParentRefinementResult(Status.PARENT_MISSING, null, 0, 0);
    }
}
