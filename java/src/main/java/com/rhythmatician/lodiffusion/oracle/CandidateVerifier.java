package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.util.Objects;

/**
 * Reusable candidate verification surface that compares candidate semantic output
 * to an immutable oracle fixture at a named Level. Supports L4..L0 independently.
 *
 * <p>Candidate output remains semantic VoxelVolume; Voxy packed longs and YZX never leak.
 * Verifier does not calculate expected values using candidate production code (e.g. EndChorusSynthesizer).
 * Expected values come solely from the fixture.
 *
 * <p>For this tracer the comparison is exact voxel equality filtered through honest-omission
 * semantics (L4/L3 chorus=0) is not claimed as exact identity but still compared to fixture's
 * omission-correct volume. Richer topology/material metrics can plug in later without changing oracle authority.
 */
public final class CandidateVerifier {
    private CandidateVerifier() {}

    public record VerificationResult(boolean passed, int mismatchedVoxels, int totalVoxels, String detail) {
        public boolean failed() { return !passed; }
    }

    /**
     * Verify candidate volume against fixture at level. Contract is validated first.
     */
    public static VerificationResult verify(Level level, SectionPos origin, VoxelVolume candidate, OracleFixture fixture) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fixture, "fixture");
        fixture.contract().validate();
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException("origin " + origin + " not aligned to " + level);
        }
        SectionPos expectedOrigin = fixture.origin();
        if (!expectedOrigin.equals(origin)) {
            throw new IllegalArgumentException("candidate origin " + origin + " != fixture origin " + expectedOrigin);
        }
        if (candidate.extent() != 32) throw new IllegalArgumentException("candidate extent must be 32, was " + candidate.extent());
        VoxelVolume expected = fixture.volume(level);
        int mism = 0;
        int total = 32 * 32 * 32;
        int firstX = -1, firstY = -1, firstZ = -1;
        int expBlock = -1, gotBlock = -1;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int e = expected.blockId(x, y, z);
                    int g = candidate.blockId(x, y, z);
                    if (e != g) {
                        mism++;
                        if (firstX == -1) { firstX = x; firstY = y; firstZ = z; expBlock = e; gotBlock = g; }
                    }
                }
            }
        }
        boolean passed = mism == 0;
        String detail = passed ? "exact match at " + level
                : String.format("mismatch %d/%d at %s first @(%d,%d,%d) expected=%d got=%d", mism, total, level, firstX, firstY, firstZ, expBlock, gotBlock);
        return new VerificationResult(passed, mism, total, detail);
    }

    /**
     * Assert exact match, throwing AssertionError with detail if mismatched. Convenience for tests.
     */
    public static void assertMatches(Level level, SectionPos origin, VoxelVolume candidate, OracleFixture fixture) {
        VerificationResult r = verify(level, origin, candidate, fixture);
        if (!r.passed()) throw new AssertionError(r.detail());
    }
}
