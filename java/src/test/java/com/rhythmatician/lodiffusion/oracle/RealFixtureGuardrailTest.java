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
        assertTrue(ex.getMessage().contains("contentSha mismatch") || ex.getMessage().contains("contentSha"),
                "corrupt SHA must be detected, was: " + ex.getMessage());
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

        // Parity-without-provenance: a test that asserts synthetic==real must fail at ALL Levels.
        // Here we prove verifier distinguishes them: real volume vs synthetic volume at same Level/origin must mismatch.
        for (Level lvl : Level.values()) {
            var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(lvl.value());
            SectionPos origin = new SectionPos(per.wsX() * lvl.regionSections(), per.wsY() * lvl.regionSections(), per.wsZ() * lvl.regionSections());
            VoxelVolume realVol = real.volume(lvl);
            var r = CandidateVerifier.verify(lvl, origin, realVol, synthetic);
            assertTrue(r.failed(),
                    "Real vs synthetic must fail at " + lvl + " — synthetic not parity, was: " + r.detail()
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
