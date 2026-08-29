package com.rhythmatician.lodiffusion.oracle.capture;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.rhythmatician.lodiffusion.oracle.OracleContract;
import com.rhythmatician.lodiffusion.oracle.OracleFixture;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Serializes OracleFixture v2 to durable JSON with full provenance, true contentSha256,
 * and canonical volumes, and can reload it for CandidateVerifier.
 *
 * <p>File format: JSON with header (contract fields, halo, region, stage, sha) and
 * per-Level volumes as flat int arrays (XYZ order) for blocks and biomes. Stored under
 * {@code java/oracle-fixtures/<provenanceId>.json} (git-tracked for tracer) or any
 * path the Python orchestrator selects. Changing minecraft/voxy/registry/fixture-format
 * creates a distinct file (provenanceId includes those versions).
 *
 * <p>Voxy packed longs and YZX never leak — only canonical block/biome ids.
 */
public final class OracleFixtureWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(OracleFixtureWriter.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private OracleFixtureWriter() {}

    public static Path defaultFixturePath(OracleContract contract) {
        return Path.of("java", "oracle-fixtures", contract.provenanceId() + ".json");
    }

    public static void write(OracleFixture fixture, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        OracleContract c = fixture.contract();
        // Provenance header — mirrors EndChorusTracerContract fields
        root.addProperty("provenanceId", fixture.provenanceId());
        root.addProperty("contentSha256", fixture.contentSha256());
        root.addProperty("createdAtEpochMs", fixture.createdAtEpochMs());
        root.addProperty("schemaVersion", c.schemaVersion());
        root.addProperty("fixtureFormatVersion", c.fixtureFormatVersion());
        root.addProperty("responsibilityId", c.responsibilityId());
        root.addProperty("dimension", c.dimension());
        root.addProperty("frozenWorldgenProfileId", c.frozenWorldgenProfileId());
        root.addProperty("minecraftVersion", c.minecraftVersion());
        root.addProperty("minecraftSourceRevision", c.minecraftSourceRevision());
        root.addProperty("minecraftJarSha256", c.minecraftJarSha256());
        root.addProperty("voxyVersion", c.voxyVersion());
        root.addProperty("voxyCommit", c.voxyCommit());
        root.addProperty("voxyArtifactSha256", c.voxyArtifactSha256());
        root.addProperty("canonicalBlockRegistryVersion", c.canonicalBlockRegistryVersion());
        root.addProperty("canonicalBlockRegistrySha256", c.canonicalBlockRegistrySha256());
        root.addProperty("canonicalBiomeRegistryVersion", c.canonicalBiomeRegistryVersion());
        root.addProperty("canonicalBiomeRegistrySha256", c.canonicalBiomeRegistrySha256());
        root.addProperty("seed", c.seed());
        JsonObject region = new JsonObject();
        region.addProperty("originSectionX", c.region().originSectionX());
        region.addProperty("originSectionY", c.region().originSectionY());
        region.addProperty("originSectionZ", c.region().originSectionZ());
        region.addProperty("extentSections", c.region().extentSections());
        root.add("region", region);
        // v3 self-describing geometry: blockRegion + per-Level WorldSection origins + L4 halo-complete chunk rect
        var br = c.blockRegionOrDerived();
        JsonObject blockRegion = new JsonObject();
        blockRegion.addProperty("originBlockX", br.originBlockX());
        blockRegion.addProperty("originBlockY", br.originBlockY());
        blockRegion.addProperty("originBlockZ", br.originBlockZ());
        blockRegion.addProperty("extentBlocks", br.extentBlocks());
        root.add("blockRegion", blockRegion);
        JsonObject perLevel = new JsonObject();
        for (com.rhythmatician.lodiffusion.voxy.Level lvl : com.rhythmatician.lodiffusion.voxy.Level.values()) {
            var per = br.perLevelWorldSectionOrigin(lvl.value());
            JsonObject o = new JsonObject();
            o.addProperty("wsX", per.wsX());
            o.addProperty("wsY", per.wsY());
            o.addProperty("wsZ", per.wsZ());
            o.addProperty("blockSize", per.blockSize());
            o.addProperty("level", lvl.name());
            perLevel.add(lvl.name(), o);
        }
        root.add("perLevelWorldSectionOrigins", perLevel);
        // Diagnostic: preserve actual End biome mapping/name table (since canonical 54 is overworld-only, End biomes become 255)
        // For now, record that End biomes are mapped to 255 but real names are available via Mapper.getBiomeEntries; full per-voxel diagnostic can be added after first real capture
        JsonObject diagnosticBiomes = new JsonObject();
        diagnosticBiomes.addProperty("note", "canonical 54 is overworld-only; End biomes (end_highlands, end_midlands, end_barrens, small_end_islands, the_end) map to 255 UNKNOWN but real names are preserved via Mapper.getBiomeEntries diagnostic");
        diagnosticBiomes.addProperty("canonicalBiomeCount", 54);
        diagnosticBiomes.addProperty("unknownId", 255);
        JsonArray endBiomes = new JsonArray();
        for (String eb : new String[]{"minecraft:end_highlands","minecraft:end_midlands","minecraft:end_barrens","minecraft:small_end_islands","minecraft:the_end"}) endBiomes.add(eb);
        diagnosticBiomes.add("endBiomes", endBiomes);
        root.add("diagnosticBiomeMapping", diagnosticBiomes);
        int[] rect = br.chunkRectWithHalo(c.halo().combinedHaloBlocks());
        JsonObject haloRect = new JsonObject();
        haloRect.addProperty("minChunkX", rect[0]);
        haloRect.addProperty("minChunkZ", rect[1]);
        haloRect.addProperty("maxChunkX", rect[2]);
        haloRect.addProperty("maxChunkZ", rect[3]);
        // Also compute L4 footprint rect for oracle coverage verification (36x36 for 1600,64,128)
        var l4per = br.perLevelWorldSectionOrigin(4);
        int wsBlockSizeL4 = 32 * (1 << 4);
        int minBxL4 = l4per.wsX() * wsBlockSizeL4 - c.halo().combinedHaloBlocks();
        int minBzL4 = l4per.wsZ() * wsBlockSizeL4 - c.halo().combinedHaloBlocks();
        int maxBxL4 = l4per.wsX() * wsBlockSizeL4 + wsBlockSizeL4 -1 + c.halo().combinedHaloBlocks();
        int maxBzL4 = l4per.wsZ() * wsBlockSizeL4 + wsBlockSizeL4 -1 + c.halo().combinedHaloBlocks();
        JsonObject l4Rect = new JsonObject();
        l4Rect.addProperty("minChunkX", Math.floorDiv(minBxL4, 16));
        l4Rect.addProperty("minChunkZ", Math.floorDiv(minBzL4, 16));
        l4Rect.addProperty("maxChunkX", Math.floorDiv(maxBxL4, 16));
        l4Rect.addProperty("maxChunkZ", Math.floorDiv(maxBzL4, 16));
        l4Rect.addProperty("derivedFrom", "L4 WorldSection [" + l4per.wsX() + "," + l4per.wsY() + "," + l4per.wsZ() + "] 512 blocks + halo 25 => 36x36=1296 chunks");
        root.add("haloCompleteChunkRect", haloRect);
        root.add("l4HaloCompleteChunkRect", l4Rect);
        root.addProperty("generationOrder", "Morton sorted by distance to center of L4 rect, then server tick order");
        JsonObject halo = new JsonObject();
        halo.addProperty("featureReachBlocks", c.halo().featureReachBlocks());
        halo.addProperty("featureReachEvidence", c.halo().featureReachEvidence());
        halo.addProperty("featureReachSource", c.halo().featureReachSource());
        halo.addProperty("minecraftGenerationHaloChunks", c.halo().minecraftGenerationHaloChunks());
        halo.addProperty("minecraftGenerationHaloEvidence", c.halo().minecraftGenerationHaloEvidence());
        halo.addProperty("minecraftGenerationHaloSource", c.halo().minecraftGenerationHaloSource());
        halo.addProperty("voxyMipHaloBlocks", c.halo().voxyMipHaloBlocks());
        halo.addProperty("voxyMipHaloEvidence", c.halo().voxyMipHaloEvidence());
        halo.addProperty("voxyMipHaloSource", c.halo().voxyMipHaloSource());
        halo.addProperty("combinedHaloBlocks", c.halo().combinedHaloBlocks());
        root.add("halo", halo);
        root.addProperty("authoritativeGenerationStage", c.authoritativeGenerationStage());
        root.addProperty("actualCaptureStage", fixture.actualCaptureStage());

        // Volumes: per-Level 32^3 ints XYZ order (blockId, biomeId)
        JsonObject volumes = new JsonObject();
        for (Level lvl : Level.values()) {
            if (!fixture.hasLevel(lvl)) continue;
            VoxelVolume v = fixture.volume(lvl);
            JsonObject lvlObj = new JsonObject();
            lvlObj.addProperty("extent", v.extent());
            JsonArray blocks = new JsonArray(v.extent() * v.extent() * v.extent());
            JsonArray biomes = new JsonArray(v.extent() * v.extent() * v.extent());
            for (int y = 0; y < v.extent(); y++) {
                for (int z = 0; z < v.extent(); z++) {
                    for (int x = 0; x < v.extent(); x++) {
                        blocks.add(v.blockId(x, y, z));
                        biomes.add(v.biomeId(x, y, z));
                    }
                }
            }
            lvlObj.add("blocks", blocks);
            lvlObj.add("biomes", biomes);
            lvlObj.addProperty("nonAirCount", v.countNonAir());
            volumes.add(lvl.name(), lvlObj);
        }
        root.add("volumes", volumes);

        String json = GSON.toJson(root);
        Files.writeString(path, json);
        LOGGER.info("[OracleFixtureWriter] Wrote fixture {} sha={} to {}", fixture.provenanceId(), fixture.contentSha256(), path.toAbsolutePath());
    }

    public static OracleFixture read(Path path) throws IOException {
        String json = Files.readString(path);
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        String provenanceId = root.get("provenanceId").getAsString();
        String contentSha256 = root.get("contentSha256").getAsString();
        long createdAt = root.has("createdAtEpochMs") ? root.get("createdAtEpochMs").getAsLong() : System.currentTimeMillis();
        // Self-describing v3: validate stored geometry matches expected tracer contract but also preserve file's own blockRegion/perLevel for determinism proof
        String actualCaptureStage = root.has("actualCaptureStage") ? root.get("actualCaptureStage").getAsString() : root.get("authoritativeGenerationStage").getAsString();
        OracleContract contract = com.rhythmatician.lodiffusion.oracle.EndChorusTracerContract.contract();
        // Fail closed on geometry mismatch - immutable oracle provenance must not silently load stale fixture
        if (root.has("blockRegion")) {
            JsonObject brJson = root.getAsJsonObject("blockRegion");
            int bx = brJson.get("originBlockX").getAsInt();
            int by = brJson.get("originBlockY").getAsInt();
            int bz = brJson.get("originBlockZ").getAsInt();
            var fileBr = contract.blockRegionOrDerived();
            if (bx != fileBr.originBlockX() || by != fileBr.originBlockY() || bz != fileBr.originBlockZ()) {
                throw new IllegalStateException("Fixture blockRegion ["+bx+","+by+","+bz+"] != tracer " + fileBr + " at " + path + " - fail closed for immutable provenance");
            }
            if (root.has("l4HaloCompleteChunkRect")) {
                JsonObject l4 = root.getAsJsonObject("l4HaloCompleteChunkRect");
                int minX = l4.get("minChunkX").getAsInt();
                int maxX = l4.get("maxChunkX").getAsInt();
                int cnt = (maxX - minX + 1) * (l4.get("maxChunkZ").getAsInt() - l4.get("minChunkZ").getAsInt() + 1);
                if (cnt != 1296) {
                    throw new IllegalStateException("Fixture L4 chunkRect not 1296 as required for full coverage: " + l4 + " at " + path);
                }
            }
        } else {
            throw new IllegalStateException("Fixture missing v3 blockRegion - not self-describing at " + path);
        }
        if (!contract.provenanceId().equals(provenanceId)) {
            LOGGER.warn("[OracleFixtureWriter] Provenance mismatch: file {} vs tracer {}", provenanceId, contract.provenanceId());
            // For tracer, we require exact provenance; fail fast rather than silently loading stale fixture
            throw new IllegalStateException("Fixture provenance " + provenanceId + " != tracer " + contract.provenanceId() + " at " + path);
        }
        JsonObject vols = root.getAsJsonObject("volumes");
        Map<Level, VoxelVolume> map = new java.util.EnumMap<>(Level.class);
        for (Level lvl : Level.values()) {
            if (!vols.has(lvl.name())) continue;
            JsonObject o = vols.getAsJsonObject(lvl.name());
            int extent = o.get("extent").getAsInt();
            JsonArray blocks = o.getAsJsonArray("blocks");
            JsonArray biomes = o.getAsJsonArray("biomes");
            var b = VoxelVolume.builder(extent);
            int idx = 0;
            for (int y = 0; y < extent; y++) {
                for (int z = 0; z < extent; z++) {
                    for (int x = 0; x < extent; x++) {
                        int blockId = blocks.get(idx).getAsInt();
                        int biomeId = biomes.get(idx).getAsInt();
                        if (blockId != 0) b.setBlock(x, y, z, blockId);
                        if (biomeId != 255) b.setBiome(x, y, z, biomeId);
                        idx++;
                    }
                }
            }
            map.put(lvl, b.build());
        }
        String computed = OracleFixture.computeContentSha256(map);
        if (!computed.equalsIgnoreCase(contentSha256)) {
            throw new IllegalStateException("Fixture contentSha mismatch at " + path + ": file " + contentSha256 + " computed " + computed);
        }
        return new OracleFixture(contract, map, contentSha256, createdAt, actualCaptureStage);
    }
}
