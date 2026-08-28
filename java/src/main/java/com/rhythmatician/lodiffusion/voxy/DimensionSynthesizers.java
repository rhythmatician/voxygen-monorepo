package com.rhythmatician.lodiffusion.voxy;

import net.minecraft.registry.RegistryKey;
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
        if (dimension == null) {
            return new EndDimensionSynthesizer(access, telemetry, seed);
        }
        String id = dimension.getValue().toString();
        if ("minecraft:the_end".equals(id)) {
            return new EndDimensionSynthesizer(access, telemetry, seed);
        }
        // Explicit unresolved: do not reuse EndL4DeterministicCandidate/ExactEndL1Candidate for other dimensions.
        // Overworld [-64,320) and Nether [0,128) have different NoiseSettings, cell sizes, and aquifersEnabled.
        // See DimensionGenerationDomain and docs/adr/0013 inventory.
        return (level, origin) -> {
            throw new UnsupportedOperationException(
                    "Dimension " + id + " synthesizer not yet implemented. "
                    + "Needs DimensionGenerationDomain + FeatureEligibility + Fidelity Profile. "
                    + "See CONTEXT.md:Generation and Worldgen Partition v1.");
        };
    }
}
