package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class OracleContractValidationTest {

    @Test
    void tracerContractValidates() {
        OracleContract c = EndChorusTracerContract.contract();
        assertDoesNotThrow(c::validate);
        assertEquals("voxygen.oracle.contract.v2", c.schemaVersion());
        assertEquals(8, c.halo().featureReachBlocks());
        assertEquals(1, c.halo().minecraftGenerationHaloChunks());
        assertEquals(1, c.halo().voxyMipHaloBlocks());
        assertEquals(25, c.halo().combinedHaloBlocks());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l4().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l0().disposition());
    }

    @Test
    void missingResponsibilityFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract bad = new OracleContract(
                base.schemaVersion(), null, base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, bad::validate);
    }

    @Test
    void missingDimensionFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), "", base.frozenWorldgenProfileId(),
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
    void missingHaloFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), null, base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(NullPointerException.class, broken::validate);
    }

    @Test
    void zeroHaloFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), new OracleContract.HaloSpec(0, "ev", "src", 0, "ev", "src", 0, "ev", "src", 0), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }

    @Test
    void missingVoxyShaFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), "",
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }

    @Test
    void missingInspectedVoxyRefsFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), List.of(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }

    @Test
    void wrongStageFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), "NOISE",
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertDoesNotThrow(broken::validate);
        // Synthetic factory requires FEATURES, so it should fail
        assertThrows(IllegalArgumentException.class, () -> com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(broken));
    }

    @Test
    void missingPerLevelFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), null, base.roles(), base.benchmarkPolicy());
        assertThrows(NullPointerException.class, broken::validate);
    }

    @Test
    void missingRegistryHashFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                "", base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }

    @Test
    void featureHaloMustBeExplicit() {
        OracleContract base = EndChorusTracerContract.contract();
        // Feature reach must be >=0 and evidence non-blank; test blank evidence fails
        OracleContract broken = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), new OracleContract.HaloSpec(8, "", "src", 1, "ev", "src", 1, "ev", "src", 10), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }
}

