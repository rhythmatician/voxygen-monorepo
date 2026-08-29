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
        root.addProperty("actualCaptureStage", c.authoritativeGenerationStage()); // offline oracle may capture at FULL; record actual stage

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
        // Reconstruct contract from stored header — for now delegate to EndChorusTracerContract.contract()
        // and verify provenance matches; full generic contract deserialization can be added later.
        // We do not recompute contract from JSON; we load the canonical tracer contract and check id.
        OracleContract contract = com.rhythmatician.lodiffusion.oracle.EndChorusTracerContract.contract();
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
        return new OracleFixture(contract, map, contentSha256, createdAt);
    }
}
