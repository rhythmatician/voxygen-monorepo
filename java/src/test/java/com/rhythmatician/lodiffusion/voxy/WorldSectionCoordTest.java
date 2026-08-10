package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Exhaustive tests for {@link WorldSectionCoord}.
 *
 * <p>Validates every coordinate transformation, containment check, and
 * the critical <em>consistency identity</em>:
 * <pre>
 *   blockToWorldSection(b, L) == sectionToWorldSection(blockToSection(b), L)
 * </pre>
 *
 * <p>Also does full end-to-end traces verifying that the octree L4→L0→Voxy
 * chain always contains the player's block coordinate at every level.
 */
class WorldSectionCoordTest {

    // ══════════════════════════════════════════════════════════════════════
    //  Block ↔ PlayerSection
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "block {0} → section {1}")
    @CsvSource({
            // Positive
            "  0,  0",
            "  1,  0",
            " 15,  0",
            " 16,  1",
            " 17,  1",
            " 31,  1",
            " 32,  2",
            "255, 15",
            "256, 16",
            // Negative (Java arithmetic right-shift floors toward −∞)
            " -1, -1",
            " -7, -1",
            "-15, -1",
            "-16, -1",  // -16 >> 4 = -1  (exactly on boundary)
            "-17, -2",
            "-32, -2",
            "-33, -3",
    })
    void blockToSection_basic(int block, int expectedSection) {
        assertEquals(expectedSection, WorldSectionCoord.blockToSection(block));
    }

    @Test
    void sectionToBlockRange_roundTrips() {
        for (int sec = -10; sec <= 10; sec++) {
            int min = WorldSectionCoord.sectionToBlockMin(sec);
            int max = WorldSectionCoord.sectionToBlockMax(sec);
            assertEquals(16, max - min + 1, "section " + sec + " should span 16 blocks");
            assertEquals(sec, WorldSectionCoord.blockToSection(min),
                    "min block should map back to same section");
            assertEquals(sec, WorldSectionCoord.blockToSection(max),
                    "max block should map back to same section");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Block ↔ WorldSection (level-aware)
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "block {0} at L{1} → ws {2}")
    @CsvSource({
            // L0: 32 blocks per ws
            "   0, 0,  0",
            "  31, 0,  0",
            "  32, 0,  1",
            "  -1, 0, -1",
            " -32, 0, -1",
            " -33, 0, -2",
            // L1: 64 blocks per ws
            "   0, 1,  0",
            "  63, 1,  0",
            "  64, 1,  1",
            "  -1, 1, -1",
            " -64, 1, -1",
            " -65, 1, -2",
            // L2: 128 blocks per ws
            " 127, 2,  0",
            " 128, 2,  1",
            "  -1, 2, -1",
            "-128, 2, -1",
            "-129, 2, -2",
            // L3: 256 blocks per ws
            " 255, 3,  0",
            " 256, 3,  1",
            "  -1, 3, -1",
            "-256, 3, -1",
            "-257, 3, -2",
            // L4: 512 blocks per ws
            " 511, 4,  0",
            " 512, 4,  1",
            "  -1, 4, -1",
            "-512, 4, -1",
            "-513, 4, -2",
    })
    void blockToWorldSection_basic(int block, int level, int expectedWs) {
        assertEquals(expectedWs, WorldSectionCoord.blockToWorldSection(block, level));
    }

    @ParameterizedTest(name = "L{0} ws width")
    @CsvSource({ "0, 32", "1, 64", "2, 128", "3, 256", "4, 512" })
    void worldSectionWidth_powersOfTwo(int level, int expectedWidth) {
        assertEquals(expectedWidth, WorldSectionCoord.worldSectionWidth(level));
    }

    @Test
    void worldSectionToBlockRange_roundTrips() {
        for (int level = 0; level <= 4; level++) {
            int width = WorldSectionCoord.worldSectionWidth(level);
            for (int ws = -5; ws <= 5; ws++) {
                int min = WorldSectionCoord.worldSectionToBlockMin(ws, level);
                int max = WorldSectionCoord.worldSectionToBlockMax(ws, level);
                assertEquals(width, max - min + 1,
                        "L" + level + " ws=" + ws + " should span " + width + " blocks");
                assertEquals(ws, WorldSectionCoord.blockToWorldSection(min, level),
                        "min block should map back for L" + level + " ws=" + ws);
                assertEquals(ws, WorldSectionCoord.blockToWorldSection(max, level),
                        "max block should map back for L" + level + " ws=" + ws);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  PlayerSection ↔ WorldSection (the critical two-step path)
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "section {0} at L{1} → ws {2}")
    @CsvSource({
            // L0: section >> 1
            "  0, 0,  0",
            "  1, 0,  0",
            " -1, 0, -1",
            " -2, 0, -1",
            "  6, 0,  3",  // player at block ~100
            // L4: section >> 5
            "  0, 4,  0",
            "  6, 4,  0",
            " 31, 4,  0",
            " 32, 4,  1",
            " -1, 4, -1",
            "-32, 4, -1",
            "-33, 4, -2",
    })
    void sectionToWorldSection_basic(int section, int level, int expectedWs) {
        assertEquals(expectedWs, WorldSectionCoord.sectionToWorldSection(section, level));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ CONSISTENCY IDENTITY ★
    //  blockToWorldSection(b, L) == sectionToWorldSection(blockToSection(b), L)
    //
    //  This is the single most important property: the one-step conversion
    //  must agree with the two-step conversion for ALL inputs.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void consistency_oneStepEqualsTwoStep_sweepBlocks() {
        // Sweep a range of block coords and all levels
        for (int block = -600; block <= 600; block++) {
            for (int level = 0; level <= 4; level++) {
                int direct   = WorldSectionCoord.blockToWorldSection(block, level);
                int twoStep  = WorldSectionCoord.sectionToWorldSection(
                        WorldSectionCoord.blockToSection(block), level);
                assertEquals(direct, twoStep,
                        "Consistency failed for block=" + block + " level=" + level);
            }
        }
    }

    @ParameterizedTest(name = "block {0}")
    @ValueSource(ints = {
            0, 1, -1, 15, 16, -16, -17, 31, 32, -32, -33,
            100, -100, 255, 256, -256, -257, 511, 512, -512, -513,
            1023, 1024, -1024, -1025,
            Integer.MAX_VALUE / 2, Integer.MIN_VALUE / 2 + 1
    })
    void consistency_oneStepEqualsTwoStep_selectedBlocks(int block) {
        for (int level = 0; level <= 4; level++) {
            int direct  = WorldSectionCoord.blockToWorldSection(block, level);
            int twoStep = WorldSectionCoord.sectionToWorldSection(
                    WorldSectionCoord.blockToSection(block), level);
            assertEquals(direct, twoStep,
                    "Consistency failed for block=" + block + " level=" + level);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  L0 WorldSection ↔ Voxy Section
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "L0 ws {0} → voxy [{1}, {2}]")
    @CsvSource({
            "  0,  0,  1",
            "  1,  2,  3",
            " -1, -2, -1",
            " -2, -4, -3",
            "  5, 10, 11",
    })
    void l0ToVoxySection_range(int l0ws, int expectedMin, int expectedMax) {
        assertEquals(expectedMin, WorldSectionCoord.l0ToVoxySectionMin(l0ws));
        assertEquals(expectedMax, WorldSectionCoord.l0ToVoxySectionMax(l0ws));
        assertEquals(expectedMin, WorldSectionCoord.l0ToVoxySection(l0ws, 0));
        assertEquals(expectedMax, WorldSectionCoord.l0ToVoxySection(l0ws, 1));
    }

    @Test
    void voxySectionToBlockRange_matchesSection() {
        // Voxy sections are 16 blocks, same as player sections
        for (int vs = -10; vs <= 10; vs++) {
            assertEquals(WorldSectionCoord.sectionToBlockMin(vs),
                    WorldSectionCoord.voxySectionToBlockMin(vs));
            assertEquals(WorldSectionCoord.sectionToBlockMax(vs),
                    WorldSectionCoord.voxySectionToBlockMax(vs));
        }
    }

    @Test
    void l0_voxy_block_chain_roundTrip() {
        // For each L0 world section, its two Voxy sub-sections should
        // together cover exactly the same block range as the L0 ws.
        for (int l0ws = -10; l0ws <= 10; l0ws++) {
            int l0Min = WorldSectionCoord.worldSectionToBlockMin(l0ws, 0);
            int l0Max = WorldSectionCoord.worldSectionToBlockMax(l0ws, 0);

            int vMin = WorldSectionCoord.l0ToVoxySectionMin(l0ws);
            int vMax = WorldSectionCoord.l0ToVoxySectionMax(l0ws);

            assertEquals(l0Min, WorldSectionCoord.voxySectionToBlockMin(vMin),
                    "L0 ws " + l0ws + " min mismatch");
            assertEquals(l0Max, WorldSectionCoord.voxySectionToBlockMax(vMax),
                    "L0 ws " + l0ws + " max mismatch");
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Octree child expansion
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void childExpansion_octant0_doublesAndAddsNothing() {
        assertEquals(-2, WorldSectionCoord.childX(-1, 0)); // -1*2 + 0
        assertEquals( 0, WorldSectionCoord.childY( 0, 0)); //  0*2 + 0
        assertEquals( 4, WorldSectionCoord.childZ( 2, 0)); //  2*2 + 0
    }

    @Test
    void childExpansion_octant7_doublesAndAddsOne() {
        // octant 7 = 0b111 → X bit0=1, Z bit1=1, Y bit2=1
        assertEquals(-1, WorldSectionCoord.childX(-1, 7)); // -1*2 + 1
        assertEquals( 1, WorldSectionCoord.childY( 0, 7)); //  0*2 + 1
        assertEquals( 5, WorldSectionCoord.childZ( 2, 7)); //  2*2 + 1
    }

    @Test
    void octantIndex_roundTrips() {
        for (int lx = 0; lx <= 1; lx++) {
            for (int ly = 0; ly <= 1; ly++) {
                for (int lz = 0; lz <= 1; lz++) {
                    int oct = WorldSectionCoord.octantIndex(lx, ly, lz);
                    assertTrue(oct >= 0 && oct <= 7);
                    // Verify bit extraction matches
                    assertEquals(lx, oct & 1,       "X bit for (" + lx + "," + ly + "," + lz + ")");
                    assertEquals(ly, (oct >> 2) & 1, "Y bit for (" + lx + "," + ly + "," + lz + ")");
                    assertEquals(lz, (oct >> 1) & 1, "Z bit for (" + lx + "," + ly + "," + lz + ")");
                }
            }
        }
    }

    @Test
    void childExpansion_coversParentRange() {
        // 8 children of a parent should cover exactly the parent's block range
        for (int level = 4; level >= 1; level--) {
            int childLevel = level - 1;
            for (int parentWs = -3; parentWs <= 3; parentWs++) {
                int parentMin = WorldSectionCoord.worldSectionToBlockMin(parentWs, level);
                int parentMax = WorldSectionCoord.worldSectionToBlockMax(parentWs, level);
                int parentWidth = parentMax - parentMin + 1;

                // Collect all child block ranges for X axis
                int childWidth = WorldSectionCoord.worldSectionWidth(childLevel);
                int child0 = WorldSectionCoord.childX(parentWs, 0);
                int child1 = WorldSectionCoord.childX(parentWs, 1);

                int c0Min = WorldSectionCoord.worldSectionToBlockMin(child0, childLevel);
                int c1Min = WorldSectionCoord.worldSectionToBlockMin(child1, childLevel);
                int c1Max = WorldSectionCoord.worldSectionToBlockMax(child1, childLevel);

                assertEquals(parentMin, c0Min,
                        "child0 X min should equal parent X min at L" + level);
                assertEquals(parentMax, c1Max,
                        "child1 X max should equal parent X max at L" + level);
                assertEquals(parentWidth, 2 * childWidth,
                        "two children should cover parent width at L" + level);
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Containment checks
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void worldSectionContains_boundaries() {
        // L4 ws -1 covers [-512, -1]
        assertTrue(WorldSectionCoord.worldSectionContains(-1, 4, -512));
        assertTrue(WorldSectionCoord.worldSectionContains(-1, 4, -1));
        assertTrue(WorldSectionCoord.worldSectionContains(-1, 4, -256));
        assertFalse(WorldSectionCoord.worldSectionContains(-1, 4, 0));
        assertFalse(WorldSectionCoord.worldSectionContains(-1, 4, -513));

        // L0 ws 0 covers [0, 31]
        assertTrue(WorldSectionCoord.worldSectionContains(0, 0, 0));
        assertTrue(WorldSectionCoord.worldSectionContains(0, 0, 31));
        assertFalse(WorldSectionCoord.worldSectionContains(0, 0, 32));
        assertFalse(WorldSectionCoord.worldSectionContains(0, 0, -1));
    }

    @Test
    void voxySectionContains_boundaries() {
        // Voxy section -1 covers [-16, -1]
        assertTrue(WorldSectionCoord.voxySectionContains(-1, -16));
        assertTrue(WorldSectionCoord.voxySectionContains(-1, -1));
        assertFalse(WorldSectionCoord.voxySectionContains(-1, 0));
        assertFalse(WorldSectionCoord.voxySectionContains(-1, -17));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ END-TO-END: Does the octree chain CONTAIN the player? ★
    //
    //  For a player at block (bx, by, bz), the L4 root world section
    //  must contain the player, AND following the closest child at each
    //  level must also contain the player, all the way to L0 and Voxy.
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "player at ({0}, {1}, {2})")
    @CsvSource({
            "  -8,  70,  25",   // actual player position from logs
            "   0,  70,   0",   // origin
            "   1,  64,   1",   // near origin, positive
            " -16,  64,  16",   // section boundary
            " -17,  64,  17",   // just past boundary
            " 511,  70, 511",   // edge of L4 ws=0
            " 512,  70, 512",   // L4 ws=1
            "-512,  70,-512",   // edge of L4 ws=-1
            "-513,  70,-513",   // L4 ws=-2
            " 100, 200, 300",   // somewhere positive
            "-100, -50,-200",   // deep negative
    })
    void endToEnd_playerContainedAtEveryLevel(int bx, int by, int bz) {
        // 1. Verify the player is in the correct world section at each level
        for (int level = 4; level >= 0; level--) {
            int wsX = WorldSectionCoord.blockToWorldSection(bx, level);
            int wsY = WorldSectionCoord.blockToWorldSection(by, level);
            int wsZ = WorldSectionCoord.blockToWorldSection(bz, level);
            assertTrue(WorldSectionCoord.worldSectionContains(wsX, level, bx),
                    "X not in L" + level + " ws=" + wsX);
            assertTrue(WorldSectionCoord.worldSectionContains(wsY, level, by),
                    "Y not in L" + level + " ws=" + wsY);
            assertTrue(WorldSectionCoord.worldSectionContains(wsZ, level, bz),
                    "Z not in L" + level + " ws=" + wsZ);
        }

        // 2. Walk L4→L0 via child expansion — the correct octant at each
        //    level must still contain the player
        int wsX = WorldSectionCoord.blockToWorldSection(bx, 4);
        int wsY = WorldSectionCoord.blockToWorldSection(by, 4);
        int wsZ = WorldSectionCoord.blockToWorldSection(bz, 4);

        for (int level = 4; level > 0; level--) {
            int childLevel = level - 1;
            // Find the octant whose child contains the player
            boolean found = false;
            for (int oct = 0; oct < 8; oct++) {
                int cx = WorldSectionCoord.childX(wsX, oct);
                int cy = WorldSectionCoord.childY(wsY, oct);
                int cz = WorldSectionCoord.childZ(wsZ, oct);
                if (WorldSectionCoord.worldSectionContains(cx, childLevel, bx)
                 && WorldSectionCoord.worldSectionContains(cy, childLevel, by)
                 && WorldSectionCoord.worldSectionContains(cz, childLevel, bz)) {
                    wsX = cx;
                    wsY = cy;
                    wsZ = cz;
                    found = true;
                    break;
                }
            }
            assertTrue(found, "No child at L" + childLevel
                    + " contains player block (" + bx + ", " + by + ", " + bz + ")");
        }

        // 3. At L0 now — verify Voxy sub-sections contain the player
        boolean foundVoxy = false;
        for (int ox = 0; ox < 2; ox++) {
            for (int oy = 0; oy < 2; oy++) {
                for (int oz = 0; oz < 2; oz++) {
                    int vx = WorldSectionCoord.l0ToVoxySection(wsX, ox);
                    int vy = WorldSectionCoord.l0ToVoxySection(wsY, oy);
                    int vz = WorldSectionCoord.l0ToVoxySection(wsZ, oz);
                    if (WorldSectionCoord.voxySectionContains(vx, bx)
                     && WorldSectionCoord.voxySectionContains(vy, by)
                     && WorldSectionCoord.voxySectionContains(vz, bz)) {
                        foundVoxy = true;
                    }
                }
            }
        }
        assertTrue(foundVoxy, "No Voxy sub-section of L0 ws ("
                + wsX + "," + wsY + "," + wsZ + ") contains player block ("
                + bx + ", " + by + ", " + bz + ")");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ PRIORITY CONSISTENCY: sectionToWorldSection for priority must
    //    match blockToWorldSection for the same player position ★
    //
    //  This tests the exact same path used in updatePriority() and
    //  spawnChildren() and root population: playerSectionX >> (level+1).
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "player block {0}, level {1}")
    @CsvSource({
            "  -8, 4",   "  -8, 3",   "  -8, 2",   "  -8, 1",   "  -8, 0",
            "  25, 4",   "  25, 3",   "  25, 2",   "  25, 1",   "  25, 0",
            "   0, 4",   "   0, 0",
            " -16, 4",   " -17, 4",
            " 512, 4",   "-512, 4",   "-513, 4",
            " 100, 2",   "-100, 2",
    })
    void priorityScaling_sectionToWorldSection_matchesDirect(int playerBlock, int level) {
        int playerSection = WorldSectionCoord.blockToSection(playerBlock);
        int viaTwoStep    = WorldSectionCoord.sectionToWorldSection(playerSection, level);
        int viaDirect     = WorldSectionCoord.blockToWorldSection(playerBlock, level);
        assertEquals(viaDirect, viaTwoStep,
                "Priority scaling mismatch for block=" + playerBlock + " level=" + level
                + ": direct=" + viaDirect + " twoStep=" + viaTwoStep);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ ROOT POPULATION: verify L4 center contains the player ★
    //
    //  Reproduces the exact computation from LodGenerationService root
    //  population: l4Cx = playerSectionX >> 5
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "player block X={0}, Z={1}")
    @CsvSource({
            "  -8,  25",   // actual player position
            "   0,   0",
            "   1,   1",
            "-512,-512",
            " 511, 511",
            " 512, 512",
            "-513,-513",
            " 100, 300",
            "-100,-200",
    })
    void rootL4_containsPlayer(int blockX, int blockZ) {
        int playerSectionX = WorldSectionCoord.blockToSection(blockX);
        int playerSectionZ = WorldSectionCoord.blockToSection(blockZ);

        // Root population: l4Cx = playerSectionX >> 5
        int l4Cx = WorldSectionCoord.sectionToWorldSection(playerSectionX, 4);
        int l4Cz = WorldSectionCoord.sectionToWorldSection(playerSectionZ, 4);

        assertTrue(WorldSectionCoord.worldSectionContains(l4Cx, 4, blockX),
                "L4 center X=" + l4Cx + " does not contain player block X=" + blockX);
        assertTrue(WorldSectionCoord.worldSectionContains(l4Cz, 4, blockZ),
                "L4 center Z=" + l4Cz + " does not contain player block Z=" + blockZ);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ PRIORITY = 0 FOR CENTER ROOT ★
    //
    //  The center L4 root (dx=0, dz=0) must have the player's ws coords.
    //  Its Manhattan distance from the player (at L4 scale) must be 0.
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "player block X={0}, Z={1}")
    @CsvSource({
            "  -8,  25",
            "   0,   0",
            " 511, 511",
            "-512,-512",
            " 100, 300",
    })
    void centerRoot_hasPriorityZero(int blockX, int blockZ) {
        int psx = WorldSectionCoord.blockToSection(blockX);
        int psz = WorldSectionCoord.blockToSection(blockZ);
        int l4Cx = WorldSectionCoord.sectionToWorldSection(psx, 4);
        int l4Cz = WorldSectionCoord.sectionToWorldSection(psz, 4);

        // This is what updatePriority does: player section >> (level+1)
        int playerAtL4_X = WorldSectionCoord.sectionToWorldSection(psx, 4);
        int playerAtL4_Z = WorldSectionCoord.sectionToWorldSection(psz, 4);

        // Center root (dx=0, dz=0)
        int priority = Math.abs(l4Cx - playerAtL4_X) + Math.abs(l4Cz - playerAtL4_Z);
        assertEquals(0, priority, "Center L4 root should have priority 0");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ CHILD PRIORITY ZERO: at every level the child containing the
    //    player should have priority 0 (assuming no surface penalty) ★
    // ══════════════════════════════════════════════════════════════════════

    @ParameterizedTest(name = "player at ({0}, {1}, {2})")
    @CsvSource({
            "  -8,  70,  25",
            "   0,  64,   0",
            " 100, 200, 300",
            "-100, -50,-200",
            " 512,  64, 512",
    })
    void childContainingPlayer_hasPriorityZero(int bx, int by, int bz) {
        int psx = WorldSectionCoord.blockToSection(bx);
        int psz = WorldSectionCoord.blockToSection(bz);

        for (int level = 4; level >= 1; level--) {
            int childLevel = level - 1;
            int parentX = WorldSectionCoord.blockToWorldSection(bx, level);
            int parentZ = WorldSectionCoord.blockToWorldSection(bz, level);

            // Find the child that contains the player
            for (int oct = 0; oct < 8; oct++) {
                int cx = WorldSectionCoord.childX(parentX, oct);
                int cz = WorldSectionCoord.childZ(parentZ, oct);
                if (WorldSectionCoord.worldSectionContains(cx, childLevel, bx)
                 && WorldSectionCoord.worldSectionContains(cz, childLevel, bz)) {
                    // Compute priority the way spawnChildren does
                    int playerAtLevel_X = WorldSectionCoord.sectionToWorldSection(psx, childLevel);
                    int playerAtLevel_Z = WorldSectionCoord.sectionToWorldSection(psz, childLevel);
                    int priority = Math.abs(cx - playerAtLevel_X)
                                 + Math.abs(cz - playerAtLevel_Z);
                    assertEquals(0, priority,
                            "Child containing player at L" + childLevel
                            + " should have priority 0, but cx=" + cx
                            + " playerAtLevel=" + playerAtLevel_X
                            + " cz=" + cz + " playerAtLevelZ=" + playerAtLevel_Z);
                    break;
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ FULL PIPELINE TRACE: L4(-1,0,0) for player at (-8,70,25) ★
    //  Reproduces the exact chain the pipeline should follow
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void fullTrace_playerNearOriginNegativeX() {
        int bx = -8, by = 70, bz = 25;

        // Player section
        assertEquals(-1, WorldSectionCoord.blockToSection(bx));
        assertEquals( 4, WorldSectionCoord.blockToSection(by));
        assertEquals( 1, WorldSectionCoord.blockToSection(bz));

        // L4 center
        int l4X = WorldSectionCoord.blockToWorldSection(bx, 4); // -8 >> 9 = -1
        int l4Y = WorldSectionCoord.blockToWorldSection(by, 4); //  70 >> 9 = 0
        int l4Z = WorldSectionCoord.blockToWorldSection(bz, 4); //  25 >> 9 = 0
        assertEquals(-1, l4X);
        assertEquals( 0, l4Y);
        assertEquals( 0, l4Z);
        assertTrue(WorldSectionCoord.worldSectionContains(l4X, 4, bx));
        assertTrue(WorldSectionCoord.worldSectionContains(l4Y, 4, by));
        assertTrue(WorldSectionCoord.worldSectionContains(l4Z, 4, bz));

        // L4 → L3: find the octant containing the player
        int l3X = WorldSectionCoord.blockToWorldSection(bx, 3); // -8 >> 8 = -1
        int l3Y = WorldSectionCoord.blockToWorldSection(by, 3); //  70 >> 8 = 0
        int l3Z = WorldSectionCoord.blockToWorldSection(bz, 3); //  25 >> 8 = 0
        assertEquals(-1, l3X);
        assertEquals( 0, l3Y);
        assertEquals( 0, l3Z);

        // L3 → L2
        int l2X = WorldSectionCoord.blockToWorldSection(bx, 2); // -8 >> 7 = -1
        int l2Y = WorldSectionCoord.blockToWorldSection(by, 2); //  70 >> 7 = 0
        int l2Z = WorldSectionCoord.blockToWorldSection(bz, 2); //  25 >> 7 = 0
        assertEquals(-1, l2X);
        assertEquals( 0, l2Y);
        assertEquals( 0, l2Z);

        // L2 → L1
        int l1X = WorldSectionCoord.blockToWorldSection(bx, 1); // -8 >> 6 = -1
        int l1Y = WorldSectionCoord.blockToWorldSection(by, 1); //  70 >> 6 = 1
        int l1Z = WorldSectionCoord.blockToWorldSection(bz, 1); //  25 >> 6 = 0
        assertEquals(-1, l1X);
        assertEquals( 1, l1Y);
        assertEquals( 0, l1Z);

        // L1 → L0
        int l0X = WorldSectionCoord.blockToWorldSection(bx, 0); // -8 >> 5 = -1
        int l0Y = WorldSectionCoord.blockToWorldSection(by, 0); //  70 >> 5 = 2
        int l0Z = WorldSectionCoord.blockToWorldSection(bz, 0); //  25 >> 5 = 0
        assertEquals(-1, l0X);
        assertEquals( 2, l0Y);
        assertEquals( 0, l0Z);

        // L0 → Voxy
        // L0 X=-1 → Voxy X: {-2, -1}. Block -8 is in Voxy section -1 ([-16, -1]).
        // L0 Y=2  → Voxy Y: {4, 5}. Block 70 is in Voxy section 4 ([64, 79]).
        // L0 Z=0  → Voxy Z: {0, 1}. Block 25 is in Voxy section 1 ([16, 31]).
        assertEquals(-2, WorldSectionCoord.l0ToVoxySectionMin(l0X));
        assertEquals(-1, WorldSectionCoord.l0ToVoxySectionMax(l0X));
        assertTrue(WorldSectionCoord.voxySectionContains(-1, bx));
        assertTrue(WorldSectionCoord.voxySectionContains(4, by));
        assertTrue(WorldSectionCoord.voxySectionContains(1, bz));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  ★ BUG CHECK: first Voxy writes at (-64,-64,-32) are WRONG ★
    //
    //  Verify that (-64,-64,-32) Voxy section is ~1km from the player.
    //  This is evidence of the bug the sort fix was meant to address.
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void bugEvidence_firstWriteIsFarFromPlayer() {
        int bx = -8, bz = 25;
        // Voxy section (-64, ?, -32) covers blocks [-1024, -1009] and [-512, -497]
        int voxySectionX = -64;
        int voxyBlockMin = WorldSectionCoord.voxySectionToBlockMin(voxySectionX);
        assertEquals(-1024, voxyBlockMin);

        // Distance from player to nearest block in that section
        int dist = Math.abs(bx - voxyBlockMin);
        assertTrue(dist > 1000, "First Voxy write at X=-64 is " + dist
                + " blocks from player — confirms distant-generation bug");
    }

    @Test
    void correctFirstWrite_shouldBeNearPlayer() {
        int bx = -8, by = 70, bz = 25;
        // Expected first L0: (-1, 2, 0) → Voxy (-2,4,0)/(-1,4,0)/(-2,5,0)/...
        // Voxy section -1 covers [-16, -1]. Player at -8 is there.
        int expectedVoxyX = -1;
        assertTrue(WorldSectionCoord.voxySectionContains(expectedVoxyX, bx));

        // Distance from player
        int closestBlock = WorldSectionCoord.voxySectionToBlockMin(expectedVoxyX);
        int dist = Math.abs(bx - closestBlock);
        assertTrue(dist < 16, "Correct first Voxy write should be <16 blocks from player");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Diagnostic / describe / traceBlock
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void describe_format() {
        String desc = WorldSectionCoord.describe(-1, 4);
        assertTrue(desc.contains("L4"));
        assertTrue(desc.contains("-512"));
        assertTrue(desc.contains("-1"));
        assertTrue(desc.contains("512 wide"));
    }

    @Test
    void traceBlock_containsAllLevels() {
        String trace = WorldSectionCoord.traceBlock(-8, 70, 25);
        assertTrue(trace.contains("Block (-8, 70, 25)"));
        assertTrue(trace.contains("Section:"));
        for (int level = 0; level <= 4; level++) {
            assertTrue(trace.contains("L" + level + ":"), "Trace should contain L" + level);
        }
        assertTrue(trace.contains("Voxy:"));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  isOutOfWorldY  (uses LodGenerationService.isOutOfWorldY)
    // ══════════════════════════════════════════════════════════════════════

    // Minecraft world Y: [-64, 192)   (MIN_WORLD_BLOCK_Y=-64, MAX_WORLD_BLOCK_Y=192)

    @Test
    void isOutOfWorldY_L0_inWorld() {
        // L0: 32 blocks.  wsY=-2 → [-64, -32) — in world
        assertFalse(LodGenerationService.isOutOfWorldY(0, -2));
        // wsY=0 → [0, 32) — in world
        assertFalse(LodGenerationService.isOutOfWorldY(0, 0));
        // wsY=5 → [160, 192) — in world
        assertFalse(LodGenerationService.isOutOfWorldY(0, 5));
    }

    @Test
    void isOutOfWorldY_L0_outsideWorld() {
        // wsY=-3 → [-96, -64) — blockMax=-64, <= -64 → out
        assertTrue(LodGenerationService.isOutOfWorldY(0, -3));
        // wsY=6 → [192, 224) — blockMin=192, >= 192 → out
        assertTrue(LodGenerationService.isOutOfWorldY(0, 6));
        // wsY=-100 → [-3200, -3168) — way below
        assertTrue(LodGenerationService.isOutOfWorldY(0, -100));
    }

    @Test
    void isOutOfWorldY_L0_exactBoundaries() {
        // wsY=-2 → blockMin=-64, blockMax=-32-1=-33 → exclusive upper = -32
        // -32 <= -64?  NO.  -64 >= 192?  NO.  → in world
        assertFalse(LodGenerationService.isOutOfWorldY(0, -2));
        // wsY=-3 → blockMin=-96, exclusive upper = -64.  -64 <= -64? YES → out
        assertTrue(LodGenerationService.isOutOfWorldY(0, -3));
    }

    @Test
    void isOutOfWorldY_L4_rootsUsedByPipeline() {
        // L4: 512 blocks per section.
        // wsY=-1 → [-512, 0) — overlaps [-64, 0) → IN world
        assertFalse(LodGenerationService.isOutOfWorldY(4, -1));
        // wsY=0 → [0, 512) — overlaps [0, 192) → IN world
        assertFalse(LodGenerationService.isOutOfWorldY(4, 0));
        // wsY=-2 → [-1024, -512) — entirely below -64 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(4, -2));
        // wsY=1 → [512, 1024) — entirely above 192 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(4, 1));
    }

    @Test
    void isOutOfWorldY_L3_childrenFromL4() {
        // L3: 256 blocks.  Children of L4 wsY=-1 are L3 wsY ∈ {-2, -1}
        // L3 wsY=-2 → [-512, -256) — -256 <= -64 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(3, -2));
        // L3 wsY=-1 → [-256, 0) — overlaps [-64, 0) → IN
        assertFalse(LodGenerationService.isOutOfWorldY(3, -1));
        // Children of L4 wsY=0 are L3 wsY ∈ {0, 1}
        // L3 wsY=0 → [0, 256) — overlaps [0, 192) → IN
        assertFalse(LodGenerationService.isOutOfWorldY(3, 0));
        // L3 wsY=1 → [256, 512) — 256 >= 192 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(3, 1));
    }

    @Test
    void isOutOfWorldY_L1_childrenFromL2() {
        // L1: 64 blocks. L1 wsY=-2 → [-128, -64) — -64 <= -64 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(1, -2));
        // L1 wsY=-1 → [-64, 0) — in world
        assertFalse(LodGenerationService.isOutOfWorldY(1, -1));
        // L1 wsY=2 → [128, 192) — in world (192 > -64 and 128 < 192)
        assertFalse(LodGenerationService.isOutOfWorldY(1, 2));
        // L1 wsY=3 → [192, 256) — 192 >= 192 → OUT
        assertTrue(LodGenerationService.isOutOfWorldY(1, 3));
    }

    /**
     * Verify that the entire legal octree child cascade from L4 roots at
     * wsY ∈ {-1, 0} stays within world bounds — and out-of-world children
     * are filtered at each level.
     */
    @Test
    void isOutOfWorldY_fullCascade_matchesWorldRange() {
        // At each level, collect which wsY values survive isOutOfWorldY,
        // starting from L4 roots wsY ∈ {-1, 0}
        java.util.Set<Integer> current = new java.util.TreeSet<>();
        current.add(-1);
        current.add(0);

        for (int level = 4; level >= 0; level--) {
            java.util.Set<Integer> inWorld = new java.util.TreeSet<>();
            for (int wsY : current) {
                if (!LodGenerationService.isOutOfWorldY(level, wsY)) {
                    inWorld.add(wsY);
                    // Verify this world section actually overlaps [-64, 192)
                    int bMin = WorldSectionCoord.worldSectionToBlockMin(wsY, level);
                    int bMax = WorldSectionCoord.worldSectionToBlockMax(wsY, level) + 1;
                    assertTrue(bMax > -64 && bMin < 192,
                            String.format("L%d wsY=%d block[%d,%d) should overlap [-64,192)",
                                    level, wsY, bMin, bMax));
                }
            }
            assertFalse(inWorld.isEmpty(), "Level " + level + " should have in-world sections");

            // Expand to children for next level
            if (level > 0) {
                current = new java.util.TreeSet<>();
                for (int wsY : inWorld) {
                    current.add(wsY * 2);       // lower half
                    current.add(wsY * 2 + 1);   // upper half
                }
            }
        }
    }

    /**
     * The log showed L1(-8,-16,-16) being processed — wsY=-16 at L1 covers
     * blocks [-1024,-960), which is 960 blocks below the world.  Verify
     * isOutOfWorldY correctly rejects this.
     */
    @Test
    void isOutOfWorldY_rejectsLoggedBugCoordinates() {
        // L1 wsY=-16 from the bug log
        assertTrue(LodGenerationService.isOutOfWorldY(1, -16),
                "L1 wsY=-16 → blocks [-1024,-960) should be out of world");
        // L2 wsY=-8
        assertTrue(LodGenerationService.isOutOfWorldY(2, -8),
                "L2 wsY=-8 → blocks [-1024,-896) should be out of world");
        // L3 wsY=-4
        assertTrue(LodGenerationService.isOutOfWorldY(3, -4),
                "L3 wsY=-4 → blocks [-1024,-768) should be out of world");
        // L4 wsY=-2 (the root that shouldn't exist)
        assertTrue(LodGenerationService.isOutOfWorldY(4, -2),
                "L4 wsY=-2 → blocks [-1024,-512) should be out of world");
    }
}
