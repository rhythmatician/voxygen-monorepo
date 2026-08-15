import { readFileSync, writeFileSync } from "node:fs";
import { classifyChanges, decideMerge, requiredChecks } from "./ci-policy.mts";

const args = new Map<string, string>();
for (let i = 2; i < process.argv.length; i += 2) args.set(process.argv[i]!, process.argv[i + 1]!);
const command = args.get("--command") ?? "decide";
const files = readFileSync(args.get("--files")!, "utf8").split(/\r?\n/).filter(Boolean);
if (command === "plan") {
  const classes = classifyChanges(files);
  process.stdout.write(JSON.stringify({ changeClasses: classes, requiredChecks: requiredChecks(classes) }));
} else {
  const manifest = decideMerge({
    candidateSha: args.get("--candidate")!, baseSha: args.get("--base")!, files,
    evidence: JSON.parse(readFileSync(args.get("--evidence")!, "utf8")),
    humanApproved: args.get("--human-approved") === "true", issue: args.get("--issue"),
  });
  writeFileSync(args.get("--output") ?? "factory-evidence-manifest.json", JSON.stringify(manifest, null, 2) + "\n");
  if (manifest.status !== "PASS") {
    console.error(JSON.stringify({ check_id: "Factory / Merge Oracle", claim: "all required claims exist for the exact candidate", candidate_sha: manifest.candidateSha, failure_class: "POLICY", expected: manifest.requiredChecks, observed: { missing: manifest.missing, invalid: manifest.invalid }, reproduction_command: "npm run ci:oracle -- --command decide ..." }, null, 2));
    process.exitCode = 1;
  }
}
