package com.rhythmatician.lodiffusion.world.noise;

import com.rhythmatician.voxygen.semantic.biome.BiomeMapping;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.noise.NoiseConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Vanilla CPU implementation of {@link BiomeProvider}.
 *
 * <p>Delegates to {@link BiomeSource#getBiome} at each quart-resolution
 * lattice point, then maps the result to a canonical palette index via
 * {@link BiomeMapping#toCanonicalId(RegistryEntry)}.
 *
 * <h2>How vanilla biome lookup works</h2>
 * <p>The {@code MultiNoiseBiomeSource} (used for overworld) selects the biome
 * whose 6-parameter noise point (temperature, humidity, continentalness,
 * erosion, depth, weirdness) is closest to the sampled climate values.
 * The lookup takes quart coordinates and a {@code MultiNoiseSampler} which
 * re-evaluates the 6 climate fields.  This means the climate noise is sampled
 * twice — once in the {@link NoiseRouterSampler} and once here.  The values
 * are guaranteed to be identical because both use the same density functions
 * and the same quart-centre coordinates.
 *
 * <h2>Thread safety</h2>
 * <p>{@link BiomeSource#getBiome} is immutable after construction.
 * {@link BiomeMapping} is a pure-function helper.  Safe for concurrent use.
 *
 * @see BiomeMapping
 * @see BiomeProvider
 */
public final class VanillaBiomeProvider implements BiomeProvider {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/BiomeVanilla");

    private final BiomeSource biomeSource;
    private final NoiseConfig noiseConfig;

    /**
     * @param biomeSource the world's biome source (usually {@code MultiNoiseBiomeSource})
     * @param noiseConfig the noise config (provides the {@code MultiNoiseSampler})
     */
    public VanillaBiomeProvider(BiomeSource biomeSource, NoiseConfig noiseConfig) {
        this.biomeSource = biomeSource;
        this.noiseConfig = noiseConfig;
        LOG.info("[BiomeVanilla] Initialised — source={}", biomeSource.getClass().getSimpleName());
    }

    @Override
    public int[][][] classifyBiomes(int sectionX, int sectionY, int sectionZ,
                                    SectionNoiseData noiseData) {
        int[][][] biomes = new int[4][2][4];
        int baseBlockX = sectionX * 16;
        int baseBlockY = sectionY * 16;
        int baseBlockZ = sectionZ * 16;

        for (int qx = 0; qx < 4; qx++) {
            for (int qy = 0; qy < 2; qy++) {
                for (int qz = 0; qz < 4; qz++) {
                    // Noise-cell-centre block coords → quart coords for BiomeSource.
                    // cellHeight=8 → 2 Y cells per section, centres at qy*8+4.
                    int blockX = baseBlockX + qx * 4 + 2;
                    int blockY = baseBlockY + qy * 8 + 4;
                    int blockZ = baseBlockZ + qz * 4 + 2;

                    RegistryEntry<Biome> entry = biomeSource.getBiome(
                            blockX >> 2, blockY >> 2, blockZ >> 2,
                            noiseConfig.getMultiNoiseSampler());

                    biomes[qx][qy][qz] = BiomeMapping.toCanonicalId(entry);
                }
            }
        }
        return biomes;
    }

    @Override
    public String backendName() {
        return "vanilla_cpu";
    }
}
