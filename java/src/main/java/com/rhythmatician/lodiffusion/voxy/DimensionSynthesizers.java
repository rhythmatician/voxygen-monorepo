package com.rhythmatician.lodiffusion.voxy;

import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Factory for per-dimension synthesizers. Keeps GenerationSession free of if(End) branching.
 * End is the only dimension with a full implementation today; Overworld/Nether are explicit
 * unresolved Partition Decision State — no silent reuse of End candidates.
 */
public final class DimensionSynthesizers {
    private DimensionSynthesizers() {}

    public static DimensionSynthesizer forDimension(
            RegistryKey<World> dimension,
            WorldNoiseAccess access,
            ExactL1SamplingTelemetry telemetry,
            long seed) {
        if (dimension == null) throw new IllegalArgumentException("dimension is null");
        RegistryKey<World> end = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_end"));
        RegistryKey<World> overworld = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "overworld"));
        RegistryKey<World> nether = RegistryKey.of(RegistryKeys.WORLD, Identifier.of("minecraft", "the_nether"));
        if (!dimension.equals(end) && !dimension.equals(overworld) && !dimension.equals(nether))
            throw new IllegalArgumentException("unknown dimension " + dimension);
        if (dimension.equals(end)) {
            return new EndDimensionSynthesizer(access, telemetry, seed);
        }
        // Explicit unresolved: do not reuse EndL4DeterministicCandidate/ExactEndL1Candidate for other dimensions.
        // Overworld [-64,320) and Nether [0,128) have different NoiseSettings, cell sizes, and aquifersEnabled.
        // See DimensionGenerationDomain and docs/adr/0013 inventory.
        return (level, origin) -> {
            throw new UnsupportedOperationException(
                    "Dimension " + dimension + " synthesizer not yet implemented. "
                    + "Needs DimensionGenerationDomain + Fidelity Profile. "
                    + "See CONTEXT.md:Generation and Worldgen Partition v1.");
        };
    }
}