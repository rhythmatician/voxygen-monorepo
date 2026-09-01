package com.rhythmatician.lodiffusion.oracle;

import java.util.Objects;
import com.rhythmatician.voxygen.semantic.Level;

public final class OracleContractValidator {
    private OracleContractValidator() {}

    public static void validate(OracleContract c) {
        Objects.requireNonNull(c, "contract");
        requireNonBlank(c.schemaVersion(), "schemaVersion");
        requireNonBlank(c.responsibilityId(), "responsibilityId");
        requireNonBlank(c.dimension(), "dimension");
        if (!"minecraft:the_end".equals(c.dimension())) throw new IllegalArgumentException("dimension must be minecraft:the_end for chorus tracer, was " + c.dimension());
        requireNonBlank(c.frozenWorldgenProfileId(), "frozenWorldgenProfileId");
        requireNonBlank(c.minecraftVersion(), "minecraftVersion");
        requireNonBlank(c.minecraftSourceRevision(), "minecraftSourceRevision");
        requireNonBlank(c.minecraftJarSha256(), "minecraftJarSha256");
        if (c.minecraftJarSha256().length() != 64) throw new IllegalArgumentException("minecraftJarSha256 must be 64 hex");
        requireNonBlank(c.voxyVersion(), "voxyVersion");
        requireNonBlank(c.voxyCommit(), "voxyCommit");
        requireNonBlank(c.voxyArtifactSha256(), "voxyArtifactSha256");
        requireNonBlank(c.canonicalBlockRegistryVersion(), "canonicalBlockRegistryVersion");
        requireNonBlank(c.canonicalBlockRegistrySha256(), "canonicalBlockRegistrySha256");
        requireNonBlank(c.canonicalBiomeRegistryVersion(), "canonicalBiomeRegistryVersion");
        requireNonBlank(c.canonicalBiomeRegistrySha256(), "canonicalBiomeRegistrySha256");
        if (c.inspectedMinecraftReferences() == null || c.inspectedMinecraftReferences().isEmpty()) throw new IllegalArgumentException("inspectedMinecraftReferences required");
        if (c.inspectedVoxyReferences() == null || c.inspectedVoxyReferences().isEmpty()) throw new IllegalArgumentException("inspectedVoxyReferences required");
        boolean hasChorus = c.inspectedMinecraftReferences().stream().anyMatch(s -> s.contains("ChorusFlowerBlock") || s.contains("ChorusPlantFeature"));
        boolean hasBiomeSource = c.inspectedMinecraftReferences().stream().anyMatch(s -> s.contains("TheEndBiomeSource"));
        boolean hasGenStep = c.inspectedMinecraftReferences().stream().anyMatch(s -> s.contains("VEGETAL_DECORATION") || s.contains("GenerationStep"));
        boolean hasDecorationSeed = c.inspectedMinecraftReferences().stream().anyMatch(s -> s.contains("WorldgenRandom") && s.contains("setDecorationSeed"));
        boolean hasRandomState = c.inspectedMinecraftReferences().stream().anyMatch(s -> s.contains("RandomState") && s.contains("PositionalRandomFactory"));
        boolean hasRng = hasDecorationSeed || hasRandomState;
        if (!hasChorus) throw new IllegalArgumentException("inspectedMinecraftReferences must include ChorusFlowerBlock/ChorusPlantFeature");
        if (!hasBiomeSource) throw new IllegalArgumentException("inspectedMinecraftReferences must include TheEndBiomeSource");
        if (!hasGenStep) throw new IllegalArgumentException("inspectedMinecraftReferences must include GenerationStep VEGETAL_DECORATION");
        if (!hasRng) throw new IllegalArgumentException("inspectedMinecraftReferences must document decoration RNG via WorldgenRandom#setDecorationSeed or RandomState PositionalRandomFactory");
        // Halo decomposition - keep components separate, combined is conservative effective area, not universal law
        var h = c.halo();
        if (h == null) throw new NullPointerException("halo is required");
        Objects.requireNonNull(h, "halo");
        if (h.featureReachBlocks() != 8) throw new IllegalArgumentException("featureReachBlocks must be 8 (chorus max spread)");
        if (h.minecraftGenerationHaloChunks() != 1) throw new IllegalArgumentException("minecraftGenerationHaloChunks must be 1");
        if (h.voxyMipHaloBlocks() != 1) throw new IllegalArgumentException("voxyMipHaloBlocks must be 1");
        // Combined is conservative effective area (featureReach 8 + generation 16 + mip 1 = 25) but not enforced as universal law; allow >=25
        if (h.combinedHaloBlocks() < 25) throw new IllegalArgumentException("combinedHaloBlocks must be >=25 (conservative 8+16+1), was " + h.combinedHaloBlocks());
        if (h.featureReachEvidence()==null || h.featureReachEvidence().isBlank()) throw new IllegalArgumentException("featureReachEvidence required");
        if (h.minecraftGenerationHaloEvidence()==null || h.minecraftGenerationHaloEvidence().isBlank()) throw new IllegalArgumentException("minecraftGenerationHaloEvidence required");
        if (h.voxyMipHaloEvidence()==null || h.voxyMipHaloEvidence().isBlank()) throw new IllegalArgumentException("voxyMipHaloEvidence required");
        // Region / blockRegion
        if (c.region() == null && c.blockRegion() == null) throw new NullPointerException("region/blockRegion required");
        var br = c.blockRegionOrDerived();
        if (br == null) throw new IllegalArgumentException("blockRegion/region required");
        if (br.extentBlocks() <=0) throw new IllegalArgumentException("extentBlocks must be >0");
        int cx = br.originBlockX();
        int cz = br.originBlockZ();
        long dist2 = (long)(cx>>4)*(cx>>4) + (long)(cz>>4)*(cz>>4);
        if (dist2 <= 4096L) throw new IllegalArgumentException("tracer blockRegion must be outer island (chunk dist >64), was dist2="+dist2+" originBlock=("+cx+","+cz+")");
        if (br.originBlockY() <0 || br.originBlockY() > 128) throw new IllegalArgumentException("originBlockY out of End height range");
        var r = c.region();
        if (r != null) {
            if (Math.floorDiv(br.originBlockX(),16) != r.originSectionX() || Math.floorDiv(br.originBlockZ(),16) != r.originSectionZ()) {
                throw new IllegalArgumentException("region and blockRegion must be consistent: blockRegion "+br+" vs region "+r);
            }
        }
        requireNonBlank(c.authoritativeGenerationStage(), "authoritativeGenerationStage");
        if (!"FEATURES".equals(c.authoritativeGenerationStage())) throw new IllegalArgumentException("authoritativeGenerationStage must be FEATURES, was " + c.authoritativeGenerationStage());
        requireNonBlank(c.fixtureFormatVersion(), "fixtureFormatVersion");
        requireNonBlank(c.provenanceId(), "provenanceId");
        if (c.schemaVersion().contains(".v3") && !c.provenanceId().contains("b"+br.originBlockX()+"_"+br.originBlockY()+"_"+br.originBlockZ())) {
            throw new IllegalArgumentException("v3 provenance must encode blockRegion b"+br.originBlockX()+"_"+br.originBlockY()+"_"+br.originBlockZ()+", was "+c.provenanceId());
        }
        requireNonBlank(c.generationOrder(), "generationOrder");
        if (!OracleContract.EXPECTED_GENERATION_ORDER.equals(c.generationOrder())) {
            throw new IllegalArgumentException("generationOrder must be '" + OracleContract.EXPECTED_GENERATION_ORDER + "' (squared distance -> X -> Z, explicit not Morton), was '" + c.generationOrder() + "'");
        }
        var pd = Objects.requireNonNull(c.perLevelDecisions(), "perLevelDecisions");
        var allowedDispositions = java.util.Set.of("UNRESOLVED", "OMIT", "REUSE_VANILLA", "DETERMINISTIC", "LEARNED_RESIDUAL", "LEARNED_FULL", "EXACT_PORT");
        for (var d : new OracleContract.PartitionDecision[]{pd.l4(), pd.l3(), pd.l2(), pd.l1(), pd.l0()}) {
            if (d==null) throw new IllegalArgumentException("perLevelDecisions contains null");
            if (!allowedDispositions.contains(d.disposition())) throw new IllegalArgumentException("per-Level disposition must be one of " + allowedDispositions + ", was "+d.disposition());
            if (d.candidates()==null || d.candidates().size()<5) throw new IllegalArgumentException("candidates must list at least 5 dispositions for " + d.disposition());
            if (d.rationale()==null || d.rationale().isBlank()) throw new IllegalArgumentException("rationale is required for disposition " + d.disposition());
        }
        var roles = Objects.requireNonNull(c.roles(), "roles");
        requireNonBlank(roles.claimRole(), "claimRole");
        requireNonBlank(roles.dependencyRole(), "dependencyRole");
        var bp = c.benchmarkPolicy();
        if (bp != null) {
            if (bp.warmupIterations() <0 || bp.measurementIterations()<=0) throw new IllegalArgumentException("benchmarkPolicy iterations invalid");
        }
        if (c.seed() != 42L) throw new IllegalArgumentException("seed must be 42 for deterministic oracle");
    }
    private static void requireNonBlank(String s, String name) {
        if (s==null || s.isBlank()) throw new IllegalArgumentException(name+" is required");
    }
}
