package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.*;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizer;
import com.rhythmatician.voxygen.generation.dimension.DimensionSynthesizers;
import com.rhythmatician.voxygen.generation.dimension.end.EndDimensionSynthesizer;
import com.rhythmatician.voxygen.generation.dimension.end.ExactL1SamplingTelemetry;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;

class DimensionSynthesizersContractTest {

    private static RegistryKey<World> key(String name) {
        return RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", name));
    }

    @Test
    void forDimensionNullFailsClosed() {
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        assertThrows(IllegalArgumentException.class, () -> DimensionSynthesizers.forDimension(null, access, telemetry, 0L));
    }

    @Test
    void forDimensionUnknownFailsClosed() {
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        RegistryKey<World> unknown = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "unknown_dim_xyz"));
        assertThrows(IllegalArgumentException.class, () -> DimensionSynthesizers.forDimension(unknown, access, telemetry, 0L));
    }

    @Test
    void endResolvesToEndDimensionSynthesizerDisabledByDefault() {
        RegistryKey<World> end = key("the_end");
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        DimensionSynthesizer synth = DimensionSynthesizers.forDimension(end, access, telemetry, 42L);
        assertInstanceOf(EndDimensionSynthesizer.class, synth);
        EndDimensionSynthesizer endSynth = (EndDimensionSynthesizer) synth;
        assertFalse(endSynth.isChorusOverlayEnabled(), "chorus overlay must be disabled by default per ADR 0015 until #220/#233");
    }

    @Test
    void endWithChorusOverlayEnabledWhenRequested() {
        RegistryKey<World> end = key("the_end");
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        DimensionSynthesizer synth = new EndDimensionSynthesizer(access, telemetry, 42L, true);
        assertInstanceOf(EndDimensionSynthesizer.class, synth);
        assertTrue(((EndDimensionSynthesizer) synth).isChorusOverlayEnabled());
        // Generic factory always returns disabled
        RegistryKey<World> end2 = key("the_end");
        DimensionSynthesizer generic = DimensionSynthesizers.forDimension(end2, access, telemetry, 42L);
        assertFalse(((EndDimensionSynthesizer) generic).isChorusOverlayEnabled());
    }

    @Test
    void overworldRemainsUnsupported() {
        RegistryKey<World> overworld = key("overworld");
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        DimensionSynthesizer synth = DimensionSynthesizers.forDimension(overworld, access, telemetry, 0L);
        assertNotNull(synth);
        SectionPos origin = new SectionPos(0, 0, 0);
        var ex = assertThrows(UnsupportedOperationException.class, () -> synth.synthesize(Level.L1, origin));
        assertTrue(ex.getMessage().contains("overworld") || ex.getMessage().contains("not yet implemented"));
    }

    @Test
    void netherRemainsUnsupported() {
        RegistryKey<World> nether = key("the_nether");
        WorldNoiseAccess access = Mockito.mock(WorldNoiseAccess.class);
        var telemetry = new ExactL1SamplingTelemetry();
        DimensionSynthesizer synth = DimensionSynthesizers.forDimension(nether, access, telemetry, 0L);
        assertNotNull(synth);
        SectionPos origin = new SectionPos(0, 0, 0);
        assertThrows(UnsupportedOperationException.class, () -> synth.synthesize(Level.L2, origin));
    }
}
