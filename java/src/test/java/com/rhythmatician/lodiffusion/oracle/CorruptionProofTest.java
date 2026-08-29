package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import org.junit.jupiter.api.Test;

/**
 * Corruption proof: deliberately corrupted candidates that would have passed
 * old PR #231-style self-referential tests (expected=candidate(...)) must fail
 * against the immutable fixture. Proves expected values cannot secretly come from candidate.
 */
class CorruptionProofTest {

    @Test
    void shiftedChorusFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        Level level = Level.L1;
        VoxelVolume correct = f.volume(level);
        // Shift chorus occupancy spatially by +1 in X (if possible)
        VoxelVolume.Builder shifted = VoxelVolume.builder(32);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = correct.blockId(x, y, z);
            if (id == 196 || id == 197) {
                // move chorus +1 X if in bounds, else leave air
                if (x + 1 < 32) shifted.setBlock(x + 1, y, z, id);
            } else if (id != 0) {
                shifted.setBlock(x, y, z, id);
            }
        }
        VoxelVolume corrupted = shifted.build();
        var r = CandidateVerifier.verify(level, origin, corrupted, f);
        assertTrue(r.failed(), "spatially shifted chorus must fail, but passed with detail: " + r.detail());
        assertTrue(r.mismatchedVoxels() > 0);
    }

    @Test
    void erasedChorusFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        Level level = Level.L2;
        VoxelVolume correct = f.volume(level);
        // Erase all chorus
        VoxelVolume.Builder erased = VoxelVolume.builder(32);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = correct.blockId(x, y, z);
            if (id == 196 || id == 197) {
                // erase -> air (0, default)
            } else if (id != 0) {
                erased.setBlock(x, y, z, id);
            }
        }
        VoxelVolume corrupted = erased.build();
        var r = CandidateVerifier.verify(level, origin, corrupted, f);
        assertTrue(r.failed(), "erased chorus must fail");
    }

    @Test
    void wrongMaterialFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        Level level = Level.L0;
        VoxelVolume correct = f.volume(level);
        // Substitute chorus_plant (197) with end_stone (359) -- wrong canonical material
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        boolean substituted = false;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = correct.blockId(x, y, z);
            if (!substituted && id == 197) {
                b.setBlock(x, y, z, 359);
                substituted = true;
            } else if (id != 0) {
                b.setBlock(x, y, z, id);
            }
        }
        assertTrue(substituted, "fixture must contain chorus_plant to test substitution");
        var r = CandidateVerifier.verify(level, origin, b.build(), f);
        assertTrue(r.failed(), "wrong material must fail");
    }

    @Test
    void edgeCrossingCorruptionFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        Level level = Level.L1;
        VoxelVolume correct = f.volume(level);
        // Perturb the edge-crossing chorus at +X boundary (31, mid, mid) that fixture intentionally places
        VoxelVolume.Builder b = VoxelVolume.builder(32);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) b.setBiome(x, y, z, 255);
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = correct.blockId(x, y, z);
            if (id != 0) b.setBlock(x, y, z, id);
        }
        // Corrupt the edge chorus by moving it inward by 1 (simulates missing halo)
        int mid = 16;
        int edgeX = 31;
        int edgeId = correct.blockId(edgeX, mid, mid);
        if (edgeId != 0) {
            b.setBlock(edgeX, mid, mid, 0); // erase edge
            b.setBlock(edgeX - 1, mid, mid, edgeId); // inward
        }
        var r = CandidateVerifier.verify(level, origin, b.build(), f);
        assertTrue(r.failed(), "edge-crossing perturbation must fail; detail: " + r.detail());
    }

    @Test
    void trivialNonsenseFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        SectionPos origin = f.origin();
        VoxelVolume nonsense = VoxelVolume.uniform(32, 1, 255); // all acacia_button -- nonsense
        var r = CandidateVerifier.verify(Level.L0, origin, nonsense, f);
        assertTrue(r.failed());
    }
}


