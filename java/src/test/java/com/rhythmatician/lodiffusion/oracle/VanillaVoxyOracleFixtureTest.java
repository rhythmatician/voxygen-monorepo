package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import org.junit.jupiter.api.Test;

class VanillaVoxyOracleFixtureTest {

    @Test
    void syntheticFixtureExposesL0ToL4() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        for (Level l : Level.values()) {
            assertTrue(f.hasLevel(l), "fixture must have " + l);
            VoxelVolume v = f.volume(l);
            assertEquals(32, v.extent());
        }
    }

    @Test
    void syntheticFixtureIsDeterministic() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture a = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        OracleFixture b = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        for (Level l : Level.values()) {
            VoxelVolume va = a.volume(l);
            VoxelVolume vb = b.volume(l);
            for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                assertEquals(va.blockId(x, y, z), vb.blockId(x, y, z), "deterministic at " + l + " " + x + "," + y + "," + z);
            }
        }
        assertEquals(a.fixtureSha256(), b.fixtureSha256());
    }

    @Test
    void fixtureStoresCanonicalIdsNotRawVoxy() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        // Every non-air block must be a valid canonical id; fixture never exposes raw Voxy Mapper ids (20-bit range up to 1M vs canonical 1104)
        for (Level l : Level.values()) {
            VoxelVolume v = f.volume(l);
            for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                int id = v.blockId(x, y, z);
                assertTrue(id >= 0 && id < 1104, "canonical block id range for " + l + " got " + id);
            }
        }
    }

    @Test
    void haloTooSmallFailsFixtureGeneration() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract smallHalo = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                base.seed(), base.region(), new OracleContract.HaloSpec(8, "too small", "test"), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, () -> VanillaVoxyOracle.generateSyntheticTracerFixture(smallHalo));
    }

    @Test
    void perLevelDispositionOmitsAtL4L3() {
        OracleContract c = EndChorusTracerContract.contract();
        assertEquals("omit", c.perLevelDisposition().l4());
        assertEquals("omit", c.perLevelDisposition().l3());
        assertEquals("claim", c.perLevelDisposition().l2());
        OracleFixture f = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        // L4/L3 must have zero chorus (honest omission)
        assertEquals(0, countChorus(f.volume(Level.L4)), "L4 must omit chorus");
        assertEquals(0, countChorus(f.volume(Level.L3)), "L3 must omit chorus");
        assertTrue(countChorus(f.volume(Level.L2)) > 0, "L2 should have chorus");
        assertTrue(countChorus(f.volume(Level.L1)) > 0, "L1 should have chorus");
        assertTrue(countChorus(f.volume(Level.L0)) > 0, "L0 should have chorus");
    }

    @Test
    void differentSeedGivesDifferentFixtureId() {
        OracleContract base = EndChorusTracerContract.contract();
        OracleContract other = new OracleContract(
                base.schemaVersion(), base.responsibilityId(), base.dimension(), base.frozenWorldgenProfileId(),
                base.minecraftVersion(), base.minecraftSourceRevision(), base.minecraftJarSha256(),
                base.voxyVersion(), base.voxyCommit(), base.voxyArtifactSha256(),
                base.canonicalBlockRegistryVersion(), base.canonicalBlockRegistrySha256(),
                base.canonicalBiomeRegistryVersion(), base.canonicalBiomeRegistrySha256(),
                base.inspectedMinecraftReferences(), base.inspectedVoxyReferences(),
                999L, base.region(), base.halo(), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), "end_chorus__s999__other", base.perLevelDisposition(),
                base.claimRole(), base.dependencyRole(), base.benchmarkPolicy());
        OracleFixture fa = VanillaVoxyOracle.generateSyntheticTracerFixture(base);
        OracleFixture fb = VanillaVoxyOracle.generateSyntheticTracerFixture(other);
        assertNotEquals(fa.fixtureSha256(), fb.fixtureSha256());
    }

    private static int countChorus(VoxelVolume v) {
        int c = 0;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = v.blockId(x, y, z);
            if (id == 196 || id == 197) c++;
        }
        return c;
    }
}
