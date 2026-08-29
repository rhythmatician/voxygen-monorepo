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
        // Per-Level WorldSection origin derived independently from blockRegion (floorDiv), not a single SectionPos for all Levels
        var br = fixture.contract().blockRegionOrDerived();
        var expectedPerLevel = br.perLevelWorldSectionOrigin(level.value());
        // Convert expectedPerLevel back to SectionPos for comparison (origin is SectionPos at 16-block granularity)
        // For L0, blockRegion origin (1536,64,0) -> SectionPos 96,4,0 -> L0 ws 48,2,0 -> but SectionPos origin for L0 is 96,4,0, for L2 it's 12,0,0? Wait: per-Level SectionPos origin is not directly comparable to SectionPos origin
        // Instead, we verify that the passed origin matches the fixture's SectionPos origin (which is the L0 origin), and that L0 origin is aligned to the requested level's grid when considered as block origin
        // To support per-Level independent derivation, we allow any origin that matches the fixture's SectionPos origin, and we check alignment against the Level's regionSections using the blockRegion's per-Level derivation
        // If the verifier is called with the fixture's SectionPos origin (e.g., 96,4,0), it will be aligned to L0/L1 but not to L2/L4. We therefore derive the expected SectionPos for this level from the blockRegion
        // and compare the candidate's origin to that derived SectionPos, OR allow the L0 origin as alias for all levels (conservative)
        SectionPos expectedOrigin = fixture.origin();
        // Derive expected SectionPos for this level from blockRegion
        int wsBlockSize = 32 * (1 << level.value());
        int expectedSecX = Math.floorDiv(br.originBlockX(), 16);
        int expectedSecY = Math.floorDiv(br.originBlockY(), 16);
        int expectedSecZ = Math.floorDiv(br.originBlockZ(), 16);
        // For coarse levels, the expected SectionPos for the verifier is still the L0 SectionPos (96,4,0), but its alignment check should be against the per-Level WorldSection grid, not the SectionPos grid
        // We therefore relax the alignment check: the blockRegion's origin must be aligned to the Level's WorldSection grid, not the SectionPos origin
        // Check that blockRegion's per-Level WorldSection contains the SectionPos origin
        int derivedWsX = Math.floorDiv(br.originBlockX(), wsBlockSize);
        int derivedWsY = Math.floorDiv(br.originBlockY(), wsBlockSize);
        int derivedWsZ = Math.floorDiv(br.originBlockZ(), wsBlockSize);
        int originWsX = Math.floorDiv(origin.x()*16, wsBlockSize);
        int originWsY = Math.floorDiv(origin.y()*16, wsBlockSize);
        int originWsZ = Math.floorDiv(origin.z()*16, wsBlockSize);
        if (derivedWsX != originWsX || derivedWsY != originWsY || derivedWsZ != originWsZ) {
            throw new IllegalArgumentException("origin " + origin + " WorldSection at " + level + " [" + originWsX + "," + originWsY + "," + originWsZ + "] != expected derived from blockRegion " + br + " [" + derivedWsX + "," + derivedWsY + "," + derivedWsZ + "]");
        }
        if (!expectedOrigin.equals(origin)) {
            // Allow alias where origin matches fixture's SectionPos origin (which is the L0 origin) - already checked via WorldSection containment above
            // If origins differ but WorldSections match, we still require exact SectionPos match for strictness, but for per-Level we allow the L0 origin
            // For now, require exact match to keep existing tests strict, but the WorldSection check above ensures per-Level correctness
            // If the test passes the L0 origin for L2, the WorldSection check will fail as above, so we need to allow L0 origin for coarse levels
            // Therefore, we only enforce exact match when Level==L0, otherwise allow any origin that maps to same WorldSection
            if (level != Level.L0 && derivedWsX == originWsX && derivedWsY == originWsY && derivedWsZ == originWsZ) {
                // allow L0 origin for coarse levels
            } else {
                throw new IllegalArgumentException("candidate origin " + origin + " != fixture origin " + expectedOrigin + " and not same WorldSection at " + level);
            }
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
