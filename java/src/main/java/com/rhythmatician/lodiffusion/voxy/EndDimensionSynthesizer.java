package com.rhythmatician.lodiffusion.voxy;

/**
 * End implementation of {@link DimensionSynthesizer}. Extracted from {@link GenerationSession#produceEndRefinementChild}
 * to avoid hard-coded if(End) branching in the session. Owns base terrain
 * (ExactEndL1Candidate vs EndL4DeterministicCandidate per Dimension Generation Domain).
 * Mipper once (L1 single 64³→32³, L2 double 128³→32³), isAllAir() early-out, Level-aware honest omission
 * (L4/L3 chorus 0) is Fidelity Profile data.
 *
 * <p>Chorus overlay (L1/L2 deterministic per-column hash %20, 3-6 height, branch) is a known
 * non-vanilla approximation. Per ADR 0015, exact post-ingest Voxy parity is the default and
 * deterministic approximation requires an explicit Worldgen Partition decision with measured
 * correctness and performance evidence. Until #220 has a disposition and #233 provides the
 * independent vanilla+Voxy oracle, the production overlay is disabled by default. The chorus
 * implementation remains available for experiments via {@link #EndDimensionSynthesizer(WorldNoiseAccess, ExactL1SamplingTelemetry, long, boolean)} or
 * {@link DimensionSynthesizers#forDimension(net.minecraft.registry.RegistryKey, WorldNoiseAccess, ExactL1SamplingTelemetry, long, boolean)} with {@code enableChorusOverlay=true}.
 * When disabled, L2 avoids the 128³ block allocation and 2M+ block evaluations for the chorus double-mip path.
 */
public final class EndDimensionSynthesizer implements DimensionSynthesizer {
    private final WorldNoiseAccess access;
    private final ExactL1SamplingTelemetry exactL1Sampling;
    private final long seed;
    private final boolean enableChorusOverlay;

    public EndDimensionSynthesizer(WorldNoiseAccess access, ExactL1SamplingTelemetry exactL1Sampling, long seed) {
        this(access, exactL1Sampling, seed, false);
    }

    public EndDimensionSynthesizer(WorldNoiseAccess access, ExactL1SamplingTelemetry exactL1Sampling, long seed, boolean enableChorusOverlay) {
        this.access = access;
        this.exactL1Sampling = exactL1Sampling;
        this.seed = seed;
        this.enableChorusOverlay = enableChorusOverlay;
    }

    public boolean isChorusOverlayEnabled() {
        return enableChorusOverlay;
    }

    @Override
    public VoxelVolume synthesize(Level level, SectionPos origin) {
        if (access == null) throw new IllegalStateException("End noise is not bound");
        boolean needsChorus = enableChorusOverlay && (level == Level.L1 || level == Level.L2);
        VoxelVolume base;
        if (level == Level.L1) {
            ExactEndL1Candidate exactL1 = new ExactEndL1Candidate(access, exactL1Sampling);
            base = exactL1.produceExactL1(origin);
        } else {
            EndL4DeterministicCandidate candidate = new EndL4DeterministicCandidate(access);
            base = candidate.produceRegion(level, origin);
        }
        if (!needsChorus) return base;
        EndChorusSynthesizer chorus = EndChorusSynthesizer.forWorld(access, seed);
        VoxelVolume chorusVol = chorus.synthesize(level, origin);
        if (chorusVol.isAllAir()) return base;
        VoxelVolume.Builder out = VoxelVolume.builder(base.extent());
        int ext = base.extent();
        for (int y = 0; y < ext; y++) {
            for (int z = 0; z < ext; z++) {
                for (int x = 0; x < ext; x++) {
                    int baseId = base.blockId(x, y, z);
                    if (baseId != CanonicalRegistries.BLOCK_AIR) {
                        out.setBlock(x, y, z, baseId);
                    } else {
                        int cid = chorusVol.blockId(x, y, z);
                        if (cid == EndChorusSynthesizer.BLOCK_CHORUS_PLANT
                                || cid == EndChorusSynthesizer.BLOCK_CHORUS_FLOWER) {
                            out.setBlock(x, y, z, cid);
                        }
                    }
                }
            }
        }
        return out.build();
    }
}
