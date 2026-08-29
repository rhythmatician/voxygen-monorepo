package com.rhythmatician.lodiffusion.voxy;

import net.minecraft.registry.RegistryKey;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Factory for per-dimension synthesizers. Keeps GenerationSession free of if(End) branching.
 * End is the only dimension with a full implementation today; Overworld/Nether are explicit
 * unresolved Partition Decision State — no silent reuse of End candidates.
 *
 * <p>Chorus overlay for End is disabled by default per ADR 0015 (requires explicit Partition
 * decision with oracle #233 and disposition #220). Experimental chorus activation is End-specific
 * via {@link EndDimensionSynthesizer} or {@link GenerationSession#produceEndRefinementChildWithChorus(Level, SectionPos, long)}; not via the generic factory.
 */
public final class DimensionSynthesizers {
    private DimensionSynthesizers() {}

    public static DimensionSynthesizer forDimension(
            RegistryKey<World> dimension,
            WorldNoiseAccess access,
            ExactL1SamplingTelemetry telemetry,
            long seed) {
        if (dimension == null) throw new IllegalArgumentException("dimension is null");
        var id = dimension.getValue();
        boolean isEnd = id.equals(Identifier.of("minecraft", "the_end"));
        boolean isOverworld = id.equals(Identifier.of("minecraft", "overworld"));
        boolean isNether = id.equals(Identifier.of("minecraft", "the_nether"));
        if (!isEnd && !isOverworld && !isNether)
            throw new IllegalArgumentException("unknown dimension " + dimension);
        if (isEnd) {
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
