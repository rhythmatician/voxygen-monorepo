package com.rhythmatician.lodiffusion.voxy;

/**
 * Centralizes all Minecraft → Octree → Voxy coordinate conversions so that
 * every shift / multiply / divide lives in one tested place.
 *
 * <h3>Coordinate systems</h3>
 * <table>
 *   <tr><th>Name</th><th>Width</th><th>Conversion from block</th></tr>
 *   <tr><td>Block</td><td>1 block</td><td>identity</td></tr>
 *   <tr><td>PlayerSection (= Voxy-L0 section)</td><td>16 blocks</td>
 *       <td>{@code block >> 4}</td></tr>
 *   <tr><td>WorldSection at level L</td><td>{@code 32 × 2^L} blocks</td>
 *       <td>{@code block >> (5 + L)}</td></tr>
 *   <tr><td>Voxy section (16-block)</td><td>16 blocks</td>
 *       <td>{@code L0_ws * 2 + offset}</td></tr>
 * </table>
 *
 * <h3>Key identities</h3>
 * <pre>
 *   playerSection(block)      = block >> 4
 *   worldSection(block, L)    = block >> (5 + L)
 *   worldSection(pSec,  L)    = pSec  >> (L + 1)
 *
 *   // These are equivalent (the two-step path equals the one-step path):
 *   block >> (5 + L)  ≡  (block >> 4) >> (L + 1)
 * </pre>
 *
 * <h3>Octree child expansion</h3>
 * <pre>
 *   childX = (parentX &lt;&lt; 1) + (octant &amp; 1)
 *   childY = (parentY &lt;&lt; 1) + ((octant &gt;&gt; 2) &amp; 1)
 *   childZ = (parentZ &lt;&lt; 1) + ((octant &gt;&gt; 1) &amp; 1)
 *   octant = (lx &amp; 1) | ((lz &amp; 1) &lt;&lt; 1) | ((ly &amp; 1) &lt;&lt; 2)
 * </pre>
 *
 * <p>Thread safety: all methods are pure functions with no shared state.
 */
public final class WorldSectionCoord {

    // ── Constants ─────────────────────────────────────────────────────────

    /** Blocks per PlayerSection / Voxy-level-0 section. */
    public static final int BLOCKS_PER_SECTION = 16;   // 2^4

    /** Blocks per L0 world section. */
    public static final int BLOCKS_PER_L0_WS = 32;     // 2^5

    /** Voxy 16-block sections per L0 world section (per axis). */
    public static final int VOXY_PER_L0 = 2;

    /** Bits to shift a block coordinate to get a player-section coordinate. */
    public static final int SECTION_SHIFT = 4;

    /** Bits to shift a block coordinate to get an L0 world-section coordinate. */
    public static final int L0_SHIFT = 5;

    private WorldSectionCoord() { } // utility class

    // ── Block ↔ PlayerSection (16-block) ──────────────────────────────────

    /**
     * Convert a block coordinate to its containing 16-block player section.
     * Equivalent to {@code Math.floorDiv(block, 16)}.
     */
    public static int blockToSection(int block) {
        return block >> SECTION_SHIFT;
    }

    /** Lowest block coordinate covered by this player section. */
    public static int sectionToBlockMin(int section) {
        return section << SECTION_SHIFT;
    }

    /** Highest block coordinate covered by this player section (inclusive). */
    public static int sectionToBlockMax(int section) {
        return (section << SECTION_SHIFT) + (BLOCKS_PER_SECTION - 1);
    }

    // ── Block ↔ WorldSection (level-aware) ────────────────────────────────

    /**
     * Convert a block coordinate to its containing world section at
     * octree level L.  Level 0 world sections are 32 blocks; each level
     * doubles, so level L covers {@code 32 × 2^L} blocks.
     *
     * @param block block coordinate
     * @param level octree level (0–4)
     * @return world section coordinate at the given level
     */
    public static int blockToWorldSection(int block, int level) {
        return block >> (L0_SHIFT + level);
    }

    /** Lowest block coordinate covered by this world section. */
    public static int worldSectionToBlockMin(int ws, int level) {
        return ws << (L0_SHIFT + level);
    }

    /** Highest block coordinate covered by this world section (inclusive). */
    public static int worldSectionToBlockMax(int ws, int level) {
        return ((ws + 1) << (L0_SHIFT + level)) - 1;
    }

    /** Width in blocks of one world section at the given level. */
    public static int worldSectionWidth(int level) {
        return BLOCKS_PER_L0_WS << level;   // 32 × 2^level
    }

    // ── PlayerSection ↔ WorldSection ──────────────────────────────────────

    /**
     * Convert a player section (16-block) to the containing world section
     * at octree level L.
     *
     * <p>Equivalent to {@code blockToWorldSection(sectionToBlockMin(section), level)},
     * but avoids the intermediate block step.  The shift is {@code level + 1}
     * because {@code (block >> 4) >> (level + 1) = block >> (5 + level)}.
     *
     * @param section player-section coordinate ({@code block >> 4})
     * @param level   octree level (0–4)
     */
    public static int sectionToWorldSection(int section, int level) {
        return section >> (level + 1);
    }

    // ── L0 WorldSection ↔ Voxy Section (16-block native sections) ─────────

    /**
     * Convert an L0 world-section coordinate to the <em>lower</em> of
     * the two Voxy 16-block sections it contains.
     */
    public static int l0ToVoxySectionMin(int l0ws) {
        return l0ws << 1;       // l0ws * 2
    }

    /**
     * Convert an L0 world-section coordinate to the <em>upper</em> of
     * the two Voxy 16-block sections it contains.
     */
    public static int l0ToVoxySectionMax(int l0ws) {
        return (l0ws << 1) + 1; // l0ws * 2 + 1
    }

    /**
     * Compute the Voxy section coordinate for a specific sub-section
     * within an L0 world section.
     *
     * @param l0ws   L0 world-section coordinate
     * @param offset sub-section offset within the L0 cell (0 or 1)
     * @return Voxy 16-block section coordinate
     */
    public static int l0ToVoxySection(int l0ws, int offset) {
        return (l0ws << 1) + offset;
    }

    // ── Voxy Section ↔ Block ──────────────────────────────────────────────

    /** Lowest block covered by this Voxy 16-block section. */
    public static int voxySectionToBlockMin(int voxySection) {
        return voxySection << SECTION_SHIFT;
    }

    /** Highest block covered by this Voxy 16-block section (inclusive). */
    public static int voxySectionToBlockMax(int voxySection) {
        return (voxySection << SECTION_SHIFT) + (BLOCKS_PER_SECTION - 1);
    }

    // ── Octree child expansion ────────────────────────────────────────────

    /** Child world-section X from parent X and octant (bit 0 = X). */
    public static int childX(int parentX, int octant) {
        return (parentX << 1) + (octant & 1);
    }

    /** Child world-section Y from parent Y and octant (bit 2 = Y). */
    public static int childY(int parentY, int octant) {
        return (parentY << 1) + ((octant >> 2) & 1);
    }

    /** Child world-section Z from parent Z and octant (bit 1 = Z). */
    public static int childZ(int parentZ, int octant) {
        return (parentZ << 1) + ((octant >> 1) & 1);
    }

    /**
     * Compute octant index from local offsets within a parent cell.
     *
     * @param lx local X offset (0 or 1)
     * @param ly local Y offset (0 or 1)
     * @param lz local Z offset (0 or 1)
     * @return octant 0–7
     */
    public static int octantIndex(int lx, int ly, int lz) {
        return (lx & 1) | ((lz & 1) << 1) | ((ly & 1) << 2);
    }

    // ── Containment checks ────────────────────────────────────────────────

    /**
     * Does the world section at level L contain the given block coordinate?
     */
    public static boolean worldSectionContains(int ws, int level, int block) {
        return block >= worldSectionToBlockMin(ws, level)
            && block <= worldSectionToBlockMax(ws, level);
    }

    /**
     * Does the Voxy 16-block section contain the given block coordinate?
     */
    public static boolean voxySectionContains(int voxySection, int block) {
        return block >= voxySectionToBlockMin(voxySection)
            && block <= voxySectionToBlockMax(voxySection);
    }

    // ── Diagnostic / human-readable helpers ───────────────────────────────

    /**
     * Describe a world section at level L in human-readable form.
     *
     * @return e.g. "L4 ws=-1 blocks=[-512, -1] (512 wide)"
     */
    public static String describe(int ws, int level) {
        return String.format("L%d ws=%d blocks=[%d, %d] (%d wide)",
                level, ws,
                worldSectionToBlockMin(ws, level),
                worldSectionToBlockMax(ws, level),
                worldSectionWidth(level));
    }

    /**
     * Trace a block position through the full L4 → L0 → Voxy hierarchy.
     * Useful for debugging coordinate mismatches.
     *
     * @return multi-line string showing all intermediate coordinates
     */
    public static String traceBlock(int blockX, int blockY, int blockZ) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Block (%d, %d, %d)%n", blockX, blockY, blockZ));
        sb.append(String.format("  Section: (%d, %d, %d)%n",
                blockToSection(blockX), blockToSection(blockY), blockToSection(blockZ)));
        for (int level = 4; level >= 0; level--) {
            int wsX = blockToWorldSection(blockX, level);
            int wsY = blockToWorldSection(blockY, level);
            int wsZ = blockToWorldSection(blockZ, level);
            sb.append(String.format("  L%d: (%d, %d, %d)  X[%d..%d]  Y[%d..%d]  Z[%d..%d]%n",
                    level, wsX, wsY, wsZ,
                    worldSectionToBlockMin(wsX, level), worldSectionToBlockMax(wsX, level),
                    worldSectionToBlockMin(wsY, level), worldSectionToBlockMax(wsY, level),
                    worldSectionToBlockMin(wsZ, level), worldSectionToBlockMax(wsZ, level)));
        }
        int l0X = blockToWorldSection(blockX, 0);
        int l0Y = blockToWorldSection(blockY, 0);
        int l0Z = blockToWorldSection(blockZ, 0);
        sb.append(String.format("  Voxy: X{%d,%d}  Y{%d,%d}  Z{%d,%d}",
                l0ToVoxySectionMin(l0X), l0ToVoxySectionMax(l0X),
                l0ToVoxySectionMin(l0Y), l0ToVoxySectionMax(l0Y),
                l0ToVoxySectionMin(l0Z), l0ToVoxySectionMax(l0Z)));
        return sb.toString();
    }

    /**
     * Trace the child-expansion chain from a given octree WorldSection
     * coordinate down to L0, following octant 0 (lower-corner child) at
     * each step.  Useful for verifying what block ranges the "worst case"
     * child tree covers.
     *
     * @param ws    world section coordinate (same for all axes in this trace)
     * @param level starting level
     * @return multi-line string of the expansion chain
     */
    public static String traceChildChain(int wsX, int wsY, int wsZ, int level) {
        StringBuilder sb = new StringBuilder();
        int x = wsX, y = wsY, z = wsZ;
        for (int lvl = level; lvl >= 0; lvl--) {
            sb.append(String.format("  L%d (%d, %d, %d)  X[%d..%d]  Y[%d..%d]  Z[%d..%d]%n",
                    lvl, x, y, z,
                    worldSectionToBlockMin(x, lvl), worldSectionToBlockMax(x, lvl),
                    worldSectionToBlockMin(y, lvl), worldSectionToBlockMax(y, lvl),
                    worldSectionToBlockMin(z, lvl), worldSectionToBlockMax(z, lvl)));
            if (lvl > 0) {
                // Follow octant 0 (lower corner of each axis)
                x = childX(x, 0);
                y = childY(y, 0);
                z = childZ(z, 0);
            }
        }
        return sb.toString();
    }
}
