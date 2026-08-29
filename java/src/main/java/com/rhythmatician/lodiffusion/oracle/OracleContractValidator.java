package com.rhythmatician.lodiffusion.oracle;

import java.util.List;
import java.util.Objects;

/**
 * Validator for {@link OracleContract}. Fails closed on any malformed or incomplete contract
 * before candidate correctness tests run. No thresholds invented here.
 */
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
        require(c.halo().evidence(), "halo.evidence");
        require(c.halo().source(), "halo.source");
        if (c.halo().haloBlocks() <= 0) throw new IllegalArgumentException("halo.haloBlocks must be >0, was " + c.halo().haloBlocks());
        require(c.authoritativeGenerationStage(), "authoritativeGenerationStage");
        require(c.fixtureFormatVersion(), "fixtureFormatVersion");
        require(c.oracleFixtureId(), "oracleFixtureId");
        Objects.requireNonNull(c.perLevelDisposition(), "perLevelDisposition is required");
        require(c.perLevelDisposition().l4(), "perLevelDisposition.l4");
        require(c.perLevelDisposition().l3(), "perLevelDisposition.l3");
        require(c.perLevelDisposition().l2(), "perLevelDisposition.l2");
        require(c.perLevelDisposition().l1(), "perLevelDisposition.l1");
        require(c.perLevelDisposition().l0(), "perLevelDisposition.l0");
        require(c.claimRole(), "claimRole");
        require(c.dependencyRole(), "dependencyRole");
        Objects.requireNonNull(c.benchmarkPolicy(), "benchmarkPolicy is required");
        if (c.benchmarkPolicy().warmupIterations() < 0) throw new IllegalArgumentException("benchmarkPolicy.warmupIterations must be >=0");
        if (c.benchmarkPolicy().measurementIterations() <= 0) throw new IllegalArgumentException("benchmarkPolicy.measurementIterations must be >0");
        require(c.benchmarkPolicy().repetitionPolicy(), "benchmarkPolicy.repetitionPolicy");
        if (!c.dimension().startsWith("minecraft:")) throw new IllegalArgumentException("dimension must be minecraft:* key, was " + c.dimension());
        if (!isKnownStage(c.authoritativeGenerationStage())) throw new IllegalArgumentException("unknown authoritativeGenerationStage " + c.authoritativeGenerationStage());
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
