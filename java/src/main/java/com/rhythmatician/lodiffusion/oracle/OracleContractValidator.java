package com.rhythmatician.lodiffusion.oracle;

import java.util.List;
import java.util.Objects;

public final class OracleContractValidator {
    private OracleContractValidator() {}

    public static void validate(OracleContract c) {
        Objects.requireNonNull(c, "contract");
        require(c.schemaVersion(), "schemaVersion");
        require(c.responsibilityId(), "responsibilityId");
        require(c.dimension(), "dimension");
        require(c.frozenWorldgenProfileId(), "frozenWorldgenProfileId");
        require(c.minecraftVersion(), "minecraftVersion");
        require(c.minecraftSourceRevision(), "minecraftSourceRevision");
        require(c.minecraftJarSha256(), "minecraftJarSha256");
        require(c.voxyVersion(), "voxyVersion");
        require(c.voxyCommit(), "voxyCommit");
        require(c.voxyArtifactSha256(), "voxyArtifactSha256");
        require(c.canonicalBlockRegistryVersion(), "canonicalBlockRegistryVersion");
        require(c.canonicalBlockRegistrySha256(), "canonicalBlockRegistrySha256");
        require(c.canonicalBiomeRegistryVersion(), "canonicalBiomeRegistryVersion");
        require(c.canonicalBiomeRegistrySha256(), "canonicalBiomeRegistrySha256");
        requireList(c.inspectedMinecraftReferences(), "inspectedMinecraftReferences");
        requireList(c.inspectedVoxyReferences(), "inspectedVoxyReferences");
        Objects.requireNonNull(c.region(), "region is required");
        Objects.requireNonNull(c.halo(), "halo is required");
        // New decomposed halo validation
        if (c.halo().featureReachBlocks() < 0) throw new IllegalArgumentException("halo.featureReachBlocks must be >=0");
        require(c.halo().featureReachEvidence(), "halo.featureReachEvidence");
        require(c.halo().featureReachSource(), "halo.featureReachSource");
        if (c.halo().minecraftGenerationHaloChunks() < 0) throw new IllegalArgumentException("halo.minecraftGenerationHaloChunks must be >=0");
        require(c.halo().minecraftGenerationHaloEvidence(), "halo.minecraftGenerationHaloEvidence");
        require(c.halo().minecraftGenerationHaloSource(), "halo.minecraftGenerationHaloSource");
        if (c.halo().voxyMipHaloBlocks() < 0) throw new IllegalArgumentException("halo.voxyMipHaloBlocks must be >=0");
        require(c.halo().voxyMipHaloEvidence(), "halo.voxyMipHaloEvidence");
        require(c.halo().voxyMipHaloSource(), "halo.voxyMipHaloSource");
        if (c.halo().combinedHaloBlocks() <= 0) throw new IllegalArgumentException("halo.combinedHaloBlocks must be >0, was " + c.halo().combinedHaloBlocks());
        // Legacy combined accessor still validated via above, but also check legacy path
        if (c.halo().haloBlocks() <= 0) throw new IllegalArgumentException("halo.haloBlocks must be >0");
        require(c.authoritativeGenerationStage(), "authoritativeGenerationStage");
        require(c.fixtureFormatVersion(), "fixtureFormatVersion");
        require(c.provenanceId(), "provenanceId");
        Objects.requireNonNull(c.perLevelDecisions(), "perLevelDecisions is required");
        requireDisposition(c.perLevelDecisions().l4(), "perLevelDecisions.l4");
        requireDisposition(c.perLevelDecisions().l3(), "perLevelDecisions.l3");
        requireDisposition(c.perLevelDecisions().l2(), "perLevelDecisions.l2");
        requireDisposition(c.perLevelDecisions().l1(), "perLevelDecisions.l1");
        requireDisposition(c.perLevelDecisions().l0(), "perLevelDecisions.l0");
        Objects.requireNonNull(c.roles(), "roles is required");
        require(c.roles().claimRole(), "roles.claimRole");
        require(c.roles().dependencyRole(), "roles.dependencyRole");
        require(c.roles().rationale(), "roles.rationale");
        Objects.requireNonNull(c.benchmarkPolicy(), "benchmarkPolicy is required");
        if (c.benchmarkPolicy().warmupIterations() < 0) throw new IllegalArgumentException("benchmarkPolicy.warmupIterations must be >=0");
        if (c.benchmarkPolicy().measurementIterations() <= 0) throw new IllegalArgumentException("benchmarkPolicy.measurementIterations must be >0");
        require(c.benchmarkPolicy().repetitionPolicy(), "benchmarkPolicy.repetitionPolicy");
        if (!c.dimension().startsWith("minecraft:")) throw new IllegalArgumentException("dimension must be minecraft:* key, was " + c.dimension());
        if (!isKnownStage(c.authoritativeGenerationStage())) throw new IllegalArgumentException("unknown authoritativeGenerationStage " + c.authoritativeGenerationStage());
    }

    private static void requireDisposition(OracleContract.PartitionDecision d, String field) {
        Objects.requireNonNull(d, field + " is required");
        require(d.disposition(), field + ".disposition");
        // Only allow known dispositions
        String disp = d.disposition();
        if (!disp.equals("UNRESOLVED") && !disp.equals("OMIT") && !disp.equals("DETERMINISTIC") && !disp.equals("LEARNED_RESIDUAL") && !disp.equals("LEARNED_FULL") && !disp.equals("EXACT_PORT") && !disp.equals("REUSE_VANILLA")) {
            throw new IllegalArgumentException(field + ".disposition must be UNRESOLVED/OMIT/DETERMINISTIC/LEARNED_RESIDUAL/LEARNED_FULL/EXACT_PORT/REUSE_VANILLA, was " + disp);
        }
        Objects.requireNonNull(d.candidates(), field + ".candidates");
        require(d.rationale(), field + ".rationale");
    }

    private static void require(String v, String field) {
        if (v == null || v.isBlank()) throw new IllegalArgumentException(field + " is required and must be non-blank");
    }
    private static void requireList(List<String> v, String field) {
        if (v == null || v.isEmpty()) throw new IllegalArgumentException(field + " is required and must be non-empty");
        for (String s : v) if (s == null || s.isBlank()) throw new IllegalArgumentException(field + " contains blank entry");
    }
    private static boolean isKnownStage(String s) {
        return switch (s) {
            case "EMPTY","STRUCTURE_STARTS","STRUCTURE_REFERENCES","BIOMES","NOISE","SURFACE","CARVERS","FEATURES","INITIALIZE_LIGHT","LIGHT","SPAWN","FULL" -> true;
            default -> false;
        };
    }
}
