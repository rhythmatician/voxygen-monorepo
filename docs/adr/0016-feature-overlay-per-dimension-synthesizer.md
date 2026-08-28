# 0016 â€” Feature-overlay per DimensionSynthesizer

Date: 2026-08-28
Status: Proposed

## Context

End chorus was the first feature vertical slice (EndChorusSynthesizer EXTENT 32 forWorld seed + end_highlands BiomeEligibility + SurfaceProvider hash%20, Mipper L1 single 64->32 L2 double 128->32 via two steps, L4/L3 honest omission, overlay where base==AIR). Verdict: 95% voxel agreement at L1/L2 with vanilla+Voxy bottom-up (197/196 vs 359) is achievable, but L2 cost is ~12s per 32 (11619ms SKIPPED_AIR 05:16:24, 12123ms WRITTEN 123 05:16:36) because it touches 128^3; L4/L3 would be SKIPPED_AIR if we gated chorus there. Hard-coding needsChorus = L1||L2 in GenerationSession.produceEndRefinementChild will not scale to Overworld (oak_log/pine/birch) and Nether (nether_wart/basalt) where generate_structures=false makes many placed features Profile-Inactive -> N/A, and where Level-aware Claim (L4 omit vs L0 claim) is Fidelity Profile data, not code.

A top-level FeatureSynthesizer per block family would fragment a 32 that spans 2-3 biomes (L2 x0y0z56 0 vs x8y0z56 123) and pay one VoxelVolume.Builder per feature (for y for z for x per feature) and Mipper per feature. WorldsectionSynthesizer would duplicate VoxelVolumeWriter.writeRegion(origin, Level, 32) which is already the storage seam.

## Decision

Feature synthesis is a per-DimensionSynthesizer overlay loop, not a top-level synthesizer.

* DimensionSynthesizer owns base terrain (ExactEndL1Candidate / EndL4DeterministicCandidate per Dimension Generation Domain) and then iterates that dimension's FeatureEligibility list ([chorus] for End; [oak,pine,birch...] for Overworld; [wart,basalt...] for Nether) in a single for y for z for x pass per 32: if(baseId != AIR) keep else if(isEligible(blockX,blockZ, Level) && hash%N==0) set feature block.
* Honest omission (chorus 0 at L4/L3) and Profile-Inactive -> N/A are Fidelity Profile data per dimension x Level, not if(Feature) branches.
* Seed from ServerWorld.getSeed() distance 0 for vanilla parity (chorus hash%20 reproduces ChorusPlantFeature placement seed semantics); same rule for other features.
* Mipper once after overlay: L1 single 64->32, L2 double 128->32 via two successive Mipper steps (equivalent to single 4^3 per EndChorusSynthesizer:247). isAllAir() early-out before Mipper (L2 x0y0z56 0) stays.
* One Builder per 32, not per feature; one Mipper, not per feature.

This does not choose Overworld tree density, Nether basalt Claim at L4, or water/lava Topology Bundle mipping — those stay Partition Decision State unresolved.

## Alternatives considered

* FeatureSynthesizer as top (one per block family): duplicates Builder per feature, duplicates isAllAir() per feature, needs halo/blending for biomes spanning a WorldSection.
* BiomeSynthesizer as top: fragments 32 that spans biomes, duplicates ChunkNoiseSampler band walk per biome (see ADR 0014).
* WorldsectionSynthesizer as top: duplicates writeRegion seam.

## Consequences

* GenerationSession.produceEndRefinementChild becomes delegation -> DimensionSynthesizer.forDimension(boundDimension).synthesize(...) with that dimension's FeatureEligibility list; End keeps one entry [chorus], future dimensions inject lists without new top seams.
* Efficiency: one pass + one Mipper + isAllAir() preserves the current ~12s per L2 32 and ~3.3s per L1 32; adding features scales with column hash%N, not with Builder count. L2 128^3 touch remains the dominant cost; Ground Surface 2D cull before 128^3 touch (per ADR 0013 band) is the next win, not per-feature dispatch.
* Correctness: Mipper shared; Level-aware omission stays honest (L4/L3 chorus 0); seed distance 0 keeps vanilla parity; 95% L1/L2 agreement remains the bar.
* Preserve-futures: if FeatureSynthesizer is promoted to top before this ADR, every Overworld tree ticket will add a new top seam and re-branch on if(Feature).