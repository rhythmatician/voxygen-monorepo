package com.rhythmatician.lodiffusion.voxy;

import java.util.List;

/** Deep module for bounded top-down End refinement. */
public interface EndRefinement {
    /** Records vanilla ownership for one aligned L0 WorldSection. */
    void observeVanilla(ObservedVanilla observation);

    /** Admits proactive L1 parent transactions around the vanilla frontier. */
    int observeFrontier(List<VanillaFrontierGuardPlanner.ParentTransaction> transactions);

    /** Admits due work and executes at most one physical End work item. */
    StepResult advance(Frame frame);

    /** Immutable lifecycle and child-mask summary. */
    Snapshot snapshot();

    record ObservedVanilla(SectionPos l0Origin, int ownedOctantMask) {
        public ObservedVanilla {
            if (l0Origin == null || !Level.L0.isAligned(l0Origin)) {
                throw new IllegalArgumentException("l0Origin must be L0 aligned");
            }
            if (ownedOctantMask < 0 || (ownedOctantMask & ~0xFF) != 0) {
                throw new IllegalArgumentException("ownedOctantMask must use eight bits");
            }
        }
    }

    record Frame(long monotonicMillis, SectionPos playerSection,
                 List<SectionPos> horizonTargets, boolean stopped) {
        public Frame {
            if (playerSection == null || horizonTargets == null) {
                throw new NullPointerException("frame positions");
            }
            horizonTargets = List.copyOf(horizonTargets);
            for (SectionPos origin : horizonTargets) {
                if (!Level.L4.isAligned(origin)) {
                    throw new IllegalArgumentException("covered L4 origin is not aligned: " + origin);
                }
            }
        }
    }

    record StepResult(Status status, boolean terrainChanged) {
        public enum Status { IDLE, PROGRESSED, DEFERRED, FAILED, STOPPED }
    }

    record DemandSummary(long admitted, long completed, long failed,
                         long skipped, int queued, int executing) {
        public String compact() {
            return "q" + admitted + "/c" + completed + "/f" + failed
                    + "/s" + skipped + "@" + queued + "+" + executing;
        }
    }

    record InitialHorizonSummary(int targets, int terminal, int written,
                                 int existing, int empty, int failed) {
        public int renderable() { return written + existing; }
    }

    record Snapshot(DemandSummary horizon, DemandSummary refinement,
                    long pendingChildren, long executingChildren,
                    long representedChildren, long deterministicEmptyChildren,
                    long vanillaCoveredChildren, long retryableChildren,
                    InitialHorizonSummary initialHorizon,
                    String lifecycle) {}
}
