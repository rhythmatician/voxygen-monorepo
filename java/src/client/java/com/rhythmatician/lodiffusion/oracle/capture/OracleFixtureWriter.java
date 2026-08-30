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
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    private OracleFixtureWriter() {}

    public static Path defaultFixturePath(OracleContract contract) {
        return Path.of("../oracle-fixtures", contract.provenanceId() + ".json");
    }

    public static void write(OracleFixture fixture, Path path) throws IOException {
        Files.createDirectories(path.getParent());
        JsonObject root = new JsonObject();
        OracleContract c = fixture.contract();
        // Provenance header — mirrors EndChorusTracerContract fields; protocolSha binds full provenance integrity
        root.addProperty("provenanceId", fixture.provenanceId());
        root.addProperty("contentSha256", fixture.contentSha256());
        root.addProperty("protocolSha256", fixture.protocolSha256());
        root.addProperty("captureProtocolSha256", fixture.captureProtocolSha256());
        root.addProperty("evidenceKind", fixture.evidenceKind().name());
        root.addProperty("evidenceIntegritySha256", fixture.evidenceIntegritySha256());
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
        root.addProperty("generationOrder", "squared distance to center -> X -> Z (explicit, not Morton), then server tick order");
        // Full provenance binding: inspected source refs, per-Level dispositions, roles, benchmark policy
        JsonArray mcRefs = new JsonArray();
        for (String s : c.inspectedMinecraftReferences()) mcRefs.add(s);
        root.add("inspectedMinecraftReferences", mcRefs);
        JsonArray vxRefs = new JsonArray();
        for (String s : c.inspectedVoxyReferences()) vxRefs.add(s);
        root.add("inspectedVoxyReferences", vxRefs);
        JsonObject perLevelDecisions = new JsonObject();
        for (var lvl : com.rhythmatician.lodiffusion.voxy.Level.values()) {
            var pd = switch (lvl) {
                case L4 -> c.perLevelDecisions().l4();
                case L3 -> c.perLevelDecisions().l3();
                case L2 -> c.perLevelDecisions().l2();
                case L1 -> c.perLevelDecisions().l1();
                case L0 -> c.perLevelDecisions().l0();
            };
            JsonObject o = new JsonObject();
            o.addProperty("disposition", pd.disposition());
            JsonArray cands = new JsonArray();
            for (String cand : pd.candidates()) cands.add(cand);
            o.add("candidates", cands);
            o.addProperty("rationale", pd.rationale());
            perLevelDecisions.add(lvl.name(), o);
        }
        root.add("perLevelDecisions", perLevelDecisions);
        JsonObject roles = new JsonObject();
        roles.addProperty("claimRole", c.roles().claimRole());
        roles.addProperty("dependencyRole", c.roles().dependencyRole());
        roles.addProperty("rationale", c.roles().rationale());
        root.add("roles", roles);
        if (c.benchmarkPolicy() != null) {
            JsonObject bp = new JsonObject();
            bp.addProperty("warmupIterations", c.benchmarkPolicy().warmupIterations());
            bp.addProperty("measurementIterations", c.benchmarkPolicy().measurementIterations());
            bp.addProperty("repetitionPolicy", c.benchmarkPolicy().repetitionPolicy());
            root.add("benchmarkPolicy", bp);
        }
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
        String actualCaptureStage = root.has("actualCaptureStage") ? root.get("actualCaptureStage").getAsString() : root.get("authoritativeGenerationStage").getAsString();
        String storedProtocolSha = root.has("protocolSha256") ? root.get("protocolSha256").getAsString() : null;
        String storedCaptureProtocolSha = root.has("captureProtocolSha256") ? root.get("captureProtocolSha256").getAsString() : null;
        String storedEvidenceIntegritySha = root.has("evidenceIntegritySha256") ? root.get("evidenceIntegritySha256").getAsString() : null;
        String storedEvidenceKind = root.has("evidenceKind") ? root.get("evidenceKind").getAsString() : null;
        String storedGenerationOrder = root.has("generationOrder") ? root.get("generationOrder").getAsString() : null;
        OracleContract contract = com.rhythmatician.lodiffusion.oracle.EndChorusTracerContract.contract();
        // Generation order is immutable capture protocol
        if (storedGenerationOrder == null) throw new IllegalStateException("Fixture missing generationOrder at " + path);
        if (!OracleContract.EXPECTED_GENERATION_ORDER.equals(storedGenerationOrder)) {
            throw new IllegalStateException("Fixture generationOrder '" + storedGenerationOrder + "' != expected '" + OracleContract.EXPECTED_GENERATION_ORDER + "' at " + path + " — generation order is acceptance-bearing");
        }
        // Capture protocol SHA is the immutable identity — excludes mutable partition/policy state
        if (storedCaptureProtocolSha == null) throw new IllegalStateException("Fixture missing captureProtocolSha256 at " + path + " — fixture not integrity-bound (immutable capture protocol)");
        String currentCaptureSha = contract.captureProtocolSha256();
        if (!storedCaptureProtocolSha.equalsIgnoreCase(currentCaptureSha)) {
            throw new IllegalStateException("Fixture captureProtocolSha256 " + storedCaptureProtocolSha + " != current tracer " + currentCaptureSha + " at " + path + " — immutable capture provenance out of sync (voxy commit, jar sha, registry, halo, generationOrder, seed, region, etc. — but NOT per-Level dispositions/roles/benchmark)");
        }
        // Verify stored captureProtocol matches recomputed from stored header (tamper detection, immutable)
        try {
            String recomputedCapture = reconstructCaptureProtocolShaFromJson(root);
            if (!storedCaptureProtocolSha.equalsIgnoreCase(recomputedCapture)) {
                throw new IllegalStateException("Fixture captureProtocolSha256 " + storedCaptureProtocolSha + " != recomputed from stored provenance " + recomputedCapture + " at " + path + " — file header tampered (immutable capture)");
            }
        } catch (IllegalStateException e) { throw e; } catch (Exception e) {
            throw new IllegalStateException("Failed to recompute captureProtocolSha from stored header at " + path + ": " + e.getMessage(), e);
        }
        // Legacy full protocolSha is kept for reference but is NOT authoritative for capture validity (mutable). If present, verify but do not fail on mutable mismatch beyond warning.
        if (storedProtocolSha != null) {
            try {
                String recomputedFull = reconstructProtocolShaFromJson(root);
                if (!storedProtocolSha.equalsIgnoreCase(recomputedFull)) {
                    throw new IllegalStateException("Fixture protocolSha256 " + storedProtocolSha + " != recomputed " + recomputedFull + " — file header tampered (full)");
                }
            } catch (IllegalStateException e) { throw e; } catch (Exception e) {
                throw new IllegalStateException("Failed to recompute protocolSha at " + path + ": " + e.getMessage(), e);
            }
        }
        if (storedEvidenceKind == null) throw new IllegalStateException("Fixture missing evidenceKind at " + path);
        OracleFixture.EvidenceKind evidenceKind;
        try { evidenceKind = OracleFixture.EvidenceKind.valueOf(storedEvidenceKind); } catch (Exception e) { throw new IllegalStateException("Invalid evidenceKind '" + storedEvidenceKind + "' at " + path); }
        // Evidence integrity binding — tampering actualCaptureStage or evidenceKind must fail
        if (storedEvidenceIntegritySha == null) throw new IllegalStateException("Fixture missing evidenceIntegritySha256 at " + path + " — evidence not integrity-bound");
        String expectedIntegrity = OracleFixture.computeEvidenceIntegritySha256(storedCaptureProtocolSha, contentSha256, actualCaptureStage, evidenceKind);
        if (!storedEvidenceIntegritySha.equalsIgnoreCase(expectedIntegrity)) {
            throw new IllegalStateException("Fixture evidenceIntegritySha256 " + storedEvidenceIntegritySha + " != expected " + expectedIntegrity + " at " + path + " — evidence binding tampered (captureProtocol/content/stage/kind)");
        }
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
        // Reconstitute fixture with validated evidence binding — REAL_CAPTURE is unforgeable via public constructor
        if (evidenceKind == OracleFixture.EvidenceKind.REAL_CAPTURE) {
            return OracleFixture.reconstituteValidatedRealFixture(contract, map, contentSha256, createdAt, actualCaptureStage, evidenceKind, storedCaptureProtocolSha, storedEvidenceIntegritySha);
        } else {
            // SYNTHETIC_TEST via public synthetic path (must match captureProtocol and integrity)
            return new OracleFixture(contract, map, contentSha256, createdAt, actualCaptureStage, evidenceKind, storedCaptureProtocolSha);
        }
    }

    private static String reconstructCaptureProtocolShaFromJson(JsonObject root) {
        try {
            java.security.MessageDigest md = java.security.MessageDigest.getInstance("SHA-256");
            StringBuilder sb = new StringBuilder(4096);
            sb.append(root.get("schemaVersion").getAsString()).append('|');
            sb.append(root.get("responsibilityId").getAsString()).append('|');
            sb.append(root.get("dimension").getAsString()).append('|');
            sb.append(root.get("frozenWorldgenProfileId").getAsString()).append('|');
            sb.append(root.get("minecraftVersion").getAsString()).append('|');
            sb.append(root.get("minecraftSourceRevision").getAsString()).append('|');
            sb.append(root.get("minecraftJarSha256").getAsString()).append('|');
            sb.append(root.get("voxyVersion").getAsString()).append('|');
            sb.append(root.get("voxyCommit").getAsString()).append('|');
            sb.append(root.get("voxyArtifactSha256").getAsString()).append('|');
            sb.append(root.get("canonicalBlockRegistryVersion").getAsString()).append('|');
            sb.append(root.get("canonicalBlockRegistrySha256").getAsString()).append('|');
            sb.append(root.get("canonicalBiomeRegistryVersion").getAsString()).append('|');
            sb.append(root.get("canonicalBiomeRegistrySha256").getAsString()).append('|');
            var mcArr = root.has("inspectedMinecraftReferences") ? root.getAsJsonArray("inspectedMinecraftReferences") : new com.google.gson.JsonArray();
            var vxArr = root.has("inspectedVoxyReferences") ? root.getAsJsonArray("inspectedVoxyReferences") : new com.google.gson.JsonArray();
            java.util.List<String> mcRefs = new java.util.ArrayList<>();
            for (var e : mcArr) mcRefs.add(e.getAsString());
            java.util.List<String> vxRefs = new java.util.ArrayList<>();
            for (var e : vxArr) vxRefs.add(e.getAsString());
            mcRefs.sort(String::compareTo);
            vxRefs.sort(String::compareTo);
            sb.append(String.join("\n", mcRefs)).append('|');
            sb.append(String.join("\n", vxRefs)).append('|');
            sb.append(root.get("seed").getAsLong()).append('|');
            if (root.has("region")) {
                var r = root.getAsJsonObject("region");
                sb.append(r.get("originSectionX").getAsInt()).append(',').append(r.get("originSectionY").getAsInt()).append(',').append(r.get("originSectionZ").getAsInt()).append(',').append(r.get("extentSections").getAsInt()).append('|');
            } else sb.append('|');
            // blockRegion + per-Level origins + rects
            com.rhythmatician.lodiffusion.oracle.OracleContract.BlockRegionSpec br = null;
            if (root.has("blockRegion")) {
                var brJson = root.getAsJsonObject("blockRegion");
                br = new com.rhythmatician.lodiffusion.oracle.OracleContract.BlockRegionSpec(
                        brJson.get("originBlockX").getAsInt(), brJson.get("originBlockY").getAsInt(), brJson.get("originBlockZ").getAsInt(), brJson.get("extentBlocks").getAsInt());
                sb.append(br.originBlockX()).append(',').append(br.originBlockY()).append(',').append(br.originBlockZ()).append(',').append(br.extentBlocks()).append('|');
                for (int lvl = 4; lvl >= 0; lvl--) {
                    var per = br.perLevelWorldSectionOrigin(lvl);
                    sb.append(per.wsX()).append(',').append(per.wsY()).append(',').append(per.wsZ()).append(',').append(per.level()).append(',').append(per.blockSize()).append(';');
                }
                sb.append('|');
                // halo needed for rects
                int haloBlocks = 25;
                if (root.has("halo")) {
                    var h = root.getAsJsonObject("halo");
                    haloBlocks = h.get("combinedHaloBlocks").getAsInt();
                }
                int[] rect = br.chunkRectWithHalo(haloBlocks);
                sb.append(rect[0]).append(',').append(rect[1]).append(',').append(rect[2]).append(',').append(rect[3]).append('|');
                var l4per = br.perLevelWorldSectionOrigin(4);
                int ws = 32 * (1 << 4);
                int minBxL4 = l4per.wsX() * ws - haloBlocks;
                int minBzL4 = l4per.wsZ() * ws - haloBlocks;
                int maxBxL4 = l4per.wsX() * ws + ws - 1 + haloBlocks;
                int maxBzL4 = l4per.wsZ() * ws + ws - 1 + haloBlocks;
                sb.append(Math.floorDiv(minBxL4, 16)).append(',').append(Math.floorDiv(minBzL4, 16)).append(',').append(Math.floorDiv(maxBxL4, 16)).append(',').append(Math.floorDiv(maxBzL4, 16)).append('|');
            } else sb.append("|||");
            if (root.has("halo")) {
                var h = root.getAsJsonObject("halo");
                sb.append(h.get("featureReachBlocks").getAsInt()).append('|').append(h.get("featureReachEvidence").getAsString()).append('|').append(h.get("featureReachSource").getAsString()).append('|');
                sb.append(h.get("minecraftGenerationHaloChunks").getAsInt()).append('|').append(h.get("minecraftGenerationHaloEvidence").getAsString()).append('|').append(h.get("minecraftGenerationHaloSource").getAsString()).append('|');
                sb.append(h.get("voxyMipHaloBlocks").getAsInt()).append('|').append(h.get("voxyMipHaloEvidence").getAsString()).append('|').append(h.get("voxyMipHaloSource").getAsString()).append('|');
                sb.append(h.get("combinedHaloBlocks").getAsInt()).append('|');
            } else sb.append("||||||||||");
            sb.append(root.has("generationOrder") ? root.get("generationOrder").getAsString() : com.rhythmatician.lodiffusion.oracle.OracleContract.EXPECTED_GENERATION_ORDER).append('|');
            sb.append(root.get("authoritativeGenerationStage").getAsString()).append('|');
            sb.append(root.get("fixtureFormatVersion").getAsString()).append('|');
            sb.append(root.get("provenanceId").getAsString()).append('|');
            // Deliberately EXCLUDE mutable partition/policy state
            byte[] digest = md.digest(sb.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (Exception e) { throw new IllegalStateException("reconstruct capture failed: " + e.getMessage(), e); }
    }

    private static String reconstructProtocolShaFromJson(JsonObject root) {
        try {
            var builder = com.rhythmatician.lodiffusion.oracle.OracleContract.builder()
                    .schemaVersion(root.get("schemaVersion").getAsString())
                    .responsibilityId(root.get("responsibilityId").getAsString())
                    .dimension(root.get("dimension").getAsString())
                    .frozenWorldgenProfileId(root.get("frozenWorldgenProfileId").getAsString())
                    .minecraftVersion(root.get("minecraftVersion").getAsString())
                    .minecraftSourceRevision(root.get("minecraftSourceRevision").getAsString())
                    .minecraftJarSha256(root.get("minecraftJarSha256").getAsString())
                    .voxyVersion(root.get("voxyVersion").getAsString())
                    .voxyCommit(root.get("voxyCommit").getAsString())
                    .voxyArtifactSha256(root.get("voxyArtifactSha256").getAsString())
                    .canonicalBlockRegistryVersion(root.get("canonicalBlockRegistryVersion").getAsString())
                    .canonicalBlockRegistrySha256(root.get("canonicalBlockRegistrySha256").getAsString())
                    .canonicalBiomeRegistryVersion(root.get("canonicalBiomeRegistryVersion").getAsString())
                    .canonicalBiomeRegistrySha256(root.get("canonicalBiomeRegistrySha256").getAsString())
                    .seed(root.get("seed").getAsLong())
                    .authoritativeGenerationStage(root.get("authoritativeGenerationStage").getAsString())
                    .fixtureFormatVersion(root.get("fixtureFormatVersion").getAsString())
                    .provenanceId(root.get("provenanceId").getAsString())
                    .generationOrder(root.get("generationOrder").getAsString());
            if (root.has("region")) {
                var r = root.getAsJsonObject("region");
                builder.region(new com.rhythmatician.lodiffusion.oracle.OracleContract.RegionSpec(
                        r.get("originSectionX").getAsInt(), r.get("originSectionY").getAsInt(), r.get("originSectionZ").getAsInt(), r.get("extentSections").getAsInt()));
            }
            if (root.has("blockRegion")) {
                var br = root.getAsJsonObject("blockRegion");
                builder.blockRegion(new com.rhythmatician.lodiffusion.oracle.OracleContract.BlockRegionSpec(
                        br.get("originBlockX").getAsInt(), br.get("originBlockY").getAsInt(), br.get("originBlockZ").getAsInt(), br.get("extentBlocks").getAsInt()));
            }
            if (root.has("halo")) {
                var h = root.getAsJsonObject("halo");
                builder.halo(new com.rhythmatician.lodiffusion.oracle.OracleContract.HaloSpec(
                        h.get("featureReachBlocks").getAsInt(), h.get("featureReachEvidence").getAsString(), h.get("featureReachSource").getAsString(),
                        h.get("minecraftGenerationHaloChunks").getAsInt(), h.get("minecraftGenerationHaloEvidence").getAsString(), h.get("minecraftGenerationHaloSource").getAsString(),
                        h.get("voxyMipHaloBlocks").getAsInt(), h.get("voxyMipHaloEvidence").getAsString(), h.get("voxyMipHaloSource").getAsString(),
                        h.get("combinedHaloBlocks").getAsInt()));
            }
            if (root.has("inspectedMinecraftReferences")) {
                var arr = root.getAsJsonArray("inspectedMinecraftReferences");
                var list = new java.util.ArrayList<String>();
                for (var e : arr) list.add(e.getAsString());
                builder.inspectedMinecraftReferences(list);
            }
            if (root.has("inspectedVoxyReferences")) {
                var arr = root.getAsJsonArray("inspectedVoxyReferences");
                var list = new java.util.ArrayList<String>();
                for (var e : arr) list.add(e.getAsString());
                builder.inspectedVoxyReferences(list);
            }
            if (root.has("perLevelDecisions")) {
                var pld = root.getAsJsonObject("perLevelDecisions");
                var map = new java.util.HashMap<com.rhythmatician.lodiffusion.voxy.Level, com.rhythmatician.lodiffusion.oracle.OracleContract.PartitionDecision>();
                for (var lvl : com.rhythmatician.lodiffusion.voxy.Level.values()) {
                    if (!pld.has(lvl.name())) continue;
                    var o = pld.getAsJsonObject(lvl.name());
                    var cands = new java.util.ArrayList<String>();
                    for (var ce : o.getAsJsonArray("candidates")) cands.add(ce.getAsString());
                    var pd = new com.rhythmatician.lodiffusion.oracle.OracleContract.PartitionDecision(o.get("disposition").getAsString(), cands, o.get("rationale").getAsString());
                    map.put(lvl, pd);
                }
                builder.perLevelDecisions(new com.rhythmatician.lodiffusion.oracle.OracleContract.PerLevelPartitionDecisions(
                        map.get(com.rhythmatician.lodiffusion.voxy.Level.L4), map.get(com.rhythmatician.lodiffusion.voxy.Level.L3), map.get(com.rhythmatician.lodiffusion.voxy.Level.L2), map.get(com.rhythmatician.lodiffusion.voxy.Level.L1), map.get(com.rhythmatician.lodiffusion.voxy.Level.L0)));
            }
            if (root.has("roles")) {
                var r = root.getAsJsonObject("roles");
                builder.roles(new com.rhythmatician.lodiffusion.oracle.OracleContract.ClaimDependencyRoles(r.get("claimRole").getAsString(), r.get("dependencyRole").getAsString(), r.get("rationale").getAsString()));
            }
            if (root.has("benchmarkPolicy")) {
                var bp = root.getAsJsonObject("benchmarkPolicy");
                builder.benchmarkPolicy(new com.rhythmatician.lodiffusion.oracle.OracleContract.BenchmarkPolicy(bp.get("warmupIterations").getAsInt(), bp.get("measurementIterations").getAsInt(), bp.get("repetitionPolicy").getAsString()));
            }
            var reconstructed = builder.build();
            return reconstructed.protocolSha256();
        } catch (Exception e) { throw new IllegalStateException("reconstruct failed: " + e.getMessage(), e); }
    }
}
