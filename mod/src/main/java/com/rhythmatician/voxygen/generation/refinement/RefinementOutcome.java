package com.rhythmatician.voxygen.generation.refinement;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.output.WriteOutcome;

/** Result of trying to turn finer End demand into published coverage. */
public record RefinementOutcome(Status status, SectionPos blockedOn, WriteOutcome writeOutcome) {
    public enum Status {
        PUBLISHED,
        ALREADY_COVERED,
        BLOCKED_PARENT,
        FAILED
    }

    public static RefinementOutcome published() {
        return published(WriteOutcome.written(1));
    }

    public static RefinementOutcome published(WriteOutcome writeOutcome) {
        return new RefinementOutcome(Status.PUBLISHED, null, writeOutcome);
    }

    public static RefinementOutcome alreadyCovered() {
        return new RefinementOutcome(Status.ALREADY_COVERED, null, null);
    }

    public static RefinementOutcome blockedParent(SectionPos parent) {
        return new RefinementOutcome(Status.BLOCKED_PARENT, parent, null);
    }

    public static RefinementOutcome failed() {
        return new RefinementOutcome(Status.FAILED, null, null);
    }

    public boolean publishedNonEmpty() {
        return status == Status.PUBLISHED
                && writeOutcome != null
                && writeOutcome.status() != WriteOutcome.Status.SKIPPED_AIR;
    }
}
