import { readFileSync } from "node:fs";
import { z } from "zod";

export type ChangeClass = "C0" | "C1_JAVA" | "C1_PYTHON" | "C1_FACTORY" | "C2" | "C3" | "C4" | "C5" | "C6" | "C7";
export type EvidenceStatus = "PASS" | "FAIL" | "INFRASTRUCTURE_FAILURE" | "NOT_APPLICABLE" | "CANCELLED" | "FLAKY" | "PENDING";

export interface Evidence { checkId: string; candidateSha: string; status: EvidenceStatus; details?: string; artifactDigests?: Record<string, string> }
export interface MergeInput { candidateSha: string; baseSha: string; files: string[]; evidence: Evidence[]; humanApproved?: boolean; issue?: string }

const checkIds = z.array(z.string().min(1));
const registrySchema = z.object({
  requirements: z.object({
    repository: checkIds, factory: checkIds, java: checkIds, python: checkIds,
    contract: checkIds, integration: checkIds, performance: checkIds, supplyChain: checkIds,
  }),
  controlPlanePrefixes: z.array(z.string().min(1)),
  controlPlaneFiles: z.array(z.string().min(1)),
});
const registry = registrySchema.parse(JSON.parse(readFileSync(new URL("../.ci/checks.json", import.meta.url), "utf8")));
const groups = registry.requirements;

const matches = (path: string, fragments: RegExp[]) => fragments.some((pattern) => pattern.test(path));

export function classifyChanges(files: string[]): ChangeClass[] {
  const classes = new Set<ChangeClass>();
  for (const raw of files) {
    const path = raw.replaceAll("\\", "/");
    if (/^(README|CONTEXT|GLOSSARY)\.md$|^docs\//.test(path)) classes.add("C0");
    if (/^java\//.test(path)) classes.add("C1_JAVA");
    if (/^python\//.test(path)) classes.add("C1_PYTHON");
    if (/^(\.sandcastle\/|package\.json$|package-lock\.json$|tsconfig\.json$)/.test(path)) classes.add("C1_FACTORY");
    if (matches(path, [/contracts?\//i, /contract/i, /CanonicalRegistries/, /biome_mapping/, /SectionPos/, /WorldSectionCoord/, /VoxelVolume/, /VoxelPredictionDecoder/, /model.*config/i])) classes.add("C2");
    if (matches(path, [/voxy/i, /fabric/i, /mixin/i, /worldgen/i, /fabric\.mod\.json$/])) classes.add("C3");
    if (matches(path, [/gradle/i, /pyproject\.toml$/, /uv\.lock$/, /package(-lock)?\.json$/, /\.github\/dependabot/, /^external\//])) classes.add("C4");
    if (registry.controlPlanePrefixes.some((prefix) => path.startsWith(prefix)) || registry.controlPlaneFiles.includes(path)) classes.add("C5");
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
  if (classes.includes("C1_FACTORY") || classes.includes("C5")) add(groups.factory);
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
  return !classifyChanges(files).includes("C5");
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
  if (changeClasses.includes("C5") && !input.humanApproved) invalid.push("C5: human approval required");
  return {
    schemaVersion: 1, candidateSha: input.candidateSha, baseSha: input.baseSha, issue: input.issue,
    changeClasses, requiredChecks: required, evidence: input.evidence, missing, invalid,
    status: missing.length === 0 && invalid.length === 0 ? "PASS" as const : "FAIL" as const,
  };
}
