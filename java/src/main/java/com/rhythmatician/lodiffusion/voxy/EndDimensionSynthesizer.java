package com.rhythmatician.lodiffusion.voxy;

/**
 * End implementation of {@link DimensionSynthesizer}. Extracted from {@link GenerationSession#produceEndRefinementChild}
 * to avoid hard-coded if(End) branching in the session. Owns base terrain
 * (ExactEndL1Candidate vs EndL4DeterministicCandidate per Dimension Generation Domain) and
 * Feature Synthesis overlay for chorus (FeatureEligibility list [chorus_plant 197 / chorus_flower 196]).
 * Mipper once (L1 single 64³→32³, L2 double 128³→32³), isAllAir() early-out, Level-aware honest omission
 * (L4/L3 chorus 0) and Profile-Inactive → N/A are Fidelity Profile data.
 */
public final class EndDimensionSynthesizer implements DimensionSynthesizer {
    private final WorldNoiseAccess access;
    private final ExactL1SamplingTelemetry exactL1Sampling;
    private final long seed;

    public EndDimensionSynthesizer(WorldNoiseAccess access, ExactL1SamplingTelemetry exactL1Sampling, long seed) {
        this.access = access;
        this.exactL1Sampling = exactL1Sampling;
        this.seed = seed;
    }

    @Override
    public VoxelVolume synthesize(Level level, SectionPos origin) {
        if (access == null) throw new IllegalStateException("End noise is not bound");
        boolean needsChorus = level == Level.L1 || level == Level.L2;
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
