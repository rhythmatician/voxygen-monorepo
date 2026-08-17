import { readFileSync } from "node:fs";
import { z } from "zod";

export type ChangeClass = "C0" | "C1_JAVA" | "C1_PYTHON" | "C1_FACTORY" | "C2" | "C3" | "C4" | "C6" | "C7";
export type EvidenceStatus = "PASS" | "FAIL" | "INFRASTRUCTURE_FAILURE" | "NOT_APPLICABLE" | "CANCELLED" | "FLAKY" | "PENDING";

export interface Evidence { checkId: string; candidateSha: string; status: EvidenceStatus; details?: string; artifactDigests?: Record<string, string> }
export interface MergeInput { candidateSha: string; baseSha: string; files: string[]; evidence: Evidence[]; humanApproved?: boolean; issue?: string }

const checkIds = z.array(z.string().min(1));
const pathSetSchema = z.object({
  rationale: z.string().min(1),
  prefixes: z.array(z.string().min(1)),
  files: z.array(z.string().min(1)),
});
const registrySchema = z.object({
  schemaVersion: z.literal(2),
  requirements: z.object({
    repository: checkIds, factory: checkIds, java: checkIds, python: checkIds,
    contract: checkIds, integration: checkIds, performance: checkIds, supplyChain: checkIds,
  }),
  pathSets: z.record(z.string().min(1), pathSetSchema),
  evidencePathRules: z.array(z.object({ pathSet: z.string().min(1), changeClass: z.literal("C1_FACTORY") })),
  humanApprovalPathSets: z.array(z.string().min(1)),
});
const registry = registrySchema.parse(JSON.parse(readFileSync(new URL("../.ci/checks.json", import.meta.url), "utf8")));
const groups = registry.requirements;

for (const name of [...registry.evidencePathRules.map((rule) => rule.pathSet), ...registry.humanApprovalPathSets]) {
  if (!registry.pathSets[name]) throw new Error(`Unknown path set: ${name}`);
}

const matches = (path: string, fragments: RegExp[]) => fragments.some((pattern) => pattern.test(path));
const normalized = (path: string) => path.replaceAll("\\", "/");
const matchesPathSet = (path: string, name: string) => {
  const pathSet = registry.pathSets[name]!;
  return pathSet.prefixes.some((prefix) => path.startsWith(prefix)) || pathSet.files.includes(path);
};

export function humanApprovalReasons(files: string[]): string[] {
  const paths = files.map(normalized);
  return registry.humanApprovalPathSets.filter((name) => paths.some((path) => matchesPathSet(path, name)));
}

export function requiresHumanApproval(files: string[]): boolean {
  return humanApprovalReasons(files).length > 0;
}

export function humanApprovalFor(files: string[]) {
  const reasons = humanApprovalReasons(files);
  return { required: reasons.length > 0, reasons };
}

export function codeOwnerPatterns(): string[] {
  const patterns = new Set<string>();
  for (const name of registry.humanApprovalPathSets) {
    const pathSet = registry.pathSets[name]!;
    pathSet.prefixes.forEach((prefix) => patterns.add(`/${prefix}`));
    pathSet.files.forEach((file) => patterns.add(`/${file}`));
  }
  return [...patterns].sort();
}

export function classifyChanges(files: string[]): ChangeClass[] {
  const classes = new Set<ChangeClass>();
  for (const raw of files) {
    const path = normalized(raw);
    if (/^(README|CONTEXT|GLOSSARY)\.md$|^docs\//.test(path)) classes.add("C0");
    if (/^java\//.test(path)) classes.add("C1_JAVA");
    if (/^python\//.test(path)) classes.add("C1_PYTHON");
    if (registry.evidencePathRules.some((rule) => rule.changeClass === "C1_FACTORY" && matchesPathSet(path, rule.pathSet))) classes.add("C1_FACTORY");
    if (matches(path, [/contracts?\//i, /contract/i, /CanonicalRegistries/, /biome_mapping/, /SectionPos/, /WorldSectionCoord/, /VoxelVolume/, /VoxelPredictionDecoder/, /model.*config/i])) classes.add("C2");
    if (matches(path, [/voxy/i, /fabric/i, /mixin/i, /worldgen/i, /fabric\.mod\.json$/])) classes.add("C3");
    if (matches(path, [/gradle/i, /pyproject\.toml$/, /uv\.lock$/, /package(-lock)?\.json$/, /\.github\/dependabot/, /^external\//])) classes.add("C4");
    if (matches(path, [/Scheduler/, /Queue/, /Inference/, /Decoder/, /VolumeWriter/, /Performance/, /benchmark/i])) classes.add("C6");
    if (matches(path, [/onnx/i, /export/i, /model/i, /train/i, /contracts?\//i, /\.onnx$/])) classes.add("C7");
  }
  if (classes.size === 0) classes.add("C0");
  return [...classes].sort();
}

export function requiredChecks(classes: ChangeClass[]): string[] {
  const checks = new Set<string>(groups.repository);
  const add = (items: readonly string[]) => items.forEach((item) => checks.add(item));
  if (classes.includes("C1_JAVA")) add(groups.java);
  if (classes.includes("C1_PYTHON")) add(groups.python);
  if (classes.includes("C1_FACTORY")) add(groups.factory);
  if (classes.includes("C2")) add(groups.contract);
  if (classes.includes("C3")) { add(groups.java); add(groups.integration); }
  if (classes.includes("C4")) {
    if (classes.includes("C1_JAVA")) add(groups.java);
    if (classes.includes("C1_PYTHON")) add(groups.python);
    if (classes.includes("C1_FACTORY")) add(groups.factory);
    add(groups.integration);
    add(groups.supplyChain);
  }
  if (classes.includes("C6")) add(groups.performance);
  if (classes.includes("C7")) add(groups.contract.slice(4, 12));
  return [...checks].sort();
}

export function mayAutonomouslyMerge(files: string[]): boolean {
  return !requiresHumanApproval(files);
}

export function decideMerge(input: MergeInput) {
  const changeClasses = classifyChanges(input.files);
  const required = requiredChecks(changeClasses);
  const byId = new Map(input.evidence.map((item) => [item.checkId, item]));
  const missing = required.filter((id) => !byId.has(id));
  const invalid: string[] = [];
  for (const id of required) {
    const item = byId.get(id);
    if (!item) continue;
    if (item.candidateSha !== input.candidateSha) invalid.push(`${id}: stale candidate SHA ${item.candidateSha}`);
    else if (item.status !== "PASS") invalid.push(`${id}: ${item.status}`);
  }
  const humanApproval = humanApprovalFor(input.files);
  if (humanApproval.required && !input.humanApproved) invalid.push("independent human approval required");
  return {
    schemaVersion: 2, candidateSha: input.candidateSha, baseSha: input.baseSha, issue: input.issue,
    changeClasses, requiredChecks: required, evidence: input.evidence, missing, invalid,
    humanApproval,
    status: missing.length === 0 && invalid.length === 0 ? "PASS" as const : "FAIL" as const,
  };
}
