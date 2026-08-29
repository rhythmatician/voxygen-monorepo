package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Offline oracle capture stub + deterministic in-memory fixture factory.
 *
 * <p>Real vanilla->Voxy capture requires a live MinecraftServer + Voxy WorldEngine (see
 * WorldConversionFactory.convert -> VoxelizedSection -> Mipper -> WorldUpdater). This class
 * provides:
 * <ul>
 *   <li>a deterministic synthetic fixture that exercises the full contract/halo/boundary machinery
 *       without requiring a live server in unit tests, and
 *   <li>a hook ({@code captureReal}) that integration tests can override with real server ingestion.
 * </ul>
 * <p>The synthetic path is explicitly NOT claimed as vanilla parity; tests that assert
 * parity must use a fixture whose provenance records real vanilla+Voxy capture.
 */
public final class VanillaVoxyOracle {
    private VanillaVoxyOracle() {}

    /**
     * Generate a deterministic fixture from the pinned tracer contract.
     * Encodes stable semantic identities (canonical block/biome) not raw Voxy Mapper ids.
     * Suitable for structural tests (contract validation, halo, corruption, verifier wiring).
     * Real fixtures for acceptance must come from {@code captureReal}.
     */
    public static OracleFixture generateSyntheticTracerFixture(OracleContract contract) {
        Objects.requireNonNull(contract, "contract");
        contract.validate();
        // Validate halo is sufficient for chorus (at least 24) per inspected sources
        if (contract.halo().haloBlocks() < 24) {
            throw new IllegalArgumentException("halo too small for chorus: need >=24, was " + contract.halo().haloBlocks());
        }
        if (!"FEATURES".equals(contract.authoritativeGenerationStage())) {
            throw new IllegalArgumentException("chorus tracer requires FEATURES stage, was " + contract.authoritativeGenerationStage());
        }
        Map<Level, VoxelVolume> vols = new EnumMap<>(Level.class);
        SectionPos origin = new SectionPos(contract.region().originSectionX(), contract.region().originSectionY(), contract.region().originSectionZ());
        long seed = contract.seed();
        // L4/L3 honest omission: no chorus (only end_stone / air)
        vols.put(Level.L4, syntheticVolume(Level.L4, origin, seed, false));
        vols.put(Level.L3, syntheticVolume(Level.L3, origin, seed, false));
        // L2/L1/L0 include chorus via deterministic per-column synthesis that matches Mipper semantics
        vols.put(Level.L2, syntheticVolume(Level.L2, origin, seed, true));
        vols.put(Level.L1, syntheticVolume(Level.L1, origin, seed, true));
        vols.put(Level.L0, syntheticVolume(Level.L0, origin, seed, true));
        String sha = sha256Hex(contract.oracleFixtureId() + ":" + contract.seed() + ":" + contract.minecraftJarSha256() + ":" + contract.voxyArtifactSha256());
        return new OracleFixture(contract, vols, sha, System.currentTimeMillis());
    }

    static VoxelVolume syntheticVolume(Level level, SectionPos origin, long seed, boolean includeChorus) {
        int extent = 32;
        VoxelVolume.Builder b = VoxelVolume.builder(extent);
        int voxelBlocks = 1 << level.value();
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        // Simple deterministic island: flat top at 70 + hash variation, chorus where hash %20==0
        for (int y = 0; y < extent; y++) {
            for (int z = 0; z < extent; z++) {
                for (int x = 0; x < extent; x++) {
                    int cx = baseX + x * voxelBlocks + voxelBlocks/2;
                    int cz = baseZ + z * voxelBlocks + voxelBlocks/2;
                    int cy = baseY + y * voxelBlocks + voxelBlocks/2;
                    long h = hash(seed, cx, cz);
                    int surf = baseY + 16 + (int)(Math.abs(h) % 8); // volume-relative 16..23 offset, ensures island inside volume for tracer
                    int blockId;
                    if (cy < 0 || cy >= 128) blockId = CanonicalRegistries.BLOCK_AIR;
                    else if (cy < surf) blockId = 359; // end_stone canonical
                    else if (includeChorus && cy < surf + 6 && cy >= surf) {
                        if ((Math.abs(h) % 20) == 0) {
                            int height = 3 + (int)(Math.abs(h >> 8) % 4);
                            int top = surf + height - 1;
                            if (cy <= top) {
                                if (cy == top) blockId = 196; // chorus_flower
                                else blockId = 197; // chorus_plant
                            } else blockId = CanonicalRegistries.BLOCK_AIR;
                        } else blockId = CanonicalRegistries.BLOCK_AIR;
                    } else blockId = CanonicalRegistries.BLOCK_AIR;
                    if (blockId != CanonicalRegistries.BLOCK_AIR) b.setBlock(x, y, z, blockId);
                    // biome: end_highlands single value -> use unknown (255) per project convention for End
                }
            }
        }
        // Boundary interaction: ensure a chorus contribution crosses the target boundary
        // Place a deterministic chorus plant at the +X edge if includeChorus so halo test has something to assert
        if (includeChorus) {
            // Force a chorus at (extent-1, midY, mid) to exercise edge crossing when halo insufficient
            int mid = extent / 2;
            int edgeX = extent - 1;
            // Find a non-air stone column at edge to anchor chorus
            // We already have chorus at some edge columns via hash; force one at edgeX,midZ if not present
            // Use seed salt to keep deterministic
            long eh = hash(seed ^ 0x9E3779B97F4A7C15L, baseX + edgeX * voxelBlocks, baseZ + mid * voxelBlocks);
            // Ensure this column is chorus-active regardless of hash
            int surf = baseY + 16 + (int)(Math.abs(eh) % 8);
            // Ensure surface > baseY+mid*voxelBlocks so plant is visible in volume
            // If surface would be below volume, bump it
            int cyBase = baseY + mid * voxelBlocks + voxelBlocks/2;
            if (surf <= cyBase) surf = cyBase + 2;
            // Write a 4-high chorus at edge column
            for (int dy = 0; dy < 4; dy++) {
                int vy = mid + dy;
                if (vy < 0 || vy >= extent) continue;
                int blockId = (dy == 3) ? 196 : 197;
                b.setBlock(edgeX, vy, mid, blockId);
            }
        }
        return b.build();
    }

    private static long hash(long seed, int x, int z) {
        long h = seed ^ ((long)x * 0x9E3779B97F4A7C15L) ^ ((long)z * 0xBF58476D1CE4E5B9L);
        h ^= h >>> 33;
        h *= 0xff51afd7ed558ccdL;
        h ^= h >>> 33;
        h *= 0xc4ceb9fe1a85ec53L;
        h ^= h >>> 33;
        return h;
    }

    private static String sha256Hex(String s) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }
}


