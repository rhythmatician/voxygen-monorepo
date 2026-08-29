package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import org.junit.jupiter.api.Test;

class SyntheticFixtureTest {

    @Test
    void syntheticFixtureExposesL0ToL4() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        for (Level l : Level.values()) {
            assertTrue(f.hasLevel(l), "fixture must have " + l);
            VoxelVolume v = f.volume(l);
            assertEquals(32, v.extent());
        }
    }

    @Test
    void syntheticFixtureIsDeterministic() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture a = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        OracleFixture b = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
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
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
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
                base.seed(), base.region(), new OracleContract.HaloSpec(8, "feature 8", "test", 1, "gen 1ch", "test", 1, "voxy 1", "test", 10), base.authoritativeGenerationStage(),
                base.fixtureFormatVersion(), base.oracleFixtureId(), base.perLevelDisposition(),
                base.roles(), base.benchmarkPolicy());
        assertThrows(IllegalArgumentException.class, () -> SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(smallHalo));
    }

    @Test
    void perLevelDecisionsAreUnresolved() {
        OracleContract c = EndChorusTracerContract.contract();
        assertEquals("UNRESOLVED", c.perLevelDecisions().l4().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l3().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l2().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l1().disposition());
        assertEquals("UNRESOLVED", c.perLevelDecisions().l0().disposition());
        OracleFixture f = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        // Real fixture determines L4/L3 behavior; synthetic harness does not predeclare expected 0 - only L0 must contain chorus
        // Do not assert L4/L3 omits chorus; record counts without outcome-dependent assertions
        assertTrue(countChorus(f.volume(Level.L2)) > 0, "L2 should have chorus");
        assertTrue(countChorus(f.volume(Level.L1)) > 0, "L1 should have chorus");
        assertTrue(countChorus(f.volume(Level.L0)) > 0, "L0 should have chorus");
    }

    @Test
    void differentSeedGivesDifferentFixtureId() {
        OracleContract base = EndChorusTracerContract.contract();
        // Seed is pinned to 42 for real oracle; test different input via different outer-island blockRegion (still seed 42) gives different content hash.
        // BlockRegion 1600,64,0 (SectionPos 100,4,0) is outer island dist2 10000>4096 and produces different synthetic volumes vs 1536,64,0.
        OracleContract other = OracleContract.builder()
                .schemaVersion(base.schemaVersion())
                .responsibilityId(base.responsibilityId())
                .dimension(base.dimension())
                .frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion())
                .minecraftSourceRevision(base.minecraftSourceRevision())
                .minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion())
                .voxyCommit(base.voxyCommit())
                .voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion())
                .canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion())
                .canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences())
                .inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(42L)
                .region(new OracleContract.RegionSpec(100, 4, 0, 2))
                .blockRegion(new OracleContract.BlockRegionSpec(1600, 64, 0, 32))
                .halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage())
                .fixtureFormatVersion(base.fixtureFormatVersion())
                .provenanceId("end_chorus__s42__b1600_64_0_e32__fh8_gh1c_vh1_ch25__mc1.21.11_voxy0.2.11-alpha__fmtv3")
                .perLevelDecisions(base.perLevelDecisions())
                .roles(base.roles())
                .benchmarkPolicy(base.benchmarkPolicy())
                .build();
        OracleFixture fa = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(base);
        OracleFixture fb = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(other);
        assertNotEquals(fa.fixtureSha256(), fb.fixtureSha256());
        assertNotEquals(fa.contentSha256(), fb.contentSha256());
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



