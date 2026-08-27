package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import me.cortex.voxy.client.RenderStatistics;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class VoxyNativeLodStatsTest {
    @AfterEach
    void restoreVoxyStatistics() {
        RenderStatistics.enabled = false;
    }

    @Test
    void diagnosticsEnableNativeStatisticsBeforeTraversalConstruction() {
        RenderStatistics.enabled = false;

        VoxyNativeLodStats.enableForDiagnostics(true, false);

        assertTrue(RenderStatistics.enabled);
    }

    @Test
    void disabledDiagnosticsExposeNoNativeRenderSnapshot() {
        RenderStatistics.enabled = false;

        VoxyNativeLodStats.enableForDiagnostics(false, false);

        assertFalse(RenderStatistics.enabled);
        assertTrue(VoxyNativeLodStats.snapshot().isEmpty());
    }

    @Test
    void snapshotPreservesNativeL0ThroughL4Order() {
        RenderStatistics.enabled = true;
        for (int level = 0; level < 5; level++) {
            RenderStatistics.hierarchicalRenderSections[level] = 10 + level;
        }

        var snapshot = VoxyNativeLodStats.snapshot().orElseThrow();

        assertArrayEquals(new int[] {10, 11, 12, 13, 14}, snapshot.levels());
    }
}
