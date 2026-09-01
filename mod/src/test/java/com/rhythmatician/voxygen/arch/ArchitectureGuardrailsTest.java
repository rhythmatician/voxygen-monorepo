package com.rhythmatician.voxygen.arch;

import static org.junit.jupiter.api.Assertions.assertThrows;

import com.rhythmatician.lodiffusion.onnx.InferenceResult;
import com.tngtech.archunit.base.DescribedPredicate;
import com.tngtech.archunit.core.domain.JavaClass;
import com.tngtech.archunit.core.domain.JavaClasses;
import com.tngtech.archunit.core.importer.ClassFileImporter;
import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.lang.ArchRule;
import com.tngtech.archunit.lang.syntax.ArchRuleDefinition;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.output.VoxelVolumeWriter;
import com.rhythmatician.voxygen.output.WriteOutcome;
import com.rhythmatician.voxygen.output.VolumeUnavailableException;
import com.rhythmatician.voxygen.output.InMemoryVolumeWriter;

/**
 * Seam-integrity guardrails — deep seam/runtime independence.
 *
 * <p>Audience: deep seam/runtime independence. This test owns semantic/output seam integrity
 * and ensures the pure semantic core remains free of Minecraft/Fabric/Voxy/runtime implementation
 * dependencies and that writer implementations do not leak model/inference concerns.
 *
 * <p>Note: {@code com.rhythmatician.voxygen.semantic.biome.AnchorSampler} and
 * {@code com.rhythmatician.voxygen.semantic.biome.BiomeMapping} are currently
 * Minecraft-bound bridge-side semantic helpers and therefore intentionally outside the
 * pure-core set. They depend on {@code net.minecraft..} and are excluded from
 * {@code SEMANTIC_CORE} by design; the pure-core rule remains strict about
 * Minecraft/Fabric/Voxy/runtime implementation dependencies.
 *
 * <p>Two rules protect {@code VoxelVolume} opacity and the {@code VoxelVolumeWriter} deep seam.
 */
public class ArchitectureGuardrailsTest {

    private static final Class<?>[] SEMANTIC_CORE = {
        SectionPos.class,
        Level.class,
        CanonicalRegistries.class,
        VoxelVolume.class,
        VoxelVolumeWriter.class,
        WriteOutcome.class,
        VolumeUnavailableException.class,
        InMemoryVolumeWriter.class
    };

    private static final String[] SEMANTIC_FORBIDDEN_PACKAGES = {
        "net.minecraft..",
        "net.fabricmc..",
        "me.cortex.voxy..",
        "ai.djl..",
        "java.lang.reflect..",
        "java.lang.invoke.."
    };

    private static final String[] WRITER_FORBIDDEN_PACKAGES = {
        "com.rhythmatician.voxygen.inference..",
        "com.rhythmatician.lodiffusion.onnx..",
        "ai.djl.."
    };

    private static DescribedPredicate<JavaClass> allowedSemanticDependency() {
        return DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(SEMANTIC_FORBIDDEN_PACKAGES))
            .as("not in forbidden semantic packages");
    }

    private static DescribedPredicate<JavaClass> allowedWriterDependency() {
        return DescribedPredicate.not(JavaClass.Predicates.resideInAnyPackage(WRITER_FORBIDDEN_PACKAGES))
            .as("not in forbidden writer packages");
    }

    static ArchRule semanticCoreRule() {
        return semanticCoreRule(JavaClass.Predicates.belongToAnyOf(SEMANTIC_CORE));
    }

    static ArchRule semanticCoreRule(DescribedPredicate<JavaClass> where) {
        return ArchRuleDefinition.classes().that(where)
            .should().onlyDependOnClassesThat(allowedSemanticDependency());
    }

    static ArchRule writerRule() {
        return ArchRuleDefinition.classes().that(JavaClass.Predicates.implement(VoxelVolumeWriter.class))
            .should().onlyDependOnClassesThat(allowedWriterDependency());
    }

    private JavaClasses importProductionClasses() {
        return new ClassFileImporter()
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_JARS)
            .withImportOption(ImportOption.Predefined.DO_NOT_INCLUDE_TESTS)
            .withImportOption(location -> !location.contains("generated"))
            .withImportOption(location -> !location.contains("dataharvester"))
            .importPackages("com.rhythmatician.voxygen", "com.rhythmatician.lodiffusion");
    }

    @Test
    void semanticCore_isRuntimeIndependent() {
        JavaClasses production = importProductionClasses();
        semanticCoreRule().check(production);
    }

    @Test
    void writers_doNotDependOnModelOutput() {
        JavaClasses production = importProductionClasses();
        writerRule().check(production);
    }

    // ------------------------------------------------------------------
    // Test-only fixtures — deliberately not production code
    // ------------------------------------------------------------------

    public static class LegalSemanticFixture {
        private final VoxelVolume volume = VoxelVolume.uniform(16, 0, 0);
        private final SectionPos pos = new SectionPos(0, 0, 0);

        public VoxelVolume getVolume() {
            return volume;
        }

        public SectionPos getPos() {
            return pos;
        }

        public java.util.List<VoxelVolume> echo(java.util.List<VoxelVolume> in) {
            return in;
        }
    }

    public static class BadSemanticFixture {
        private java.lang.reflect.Method method;

        public void setMethod(java.lang.reflect.Method m) {
            this.method = m;
        }
    }

    public static class BadWriterFixture implements VoxelVolumeWriter {
        private InferenceResult result;

        public void setResult(InferenceResult r) {
            this.result = r;
        }

        @Override
        public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
            return WriteOutcome.skippedAir();
        }

        @Override
        public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
            return WriteOutcome.skippedAir();
        }
    }

    // ------------------------------------------------------------------
    // Sensitivity proof — same rule construction as production
    // ------------------------------------------------------------------

    @Test
    void sensitivity_legalSemanticDependency_passes() {
        JavaClasses classes = new ClassFileImporter().importClasses(LegalSemanticFixture.class);
        semanticCoreRule(JavaClass.Predicates.belongToAnyOf(LegalSemanticFixture.class)).check(classes);
    }

    @Test
    void sensitivity_forbiddenSemanticDependency_isRejected() {
        JavaClasses classes = new ClassFileImporter().importClasses(BadSemanticFixture.class);
        ArchRule rule = semanticCoreRule(JavaClass.Predicates.belongToAnyOf(BadSemanticFixture.class));
        assertThrows(AssertionError.class, () -> rule.check(classes));
    }

    @Test
    void sensitivity_forbiddenWriterDependency_isRejected() {
        JavaClasses classes = new ClassFileImporter().importClasses(BadWriterFixture.class);
        ArchRule rule = writerRule();
        assertThrows(AssertionError.class, () -> rule.check(classes));
    }
}
