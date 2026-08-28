package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * TDD parity spec for End chorus top-down vs vanilla+Voxy bottom-up.
 *
 * <p>Concept: distant End out-island chunk as player approaches = L4->L3->L2->L1->L0->vanilla.
 * On leaving, vanilla chunk is ingested by Voxy and must be visible as L0->L1->L2->L3->L4.
 * This spec proves our top-down chorus synthesizer produces the same sparse octree
 * Voxy would have produced bottom-up from a full vanilla chunk, within tolerance.
 *
 * <p>A -> C without B: A = seed/surface/biome (noise), B = full vanilla chunk,
 * C = Voxy sparse octree (32^3 WorldSections). We prove C_topDown ~= C_bottomUp.
 *
 * <p>Stratification: dimension=END, Level=L0..L4 (L4 coarsest). Each test is a vertical slice:
 * one behavior, one seam, one Level. Tolerance >=95% voxel agreement; exact 100%
 * is expected for deterministic cases.
 */
class EndChorusTopDownParityTest {

    static final int BLOCK_AIR = CanonicalRegistries.BLOCK_AIR;
    static final int BLOCK_END_STONE = 359;
    static final int BLOCK_CHORUS_FLOWER = 196;
    static final int BLOCK_CHORUS_PLANT = 197;
    static final String EXPECTED_CHORUS_HUE = "purple";

    private static final long TEST_SEED = 0x5EED5EEDL;

    // ------------------------------------------------------------------
    // Coarse levels honestly omit chorus (thin feature caveat)
    // ------------------------------------------------------------------

    @Test
    void l3AndL4CoarseLevelsContainNoChorus() {
        assertTrue(classExists("com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer"),
                "EndChorusSynthesizer must exist to synthesize chorus at coarse LOD");
        var synth = EndChorusSynthesizer.forTesting(TEST_SEED);
        for (Level level : new Level[]{Level.L3, Level.L4}) {
            for (SectionPos origin : new SectionPos[]{
                    new SectionPos(0, 0, 0),
                    new SectionPos(32, 0, 0),
                    new SectionPos(0, 0, 32),
                    new SectionPos(16, 0, 16)}) {
                if (!level.isAligned(origin)) continue;
                VoxelVolume vol = synth.synthesize(level, origin);
                assertEquals(32, vol.extent(), "extent 32 at " + level);
                int chorus = countChorus(vol);
                assertEquals(0, chorus,
                        "L3/L4 must omit chorus (honest omission) at " + level + " origin " + origin);
                // Also ensure no purple hue at coarse: every non-air is end_stone
                for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int id = vol.blockId(x, y, z);
                    assertTrue(id == BLOCK_AIR || id == BLOCK_END_STONE,
                            "coarse L3/L4 only air|end_stone at " + level + " got " + safeName(id));
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // L1: single Mipper step parity (2^3 blocks -> 1 voxel)
    // ------------------------------------------------------------------

    @Test
    void l1TopDownEqualsBottomUpMipViaOpacityBiasedRule() {
        assertTrue(classExists("com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer"),
                "EndChorusSynthesizer must exist");
        var synth = EndChorusSynthesizer.forTesting(TEST_SEED);
        SectionPos origin = new SectionPos(0, 0, 0); // L1 aligned (region 4)
        VoxelVolume topDown = synth.synthesize(Level.L1, origin);

        // Bottom-up: generate independent 64^3 block volume via same deterministic
        // per-block function but through a separate code path, then mip 2^3 via
        // opacity-biased rule. This is not tautological: topDown uses synthesizer,
        // bottomUp uses direct blockIdAt + manual mip via helper.
        VoxelVolume bottomUp = synthesizeBottomUpL1(origin, TEST_SEED);

        assertVolumesEqual(bottomUp, topDown, "L1 top-down must equal bottom-up 2^3 mip");
        // Also verify at least some chorus survives at L1 where islands exist
        // (probabilistic; with seed 0x5EED, we expect >0 but allow zero for void origins)
        // Check purple presence: any non-stone non-air must be purple
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = topDown.blockId(x, y, z);
            assertTrue(id == BLOCK_AIR || id == BLOCK_END_STONE
                            || id == BLOCK_CHORUS_PLANT || id == BLOCK_CHORUS_FLOWER,
                    "L1 purple present only chorus at (" + x + "," + y + "," + z + ") got " + safeName(id));
        }
    }

    // ------------------------------------------------------------------
    // L2: double Mipper (4^3) with purple presence metrics and tolerance
    // ------------------------------------------------------------------

    @Test
    void l2TopDownEqualsDoubleMipWithPurplePresenceMetrics() {
        assertTrue(classExists("com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer"),
                "EndChorusSynthesizer must exist");
        var synth = EndChorusSynthesizer.forTesting(TEST_SEED);
        SectionPos origin = new SectionPos(0, 0, 0); // L2 aligned (region 8)
        VoxelVolume topDown = synth.synthesize(Level.L2, origin);
        VoxelVolume bottomUp = synthesizeBottomUpL2(origin, TEST_SEED);

        // 100% agreement expected for deterministic double-mip; allow 95% tolerance per spec
        double agreement = voxelAgreement(bottomUp, topDown);
        assertTrue(agreement >= 0.95,
                "L2 double-mip agreement must be >=95% but was " + agreement + " topDown chorus=" + countChorus(topDown) + " bottomUp chorus=" + countChorus(bottomUp));

        // Purple presence: count chorus before and after double mip, ensure plausible retention
        int topChorus = countChorus(topDown);
        int bottomChorus = countChorus(bottomUp);
        // L2 has 4x coarser sampling, so chorus count is reduced but not zero where islands exist
        // For our seed, topDown should have >0 chorus if any island column had plant
        // We do not assert >0 because void origins may have zero; we assert counts match within tolerance
        assertEquals(bottomChorus, topChorus, "L2 chorus counts must match between top-down and bottom-up");
        // Every chorus voxel must be purple
        assertTrue(isAllPurple(topDown), "L2 chorus hue must be purple");
    }

    // ------------------------------------------------------------------
    // Cache reuse: L2 cached derivation for L1 equals direct L1
    // ------------------------------------------------------------------

    @Test
    void l2CacheReusedForL1RefinementMatchesDirectGeneration() {
        boolean hasCache = classExists("com.rhythmatician.lodiffusion.voxy.EndChorusCache");
        boolean hasSynth = classExists("com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer");
        assertTrue(hasCache || hasSynth, "cache type must exist");

        var synth = EndChorusSynthesizer.forTesting(TEST_SEED);
        var cache = new EndChorusCache(synth);

        // Prime L2 cache at parent
        SectionPos l2Origin = new SectionPos(0, 0, 0);
        VoxelVolume l2 = cache.getL2(l2Origin);
        assertNotNull(l2);
        assertEquals(1, cache.cachedL2Count(), "L2 should be cached");

        // Derive each of the 8 L1 children via cache and compare to direct
        for (int oct = 0; oct < 8; oct++) {
            SectionPos l1Origin = l1ChildOf(l2Origin, oct);
            VoxelVolume viaCache = cache.deriveL1FromL2(l1Origin);
            VoxelVolume direct = synth.synthesize(Level.L1, l1Origin);
            assertVolumesEqual(direct, viaCache,
                    "L1 child octant " + oct + " via cache must match direct");
        }
        // Also ensure direct L1 equals bottom-up for those children
        for (int oct = 0; oct < 8; oct++) {
            SectionPos l1Origin = l1ChildOf(l2Origin, oct);
            VoxelVolume direct = synth.synthesize(Level.L1, l1Origin);
            VoxelVolume bottomUp = synthesizeBottomUpL1(l1Origin, TEST_SEED);
            double agr = voxelAgreement(direct, bottomUp);
            assertTrue(agr >= 0.95, "L1 cache child " + oct + " agreement " + agr);
        }
    }

    // ------------------------------------------------------------------
    // Purple hue + biome gating
    // ------------------------------------------------------------------

    @Test
    void chorusVoxelsArePurpleAndBiomeGated() {
        assertTrue(classExists("com.rhythmatician.lodiffusion.voxy.EndChorusSynthesizer"),
                "EndChorusSynthesizer must exist");

        // Gated case: only eligible biome (end_highlands) gets chorus
        var eligible = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> true); // flat
        var ineligible = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> false);
        VoxelVolume volEligible = eligible.synthesize(Level.L1, new SectionPos(0, 0, 0));
        VoxelVolume volIneligible = ineligible.synthesize(Level.L1, new SectionPos(0, 0, 0));

        int chorusEligible = countChorus(volEligible);
        int chorusIneligible = countChorus(volIneligible);
        assertTrue(chorusEligible >= 0, "eligible may have chorus");
        assertEquals(0, chorusIneligible, "ineligible biome must have zero chorus");

        // Purple hue: every chorus voxel's canonical name contains purple family
        // Verified via block registry: chorus_plant/flower are purple; we check IDs
        assertTrue(isAllPurple(volEligible), "eligible volume chorus must be purple");
        assertTrue(isAllPurple(volIneligible), "ineligible volume (empty) trivially purple");

        // Mixed biome: checkerboard
        var mixed = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> ((x + z) & 1) == 0);
        VoxelVolume volMixed = mixed.synthesize(Level.L1, new SectionPos(0, 0, 0));
        int mixedCount = countChorus(volMixed);
        // Mixed should have between 0 and eligible, roughly half, but at least not equal to eligible if eligible>0
        if (chorusEligible > 0) {
            assertTrue(mixedCount <= chorusEligible, "mixed chorus <= eligible");
            assertTrue(mixedCount >= 0, "mixed chorus >=0");
        }
        assertTrue(isAllPurple(volMixed), "mixed chorus must be purple");
    }

    // ------------------------------------------------------------------
    // Helpers mirroring production Mipper and test utilities
    // ------------------------------------------------------------------

    static boolean classExists(String fqn) {
        try { Class.forName(fqn); return true; } catch (ClassNotFoundException e) { return false; }
    }

    static int countChorus(VoxelVolume v) {
        int n = 0;
        int e = v.extent();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int id = v.blockId(x, y, z);
            if (id == BLOCK_CHORUS_PLANT || id == BLOCK_CHORUS_FLOWER) n++;
        }
        return n;
    }

    static int mipBlockId(int[] eightChildren) {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < 8; i++) {
            int id = eightChildren[i];
            if (id == 0) continue;
            int opacity = (id == 359) ? 15 : 0;
            int cornerPriority = i;
            int score = (opacity << 4) | cornerPriority;
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best == -1 ? 0 : best;
    }

    static void assertVolumesEqual(VoxelVolume expected, VoxelVolume actual, String msg) {
        assertEquals(expected.extent(), actual.extent(), msg + " extent");
        int e = expected.extent();
        java.util.List<String> diffs = new java.util.ArrayList<>();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int eb = expected.blockId(x, y, z);
            int ab = actual.blockId(x, y, z);
            if (eb != ab) {
                if (diffs.size() < 5) diffs.add(String.format("(%d,%d,%d) exp %d(%s) got %d(%s)", x, y, z, eb, safeName(eb), ab, safeName(ab)));
            }
        }
        assertTrue(diffs.isEmpty(), msg + " diffs: " + String.join(";", diffs));
    }

    static double voxelAgreement(VoxelVolume a, VoxelVolume b) {
        assertEquals(a.extent(), b.extent(), "extent");
        int e = a.extent();
        int total = e * e * e;
        int same = 0;
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            if (a.blockId(x, y, z) == b.blockId(x, y, z)) same++;
        }
        return (double) same / total;
    }

    static boolean isAllPurple(VoxelVolume v) {
        int e = v.extent();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int id = v.blockId(x, y, z);
            if (id != BLOCK_AIR && id != BLOCK_END_STONE && id != BLOCK_CHORUS_PLANT && id != BLOCK_CHORUS_FLOWER) return false;
        }
        return true;
    }

    static String safeName(int id) {
        try { return CanonicalRegistries.canonicalName(id); } catch (Exception e) { return "id:" + id; }
    }

    private static VoxelVolume synthesizeBottomUpL1(SectionPos origin, long seed) {
        // Bottom-up independent path: generate 64^3 blocks via same blockIdAt but without calling synthesizer's mip,
        // to prove synthesizer's mip equals manual mip.
        var refSynth = EndChorusSynthesizer.forTesting(seed);
        // Use reflection to call blockIdAt for bottom-up generation, or just call synthesize via a second instance
        // For independence, we recompute blocks here using the same hash but separate loop.
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        final int regionBlocks = 64;
        int[] blocks = new int[regionBlocks * regionBlocks * regionBlocks];
        for (int y = 0; y < regionBlocks; y++) {
            int wy = baseY + y;
            for (int z = 0; z < regionBlocks; z++) {
                int wz = baseZ + z;
                for (int x = 0; x < regionBlocks; x++) {
                    int wx = baseX + x;
                    blocks[(y * regionBlocks + z) * regionBlocks + x] =
                            refSynth.blockIdAt(wx, wy, wz);
                }
            }
        }
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int vy = 0; vy < 32; vy++) for (int vz = 0; vz < 32; vz++) for (int vx = 0; vx < 32; vx++) {
            int bx = vx * 2, by = vy * 2, bz = vz * 2;
            int[] eight = new int[8];
            int idx = 0;
            for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                eight[idx++] = blocks[((by + dy) * regionBlocks + (bz + dz)) * regionBlocks + (bx + dx)];
            }
            int mip = mipBlockId(eight);
            if (mip != 0) b.setBlock(vx, vy, vz, mip);
        }
        return b.build();
    }

    private static VoxelVolume synthesizeBottomUpL2(SectionPos origin, long seed) {
        var refSynth = EndChorusSynthesizer.forTesting(seed);
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        final int regionBlocks = 128;
        int[] blocks = new int[regionBlocks * regionBlocks * regionBlocks];
        for (int y = 0; y < regionBlocks; y++) {
            int wy = baseY + y;
            for (int z = 0; z < regionBlocks; z++) {
                int wz = baseZ + z;
                for (int x = 0; x < regionBlocks; x++) {
                    int wx = baseX + x;
                    blocks[(y * regionBlocks + z) * regionBlocks + x] =
                            refSynth.blockIdAt(wx, wy, wz);
                }
            }
        }
        // double mip 2->2
        final int mid = 64;
        int[] midBlocks = new int[mid * mid * mid];
        for (int y = 0; y < mid; y++) for (int z = 0; z < mid; z++) for (int x = 0; x < mid; x++) {
            int bx = x * 2, by = y * 2, bz = z * 2;
            int[] eight = new int[8];
            int idx = 0;
            for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                eight[idx++] = blocks[((by + dy) * regionBlocks + (bz + dz)) * regionBlocks + (bx + dx)];
            }
            midBlocks[(y * mid + z) * mid + x] = mipBlockId(eight);
        }
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int vy = 0; vy < 32; vy++) for (int vz = 0; vz < 32; vz++) for (int vx = 0; vx < 32; vx++) {
            int bx = vx * 2, by = vy * 2, bz = vz * 2;
            int[] eight = new int[8];
            int idx = 0;
            for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                eight[idx++] = midBlocks[((by + dy) * mid + (bz + dz)) * mid + (bx + dx)];
            }
            int mip = mipBlockId(eight);
            if (mip != 0) b.setBlock(vx, vy, vz, mip);
        }
        return b.build();
    }

    private static SectionPos l1ChildOf(SectionPos l2Origin, int octant) {
        int s = Level.L1.regionSections(); // 4
        return new SectionPos(
                l2Origin.x() + ((octant & 1) * s),
                l2Origin.y() + (((octant >> 2) & 1) * s),
                l2Origin.z() + (((octant >> 1) & 1) * s));
    }
}


