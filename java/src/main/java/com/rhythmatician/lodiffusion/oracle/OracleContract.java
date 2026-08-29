package com.rhythmatician.lodiffusion.oracle;

import java.util.List;
import java.util.Objects;

/**
 * Executable/versioned contract for the vanilla->Voxy oracle harness, tracer: End chorus.
 *
 * <p>All fields are required and validated before candidate correctness tests run.
 * See ADR 0015 (post-ingest Voxy mip parity is the default target) and
 * tmp/issue-233-instructions.md for provenance requirements.
 *
 * <p>Schema version is independent of fixture format; both are part of evidence identity.
 */
public record OracleContract(
        // schema
        String schemaVersion,
        // responsibility identity
        String responsibilityId,
        String dimension,
        String frozenWorldgenProfileId,
        // MC source identity
        String minecraftVersion,
        String minecraftSourceRevision,
        String minecraftJarSha256,
        // Voxy identity
        String voxyVersion,
        String voxyCommit,
        String voxyArtifactSha256,
        // registry identity
        String canonicalBlockRegistryVersion,
        String canonicalBlockRegistrySha256,
        String canonicalBiomeRegistryVersion,
        String canonicalBiomeRegistrySha256,
        // inspected source references (exact classes/methods)
        List<String> inspectedMinecraftReferences,
        List<String> inspectedVoxyReferences,
        // seed / region
        long seed,
        RegionSpec region,
        HaloSpec halo,
        String authoritativeGenerationStage,
        // fixture identity
        String fixtureFormatVersion,
        String oracleFixtureId,
        // per-Level disposition (L4..L0)
        PerLevelDisposition perLevelDisposition,
        // claim/dependency roles
        String claimRole,
        String dependencyRole,
        // benchmark/evidence hooks
        BenchmarkPolicy benchmarkPolicy
) {
    public static final String CURRENT_SCHEMA_VERSION = "voxygen.oracle.contract.v1";
    public static final String CURRENT_FIXTURE_FORMAT_VERSION = "voxygen.oracle.fixture.v1";

    public record RegionSpec(int originSectionX, int originSectionY, int originSectionZ, int extentSections) {}
    public record HaloSpec(int haloBlocks, String evidence, String source) {}
    public record PerLevelDisposition(
            String l4,
            String l3,
            String l2,
            String l1,
            String l0) {}
    public record BenchmarkPolicy(int warmupIterations, int measurementIterations, String repetitionPolicy) {}

    /** Validates required fields; throws IllegalArgumentException / NullPointerException with descriptive message. */
    public void validate() {
        OracleContractValidator.validate(this);
    }

    public static Builder builder() { return new Builder(); }

    public static final class Builder {
        private String schemaVersion = CURRENT_SCHEMA_VERSION;
        private String responsibilityId;
        private String dimension;
        private String frozenWorldgenProfileId;
        private String minecraftVersion;
        private String minecraftSourceRevision;
        private String minecraftJarSha256;
        private String voxyVersion;
        private String voxyCommit;
        private String voxyArtifactSha256;
        private String canonicalBlockRegistryVersion;
        private String canonicalBlockRegistrySha256;
        private String canonicalBiomeRegistryVersion;
        private String canonicalBiomeRegistrySha256;
        private List<String> inspectedMinecraftReferences;
        private List<String> inspectedVoxyReferences;
        private Long seed;
        private RegionSpec region;
        private HaloSpec halo;
        private String authoritativeGenerationStage;
        private String fixtureFormatVersion = CURRENT_FIXTURE_FORMAT_VERSION;
        private String oracleFixtureId;
        private PerLevelDisposition perLevelDisposition;
        private String claimRole;
        private String dependencyRole;
        private BenchmarkPolicy benchmarkPolicy;

        public Builder schemaVersion(String v) { this.schemaVersion = v; return this; }
        public Builder responsibilityId(String v) { this.responsibilityId = v; return this; }
        public Builder dimension(String v) { this.dimension = v; return this; }
        public Builder frozenWorldgenProfileId(String v) { this.frozenWorldgenProfileId = v; return this; }
        public Builder minecraftVersion(String v) { this.minecraftVersion = v; return this; }
        public Builder minecraftSourceRevision(String v) { this.minecraftSourceRevision = v; return this; }
        public Builder minecraftJarSha256(String v) { this.minecraftJarSha256 = v; return this; }
        public Builder voxyVersion(String v) { this.voxyVersion = v; return this; }
        public Builder voxyCommit(String v) { this.voxyCommit = v; return this; }
        public Builder voxyArtifactSha256(String v) { this.voxyArtifactSha256 = v; return this; }
        public Builder canonicalBlockRegistryVersion(String v) { this.canonicalBlockRegistryVersion = v; return this; }
        public Builder canonicalBlockRegistrySha256(String v) { this.canonicalBlockRegistrySha256 = v; return this; }
        public Builder canonicalBiomeRegistryVersion(String v) { this.canonicalBiomeRegistryVersion = v; return this; }
        public Builder canonicalBiomeRegistrySha256(String v) { this.canonicalBiomeRegistrySha256 = v; return this; }
        public Builder inspectedMinecraftReferences(List<String> v) { this.inspectedMinecraftReferences = v; return this; }
        public Builder inspectedVoxyReferences(List<String> v) { this.inspectedVoxyReferences = v; return this; }
        public Builder seed(long v) { this.seed = v; return this; }
        public Builder region(RegionSpec v) { this.region = v; return this; }
        public Builder halo(HaloSpec v) { this.halo = v; return this; }
        public Builder authoritativeGenerationStage(String v) { this.authoritativeGenerationStage = v; return this; }
        public Builder fixtureFormatVersion(String v) { this.fixtureFormatVersion = v; return this; }
        public Builder oracleFixtureId(String v) { this.oracleFixtureId = v; return this; }
        public Builder perLevelDisposition(PerLevelDisposition v) { this.perLevelDisposition = v; return this; }
        public Builder claimRole(String v) { this.claimRole = v; return this; }
        public Builder dependencyRole(String v) { this.dependencyRole = v; return this; }
        public Builder benchmarkPolicy(BenchmarkPolicy v) { this.benchmarkPolicy = v; return this; }

        public OracleContract build() {
            OracleContract c = new OracleContract(
                    schemaVersion, responsibilityId, dimension, frozenWorldgenProfileId,
                    minecraftVersion, minecraftSourceRevision, minecraftJarSha256,
                    voxyVersion, voxyCommit, voxyArtifactSha256,
                    canonicalBlockRegistryVersion, canonicalBlockRegistrySha256,
                    canonicalBiomeRegistryVersion, canonicalBiomeRegistrySha256,
                    inspectedMinecraftReferences, inspectedVoxyReferences,
                    seed == null ? 0L : seed, region, halo, authoritativeGenerationStage,
                    fixtureFormatVersion, oracleFixtureId, perLevelDisposition, claimRole, dependencyRole, benchmarkPolicy
            );
            c.validate();
            if (seed == null) throw new IllegalArgumentException("seed is required");
            return c;
        }
    }
}
