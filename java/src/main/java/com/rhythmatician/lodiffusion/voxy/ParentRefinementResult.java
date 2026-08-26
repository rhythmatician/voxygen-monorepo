package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/** Observable result of one parent refinement intent. */
public record ParentRefinementResult(Status status, WriteOutcome writeOutcome) {
    public enum Status {
        PUBLISHED,
        PARENT_MISSING
    }

    public ParentRefinementResult {
        Objects.requireNonNull(status, "status");
        if (status == Status.PUBLISHED) {
            Objects.requireNonNull(writeOutcome, "writeOutcome");
        } else if (writeOutcome != null) {
            throw new IllegalArgumentException("missing parent cannot have a write outcome");
        }
    }

    static ParentRefinementResult published(WriteOutcome outcome) {
        return new ParentRefinementResult(Status.PUBLISHED, outcome);
    }

    static ParentRefinementResult parentMissing() {
        return new ParentRefinementResult(Status.PARENT_MISSING, null);
    }
}
