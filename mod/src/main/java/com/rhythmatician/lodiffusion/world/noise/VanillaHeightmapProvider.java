package com.rhythmatician.lodiffusion.world.noise;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Arrays;
import java.util.function.Predicate;

/**
 * Vanilla CPU implementation of {@link HeightmapProvider}.
 *
 * <p>Derives heightmaps (WORLD_SURFACE_WG and OCEAN_FLOOR_WG) by running a
 * full-chunk {@link ChunkNoiseSampler} and scanning columns top-down for the
 * first solid block — identical to what happens inside
 * {@code NoiseChunkGenerator.populateNoise()}.
 *
 * <h2>Performance</h2>
 * <p>One {@code ChunkNoiseSampler(4, ...)} covers 4 horizontal noise cells
 * (16 block columns).  The dominant cost is {@code sampleStartDensity()} /
 * {@code sampleEndDensity()}, which evaluate the full noise router tree.
 * This is called ~5 times per chunk, vs. 512 times with per-column samplers.
 * Net speedup: ~64×.
 *
 * <h2>Thread safety</h2>
 * <p>Each call creates its own {@code ChunkNoiseSampler} — no shared mutable
 * state.  Safe to call from multiple threads concurrently.
 *
 * @see HeightmapData
 * @see HeightmapProvider
 */
public final class VanillaHeightmapProvider implements HeightmapProvider {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/HeightmapVanilla");

    private final ServerWorld serverWorld;
    private final ChunkGeneratorSettings settings;
    private final NoiseConfig noiseConfig;

    /**
     * @param serverWorld the server-side world (needed for {@code trimHeight()})
     * @param generator   the chunk generator (must be {@link NoiseChunkGenerator})
     * @param noiseConfig the noise config for the world
     * @throws IllegalArgumentException if {@code generator} is not a
     *         {@code NoiseChunkGenerator}
     */
    public VanillaHeightmapProvider(ServerWorld serverWorld,
                                    ChunkGenerator generator,
                                    NoiseConfig noiseConfig) {
        if (!(generator instanceof NoiseChunkGenerator ncg)) {
            throw new IllegalArgumentException(
                    "VanillaHeightmapProvider requires NoiseChunkGenerator, got "
                    + generator.getClass().getSimpleName());
        }
        this.serverWorld = serverWorld;
        this.settings = ncg.getSettings().value();
        this.noiseConfig = noiseConfig;

        LOG.info("[HeightmapVanilla] Initialised — seaLevel={}", settings.seaLevel());
    }

    @Override
    public HeightmapData sampleHeightmaps(int sectionX, int sectionZ) {
        GenerationShapeConfig shape = settings.generationShapeConfig().trimHeight(serverWorld);

        int hCells   = 16 / shape.horizontalCellBlockCount();
        int hCellB   = shape.horizontalCellBlockCount();
        int vCellB   = shape.verticalCellBlockCount();
        int minCellY = MathHelper.floorDiv(shape.minimumY(), vCellB);
        int cellHeight = MathHelper.floorDiv(shape.height(), vCellB);
        int startX   = sectionX * 16;
        int startZ   = sectionZ * 16;
        int bottomY  = shape.minimumY();

        // Replicate NoiseChunkGenerator.createFluidLevelSampler()
        int seaLevel = settings.seaLevel();
        AquiferSampler.FluidLevel lavaLevel =
                new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        AquiferSampler.FluidLevel seaFluid =
                new AquiferSampler.FluidLevel(seaLevel, settings.defaultFluid());
        AquiferSampler.FluidLevelSampler fluidSampler =
                (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaLevel : seaFluid;

        // One sampler for the entire 16×16 chunk
        ChunkNoiseSampler sampler = new ChunkNoiseSampler(
                hCells, noiseConfig, startX, startZ, shape,
                DensityFunctionTypes.Beardifier.INSTANCE,
                settings, fluidSampler, Blender.getNoBlending());

        Predicate<BlockState> surfacePred =
                Heightmap.Type.WORLD_SURFACE_WG.getBlockPredicate();
        Predicate<BlockState> oceanPred =
                Heightmap.Type.OCEAN_FLOOR_WG.getBlockPredicate();

        float[][] surface    = new float[16][16];
        float[][] oceanFloor = new float[16][16];
        for (float[] row : surface)    Arrays.fill(row, bottomY);
        for (float[] row : oceanFloor) Arrays.fill(row, bottomY);

        boolean[] surfaceDone = new boolean[256];
        boolean[] oceanDone   = new boolean[256];

        // Mirror NoiseChunkGenerator.populateNoise() loop structure — same
        // call order so the sampler's state machine advances correctly.
        sampler.sampleStartDensity();

        for (int o = 0; o < hCells; o++) {
            sampler.sampleEndDensity(o);

            for (int p = 0; p < hCells; p++) {
                for (int r = cellHeight - 1; r >= 0; r--) {
                    sampler.onSampledCellCorners(r, p);

                    for (int s = vCellB - 1; s >= 0; s--) {
                        int blockY = (minCellY + r) * vCellB + s;
                        sampler.interpolateY(blockY, (double) s / vCellB);

                        for (int w = 0; w < hCellB; w++) {
                            int blockX = startX + o * hCellB + w;
                            int lx     = o * hCellB + w;
                            sampler.interpolateX(blockX, (double) w / hCellB);

                            for (int z = 0; z < hCellB; z++) {
                                int blockZ = startZ + p * hCellB + z;
                                int lz     = p * hCellB + z;
                                sampler.interpolateZ(blockZ, (double) z / hCellB);

                                int idx = lx * 16 + lz;
                                if (surfaceDone[idx] && oceanDone[idx]) continue;

                                BlockState state = sampler.sampleBlockState();
                                BlockState actual = (state == null)
                                        ? settings.defaultBlock() : state;

                                if (!surfaceDone[idx] && surfacePred.test(actual)) {
                                    surface[lx][lz] = blockY + 1;
                                    surfaceDone[idx] = true;
                                }
                                if (!oceanDone[idx] && oceanPred.test(actual)) {
                                    oceanFloor[lx][lz] = blockY + 1;
                                    oceanDone[idx] = true;
                                }
                            }
                        }
                    }
                }
            }
        }

        sampler.stopInterpolation();
        return new HeightmapData(surface, oceanFloor);
    }

    @Override
    public String backendName() {
        return "vanilla_cpu";
    }
}
