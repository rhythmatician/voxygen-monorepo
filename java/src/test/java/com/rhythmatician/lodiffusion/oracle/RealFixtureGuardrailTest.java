package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter;
import com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CI guardrail: proves that missing/corrupt contract/fixture and
 * parity-without-provenance fail the ACTUAL CI path (gradlew test),
 * not just manual validator invocation.
 *
 * <p>These tests run in the ordinary {@code test} suite (no Voxy jar),
 * so they are executed by the {@code java} lane in Factory CI. If the
 * real fixture is deleted or corrupted, CI must fail — not silently
 * fall back to synthetic.
 */
class RealFixtureGuardrailTest {

    private static Path realFixturePath() {
        OracleContract c = EndChorusTracerContract.contract();
        Path[] candidates = {
                Paths.get("oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("java/oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("../oracle-fixtures", c.provenanceId() + ".json"),
        };
        for (Path p : candidates) if (Files.exists(p)) return p;
        Path def = OracleFixtureWriter.defaultFixturePath(c);
        if (Files.exists(def)) return def;
        return candidates[0]; // for error message
    }

    @Test
    void realFixtureMustExistForCi() {
        Path p = realFixturePath();
        assertTrue(Files.exists(p),
                "Real fixture must exist at " + p.toAbsolutePath()
                        + " — CI must fail if deleted. Synthetic is NOT a substitute for oracle evidence.");
        assertTrue(Files.isRegularFile(p));
        assertTrue(p.toFile().length() > 1_000_000, "Real fixture should be ~3.8M, was " + p.toFile().length());
    }

    @Test
    void realFixtureLoadsAndShaValidates() throws Exception {
        Path p = realFixturePath();
        OracleFixture f = OracleFixtureWriter.read(p);
        assertNotNull(f);
        assertEquals("a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33", f.contentSha256());
        assertEquals(EndChorusTracerContract.contract().provenanceId(), f.provenanceId());
        // Content SHA must match recomputed — proves not synthetic
        assertEquals(OracleFixture.computeContentSha256(f.volumesView()), f.contentSha256());
        // Immutable capture protocol + evidence integrity binding
        assertEquals(EndChorusTracerContract.contract().captureProtocolSha256(), f.captureProtocolSha256(), "captureProtocolSha must match current tracer contract (immutable)");
        assertEquals(EndChorusTracerContract.contract().protocolSha256(), f.protocolSha256(), "full protocolSha (legacy) must also match");
        assertEquals(OracleFixture.computeEvidenceIntegritySha256(f.captureProtocolSha256(), f.contentSha256(), f.actualCaptureStage(), f.evidenceKind()), f.evidenceIntegritySha256(), "evidenceIntegrity must bind captureProtocol+content+stage+kind");
        assertEquals(OracleContract.EXPECTED_GENERATION_ORDER, f.contract().generationOrder(), "generationOrder must be squared distance -> X -> Z");
        assertEquals(OracleFixture.EvidenceKind.REAL_CAPTURE, f.evidenceKind(), "real fixture must be REAL_CAPTURE");
        assertEquals("FULL", f.actualCaptureStage(), "actualCaptureStage must be FULL for this fixture");
    }

    @Test
    void corruptShaFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        OracleFixture f = OracleFixtureWriter.read(real);
        // Write a temp copy and corrupt its SHA
        Path copy = tmp.resolve("corrupt-sha.json");
        String json = Files.readString(real);
        String badSha = "0000000000000000000000000000000000000000000000000000000000000000";
        String corrupted = json.replace(f.contentSha256(), badSha);
        assertNotEquals(json, corrupted, "must have replaced SHA");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("contentsha") || ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("evidence"),
                "corrupt SHA must be detected via contentSha or evidenceIntegrity, was: " + ex.getMessage());
    }

    @Test
    void corruptProvenanceFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        String badProv = json.replace(EndChorusTracerContract.contract().provenanceId(), "end_chorus__s999__bogus");
        Path copy = tmp.resolve("corrupt-prov.json");
        Files.writeString(copy, badProv);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("provenance"),
                "corrupt provenance must be detected, was: " + ex.getMessage());
    }

    @Test
    void corruptProtocolShaFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        // Corrupt captureProtocolSha256 (immutable) — also corrupts legacy protocolSha for backwards compat
        String goodSha = EndChorusTracerContract.contract().captureProtocolSha256();
        String badSha = "0" + goodSha.substring(1);
        assertNotEquals(goodSha, badSha);
        String corrupted = json.replace(goodSha, badSha);
        Path copy = tmp.resolve("corrupt-protocol.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("captureprotocol") || ex.getMessage().toLowerCase().contains("protocol"),
                "corrupt captureProtocolSha must be detected, was: " + ex.getMessage());
    }

    @Test
    void corruptEvidenceIntegrityFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String good = root.get("evidenceIntegritySha256").getAsString();
        String bad = "0" + good.substring(1);
        root.addProperty("evidenceIntegritySha256", bad);
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("corrupt-integrity.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("evidence"),
                "corrupt evidenceIntegrity must be detected, was: " + ex.getMessage());
    }

    @Test
    void corruptActualCaptureStageFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("actualCaptureStage", "FEATURES");
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("corrupt-stage.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("actualcapturestage") || ex.getMessage().toLowerCase().contains("evidence"),
                "corrupt actualCaptureStage must fail evidence integrity, was: " + ex.getMessage());
    }

    @Test
    void corruptEvidenceKindFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("evidenceKind", "SYNTHETIC_TEST");
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("corrupt-kind.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("evidencekind") || ex.getMessage().toLowerCase().contains("evidence"),
                "corrupt evidenceKind must fail evidence integrity, was: " + ex.getMessage());
    }

    @Test
    void corruptGenerationOrderFailsFast(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        String badOrder = "Morton sorted by distance to center of L4 rect, then server tick order";
        // Robustly corrupt via JSON object (avoids GSON html-escaping of '>' as \\u003e)
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        assertTrue(root.has("generationOrder"), "fixture must have generationOrder");
        root.addProperty("generationOrder", badOrder);
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        assertNotEquals(json, corrupted, "must have replaced generationOrder");
        Path copy = tmp.resolve("corrupt-order.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("generationorder") || ex.getMessage().toLowerCase().contains("generation"),
                "corrupt generationOrder must be detected, was: " + ex.getMessage());
    }

    @Test
    void tamperedVoxyArtifactShaFailsViaProtocolSha(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        OracleContract c = EndChorusTracerContract.contract();
        String json = Files.readString(real);
        // Change voxyArtifactSha256 but keep old captureProtocolSha — must fail via captureProtocolSha mismatch (tamper detection, immutable)
        String corrupted = json.replace(c.voxyArtifactSha256(), "0000000000000000000000000000000000000000000000000000000000000000");
        assertNotEquals(json, corrupted);
        Path copy = tmp.resolve("tampered-voxy.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("captureprotocol") || ex.getMessage().toLowerCase().contains("protocol"),
                "tampered voxyArtifactSha must be detected via captureProtocolSha, was: " + ex.getMessage());
    }

    @Test
    void missingFileFailsFast() {
        Path missing = Paths.get("oracle-fixtures", "does_not_exist__s0__b0_0_0_e32__fmtv3.json");
        assertThrows(Exception.class, () -> OracleFixtureWriter.read(missing));
    }

    @Test
    void syntheticMustNotBeMistakenForReal() throws Exception {
        Path realPath = realFixturePath();
        OracleFixture real = OracleFixtureWriter.read(realPath);
        OracleContract c = EndChorusTracerContract.contract();
        OracleFixture synthetic = SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);

        // Provenance matches (same contract) but content SHA must differ — synthetic is not oracle evidence
        assertEquals(real.provenanceId(), synthetic.provenanceId(), "provenance same contract");
        assertNotEquals(real.contentSha256(), synthetic.contentSha256(),
                "synthetic SHA must differ from real double-pristine SHA — otherwise synthetic could masquerade as real");
        assertNotEquals(real.contentSha256(), "0000000000000000000000000000000000000000000000000000000000000000");

        // Typed evidence: real is REAL_CAPTURE, synthetic is SYNTHETIC_TEST — same provenanceId and captureProtocolSha but different evidenceKind/content
        assertEquals(OracleFixture.EvidenceKind.REAL_CAPTURE, real.evidenceKind(), "real fixture must be REAL_CAPTURE");
        assertEquals(OracleFixture.EvidenceKind.SYNTHETIC_TEST, synthetic.evidenceKind(), "synthetic must be SYNTHETIC_TEST");
        assertEquals(real.captureProtocolSha256(), synthetic.captureProtocolSha256(), "same contract => same captureProtocolSha, but evidenceKind/content distinguishes");
        assertNotEquals(real.evidenceIntegritySha256(), synthetic.evidenceIntegritySha256(), "evidenceIntegrity must differ (content/kind)");
        // Parity-without-provenance: a test that asserts synthetic==real must fail at ALL Levels.
        // Here we prove verifier distinguishes them in two ways:
        // 1) Strict parity verification must reject synthetic evidenceKind (typed guard)
        // 2) Even leniently, the voxel content mismatches at every Level
        for (Level lvl : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(lvl.value());
            SectionPos origin = new SectionPos(per.wsX() * lvl.regionSections(), per.wsY() * lvl.regionSections(), per.wsZ() * lvl.regionSections());
            VoxelVolume realVol = real.volume(lvl);
            // Strict parity must throw for SYNTHETIC_TEST — synthetic cannot satisfy ADR 0015
            var ex = assertThrows(IllegalArgumentException.class, () -> CandidateVerifier.verify(lvl, origin, realVol, synthetic),
                    "Real vs synthetic must be rejected at " + lvl + " due to SYNTHETIC_TEST evidenceKind");
            assertTrue(ex.getMessage().contains("REAL_CAPTURE") || ex.getMessage().contains("SYNTHETIC_TEST"), "exception must mention evidenceKind, was: " + ex.getMessage());
            // Leniently, content still mismatches at every Level (proves deterministic hash != vanilla)
            var r = CandidateVerifier.verifyLenient(lvl, origin, realVol, synthetic);
            assertTrue(r.failed(),
                    "Real vs synthetic must fail even leniently at " + lvl + " — synthetic not parity, was: " + r.detail()
                            + " (real chorus " + countChorus(realVol) + " vs synthetic " + countChorus(synthetic.volume(lvl)) + ")");
        }
    }

    @Test
    void realFixtureContractValidates() throws Exception {
        OracleFixture f = OracleFixtureWriter.read(realFixturePath());
        assertDoesNotThrow(f.contract()::validate);
        assertEquals("minecraft:the_end", f.contract().dimension());
        assertEquals(25, f.contract().halo().combinedHaloBlocks());
        assertEquals("FEATURES", f.contract().authoritativeGenerationStage());
    }

    private static int countChorus(VoxelVolume v) {
        int c = 0;
        for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
            int id = v.blockId(x, y, z);
            if (id == 196 || id == 197) c++;
        }
        return c;
    }
}
