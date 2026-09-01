package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import java.util.Objects;
import com.rhythmatician.voxygen.features.end.chorus.EndChorusSynthesizer;

/**
 * Reusable candidate verification surface that compares candidate semantic output
 * to an immutable oracle fixture at a named Level. Supports L4..L0 independently.
 *
 * <p>Candidate output remains semantic VoxelVolume; Voxy packed longs and YZX never leak.
 * Verifier does not calculate expected values using candidate production code (e.g. EndChorusSynthesizer).
 * Expected values come solely from the fixture which is the sole authority.
 *
 * <p>For this tracer the comparison is exact voxel equality. No chorus level is assumed to be
 * empty: real fixture capture determines L4..L0 content. If a Level's WorldSection lacks data,
 * fixture generation fails rather than synthesizing an empty volume, so verifier can require exact match.
 * Biome parity remains unclaimed (canonical 255) but diagnostic End biome names are preserved in fixture.
 */
public final class CandidateVerifier {
    private CandidateVerifier() {}

    public record VerificationResult(boolean passed, int mismatchedVoxels, int totalVoxels, String detail) {
        public boolean failed() { return !passed; }
    }

    /**
     * Verify candidate volume against fixture at level. Contract is validated first.
     * Requires {@code REAL_CAPTURE} evidence — synthetic fixtures cannot satisfy parity (ADR 0015).
     */
    public static VerificationResult verify(Level level, SectionPos origin, VoxelVolume candidate, OracleFixture fixture) {
        return verifyInternal(level, origin, candidate, fixture, true);
    }

    /**
     * Lenient verification for harness/structural tests that exercise verifier wiring
     * with synthetic fixtures. Not for parity claims.
     */
    public static VerificationResult verifyLenient(Level level, SectionPos origin, VoxelVolume candidate, OracleFixture fixture) {
        return verifyInternal(level, origin, candidate, fixture, false);
    }

    private static VerificationResult verifyInternal(Level level, SectionPos origin, VoxelVolume candidate, OracleFixture fixture, boolean requireRealCapture) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(candidate, "candidate");
        Objects.requireNonNull(fixture, "fixture");
        if (requireRealCapture && fixture.evidenceKind() != OracleFixture.EvidenceKind.REAL_CAPTURE) {
            throw new IllegalArgumentException("CandidateVerifier parity verification requires REAL_CAPTURE evidence, was " + fixture.evidenceKind() + " — synthetic test fixtures (SYNTHETIC_TEST) cannot satisfy ADR 0015 independent-oracle parity");
        }
        fixture.contract().validate();
        // Derive exact expected SectionPos for this Level from WorldSectionOrigin × level.regionSections() (per review)
        var br = fixture.contract().blockRegionOrDerived();
        var per = br.perLevelWorldSectionOrigin(level.value());
        int expectedSecX = per.wsX() * level.regionSections();
        int expectedSecY = per.wsY() * level.regionSections();
        int expectedSecZ = per.wsZ() * level.regionSections();
        SectionPos expectedOrigin = new SectionPos(expectedSecX, expectedSecY, expectedSecZ);
        if (!expectedOrigin.equals(origin)) {
            throw new IllegalArgumentException("origin " + origin + " != expected " + expectedOrigin + " for " + level + " derived from WorldSectionOrigin " + per + " × regionSections " + level.regionSections() + " (blockRegion " + br + ")");
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
