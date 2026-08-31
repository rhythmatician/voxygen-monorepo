package com.rhythmatician.voxygen.generation.refinement;

import java.util.Objects;
import com.rhythmatician.voxygen.output.WriteOutcome;

/** Storage provenance for one child inside a parent refinement transaction. */
public record ChildMaterializationOutcome(Kind kind, int nonAirWritten) {
    public enum Kind {
        GENERATED_FALLBACK,
        PRESERVED_EXISTING,
        EMPTY
    }

    public ChildMaterializationOutcome {
        if (nonAirWritten < 0) {
            throw new IllegalArgumentException("nonAirWritten must be non-negative");
        }
        if (kind != Kind.GENERATED_FALLBACK && nonAirWritten != 0) {
            throw new IllegalArgumentException(kind + " cannot report written voxels");
        }
    }

    public static ChildMaterializationOutcome generatedFallback(int nonAirWritten) {
        if (nonAirWritten == 0) {
            throw new IllegalArgumentException("generated fallback must contain non-air voxels");
        }
        return new ChildMaterializationOutcome(Kind.GENERATED_FALLBACK, nonAirWritten);
    }

    public static ChildMaterializationOutcome preservedExisting() {
        return new ChildMaterializationOutcome(Kind.PRESERVED_EXISTING, 0);
    }

    public static ChildMaterializationOutcome empty() {
        return new ChildMaterializationOutcome(Kind.EMPTY, 0);
    }

    public static ChildMaterializationOutcome fromWriteOutcome(WriteOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        return switch (outcome.status()) {
            case WRITTEN -> generatedFallback(outcome.nonAirWritten());
            case SKIPPED_EXISTS -> preservedExisting();
            case SKIPPED_AIR -> empty();
        };
    }

    public boolean advertiseToParent() {
        return kind != Kind.EMPTY;
    }

    public WriteOutcome asWriteOutcome() {
        return switch (kind) {
            case GENERATED_FALLBACK -> WriteOutcome.written(nonAirWritten);
            case PRESERVED_EXISTING -> WriteOutcome.skippedExists();
            case EMPTY -> WriteOutcome.skippedAir();
        };
    }
}
