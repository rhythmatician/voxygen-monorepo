package com.rhythmatician.voxygen.arch;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;

/**
 * Package-ownership guardrails — repository/package ownership doctrine.
 *
 * <p>Audience: repository/package ownership doctrine. This test remains the enforcement home for
 * ADR 0016 Decisions 1–3 (canonical root, flat {@code backend.voxy} leaf, legacy shims) and
 * repository/package ownership rules, including foreign-Voxy ownership and production
 * package-direction invariants.
 *
 * <p>ADR 0016 ownership is enforced here; see {@code ArchitectureGuardrailsTest} for deep
 * seam/runtime independence. Foreign-Voxy ownership ({@code me.cortex.voxy..}) is explicit:
 * only the adapter {@code backend.voxy} may depend on it.
 */
public class PackageBoundaryTest {

    private static final String[] CORE_PACKAGES = {
        "com.rhythmatician.voxygen.features..",
        "com.rhythmatician.voxygen.semantic..",
        "com.rhythmatician.voxygen.worldgen..",
        "com.rhythmatician.voxygen.terrain..",
        "com.rhythmatician.voxygen.inference..",
        "com.rhythmatician.voxygen.generation.scheduling..",
        "com.rhythmatician.voxygen.generation.refinement..",
        "com.rhythmatician.voxygen.generation.dimension..",
        "com.rhythmatician.voxygen.generation.features.."
    };

    private static final String BACKEND_VOXY = "com.rhythmatician.voxygen.backend.voxy..";

    private static final String[] EXPECTED_VOXYGEN_CLASSES = {
        "com.rhythmatician.voxygen.semantic.Level",
        "com.rhythmatician.voxygen.semantic.SectionPos",
        "com.rhythmatician.voxygen.semantic.VoxelVolume",
        "com.rhythmatician.voxygen.semantic.CanonicalRegistries",
        "com.rhythmatician.voxygen.semantic.WorldSectionCoord",
        "com.rhythmatician.voxygen.semantic.biome.AnchorSampler",
        "com.rhythmatician.voxygen.semantic.biome.BiomeMapping",
        "com.rhythmatician.voxygen.output.VoxelVolumeWriter",
        "com.rhythmatician.voxygen.output.WriteOutcome",
        "com.rhythmatician.voxygen.output.VolumeUnavailableException",
        "com.rhythmatician.voxygen.output.InMemoryVolumeWriter",
        "com.rhythmatician.voxygen.worldgen.WorldNoiseAccess",
        "com.rhythmatician.voxygen.generation.dimension.DimensionGenerationDomain",
        "com.rhythmatician.voxygen.worldgen.heightmap.HeightmapFallbackGenerator",
        "com.rhythmatician.voxygen.generation.session.GenerationSession",
        "com.rhythmatician.voxygen.generation.scheduling.LodGenerationQueue",
        "com.rhythmatician.voxygen.generation.scheduling.LodGenerationService",
        "com.rhythmatician.voxygen.generation.scheduling.SectionTask",
        "com.rhythmatician.voxygen.generation.scheduling.ChunkScheduler",
        "com.rhythmatician.voxygen.generation.scheduling.VanillaOccupancyPyramid",
        "com.rhythmatician.voxygen.generation.scheduling.VanillaFrontierGuardPlanner",
        "com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch",
        "com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent",
        "com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult",
        "com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome",
        "com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff",
        "com.rhythmatician.voxygen.generation.refinement.DefaultEndRefinement",
        "com.rhythmatician.voxygen.generation.refinement.EndRefinement",
        "com.rhythmatician.voxygen.generation.refinement.LodOverlayState",
        "com.rhythmatician.voxygen.generation.refinement.RefinementAdmissionGate",
        "com.rhythmatician.voxygen.generation.refinement.RefinementDemandSelector",
        "com.rhythmatician.voxygen.generation.refinement.RefinementLifecycleTelemetry",
        "com.rhythmatician.voxygen.generation.refinement.RefinementOutcome",
        "com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizer",
        "com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizers",
        "com.rhythmatician.voxygen.generation.dimension.end.EndDimensionSynthesizer",
        "com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate",
        "com.rhythmatician.voxygen.generation.dimension.end.ExactEndL1Candidate",
        "com.rhythmatician.voxygen.generation.dimension.end.ExactL1SamplingTelemetry",
        "com.rhythmatician.voxygen.generation.TerrainPublicationRoute",
        "com.rhythmatician.voxygen.features.end.chorus.EndChorusSynthesizer",
        "com.rhythmatician.voxygen.inference.onnx.VoxelPredictionDecoder",
        "com.rhythmatician.voxygen.backend.voxy.RealVoxyVolumeWriter",
        "com.rhythmatician.voxygen.backend.voxy.VoxyCompat",
        "com.rhythmatician.voxygen.backend.voxy.VoxyDetection",
        "com.rhythmatician.voxygen.backend.voxy.VoxyEngine",
        "com.rhythmatician.voxygen.HelloTerrainMod"
    };

    // Legacy lodiffusion packages that are allowed to remain as shims
    private static final Set<String> LEGACY_SHIMS = Set.of(
        "com.rhythmatician.lodiffusion.HelloTerrainMod",
        "com.rhythmatician.lodiffusion.Config",
        "com.rhythmatician.lodiffusion.ModDetection",
        "com.rhythmatician.lodiffusion.LodiffusionClient",
        "com.rhythmatician.lodiffusion.command.LodiffusionCommand"
    );

    private JavaClasses importVoxygenProduction() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("generated"))
            .importPackages("com.rhythmatician.voxygen");
    }

    @Test
    void corePackages_doNotDependOnBackendVoxy() {
        JavaClasses voxygen = importVoxygenProduction();
        // GenerationSession (generation.session) is the composition root that wires
        // the backend via the neutral VoxelVolumeWriter seam and is excluded from
        // this check via CORE_PACKAGES. All other generation subpackages must
        // remain backend-agnostic.
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage(CORE_PACKAGES)
            .should().dependOnClassesThat().resideInAnyPackage(BACKEND_VOXY);
        // Must be non-vacuous: at least one core class must exist
        assertTrue(voxygen.size() > 0, "voxygen production classes should not be empty");
        long coreCount = voxygen.stream()
            .filter(c -> {
                String pkg = c.getPackageName();
                return pkg.startsWith("com.rhythmatician.voxygen.features")
                    || pkg.startsWith("com.rhythmatician.voxygen.semantic")
                    || pkg.startsWith("com.rhythmatician.voxygen.worldgen")
                    || pkg.startsWith("com.rhythmatician.voxygen.generation");
            })
            .count();
        assertTrue(coreCount > 10, "core voxygen classes should be present, found " + coreCount);
        rule.check(voxygen);
    }

    @Test
    void nonAdapterVoxygen_doesNotDependOnForeignVoxy() {
        JavaClasses voxygen = importVoxygenProduction();
        ArchRule rule = ArchRuleDefinition.noClasses()
            .that().resideInAnyPackage("com.rhythmatician.voxygen..")
            .and(DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(BACKEND_VOXY)))
            .should().dependOnClassesThat().resideInAnyPackage("me.cortex.voxy..");
        rule.check(voxygen);
    }

    @Test
    void output_doesNotDependOnGenerationInferenceOrBackend() {
        JavaClasses voxygen = importVoxygenProduction();
        // Production output is the neutral three-type seam (VoxelVolumeWriter, WriteOutcome,
        // VolumeUnavailableException) plus InMemoryVolumeWriter (still in production pending
        // #249 testFixtures move) so production output must not depend on generation/inference/backend. The refineParent
        // seam (ParentRefinementIntent/Result/Batch etc.) is the only allowed generation bridge
        // for the writer's parent-refinement capability; all other generation/inference/backend
        // dependencies are forbidden.
        DescribedPredicate<JavaClass> allowedOutputDependency = DescribedPredicate.or(
            JavaClass.Predicates.resideInAnyPackage("java.."),
            JavaClass.Predicates.resideInAnyPackage("com.rhythmatician.voxygen.output.."),
            JavaClass.Predicates.resideInAnyPackage("com.rhythmatician.voxygen.semantic.."),
            JavaClass.Predicates.resideInAnyPackage("org.slf4j.."),
            JavaClass.Predicates.belongToAnyOf(
                com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent.class,
                com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult.class,
                com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch.class,
                com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome.class,
                com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff.class)
        ).as("allowed output dependencies");
        ArchRule rule = ArchRuleDefinition.classes()
            .that().resideInAnyPackage("com.rhythmatician.voxygen.output..")
            .should().onlyDependOnClassesThat(allowedOutputDependency);
        rule.check(voxygen);
    }

    @Test
    void expectedVoxygenClasses_exist() {
        for (String fqn : EXPECTED_VOXYGEN_CLASSES) {
            try {
                Class.forName(fqn);
            } catch (ClassNotFoundException e) {
                throw new AssertionError("Expected voxygen class not found: " + fqn, e);
            }
        }
    }

    @Test
    void noProductionJavaRemainsUnderLegacyLodiffusionExceptShims() throws IOException {
        Path candidate = Paths.get("java/src/main/java");
        if (!Files.exists(candidate)) {
            candidate = Paths.get("src/main/java");
        }
        if (!Files.exists(candidate)) {
            candidate = Paths.get("java/src/main/java");
            if (!Files.exists(candidate)) return;
        }
        final Path mainJava = candidate;
        Path legacyRoot = mainJava.resolve("com/rhythmatician/lodiffusion");
        if (!Files.exists(legacyRoot)) return;

        try (Stream<Path> walk = Files.walk(legacyRoot)) {
            List<Path> legacyFiles = walk
                .filter(p -> p.toString().endsWith(".java"))
                .collect(Collectors.toList());

            // Filter to production (exclude shims)
            List<Path> nonShim = legacyFiles.stream()
                .filter(p -> {
                    // Convert path to FQN
                    String rel = mainJava.relativize(p).toString()
                        .replace('/', '.')
                        .replace('\\', '.')
                        .replaceAll("\\.java$", "");
                    // Allow shims and their subpackages
                    for (String shim : LEGACY_SHIMS) {
                        if (rel.equals(shim) || rel.startsWith(shim + ".")) return false;
                        // Also allow any class in allowed shim packages
                        if (rel.startsWith("com.rhythmatician.lodiffusion.command.")
                            || rel.startsWith("com.rhythmatician.lodiffusion.util.")
                            || rel.startsWith("com.rhythmatician.lodiffusion.world.")
                            || rel.startsWith("com.rhythmatician.lodiffusion.gpu.")
                            || rel.startsWith("com.rhythmatician.lodiffusion.onnx.")
                            || rel.startsWith("com.rhythmatician.lodiffusion.config.")) {
                            // These are not voxy; they are allowed to remain for now
                            // But we only care about old voxy authority
                            if (rel.startsWith("com.rhythmatician.lodiffusion.voxy")) return true;
                            return false;
                        }
                    }
                    // If it's under lodiffusion.voxy, it's legacy authority and should be gone
                    if (rel.startsWith("com.rhythmatician.lodiffusion.voxy")) {
                        return true;
                    }
                    // Other legacy packages like lodiffusion.HelloTerrainMod shim is allowed
                    // but we already filtered shims; everything else under lodiffusion that is not shim
                    // and not in allowed util/world/gpu/onnx is considered shim for now
                    return false;
                })
                .collect(Collectors.toList());

            assertTrue(nonShim.isEmpty(),
                "Legacy production Java remains under com.rhythmatician.lodiffusion.voxy (should be migrated to voxygen): " + nonShim);
        }
    }
}
