package com.rhythmatician.lodiffusion.oracle;

import java.util.List;
import java.util.Objects;

/**
 * Executable/versioned contract for the vanilla->Voxy oracle harness, tracer: End chorus.
 *
 * <p>v3: block-space tracer (outer-island END_HIGHLANDS) + decomposed halo + per-Level WorldSection origins derived independently via floorDiv(blockOrigin, 32<<L).
 *
 * <p>All fields are required and validated before candidate correctness tests run.
 * See ADR 0015 (post-ingest Voxy mip parity is the default target).
 */
public record OracleContract(
        String schemaVersion,
        String responsibilityId,
        String dimension,
        String frozenWorldgenProfileId,
        String minecraftVersion,
        String minecraftSourceRevision,
        String minecraftJarSha256,
        String voxyVersion,
        String voxyCommit,
        String voxyArtifactSha256,
        String canonicalBlockRegistryVersion,
        String canonicalBlockRegistrySha256,
        String canonicalBiomeRegistryVersion,
        String canonicalBiomeRegistrySha256,
        List<String> inspectedMinecraftReferences,
        List<String> inspectedVoxyReferences,
        long seed,
        RegionSpec region,
        BlockRegionSpec blockRegion,
        HaloSpec halo,
        String authoritativeGenerationStage,
        String fixtureFormatVersion,
        String provenanceId,
        PerLevelPartitionDecisions perLevelDecisions,
        ClaimDependencyRoles roles,
        BenchmarkPolicy benchmarkPolicy
) {
    // Legacy 18-arg constructor for tests that still pass region+halo without explicit blockRegion (derives blockRegion from region)
    public OracleContract(
            String schemaVersion,
            String responsibilityId,
            String dimension,
            String frozenWorldgenProfileId,
            String minecraftVersion,
            String minecraftSourceRevision,
            String minecraftJarSha256,
            String voxyVersion,
            String voxyCommit,
            String voxyArtifactSha256,
            String canonicalBlockRegistryVersion,
            String canonicalBlockRegistrySha256,
            String canonicalBiomeRegistryVersion,
            String canonicalBiomeRegistrySha256,
            java.util.List<String> a,
            java.util.List<String> b,
            long seed,
            RegionSpec region,
            HaloSpec halo,
            String authoritativeGenerationStage,
            String fixtureFormatVersion,
            String provenanceId,
            PerLevelPartitionDecisions perLevelDecisions,
            ClaimDependencyRoles roles,
            BenchmarkPolicy benchmarkPolicy) {
        this(schemaVersion, responsibilityId, dimension, frozenWorldgenProfileId,
             minecraftVersion, minecraftSourceRevision, minecraftJarSha256,
             voxyVersion, voxyCommit, voxyArtifactSha256,
             canonicalBlockRegistryVersion, canonicalBlockRegistrySha256,
             canonicalBiomeRegistryVersion, canonicalBiomeRegistrySha256,
             a, b, seed, region, region != null ? BlockRegionSpec.fromSectionSpec(region) : null, halo,
             authoritativeGenerationStage, fixtureFormatVersion, provenanceId,
             perLevelDecisions, roles, benchmarkPolicy);
    }

    public static final String CURRENT_SCHEMA_VERSION = "voxygen.oracle.contract.v3";
    public static final String CURRENT_FIXTURE_FORMAT_VERSION = "voxygen.oracle.fixture.v3";

    // Legacy accessor for tests that still use oracleFixtureId name
    public String oracleFixtureId() { return provenanceId(); }

    public record RegionSpec(int originSectionX, int originSectionY, int originSectionZ, int extentSections) {}
    /** Block-space tracer region: origin in blocks + extent in blocks (typically 32 for L0 tracer). Per-Level WorldSection origins are derived independently via floorDiv. */
    public record BlockRegionSpec(int originBlockX, int originBlockY, int originBlockZ, int extentBlocks) {
        public static BlockRegionSpec fromSectionSpec(RegionSpec s) {
            return new BlockRegionSpec(s.originSectionX()*16, s.originSectionY()*16, s.originSectionZ()*16, s.extentSections()*16);
        }
        public SectionPosOrigin perLevelWorldSectionOrigin(int level) {
            int wsBlockSize = 32 * (1 << level);
            int wsX = Math.floorDiv(originBlockX, wsBlockSize);
            int wsY = Math.floorDiv(originBlockY, wsBlockSize);
            int wsZ = Math.floorDiv(originBlockZ, wsBlockSize);
            return new SectionPosOrigin(wsX, wsY, wsZ, level, wsBlockSize);
        }
        /** Chunk rectangle (inclusive) covering blockRegion + combinedHaloBlocks, for ingest. */
        public int[] chunkRectWithHalo(int combinedHaloBlocks) {
            int minBx = originBlockX - combinedHaloBlocks;
            int minBz = originBlockZ - combinedHaloBlocks;
            int maxBx = originBlockX + extentBlocks -1 + combinedHaloBlocks;
            int maxBz = originBlockZ + extentBlocks -1 + combinedHaloBlocks;
            int minCx = Math.floorDiv(minBx, 16);
            int minCz = Math.floorDiv(minBz, 16);
            int maxCx = Math.floorDiv(maxBx, 16);
            int maxCz = Math.floorDiv(maxBz, 16);
            return new int[]{minCx, minCz, maxCx, maxCz};
        }
    }
    public record SectionPosOrigin(int wsX, int wsY, int wsZ, int level, int blockSize) {}
    public record HaloSpec(
            int featureReachBlocks,
            String featureReachEvidence,
            String featureReachSource,
            int minecraftGenerationHaloChunks,
            String minecraftGenerationHaloEvidence,
            String minecraftGenerationHaloSource,
            int voxyMipHaloBlocks,
            String voxyMipHaloEvidence,
            String voxyMipHaloSource,
            int combinedHaloBlocks
    ) {
        // Legacy accessor
        public int haloBlocks() { return combinedHaloBlocks(); }
        public String evidence() { return featureReachEvidence() + " | " + minecraftGenerationHaloEvidence() + " | " + voxyMipHaloEvidence(); }
        public String source() { return featureReachSource() + " + " + minecraftGenerationHaloSource() + " + " + voxyMipHaloSource(); }
    }
    public record PartitionDecision(String disposition, java.util.List<String> candidates, String rationale) {
        public static PartitionDecision unresolved(String rationale) {
            return new PartitionDecision("UNRESOLVED", List.of("OMIT","DETERMINISTIC","LEARNED_RESIDUAL","LEARNED_FULL","EXACT_PORT"), rationale);
        }
    }
    public record PerLevelPartitionDecisions(
            PartitionDecision l4,
            PartitionDecision l3,
            PartitionDecision l2,
            PartitionDecision l1,
            PartitionDecision l0) {}
    // Legacy name for compatibility
    public PerLevelPartitionDecisions perLevelDisposition() { return perLevelDecisions(); }
    public record ClaimDependencyRoles(String claimRole, String dependencyRole, String rationale) {}
    // Legacy accessors
    public String claimRole() { return roles().claimRole(); }
    public String dependencyRole() { return roles().dependencyRole(); }

    public record BenchmarkPolicy(int warmupIterations, int measurementIterations, String repetitionPolicy) {}

    public BlockRegionSpec blockRegionOrDerived() { return blockRegion != null ? blockRegion : (region!=null ? BlockRegionSpec.fromSectionSpec(region) : null); }
    public SectionPosOrigin perLevelOrigin(int level) { return blockRegionOrDerived().perLevelWorldSectionOrigin(level); }
    public int[] chunkRectWithHalo() { return blockRegionOrDerived().chunkRectWithHalo(halo.combinedHaloBlocks()); }
    public void validate() { OracleContractValidator.validate(this); }
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
        private BlockRegionSpec blockRegion;
        private HaloSpec halo;
        private String authoritativeGenerationStage;
        private String fixtureFormatVersion = CURRENT_FIXTURE_FORMAT_VERSION;
        private String provenanceId;
        private PerLevelPartitionDecisions perLevelDecisions;
        private ClaimDependencyRoles roles;
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
        public Builder region(RegionSpec v) { this.region = v; if (this.blockRegion==null && v!=null) this.blockRegion = BlockRegionSpec.fromSectionSpec(v); return this; }
        public Builder blockRegion(BlockRegionSpec v) { this.blockRegion = v; return this; }
        public Builder halo(HaloSpec v) { this.halo = v; return this; }
        public Builder authoritativeGenerationStage(String v) { this.authoritativeGenerationStage = v; return this; }
        public Builder fixtureFormatVersion(String v) { this.fixtureFormatVersion = v; return this; }
        public Builder provenanceId(String v) { this.provenanceId = v; return this; }
        public Builder oracleFixtureId(String v) { this.provenanceId = v; return this; }
        public Builder perLevelDecisions(PerLevelPartitionDecisions v) { this.perLevelDecisions = v; return this; }
        public Builder perLevelDisposition(PerLevelPartitionDecisions v) { this.perLevelDecisions = v; return this; }
        // Legacy compatibility for old tests that pass PerLevelDisposition as strings
        public Builder perLevelDisposition(PerLevelDisposition old) {
            // Map old omit/claim to UNRESOLVED with rationale
            this.perLevelDecisions = new PerLevelPartitionDecisions(
                PartitionDecision.unresolved("migrated from v1 "+old.l4()),
                PartitionDecision.unresolved("migrated from v1 "+old.l3()),
                PartitionDecision.unresolved("migrated from v1 "+old.l2()),
                PartitionDecision.unresolved("migrated from v1 "+old.l1()),
                PartitionDecision.unresolved("migrated from v1 "+old.l0())
            );
            return this;
        }
        public Builder perLevelDisposition(String l4,String l3,String l2,String l1,String l0) {
            this.perLevelDecisions = new PerLevelPartitionDecisions(
                new PartitionDecision(l4, List.of(), "legacy"),
                new PartitionDecision(l3, List.of(), "legacy"),
                new PartitionDecision(l2, List.of(), "legacy"),
                new PartitionDecision(l1, List.of(), "legacy"),
                new PartitionDecision(l0, List.of(), "legacy")
            );
            return this;
        }
        public Builder roles(ClaimDependencyRoles v) { this.roles = v; return this; }
        public Builder claimRole(String v) {
            if (this.roles == null) this.roles = new ClaimDependencyRoles(v, "", "legacy claim");
            else this.roles = new ClaimDependencyRoles(v, this.roles.dependencyRole(), this.roles.rationale());
            return this;
        }
        public Builder dependencyRole(String v) {
            if (this.roles == null) this.roles = new ClaimDependencyRoles("", v, "legacy dependency");
            else this.roles = new ClaimDependencyRoles(this.roles.claimRole(), v, this.roles.rationale());
            return this;
        }
        public Builder benchmarkPolicy(BenchmarkPolicy v) { this.benchmarkPolicy = v; return this; }

        // Legacy compatibility record for old tests
        public record PerLevelDisposition(String l4,String l3,String l2,String l1,String l0) {}

        public OracleContract build() {
            if (this.roles == null) this.roles = new ClaimDependencyRoles("","", "");
            if (region==null && blockRegion!=null) {
                int sx = Math.floorDiv(blockRegion.originBlockX(), 16);
                int sy = Math.floorDiv(blockRegion.originBlockY(), 16);
                int sz = Math.floorDiv(blockRegion.originBlockZ(), 16);
                int ext = Math.max(1, (blockRegion.extentBlocks()+15)/16);
                region = new RegionSpec(sx, sy, sz, ext);
            }
            if (blockRegion==null && region!=null) blockRegion = BlockRegionSpec.fromSectionSpec(region);
            OracleContract c = new OracleContract(
                schemaVersion, responsibilityId, dimension, frozenWorldgenProfileId,
                minecraftVersion, minecraftSourceRevision, minecraftJarSha256,
                voxyVersion, voxyCommit, voxyArtifactSha256,
                canonicalBlockRegistryVersion, canonicalBlockRegistrySha256,
                canonicalBiomeRegistryVersion, canonicalBiomeRegistrySha256,
                inspectedMinecraftReferences, inspectedVoxyReferences,
                seed == null ? 0L : seed, region, blockRegion, halo, authoritativeGenerationStage,
                fixtureFormatVersion, provenanceId, perLevelDecisions, roles, benchmarkPolicy
            );
            c.validate();
            if (seed == null) throw new IllegalArgumentException("seed is required");
            return c;
        }
    }
}

