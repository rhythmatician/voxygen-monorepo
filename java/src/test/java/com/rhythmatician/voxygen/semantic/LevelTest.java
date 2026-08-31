package com.rhythmatician.voxygen.semantic;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Behavior-focused tests for Level L0..L4 write-contract slice.
 * Covers value(), regionSections(), isAligned() with aligned/misaligned origins,
 * zero/positive/negative and boundary values immediately before/after valid multiples.
 */
class LevelTest {

    @Test
    void value_exactL0toL4() {
        assertEquals(0, Level.L0.value());
        assertEquals(1, Level.L1.value());
        assertEquals(2, Level.L2.value());
        assertEquals(3, Level.L3.value());
        assertEquals(4, Level.L4.value());
    }

    @Test
    void regionSections_exactL0toL4() {
        // regionSections = 1 << (value+1)
        assertEquals(2, Level.L0.regionSections());
        assertEquals(4, Level.L1.regionSections());
        assertEquals(8, Level.L2.regionSections());
        assertEquals(16, Level.L3.regionSections());
        assertEquals(32, Level.L4.regionSections());
    }

    @Test
    void isAligned_zeroOrigin_alwaysAligned() {
        SectionPos zero = new SectionPos(0, 0, 0);
        for (Level l : Level.values()) {
            assertTrue(l.isAligned(zero), l + " zero should be aligned");
        }
    }

    @Test
    void isAligned_positiveAlignedOrigins() {
        // L0 region 2: multiples of 2
        assertTrue(Level.L0.isAligned(new SectionPos(2, 0, 0)));
        assertTrue(Level.L0.isAligned(new SectionPos(0, 2, 0)));
        assertTrue(Level.L0.isAligned(new SectionPos(0, 0, 2)));
        assertTrue(Level.L0.isAligned(new SectionPos(2, 4, 6)));
        // L1 region 4
        assertTrue(Level.L1.isAligned(new SectionPos(4, 8, 12)));
        assertTrue(Level.L1.isAligned(new SectionPos(0, 4, 0)));
        // L2 region 8
        assertTrue(Level.L2.isAligned(new SectionPos(8, 16, 24)));
        assertTrue(Level.L2.isAligned(new SectionPos(0, 0, 8)));
        // L3 region 16
        assertTrue(Level.L3.isAligned(new SectionPos(16, 32, 48)));
        // L4 region 32
        assertTrue(Level.L4.isAligned(new SectionPos(32, 64, 96)));
        assertTrue(Level.L4.isAligned(new SectionPos(0, 32, 0)));
    }

    @Test
    void isAligned_positiveMisalignedOrigins() {
        assertFalse(Level.L0.isAligned(new SectionPos(1, 0, 0)));
        assertFalse(Level.L0.isAligned(new SectionPos(0, 1, 0)));
        assertFalse(Level.L0.isAligned(new SectionPos(0, 0, 1)));
        assertFalse(Level.L1.isAligned(new SectionPos(1, 0, 0)));
        assertFalse(Level.L1.isAligned(new SectionPos(2, 0, 0)));
        assertFalse(Level.L1.isAligned(new SectionPos(0, 0, 5)));
        assertFalse(Level.L2.isAligned(new SectionPos(7, 0, 0)));
        assertFalse(Level.L2.isAligned(new SectionPos(0, 9, 0)));
        assertFalse(Level.L3.isAligned(new SectionPos(15, 0, 0)));
        assertFalse(Level.L4.isAligned(new SectionPos(31, 0, 0)));
        assertFalse(Level.L4.isAligned(new SectionPos(33, 0, 0)));
    }

    @Test
    void isAligned_negativeAlignedOrigins() {
        // negative multiples remain aligned: -2 % 2 == 0 etc
        assertTrue(Level.L0.isAligned(new SectionPos(-2, 0, 0)));
        assertTrue(Level.L0.isAligned(new SectionPos(-4, -2, -6)));
        assertTrue(Level.L1.isAligned(new SectionPos(-4, 0, 0)));
        assertTrue(Level.L1.isAligned(new SectionPos(-8, -4, 0)));
        assertTrue(Level.L2.isAligned(new SectionPos(-8, -16, -24)));
        assertTrue(Level.L3.isAligned(new SectionPos(-16, 0, 0)));
        assertTrue(Level.L4.isAligned(new SectionPos(-32, -64, 0)));
    }

    @Test
    void isAligned_negativeMisalignedOrigins() {
        assertFalse(Level.L0.isAligned(new SectionPos(-1, 0, 0)));
        assertFalse(Level.L0.isAligned(new SectionPos(0, -1, 0)));
        assertFalse(Level.L0.isAligned(new SectionPos(0, 0, -1)));
        assertFalse(Level.L1.isAligned(new SectionPos(-1, 0, 0)));
        assertFalse(Level.L1.isAligned(new SectionPos(-2, 0, 0)));
        assertFalse(Level.L1.isAligned(new SectionPos(-3, 0, 0)));
        assertFalse(Level.L2.isAligned(new SectionPos(-7, 0, 0)));
        assertFalse(Level.L2.isAligned(new SectionPos(0, -7, 0)));
        assertFalse(Level.L3.isAligned(new SectionPos(-15, 0, 0)));
        assertFalse(Level.L4.isAligned(new SectionPos(-31, 0, 0)));
    }

    @Test
    void isAligned_singleAxisMisalignmentFailsEvenIfOthersAligned() {
        // L2 region 8: x misaligned should fail even if y,z aligned
        assertFalse(Level.L2.isAligned(new SectionPos(7, 8, 8)));
        assertFalse(Level.L2.isAligned(new SectionPos(8, 7, 8)));
        assertFalse(Level.L2.isAligned(new SectionPos(8, 8, 7)));
        // L1 region 4: one axis off
        assertFalse(Level.L1.isAligned(new SectionPos(5, 4, 4)));
        assertFalse(Level.L1.isAligned(new SectionPos(4, 5, 4)));
        assertFalse(Level.L1.isAligned(new SectionPos(4, 4, 5)));
    }

    @Test
    void isAligned_boundaryImmediatelyBeforeAndAfterValidMultiple() {
        // For each level, test s-1 (just before), s (aligned), s+1 (just after)
        for (Level level : Level.values()) {
            int s = level.regionSections();
            // positive
            assertFalse(level.isAligned(new SectionPos(s - 1, 0, 0)), level + " s-1 should be misaligned");
            assertTrue(level.isAligned(new SectionPos(s, 0, 0)), level + " s should be aligned");
            assertFalse(level.isAligned(new SectionPos(s + 1, 0, 0)), level + " s+1 should be misaligned");
            // double
            assertTrue(level.isAligned(new SectionPos(2 * s, 0, 0)));
            assertFalse(level.isAligned(new SectionPos(2 * s - 1, 0, 0)));
            assertFalse(level.isAligned(new SectionPos(2 * s + 1, 0, 0)));
            // negative
            assertTrue(level.isAligned(new SectionPos(-s, 0, 0)));
            assertFalse(level.isAligned(new SectionPos(-s + 1, 0, 0)), level + " -s+1 should be misaligned");
            assertFalse(level.isAligned(new SectionPos(-s - 1, 0, 0)), level + " -s-1 should be misaligned");
            // also test y/z axis boundary
            assertFalse(level.isAligned(new SectionPos(0, s - 1, 0)));
            assertFalse(level.isAligned(new SectionPos(0, 0, s - 1)));
            assertTrue(level.isAligned(new SectionPos(0, s, 0)));
            assertTrue(level.isAligned(new SectionPos(0, 0, s)));
        }
    }

    @Test
    void isAligned_mixedPositiveNegativeBoundary() {
        // L0 s=2: 1 is misaligned even though -2 is aligned
        assertFalse(Level.L0.isAligned(new SectionPos(1, -2, 2)));
        assertFalse(Level.L0.isAligned(new SectionPos(-1, 2, -2)));
        // L4 s=32: 32 aligned, 31 and 33 not
        assertTrue(Level.L4.isAligned(new SectionPos(32, 32, 32)));
        assertFalse(Level.L4.isAligned(new SectionPos(31, 32, 32)));
        assertFalse(Level.L4.isAligned(new SectionPos(33, 32, 32)));
        assertFalse(Level.L4.isAligned(new SectionPos(32, 31, 32)));
        assertFalse(Level.L4.isAligned(new SectionPos(32, 32, 31)));
    }
}
