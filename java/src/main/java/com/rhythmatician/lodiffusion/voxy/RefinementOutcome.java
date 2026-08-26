package com.rhythmatician.lodiffusion.voxy;

/** Result of trying to turn finer End demand into published coverage. */
record RefinementOutcome(Status status, SectionPos blockedOn, WriteOutcome writeOutcome) {
    enum Status {
        PUBLISHED,
        ALREADY_COVERED,
        BLOCKED_PARENT,
        FAILED
    }

    static RefinementOutcome published() {
        return published(WriteOutcome.written(1));
    }

    static RefinementOutcome published(WriteOutcome writeOutcome) {
        return new RefinementOutcome(Status.PUBLISHED, null, writeOutcome);
    }

    static RefinementOutcome alreadyCovered() {
        return new RefinementOutcome(Status.ALREADY_COVERED, null, null);
    }

    static RefinementOutcome blockedParent(SectionPos parent) {
        return new RefinementOutcome(Status.BLOCKED_PARENT, parent, null);
    }

    static RefinementOutcome failed() {
        return new RefinementOutcome(Status.FAILED, null, null);
    }

    boolean publishedNonEmpty() {
        return status == Status.PUBLISHED
                && writeOutcome != null
                && writeOutcome.status() != WriteOutcome.Status.SKIPPED_AIR;
    }
}
