import { readFileSync, writeFileSync } from "node:fs";
import type { Evidence, EvidenceStatus } from "./ci-policy.mts";

const registry = JSON.parse(readFileSync(".ci/checks.json", "utf8")) as { lanes: Record<string, string[]> };
const laneResults = JSON.parse(readFileSync(process.argv[2]!, "utf8")) as Record<string, string>;
const candidateSha = process.argv[3]!;
const evidence: Evidence[] = [];
for (const [lane, checkIds] of Object.entries(registry.lanes)) {
  const result = laneResults[lane];
  if (!result || result === "skipped") continue;
  const status: EvidenceStatus = result === "success" ? "PASS" : result === "cancelled" ? "CANCELLED" : "FAIL";
  evidence.push(...checkIds.map((checkId) => ({ checkId, candidateSha, status, details: `GitHub Actions lane ${lane}: ${result}` })));
}
writeFileSync(process.argv[4]!, JSON.stringify(evidence, null, 2) + "\n");
