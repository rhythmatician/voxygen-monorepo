package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;

/**
 * Complete handoff of one parent to its eight children, carrying both facts
 * the renderer needs: which octants have a present child section and which
 * are <em>proved empty</em>.
 *
 * <p>Handoff completeness is independent of child occupancy. A sparse
 * {@code presentMask} is legal whenever every clear bit is explicitly proved
 * empty — including the all-empty case where the coarse parent must be
 * retired rather than kept as a false-positive leaf.
 *
 * @param presentMask bit per octant with an installed non-empty child section
 * @param emptyMask   bit per octant whose finer representation is terminal-empty
 */
record CompleteChildHandoff(byte presentMask, byte emptyMask) {
    private static final int ALL_OCTANTS = 0xFF;

    CompleteChildHandoff {
        Objects.requireNonNull(presentMask, "presentMask");
        Objects.requireNonNull(emptyMask, "emptyMask");
        validate(presentMask & ALL_OCTANTS, emptyMask & ALL_OCTANTS);
    }

    static CompleteChildHandoff ofMasks(int presentMask, int emptyMask) {
        if ((presentMask & ~ALL_OCTANTS) != 0 || (emptyMask & ~ALL_OCTANTS) != 0) {
            throw new IllegalArgumentException(
                    "handoff masks must fit eight octants: present=" + presentMask
                    + " empty=" + emptyMask);
        }
        return new CompleteChildHandoff((byte) presentMask, (byte) emptyMask);
    }

    private static void validate(int presentMask, int emptyMask) {
        if ((presentMask & ~ALL_OCTANTS) != 0 || (emptyMask & ~ALL_OCTANTS) != 0) {
            throw new IllegalArgumentException(
                    "handoff masks must fit eight octants: present=" + presentMask
                    + " empty=" + emptyMask);
        }
        if ((presentMask & emptyMask) != 0) {
            throw new IllegalArgumentException(
                    "an octant cannot be both present and empty: "
                    + (presentMask & emptyMask));
        }
        if ((presentMask | emptyMask) != ALL_OCTANTS) {
            throw new IllegalArgumentException(
                    "handoff is incomplete; unresolved octants: "
                    + (ALL_OCTANTS & ~(presentMask | emptyMask)));
        }
    }

    boolean isComplete() {
        return true;
    }
}
