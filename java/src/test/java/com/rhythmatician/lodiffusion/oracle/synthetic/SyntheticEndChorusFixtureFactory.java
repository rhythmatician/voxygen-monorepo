package com.rhythmatician.lodiffusion.oracle.synthetic;

import com.rhythmatician.lodiffusion.oracle.OracleContract;
import com.rhythmatician.lodiffusion.oracle.OracleFixture;
import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
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
 * SYNTHETIC ONLY — harness-development fixture factory.
 *
 * <p>Deterministic in-memory fixture that exercises contract/halo/boundary/verifier wiring
 * without a live MinecraftServer. It is NOT oracle evidence and must never be mistaken for
 * real vanilla→Voxy capture. Real oracle fixtures come from VanillaVoxyOracle in voxyIntegrationTest
 * in {@code src/voxyIntegrationTest} via live server + real Voxy ingestion.
 *
 * <p>Preserved for structural tests; not for parity claims.
 */
public final class SyntheticEndChorusFixtureFactory {
    private SyntheticEndChorusFixtureFactory() {}

    public static OracleFixture generateSyntheticTracerFixture(OracleContract contract) {
        Objects.requireNonNull(contract, "contract");
        contract.validate();
        if (contract.halo().combinedHaloBlocks() < 25) {
            throw new IllegalArgumentException("halo too small for chorus: need >=25 (8+16+1), was " + contract.halo().combinedHaloBlocks());
        }
        if (!"FEATURES".equals(contract.authoritativeGenerationStage())) {
            throw new IllegalArgumentException("chorus tracer requires FEATURES stage, was " + contract.authoritativeGenerationStage());
        }
        Map<Level, VoxelVolume> vols = new EnumMap<>(Level.class);
        SectionPos origin = new SectionPos(contract.region().originSectionX(), contract.region().originSectionY(), contract.region().originSectionZ());
        long seed = contract.seed();
        vols.put(Level.L4, syntheticVolume(Level.L4, origin, seed, false));
        vols.put(Level.L3, syntheticVolume(Level.L3, origin, seed, false));
        vols.put(Level.L2, syntheticVolume(Level.L2, origin, seed, true));
        vols.put(Level.L1, syntheticVolume(Level.L1, origin, seed, true));
        vols.put(Level.L0, syntheticVolume(Level.L0, origin, seed, true));
        String sha = OracleFixture.computeContentSha256(vols);
        return new OracleFixture(contract, vols, sha, System.currentTimeMillis());
    }

    static VoxelVolume syntheticVolume(Level level, SectionPos origin, long seed, boolean includeChorus) {
        int extent = 32;
        VoxelVolume.Builder b = VoxelVolume.builder(extent);
        int voxelBlocks = 1 << level.value();
        int baseX = origin.x() << 4;
        int baseY = origin.y() << 4;
        int baseZ = origin.z() << 4;
        for (int y = 0; y < extent; y++) {
            for (int z = 0; z < extent; z++) {
                for (int x = 0; x < extent; x++) {
                    int cx = baseX + x * voxelBlocks + voxelBlocks/2;
                    int cz = baseZ + z * voxelBlocks + voxelBlocks/2;
                    int cy = baseY + y * voxelBlocks + voxelBlocks/2;
                    long h = hash(seed, cx, cz);
                    int surf = baseY + 16 + (int)(Math.abs(h) % 8);
                    int blockId;
                    if (cy < 0 || cy >= 128) blockId = CanonicalRegistries.BLOCK_AIR;
                    else if (cy < surf) blockId = 359;
                    else if (includeChorus && cy < surf + 6 && cy >= surf) {
                        if ((Math.abs(h) % 20) == 0) {
                            int height = 3 + (int)(Math.abs(h >> 8) % 4);
                            int top = surf + height - 1;
                            if (cy <= top) {
                                if (cy == top) blockId = 196;
                                else blockId = 197;
                            } else blockId = CanonicalRegistries.BLOCK_AIR;
                        } else blockId = CanonicalRegistries.BLOCK_AIR;
                    } else blockId = CanonicalRegistries.BLOCK_AIR;
                    if (blockId != CanonicalRegistries.BLOCK_AIR) b.setBlock(x, y, z, blockId);
                }
            }
        }
        if (includeChorus) {
            int mid = extent / 2;
            int edgeX = extent - 1;
            long eh = hash(seed ^ 0x9E3779B97F4A7C15L, baseX + edgeX * voxelBlocks, baseZ + mid * voxelBlocks);
            int surf = baseY + 16 + (int)(Math.abs(eh) % 8);
            int cyBase = baseY + mid * voxelBlocks + voxelBlocks/2;
            if (surf <= cyBase) surf = cyBase + 2;
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



