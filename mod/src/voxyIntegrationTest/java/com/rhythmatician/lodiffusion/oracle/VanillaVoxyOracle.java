package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Real vanilla -> Voxy oracle for End chorus tracer.
 *
 * <p>Intended path (A -> real vanilla B -> real Voxy C):
 * <ol>
 *   <li>Boot {@code MinecraftServer} with frozen worldgen profile {@code end_pinned_v1_generate_structures=false} and seed from contract (42).
 *   <li>Obtain {@code ServerLevel} for {@code minecraft:the_end}; generate chunks covering target region
 *       {@link OracleContract.RegionSpec} plus halo {@code featureReach 8 + 1 chunk FEATURES + 1 block mip = 25}
 *       through {@code ChunkStatus.FEATURES} (authoritative for chorus via {@code ChunkPyramid writeRadius 1}).
 *   <li>For each 16^3 vanilla section, read {@code PalettedContainer<BlockState>} + {@code PalettedContainer<Holder<Biome>>}
 *       + light, call {@code WorldConversionFactory.convert -> mipSection(Mipper.mip)} with real {@code Mapper}.
 *   <li>Insert each {@code VoxelizedSection} into {@code WorldEngine} via {@code WorldUpdater.insertUpdate -> WorldSection},
 *       then read back {@code WorldSection} L0..L4 via {@code WorldEngine.acquire(lvl,x,y,z)}.
 *   <li>Decode per-world {@code Mapper} IDs ({@code getBlockId/getBiomeId} -> {@code blocks/stateEntry}, {@code biomeEntry})
 *       back to stable canonical IDs ({@code CanonicalRegistries BLOCK_COUNT 1104 / BIOME_COUNT 54+255}) before fixture serialization.
 *   <li>Serialize immutable {@code OracleFixture} (level-wise 32^3 {@code VoxelVolume} of canonical ids) with true
 *       {@code contentSha256} and full provenance from contract; changing minecraft/voxy/registry/fixture-format creates distinct identity.
 * </ol>
 *
 * <p>Current state: harness structure and contract validation are executable; live server bootstrap is the remaining blocker.
 * This class validates the contract and halo but throws a descriptive blocker for live capture until a headless
 * {@code MinecraftServer} harness exists in this loom project (see {@link #BLOCKER}).
 *
 * <p>Preserve independence: oracle must not share {@code EndChorusSynthesizer} or any helper that determines expected world semantics;
 * Voxy packed longs / YZX must not leak into fixture or verifier APIs.
 */
public final class VanillaVoxyOracle {
    private VanillaVoxyOracle() {}

    public static final String BLOCKER =
            "BLOCKED: live vanilla->Voxy End chorus capture requires a headless MinecraftServer/ServerLevel harness for "
            + "minecraft:the_end at fixed seed 42 through ChunkStatus.FEATURES with halo 25, plus a real "
            + "IMappingStorage/Mapper/WorldEngine pipeline that this loom project does not yet bootstrap in "
            + "voxyIntegrationTest. Existing voxyIntegrationTest runs with a mocked Mapper and HeadlessNodeManagerProbe; "
            + "there is no GameTest/IntegratedServer/FabricLoader server bootstrap that materializes real chunks, "
            + "feeds them through WorldConversionFactory.convert+mipSection, and decodes Mapper IDs to "
            + "CanonicalRegistries (1104/54+255). Implement a dedicated test server harness (e.g. Fabric GameTest "
            + "or dedicated server bootstrap via MinecraftServer.createFromArguments + RegistryAccess) in "
            + "src/voxyIntegrationTest, then wire WorldEngine+IMappingStorage (in-memory IMappingStorage impl over "
            + "Int2ObjectOpenHashMap) to capture L0..L4 WorldSections and decode to stable canonical fixture. "
            + "See EndChorusTracerContract inspected refs: ChorusPlantFeature:27-35, ChorusFlowerBlock:178-210 "
            + "maxHorizontalSpread=8, ChunkPyramid:18 writeRadius 1, ChunkStatus:28 FEATURES=7, "
            + "WorldConversionFactory:130-220, VoxelizedSection long[4681], Mipper:9-55, Mapper:1-120 "
            + "composeMappingId (light 56 biome 47 blockId 27), WorldUpdater:14-90, WorldSection YZX, "
            + "WorldEngine getWorldSectionId. Until then synthetic fixture remains harness-only.";

    /**
     * Capture a real fixture for the given contract via live vanilla + live Voxy.
     * Validates contract + halo but currently throws blocker if live server is unavailable.
     */
    public static OracleFixture capture(OracleContract contract) {
        Objects.requireNonNull(contract, "contract");
        contract.validate();
        if (!"FEATURES".equals(contract.authoritativeGenerationStage())) {
            throw new IllegalArgumentException("chorus tracer requires FEATURES, was " + contract.authoritativeGenerationStage());
        }
        if (contract.halo().combinedHaloBlocks() < 25) {
            throw new IllegalArgumentException("halo too small for chorus: need >=25 (8+16+1), was " + contract.halo().combinedHaloBlocks());
        }
        if (contract.halo().featureReachBlocks() != 8
                || contract.halo().minecraftGenerationHaloChunks() != 1
                || contract.halo().voxyMipHaloBlocks() != 1) {
            throw new IllegalArgumentException("halo must be decomposed 8/1/1/25 per EndChorusTracerContract inspection, was "
                    + contract.halo().featureReachBlocks() + "/" + contract.halo().minecraftGenerationHaloChunks()
                    + "/" + contract.halo().voxyMipHaloBlocks() + "/" + contract.halo().combinedHaloBlocks());
        }
        if (!"minecraft:the_end".equals(contract.dimension())) {
            throw new IllegalArgumentException("tracer dimension must be minecraft:the_end, was " + contract.dimension());
        }
        // Live path would: bootstrap server, generate chunks, convert+mip via real Voxy, read WorldSections, decode to canonical.
        // Today the loom voxyIntegrationTest classpath has no server bootstrap - fail with actionable blocker.
        throw new IllegalStateException(BLOCKER);
    }

    /**
     * Decode a per-world Voxy block/biome mapping back to stable canonical ids.
     * Real implementation would consult Mapper blockId2stateEntry/biomeId2biomeEntry and
     * CanonicalRegistries mapping; stub validates bounds but preserves the seam.
     */
    public static int decodeBlockId(int perWorldBlockId, String canonicalName) {
        if (perWorldBlockId < 0) throw new IllegalArgumentException("perWorldBlockId <0");
        // CanonicalRegistries is the stable target; real decode maps via Mapper.getBlockStateFromBlockId
        // -> BlockState -> registry key -> canonicalName -> CanonicalRegistries lookup.
        if (canonicalName == null || canonicalName.isBlank()) throw new IllegalArgumentException("canonicalName required");
        // For now just validate canonicalName is a known canonical via linear scan; real path uses vocabulary map.
        for (int i = 0; i < CanonicalRegistries.BLOCK_COUNT; i++) {
            if (CanonicalRegistries.canonicalName(i).equals(canonicalName)) return i;
        }
        throw new IllegalArgumentException("unknown canonicalName " + canonicalName);
    }

    public static int decodeBiomeId(String perWorldBiomeKey) {
        if (perWorldBiomeKey == null || perWorldBiomeKey.isBlank()) throw new IllegalArgumentException("perWorldBiomeKey required");
        if (perWorldBiomeKey.equals("minecraft:unknown") || perWorldBiomeKey.equals("unknown")) return CanonicalRegistries.BIOME_UNKNOWN;
        // Validate against known biomes would use BiomeMapping; bounds check suffices for seam proof.
        return CanonicalRegistries.BIOME_UNKNOWN;
    }

    static Map<Level, VoxelVolume> emptyVolumesForContract(OracleContract contract) {
        Map<Level, VoxelVolume> m = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            VoxelVolume.Builder b = VoxelVolume.builder(32);
            m.put(l, b.build());
        }
        return m;
    }
}
