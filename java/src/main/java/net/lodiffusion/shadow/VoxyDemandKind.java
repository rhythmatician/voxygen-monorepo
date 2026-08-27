package net.lodiffusion.shadow;

/** Why Voxygen requested one Voxy coordinate and how urgently it should run. */
public enum VoxyDemandKind {
    /** Coarse terrain that establishes visible horizon coverage. */
    HORIZON_COVERAGE(Priority.HORIZON),
    /** A complete L1-to-L0 transaction protecting the vanilla frontier. */
    VANILLA_FRONTIER_GUARD(Priority.GUARD),
    /** A complete parent transaction selected for screen-space fidelity. */
    VISUAL_REFINEMENT(Priority.VISUAL);

    public enum Priority {
        GUARD,
        HORIZON,
        VISUAL
    }

    private final Priority priority;

    VoxyDemandKind(Priority priority) {
        this.priority = priority;
    }

    public Priority priority() {
        return priority;
    }
}
