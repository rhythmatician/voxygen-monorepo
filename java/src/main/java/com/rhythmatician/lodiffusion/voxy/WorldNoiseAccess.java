package com.rhythmatician.lodiffusion.voxy;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.registry.entry.RegistryEntry;
import net.minecraft.util.Identifier;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.structure.StructureStart;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.ChunkPos;
import net.minecraft.world.Heightmap;
import net.minecraft.world.World;
import net.minecraft.world.biome.Biome;
import net.minecraft.world.biome.source.BiomeSource;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ChunkSection;
import net.minecraft.world.chunk.ProtoChunk;
import net.minecraft.world.chunk.UpgradeData;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.gen.StructureAccessor;
import net.minecraft.world.gen.chunk.AquiferSampler;
import net.minecraft.world.gen.chunk.Blender;
import net.minecraft.world.gen.chunk.ChunkGenerator;
import net.minecraft.world.gen.chunk.ChunkGeneratorSettings;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import net.minecraft.world.gen.chunk.GenerationShapeConfig;
import net.minecraft.world.gen.chunk.NoiseChunkGenerator;
import net.minecraft.world.gen.densityfunction.DensityFunction;
import net.minecraft.world.gen.densityfunction.DensityFunctionTypes;
import net.minecraft.world.gen.noise.NoiseConfig;
import net.minecraft.world.gen.noise.NoiseRouter;
import net.minecraft.world.gen.structure.Structure;

import java.util.Arrays;
import java.util.List;
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
    private final DensitySample finalDensity;

    @FunctionalInterface
    interface DensitySample {
        double sample(int blockX, int blockY, int blockZ);
    }

    private WorldNoiseAccess(ServerWorld serverWorld, ChunkGenerator generator,
                             NoiseConfig noiseConfig) {
        this.serverWorld = serverWorld;
        this.generator = generator;
        this.noiseConfig = noiseConfig;
        this.biomeSource = generator.getBiomeSource();
        DensityFunction density = noiseConfig.getNoiseRouter().finalDensity();
        this.finalDensity = (blockX, blockY, blockZ) ->
                density.sample(new DensityFunction.UnblendedNoisePos(blockX, blockY, blockZ));
    }

    WorldNoiseAccess(DensitySample finalDensity) {
        this.serverWorld = null;
        this.generator = null;
        this.noiseConfig = null;
        this.biomeSource = null;
        this.finalDensity = finalDensity;
    }

    /**
     * The world's {@link NoiseConfig}, needed by
     * {@link com.rhythmatician.lodiffusion.world.noise.NoiseRouterSamplerFactory}
     * to build a {@link com.rhythmatician.lodiffusion.world.noise.VanillaNoiseRouterSampler}.
     */
    public NoiseConfig noiseConfig() {
        return noiseConfig;
    }

    /** The server-side world (needed by {@code VanillaHeightmapProvider}). */
    public ServerWorld serverWorld() {
        return serverWorld;
    }

    /** The chunk generator (needed by {@code VanillaHeightmapProvider}). */
    public ChunkGenerator generator() {
        return generator;
    }

    /** The biome source (needed by {@code VanillaBiomeProvider}). */
    public BiomeSource biomeSource() {
        return biomeSource;
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
        if (!(generator instanceof NoiseChunkGenerator)) {
            // Non-noise generator (e.g. flat world) — fall back to per-column sampling
            float[][] surface = sampleHeightmap(sectionX, sectionZ, Heightmap.Type.WORLD_SURFACE_WG);
            float[][] ocean   = sampleHeightmap(sectionX, sectionZ, Heightmap.Type.OCEAN_FLOOR_WG);
            return new float[][][] { surface, ocean };
        }

        NoiseSamplerSetup setup = createNoiseSampler(sectionX, sectionZ);
        ChunkGeneratorSettings settings = setup.settings();
        GenerationShapeConfig shape = setup.shape();

        // Standard overworld values: hCells=4, hCellB=4, vCellB=8, minCellY=-8, cellHeight=48
        int hCells     = 16 / shape.horizontalCellBlockCount();
        int hCellB     = shape.horizontalCellBlockCount();
        int vCellB     = shape.verticalCellBlockCount();
        int minCellY   = MathHelper.floorDiv(shape.minimumY(), vCellB);
        int cellHeight = MathHelper.floorDiv(shape.height(),   vCellB);
        int startX     = sectionX * 16;
        int startZ     = sectionZ * 16;
        ChunkNoiseSampler sampler = setup.sampler();

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

    /** Samples one isolated virgin noise-stage chunk column without world insertion. */
    void sampleExactEndBaseTerrainChunk(
            int chunkX,
            int chunkZ,
            int retainMinY,
            int retainMaxY,
            ExactEndL1Candidate.SolidBlockConsumer consumer,
            ExactL1SamplingTelemetry telemetry) {
        if (retainMinY >= retainMaxY) {
            throw new IllegalArgumentException("retainMinY must not exceed retainMaxY");
        }
        if (!(generator instanceof NoiseChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("exact noise sampling requires NoiseChunkGenerator");
        }
        int startX = chunkX << 4;
        int startZ = chunkZ << 4;
        if (telemetry.claimHeightOracleProbe()) {
            telemetry.recordHeightOracle(
                    chunkX, chunkZ, retainMinY, retainMaxY,
                    generator.getHeight(
                    startX + 8,
                    startZ + 8,
                    Heightmap.Type.WORLD_SURFACE_WG,
                    serverWorld,
                    noiseConfig));
        }
        ProtoChunk proto = new ProtoChunk(
                new ChunkPos(chunkX, chunkZ),
                UpgradeData.NO_UPGRADE_DATA,
                serverWorld,
                serverWorld.getPalettesFactory(),
                null);
        Chunk generated = noiseGenerator.populateNoise(
                Blender.getNoBlending(), noiseConfig,
                new NoStructuresAccessor(serverWorld), proto).join();
        telemetry.recordProtoChunk();
        for (int blockY = retainMinY; blockY < retainMaxY; blockY++) {
            ChunkSection section = generated.getSection(generated.getSectionIndex(blockY));
            for (int localX = 0; localX < 16; localX++) {
                for (int localZ = 0; localZ < 16; localZ++) {
                    BlockState state = section.getBlockState(localX, blockY & 15, localZ);
                    telemetry.recordRetainedCallback();
                    if (state.isAir()) {
                        telemetry.recordRawAir();
                    } else {
                        telemetry.recordRawExplicitNonAir();
                    }
                    consumer.accept(startX + localX, blockY, startZ + localZ, !state.isAir());
                }
            }
        }
    }

    /**
     * Runs the real L1 producer against isolated noise-stage chunks and records
     * whether its sixteen target chunks remained outside the loaded world.
     */
    public ExactEndL1Probe probeExactEndL1(SectionPos origin) {
        if (!Level.L1.isAligned(origin)) {
            throw new IllegalArgumentException("origin " + origin + " is not aligned to L1");
        }
        int firstChunkX = origin.x();
        int firstChunkZ = origin.z();
        boolean unloadedBefore = targetChunksAreUnloaded(firstChunkX, firstChunkZ);
        VoxelVolume volume = new ExactEndL1Candidate(this).produceExactL1(origin);
        boolean unloadedAfter = targetChunksAreUnloaded(firstChunkX, firstChunkZ);
        return new ExactEndL1Probe(volume.countNonAir(), unloadedBefore, unloadedAfter);
    }

    private boolean targetChunksAreUnloaded(int firstChunkX, int firstChunkZ) {
        for (int chunkX = firstChunkX; chunkX < firstChunkX + 4; chunkX++) {
            for (int chunkZ = firstChunkZ; chunkZ < firstChunkZ + 4; chunkZ++) {
                if (serverWorld.isChunkLoaded(chunkX, chunkZ)) return false;
            }
        }
        return true;
    }

    public record ExactEndL1Probe(
            int nonAirVoxels,
            boolean targetChunksUnloadedBefore,
            boolean targetChunksUnloadedAfter) {
        public boolean isSuccessfulUnloadedSample() {
            return nonAirVoxels > 0
                    && targetChunksUnloadedBefore
                    && targetChunksUnloadedAfter;
        }
    }

    private NoiseSamplerSetup createNoiseSampler(int chunkX, int chunkZ) {
        if (!(generator instanceof NoiseChunkGenerator noiseGenerator)) {
            throw new IllegalStateException("exact noise sampling requires NoiseChunkGenerator");
        }
        ChunkGeneratorSettings settings = noiseGenerator.getSettings().value();
        GenerationShapeConfig shape = settings.generationShapeConfig().trimHeight(serverWorld);
        int horizontalCells = 16 / shape.horizontalCellBlockCount();
        int seaLevel = settings.seaLevel();
        AquiferSampler.FluidLevel lava =
                new AquiferSampler.FluidLevel(-54, Blocks.LAVA.getDefaultState());
        AquiferSampler.FluidLevel sea =
                new AquiferSampler.FluidLevel(seaLevel, settings.defaultFluid());
        AquiferSampler.FluidLevelSampler fluids =
                (x, y, z) -> y < Math.min(-54, seaLevel) ? lava : sea;
        ChunkNoiseSampler sampler = new ChunkNoiseSampler(
                horizontalCells,
                noiseConfig,
                chunkX << 4,
                chunkZ << 4,
                shape,
                DensityFunctionTypes.Beardifier.INSTANCE,
                settings,
                fluids,
                Blender.getNoBlending());
        return new NoiseSamplerSetup(sampler, settings, shape);
    }

    private record NoiseSamplerSetup(
            ChunkNoiseSampler sampler,
            ChunkGeneratorSettings settings,
            GenerationShapeConfig shape) {}

    private static final class NoStructuresAccessor extends StructureAccessor {
        private NoStructuresAccessor(ServerWorld world) {
            super(world, new GeneratorOptions(world.getSeed(), false, false), null);
        }

        @Override
        public List<StructureStart> getStructureStarts(
                ChunkPos pos, Predicate<Structure> predicate) {
            return List.of();
        }
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
     * <p>This is the authoritative implementation — used by
     * {@code WorldNoiseAccess} (and by the data-harvester mod's {@code NoiseDumperCommand}).
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

    // ------------------------------------------------------------------
    // Named DensityFunction registry lookup (WS-4.1)
    // ------------------------------------------------------------------

    /**
     * Look up a registered {@link DensityFunction} by its overworld resource path.
     *
     * <p>Density functions such as {@code "overworld/offset"},
     * {@code "overworld/caves/spaghetti_2d"}, etc. are stored in Minecraft's
     * dynamic {@code DensityFunction} registry and can be sampled via
     * {@link #sampleRouterField3D(DensityFunction, int, int)} at any world
     * coordinate without a loaded chunk.
     *
     * <p>Returns {@code null} if the registry or the specific ID is not found
     * (e.g. the world uses a non-overworld or custom generator).
     *
     * @param path  the overworld-relative resource path, e.g.
     *              {@code "overworld/offset"} or
     *              {@code "overworld/caves/spaghetti_2d"}
     * @return the {@link DensityFunction}, or {@code null} on failure
     */
    public DensityFunction lookupDensityFunction(String path) {
        try {
            var dfReg = serverWorld.getRegistryManager()
                    .getOrThrow(RegistryKeys.DENSITY_FUNCTION);
            Identifier id = Identifier.of("minecraft", path);
            RegistryKey<DensityFunction> key =
                    RegistryKey.of(RegistryKeys.DENSITY_FUNCTION, id);
            DensityFunction df = dfReg.get(key);
            if (df == null) {
                HelloTerrainMod.LOGGER.debug(
                        "[WorldNoiseAccess] DensityFunction not found: minecraft:{}", path);
            }
            return df;
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[WorldNoiseAccess] lookupDensityFunction({}) failed: {}",
                    path, e.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Raw NoiseRouter field sampling
    // ------------------------------------------------------------------

    /**
     * Expose the {@link NoiseRouter} for direct {@link DensityFunction} access.
     *
     * <p>The returned router's functions can be sampled at arbitrary (x, y, z)
     * positions using {@link DensityFunction#sample(DensityFunction.NoisePos)}.
     */
    public NoiseRouter getNoiseRouter() {
        return noiseConfig.getNoiseRouter();
    }

    // ------------------------------------------------------------------
    // Seeded source-grounded FINAL_DENSITY point query (End L4 tracer)
    // ------------------------------------------------------------------

    /**
     * Seeded source-grounded point query of {@code NoiseRouter.FINAL_DENSITY}
     * at the given block coordinate via the already-bound {@link NoiseConfig}.
     *
     * <p>This is a pure computation backed by the world-bound
     * {@code NoiseRouter.FINAL_DENSITY} (via {@link NoiseConfig}); it does
     * not create or access chunks and has no side effects. The query is
     * seeded: the same seed/profile/dimension yields the same value at the
     * same coordinate.
     *
     * <p><b>Does NOT claim equality with NoiseChunk's interpolated block density.</b>
     * {@code NoiseChunk} wraps the router with {@code Interpolated} /
     * {@code CacheAllInCell} trilinear interpolation over the dimension-specific
     * cell grid (End 8x4 vs Overworld 4x8); reproducing that exact interpolated
     * block value requires cell interpolation. This point query samples
     * {@code FINAL_DENSITY} directly at the block centre via
     * {@link DensityFunction.UnblendedNoisePos}. Disagreement is measured by
     * the bounded Voxy-mip any-solid oracle (L4 solid iff any of 4096 blocks
     * solid), not by assumed equality.
     *
     * @param blockX block X coordinate
     * @param blockY block Y coordinate
     * @param blockZ block Z coordinate
     * @return FINAL_DENSITY value at the block position; &gt;0 is solid
     */
    public double sampleFinalDensity(int blockX, int blockY, int blockZ) {
        return finalDensity.sample(blockX, blockY, blockZ);
    }

    // -------------------------------------------------------------------------
    // Block-resolution sampling (WS-1.3 parity validation)
    // -------------------------------------------------------------------------

    /**
     * Sample {@code router.finalDensity()} at every block in a chunk column,
     * producing the same 16×384×16 grid written by the GPU compute shader to Binding 7.
     *
     * <p>Indexing matches the shader: {@code [lx + 16*lz] * 384 + (by + 64)}.
     *
     * @param router   the NoiseRouter for the dimension
     * @param sectionX chunk X coordinate
     * @param sectionZ chunk Z coordinate
     * @return flat {@code float[16 * 384 * 16]} array
     */
    public float[] sampleFinalDensityBlockRes(NoiseRouter router,
                                               int sectionX, int sectionZ) {
        float[] out = new float[16 * 384 * 16];
        DensityFunction df = router.finalDensity();
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;
        for (int lx = 0; lx < 16; lx++) {
            int bx = baseX + lx;
            for (int lz = 0; lz < 16; lz++) {
                int bz = baseZ + lz;
                int colBase = (lx + 16 * lz) * 384;
                for (int by = -64; by < 320; by++) {
                    out[colBase + (by + 64)] = (float) df.sample(
                            new DensityFunction.UnblendedNoisePos(bx, by, bz));
                }
            }
        }
        return out;
    }

    /**
     * Sample a {@link DensityFunction} at 16×16 <em>block</em> resolution for a single Y.
     * Higher-resolution than {@link #sampleRouterField2D} (which uses 4×4 cells).
     *
     * @param df       the density function to evaluate
     * @param sectionX chunk X coordinate
     * @param sectionZ chunk Z coordinate
     * @param sampleY  block Y at which to evaluate
     * @return {@code float[16][16]}, lx-outer / lz-inner (x-major)
     */
    public float[][] sampleRouterField2DBlockRes(DensityFunction df,
                                                  int sectionX, int sectionZ,
                                                  int sampleY) {
        float[][] out = new float[16][16];
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;
        for (int lx = 0; lx < 16; lx++) {
            for (int lz = 0; lz < 16; lz++) {
                out[lx][lz] = (float) df.sample(
                        new DensityFunction.UnblendedNoisePos(
                                baseX + lx, sampleY, baseZ + lz));
            }
        }
        return out;
    }

    /**
     * Sample a {@link DensityFunction} at 4×4 cell resolution at a fixed Y.
     *
     * <p>Cell centre coordinates: {@code X = chunkBaseX + cx*4 + 2},
     * {@code Z = chunkBaseZ + cz*4 + 2}.  The Y coordinate is fixed for
     * fields that do not vary vertically (continents, erosion, ridges,
     * temperature, vegetation).
     *
     * @param df       the density function to evaluate
     * @param sectionX chunk X coordinate
     * @param sectionZ chunk Z coordinate
     * @param sampleY  block Y at which to evaluate
     * @return {@code float[4][4]} grid, cx-outer / cz-inner (x-major)
     */
    public float[][] sampleRouterField2D(DensityFunction df,
                                         int sectionX, int sectionZ,
                                         int sampleY) {
        float[][] out = new float[4][4];
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;
        for (int cx = 0; cx < 4; cx++) {
            int x = baseX + cx * 4 + 2;  // cell centre X
            for (int cz = 0; cz < 4; cz++) {
                int z = baseZ + cz * 4 + 2;  // cell centre Z
                out[cx][cz] = (float) df.sample(
                        new DensityFunction.UnblendedNoisePos(x, sampleY, z));
            }
        }
        return out;
    }

    /**
     * Sample a {@link DensityFunction} at 4×48×4 cell resolution — the full
     * overworld noise grid from Y=-64 to Y=320 (48 cells of 8 blocks each).
     *
     * <p>Cell centre coordinates:
     * <ul>
     *   <li>X: {@code chunkBaseX + cx*4 + 2}</li>
     *   <li>Z: {@code chunkBaseZ + cz*4 + 2}</li>
     *   <li>Y: {@code -64 + cy*8 + 4}  (centre of 8-block cell)</li>
     * </ul>
     *
     * @param df       the density function to evaluate
     * @param sectionX chunk X coordinate
     * @param sectionZ chunk Z coordinate
     * @return {@code float[4][48][4]} grid, {@code [cx][cy][cz]} order
     */
    public float[][][] sampleRouterField3D(DensityFunction df,
                                           int sectionX, int sectionZ) {
        float[][][] out = new float[4][48][4];
        int baseX = sectionX * 16;
        int baseZ = sectionZ * 16;
        for (int cx = 0; cx < 4; cx++) {
            int x = baseX + cx * 4 + 2;
            for (int cz = 0; cz < 4; cz++) {
                int z = baseZ + cz * 4 + 2;
                for (int cy = 0; cy < 48; cy++) {
                    int y = -64 + cy * 8 + 4;  // cell centre Y
                    out[cx][cy][cz] = (float) df.sample(
                            new DensityFunction.UnblendedNoisePos(x, y, z));
                }
            }
        }
        return out;
    }

    // ------------------------------------------------------------------
    // SparseOctree noise input (Stage 2 model: noise_3d)
    // ------------------------------------------------------------------

    /**
     * Registry paths and special handling for the 13 SparseOctree noise channels.
     *
     * <p>Layout matches the Python training pipeline:
     * <pre>
     *   0  offset           overworld/offset
     *   1  factor           overworld/factor
     *   2  jaggedness       overworld/jaggedness
     *   3  depth            router.depth()  (null path → special)
     *   4  sloped_cheese    overworld/sloped_cheese
     *   5  y                cell-centre Y (null path → special)
     *   6  entrances        overworld/caves/entrances
     *   7  cheese_caves     overworld/caves/pillars
     *   8  spaghetti_2d     overworld/caves/spaghetti_2d
     *   9  roughness        overworld/caves/spaghetti_roughness_function
     *  10  noodle           overworld/caves/noodle
     *  11  base_3d_noise    overworld/base_3d_noise
     *  12  final_density    router.finalDensity() (null path → special)
     * </pre>
     */
    private static final String[] NOISE_3D_PATHS = {
        "overworld/offset",
        "overworld/factor",
        "overworld/jaggedness",
        null,   // depth → router.depth()
        "overworld/sloped_cheese",
        null,   // y → cell-centre Y value
        "overworld/caves/entrances",
        "overworld/caves/pillars",
        "overworld/caves/spaghetti_2d",
        "overworld/caves/spaghetti_roughness_function",
        "overworld/caves/noodle",
        "overworld/base_3d_noise",
        null,   // final_density → router.finalDensity()
    };

    /** Number of SparseOctree noise channels. */
    public static final int N_NOISE_3D = NOISE_3D_PATHS.length; // 13

    /**
     * Lazily-resolved density functions for {@link #sampleNoise3DForSection}.
     * Index 5 (Y) stays null; indices 3 and 12 use router fields.
     * Protected by {@code this} monitor on first initialization.
     */
    private volatile DensityFunction[] noise3dFunctions = null;

    /**
     * Resolve (once) and cache all {@link DensityFunction} objects needed for
     * the 13-channel SparseOctree noise input.
     */
    private DensityFunction[] getNoise3dFunctions() {
        if (noise3dFunctions != null) return noise3dFunctions;
        synchronized (this) {
            if (noise3dFunctions != null) return noise3dFunctions;
            NoiseRouter router = noiseConfig.getNoiseRouter();
            DensityFunction[] dfs = new DensityFunction[N_NOISE_3D];
            for (int i = 0; i < N_NOISE_3D; i++) {
                String path = NOISE_3D_PATHS[i];
                if (path == null) {
                    if (i == 3)  { dfs[i] = router.depth(); }           // depth
                    else if (i == 12) { dfs[i] = router.finalDensity(); } // final_density
                    // i == 5 (Y): stays null, handled per-cell
                } else {
                    DensityFunction df = lookupDensityFunction(path);
                    if (df == null) {
                        HelloTerrainMod.LOGGER.warn(
                                "[WorldNoiseAccess] noise3d[{}] '{}' not found — using zero",
                                i, path);
                        df = DensityFunctionTypes.zero();
                    }
                    dfs[i] = df;
                }
            }
            noise3dFunctions = dfs;
            return dfs;
        }
    }

    /**
     * Sample the 13-channel SparseOctree noise input for a single L0 Voxy section.
     *
     * <p>Returns a flat {@code float[N_NOISE_3D * 4 * 2 * 4]} array in
     * {@code [field][cx][cy][cz]} (channel-outermost, C-contiguous) order,
     * matching the Python training pipeline's <br>
     * {@code noise_3d shape=(N, 13, 4, 2, 4)}.
     *
     * <p>The two Y-cells that make up a 16-block section at vanilla cell resolution
     * (8 blocks/cell) are sliced from the full 48-cell column:
     * <pre>
     *   cy_start = (sectionY + 4) * 2
     *   cy values sampled: cy_start, cy_start + 1
     * </pre>
     *
     * @param chunkX   chunk X coordinate (= wsX at L0)
     * @param chunkZ   chunk Z coordinate (= wsZ at L0)
     * @param sectionY section Y in native (L0) units, range [-4, 19]
     * @return flat {@code float[13 * 4 * 2 * 4 = 416]}, or an all-zeros array
     *         if the noise pipeline is unavailable
     *
     * @deprecated Legacy 13-channel path.  Use
     *     {@link com.rhythmatician.lodiffusion.world.noise.NoiseRouterSampler#sampleSection}
     *     which produces the standard 15-field × 4×4×4 quart tensor.
     */
    @Deprecated
    public float[] sampleNoise3DForSection(int chunkX, int chunkZ, int sectionY) {
        DensityFunction[] dfs = getNoise3dFunctions();
        float[] flat = new float[N_NOISE_3D * 4 * 2 * 4];

        // cy_start = (sectionY + 4) * 2; clamp to valid cell range [0, 47]
        int cyStart = (sectionY + 4) * 2;
        cyStart = Math.max(0, Math.min(46, cyStart));  // ensure cy_start+1 ≤ 47

        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;

        int flatIdx = 0;
        for (int field = 0; field < N_NOISE_3D; field++) {
            DensityFunction df = dfs[field];
            for (int cx = 0; cx < 4; cx++) {
                int x = baseX + cx * 4 + 2;
                for (int localCy = 0; localCy < 2; localCy++) {
                    int cy = cyStart + localCy;
                    int y = -64 + cy * 8 + 4;  // cell-centre Y in blocks
                    for (int cz = 0; cz < 4; cz++) {
                        int z = baseZ + cz * 4 + 2;
                        float val;
                        if (df == null) {
                            // field 5 = Y: emit cell-centre Y
                            val = y;
                        } else {
                            val = (float) df.sample(
                                    new DensityFunction.UnblendedNoisePos(x, y, z));
                        }
                        flat[flatIdx++] = val;
                    }
                }
            }
        }
        return flat;
    }

    /**
     * Sample biome IDs at 4×2×4 noise cell resolution for a section.
     *
     * <p>Used by SparseOctree training data export. Biomes are sampled at
     * quarter-block resolution and mapped to stable integer IDs via
     * biome registry position.
     *
     * @param chunkX   chunk X coordinate
     * @param chunkZ   chunk Z coordinate
     * @param sectionY section Y in native units, range [-4, 19]
     * @return {@code int[4][2][4]} grid, {@code [cx][localCy][cz]} order
     */
    public int[][][] sampleBiomeIdsForSection(int chunkX, int chunkZ, int sectionY) {
        int[][][] result = new int[4][2][4];
        int cyStart = (sectionY + 4) * 2;
        cyStart = Math.max(0, Math.min(46, cyStart));

        int baseX = chunkX * 16;
        int baseZ = chunkZ * 16;

        try {
            Registry<Biome> biomeReg = serverWorld.getRegistryManager()
                    .getOrThrow(RegistryKeys.BIOME);

            for (int cx = 0; cx < 4; cx++) {
                int x = baseX + cx * 4 + 2;
                for (int localCy = 0; localCy < 2; localCy++) {
                    int cy = cyStart + localCy;
                    int y = -64 + cy * 8 + 4;
                    for (int cz = 0; cz < 4; cz++) {
                        int z = baseZ + cz * 4 + 2;

                        // Sample biome at quart coordinates
                        RegistryEntry<Biome> biomeEntry = biomeSource.getBiome(
                                x >> 2, y >> 2, z >> 2,
                                noiseConfig.getMultiNoiseSampler());

                        // Get the index in the biome registry
                        int biomeId = biomeReg.getRawId(biomeEntry.value());
                        result[cx][localCy][cz] = biomeId >= 0 ? biomeId : 0;
                    }
                }
            }
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn(
                    "[WorldNoiseAccess] Failed to sample biome IDs: {}", e.getMessage());
            // Return all zeros on error
            for (int cx = 0; cx < 4; cx++) {
                for (int cy = 0; cy < 2; cy++) {
                    for (int cz = 0; cz < 4; cz++) {
                        result[cx][cy][cz] = 0;
                    }
                }
            }
        }
        return result;
    }
}
