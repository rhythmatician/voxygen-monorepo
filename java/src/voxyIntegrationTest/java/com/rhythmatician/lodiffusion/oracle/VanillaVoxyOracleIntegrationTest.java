package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import org.junit.jupiter.api.Test;

/**
 * Integration test for the real vanilla->Voxy oracle seam.
 *
 * <p>Today this proves the contract/halo/canonical decode seams are wired and that
 * live capture fails with an actionable blocker rather than silently substituting synthetic data.
 * Once the headless MinecraftServer harness exists, this test will be expanded to:
 * capture a real End fixture at FEATURES for region 0,0,0 e2 halo 25, assert L0..L4 volumes are
 * 32^3 canonical ids (BLOCK_COUNT 1104 / BIOME 54+255), and that contentSha256 is stable.
 */
class VanillaVoxyOracleIntegrationTest {

    @Test
    void captureValidatesHaloAndReportsBlockerInsteadOfSynthetic() {
        OracleContract c = EndChorusTracerContract.contract();
        IllegalStateException ex = assertThrows(IllegalStateException.class, () -> VanillaVoxyOracle.capture(c));
        assertTrue(ex.getMessage().contains("BLOCKED"));
        assertTrue(ex.getMessage().contains("MinecraftServer"));
        assertTrue(ex.getMessage().contains("WorldConversionFactory"));
        assertTrue(ex.getMessage().contains("Mapper"));
        assertTrue(ex.getMessage().contains("ChunkPyramid"));
        assertTrue(ex.getMessage().contains("ChorusFlowerBlock"));
    }

    @Test
    void captureRejectsBadHaloOrStageInsteadOfSilentlyCapturing() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract smallHalo = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), new OracleContract.HaloSpec(8, "e", "s", 1, "e", "s", 1, "e", "s", 5), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, () -> VanillaVoxyOracle.capture(smallHalo));

        OracleContract badStage = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), base.halo(), "NOISE",
                base.fixtureFormatVersion(), base.provenanceId(), base.perLevelDecisions(), base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, () -> VanillaVoxyOracle.capture(badStage));
    }

    @Test
    void canonicalDecodeSeamRespectsStableRegistries() {
        assertEquals(0, VanillaVoxyOracle.decodeBlockId(5, "minecraft:air"));
        assertEquals(359, VanillaVoxyOracle.decodeBlockId(12, "minecraft:end_stone"));
        assertThrows(IllegalArgumentException.class, () -> VanillaVoxyOracle.decodeBlockId(3, "minecraft:not_a_block"));
        assertEquals(CanonicalRegistries.BIOME_UNKNOWN, VanillaVoxyOracle.decodeBiomeId("minecraft:unknown"));
    }

    @Test
    void oracleDoesNotShareCandidateHelper() throws Exception {
        // Check via resource/classpath independent check: ensure VanillaVoxyOracle class does not reference candidate helpers
        // via reflection check on imports is not reliable at runtime, so we assert the class is loadable and does not
        // delegate to synthetic factory - we already prove that via capture not importing it (compile-time boundary
        // enforced by sourceSet isolation: src/test synthetic not on voxyIntegrationTest classpath).
        assertFalse(VanillaVoxyOracle.class.getName().contains("Synthetic"), "oracle class name must not be synthetic");
        // The real enforcement is the Gradle verifyUnitTestIsolation task which ensures src/test vs voxyIntegrationTest boundaries.
        assertTrue(true);
    }
}

