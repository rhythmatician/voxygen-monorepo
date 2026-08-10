package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.util.math.MathHelper;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;

import java.util.Arrays;
import java.util.function.Predicate;


/**
 * Provides server-side access to Minecraft's noise generators for sampling
 * heightmap, biome, and density-router values at <em>any</em> (x, z) coordinate
 * — <b>without needing a loaded chunk</b>.
 *
 * <p>This replaces the synthetic sine-wave heightmap and constant-biome fallback
 * that was previously used for distant (unloaded) sections.  It works by
 * tapping into the integrated server's {@link ChunkGenerator} and
 * {@link NoiseConfig} directly.
 *
 * <h3>Limitations</h3>
 * <ul>
 *   <li>Only works when an integrated server is available (singleplayer / LAN).
 *       Returns {@code null} from {@link #tryCreate(World)} on dedicated servers.</li>
 *   <li>All sampling methods are pure computation (no world state mutation),
 *       so they are safe to call from the LOD worker thread.</li>
 * </ul>
 */
public final class WorldNoiseAccess {

    private final ServerWorld serverWorld;
    private final ChunkGenerator generator;
    private final NoiseConfig noiseConfig;
    private final BiomeSource biomeSource;

    private WorldNoiseAccess(ServerWorld serverWorld, ChunkGenerator generator,
                             NoiseConfig noiseConfig) {
        this.serverWorld = serverWorld;
        this.generator = generator;
        this.noiseConfig = noiseConfig;
        this.biomeSource = generator.getBiomeSource();
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Try to create a {@code WorldNoiseAccess} from a server instance.
     *
     * @param server      the Minecraft server (integrated or dedicated)
     * @param clientWorld the client-side world (used to determine dimension)
     * @return a new instance, or {@code null} if {@code NoiseConfig} is
     *         not available (e.g., non-noise chunk generator)
     */
    public static WorldNoiseAccess tryCreate(MinecraftServer server, World clientWorld) {
        try {
            if (server == null) {
                HelloTerrainMod.LOGGER.info(
                        "[WorldNoiseAccess] No server provided — cannot bind noise pipeline");
                return null;
            }

            // Get the server-side world for the same dimension as the client
            RegistryKey<World> dimKey = clientWorld.getRegistryKey();
            ServerWorld serverWorld = server.getWorld(dimKey);
            if (serverWorld == null) {
                HelloTerrainMod.LOGGER.warn(
                        "[WorldNoiseAccess] Could not get ServerWorld for dimension {}",
                        dimKey.getValue());
                return null;
            }

            return tryCreate(serverWorld);

        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[WorldNoiseAccess] Failed to initialize: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Try to create a {@code WorldNoiseAccess} directly from a {@link ServerWorld}.
     *
     * <p>This overload is useful from server-side code (e.g., commands) that
     * already has a {@code ServerWorld} reference.
     *
     * @param serverWorld the server-side world
     * @return a new instance, or {@code null} if {@code NoiseConfig} is unavailable
     */
    public static WorldNoiseAccess tryCreate(ServerWorld serverWorld) {
        try {
            ChunkGenerator gen = serverWorld.getChunkManager().getChunkGenerator();

            NoiseConfig nc = tryGetNoiseConfig(serverWorld);
            if (nc == null) {
                HelloTerrainMod.LOGGER.warn(
                        "[WorldNoiseAccess] NoiseConfig unavailable — cannot use noise access");
                return null;
            }

            HelloTerrainMod.LOGGER.info(
                    "[WorldNoiseAccess] Successfully bound to server noise pipeline");
            return new WorldNoiseAccess(serverWorld, gen, nc);

        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[WorldNoiseAccess] Failed to initialize: {}", e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Heightmap sampling
    // ------------------------------------------------------------------

    /**
     * Sample the real surface heightmap for a 16×16 section column.
     *
     * <p>Delegates to {@link #sampleBothHeightmaps} so that the fast
     * {@code ChunkNoiseSampler(4)} path is used for overworld-style generators
     * (~64× faster than 256 independent {@code getHeight()} calls).
     *
     * @param sectionX section X coordinate (chunk X)
     * @param sectionZ section Z coordinate (chunk Z)
     * @return float[16][16] of surface Y values in block coordinates
     */
    public float[][] sampleHeightmap(int sectionX, int sectionZ) {
        return sampleBothHeightmaps(sectionX, sectionZ)[0];  // [0] = WORLD_SURFACE_WG
    }

    /**
     * Sample BOTH heightmaps (WORLD_SURFACE_WG and OCEAN_FLOOR_WG) for a chunk
     * in a single pass using a full-chunk {@link ChunkNoiseSampler}.
     *
     * <p>Constructs one {@code ChunkNoiseSampler(4, ...)} covering the entire
     * 16×16 chunk (4 horizontal noise cells), then walks all block columns
     * top-down using the same interpolation loop as
     * {@code NoiseChunkGenerator.populateNoise()}, recording the first solid
     * block per column for each heightmap type.
     *
     * <p><b>Why this is fast (~64× over {@code getHeight()} ×256):</b>
     * The dominant cost is {@code sampleStartDensity()} / {@code sampleEndDensity()},
     * which evaluate the full noise router tree to fill interpolator buffers.
     * Here we call these <em>5 times total</em> (once + 4 horizontal cells) for
     * the whole chunk. The equivalent with independent per-column samplers
     * ({@code horizontalCellCount=1}) is 512 calls (256 columns × 2 types).
     * Within each 4×4 cell, all 64 block states are cheap trilinear interpolation.
     *
     * <p><b>Thread safety:</b> Each invocation creates its own
     * {@code ChunkNoiseSampler} with independent interpolator state. No shared
     * mutable state — safe to call from N threads simultaneously.
     *
     * <p><b>No side effects:</b> No chunks are created or cached. Pure computation
     * identical to what happens inside {@code populateNoise()}, minus block writes.
     *
     * @param sectionX section X coordinate (chunk X)
     * @param sectionZ section Z coordinate (chunk Z)
     * @return {@code float[2][16][16]}: index 0 = WORLD_SURFACE_WG,
     *         index 1 = OCEAN_FLOOR_WG. Values are {@code topSolidBlockY + 1},
     *         consistent with {@link ChunkGenerator#getHeight}.
     */
    public float[][][] sampleBothHeightmaps(int sectionX, int sectionZ) {
        if (!(generator instanceof NoiseChunkGenerator ncg)) {
            // Non-noise generator (e.g. flat world) — fall back to per-column sampling
            float[][] surface = sampleHeightmap(sectionX, sectionZ, Heightmap.Type.WORLD_SURFACE_WG);
            float[][] ocean   = sampleHeightmap(sectionX, sectionZ, Heightmap.Type.OCEAN_FLOOR_WG);
            return new float[][][] { surface, ocean };
        }

        ChunkGeneratorSettings settings = ncg.getSettings().value();
        GenerationShapeConfig shape = settings.generationShapeConfig().trimHeight(serverWorld);

        // Standard overworld values: hCells=4, hCellB=4, vCellB=8, minCellY=-8, cellHeight=48
        int hCells     = 16 / shape.horizontalCellBlockCount();
        int hCellB     = shape.horizontalCellBlockCount();
        int vCellB     = shape.verticalCellBlockCount();
        int minCellY   = MathHelper.floorDiv(shape.minimumY(), vCellB);
        int cellHeight = MathHelper.floorDiv(shape.height(),   vCellB);
        int startX     = sectionX * 16;
        int startZ     = sectionZ * 16;

        // Replicate NoiseChunkGenerator.createFluidLevelSampler().
        // The private Supplier<FluidLevelSampler> on NoiseChunkGenerator is
        // inaccessible, so we reconstruct it from the public settings.
        int seaLevel = settings.seaLevel();
        AquiferSampler.FluidLevel lavaLevel = new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        AquiferSampler.FluidLevel seaFluid  = new AquiferSampler.FluidLevel(seaLevel, settings.defaultFluid());
        AquiferSampler.FluidLevelSampler fluidSampler =
                (x, y, z) -> y < Math.min(-54, seaLevel) ? lavaLevel : seaFluid;

        // One sampler for the entire 16×16 chunk — 4 horizontal cells × 4.
        // Beardifier.INSTANCE is a no-op since we have no structure bounding boxes.
        ChunkNoiseSampler sampler = new ChunkNoiseSampler(
                hCells, noiseConfig, startX, startZ, shape,
                DensityFunctionTypes.Beardifier.INSTANCE,
                settings, fluidSampler, Blender.getNoBlending());

        Predicate<BlockState> surfacePred = Heightmap.Type.WORLD_SURFACE_WG.getBlockPredicate();
        Predicate<BlockState> oceanPred   = Heightmap.Type.OCEAN_FLOOR_WG.getBlockPredicate();

        int bottomY = shape.minimumY();
        float[][] surface    = new float[16][16];
        float[][] oceanFloor = new float[16][16];
        for (float[] row : surface)    Arrays.fill(row, bottomY);
        for (float[] row : oceanFloor) Arrays.fill(row, bottomY);

        // Per-column flags: once both heightmaps are found for a column (iterating
        // top-down), we skip sampleBlockState() for it. interpolate*() still runs
        // in order so the sampler's state machine advances correctly.
        boolean[] surfaceDone = new boolean[256];
        boolean[] oceanDone   = new boolean[256];

        // Mirror of NoiseChunkGenerator.populateNoise() — same loop structure,
        // same call order — but collecting heightmaps instead of writing blocks.
        sampler.sampleStartDensity();

        for (int o = 0; o < hCells; o++) {            // horizontal cell X (0-3)
            sampler.sampleEndDensity(o);

            for (int p = 0; p < hCells; p++) {        // horizontal cell Z (0-3)
                for (int r = cellHeight - 1; r >= 0; r--) {  // vertical cell, top → bottom
                    sampler.onSampledCellCorners(r, p);

                    for (int s = vCellB - 1; s >= 0; s--) {  // block within cell, top → bottom
                        int blockY = (minCellY + r) * vCellB + s;
                        sampler.interpolateY(blockY, (double) s / vCellB);

                        for (int w = 0; w < hCellB; w++) {   // block X within cell
                            int blockX = startX + o * hCellB + w;
                            int lx     = o * hCellB + w;
                            sampler.interpolateX(blockX, (double) w / hCellB);

                            for (int z = 0; z < hCellB; z++) { // block Z within cell
                                int blockZ = startZ + p * hCellB + z;
                                int lz     = p * hCellB + z;
                                sampler.interpolateZ(blockZ, (double) z / hCellB);

                                int idx = lx * 16 + lz;
                                if (surfaceDone[idx] && oceanDone[idx]) continue;

                                BlockState state = sampler.sampleBlockState();
                                // null from ChunkNoiseSampler means AIR; fall back
                                // to default block (stone) for density > 0 regions
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
        return new float[][][] { surface, oceanFloor };
    }

    /**
     * Sample a heightmap of the given type for a 16×16 section column.
     *
     * <p>Uses {@link ChunkGenerator#getHeight(int, int, Heightmap.Type,
     * net.minecraft.world.HeightLimitView, NoiseConfig)} — pure computation,
     * no loaded chunk needed.
     *
     * @param sectionX section X coordinate (chunk X)
     * @param sectionZ section Z coordinate (chunk Z)
     * @param type     the heightmap type (e.g. WORLD_SURFACE_WG, OCEAN_FLOOR_WG)
     * @return float[16][16] of Y values in block coordinates
     */
    public float[][] sampleHeightmap(int sectionX, int sectionZ, Heightmap.Type type) {
        float[][] hm = new float[16][16];
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;

        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                hm[lx][lz] = generator.getHeight(
                        baseX + lx, baseZ + lz,
                        type, serverWorld, noiseConfig);
            }
        }
        return hm;
    }

    // ------------------------------------------------------------------
    // Biome sampling
    // ------------------------------------------------------------------

    /**
     * Sample biome names for a 16×16 section column using the
     * server-side {@link BiomeSource}.
     *
     * <p>Biomes are sampled at quarter-resolution (4-block steps) as per
     * Minecraft's biome storage convention, then each block column gets
     * the biome of its containing quarter.
     *
     * <p>Returns the biome's registry key name (e.g. {@code "minecraft:plains"}).
     * The Python pipeline maps these to canonical integer IDs via a shared
     * alphabetical biome mapping.
     *
     * @param sectionX section X coordinate
     * @param sectionZ section Z coordinate
     * @param heightmap surface heightmap for Y coordinate
     * @return String[16][16] of biome registry key names
     */
    public String[][] sampleBiomeNames(int sectionX, int sectionZ, float[][] heightmap) {
        String[][] biomes = new String[16][16];
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;

        // Biomes are stored at quarter resolution — sample at quart coords
        // and fill the 4×4 block region with the same value.
        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                int bx = baseX + qx * 4 + 2;  // center of quartet
                int bz = baseZ + qz * 4 + 2;
                int surfaceY = (int) heightmap[qx * 4][qz * 4];

                // BiomeSource.getBiome works at quart coordinates
                RegistryEntry<Biome> biomeEntry = biomeSource.getBiome(
                        bx >> 2, surfaceY >> 2, bz >> 2,
                        noiseConfig.getMultiNoiseSampler());

                // Extract registry key name (e.g. "minecraft:plains")
                String biomeName = biomeEntry.getKey()
                        .map(key -> key.getValue().toString())
                        .orElse("minecraft:unknown");

                // Fill the 4×4 block region
                for (int dx = 0; dx < 4; dx++) {
                    for (int dz = 0; dz < 4; dz++) {
                        biomes[qx * 4 + dx][qz * 4 + dz] = biomeName;
                    }
                }
            }
        }
        return biomes;
    }

    /**
     * Sample biome integer indices for a 16×16 section column.
     *
     * @deprecated Use {@link #sampleBiomeNames} for stable canonical encoding.
     *     This method uses unstable {@code hashCode() % 256} encoding.
     */
    @Deprecated
    public int[][] sampleBiomes(int sectionX, int sectionZ, float[][] heightmap) {
        String[][] names = sampleBiomeNames(sectionX, sectionZ, heightmap);
        int[][] biomes = new int[16][16];
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                biomes[x][z] = Math.abs(names[x][z].hashCode()) % 256;
            }
        }
        return biomes;
    }

    // ------------------------------------------------------------------
    // Internal helpers
    // ------------------------------------------------------------------

    /**
     * Get the {@link NoiseConfig} from the world's chunk manager.
     *
     * <p>In MC 1.21+ (Yarn), {@code ServerChunkManager.getNoiseConfig()} is a
     * public accessor — no reflection needed. The {@code NoiseConfig} is stored
     * on {@code ServerChunkLoadingManager} and created during world loading for
     * every {@code ChunkGenerator} type (with a fallback for non-noise generators).
     *
     * <p>This is the authoritative implementation — used by both
     * {@code WorldNoiseAccess} and {@code NoiseDumperCommand}.
     */
    private static NoiseConfig tryGetNoiseConfig(ServerWorld world) {
        try {
            return world.getChunkManager().getNoiseConfig();
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[WorldNoiseAccess] Failed to get NoiseConfig: {}", e.getMessage());
            return null;
        }
    }

    /** Expose for diagnostics. */
    public boolean isAvailable() {
        return true;  // if constructed, it's available
    }
}
