package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class OracleContractValidationTest {

    @Test
    void tracerContractValidates() {
        OracleContract c = EndChorusTracerContract.contract();
        assertDoesNotThrow(c::validate);
        assertEquals("voxygen.oracle.contract.v3", c.schemaVersion());
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
        assertThrows(IllegalArgumentException.class, broken::validate);
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

    @Test
    void perLevelDispositionsAllowWorldgenPartitionDecisions() {
        OracleContract base = EndChorusTracerContract.contract();
        // Generic validator must allow OMIT, DETERMINISTIC, LEARNED_RESIDUAL etc. for #220 partition decisions
        for (String disp : new String[]{"UNRESOLVED", "OMIT", "DETERMINISTIC", "LEARNED_RESIDUAL", "LEARNED_FULL", "EXACT_PORT"}) {
            var pd = new OracleContract.PerLevelPartitionDecisions(
                    new OracleContract.PartitionDecision(disp, List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "test " + disp),
                    new OracleContract.PartitionDecision(disp, List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "test " + disp),
                    new OracleContract.PartitionDecision(disp, List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "test " + disp),
                    new OracleContract.PartitionDecision(disp, List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "test " + disp),
                    new OracleContract.PartitionDecision(disp, List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "test " + disp));
            OracleContract c = OracleContract.builder()
                    .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                    .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                    .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                    .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                    .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                    .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                    .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                    .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                    .perLevelDecisions(pd).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder()).build();
            assertDoesNotThrow(c::validate, "disposition " + disp + " must be allowed by generic validator");
        }
        // Invalid disposition must still fail
        var badPd = new OracleContract.PerLevelPartitionDecisions(
                new OracleContract.PartitionDecision("BOGUS", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "bad"),
                new OracleContract.PartitionDecision("UNRESOLVED", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "ok"),
                new OracleContract.PartitionDecision("UNRESOLVED", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "ok"),
                new OracleContract.PartitionDecision("UNRESOLVED", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "ok"),
                new OracleContract.PartitionDecision("UNRESOLVED", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), "ok"));
        assertThrows(IllegalArgumentException.class, () -> OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(badPd).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder()).build());
    }

    @Test
    void tracerContractCurrentlyUnresolved() {
        OracleContract c = EndChorusTracerContract.contract();
        // Tracer-specific assertion: current contract is UNRESOLVED at all Levels until #220 decides
        assertEquals("UNRESOLVED", c.perLevelDecisions().l4().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l3().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l2().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l1().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l0().disposition());
    }

    @Test
    void generationOrderMustBeSquaredDistance() {
        OracleContract base = EndChorusTracerContract.contract();
        assertThrows(IllegalArgumentException.class, () -> OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder("Morton sorted by distance to center of L4 rect, then server tick order").build());
    }
}

