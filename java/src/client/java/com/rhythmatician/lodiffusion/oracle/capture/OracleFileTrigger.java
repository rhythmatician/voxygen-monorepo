package com.rhythmatician.lodiffusion.oracle.capture;

import com.rhythmatician.lodiffusion.oracle.CandidateVerifier;
import com.rhythmatician.lodiffusion.oracle.EndChorusTracerContract;
import com.rhythmatician.lodiffusion.oracle.OracleContract;
import com.rhythmatician.lodiffusion.oracle.OracleFixture;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.minecraft.client.MinecraftClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-polling trigger for deterministic oracle regeneration.
 * Expects {"provenanceId":"...","actualCaptureStage":"FULL" or "FEATURES", "blockRegion":{...}}.
 * Derives per-Level WorldSections independently and enforces L0 chorus>0 only.
 */
public final class OracleFileTrigger {
    private static final Logger LOGGER = LoggerFactory.getLogger(OracleFileTrigger.class);
    static final Path REQUEST_PATH = Path.of("config", "oracle_capture_request.json");
    static final Path DONE_PATH = Path.of("config", "oracle_capture_done.json");

    private static boolean registered;

    private OracleFileTrigger() {}

    public static void register() {
        if (registered) return;
        registered = true;
        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                if (Files.exists(REQUEST_PATH)) {
                    handleRequest();
                }
            } catch (Exception e) {
                LOGGER.error("[OracleFileTrigger] Failed to handle capture request", e);
                writeDoneError(e.toString());
            }
        });
        LOGGER.info("[OracleFileTrigger] Registered; watching {}", REQUEST_PATH.toAbsolutePath());
    }

    private static void handleRequest() throws Exception {
        String req = Files.readString(REQUEST_PATH);
        LOGGER.info("[OracleFileTrigger] Capture request: {}", req);
        OracleContract contract = EndChorusTracerContract.contract();
        String actualStage = "FULL";
        try {
            com.google.gson.JsonObject jo = new com.google.gson.Gson().fromJson(req, com.google.gson.JsonObject.class);
            if (jo.has("actualCaptureStage")) actualStage = jo.get("actualCaptureStage").getAsString();
            else if (jo.has("actualStage")) actualStage = jo.get("actualStage").getAsString();
        } catch (Exception ignored) {}
        LOGGER.info("[OracleFileTrigger] actualCaptureStage={} authoritative={}", actualStage, contract.authoritativeGenerationStage());
        WorldSectionOracleCapture.setActualCaptureStage(actualStage);
        OracleFixture fixture;
        try {
            fixture = WorldSectionOracleCapture.capture(contract);
        } finally {
            WorldSectionOracleCapture.clearActualCaptureStage();
        }
        Path out = OracleFixtureWriter.defaultFixturePath(contract);
        OracleFixtureWriter.write(fixture, out);

        SectionPos origin = fixture.origin();
        var blockRegion = contract.blockRegionOrDerived();
        StringBuilder report = new StringBuilder();
        report.append("provenance=").append(fixture.provenanceId()).append(" sha=").append(fixture.contentSha256()).append(" actual=").append(fixture.actualCaptureStage()).append(" authoritative=").append(contract.authoritativeGenerationStage()).append("\n");
        report.append("blockRegion=").append(blockRegion).append(" chunkRect=").append(java.util.Arrays.toString(blockRegion.chunkRectWithHalo(contract.halo().combinedHaloBlocks()))).append("\n");
        for (Level lvl : Level.values()) {
            var per = blockRegion.perLevelWorldSectionOrigin(lvl.value());
            report.append(lvl).append(" WS[").append(per.wsX()).append(",").append(per.wsY()).append(",").append(per.wsZ()).append("] blockSize=").append(per.blockSize()).append(" ");
        }
        report.append("\n");
        boolean hasChorusAtL0 = false;
        for (Level lvl : Level.values()) {
            VoxelVolume correct = fixture.volume(lvl);
            var r = CandidateVerifier.verify(lvl, origin, correct, fixture);
            if (!r.passed()) throw new IllegalStateException("Correct candidate failed at " + lvl + ": " + r.detail());
            VoxelVolume mutated = mutateOneVoxel(correct);
            var rm = CandidateVerifier.verify(lvl, origin, mutated, fixture);
            if (rm.passed()) throw new IllegalStateException("Mutated candidate should fail at " + lvl);
            int chorusCount = countChorus(correct);
            if (lvl == Level.L0 && chorusCount > 0) hasChorusAtL0 = true;
            var per = blockRegion.perLevelWorldSectionOrigin(lvl.value());
            report.append(lvl).append(": chorus=").append(chorusCount).append(" nonAir=").append(correct.countNonAir()).append(" ws=[").append(per.wsX()).append(",").append(per.wsY()).append(",").append(per.wsZ()).append("] mutatedFails=").append(!rm.passed()).append("\n");
            LOGGER.info("[OracleFileTrigger] {} chorus={} mutated fails as expected: {}", lvl, chorusCount, rm.detail());
        }
        if (!hasChorusAtL0) {
            String msg = "L0 has zero chorus for blockRegion " + blockRegion + " — anchor does not contain real chorus_plant/flower at seed 42; re-pin via outer-island chunk inspection";
            LOGGER.warn("[OracleFileTrigger] {}", msg);
            report.append("ERROR: ").append(msg).append("\n");
            writeDoneError(msg + " | report:\n" + report);
            Files.deleteIfExists(REQUEST_PATH);
            return;
        }
        String doneJson = "{\n  \"provenanceId\": \"" + fixture.provenanceId() + "\",\n"
                + "  \"contentSha256\": \"" + fixture.contentSha256() + "\",\n"
                + "  \"fixturePath\": \"" + out.toString().replace("\"", "\\\"") + "\",\n"
                + "  \"actualCaptureStage\": \"" + fixture.actualCaptureStage() + "\",\n"
                + "  \"authoritativeGenerationStage\": \"" + contract.authoritativeGenerationStage() + "\",\n"
                + "  \"report\": \"" + report.toString().replace("\n", "\\n").replace("\"", "\\\"") + "\"\n"
                + "}\n";
        Files.createDirectories(DONE_PATH.getParent());
        Files.writeString(DONE_PATH, doneJson);
        LOGGER.info("[OracleFileTrigger] Done file written: {} report:\n{}", DONE_PATH.toAbsolutePath(), report);
        Files.deleteIfExists(REQUEST_PATH);
        try {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("[Oracle] Fixture captured: " + fixture.provenanceId() + " sha=" + fixture.contentSha256().substring(0, 12) + " actual=" + fixture.actualCaptureStage()), false);
            }
        } catch (Exception ignored) {}
    }

    private static VoxelVolume mutateOneVoxel(VoxelVolume src) {
        var b = VoxelVolume.builder(src.extent());
        for (int y = 0; y < src.extent(); y++) for (int z = 0; z < src.extent(); z++) for (int x = 0; x < src.extent(); x++) {
            int block = src.blockId(x, y, z);
            int biome = src.biomeId(x, y, z);
            if (block != 0) b.setBlock(x, y, z, block);
            if (biome != 255) b.setBiome(x, y, z, biome);
        }
        for (int y = 0; y < src.extent(); y++) for (int z = 0; z < src.extent(); z++) for (int x = 0; x < src.extent(); x++) {
            if (src.blockId(x, y, z) != 0) {
                b.setBlock(x, y, z, 0);
                return b.build();
            }
        }
        b.setBlock(0, 0, 0, 359);
        return b.build();
    }

    private static int countChorus(VoxelVolume v) {
        int c = 0;
        for (int y = 0; y < v.extent(); y++) for (int z = 0; z < v.extent(); z++) for (int x = 0; x < v.extent(); x++) {
            int id = v.blockId(x, y, z);
            if (id == 196 || id == 197) c++;
        }
        return c;
    }

    private static void writeDoneError(String error) {
        try {
            Files.createDirectories(DONE_PATH.getParent());
            String json = "{\n  \"error\": \"" + error.replace("\"", "\\\"").replace("\n", "\\n") + "\"\n}\n";
            Files.writeString(DONE_PATH, json);
        } catch (IOException ignored) {}
    }
}
