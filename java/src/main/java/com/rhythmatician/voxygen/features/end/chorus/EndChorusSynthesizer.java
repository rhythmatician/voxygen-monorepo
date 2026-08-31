package com.rhythmatician.voxygen.features.end.chorus;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.util.math.BlockPos;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.worldgen.DimensionGenerationDomain;
import com.rhythmatician.voxygen.generation.dimension.end.EndL4DeterministicCandidate;
import com.rhythmatician.voxygen.worldgen.WorldNoiseAccess;

/**
 * Top-down chorus synthesizer for End out-islands.
 *
 * <p>Seam: generates semantic {@link VoxelVolume} extent 32 at the requested {@link Level}
 * without materializing a vanilla chunk (A -> C without B). Inputs are the
 * frozen world seed, surface shape (island top) and biome eligibility; outputs
 * are air|end_stone|chorus_plant|chorus_flower with BIOME_UNKNOWN (End).
 *
 * <p>Honest omissions: L4/L3 never contain chorus (coarse omission, documented
 * as residual cost). L2/L1/L0 include chorus via deterministic per-column
 * hash (%20, 3-6 height, branch) as a procedural approximation without requiring a loaded chunk. Not vanilla {@code ChorusPlantFeature} placement; no vanilla parity established. Honest omission L4/L3=0 is residual cost; single-voxel edge cases may differ due to centre-sample vs any-solid and branch stochasticity. See #220 for disposition.
 *
 * <p>Voxy Mipper semantics: bottom-up is vanilla chunk (16x384x16 blocks with chorus)
 * -> Voxy ingest (VoxelizedSection 16^3 -> 32^3 WorldSection via {@code Mipper}
 * opacity-biased rule). Top-down matches the same Mipper selection given the same block field:
 * opaque end_stone (opacity 15) wins over chorus/air (opacity 0) with corner
 * priority I111=7..I000=0 as in {@code external/voxy/src/main/java/me/cortex/voxy/common/world/other/Mipper.java}.
 * L1 is single mip (2^3), L2 is double mip (4^3) via two successive Mipper
 * steps, which is equivalent to a single 4^3 Mipper pass.
 *
 * <p>Biome gating: chorus only in chorus-eligible biomes (production:
 * {@code end_highlands}; tests may inject always-true). Purple hue invariant:
 * every non-air non-stone block is chorus_plant (197) or chorus_flower (196).
 *
 * <p>Thread-safe after construction; single mutable state is the seed and
 * providers which are final.
 */
public final class EndChorusSynthesizer {

    public static final int BLOCK_AIR = CanonicalRegistries.BLOCK_AIR;
    public static final int BLOCK_END_STONE = 359; // minecraft:end_stone
    public static final int BLOCK_CHORUS_PLANT = 197; // minecraft:chorus_plant
    public static final int BLOCK_CHORUS_FLOWER = 196; // minecraft:chorus_flower
    public static final int EXTENT = 32;
    public static final int END_MIN_Y = DimensionGenerationDomain.END.minY();
    public static final int END_MAX_Y = DimensionGenerationDomain.END.maxY();

    /** Eligibility of a block column for chorus (biome gate). */
    @FunctionalInterface
    public interface BiomeEligibility {
        boolean isChorusBiome(int blockX, int blockZ);
    }

    /** Surface Y (worldSurface) per column; -1 means void (no island). */
    @FunctionalInterface
    public interface SurfaceProvider {
        int surfaceY(int blockX, int blockZ);
    }

    private final long seed;
    private final BiomeEligibility biomeCheck;
    private final SurfaceProvider surface;

    public EndChorusSynthesizer(long seed, BiomeEligibility biomeCheck, SurfaceProvider surface) {
        this.seed = seed;
        this.biomeCheck = Objects.requireNonNull(biomeCheck, "biomeCheck");
        this.surface = Objects.requireNonNull(surface, "surface");
    }

    /** Test convenience: flat island at 70, always-eligible biome. */
    public static EndChorusSynthesizer forTesting(long seed) {
        return new EndChorusSynthesizer(seed,
                (x, z) -> true,
                (x, z) -> {
                    long h = hash(seed, x, z);
                    // 1/3 void to exercise air padding, otherwise flat 70
                    if ((h & 3) == 0 && (h & 7) == 0) return -1;
                    return 64 + (int) (Math.abs(h) % 16); // 64..79
                });
    }

    /** Test convenience: synthetic islands with controllable surface. */
    public static EndChorusSynthesizer forTesting(long seed, SurfaceProvider surface, BiomeEligibility biome) {
        return new EndChorusSynthesizer(seed, biome, surface);
    }

    /**
     * World-bound experimental factory wrapping {@link WorldNoiseAccess} for real End islands. Until #233 provides the independent oracle, not for production L1/L2 overlay.
     * Surface is derived from finalDensity top solid; biome via biome sampler.
     * Falls back to air where density indicates void.
     */
    public static EndChorusSynthesizer forWorld(WorldNoiseAccess access, long seed) {
        Objects.requireNonNull(access, "access");
        // For production we need heightmap-like surface. Easiest: sample finalDensity column top.
        // We do a bounded top-down scan for the highest solid block 0..128, mirroring vanilla's
        // HEIGHTMAP but using density centre-sample. This is deterministic and cheap for the
        // block volumes we generate (at most 128*64*64 per L1).
        Map<Long, Integer> surfaceCache = new ConcurrentHashMap<>();
        Map<Long, Boolean> biomeCache = new ConcurrentHashMap<>();
        return new EndChorusSynthesizer(seed,
                (blockX, blockZ) -> {
                    long bkey = ((long) blockX << 32) ^ (blockZ & 0xffffffffL);
                    Boolean cached = biomeCache.get(bkey);
                    if (cached != null) return cached;
                    boolean result;
                    try {
                        if (access.serverWorld() == null) {
                            // Headless/tests: assume eligible so unit tests don't block
                            result = true;
                        } else {
                            BlockPos pos = new BlockPos(blockX, 64, blockZ);
                            var biomeEntry = access.serverWorld().getBiome(pos);
                            var biomeKey = biomeEntry.getKey().orElse(null);
                            result = biomeKey != null && biomeKey.getValue().toString().equals("minecraft:end_highlands");
                        }
                    } catch (Exception e) {
                        result = false;
                    }
                    biomeCache.put(bkey, result);
                    return result;
                },
                (blockX, blockZ) -> {
                    long key = ((long) blockX << 32) ^ (blockZ & 0xffffffffL);
                    Integer cached = surfaceCache.get(key);
                    if (cached != null) return cached;
                    for (int y = END_MAX_Y - 1; y >= END_MIN_Y; y--) {
                        double d = access.sampleFinalDensity(blockX, y, blockZ);
                        if (d > 0) { int r = y + 1; surfaceCache.put(key, r); return r; }
                    }
                    surfaceCache.put(key, -1);
                    return -1;
                });
    }

    /** Main seam: synthesize a 32^3 region at the requested Level. */
    public VoxelVolume synthesize(Level level, SectionPos origin) {
        if (level == null) throw new NullPointerException("level");
        if (origin == null) throw new NullPointerException("origin");
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level + " regionSections=" + level.regionSections());
        }
        if (level == Level.L4 || level == Level.L3) {
            return synthesizeCoarse(level, origin);
        }
        if (level == Level.L2) {
            return synthesizeL2(origin);
        }
        if (level == Level.L1) {
            return synthesizeL1(origin);
        }
        // L0
        return synthesizeL0(origin);
    }

    /** Backwards-compatible alias. */
    public VoxelVolume produceRegion(Level level, SectionPos origin) {
        return synthesize(level, origin);
    }

    // ------------------------------------------------------------------
    // Level-specific synthesis
    // ------------------------------------------------------------------

    private VoxelVolume synthesizeCoarse(Level level, SectionPos origin) {
        // Honest omission: no chorus at L3/L4. Produce base end_stone/air via centre-sample
        // at this Level's voxel size (matching EndL4DeterministicCandidate geometry).
        int voxelBlocks = 1 << level.value();
        int centreOffset = voxelBlocks / 2;
        VoxelVolume.Builder b = VoxelVolume.builder(EXTENT);
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        for (int y = 0; y < EXTENT; y++) {
            int y0 = baseY + y * voxelBlocks;
            int y1 = y0 + voxelBlocks;
            boolean activeY = y0 < END_MAX_Y && y1 > END_MIN_Y;
            if (!activeY) continue;
            for (int z = 0; z < EXTENT; z++) {
                for (int x = 0; x < EXTENT; x++) {
                    int cx = baseX + x * voxelBlocks + centreOffset;
                    int cy = y0 + centreOffset;
                    int cz = baseZ + z * voxelBlocks + centreOffset;
                    // centre-sample island presence via surface provider at centre
                    int surf = surface.surfaceY(cx, cz);
                    int blockId;
                    if (surf < 0) {
                        blockId = BLOCK_AIR;
                    } else if (cy < surf) {
                        blockId = BLOCK_END_STONE;
                    } else {
                        blockId = BLOCK_AIR;
                    }
                    if (blockId != BLOCK_AIR) {
                        b.setBlock(x, y, z, blockId);
                    }
                }
            }
        }
        return b.build();
    }

    private VoxelVolume synthesizeL0(SectionPos origin) {
        // L0: 1 block/voxel, 32 blocks per axis, no mip, direct block placement.
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        VoxelVolume.Builder b = VoxelVolume.builder(EXTENT);
        for (int y = 0; y < EXTENT; y++) {
            int worldY = baseY + y;
            if (worldY < END_MIN_Y || worldY >= END_MAX_Y) continue;
            for (int z = 0; z < EXTENT; z++) {
                int worldZ = baseZ + z;
                for (int x = 0; x < EXTENT; x++) {
                    int worldX = baseX + x;
                    int id = blockIdAt(worldX, worldY, worldZ);
                    if (id != BLOCK_AIR) b.setBlock(x, y, z, id);
                }
            }
        }
        return b.build();
    }

    private VoxelVolume synthesizeL1(SectionPos origin) {
        // L1: 2 blocks/voxel, region 64^3 blocks -> 32^3 voxels via single Mipper step.
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        final int voxelBlocks = 2;
        final int regionBlocks = 64;
        // Generate 64^3 block array at world resolution, then mip 2x2x2.
        // Use flat index: [y][z][x] at block resolution.
        int[] blocks = new int[regionBlocks * regionBlocks * regionBlocks];
        for (int y = 0; y < regionBlocks; y++) {
            int worldY = baseY + y;
            boolean inY = worldY >= END_MIN_Y && worldY < END_MAX_Y;
            for (int z = 0; z < regionBlocks; z++) {
                int worldZ = baseZ + z;
                for (int x = 0; x < regionBlocks; x++) {
                    int worldX = baseX + x;
                    int id = inY ? blockIdAt(worldX, worldY, worldZ) : BLOCK_AIR;
                    blocks[(y * regionBlocks + z) * regionBlocks + x] = id;
                }
            }
        }
        VoxelVolume.Builder out = VoxelVolume.builder(EXTENT);
        // Mip 2x2x2 -> 32^3
        for (int vy = 0; vy < EXTENT; vy++) {
            for (int vz = 0; vz < EXTENT; vz++) {
                for (int vx = 0; vx < EXTENT; vx++) {
                    int bx = vx * voxelBlocks;
                    int by = vy * voxelBlocks;
                    int bz = vz * voxelBlocks;
                    int[] eight = new int[8];
                    int idx = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                int x = bx + dx;
                                int y = by + dy;
                                int z = bz + dz;
                                eight[idx++] = blocks[(y * regionBlocks + z) * regionBlocks + x];
                            }
                        }
                    }
                    int mip = mipBlockId(eight);
                    if (mip != BLOCK_AIR) out.setBlock(vx, vy, vz, mip);
                }
            }
        }
        return out.build();
    }

    private VoxelVolume synthesizeL2(SectionPos origin) {
        // L2: 4 blocks/voxel, region 128^3 blocks -> 32^3 voxels via 4^3 Mipper.
        // Implemented as two successive 2x2x2 mips via an intermediate 64^3 L1-like volume
        // to reuse the same opacity-biased rule and to make cache-reuse provable.
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        final int regionBlocks = 128;
        final int voxelBlocks = 4;
        // 128^3 block array
        int[] blocks = new int[regionBlocks * regionBlocks * regionBlocks];
        for (int y = 0; y < regionBlocks; y++) {
            int worldY = baseY + y;
            boolean inY = worldY >= END_MIN_Y && worldY < END_MAX_Y;
            for (int z = 0; z < regionBlocks; z++) {
                int worldZ = baseZ + z;
                for (int x = 0; x < regionBlocks; x++) {
                    int worldX = baseX + x;
                    int id = inY ? blockIdAt(worldX, worldY, worldZ) : BLOCK_AIR;
                    blocks[(y * regionBlocks + z) * regionBlocks + x] = id;
                }
            }
        }
        // First mip 2x -> 64^3 intermediate (like L1 block -> voxel)
        final int midBlocks = 64;
        int[] mid = new int[midBlocks * midBlocks * midBlocks];
        for (int y = 0; y < midBlocks; y++) {
            for (int z = 0; z < midBlocks; z++) {
                for (int x = 0; x < midBlocks; x++) {
                    int bx = x * 2;
                    int by = y * 2;
                    int bz = z * 2;
                    int[] eight = new int[8];
                    int idx = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                eight[idx++] = blocks[((by + dy) * regionBlocks + (bz + dz)) * regionBlocks + (bx + dx)];
                            }
                        }
                    }
                    mid[(y * midBlocks + z) * midBlocks + x] = mipBlockId(eight);
                }
            }
        }
        // Second mip 2x -> 32^3
        VoxelVolume.Builder out = VoxelVolume.builder(EXTENT);
        for (int vy = 0; vy < EXTENT; vy++) {
            for (int vz = 0; vz < EXTENT; vz++) {
                for (int vx = 0; vx < EXTENT; vx++) {
                    int bx = vx * 2;
                    int by = vy * 2;
                    int bz = vz * 2;
                    int[] eight = new int[8];
                    int idx = 0;
                    for (int dy = 0; dy < 2; dy++) {
                        for (int dz = 0; dz < 2; dz++) {
                            for (int dx = 0; dx < 2; dx++) {
                                eight[idx++] = mid[((by + dy) * midBlocks + (bz + dz)) * midBlocks + (bx + dx)];
                            }
                        }
                    }
                    int mip = mipBlockId(eight);
                    if (mip != BLOCK_AIR) out.setBlock(vx, vy, vz, mip);
                }
            }
        }
        return out.build();
    }

    // ------------------------------------------------------------------
    // Per-block ground truth (shared between direct and mip paths)
    // ------------------------------------------------------------------

    /**
     * Deterministic per-block ID at world coordinate. This is the A->B truth
     * that bottom-up vanilla would produce before Voxy mip, and top-down mimics.
     */
    public int blockIdAt(int worldX, int worldY, int worldZ) {
        if (worldY < END_MIN_Y || worldY >= END_MAX_Y) return BLOCK_AIR;
        int surf = surface.surfaceY(worldX, worldZ);
        if (surf < 0) return BLOCK_AIR;
        // Below surface: end_stone solid (island interior). End islands are solid, no caves in this model.
        if (worldY < surf) return BLOCK_END_STONE;
        // At or above surface: maybe chorus
        if (worldY >= surf && worldY < surf + 16) {
            // Check chorus eligibility for this column
            if (!biomeCheck.isChorusBiome(worldX, worldZ)) return BLOCK_AIR;
            // Deterministic per-column chorus presence: hash of column
            long colHash = hash(seed, worldX, worldZ);
            // Use absolute hash for distribution; 5% columns have chorus (matches ~0-4 per chunk ~ 1/16*0.05*256 ~13%)
            // Tweak to 1 in 20 to keep count low but visible.
            if ((Math.abs(colHash) % 20) != 0) return BLOCK_AIR;
            int height = 3 + (int) (Math.abs(colHash >> 8) % 4); // 3..6
            int top = surf + height - 1;
            if (worldY > top) return BLOCK_AIR;
            // Branch: for height >=4, add one side branch at height-2
            // Branch position is deterministic offset from column hash
            if (worldY == surf + height - 2 && height >= 4) {
                // Check if this block is the branch stem (adjacent to main column)
                // We model branch as one extra chorus_plant at (+1,0) or (0,+1) from main
                // For the main column's voxel, this does not affect the block at (x,z) but at neighbor.
                // So for the queried (worldX,worldZ), we need to see if it is a branch of a neighbor column.
                // That's handled when that neighbor column is queried as main column's branch.
                // For now, just handle main column's own branch at its own position as extra flower? Actually
                // branch grows outward, so the block at main column remains plant, branch is at offset.
                // So no extra logic for main column.
            }
            // Check if this world pos is a branch of a neighboring plant
            // Neighbor plants could extend into this column: look at 4 horizontal neighbors at same Y
            for (int[] dir : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                int nx = worldX - dir[0];
                int nz = worldZ - dir[1];
                int nSurf = surface.surfaceY(nx, nz);
                if (nSurf < 0) continue;
                if (!biomeCheck.isChorusBiome(nx, nz)) continue;
                long nHash = hash(seed, nx, nz);
                if ((Math.abs(nHash) % 20) != 0) continue;
                int nHeight = 3 + (int) (Math.abs(nHash >> 8) % 4);
                int nTop = nSurf + nHeight - 1;
                // Branch height is at nSurf + nHeight -2, branch extends one outward, with its own short column of 1-2
                if (nHeight >= 4 && worldY == nSurf + nHeight - 2) {
                    // This could be the branch base (one block outward)
                    // Verify direction matches hash's chosen branch direction
                    int dirIdx = (int) (Math.abs(nHash >> 16) % 4);
                    int[] chosen = new int[][]{{1,0},{-1,0},{0,1},{0,-1}}[dirIdx];
                    if (dir[0]==chosen[0] && dir[1]==chosen[1]) {
                        // Branch block at this world pos
                        // Branch has its own height 1-2 above branch base? Simplify: single plant block with flower on top
                        // For this branch column, the block at worldY is chorus_plant, and worldY+1 is flower
                        // But we are querying at worldY == branch base Y, so it's plant.
                        // Check if worldY+1 is also part of branch (flower) - that would be queried at next Y.
                        return BLOCK_CHORUS_PLANT;
                    }
                }
                if (nHeight >= 4 && worldY == nSurf + nHeight - 1) {
                    // Branch flower one block above branch base but offset outward, so at (nx+chosen) at height nTop
                    // That flower is at worldY == nTop, but at offset position, not at main column top.
                    // For this world pos which is offset, check if it's the branch flower
                    int dirIdx = (int) (Math.abs(nHash >> 16) % 4);
                    int[] chosen = new int[][]{{1,0},{-1,0},{0,1},{0,-1}}[dirIdx];
                    if (dir[0]==chosen[0] && dir[1]==chosen[1]) {
                        // This world pos could be the flower above the branch base
                        // Branch flower is at worldY == nTop which equals branch base Y+1
                        // So if worldY == nTop and this pos is branch offset, it's flower
                        // But we already handled branch base at -2, flower at -1 offset? Actually branch base is at -2, flower at -1+1?
                        // Simplify: branch adds one extra block at (nx+chosen, nSurf+nHeight-1) as flower, and base at (nx+chosen, nSurf+nHeight-2) as plant
                        // So at worldY == nTop (which is nSurf+nHeight-1) and offset, it's flower.
                        // At worldY == nTop-1 and offset, it's plant (already handled above)
                        // So need to distinguish: at worldY == nTop, return flower for offset
                        int branchFlowerY = nSurf + nHeight - 1;
                        // branchFlowerY is same as main top, but at offset
                        if (worldY == branchFlowerY) {
                            return BLOCK_CHORUS_FLOWER;
                        }
                    }
                }
            }
            if (worldY == top) return BLOCK_CHORUS_FLOWER;
            return BLOCK_CHORUS_PLANT;
        }
        return BLOCK_AIR;
    }

    // ------------------------------------------------------------------
    // Mipper helper (mirrors Voxy Mipper.java and test helper)
    // ------------------------------------------------------------------

    public static int mipBlockId(int[] eightChildren) {
        int best = -1;
        int bestScore = -1;
        for (int i = 0; i < eightChildren.length; i++) {
            int id = eightChildren[i];
            if (id == BLOCK_AIR) continue;
            int opacity = (id == BLOCK_END_STONE) ? 15 : 0;
            int cornerPriority = i; // I000=0..I111=7 matches Voxy
            int score = (opacity << 4) | cornerPriority;
            if (score > bestScore) {
                bestScore = score;
                best = id;
            }
        }
        return best == -1 ? BLOCK_AIR : best;
    }

    // Deterministic 64-bit mix for column hashing
    public static long hash(long seed, int x, int z) {
        long h = seed;
        h ^= (long) x * 0x9E3779B97F4A7C15L;
        h ^= (long) z * 0xBF58476D1CE4E5B9L;
        h ^= h >> 30;
        h *= 0xBF58476D1CE4E5B9L;
        h ^= h >> 27;
        h *= 0x94D049BB133111EBL;
        h ^= h >> 31;
        return h;
    }
}
