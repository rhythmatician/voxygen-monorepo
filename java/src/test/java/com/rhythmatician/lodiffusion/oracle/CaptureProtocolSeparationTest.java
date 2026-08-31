package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import com.rhythmatician.lodiffusion.oracle.capture.OracleFixtureWriter;
import com.rhythmatician.voxygen.semantic.Level;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import com.rhythmatician.voxygen.semantic.SectionPos;

/**
 * Regression for final provenance separation before merging #241:
 * immutable capture protocol (captureProtocolSha256) must NOT include mutable
 * Worldgen Partition/policy state (per-Level dispositions, claim/dependency roles,
 * benchmark warmup). Changing those via #220 must NOT invalidate existing real fixture
 * a5fea400. Changing Voxy SHA / generationOrder / halo MUST. Evidence integrity
 * binding (captureProtocol + contentSha + actualCaptureStage + evidenceKind) must fail
 * on tampering, and REAL_CAPTURE must be unforgeable via public OracleFixture ctor.
 */
class CaptureProtocolSeparationTest {

    private static Path realFixturePath() {
        OracleContract c = EndChorusTracerContract.contract();
        Path[] candidates = {
                Paths.get("oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("java/oracle-fixtures", c.provenanceId() + ".json"),
                Paths.get("../oracle-fixtures", c.provenanceId() + ".json"),
        };
        for (Path p : candidates) if (Files.exists(p)) return p;
        return Paths.get("java/oracle-fixtures", c.provenanceId() + ".json");
    }

    @Test
    void captureProtocolExcludesMutablePartitionPolicy() {
        OracleContract base = EndChorusTracerContract.contract();
        // Change L4 disposition UNRESOLVED -> OMIT must NOT change captureProtocolSha
        var pd = base.perLevelDecisions();
        var newL4 = new OracleContract.PartitionDecision("OMIT", pd.l4().candidates(), "test OMIT");
        var newPd = new OracleContract.PerLevelPartitionDecisions(newL4, pd.l3(), pd.l2(), pd.l1(), pd.l0());
        var cOmit = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(newPd).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder()).build();
        // Capture SHA must be unchanged
        assertEquals(base.captureProtocolSha256(), cOmit.captureProtocolSha256(), "captureProtocolSha must NOT change when L4 disposition UNRESOLVED->OMIT");
        // Full protocol SHA must change (mutable state is part of full)
        assertNotEquals(base.protocolSha256(), cOmit.protocolSha256(), "full protocolSha SHOULD change with disposition (mutable)");
        // Also roles and benchmarkPolicy must not affect capture
        var cRoles = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(base.perLevelDecisions()).roles(new OracleContract.ClaimDependencyRoles("changed claim", "changed dep", "changed")).benchmarkPolicy(new OracleContract.BenchmarkPolicy(99, 99, "changed")).generationOrder(base.generationOrder()).build();
        assertEquals(base.captureProtocolSha256(), cRoles.captureProtocolSha256(), "captureProtocolSha must NOT change with roles/benchmark");
        assertNotEquals(base.protocolSha256(), cRoles.protocolSha256(), "full protocolSha should change with roles/benchmark");
    }

    @Test
    void captureProtocolChangesWithImmutableFields() {
        OracleContract base = EndChorusTracerContract.contract();
        // Voxy SHA change must change captureProtocolSha
        var cVoxy = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256("0000000000000000000000000000000000000000000000000000000000000000")
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder()).build();
        assertNotEquals(base.captureProtocolSha256(), cVoxy.captureProtocolSha256(), "voxyArtifactSha change must change captureProtocolSha");
        // generationOrder is validated to be squared distance — Morton must be rejected
        assertThrows(IllegalArgumentException.class, () -> OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(base.halo())
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder("Morton sorted by distance to center of L4 rect, then server tick order").build(),
                "Morton generationOrder must be rejected as not squared distance");
        // halo change must change captureProtocolSha (use valid but different halo evidence, not numeric 8)
        var cHalo = OracleContract.builder()
                .schemaVersion(base.schemaVersion()).responsibilityId(base.responsibilityId()).dimension(base.dimension()).frozenWorldgenProfileId(base.frozenWorldgenProfileId())
                .minecraftVersion(base.minecraftVersion()).minecraftSourceRevision(base.minecraftSourceRevision()).minecraftJarSha256(base.minecraftJarSha256())
                .voxyVersion(base.voxyVersion()).voxyCommit(base.voxyCommit()).voxyArtifactSha256(base.voxyArtifactSha256())
                .canonicalBlockRegistryVersion(base.canonicalBlockRegistryVersion()).canonicalBlockRegistrySha256(base.canonicalBlockRegistrySha256())
                .canonicalBiomeRegistryVersion(base.canonicalBiomeRegistryVersion()).canonicalBiomeRegistrySha256(base.canonicalBiomeRegistrySha256())
                .inspectedMinecraftReferences(base.inspectedMinecraftReferences()).inspectedVoxyReferences(base.inspectedVoxyReferences())
                .seed(base.seed()).region(base.region()).blockRegion(base.blockRegionOrDerived()).halo(new OracleContract.HaloSpec(8, "Chorus max horizontal spread 8 blocks - TWEAKED", "ChorusFlowerBlock.java:178-210 growTreeRecursive maxHorizontalSpread=8 - tweaked", 1, "FEATURES reads CARVERS@1 and STRUCTURE_STARTS@8, writes 1 chunk; need +1 chunk halo to make placement well-defined at boundary - tweaked", "ChunkPyramid.java:18 ChunkStatus.java:28", 1, "Voxy 2x2x2 Mipper group crossing WorldSection boundary needs 1 block halo - tweaked", "Mipper.java:9-55 + WorldSection.java YZX", 25))
                .authoritativeGenerationStage(base.authoritativeGenerationStage()).fixtureFormatVersion(base.fixtureFormatVersion()).provenanceId(base.provenanceId())
                .perLevelDecisions(base.perLevelDecisions()).roles(base.roles()).benchmarkPolicy(base.benchmarkPolicy()).generationOrder(base.generationOrder()).build();
        assertNotEquals(base.captureProtocolSha256(), cHalo.captureProtocolSha256(), "halo evidence change must change captureProtocolSha");
    }

    @Test
    void existingFixtureRemainsValidAfterDispositionChange() throws Exception {
        // Real fixture a5fea400 must remain valid even though we could change L4 to OMIT — because captureProtocol excludes it
        Path p = realFixturePath();
        OracleFixture f = OracleFixtureWriter.read(p);
        assertEquals("a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33", f.contentSha256());
        // Capture protocol of current tracer must equal fixture's captureProtocol
        assertEquals(EndChorusTracerContract.contract().captureProtocolSha256(), f.captureProtocolSha256(), "captureProtocol must match after disposition-agnostic split");
        // Content SHA still validates
        assertEquals(OracleFixture.computeContentSha256(f.volumesView()), f.contentSha256());
    }

    @Test
    void tamperingActualCaptureStageFailsEvidenceIntegrity(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // Change FULL -> FEATURES
        assertEquals("FULL", root.get("actualCaptureStage").getAsString());
        root.addProperty("actualCaptureStage", "FEATURES");
        // Keep old evidenceIntegrity — must fail
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("tampered-stage.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("evidence") || ex.getMessage().toLowerCase().contains("actualcapturestage"),
                "tampered actualCaptureStage must fail evidence integrity, was: " + ex.getMessage());
    }

    @Test
    void tamperingEvidenceKindFails(@TempDir Path tmp) throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        root.addProperty("evidenceKind", "SYNTHETIC_TEST");
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("tampered-kind.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(IllegalStateException.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidenceintegrity") || ex.getMessage().toLowerCase().contains("evidencekind") || ex.getMessage().toLowerCase().contains("evidence"),
                "tampered evidenceKind must fail evidence integrity, was: " + ex.getMessage());
    }

    @Test
    void tamperingEvidenceKindSyntheticToRealFails(@TempDir Path tmp) throws Exception {
        // Create synthetic and try to flip to REAL_CAPTURE — must fail evidence integrity or capture
        OracleContract c = EndChorusTracerContract.contract();
        var synth = com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c);
        // Write synthetic to temp via writer? Use direct JSON manipulation on real fixture but change to synthetic content?
        // Instead test that flipping synthetic JSON to REAL_CAPTURE fails
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        // Keep contentSha of real but change evidenceKind to SYNTHETIC_TEST then back to REAL? Actually test SYNTHETIC->REAL
        // Simulate synthetic file: change contentSha to synthetic's and try to claim REAL
        root.addProperty("contentSha256", synth.contentSha256());
        // Need to also update volumes to match synthetic? For this tamper test, we just flip kind without fixing integrity — should fail
        root.addProperty("evidenceKind", "REAL_CAPTURE");
        // Keep old integrity — must fail
        String corrupted = new com.google.gson.GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create().toJson(root);
        Path copy = tmp.resolve("synth-to-real.json");
        Files.writeString(copy, corrupted);
        var ex = assertThrows(Exception.class, () -> OracleFixtureWriter.read(copy));
        assertTrue(ex.getMessage().toLowerCase().contains("evidence") || ex.getMessage().toLowerCase().contains("content") || ex.getMessage().toLowerCase().contains("integrity"),
                "SYNTHETIC->REAL flip must fail, was: " + ex.getMessage());
    }

    @Test
    void arbitraryPublicFixtureCannotForgeRealCapture() {
        OracleContract c = EndChorusTracerContract.contract();
        var vols = com.rhythmatician.lodiffusion.oracle.synthetic.SyntheticEndChorusFixtureFactory.generateSyntheticTracerFixture(c).volumesView();
        String sha = OracleFixture.computeContentSha256(vols);
        // Public constructors must not allow REAL_CAPTURE
        assertThrows(IllegalArgumentException.class, () -> new OracleFixture(c, vols, sha, System.currentTimeMillis(), "FULL", OracleFixture.EvidenceKind.REAL_CAPTURE, c.captureProtocolSha256()),
                "public OracleFixture(..., REAL_CAPTURE) must be rejected as unforgeable");
        assertThrows(IllegalArgumentException.class, () -> new OracleFixture(c, vols, sha, System.currentTimeMillis(), "FULL", OracleFixture.EvidenceKind.REAL_CAPTURE),
                "public 6-arg REAL_CAPTURE must be rejected");
        // Even if someone tries to use synthetic volumes with REAL_CAPTURE via validated factory but with synthetic content, the contentSha must still match but evidenceKind binding will be checked by verifier
        // The only way to get REAL_CAPTURE is via validated factory with real content — but synthetic content with REAL_CAPTURE via factory should still be possible? The unforgeability is about public ctor, not factory
        // Verify that a public SYNTHETIC_TEST fixture is not accepted as parity
        var synth = new OracleFixture(c, vols, sha, System.currentTimeMillis(), "FULL", OracleFixture.EvidenceKind.SYNTHETIC_TEST, c.captureProtocolSha256());
        assertEquals(OracleFixture.EvidenceKind.SYNTHETIC_TEST, synth.evidenceKind());
        // CandidateVerifier must reject it
        var per = c.blockRegionOrDerived().perLevelWorldSectionOrigin(Level.L0.value());
        var origin = new com.rhythmatician.voxygen.semantic.SectionPos(per.wsX() * Level.L0.regionSections(), per.wsY() * Level.L0.regionSections(), per.wsZ() * Level.L0.regionSections());
        var vol = synth.volume(Level.L0);
        assertThrows(IllegalArgumentException.class, () -> com.rhythmatician.lodiffusion.oracle.CandidateVerifier.verify(Level.L0, origin, vol, synth),
                "SYNTHETIC_TEST must be rejected by parity verifier");
    }

    @Test
    void evidenceIntegrityBindingRequiresAllFour() throws Exception {
        Path real = realFixturePath();
        String json = Files.readString(real);
        com.google.gson.JsonObject root = com.google.gson.JsonParser.parseString(json).getAsJsonObject();
        String cap = root.get("captureProtocolSha256").getAsString();
        String content = root.get("contentSha256").getAsString();
        String stage = root.get("actualCaptureStage").getAsString();
        String kind = root.get("evidenceKind").getAsString();
        String integrity = root.get("evidenceIntegritySha256").getAsString();
        // Recompute via OracleFixture helper must match stored
        String recomputed = OracleFixture.computeEvidenceIntegritySha256(cap, content, stage, OracleFixture.EvidenceKind.valueOf(kind));
        assertEquals(integrity.toLowerCase(), recomputed.toLowerCase(), "evidenceIntegrity must be SHA(captureProtocol|content|stage|kind)");
        // Changing any of the four changes integrity
        String diffCap = "0" + cap.substring(1);
        String diffIntegrity = OracleFixture.computeEvidenceIntegritySha256(diffCap, content, stage, OracleFixture.EvidenceKind.valueOf(kind));
        assertNotEquals(integrity, diffIntegrity, "captureProtocol change must change integrity");
        String diffContent = "0" + content.substring(1);
        assertNotEquals(integrity, OracleFixture.computeEvidenceIntegritySha256(cap, diffContent, stage, OracleFixture.EvidenceKind.valueOf(kind)));
        assertNotEquals(integrity, OracleFixture.computeEvidenceIntegritySha256(cap, content, "FEATURES", OracleFixture.EvidenceKind.valueOf(kind)));
        assertNotEquals(integrity, OracleFixture.computeEvidenceIntegritySha256(cap, content, stage, OracleFixture.EvidenceKind.SYNTHETIC_TEST));
    }
}
