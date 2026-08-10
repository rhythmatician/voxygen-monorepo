package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * Unit tests for {@link OctreeTask} coordinate helpers and
 * {@link OctreeQueue} child-spawning logic.
 *
 * <p>These verify the octree traversal contract without requiring a live
 * WorldEngine or ONNX models.  Tests cover:
 * <ul>
 *   <li>Parent→child coordinate expansion</li>
 *   <li>Octant index encoding (bit layout: x|z|y)</li>
 *   <li>Key packing/deduplication</li>
 *   <li>Child spawn / dedup behaviour</li>
 *   <li>{@code extractAndUpsampleOctant} nearest-neighbor upsampling</li>
 * </ul>
 */
class OctreeQueueSpawnTest {

    // ══════════════════════════════════════════════════════════════════════
    //  OctreeTask coordinate helpers
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void octantIndex_allCombinations() {
        // Exhaustive check for all 8 octant combinations
        // Bit layout: bit0=x, bit1=z, bit2=y
        assertEquals(0, OctreeTask.octantIndex(0, 0, 0));  // 000
        assertEquals(1, OctreeTask.octantIndex(1, 0, 0));  // 001
        assertEquals(2, OctreeTask.octantIndex(0, 0, 1));  // 010
        assertEquals(3, OctreeTask.octantIndex(1, 0, 1));  // 011
        assertEquals(4, OctreeTask.octantIndex(0, 1, 0));  // 100
        assertEquals(5, OctreeTask.octantIndex(1, 1, 0));  // 101
        assertEquals(6, OctreeTask.octantIndex(0, 1, 1));  // 110
        assertEquals(7, OctreeTask.octantIndex(1, 1, 1));  // 111
    }

    @Test
    void octantIndex_matchesVoxyWorldSection() {
        // Verify our encoding matches Voxy's WorldSection.getChildIndex(x,y,z)
        // Voxy: (x&1) | ((y&1)<<2) | ((z&1)<<1)
        for (int x = 0; x < 4; x++)
            for (int y = 0; y < 4; y++)
                for (int z = 0; z < 4; z++) {
                    int voxyIdx = (x & 1) | ((y & 1) << 2) | ((z & 1) << 1);
                    int ourIdx = OctreeTask.octantIndex(x, y, z);
                    assertEquals(voxyIdx, ourIdx,
                            "Mismatch at (" + x + "," + y + "," + z + ")");
                }
    }

    @ParameterizedTest(name = "octant {0}: childX({1})={2}, childY({3})={4}, childZ({5})={6}")
    @CsvSource({
        "0, 0,0, 0,0, 0,0",   // octant 0: all zeros
        "1, 0,1, 0,0, 0,0",   // octant 1: +X
        "2, 0,0, 0,0, 0,1",   // octant 2: +Z
        "3, 0,1, 0,0, 0,1",   // octant 3: +X +Z
        "4, 0,0, 0,1, 0,0",   // octant 4: +Y
        "5, 0,1, 0,1, 0,0",   // octant 5: +X +Y
        "6, 0,0, 0,1, 0,1",   // octant 6: +Y +Z
        "7, 0,1, 0,1, 0,1"    // octant 7: +X +Y +Z
    })
    void childCoords_fromOriginParent(int oct,
                                       int pX, int expCX,
                                       int pY, int expCY,
                                       int pZ, int expCZ) {
        assertEquals(expCX, OctreeTask.childX(pX, oct));
        assertEquals(expCY, OctreeTask.childY(pY, oct));
        assertEquals(expCZ, OctreeTask.childZ(pZ, oct));
    }

    @Test
    void childCoords_nonZeroParent() {
        // Parent at (5, 3, 7), octant 7 (all bits set)
        // childX = 5*2 + 1 = 11, childY = 3*2 + 1 = 7, childZ = 7*2 + 1 = 15
        assertEquals(11, OctreeTask.childX(5, 7));
        assertEquals(7, OctreeTask.childY(3, 7));
        assertEquals(15, OctreeTask.childZ(7, 7));
    }

    @Test
    void childCoords_roundTrip() {
        // For every octant, the child coords should reconstruct the octant
        int parentX = 10, parentY = 20, parentZ = 30;
        for (int oct = 0; oct < 8; oct++) {
            int cx = OctreeTask.childX(parentX, oct);
            int cy = OctreeTask.childY(parentY, oct);
            int cz = OctreeTask.childZ(parentZ, oct);

            // Reconstruct octant from child coords
            int reconstructed = OctreeTask.octantIndex(cx, cy, cz);
            assertEquals(oct, reconstructed,
                    "Round-trip failed for octant " + oct);
        }
    }

    @Test
    void childCoords_negativeParent() {
        // Negative coords work correctly
        int parentX = -3, parentY = -1, parentZ = -2;
        // octant 0: childX = -3*2 + 0 = -6
        assertEquals(-6, OctreeTask.childX(parentX, 0));
        assertEquals(-2, OctreeTask.childY(parentY, 0));
        assertEquals(-4, OctreeTask.childZ(parentZ, 0));

        // octant 7: childX = -3*2 + 1 = -5
        assertEquals(-5, OctreeTask.childX(parentX, 7));
        assertEquals(-1, OctreeTask.childY(parentY, 7));
        assertEquals(-3, OctreeTask.childZ(parentZ, 7));
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Key packing
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void packKey_differentLevels_differentKeys() {
        long k0 = OctreeTask.packKey(0, 0, 0, 0);
        long k4 = OctreeTask.packKey(4, 0, 0, 0);
        assertNotEquals(k0, k4,
                "Different levels must produce different keys");
    }

    @Test
    void packKey_sameCoordsDifferentLevel() {
        for (int lvl = 0; lvl < 5; lvl++) {
            long k = OctreeTask.packKey(lvl, 100, 200, 300);
            // Verify level is recoverable from the top 3 bits
            int recoveredLvl = (int) (k >>> 60) & 0x7;
            assertEquals(lvl, recoveredLvl,
                    "Level " + lvl + " should be recoverable from packed key");
        }
    }

    @Test
    void packKey_negativeCoords() {
        // Negative coords should produce different keys from positive
        long kPos = OctreeTask.packKey(2, 1, 1, 1);
        long kNeg = OctreeTask.packKey(2, -1, -1, -1);
        assertNotEquals(kPos, kNeg);
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OctreeQueue child spawning
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void spawnChildren_noOccupancy_spawnsNone() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask parent = new OctreeTask(4, 0, 0, 0, -1, 0);
        int[][][] argmax = new int[32][32][32]; // all zeros

        int spawned = queue.spawnChildren(parent, (byte) 0x00, argmax, 0, 0);
        assertEquals(0, spawned, "Zero occMask should spawn no children");
    }

    @Test
    void spawnChildren_allOccupied_spawnsEight() {
        OctreeQueue queue = new OctreeQueue();
        // Use L2 parent at (0,0,0): all 8 L1 children have Y in [0,127] — in-world
        OctreeTask parent = new OctreeTask(2, 0, 0, 0, -1, 0);
        int[][][] argmax = new int[32][32][32];

        int spawned = queue.spawnChildren(parent, (byte) 0xFF, argmax, 0, 0);
        assertEquals(8, spawned, "Full occMask should spawn 8 children");
    }

    @Test
    void spawnChildren_singleOctant() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask parent = new OctreeTask(3, 0, 0, 0, -1, 0);
        int[][][] argmax = new int[32][32][32];

        // Only octant 5 occupied (bit 5)
        int spawned = queue.spawnChildren(parent, (byte) (1 << 5), argmax, 0, 0);
        assertEquals(1, spawned, "Single bit should spawn 1 child");

        // The child should be at level 2
        assertEquals(1, queue.levelQueueSize(2),
                "Child should be in level 2 queue");
    }

    @Test
    void spawnChildren_childLevel_isParentMinusOne() {
        OctreeQueue queue = new OctreeQueue();

        // L4 parent → L3 children
        OctreeTask parent = new OctreeTask(4, 0, 0, 0, -1, 0);
        int[][][] argmax = new int[32][32][32];
        queue.spawnChildren(parent, (byte) 0x01, argmax, 0, 0);
        assertEquals(1, queue.levelQueueSize(3),
                "L4 parent spawns to L3 queue");

        // L1 parent → L0 children
        queue.clear();
        OctreeTask parent1 = new OctreeTask(1, 0, 0, 0, 0, 0);
        parent1.parentContextFlat = new long[32 * 32 * 32]; // required for non-root
        queue.spawnChildren(parent1, (byte) 0x01, argmax, 0, 0);
        assertEquals(1, queue.levelQueueSize(0),
                "L1 parent spawns to L0 queue");
    }

    @Test
    void spawnChildren_l0Parent_spawnsNothing() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask parent = new OctreeTask(0, 0, 0, 0, 0, 0);
        int[][][] argmax = new int[32][32][32];

        int spawned = queue.spawnChildren(parent, (byte) 0xFF, argmax, 0, 0);
        assertEquals(0, spawned, "L0 (leaf) should not spawn children");
    }

    @Test
    void spawnChildren_deduplication() {
        OctreeQueue queue = new OctreeQueue();
        // Use L2 parent at (0,0,0): all 8 L1 children have Y in [0,127] — in-world
        OctreeTask parent = new OctreeTask(2, 0, 0, 0, -1, 0);
        int[][][] argmax = new int[32][32][32];

        // First spawn: 8 children
        int first = queue.spawnChildren(parent, (byte) 0xFF, argmax, 0, 0);
        assertEquals(8, first);

        // Second spawn with same parent: all duplicates
        int second = queue.spawnChildren(parent, (byte) 0xFF, argmax, 0, 0);
        assertEquals(0, second, "Duplicate children should not be re-enqueued");
    }

    @Test
    void spawnChildren_childCoordinates() {
        OctreeQueue queue = new OctreeQueue();
        // Parent at L4 (5, -1, 7), only octant 7 occupied
        // Child Y = -1*2+1 = -1 at L3 → blocks [-256, -1], overlaps world [-64, 192)
        OctreeTask parent = new OctreeTask(4, 5, -1, 7, -1, 0);
        int[][][] argmax = new int[32][32][32];

        queue.spawnChildren(parent, (byte) (1 << 7), argmax, 0, 0);

        // Child should be at L3 with coords:
        // childX = 5*2 + 1 = 11
        // childY = -1*2 + 1 = -1
        // childZ = 7*2 + 1 = 15
        OctreeTask child = queue.pollLevel(3);
        assertNotNull(child, "Child should be in L3 queue");
        assertEquals(3, child.level);
        assertEquals(11, child.wsX, "childX = parentX*2 + (oct&1)");
        assertEquals(-1, child.wsY, "childY = parentY*2 + ((oct>>2)&1)");
        assertEquals(15, child.wsZ, "childZ = parentZ*2 + ((oct>>1)&1)");
        assertEquals(7, child.octant, "Child's octant should be 7");
    }

    @Test
    void spawnChildren_childHasParentContext() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask parent = new OctreeTask(4, 0, 0, 0, -1, 0);

        // Fill argmax with known pattern: class = Y coordinate
        int[][][] argmax = new int[32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    argmax[y][z][x] = y;

        // Spawn octant 0 (offsets: X=0, Z=0, Y=0 → lower-left-bottom octant)
        queue.spawnChildren(parent, (byte) 0x01, argmax, 0, 0);
        OctreeTask child = queue.pollLevel(3);

        assertNotNull(child.parentContextFlat,
                "Child must have parentContextFlat");
        assertEquals(32 * 32 * 32, child.parentContextFlat.length,
                "parentContextFlat must be 32768 elements");

        // Verify upsampled content: octant 0 extracts Y=0..15 from parent
        // After 2× upsample: dst[0] should map to src Y=0, dst[1] → Y=0,
        // dst[2] → Y=1, dst[3] → Y=1, etc.
        // First row (y=0, z=0): should all be 0 (from src Y=0)
        assertEquals(0L, child.parentContextFlat[0],
                "First voxel should be class 0 (from src Y=0)");

        // Row at dst y=2, which maps to src Y=1
        int idx_y2_z0_x0 = 2 * 32 * 32;
        assertEquals(1L, child.parentContextFlat[idx_y2_z0_x0],
                "dst Y=2 should map to src Y=1 via nearest-neighbor");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  extractAndUpsampleOctant
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void extractAndUpsample_outputSize() {
        int[][][] src = new int[32][32][32];
        long[] result = OctreeQueue.extractAndUpsampleOctant(src, 0, 0, 0);
        assertEquals(32 * 32 * 32, result.length,
                "Output must be exactly 32768 elements");
    }

    @Test
    void extractAndUpsample_octant0_uniformValue() {
        // Fill entire volume with class 42
        int[][][] src = new int[32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    src[y][z][x] = 42;

        long[] result = OctreeQueue.extractAndUpsampleOctant(src, 0, 0, 0);

        // All output voxels should be 42
        for (int i = 0; i < result.length; i++)
            assertEquals(42L, result[i],
                    "All voxels should be 42 after upsample, failed at index " + i);
    }

    @Test
    void extractAndUpsample_nearestNeighbor_2x() {
        // Fill src with gradient: value = x coordinate
        int[][][] src = new int[32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    src[y][z][x] = x;

        // Extract octant 0 (offX=0, 16 source voxels: x=0..15)
        // Upsample 2×: dst x=0,1 → src x=0; dst x=2,3 → src x=1; etc.
        long[] result = OctreeQueue.extractAndUpsampleOctant(src, 0, 0, 0);

        for (int dx = 0; dx < 32; dx++) {
            int expectedSrcX = dx >> 1;  // nearest-neighbor
            assertEquals((long) expectedSrcX, result[dx],
                    "At dst x=" + dx + " expected src x=" + expectedSrcX);
        }
    }

    @Test
    void extractAndUpsample_octant7_offsets() {
        // Octant 7: bit0=X=1, bit1=Z=1, bit2=Y=1
        // Offsets: X=16, Z=16, Y=16
        int[][][] src = new int[32][32][32];

        // Mark only the octant 7 region with value 99
        for (int y = 16; y < 32; y++)
            for (int z = 16; z < 32; z++)
                for (int x = 16; x < 32; x++)
                    src[y][z][x] = 99;

        long[] result = OctreeQueue.extractAndUpsampleOctant(src, 16, 16, 16);

        // All output should be 99
        for (int i = 0; i < result.length; i++)
            assertEquals(99L, result[i],
                    "Octant 7 extraction should return 99 everywhere");
    }

    @Test
    void extractAndUpsample_differentOctants_extractDifferentData() {
        // Fill each octant with a different value
        int[][][] src = new int[32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++) {
                    int octX = (x >= 16) ? 1 : 0;
                    int octZ = (z >= 16) ? 1 : 0;
                    int octY = (y >= 16) ? 1 : 0;
                    int octant = octX | (octZ << 1) | (octY << 2);
                    src[y][z][x] = octant;
                }

        // Extract each octant and verify all values match the octant index
        for (int oct = 0; oct < 8; oct++) {
            int offX = (oct & 1) * 16;
            int offZ = ((oct >> 1) & 1) * 16;
            int offY = ((oct >> 2) & 1) * 16;

            long[] result = OctreeQueue.extractAndUpsampleOctant(src, offY, offZ, offX);

            for (int i = 0; i < result.length; i++)
                assertEquals((long) oct, result[i],
                        "Octant " + oct + " should contain value " + oct
                        + " at index " + i);
        }
    }

    @Test
    void extractAndUpsample_yGradient_verifyRowMajorYZX() {
        // Verify Y,Z,X row-major ordering in output flat array
        int[][][] src = new int[32][32][32];
        for (int y = 0; y < 32; y++)
            for (int z = 0; z < 32; z++)
                for (int x = 0; x < 32; x++)
                    src[y][z][x] = y;  // value = Y coordinate

        // Octant 0: extracts Y=0..15
        long[] result = OctreeQueue.extractAndUpsampleOctant(src, 0, 0, 0);

        // Verify a few known positions in YZX layout:
        // idx = dy * 32 * 32 + dz * 32 + dx
        for (int dy = 0; dy < 32; dy++) {
            int idx = dy * 32 * 32; // dz=0, dx=0
            int expectedY = dy >> 1; // nearest-neighbor from source
            assertEquals((long) expectedY, result[idx],
                    "Row-major Y at dy=" + dy + " should be " + expectedY);
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OctreeTask state transitions
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void taskState_defaultIsPending() {
        OctreeTask task = new OctreeTask(4, 0, 0, 0, -1, 0);
        assertEquals(OctreeTask.State.PENDING, task.state());
    }

    @Test
    void taskState_claimForProcessing() {
        OctreeTask task = new OctreeTask(4, 0, 0, 0, -1, 0);
        assertTrue(task.claimForProcessing());
        assertEquals(OctreeTask.State.PROCESSING, task.state());
        // Second claim should fail
        assertFalse(task.claimForProcessing());
    }

    @Test
    void taskState_markReady_clearsParentContext() {
        OctreeTask task = new OctreeTask(3, 0, 0, 0, 0, 0);
        task.parentContextFlat = new long[32 * 32 * 32];
        task.markReady();
        assertEquals(OctreeTask.State.READY, task.state());
        assertNull(task.parentContextFlat,
                "markReady should null parentContextFlat to free memory");
    }

    @Test
    void taskState_markFailed_clearsParentContext() {
        OctreeTask task = new OctreeTask(3, 0, 0, 0, 0, 0);
        task.parentContextFlat = new long[32 * 32 * 32];
        task.markFailed("test error");
        assertEquals(OctreeTask.State.FAILED, task.state());
        assertEquals("test error", task.failureMessage);
        assertNull(task.parentContextFlat);
    }

    @Test
    void taskState_cancel_onlyFromPending() {
        OctreeTask task = new OctreeTask(4, 0, 0, 0, -1, 0);
        assertTrue(task.cancel());
        assertTrue(task.isCancelled());

        // Cannot cancel twice
        OctreeTask task2 = new OctreeTask(4, 1, 0, 0, -1, 0);
        task2.claimForProcessing();
        assertFalse(task2.cancel(), "Cannot cancel a PROCESSING task");
    }

    // ══════════════════════════════════════════════════════════════════════
    //  OctreeQueue enqueue / dedup
    // ══════════════════════════════════════════════════════════════════════

    @Test
    void enqueueRoot_rejectsNonL4() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask badTask = new OctreeTask(3, 0, 0, 0, -1, 0);
        assertThrows(IllegalArgumentException.class,
                () -> queue.enqueueRoot(badTask),
                "enqueueRoot should reject non-L4 tasks");
    }

    @Test
    void enqueueRoot_deduplicates() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask t1 = new OctreeTask(4, 0, 0, 0, -1, 0);
        OctreeTask t2 = new OctreeTask(4, 0, 0, 0, -1, 0);

        assertTrue(queue.enqueueRoot(t1), "First enqueue should succeed");
        assertFalse(queue.enqueueRoot(t2), "Duplicate key should be rejected");
    }

    @Test
    void queue_levelSizes() {
        OctreeQueue queue = new OctreeQueue();

        // Enqueue 3 L4 roots
        for (int i = 0; i < 3; i++) {
            queue.enqueueRoot(new OctreeTask(4, i, 0, 0, -1, 0));
        }
        assertEquals(3, queue.levelQueueSize(4));
        assertEquals(0, queue.levelQueueSize(3));
    }

    @Test
    void queue_completionTracking() {
        OctreeQueue queue = new OctreeQueue();
        assertEquals(0, queue.completedCount());
        assertEquals(0, queue.failedCount());

        queue.markCompleted();
        queue.markCompleted();
        queue.markFailed();

        assertEquals(2, queue.completedCount());
        assertEquals(1, queue.failedCount());
        assertEquals(3, queue.totalProcessed());
    }

    // ══════════════════════════════════════════════════════════════════════
    //  Coordinate-scaling regression tests
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Verify that updatePriority correctly maps playerSectionX/Z (16-block
     * chunk coords) to world-section coordinates at each level.
     *
     * <p>Player at block (-181, -9) → playerSection = (-12, -1).
     * <ul>
     *   <li>L0 wsX covers 32 blocks → player's L0 wsX = -6 (block -181 / 32)</li>
     *   <li>L4 wsX covers 512 blocks → player's L4 wsX = -1 (block -181 / 512)</li>
     * </ul>
     * A task AT the player's position should get priority 0.
     */
    @Test
    void updatePriority_coordinateScaling() {
        int playerSectionX = -12;  // blockX=-181 >> 4
        int playerSectionZ = -1;   // blockZ=-9 >> 4

        // L0: player's wsX = floor(-181/32) = -6, wsZ = floor(-9/32) = -1
        OctreeTask l0 = new OctreeTask(0, -6, 0, -1, 0, 999);
        l0.updatePriority(playerSectionX, playerSectionZ);
        assertEquals(0, l0.priority, "L0 task at player position should have priority 0");

        // L4: player's wsX = floor(-181/512) = -1, wsZ = floor(-9/512) = -1
        OctreeTask l4 = new OctreeTask(4, -1, 0, -1, -1, 999);
        l4.updatePriority(playerSectionX, playerSectionZ);
        assertEquals(0, l4.priority, "L4 task at player position should have priority 0");

        // L0 task 1 section away should have priority 1
        OctreeTask l0off = new OctreeTask(0, -5, 0, -1, 0, 999);
        l0off.updatePriority(playerSectionX, playerSectionZ);
        assertEquals(1, l0off.priority, "L0 task 1 section from player should have priority 1");
    }

    /**
     * Verify that spawnChildren computes child priorities using the correct
     * coordinate mapping (matching updatePriority).
     */
    @Test
    void spawnChildren_priorityUsesCorrectCoordinateScaling() {
        OctreeQueue queue = new OctreeQueue();
        // Parent at L4 (-1, 0, -1) — the player's L4 section
        OctreeTask parent = new OctreeTask(4, -1, 0, -1, -1, 0);
        int[][][] argmax = new int[32][32][32];

        // Player at block (-181, -9) → section (-12, -1)
        queue.spawnChildren(parent, (byte) 0xFF, argmax, -12, -1);

        // L3 child at (-1, 0, -1) should be the closest
        // Player L3 wsX = floor(-181/256) = -1, L3 wsZ = floor(-9/256) = -1
        // So child (-1, *, -1) should have priority 0
        boolean foundZeroPriority = false;
        for (int i = 0; i < 8; i++) {
            OctreeTask child = queue.pollLevel(3);
            if (child != null && child.wsX == -1 && child.wsZ == -1) {
                assertEquals(0, child.priority,
                    "L3 child at (-1,*,-1) should have distance 0 from player");

                foundZeroPriority = true;
            }
        }
        assertTrue(foundZeroPriority, "Should find a child with distance priority 0");
    }

    // ══════════════════════════════════════════════════════════════════
    //  Adjacency boost propagation tests
    // ══════════════════════════════════════════════════════════════════

    @Test
    void adjacencyBoost_simpleNeighbour() {
        OctreeQueue queue = new OctreeQueue();
        // two adjacent L0 tasks, both start with same priority
        OctreeTask t1 = new OctreeTask(0, 0, 0, 0, 0, 10);
        OctreeTask t2 = new OctreeTask(0, 1, 0, 0, 1, 10);
        assertTrue(queue.enqueueChild(t1));
        assertTrue(queue.enqueueChild(t2));

        // no boosts yet
        assertFalse(t2.nearProcessed);

        // mark t1 as completed; propagation should flag t2
        queue.propagateAdjacency(t1);
        assertTrue(t2.nearProcessed, "neighbour should have been flagged");

        // reprioritise to apply the new boost
        queue.reprioritise(0, 0);
        assertTrue(t2.priority < 10,
                "priority should drop when adjacent to processed");
    }

    @Test
    void adjacencyBoost_ringsExpand() {
        OctreeQueue queue = new OctreeQueue();
        OctreeTask a = new OctreeTask(0, 0, 0, 0, 0, 20);
        OctreeTask b = new OctreeTask(0, 1, 0, 0, 1, 20);
        OctreeTask c = new OctreeTask(0, 2, 0, 0, 2, 20);
        assertTrue(queue.enqueueChild(a));
        assertTrue(queue.enqueueChild(b));
        assertTrue(queue.enqueueChild(c));

        // complete a → boost b only
        queue.propagateAdjacency(a);
        queue.reprioritise(0,0);
        assertTrue(b.nearProcessed);
        assertFalse(c.nearProcessed);

        // complete b and propagate again
        queue.propagateAdjacency(b);
        queue.reprioritise(0,0);
        assertTrue(c.nearProcessed, "second ring should be boosted after b completes");
    }
}

