package com.rhythmatician.lodiffusion.voxy;

/** Result of trying to turn finer End demand into published coverage. */
record RefinementOutcome(Status status, SectionPos blockedOn) {
    enum Status {
        PUBLISHED,
        ALREADY_COVERED,
        BLOCKED_PARENT,
        FAILED
    }

    static RefinementOutcome published() {
        return new RefinementOutcome(Status.PUBLISHED, null);
    }

    static RefinementOutcome alreadyCovered() {
        return new RefinementOutcome(Status.ALREADY_COVERED, null);
    }

    static RefinementOutcome blockedParent(SectionPos parent) {
        return new RefinementOutcome(Status.BLOCKED_PARENT, parent);
    }

    static RefinementOutcome failed() {
        return new RefinementOutcome(Status.FAILED, null);
    }
}
