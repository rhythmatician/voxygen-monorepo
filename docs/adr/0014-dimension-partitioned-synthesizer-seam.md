# 0014 - Dimension-partitioned synthesizer seam

Date: 2026-08-28
Status: Proposed

## Context

The End vertical slice (EndChorusSynthesizer L4/L3 omission, L2 double-mip 128->32, L1 64->32, forWorld seed + end_highlands BiomeEligibility + SurfaceProvider hash20) works, but GenerationSession.produceEndRefinementChild is an if(L1) exact else deterministic branch with needsChorus = L1||L2 hard-coded for End. Worldgen Partition v1 defines separate frozen profiles for the_end / the_nether / overworld with different NoiseSettings, different Fidelity Profile claims per Stratification dimension x Level, and different Placed Feature Profile-Inactive sets. A Biome-level seam would fragment a single 32 VoxelVolume that spans 2-3 biomes and duplicate the ChunkNoiseSampler(4) band walk per biome; a Worldsection-level seam would duplicate VoxelVolumeWriter.writeRegion(origin, Level, 32) which is already the storage seam.

WorldSectionCoord is dimension-agnostic (block >> (5+L), worldSectionToBlockMin/Max(wsY, Level)), and Mipper opacity-15-wins is shared, but Dimension Generation Domain bounds and Topology Bundle (solid/water/lava) are dimension-specific. Hard-coding if(End) in the session will not scale and loses optionality for Overworld/Nether L4 honest omission and L1 exact walks.

## Decision

GenerationSession will delegate Level + SectionPos synthesis to a DimensionSynthesizer seam synthesize(Level, SectionPos) -> VoxelVolume[32] looked up by boundDimension (ServerWorld.getRegistryKey().getValue() the_end/the_nether/overworld), not by if(End) branching.

* DimensionSynthesizer owns Dimension Generation Domain Y interval, NoiseSettings, Mipper rule, and L4/L3 omission per Fidelity Profile; it produces A->C without B (seed + surface + eligibility -> volume without a ProtoChunk).
* Shared collaborators: Mipper, CanonicalVoxyMaps, CanonicalRegistries, VoxelVolume.Builder[32].
* Biome variation is FeatureEligibility.isEligible(blockX,blockZ, Level) injected per dimension, not a top-level BiomeSynthesizer. Specialization BiomeEligibility.isChorusBiome remains a FeatureEligibility for chorus; placed features are Profile-Inactive -> N/A when generate_structures=false.
* VoxelVolumeWriter.writeRegion remains the storage seam; Worldsection Synthesizer is not a separate concept.
* EndChorusSynthesizer stays End-specific but becomes one implementation of DimensionSynthesizer; OverworldSynthesizer/NetherSynthesizer are siblings, not branches.

This does not choose Overworld/Nether L4 fidelity, tree vs fortress Claim at L4, or water/lava Topology Bundle mipping — those stay Partition Decision State unresolved with bounded candidates.

## Alternatives considered

* Keep if(End) branch and add if(Nether)/if(Overworld): briefly simpler, but couples GenerationSession to every dimension and duplicates needsChorus/needsFeature dispatch. High fan-out cost before downstream Wayfinder decisions.
* BiomeSynthesizer as top seam (plains/desert/nether_wastes): fragments 32 volumes that span biomes, requires halo/blending, duplicates isAllAir() early-out (L2 x0y0z56 0 at 05:16:24) per biome.
* WorldsectionSynthesizer as top seam: duplicates VoxelVolumeWriter.writeRegion — same origin+Level -> 32 signature. No new boundary.

## Consequences

* GenerationSession.produceEndRefinementChild becomes delegation -> DimensionSynthesizer.forDimension(boundDimension).synthesize(...); EndL4DeterministicCandidate/ExactEndL1Candidate move behind the dimension implementation.
* Dimension Generation Domain per docs/adr/0013 inventory must be re-run per dimension (aquifers, beardifier, NoiseSettings bounds are not assumed forward).
* Feature Eligibility per Level allows L4 omit vs L0 claim without a global BiomeSynthesizer.
* Preserves optionality for Nether fortress vs Overworld oak Claim at L4 and for Topology Bundle-aware mipping — those stay unresolved with stable A->C shape.
* Preserve-futures checkpoint: if this seam is not introduced before Nether/Overworld Wayfinder tickets fan out, every ticket will re-branch on if(End).
