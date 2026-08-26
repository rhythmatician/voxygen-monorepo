package com.rhythmatician.lodiffusion.voxy;

/** Compact per-session lifecycle evidence for parent refinement transactions. */
final class RefinementLifecycleTelemetry {
    private static final int DEQUEUED = 0;
    private static final int BLOCKED = 1;
    private static final int EMPTY_OR_SKIPPED = 2;
    private static final int NONEMPTY = 3;
    private static final int FAILED = 4;
    private final long[][] counts = new long[5][5];

    synchronized void recordDequeued(int parentLevel) {
        if (tracked(parentLevel)) counts[parentLevel][DEQUEUED]++;
    }

    synchronized void recordAttempt(int parentLevel, RefinementOutcome outcome) {
        if (!tracked(parentLevel) || outcome == null) return;
        counts[parentLevel][DEQUEUED]++;
        recordOutcome(parentLevel, outcome);
    }

    synchronized void recordOutcome(int parentLevel, RefinementOutcome outcome) {
        if (!tracked(parentLevel) || outcome == null) return;
        int category = switch (outcome.status()) {
            case BLOCKED_PARENT -> BLOCKED;
            case FAILED -> FAILED;
            case ALREADY_COVERED -> EMPTY_OR_SKIPPED;
            case PUBLISHED -> outcome.publishedNonEmpty() ? NONEMPTY : EMPTY_OR_SKIPPED;
        };
        counts[parentLevel][category]++;
    }

    synchronized String compact() {
        StringBuilder summary = new StringBuilder(127);
        for (int level = 4; level >= 1; level--) {
            if (!summary.isEmpty()) summary.append(' ');
            long[] value = counts[level];
            summary.append('L').append(level)
                    .append("[d").append(value[DEQUEUED])
                    .append(",b").append(value[BLOCKED])
                    .append(",e").append(value[EMPTY_OR_SKIPPED])
                    .append(",n").append(value[NONEMPTY])
                    .append(",f").append(value[FAILED]).append(']');
        }
        return summary.toString();
    }

    synchronized void reset() {
        for (long[] level : counts) java.util.Arrays.fill(level, 0L);
    }

    private static boolean tracked(int parentLevel) {
        return parentLevel >= 1 && parentLevel <= 4;
    }
}
