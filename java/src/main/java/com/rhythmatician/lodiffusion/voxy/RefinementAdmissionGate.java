package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import java.util.concurrent.atomic.AtomicBoolean;
import net.lodiffusion.shadow.VoxyWorkKind;

/** Test-only startup probe that isolates L4 horizon coverage from refinement work. */
public final class RefinementAdmissionGate {
    public static final String DISABLE_PROPERTY = "lodiffusion.test.disableRefinementAdmission";
    private static final AtomicBoolean LOGGED = new AtomicBoolean();

    private RefinementAdmissionGate() {
    }

    public static boolean allows(VoxyWorkKind workKind) {
        return workKind != VoxyWorkKind.PARENT_REFINEMENT || !disabled();
    }

    public static boolean disabled() {
        return Boolean.getBoolean(DISABLE_PROPERTY);
    }

    public static void logResolvedModeOnce() {
        if (LOGGED.compareAndSet(false, true)) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][TEST] disableRefinementAdmission={}", disabled());
        }
    }
}
