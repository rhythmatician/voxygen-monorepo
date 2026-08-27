package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/**
 * A complete handoff is a statement about <em>topology knowledge</em>, not
 * child occupancy: every octant must be terminal (present or proved empty),
 * and the two facts are disjoint. Sparse present masks are legal whenever
 * every clear bit is explicitly proved empty.
 */
class CompleteChildHandoffTest {

    @Test
    void allEightPresentIsCompleteAndFullyOccupied() {
        CompleteChildHandoff handoff = new CompleteChildHandoff((byte) 0xFF, (byte) 0);

        assertEquals((byte) 0xFF, handoff.presentMask());
        assertEquals((byte) 0, handoff.emptyMask());
        assertTrue(handoff.isComplete());
    }

    @Test
    void sparsePresentMaskWithAllClearBitsProvedEmptyIsComplete() {
        // SOLID EMPTY SOLID EMPTY / SOLID SOLID EMPTY SOLID
        CompleteChildHandoff handoff = new CompleteChildHandoff((byte) 0b1001_0101, (byte) 0b0110_1010);

        assertTrue(handoff.isComplete());
        assertEquals((byte) 0b1001_0101, handoff.presentMask());
        assertEquals((byte) 0b0110_1010, handoff.emptyMask());
    }

    @Test
    void allEmptyHandoffIsCompleteWithNoPresentChildren() {
        CompleteChildHandoff handoff = new CompleteChildHandoff((byte) 0, (byte) 0xFF);

        assertTrue(handoff.isComplete());
        assertEquals((byte) 0, handoff.presentMask());
        assertEquals((byte) 0xFF, handoff.emptyMask());
    }

    @Test
    void overlappingMasksAreUnrepresentable() {
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteChildHandoff((byte) 0b0000_0011, (byte) 0b0000_0001));
    }

    @Test
    void unresolvedOctantIsNotACompleteHandoff() {
        // Octant 7 is neither present nor empty.
        assertThrows(IllegalArgumentException.class,
                () -> new CompleteChildHandoff((byte) 0b0111_1111, (byte) 0));
    }

    @Test
    void masksBeyondEightOctantsAreRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> CompleteChildHandoff.ofMasks(0x1FF, 0));
    }
}
