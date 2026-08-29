package com.rhythmatician.lodiffusion.oracle.capture;

import com.rhythmatician.lodiffusion.oracle.OracleContract;
import com.rhythmatician.lodiffusion.oracle.OracleFixture;
import com.rhythmatician.lodiffusion.voxy.BiomeMapping;
import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import net.minecraft.client.MinecraftClient;
import net.minecraft.registry.Registries;
import net.minecraft.block.Block;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Captures post-ingest Voxy WorldSections at L0..L4 for the tracer region after
 * DataHarvester ingest has completed through the real pipeline:
 * WorldConversionFactory.convert -> mipSection/Mipper -> Mapper -> WorldUpdater.insertUpdate -> WorldEngine.
 *
 * <p>Reads the consolidated 32^3 WorldSection (not the pre-insertion VoxelizedSection at HEAD of
 * insertUpdate). Decodes per-world packed voxels via the live world's Mapper back to stable
 * canonical block/biome IDs and exposes only semantic VoxelVolume data to OracleFixture.
 *
 * <p>Voxy packed format (Mapper): light@56 8b, biome@47 9b, blockId@27 20b, AIR=0.
 * Decoding uses Mapper.getBlockStateFromBlockId + Registries.BLOCK.getId and
 * Mapper.getBiomeEntries for biome reverse mapping.
 */
public final class WorldSectionOracleCapture {
    private static final Logger LOGGER = LoggerFactory.getLogger(WorldSectionOracleCapture.class);
    private static final int WS_SIZE = 32;

    // ThreadLocal for actualCaptureStage supplied by OracleFileTrigger (FULL vs FEATURES)
    static final class ActualStageHolder {
        private static final ThreadLocal<String> TL = new ThreadLocal<>();
        static void set(String s) { TL.set(s); }
        static String get() { return TL.get(); }
        static void clear() { TL.remove(); }
    }
    public static void setActualCaptureStage(String s) { ActualStageHolder.set(s); }
    public static void clearActualCaptureStage() { ActualStageHolder.clear(); }

    private WorldSectionOracleCapture() {}

    /**
     * Capture fixture for the given contract from the current client world's Voxy WorldEngine.
     * Must be called on the client thread after ingest has flushed and WorldEngine is live.
     *
     * @return OracleFixture with L0..L4 volumes, true contentSha256, and provenance from contract
     * @throws IllegalStateException if WorldEngine/Mapper unavailable or target WorldSections missing
     */
    public static OracleFixture capture(OracleContract contract) {
        Objects.requireNonNull(contract, "contract");
        contract.validate();
        if (!"minecraft:the_end".equals(contract.dimension())) {
            throw new IllegalArgumentException("tracer dimension must be minecraft:the_end, was " + contract.dimension());
        }
        // Allow both FEATURES (authoritative chorus) and FULL (offline oracle may generate FULL for simplicity).
        // If FULL was used, caller must record that FULL was the actual capture stage.
        String stage = contract.authoritativeGenerationStage();
        boolean isFull = "FULL".equals(stage);
        boolean isFeatures = "FEATURES".equals(stage);
        if (!isFull && !isFeatures) {
            throw new IllegalArgumentException("oracle capture expects FEATURES or FULL, was " + stage);
        }

        var mc = MinecraftClient.getInstance();
        if (mc == null || mc.world == null) {
            throw new IllegalStateException("No client level available — must capture while connected to oracle world");
        }
        var level = mc.world;

        // Obtain WorldEngine via reflection facade VoxyEngine (client-side)
        Object worldEngine = getWorldEngineReflectively(level);
        if (worldEngine == null) {
            throw new IllegalStateException("Voxy WorldEngine not available — is Voxy installed and world loaded? level=" + level.getRegistryKey().getValue().toString());
        }
        Object mapper = getMapperReflectively(worldEngine);
        if (mapper == null) {
            throw new IllegalStateException("Voxy Mapper not available from WorldEngine");
        }

        // Build reverse maps: canonical name -> canonicalId, and per-world id -> canonical
        Map<String, Integer> canonicalNameToId = buildCanonicalNameToId();
        Map<Integer, String> voxyBiomeIdToName = buildVoxyBiomeIdToName(mapper);

        Map<Level, VoxelVolume> volumes = new EnumMap<>(Level.class);
        SectionPos origin = new SectionPos(contract.region().originSectionX(), contract.region().originSectionY(), contract.region().originSectionZ());

        for (Level lvl : Level.values()) {
            VoxelVolume vol = captureOneLevel(level, worldEngine, mapper, origin, lvl, canonicalNameToId, voxyBiomeIdToName);
            volumes.put(lvl, vol);
            LOGGER.info("[OracleCapture] L{} WorldSection {} captured: {}/{} non-air", lvl.value(), lvl, vol.countNonAir(), WS_SIZE * WS_SIZE * WS_SIZE);
        }

        String sha = OracleFixture.computeContentSha256(volumes);
        // actualCaptureStage is supplied by caller (OracleFileTrigger) via ThreadLocal or via contract's authoritative; for direct calls use FULL if requested
        String actualStage = WorldSectionOracleCapture.ActualStageHolder.get();
        if (actualStage == null) actualStage = contract.authoritativeGenerationStage();
        OracleFixture fixture = new OracleFixture(contract, volumes, sha, System.currentTimeMillis(), actualStage);
        LOGGER.info("[OracleCapture] Fixture provenance={} sha={} halo={} stage={} region={} volumes={}", fixture.provenanceId(), sha, contract.halo().combinedHaloBlocks(), stage, origin, volumes.size());
        return fixture;
    }

    private static VoxelVolume captureOneLevel(
            net.minecraft.client.world.ClientWorld level,
            Object worldEngine,
            Object mapper,
            SectionPos origin,
            Level lvl,
            Map<String, Integer> canonicalNameToId,
            Map<Integer, String> voxyBiomeIdToName) {

        int L = lvl.value();
        // WorldSection at Level L covers 32*(1<<L) blocks. Compute ws coords that contain origin.
        int worldBlockOriginX = origin.x() * 16;
        int worldBlockOriginY = origin.y() * 16;
        int worldBlockOriginZ = origin.z() * 16;
        int wsBlockSize = WS_SIZE * (1 << L);
        int wsX = Math.floorDiv(worldBlockOriginX, wsBlockSize);
        int wsY = Math.floorDiv(worldBlockOriginY, wsBlockSize);
        int wsZ = Math.floorDiv(worldBlockOriginZ, wsBlockSize);

        Object ws = acquireWorldSectionIfExists(worldEngine, L, wsX, wsY, wsZ);
        if (ws == null) {
            String msg = String.format("[OracleCapture] FATAL: L%s WorldSection %s@[%d,%d,%d] missing — ingest bounds too small or WorldEngine not yet flushed. Origin %s L=%d blockOrigin (%d,%d,%d) wsBlockSize %d. L4 footprint must be ingested (94..129 x -2..33) via DataHarvester L4 36x36=1296 chunks and insertUpdate RETURN barrier must complete.", L, lvl, wsX, wsY, wsZ, origin, L, worldBlockOriginX, worldBlockOriginY, worldBlockOriginZ, wsBlockSize);
            LOGGER.error(msg);
            throw new IllegalStateException(msg);
        }

        try {
            long[] raw = copyWorldSectionData(ws);
            int nonEmptyChildren = getNonEmptyChildren(ws);
            LOGGER.info("[OracleCapture] L{} ws [{},{},{}] nonEmptyChildren=0x{} rawLen={}", L, wsX, wsY, wsZ, Integer.toHexString(nonEmptyChildren & 0xFF), raw.length);
            return decodeWorldSectionToVoxelVolume(raw, mapper, canonicalNameToId, voxyBiomeIdToName, lvl);
        } finally {
            releaseWorldSection(ws);
        }
    }

    private static VoxelVolume decodeWorldSectionToVoxelVolume(
            long[] raw, Object mapper, Map<String, Integer> canonicalNameToId,
            Map<Integer, String> voxyBiomeIdToName, Level lvl) {

        if (raw.length != WS_SIZE * WS_SIZE * WS_SIZE) {
            throw new IllegalStateException("WorldSection raw length expected 32768, was " + raw.length);
        }
        var builder = VoxelVolume.builder(WS_SIZE);
        // WorldSection YZX: index = (y<<10)|(z<<5)|x
        for (int y = 0; y < WS_SIZE; y++) {
            for (int z = 0; z < WS_SIZE; z++) {
                for (int x = 0; x < WS_SIZE; x++) {
                    int idx = (y << 10) | (z << 5) | x;
                    long packed = raw[idx];
                    // Mapper.isAir: (packed>>27)&((1<<20)-1)==0
                    boolean isAir = ((packed >> 27) & ((1 << 20) - 1)) == 0;
                    int canonicalBlock;
                    int canonicalBiome;
                    if (isAir) {
                        canonicalBlock = CanonicalRegistries.BLOCK_AIR;
                        canonicalBiome = CanonicalRegistries.BIOME_UNKNOWN;
                    } else {
                        int voxyBlockId = (int) ((packed >> 27) & ((1 << 20) - 1));
                        int voxyBiomeId = (int) ((packed >> 47) & 0x1FF);
                        // Light is averaged in mip, not stored separately in VoxelVolume
                        canonicalBlock = voxyBlockIdToCanonical(voxyBlockId, mapper, canonicalNameToId);
                        String biomeName = voxyBiomeIdToName.get(voxyBiomeId);
                        if (biomeName == null) {
                            canonicalBiome = CanonicalRegistries.BIOME_UNKNOWN;
                        } else {
                            canonicalBiome = BiomeMapping.toCanonicalId(biomeName);
                            if (canonicalBiome == CanonicalRegistries.BIOME_UNKNOWN && biomeName.startsWith("minecraft:")) {
                                // End biomes are not in overworld canonical 54, preserve as diagnostic trace but keep 255 for verifier (which leaves biome parity unclaimed)
                                LOGGER.debug("[OracleCapture] Diagnostic End biome {} -> 255 (unknown) at voxyBiomeId {}", biomeName, voxyBiomeId);
                            }
                        }
                    }
                    if (canonicalBlock != CanonicalRegistries.BLOCK_AIR || canonicalBiome != CanonicalRegistries.BIOME_UNKNOWN) {
                        builder.setBlock(x, y, z, canonicalBlock);
                        builder.setBiome(x, y, z, canonicalBiome);
                    }
                }
            }
        }
        return builder.build();
    }

    private static int voxyBlockIdToCanonical(int voxyBlockId, Object mapper, Map<String, Integer> canonicalNameToId) {
        try {
            var m = mapper.getClass().getMethod("getBlockStateFromBlockId", int.class);
            Object state = m.invoke(mapper, voxyBlockId);
            if (state == null) throw new IllegalStateException("Mapper returned null BlockState for voxyId " + voxyBlockId);
            var getBlock = state.getClass().getMethod("getBlock");
            Object block = getBlock.invoke(state);
            var reg = Registries.BLOCK;
            var id = reg.getId((Block) block);
            if (id == null) throw new IllegalStateException("Registries.BLOCK.getId returned null for voxyId " + voxyBlockId);
            String name = id.toString();
            Integer canon = canonicalNameToId.get(name);
            if (canon != null) return canon;
            String msg = String.format("[OracleCapture] FATAL: Unknown canonical for non-air block %s voxyId=%d - registry mismatch (canonical 1104), aborting fixture", name, voxyBlockId);
            LOGGER.error(msg);
            throw new IllegalStateException(msg);
        } catch (IllegalStateException e) {
            throw e;
        } catch (Exception e) {
            String msg = String.format("[OracleCapture] FATAL: Block decode failed for voxyId=%d: %s", voxyBlockId, e.toString());
            LOGGER.error(msg);
            throw new IllegalStateException(msg, e);
        }
    }

    private static Map<String, Integer> buildCanonicalNameToId() {
        Map<String, Integer> m = new HashMap<>(CanonicalRegistries.BLOCK_COUNT * 2);
        for (int i = 0; i < CanonicalRegistries.BLOCK_COUNT; i++) {
            m.put(CanonicalRegistries.canonicalName(i), i);
        }
        return m;
    }

    private static Map<Integer, String> buildVoxyBiomeIdToName(Object mapper) {
        Map<Integer, String> m = new HashMap<>();
        try {
            var getBiomeEntries = mapper.getClass().getMethod("getBiomeEntries");
            Object[] entries = (Object[]) getBiomeEntries.invoke(mapper);
            if (entries != null) {
                for (Object e : entries) {
                    int id = e.getClass().getField("id").getInt(e);
                    String biome = (String) e.getClass().getField("biome").get(e);
                    m.put(id, biome);
                }
            }
        } catch (Exception e) {
            LOGGER.warn("[OracleCapture] Failed to read Mapper biome entries: {}", e.toString());
        }
        return m;
    }

    // ---- Reflection helpers for VoxyEngine ----

    private static Object getWorldEngineReflectively(net.minecraft.world.World level) {
        try {
            Class<?> ve = Class.forName("com.rhythmatician.lodiffusion.voxy.VoxyEngine");
            var meth = ve.getDeclaredMethod("getWorldEngine", net.minecraft.world.World.class);
            meth.setAccessible(true);
            return meth.invoke(null, level);
        } catch (Exception e) {
            LOGGER.warn("[OracleCapture] getWorldEngine failed: {}", e.toString());
            return null;
        }
    }

    private static Object getMapperReflectively(Object worldEngine) {
        try {
            Class<?> ve = Class.forName("com.rhythmatician.lodiffusion.voxy.VoxyEngine");
            var meth = ve.getDeclaredMethod("getMapper", Object.class);
            meth.setAccessible(true);
            return meth.invoke(null, worldEngine);
        } catch (Exception e) {
            LOGGER.warn("[OracleCapture] getMapper failed: {}", e.toString());
            return null;
        }
    }

    private static Object acquireWorldSectionIfExists(Object worldEngine, int lvl, int x, int y, int z) {
        try {
            Class<?> ve = Class.forName("com.rhythmatician.lodiffusion.voxy.VoxyEngine");
            var meth = ve.getDeclaredMethod("acquireIfExists", Object.class, int.class, int.class, int.class, int.class);
            meth.setAccessible(true);
            return meth.invoke(null, worldEngine, lvl, x, y, z);
        } catch (Exception e) {
            LOGGER.warn("[OracleCapture] acquireIfExists failed L{} [{},{},{}]: {}", lvl, x, y, z, e.toString());
            return null;
        }
    }

    private static long[] copyWorldSectionData(Object ws) {
        try {
            var meth = ws.getClass().getMethod("copyData");
            return (long[]) meth.invoke(ws);
        } catch (Exception e) {
            // Fallback to _unsafeGetRawDataArray clone
            try {
                var meth2 = ws.getClass().getMethod("_unsafeGetRawDataArray");
                long[] raw = (long[]) meth2.invoke(ws);
                return raw.clone();
            } catch (Exception e2) {
                throw new RuntimeException("Failed to copy WorldSection data", e2);
            }
        }
    }

    private static int getNonEmptyChildren(Object ws) {
        try {
            var meth = ws.getClass().getMethod("getNonEmptyChildren");
            return (byte) meth.invoke(ws) & 0xFF;
        } catch (Exception e) {
            return 0;
        }
    }

    private static void releaseWorldSection(Object ws) {
        try {
            var meth = ws.getClass().getMethod("release");
            meth.invoke(ws);
        } catch (Exception e) {
            LOGGER.warn("[OracleCapture] release failed: {}", e.toString());
        }
    }
}
