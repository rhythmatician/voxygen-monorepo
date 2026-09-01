package com.rhythmatician.lodiffusion.voxy;

import java.util.Optional;
import me.cortex.voxy.client.RenderStatistics;

/** Client diagnostics adapter for Voxy's native hierarchical render counters. */
public final class VoxyNativeLodStats {
    public record Snapshot(int l0, int l1, int l2, int l3, int l4) {
        public int[] levels() {
            return new int[] {l0, l1, l2, l3, l4};
        }

        public String asJsonArray() {
            return "[" + l0 + "," + l1 + "," + l2 + "," + l3 + "," + l4 + "]";
        }
    }

    private VoxyNativeLodStats() {
    }

    /**
     * Must run before Voxy constructs its traverser, because that construction compiles the
     * statistics shader variant.
     */
    public static void enableForDiagnostics(boolean overlayRequested, boolean afkDiagnosticsRequested) {
        if (overlayRequested || afkDiagnosticsRequested) {
            RenderStatistics.enabled = true;
        }
    }

    /** Returns the latest native L0 through L4 render-section counts, if enabled at startup. */
    public static Optional<Snapshot> snapshot() {
        if (!RenderStatistics.enabled || RenderStatistics.hierarchicalRenderSections.length < 5) {
            return Optional.empty();
        }
        int[] sections = RenderStatistics.hierarchicalRenderSections;
        return Optional.of(new Snapshot(sections[0], sections[1], sections[2], sections[3], sections[4]));
    }
}
