package com.rhythmatician.lodiffusion.oracle;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.file.*;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * CI policy guard for ADR 0015 § "Tests named or described as parity, round-trip,
 * vanilla convergence, or post-ingest agreement require an independent oracle path."
 *
 * <p>Any test/class whose name claims {@code Parity}, {@code RoundTrip},
 * {@code VanillaConvergence} (or equivalent) must carry machine-checkable
 * independent-oracle provenance or live in the appropriate oracle-fixture
 * boundary ({@code voxyIntegrationTest} with real Voxy + {@code OracleFixture}).
 *
 * <p>This test scans the repository's test sources at runtime and fails the
 * ordinary {@code gradlew test} lane if a violating file exists — so the
 * violation is caught by the actual CI path, not just manual validator.
 *
 * <p>Provenance is satisfied by any of:
 * <ul>
 *   <li>path contains {@code voxyIntegrationTest} (live Voxy boundary)
 *   <li>content references {@code OracleFixture}, {@code VanillaVoxyOracle},
 *       {@code EndChorusTracerContract}, {@code OracleFixtureWriter}, or {@code RealFixture}
 * </ul>
 *
 * <p>The guard itself is exempt (it contains "Parity" in its name but is the
 * policy, not a claim) and is allowed because it references the oracle.
 */
class ParityProvenanceGuardTest {

    private static final Pattern PARITY_TERM = Pattern.compile("(?i)(parity|roundtrip|round_trip|vanillaconvergence|vanilla_convergence|convergence|postIngest|post_ingest)");
    // For filename/class-name we treat "convergence" alone as a claim only when paired with vanilla/parity context
    // to avoid flagging unrelated "convergence" uses. The instruction says
    // Parity, RoundTrip, VanillaConvergence — so we match those three roots; bare "convergence"
    // is included only as a catch-all for equivalent naming, but we still require a parity-like token.
    private static final Set<String> PARITY_SUBSTRINGS = Set.of("parity", "roundtrip", "round_trip", "vanillaconvergence", "vanilla_convergence");

    // Real capture must be evidenced by typed fixture kind or real-oracle loader — mere mention of OracleFixture is not sufficient
    // because SyntheticEndChorusFixtureFactory returns the same OracleFixture type with SYNTHETIC_TEST kind.
    private static final List<String> REAL_CAPTURE_MARKERS = List.of(
            "REAL_CAPTURE",
            "OracleFixtureWriter.read",
            "VanillaVoxyOracle",
            "protocolSha256"
    );
    private static final List<String> SYNTHETIC_TAINT_MARKERS = List.of(
            "SYNTHETIC_TEST",
            "SyntheticEndChorusFixtureFactory"
    );

    // Legitimate parity-infrastructure tests that validate config/reporting logic,
    // not vanilla/voxel parity claims. They contain "Parity" in name but do not
    // assert worldgen/terrain convergence and are exempt from the oracle provenance
    // requirement (they test the machinery, not the terrain).
    private static final Set<String> EXEMPT_LEGITIMATE_PARITY_INFRA = Set.of(
            "ParityConfigTest.java",
            "ParityReporterTest.java"
    );

    private static boolean isParityClaimingFilename(String filename) {
        String lower = filename.toLowerCase(Locale.ROOT);
        // Direct parity-family substrings
        for (String s : PARITY_SUBSTRINGS) {
            if (lower.contains(s)) return true;
        }
        // "convergence" alone is not flagged unless it's a test claiming vanilla/post-ingest parity.
        // To avoid over-flagging, require "convergence" plus parity context in filename — but per
        // instruction "or equivalent" we still flag bare convergence tests that are clearly parity-style.
        // Conservative: flag "convergence" only if filename also looks like a test (contains Test).
        if (lower.contains("convergence") && lower.contains("test")) {
            // Check if this is a known non-parity convergence (e.g., unrelated math) — allow list not needed yet.
            // Flag it as parity-claiming; provenance can still satisfy.
            return true;
        }
        return false;
    }

    private static boolean hasProvenance(String content, String path) {
        boolean hasSyntheticTaint = SYNTHETIC_TAINT_MARKERS.stream().anyMatch(content::contains);
        boolean hasRealCapture = REAL_CAPTURE_MARKERS.stream().anyMatch(content::contains);
        // Being in voxyIntegrationTest alone is not sufficient per review — must also evidence REAL_CAPTURE
        if (hasSyntheticTaint && !hasRealCapture) return false;
        if (hasRealCapture) return true;
        // No real capture marker — even voxyIntegrationTest is not sufficient alone
        return false;
    }

    /** Returns violation messages for the given files, reading content via the provided reader. */
    static List<String> findViolations(List<Path> files) throws IOException {
        List<String> violations = new ArrayList<>();
        for (Path p : files) {
            String filename = p.getFileName().toString();
            // Exempt the guard itself — it's the policy, not a parity claim
            if (filename.equals("ParityProvenanceGuardTest.java")) continue;
            if (EXEMPT_LEGITIMATE_PARITY_INFRA.contains(filename)) continue;
            if (!isParityClaimingFilename(filename)) continue;
            String content = Files.readString(p);
            String pathStr = p.toString().replace('\\', '/');
            if (!hasProvenance(content, pathStr)) {
                violations.add(p + " claims parity/roundTrip/vanillaConvergence (filename: " + filename
                        + ") but lacks REAL_CAPTURE provenance (needs EvidenceKind.REAL_CAPTURE or OracleFixtureWriter.read with protocolSha256, or VanillaVoxyOracle; synthetic SYNTHETIC_TEST is not sufficient, and voxyIntegrationTest alone is not sufficient)");
            }
        }
        return violations;
    }

    private static List<Path> collectCandidateFiles(Path repoRoot) throws IOException {
        List<Path> candidates = new ArrayList<>();
        // Only scan test sources — production ParityConfig/ParityReporter in src/main are not test claims
        List<Path> scanRoots = List.of(
                repoRoot.resolve("java/src/test/java"),
                repoRoot.resolve("java/src/voxyIntegrationTest/java")
        );
        for (Path root : scanRoots) {
            if (!Files.exists(root)) continue;
            try (Stream<Path> walk = Files.walk(root)) {
                walk.filter(p -> {
                    String s = p.toString().replace('\\', '/');
                    if (s.contains("/build/") || s.contains("/.gradle/") || s.contains("/.venv/") || s.contains("/node_modules/")) return false;
                    return s.endsWith(".java") || s.endsWith(".py");
                }).forEach(candidates::add);
            }
        }
        // Python test roots (exclude .venv) — only test files, not implementation
        Path pythonRoot = repoRoot.resolve("python");
        if (Files.exists(pythonRoot)) {
            try (Stream<Path> walk = Files.walk(pythonRoot)) {
                walk.filter(p -> {
                    String s = p.toString().replace('\\', '/');
                    if (s.contains("/.venv/") || s.contains("/.tox/") || s.contains("/__pycache__/") || s.contains("/build/") || s.contains("/.gradle/")) return false;
                    if (!(s.endsWith(".py"))) return false;
                    // Only consider python test files
                    return s.contains("/tests/") || s.contains("test_") || s.contains("_test.py");
                }).forEach(candidates::add);
            }
        }
        return candidates;
    }

    private static Path findRepoRoot() {
        Path cur = Paths.get("").toAbsolutePath();
        for (int i = 0; i < 8; i++) {
            if (Files.exists(cur.resolve("java/build.gradle")) || Files.exists(cur.resolve("java/gradlew.bat"))) {
                return cur;
            }
            Path parent = cur.getParent();
            if (parent == null) break;
            cur = parent;
        }
        // Fallback: try relative from java dir
        Path alt = Paths.get("").toAbsolutePath();
        if (alt.getFileName() != null && alt.getFileName().toString().equals("java")) {
            return alt.getParent();
        }
        return Paths.get("").toAbsolutePath();
    }

    @Test
    void parityClaimingTestsMustHaveIndependentOracleProvenance() throws Exception {
        Path repoRoot = findRepoRoot();
        List<Path> candidates = collectCandidateFiles(repoRoot);
        List<String> violations = findViolations(candidates);
        if (!violations.isEmpty()) {
            fail("Parity/roundTrip/vanillaConvergence tests without REAL_CAPTURE provenance found (ADR 0015):\n"
                    + String.join("\n", violations)
                    + "\n\nAny test/class claiming Parity, RoundTrip, VanillaConvergence, or equivalent must evidence REAL_CAPTURE "
                    + "(EvidenceKind.REAL_CAPTURE, OracleFixtureWriter.read with protocolSha256, or VanillaVoxyOracle). "
                    + "Synthetic fixtures (SYNTHETIC_TEST / SyntheticEndChorusFixtureFactory) do NOT satisfy parity provenance, "
                    + "and voxyIntegrationTest location alone is not sufficient per review.");
        }
    }

    @Test
    void bogusParityWithoutProvenanceIsDetected(@TempDir Path tmp) throws Exception {
        // Create a deliberately bogus parity test that lacks provenance — must be flagged by the same logic
        Path bogus = tmp.resolve("BogusParityTest.java");
        String content = """
                package com.example;
                import org.junit.jupiter.api.Test;
                class BogusParityTest {
                    @Test
                    void fakeParity() {
                        // Claims parity but uses synthetic expected — no independent oracle
                        int expected = 42;
                        int actual = 42;
                        assert expected == actual;
                    }
                }
                """;
        Files.writeString(bogus, content);
        List<String> violations = findViolations(List.of(bogus));
        assertEquals(1, violations.size(),
                "Bogus parity test without provenance must be detected by the policy, was: " + violations);
        assertTrue(violations.get(0).contains("BogusParityTest.java"));

        // Same file but with provenance should NOT be flagged
        Path withProvenance = tmp.resolve("GoodParityTest.java");
        String goodContent = """
                package com.example;
                import com.rhythmatician.lodiffusion.oracle.OracleFixture;
                class GoodParityTest {
                    @Test
                    void realParity() {
                        OracleFixture.EvidenceKind k = OracleFixture.EvidenceKind.REAL_CAPTURE;
                        var f = OracleFixtureWriter.read(java.nio.file.Paths.get("java/oracle-fixtures/fixture.json"));
                    }
                }
                """;
        Files.writeString(withProvenance, goodContent);
        List<String> goodViolations = findViolations(List.of(withProvenance));
        assertTrue(goodViolations.isEmpty(), "Parity test with OracleFixture provenance must not be flagged, was: " + goodViolations);

        // voxyIntegrationTest alone is NOT sufficient per review — must also evidence REAL_CAPTURE
        Path inIntegrationBare = tmp.resolve("voxyIntegrationTest").resolve("BareIntegrationParityTest.java");
        Files.createDirectories(inIntegrationBare.getParent());
        Files.writeString(inIntegrationBare, content);
        List<String> bareViolations = findViolations(List.of(inIntegrationBare));
        assertEquals(1, bareViolations.size(), "Bare voxyIntegrationTest without REAL_CAPTURE must still be flagged, was: " + bareViolations);
        // voxyIntegrationTest WITH real capture marker is sufficient
        Path inIntegration = tmp.resolve("voxyIntegrationTest").resolve("IntegrationParityTest.java");
        Files.createDirectories(inIntegration.getParent());
        Files.writeString(inIntegration, goodContent);
        List<String> integrationViolations = findViolations(List.of(inIntegration));
        assertTrue(integrationViolations.isEmpty(),
                "Parity test in voxyIntegrationTest WITH REAL_CAPTURE must not be flagged, was: " + integrationViolations);
    }
}
