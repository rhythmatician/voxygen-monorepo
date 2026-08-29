package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Synthetic mipper consistency for End chorus.
 *
 * <p>TODO(#233): real vanilla+Voxy oracle pending. Until then this test does NOT claim
 * RoundTrip, VanillaConvergence, or post-ingest Voxy parity per ADR 0015. It only
 * asserts deterministic internal consistency: same seed + same synthetic surface +
 * same opacity-biased Mipper must yield identical 32³ volumes when exercised via
 * two independent code paths (synthesizer vs manual mip). Real parity requires
 * independent vanilla chunk + Voxy ingest fixtures (see Issue #233).
 */
class EndChorusSyntheticMipperConsistencyTest {

    static final int BLOCK_AIR = CanonicalRegistries.BLOCK_AIR;
    static final int BLOCK_END_STONE = 359;
    static final int BLOCK_CHORUS_FLOWER = 196;
    static final int BLOCK_CHORUS_PLANT = 197;

    private static final long TEST_SEED = 0x5EED5EEDL;

    @Test
    void l3AndL4CoarseLevelsContainNoChorus_honestOmission() {
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
                assertEquals(0, chorus, "L3/L4 must omit chorus (honest omission) at " + level + " origin " + origin);
                for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int id = vol.blockId(x, y, z);
                    assertTrue(id == BLOCK_AIR || id == BLOCK_END_STONE,
                            "coarse L3/L4 only air|end_stone at " + level + " got " + safeName(id));
                }
            }
        }
    }

    @Test
    void chorusVoxelsArePurpleAndBiomeGated() {
        var eligible = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> true);
        var ineligible = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> false);
        VoxelVolume volEligible = eligible.synthesize(Level.L1, new SectionPos(0, 0, 0));
        VoxelVolume volIneligible = ineligible.synthesize(Level.L1, new SectionPos(0, 0, 0));
        int chorusIneligible = countChorus(volIneligible);
        assertEquals(0, chorusIneligible, "ineligible biome must have zero chorus");
        assertTrue(isAllPurple(volEligible), "eligible volume chorus must be purple");
        assertTrue(isAllPurple(volIneligible), "ineligible volume trivially purple");
        var mixed = EndChorusSynthesizer.forTesting(TEST_SEED, (x, z) -> 70, (x, z) -> ((x + z) & 1) == 0);
        VoxelVolume volMixed = mixed.synthesize(Level.L1, new SectionPos(0, 0, 0));
        assertTrue(isAllPurple(volMixed), "mixed chorus must be purple");
    }

    @Test
    void l1SyntheticMipConsistentWithManualMip() {
        var synth = EndChorusSynthesizer.forTesting(TEST_SEED);
        SectionPos origin = new SectionPos(0, 0, 0);
        VoxelVolume topDown = synth.synthesize(Level.L1, origin);
        VoxelVolume bottomUp = synthesizeBottomUpL1(origin, TEST_SEED);
        assertVolumesEqual(bottomUp, topDown, "L1 synthetic must equal manual 2^3 mip (same seed/surface/Mipper)");
    }

    // helpers
    static int countChorus(VoxelVolume v) {
        int n = 0; int e = v.extent();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int id = v.blockId(x, y, z);
            if (id == BLOCK_CHORUS_PLANT || id == BLOCK_CHORUS_FLOWER) n++;
        }
        return n;
    }
    static boolean isAllPurple(VoxelVolume v) {
        int e = v.extent();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int id = v.blockId(x, y, z);
            if (id != BLOCK_AIR && id != BLOCK_END_STONE && id != BLOCK_CHORUS_PLANT && id != BLOCK_CHORUS_FLOWER) return false;
        }
        return true;
    }
    static String safeName(int id) { try { return CanonicalRegistries.canonicalName(id); } catch (Exception e) { return "id:" + id; } }
    static void assertVolumesEqual(VoxelVolume expected, VoxelVolume actual, String msg) {
        assertEquals(expected.extent(), actual.extent(), msg + " extent");
        int e = expected.extent();
        java.util.List<String> diffs = new java.util.ArrayList<>();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            int eb = expected.blockId(x, y, z); int ab = actual.blockId(x, y, z);
            if (eb != ab && diffs.size() < 5) diffs.add(String.format("(%d,%d,%d) exp %d got %d", x, y, z, eb, ab));
        }
        assertTrue(diffs.isEmpty(), msg + " diffs: " + String.join(";", diffs));
    }
    private static VoxelVolume synthesizeBottomUpL1(SectionPos origin, long seed) {
        var refSynth = EndChorusSynthesizer.forTesting(seed);
        int baseX = origin.x() << 4; int baseY = origin.y() << 4; int baseZ = origin.z() << 4;
        final int regionBlocks = 64;
        int[] blocks = new int[regionBlocks * regionBlocks * regionBlocks];
        for (int y = 0; y < regionBlocks; y++) {
            int wy = baseY + y;
            for (int z = 0; z < regionBlocks; z++) {
                int wz = baseZ + z;
                for (int x = 0; x < regionBlocks; x++) {
                    int wx = baseX + x;
                    blocks[(y * regionBlocks + z) * regionBlocks + x] = refSynth.blockIdAt(wx, wy, wz);
                }
            }
        }
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int vy = 0; vy < 32; vy++) for (int vz = 0; vz < 32; vz++) for (int vx = 0; vx < 32; vx++) {
            int bx = vx * 2, by = vy * 2, bz = vz * 2;
            int[] eight = new int[8]; int idx = 0;
            for (int dy = 0; dy < 2; dy++) for (int dz = 0; dz < 2; dz++) for (int dx = 0; dx < 2; dx++) {
                eight[idx++] = blocks[((by + dy) * regionBlocks + (bz + dz)) * regionBlocks + (bx + dx)];
            }
            int mip = mipBlockId(eight);
            if (mip != 0) b.setBlock(vx, vy, vz, mip);
        }
        return b.build();
    }
    static int mipBlockId(int[] eightChildren) {
        int best = -1; int bestScore = -1;
        for (int i = 0; i < 8; i++) {
            int id = eightChildren[i]; if (id == 0) continue;
            int opacity = (id == 359) ? 15 : 0; int score = (opacity << 4) | i;
            if (score > bestScore) { bestScore = score; best = id; }
        }
        return best == -1 ? 0 : best;
    }
}
