package com.rhythmatician.lodiffusion.voxy;

public record WriteOutcome(Status status, int nonAirWritten) {
    public enum Status {
        WRITTEN,
        SKIPPED_AIR,
        SKIPPED_EXISTS
    }

    public static WriteOutcome written(int n) {
        return new WriteOutcome(Status.WRITTEN, n);
    }

    public static WriteOutcome skippedAir() {
        return new WriteOutcome(Status.SKIPPED_AIR, 0);
    }

    public int nonAirCount() { return nonAirWritten; }

    public static WriteOutcome skippedExists() {
        return new WriteOutcome(Status.SKIPPED_EXISTS, 0);
    }
}
