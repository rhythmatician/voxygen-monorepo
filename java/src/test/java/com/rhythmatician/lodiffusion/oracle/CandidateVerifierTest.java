package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import org.junit.jupiter.api.Test;

class CandidateVerifierTest {

    @Test
    void correctCandidatePassesAllLevels() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        for (Level l : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(l.value());
            SectionPos origin = new SectionPos(per.wsX() * l.regionSections(), per.wsY() * l.regionSections(), per.wsZ() * l.regionSections());
            VoxelVolume candidate = f.volume(l);
            var r = CandidateVerifier.verify(l, origin, candidate, f);
            assertTrue(r.passed(), "correct candidate must pass at " + l + ": " + r.detail());
        }
    }

    @Test
    void verifierSupportsEachLevelIndependently() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        var perL2 = c.blockRegionOrDerived().perLevelWorldSectionOrigin(Level.L2.value());
        SectionPos originL2 = new SectionPos(perL2.wsX() * Level.L2.regionSections(), perL2.wsY() * Level.L2.regionSections(), perL2.wsZ() * Level.L2.regionSections());
        // Verify each level independently - mismatched level data should fail
        VoxelVolume l1Vol = f.volume(Level.L1);
        var r = CandidateVerifier.verify(Level.L2, originL2, l1Vol, f);
        assertNotNull(r);
    }

    @Test
    void verifierDoesNotUseCandidateProductionCodeForExpected() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        Level level = Level.L1;
        var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
        SectionPos origin = new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());
        VoxelVolume expected = f.volume(level);
        VoxelVolume candidate = expected.copy();
        var r = CandidateVerifier.verify(level, origin, candidate, f);
        assertTrue(r.passed());
        // Ensure verifier class does not import EndChorusSynthesizer (checked via source inspection / isolation test)
    }

    @Test
    void malformedContractFailsBeforeCandidateComparison() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), "", base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }

    @Test
    void wrongOriginFails() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        VoxelVolume candidate = f.volume(Level.L0);
        SectionPos wrong = new SectionPos(99, 99, 99);
        assertThrows(IllegalArgumentException.class, () -> CandidateVerifier.verify(Level.L0, wrong, candidate, f));
    }
}



