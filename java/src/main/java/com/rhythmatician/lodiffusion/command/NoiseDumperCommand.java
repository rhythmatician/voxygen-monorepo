package com.rhythmatician.lodiffusion.command;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.IntegerArgumentType;
import com.mojang.brigadier.context.CommandContext;
import com.rhythmatician.lodiffusion.voxy.WorldNoiseAccess;

import net.minecraft.command.permission.Permission;
import net.minecraft.command.permission.PermissionLevel;
import net.minecraft.server.command.CommandManager;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

/**
 * Server command {@code /dumpnoise <radius>} that extracts vanilla noise
 * signals using {@link WorldNoiseAccess} and serialises them to JSON files
 * under {@code run/noise_dumps/}.
 *
 * <p><b>No loaded chunks required.</b> All data is computed directly from
 * the {@link net.minecraft.world.gen.chunk.ChunkGenerator} and
 * {@link net.minecraft.world.gen.noise.NoiseConfig} — pure math, no world
 * state needed. This means noise can be dumped for <em>any</em> coordinate,
 * even if no player has ever visited the area.
 *
 * <p>Each dump file contains:
 * <ul>
 *   <li>{@code heightmap_surface} — 16×16 WORLD_SURFACE_WG heights (x-major)</li>
 *   <li>{@code heightmap_ocean_floor} — 16×16 OCEAN_FLOOR_WG heights (x-major)</li>
 *   <li>{@code biome_names} — 16×16 biome registry key names at block resolution
 *       (e.g. "minecraft:plains"), x-major</li>
 *   <li>{@code seed}, {@code chunk_x}, {@code chunk_z}</li>
 * </ul>
 *
 * <p>Usage: {@code /dumpnoise [radius]}  (default radius = 8 chunks)
 *
 * <p>The output JSON can be consumed by the Python training pipeline through
 * {@code scripts/add_column_heights.py} when the
 * {@code --noise-dump-dir} option is supplied.
 */
public final class NoiseDumperCommand {

    private static final Logger LOG = LoggerFactory.getLogger(NoiseDumperCommand.class);

    private NoiseDumperCommand() {}

    /**
     * Register {@code /dumpnoise [radius]} with the Brigadier dispatcher.
     * Should be called from {@link com.rhythmatician.lodiffusion.HelloTerrainMod}.
     */
    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(CommandManager.literal("dumpnoise")
            .requires(src -> src.getPermissions().hasPermission(
                    new Permission.Level(PermissionLevel.GAMEMASTERS)))
            // /dumpnoise            (default radius 8)
            .executes(ctx -> execute(ctx, 8))
            // /dumpnoise <radius>
            .then(CommandManager.argument("radius", IntegerArgumentType.integer(1, 512))
                .executes(ctx -> execute(ctx,
                        IntegerArgumentType.getInteger(ctx, "radius"))))
        );
    }

    // ------------------------------------------------------------------
    // Main handler
    // ------------------------------------------------------------------

    private static int execute(CommandContext<ServerCommandSource> ctx, int radius) {
        ServerCommandSource source = ctx.getSource();
        ServerWorld world = source.getWorld();

        // Output directory: <run>/noise_dumps/
        Path outDir = Path.of("noise_dumps");
        try {
            Files.createDirectories(outDir);
        } catch (IOException e) {
            source.sendError(Text.literal("[NoiseDumper] Cannot create output dir: " + e.getMessage()));
            return 0;
        }

        // Create WorldNoiseAccess — chunk-free noise pipeline.
        // If this fails, we cannot proceed (no fallback to chunk-based sampling).
        WorldNoiseAccess noise = WorldNoiseAccess.tryCreate(world);
        if (noise == null) {
            source.sendError(Text.literal(
                    "[NoiseDumper] Failed to initialise noise pipeline. "
                    + "NoiseConfig unavailable — is this a vanilla overworld?"));
            return 0;
        }

        long seed = world.getSeed();

        // Find player origin chunk (or fallback to 0,0)
        BlockPos origin;
        try {
            origin = BlockPos.ofFloored(source.getPosition());
        } catch (UnsupportedOperationException e) {
            origin = BlockPos.ORIGIN;
        }
        int centerCx = origin.getX() >> 4;
        int centerCz = origin.getZ() >> 4;

        int totalChunks = (2 * radius + 1) * (2 * radius + 1);
        source.sendFeedback(
                () -> Text.literal(String.format(
                        "[NoiseDumper] Dumping %d chunks (%d×%d) centred (%d,%d) → %s",
                        totalChunks, 2 * radius + 1, 2 * radius + 1,
                        centerCx, centerCz, outDir.toAbsolutePath())),
                false);

        // Parallel worker pool — limited to avoid starving the server tick loop.
        // ChunkStatus.NOISE generation runs on the server's own worker executor
        // internally, so each task here just blocks waiting for that result.
        // 4 threads is a good balance: enough to keep the pipeline full without
        // hammering the CPU the way 31 threads did.
        int threadCount = 4;
        source.sendFeedback(
                () -> Text.literal(String.format(
                        "[NoiseDumper] Using %d worker threads", threadCount)),
                false);

        ExecutorService pool = Executors.newFixedThreadPool(threadCount, r -> {
            Thread t = new Thread(r, "NoiseDumper-Worker");
            t.setDaemon(true);
            return t;
        });

        AtomicInteger dumped = new AtomicInteger();
        AtomicInteger failed = new AtomicInteger();
        long startTime = System.currentTimeMillis();
        List<Future<?>> futures = new ArrayList<>(totalChunks);

        for (int dx = -radius; dx <= radius; dx++) {
            for (int dz = -radius; dz <= radius; dz++) {
                final int cx = centerCx + dx;
                final int cz = centerCz + dz;
                futures.add(pool.submit(() -> {
                    try {
                        dumpChunkNoise(noise, cx, cz, seed, outDir);
                        dumped.incrementAndGet();
                    } catch (Exception e) {
                        LOG.warn("[NoiseDumper] Failed chunk (" + cx + "," + cz + "): " + e);
                        failed.incrementAndGet();
                    }

                    // Throttled progress: every 100 chunks or on the last one
                    int done = dumped.get() + failed.get();
                    if (done % 100 == 0 || done == totalChunks) {
                        double elapsed = (System.currentTimeMillis() - startTime) / 1000.0;
                        double rate = elapsed > 0 ? done / elapsed : 0;
                        double eta = rate > 0 ? (totalChunks - done) / rate : 0;
                        source.sendFeedback(
                                () -> Text.literal(String.format(
                                        "[NoiseDumper] %d/%d (%.1f/s, ETA %.0fs)",
                                        done, totalChunks, rate, eta)),
                                false);
                    }
                }));
            }
        }

        // Coordinator thread waits for all futures, then shuts down the pool
        Thread coordinator = new Thread(() -> {
            for (Future<?> f : futures) {
                try {
                    f.get();
                } catch (Exception e) {
                    LOG.warn("[NoiseDumper] Future error: " + e);
                }
            }
            pool.shutdown();
            double totalSec = (System.currentTimeMillis() - startTime) / 1000.0;
            final int d = dumped.get();
            final int fl = failed.get();
            source.sendFeedback(
                    () -> Text.literal(String.format(
                            "[NoiseDumper] Done. %d dumped, %d failed in %.1fs (%.1f chunks/s)",
                            d, fl, totalSec, d / totalSec)),
                    false);
        }, "NoiseDumper-Coordinator");
        coordinator.setDaemon(true);
        coordinator.start();

        return 1;
    }

    // ------------------------------------------------------------------
    // Per-chunk dump (chunk-free)
    // ------------------------------------------------------------------

    /**
     * Dump noise signals for a single chunk position to JSON.
     *
     * <p>All data is computed via {@link WorldNoiseAccess} — no loaded chunk
     * or world state is required.
     *
     * @param noise  the noise access (provides heightmaps, biomes)
     * @param cx     chunk X coordinate
     * @param cz     chunk Z coordinate
     * @param seed   world seed
     * @param outDir output directory
     */
    static void dumpChunkNoise(WorldNoiseAccess noise,
                               int cx, int cz, long seed,
                               Path outDir) throws IOException {
        String filename = String.format("chunk_%d_%d.json", cx, cz);
        Path file = outDir.resolve(filename);

        // Sample both heightmaps in one populateNoise() pass via ChunkStatus.NOISE.
        // This is ~64× cheaper than calling getHeight() 512 times (256 cols × 2 types)
        // because ChunkNoiseSampler reuses noise evaluations across the 4×4 cell grid.
        float[][][] heightmaps = noise.sampleBothHeightmaps(cx, cz);
        float[][] surfaceHm   = heightmaps[0];  // WORLD_SURFACE_WG
        float[][] oceanHm     = heightmaps[1];  // OCEAN_FLOOR_WG

        // Sample biomes at surface level (chunk-free via BiomeSource.getBiome())
        String[][] biomeNames = noise.sampleBiomeNames(cx, cz, surfaceHm);

        // Build JSON — heightmaps + biome names for add_column_heights.py
        StringBuilder sb = new StringBuilder(4096);
        sb.append("{\n");
        sb.append("  \"chunk_x\": ").append(cx).append(",\n");
        sb.append("  \"chunk_z\": ").append(cz).append(",\n");
        sb.append("  \"seed\": ").append(seed).append(",\n");

        // Heightmaps — flat 256 values, x-major (x outer, z inner)
        sb.append("  \"heightmap_surface\": [");
        appendFloatGrid(sb, surfaceHm);
        sb.append("],\n");

        sb.append("  \"heightmap_ocean_floor\": [");
        appendFloatGrid(sb, oceanHm);
        sb.append("],\n");

        // Biome names — flat 256 strings, x-major (block resolution)
        sb.append("  \"biome_names\": [");
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x > 0 || z > 0) sb.append(',');
                sb.append('"').append(biomeNames[x][z]).append('"');
            }
        }
        sb.append("]\n");

        sb.append("}\n");
        Files.writeString(file, sb.toString());
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private static void appendFloatGrid(StringBuilder sb, float[][] grid) {
        for (int x = 0; x < 16; x++) {
            for (int z = 0; z < 16; z++) {
                if (x > 0 || z > 0) sb.append(',');
                // Cast to int — heightmaps are whole-block Y values
                sb.append((int) grid[x][z]);
            }
        }
    }
}
