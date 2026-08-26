package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/** Storage provenance for one child inside a parent refinement transaction. */
record ChildMaterializationOutcome(Kind kind, int nonAirWritten) {
    enum Kind {
        GENERATED_FALLBACK,
        PRESERVED_EXISTING,
        EMPTY
    }

    ChildMaterializationOutcome {
        if (nonAirWritten < 0) {
            throw new IllegalArgumentException("nonAirWritten must be non-negative");
        }
        if (kind != Kind.GENERATED_FALLBACK && nonAirWritten != 0) {
            throw new IllegalArgumentException(kind + " cannot report written voxels");
        }
    }

    static ChildMaterializationOutcome generatedFallback(int nonAirWritten) {
        if (nonAirWritten == 0) {
            throw new IllegalArgumentException("generated fallback must contain non-air voxels");
        }
        return new ChildMaterializationOutcome(Kind.GENERATED_FALLBACK, nonAirWritten);
    }

    static ChildMaterializationOutcome preservedExisting() {
        return new ChildMaterializationOutcome(Kind.PRESERVED_EXISTING, 0);
    }

    static ChildMaterializationOutcome empty() {
        return new ChildMaterializationOutcome(Kind.EMPTY, 0);
    }

    static ChildMaterializationOutcome fromWriteOutcome(WriteOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome.status()) {
            case WRITTEN -> generatedFallback(outcome.nonAirWritten());
            case SKIPPED_EXISTS -> preservedExisting();
            case SKIPPED_AIR -> empty();
        };
    }

    boolean advertiseToParent() {
        return kind != Kind.EMPTY;
    }

    WriteOutcome asWriteOutcome() {
        return switch (kind) {
            case GENERATED_FALLBACK -> WriteOutcome.written(nonAirWritten);
            case PRESERVED_EXISTING -> WriteOutcome.skippedExists();
            case EMPTY -> WriteOutcome.skippedAir();
        };
    }
}
