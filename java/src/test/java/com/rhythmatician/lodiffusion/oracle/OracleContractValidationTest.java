package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import java.util.List;
import org.junit.jupiter.api.Test;

class OracleContractValidationTest {

    @Test
    void tracerContractValidates() {
        OracleContract c = EndChorusTracerContract.contract();
        assertDoesNotThrow(c::validate);
    }

    @Test
    void missingResponsibilityFails() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract broken = new OracleContract(
                base.schemaVersion(), null, base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.seed(), base.region(), new OracleContract.HaloSpec(0, "ev", "src"), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
        assertDoesNotThrow(broken::validate);
        assertThrows(IllegalArgumentException.class, () -> VanillaVoxyOracle.generateSyntheticTracerFixture(broken));
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), null,
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
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
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, broken::validate);
    }
}
