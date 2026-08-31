package com.rhythmatician.voxygen;

/**
 * ACCESS-WIDENING AUDIT — Voxygen package refactor (branch refactor/voxygen-package-architecture)
 *
 * <p>During the incremental migration from {@code com.rhythmatician.lodiffusion.voxy}
 * to {@code com.rhythmatician.voxygen}, many types that were previously
 * package-private (accessible because they shared the single {@code voxy}
 * package) became cross-package after being split into the new ownership
 * layers. To keep each slice green (compileJava && compileTestJava) without
 * bulk-copy-then-fix, the minimal set of members below was widened to
 * {@code public}. No members were widened mechanically to "everything public";
 * each entry was required by a concrete cross-package compile error and is
 * documented with the reason.
 *
 * <p>Policy for future work (per task instructions): <strong>Do not widen
 * anything further</strong> to make a test compile. If a test cannot reach a
 * member, co-locate the test with the owner package, or leave the member
 * package-private and note the gap in this file, rather than making it public.
 * This audit is the single source of truth for what was widened and why;
 * any new widening must be appended here with a reason before it is merged.
 *
 * <h3>Top-level types that were package-private and are now public</h3>
 * <ul>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.CanonicalVoxyMaps} — was {@code final class}, now {@code public final class}; accessed from {@code generation.session.GenerationSession} (different package) after move.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.RealVoxyVolumeWriter} — public; accessed from GenerationSession.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxelizedSectionSnapshot} — public; accessed from tests and generation.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyBlockMapper} — public; accessed from inference (VoxelPredictionDecoder) and GenerationSession.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyCompat} — public; accessed from HelloTerrainMod and GenerationSession.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyDatasetExportService} — public; leaf adapter, referenced from GenerationSession.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyDetection} — public; accessed from worldgen and generation.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyEngine} — public; accessed from GenerationSession.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyIdMaps} — public; accessed from GenerationSession and RealVoxyVolumeWriter.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyNodeRequestRetry} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyOverlayNodeEncoding} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyProcessingAPI} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyTopologyOwnership} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyTraversalNodeIdShaderPatch} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.backend.voxy.VoxyWorldBinding} — public; method {@code readAndUpsampleParentOctant} also widened.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate} — was package-private, now {@code public final class}; instantiated from generation.session.GenerationSession (different package) and from worldgen.WorldNoiseAccess.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.end.ExactEndL1Candidate} — public; instantiated from WorldNoiseAccess and GenerationSession; nested interfaces {@code ChunkColumnSampler} and {@code SolidBlockConsumer} also made {@code public}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.end.ExactL1SamplingTelemetry} — was package-private {@code class}, now {@code public final class}; added {@code public ExactL1SamplingTelemetry()} constructor (implicit default was package-private) and widened methods {@code recordChildCall}, {@code recordRetainedCallback}, {@code recordRawAir}, {@code recordRawExplicitNonAir}, {@code recordAcceptedCallback}, {@code recordReducedSolidVoxels}, {@code compact}, {@code reset} to public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement} — public; method {@code productionConfig()} widened to {@code public static Config}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement.Config} — was package-private {@code record}, now {@code public record}; secondary constructor {@code Config(int,int,int,int,double,long)} and compact constructor {@code Config {}} made {@code public}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.EndRefinement} — public interface (was package-private).</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.RefinementAdmissionGate} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.RefinementDemandSelector} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.RefinementLifecycleTelemetry} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.RefinementOutcome} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome} — public (record).</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.ChunkScheduler} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.LodGenerationQueue} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.LodGenerationService} — public (already), but members widened: see below.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.SectionTask} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid} — was package-private {@code final class}, now {@code public final class}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid.Cell} — was package-private {@code record}, now {@code public record}; compact constructor {@code Cell {}} made {@code public Cell {}}; methods {@code parent()} and {@code child(int)} made {@code public}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid.Delta} — was package-private {@code record}, now {@code public record}; compact constructor made {@code public Delta {}}; method {@code isEmpty()} made {@code public}.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid} methods {@code classify(Cell)} and {@code urgentBoundary()} widened to {@code public} — accessed from {@code generation.refinement.DefaultEndRefinement} and from tests in {@code lodiffusion.voxy} (different source set).</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizer} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizers} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.dimension.end.EndDimensionSynthesizer} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.generation.TerrainPublicationRoute} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.features.end.chorus.EndChorusSynthesizer} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.inference.onnx.VoxelPredictionDecoder} — public.</li>
 *   <li>{@code com.rhythmatician.voxygen.worldgen.heightmap.HeightmapFallbackGenerator} — public; constants {@code SEA_LEVEL}, nested {@code SurfaceType}, and method {@code surfaceTypeForBiome(int)} widened to public.</li>
 *   <li>{@code com.rhythmatician.voxygen.semantic.biome.AnchorSampler} — public; methods {@code computeHeightPlanes}, {@code sampleHeightmap(Chunk)}, {@code sampleBiomes(Chunk)} widened to public.</li>
 * </ul>
 *
 * <h3>Members widened within already-public types</h3>
 * <ul>
 *   <li>{@code generation.session.GenerationSession.Y_BASE_SECTION}, {@code MIN_WORLD_BLOCK_Y}, {@code MAX_WORLD_BLOCK_Y}, {@code GENERATION_RADIUS}, {@code SEA_LEVEL}, {@code HEIGHT_AMPLITUDE} — were {@code static final} package-private, now {@code public static final}; accessed from {@code generation.scheduling.LodGenerationService} (different package) after split.</li>
 *   <li>{@code GenerationSession.isOutOfWorldY(int,int)} — was {@code static boolean}, now {@code public static boolean}.</li>
 *   <li>{@code GenerationSession.sectionKey(int,int,int)} — now {@code public static long}.</li>
 *   <li>{@code GenerationSession.buildHeightmap(int,int)} — now {@code public static float[][]}.</li>
 *   <li>{@code GenerationSession.setEndL4TracerModeForTest(boolean)}, {@code forceRunningForTest()}, {@code isEndL4TracerMode()}, {@code observeVanillaChunkColumn(int,int)} — now {@code public}; accessed from LodGenerationService.</li>
 *   <li>{@code GenerationSession.tracerCompletion()} — was package-private, now {@code public TracerCompletion}; accessed from tests in {@code lodiffusion.voxy}.</li>
 *   <li>{@code GenerationSession.TracerCompletion} — already {@code public record}, retained; no widening needed beyond class.</li>
 *   <li>{@code LodGenerationService.getBoundDimensionForTest()} — was package-private, now {@code public}.</li>
 *   <li>{@code LodGenerationService.buildHeightmap(int,int)} — was package-private, now {@code public}; retained for {@code L1AvailabilityContractTest} reflection.</li>
 *   <li>{@code LodGenerationService.Y_SECTIONS}, {@code Y_BASE_SECTION}, {@code MIN_WORLD_BLOCK_Y}, {@code MAX_WORLD_BLOCK_Y}, {@code isOutOfWorldY}, {@code sectionKey} — already public via delegation to GenerationSession; no additional widening.</li>
 *   <li>{@code VanillaOccupancyPyramid.classify}, {@code urgentBoundary}, {@code Delta.isEmpty} — widened as above.</li>
 *   <li>{@code ExactL1SamplingTelemetry} — added public no-arg constructor; widened all record* methods and compact/reset.</li>
 *   <li>{@code WorldNoiseAccess.sampleExactEndBaseTerrainChunk(...)} — was package-private {@code void}, now {@code public void}; called from {@code generation.dimension.end.ExactEndL1Candidate} (different package after move).</li>
 *   <li>{@code HeightmapFallbackGenerator.SEA_LEVEL}, {@code SurfaceType}, {@code surfaceTypeForBiome} — widened as above.</li>
 *   <li>{@code AnchorSampler.*} — widened as above.</li>
 *   <li>{@code VoxyWorldBinding.readAndUpsampleParentOctant} — widened to public.</li>
 * </ul>
 *
 * <h3>Intentionally NOT widened</h3>
 * <ul>
 *   <li>{@code com.rhythmatician.voxygen.semantic.Level} constructor — remains package-private (enum constructors cannot be public; Java forbids {@code public} enum constructors). Left as {@code Level(int)}.</li>
 *   <li>{@code VanillaOccupancyPyramid.urgentBoundary} internal map and {@code l0OctantMasks} — remain private; only the public view accessor was widened.</li>
 *   <li>Any member not listed above was not widened. If a future test needs a package-private member, prefer co-locating the test in the same package (e.g., move the test to {@code com.rhythmatician.voxygen.generation.scheduling}) over widening.</li>
 * </ul>
 *
 * <p>All widenings were minimal and driven by concrete compiler errors after the
 * package split. No bulk "make everything public" was performed; many
 * package-private helpers remain package-private. See git diff for the exact
 * hunks; this file is the human-readable ledger.
 */
public final class AccessWideningAudit {
    private AccessWideningAudit() {}
}
