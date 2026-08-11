package com.rhythmatician.lodiffusion.world.noise;

import com.rhythmatician.lodiffusion.voxy.BiomeMapping;

import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.noise.NoiseConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * GPU-paired implementation of {@link BiomeProvider}.
 *
 * <p>Performs the same vanilla {@link BiomeSource#getBiome} lookup as
 * {@link VanillaBiomeProvider}, but exists as a distinct class for three reasons:
 * <ol>
 *   <li><b>Semantic clarity</b> — when the terrain backend is {@code "gpu"},
 *       the {@link UpstreamNoiseContext} should report a GPU-aware biome
 *       provider rather than a vanilla one.</li>
 *   <li><b>Future extension</b> — this class is the natural home for a true
 *       GPU biome readback path once {@code BiomePaletteSSBO} axis ordering
 *       is normalised ({@code [qx][qz][qy] → [qx][qy][qz]}).</li>
 *   <li><b>Logging / metrics</b> — separates biome-lookup log lines so that
 *       GPU and vanilla pipelines are distinguishable in telemetry.</li>
 * </ol>
 *
 * <h2>Implementation note</h2>
 * <p>The climate fields (temperature, humidity, continentalness, erosion,
 * depth, weirdness) are already present in {@link SectionNoiseData} from the
 * GPU noise shader.  Minecraft's {@code MultiNoiseBiomeSource} re-evaluates
 * these same fields internally via the {@code MultiNoiseSampler}.  The values
 * are guaranteed identical because both paths resolve the same density
 * functions at the same quart-centre coordinates.  A future optimisation could
 * skip the re-evaluation by feeding the GPU-sampled climate values directly
 * into the biome search tree; for now, correctness is prioritised.
 *
 * <h2>Thread safety</h2>
 * <p>{@link BiomeSource#getBiome} is immutable after construction.
 * {@link BiomeMapping} is a pure-function helper.  Safe for concurrent use.
 *
 * @see VanillaBiomeProvider
 * @see BiomeProvider
 * @see BiomeMapping
 */
public final class GpuBiomeProvider implements BiomeProvider {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/BiomeGpu");

    private final BiomeSource biomeSource;
    private final NoiseConfig noiseConfig;

    /**
     * @param biomeSource the world's biome source (usually {@code MultiNoiseBiomeSource})
     * @param noiseConfig the noise config (provides the {@code MultiNoiseSampler})
     */
    public GpuBiomeProvider(BiomeSource biomeSource, NoiseConfig noiseConfig) {
        this.biomeSource = biomeSource;
        this.noiseConfig = noiseConfig;
        LOG.info("[BiomeGpu] Initialised — source={}", biomeSource.getClass().getSimpleName());
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
        return "gpu_climate";
    }
}
