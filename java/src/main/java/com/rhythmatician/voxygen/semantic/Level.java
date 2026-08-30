package com.rhythmatician.voxygen.semantic;

/**
 * LOD refinement level L0..L4 where L0 is finest.
 *
 * <p>Level controls voxel scale and alignment, not storage layout.
 * Validated volume dimensions; never inferred from extent.
 * Voxy WorldSection (32^3) is a private consolidation detail, never a Level.
 */
public enum Level {
    L0(0),
    L1(1),
    L2(2),
    L3(3),
    L4(4);

    private final int value;

    Level(int value) {
        this.value = value;
    }

    /** Ordinal value 0..4 where 0 = L0 finest. */
    public int value() {
        return value;
    }

    /**
     * Region size in L0 sections (16-block units) for a 32^3 volume at this level.
     * Equals 1 << (value + 1).
     */
    public int regionSections() {
        return 1 << (value + 1);
    }

    /** True if origin is aligned to the region grid at this level. */
    public boolean isAligned(SectionPos origin) {
        int s = regionSections();
        return origin.x() % s == 0 && origin.y() % s == 0 && origin.z() % s == 0;
    }
}
