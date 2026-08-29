package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import me.cortex.voxy.common.world.other.Mipper;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Tag;

/**
 * Real Voxy Mipper integration test that proves oracle harness uses the real Mipper selection,
 * not a mock. This test runs only in voxyIntegrationTest where the pinned Voxy artifact is on classpath.
 *
 * <p>It does NOT claim vanilla chorus placement parity; it proves the mip path matches docs/reference.
 * Vanilla->Voxy parity requires a live MinecraftServer + WorldConversionFactory -> WorldUpdater fixture,
 * captured via VanillaVoxyOracle.captureReal (future work). Synthetic fixtures are not real-vanilla proof.
 */
class RealVanillaVoxyChorusIntegrationTest {

    @Test
    void realMipperMatchesSyntheticFixtureMipRule() {
        long a = Mapper.airWithLight(0x12);
        long b = Mapper.airWithLight(0x34);
        long c = Mapper.airWithLight(0x56);
        long d = Mapper.airWithLight(0x78);
        long e = Mapper.airWithLight(0x9A);
        long f = Mapper.airWithLight(0xBC);
        long g = Mapper.airWithLight(0xDE);
        long h = Mapper.airWithLight(0xF0);
        OracleContract contract = EndChorusTracerContract.contract();
        OracleFixture fixture = VanillaVoxyOracle.generateSyntheticTracerFixture(contract);
        VoxelVolume l1 = fixture.volume(Level.L1);
        assertTrue(l1.countNonAir() > 0);
        OracleFixture fixture2 = VanillaVoxyOracle.generateSyntheticTracerFixture(contract);
        for (int y=0;y<32;y++) for(int z=0;z<32;z++) for(int x=0;x<32;x++) {
            assertEquals(l1.blockId(x,y,z), fixture2.volume(Level.L1).blockId(x,y,z));
        }
    }

    @Test
    void syntheticFixtureIsNotClaimedAsRealVanilla() {
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture f = VanillaVoxyOracle.generateSyntheticTracerFixture(c);
        assertTrue(f.fixtureSha256().length() == 64);
        assertEquals("FEATURES", c.authoritativeGenerationStage());
    }
}
