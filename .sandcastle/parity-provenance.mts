/**
 * Parity provenance guard — ADR 0015.
 *
 * Any test/class whose name claims Parity, RoundTrip, VanillaConvergence
 * (or equivalent) must carry machine-checkable independent-oracle provenance
 * or live in the appropriate oracle-fixture boundary.
 *
 * Used by both the Java guard (ParityProvenanceGuardTest.java, which runs in
 * gradlew test / Factory CI java lane) and the Sandcastle factory tests
 * (parity-provenance.test.mts, which runs in npm test / Factory CI factory lane).
 */

export const PARITY_SUBSTRINGS = [
  "parity",
  "roundtrip",
  "round_trip",
  "vanillaconvergence",
  "vanilla_convergence",
] as const;

const PROVENANCE_MARKERS = [
  "OracleFixtureWriter",
  "VanillaVoxyOracle",
  "OracleFixture",
  "EndChorusTracerContract",
  "OracleContract",
  "RealFixture",
] as const;

const GUARD_EXEMPT = new Set([
  "ParityProvenanceGuardTest.java",
  "parity-provenance.test.mts",
  "parity-provenance.mts",
  // Legitimate parity-infrastructure tests that validate config/reporting logic,
  // not vanilla/voxel parity claims — they contain "Parity" in name but do not
  // assert worldgen/terrain convergence and are exempt from the oracle provenance
  // requirement (they test the machinery, not the terrain).
  "ParityConfigTest.java",
  "ParityReporterTest.java",
]);

export function isParityClaimingFilename(filename: string): boolean {
  const lower = filename.toLowerCase();
  for (const s of PARITY_SUBSTRINGS) {
    if (lower.includes(s)) return true;
  }
  // "convergence" alone is flagged only when the file is a parity-style test
  if (lower.includes("convergence") && lower.includes("test")) return true;
  // Also flag post_ingest / postIngest
  if (lower.includes("post_ingest") || lower.includes("postingest")) return true;
  return false;
}

export function hasProvenance(content: string, path: string): boolean {
  const normalized = path.replaceAll("\\", "/");
  if (normalized.includes("voxyIntegrationTest")) return true;
  for (const m of PROVENANCE_MARKERS) {
    if (content.includes(m)) return true;
  }
  return false;
}

export interface Violation {
  path: string;
  filename: string;
  message: string;
}

/**
 * Scan a list of {path, content} entries and return parity-without-provenance violations.
 * The caller is responsible for gathering the file list (e.g., via git ls-files or fs walk).
 */
export function findParityWithoutProvenance(
  entries: Array<{ path: string; content: string }>,
): Violation[] {
  const violations: Violation[] = [];
  for (const { path, content } of entries) {
    const filename = path.split("/").pop() ?? path;
    if (GUARD_EXEMPT.has(filename)) continue;
    if (!isParityClaimingFilename(filename)) continue;
    if (hasProvenance(content, path)) continue;
    violations.push({
      path,
      filename,
      message:
        `${path} claims parity/roundTrip/vanillaConvergence (filename: ${filename}) but lacks ` +
        `independent-oracle provenance (needs OracleFixture/VanillaVoxyOracle/EndChorusTracerContract or voxyIntegrationTest boundary)`,
    });
  }
  return violations;
}
