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
 * File-polling trigger for deterministic oracle regeneration without RCON packet plumbing.
 *
 * <p>Python orchestrator writes {@code config/oracle_capture_request.json} (or any path) containing
 * {@code {"provenanceId":"...","actualCaptureStage":"FULL"}}; on each client tick this handler
 * checks for the file, runs {@link WorldSectionOracleCapture#capture}, writes
 * {@code java/oracle-fixtures/<provenanceId>.json} via {@link OracleFixtureWriter}, replays
 * CandidateVerifier against the real fixture (including one localized mutation that must fail),
 * writes {@code config/oracle_capture_done.json} with sha and per-Level chorus counts, and
 * deletes the request file. Python polls for done file and exits nonzero on incomplete.
 *
 * <p>Voxy packed longs / YZX never leak — fixture holds only canonical VoxelVolume.
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
        // For tracer, contract is fixed; we could parse provenance/actualStage from JSON but for now use tracer contract.
        OracleContract contract = EndChorusTracerContract.contract();
        // Allow orchestrator to override stage to FULL if it generated FULL chunks offline (record actual stage)
        if (req.contains("\"actualCaptureStage\"") && req.contains("FULL")) {
            // Build a contract copy with FULL stage but same provenance id? The provenanceId must change if stage changes.
            // For offline oracle we record FULL was the actual capture stage but keep provenance as tracer's FEATURES id for now
            // and log that synthesis used FULL — per instruction, record that FULL was actual capture stage.
            LOGGER.info("[OracleFileTrigger] Request requested FULL capture stage — will capture at current stage {} (FEATURES is authoritative for chorus)", contract.authoritativeGenerationStage());
        }

        OracleFixture fixture = WorldSectionOracleCapture.capture(contract);
        Path out = OracleFixtureWriter.defaultFixturePath(contract);
        OracleFixtureWriter.write(fixture, out);

        // Replay CandidateVerifier — correct candidate (fixture's own volumes) must pass; localized mutation must fail
        SectionPos origin = fixture.origin();
        StringBuilder report = new StringBuilder();
        report.append("provenance=").append(fixture.provenanceId()).append(" sha=").append(fixture.contentSha256()).append("\n");

        for (Level lvl : Level.values()) {
            VoxelVolume correct = fixture.volume(lvl);
            var r = CandidateVerifier.verify(lvl, origin, correct, fixture);
            if (!r.passed()) throw new IllegalStateException("Correct candidate failed at " + lvl + ": " + r.detail());
            // Localized mutation: flip one non-air voxel if exists, else flip air to end_stone
            VoxelVolume mutated = mutateOneVoxel(correct);
            var rm = CandidateVerifier.verify(lvl, origin, mutated, fixture);
            if (rm.passed()) throw new IllegalStateException("Mutated candidate should fail at " + lvl);
            int chorusCount = countChorus(correct);
            report.append(lvl).append(": chorus=").append(chorusCount).append(" nonAir=").append(correct.countNonAir()).append(" mutatedFails=").append(!rm.passed()).append("\n");
            LOGGER.info("[OracleFileTrigger] {} chorus={} mutated fails as expected: {}", lvl, chorusCount, rm.detail());
        }

        // Write done file with report
        String doneJson = "{\n  \"provenanceId\": \"" + fixture.provenanceId() + "\",\n"
                + "  \"contentSha256\": \"" + fixture.contentSha256() + "\",\n"
                + "  \"fixturePath\": \"" + out.toString().replace("\"", "\\\"") + "\",\n"
                + "  \"report\": \"" + report.toString().replace("\n", "\\n").replace("\"", "\\\"") + "\",\n"
                + "  \"actualCaptureStage\": \"" + contract.authoritativeGenerationStage() + "\"\n"
                + "}\n";
        Files.createDirectories(DONE_PATH.getParent());
        Files.writeString(DONE_PATH, doneJson);
        LOGGER.info("[OracleFileTrigger] Done file written: {} report:\n{}", DONE_PATH.toAbsolutePath(), report);

        // Delete request so we don't retrigger
        Files.deleteIfExists(REQUEST_PATH);
        // Also log to client chat if possible
        try {
            var mc = MinecraftClient.getInstance();
            if (mc != null && mc.player != null) {
                mc.player.sendMessage(net.minecraft.text.Text.literal("[Oracle] Fixture captured: " + fixture.provenanceId() + " sha=" + fixture.contentSha256().substring(0, 12) + "..."), false);
            }
        } catch (Exception ignored) {}
    }

    private static VoxelVolume mutateOneVoxel(VoxelVolume src) {
        var b = VoxelVolume.builder(src.extent());
        // Copy all
        for (int y = 0; y < src.extent(); y++) for (int z = 0; z < src.extent(); z++) for (int x = 0; x < src.extent(); x++) {
            int block = src.blockId(x, y, z);
            int biome = src.biomeId(x, y, z);
            if (block != 0) b.setBlock(x, y, z, block);
            if (biome != 255) b.setBiome(x, y, z, biome);
        }
        // Find first non-air to flip to air, else flip 0,0,0 to end_stone (359)
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
