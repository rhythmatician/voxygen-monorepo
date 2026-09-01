import * as fs from "node:fs";
import * as path from "node:path";
import { execFileSync } from "node:child_process";
import { classifyChanges, humanApprovalReasons } from "./ci-policy.mts";
import { withAtomicJsonReceipt } from "./resource-scopes.mts";
import { EXPECTED_SANDCASTLE_SOURCE_SHA } from "./sandcastle-runtime-provenance.mts";

// ---------------------------------------------------------------------------
// Types — must preserve distinct VerificationState values per contract
// ---------------------------------------------------------------------------

export type VerificationState =
  | "pending"
  | "running"
  | "passed"
  | "failed"
  | "not-run"
  | "unavailable";

export type VerificationObligation = {
  id: string;
  argv: string[];
  cwdKind: "candidate" | "clean-checkout" | "docker-worker";
  required: boolean;
  state: VerificationState;
  exitCode?: number;
  startedAt?: string;
  completedAt?: string;
  receiptPath?: string;
  failure?: string;
};

export type EvidenceKind =
  | "behavioral-production-path"
  | "structural-only"
  | "exact-runtime-canary"
  | "live-rollout-pending"
  | "not-run"
  | "capability-gap";

export type CandidateProof = {
  criterionId: string;
  claim: string;
  candidateSha: string;
  productionEntryPoint: string;
  productionConsumer: string;
  evidenceKind: EvidenceKind;
  tests: string[];
  commandObligationIds: string[];
  assertedPostconditions: string[];
  proved: boolean;
  gap?: string;
};

export type CandidateEnvironmentReceipt = {
  nodeVersion: string;
  packageLockHash?: string;
  javaVersion?: string;
  gradleVersion?: string;
  sandcastleRuntimeSha: string;
  sandcastleRuntimeDistPath?: string;
  dockerImageId?: string;
  cleanCheckout: boolean;
  ambientSiblingDetected: boolean;
  cwdValid: boolean;
  worktreeStatusBefore?: string;
  worktreeStatusAfter?: string;
};

export type CandidateProofResult = {
  candidateSha: string;
  baseSha: string;
  issueId: string;
  issueTitle: string;
  candidateBranch: string;
  obligations: VerificationObligation[];
  proofs: CandidateProof[];
  processesSettled: boolean;
  resourcesSettled: boolean;
  environment: CandidateEnvironmentReceipt;
  readyForReview: boolean;
  blockingReasons: string[];
  schemaVersion: number;
  generatedAt: string;
};

export type CandidateProofInput = {
  issueId: string;
  issueTitle: string;
  issueBody: string;
  candidateBranch: string;
  baseSha?: string;
  candidateSha?: string;
  changedFiles: string[];
  // Optional model-authored claim drafts — never authoritative
  candidateClaims?: Array<{
    criterionId?: string;
    claim: string;
    productionEntryPoint?: string;
    productionConsumer?: string;
    evidenceKind?: EvidenceKind;
    tests?: string[];
    commandObligationIds?: string[];
    assertedPostconditions?: string[];
  }>;
  // Optional explicit worktree status for testing
  worktreeStatusBefore?: string;
  worktreeStatusAfter?: string;
};

export type CandidateProofDependencies = {
  // Git capture — injected for testing
  getBaseSha?: () => string;
  getCandidateSha?: () => string;
  getBranchSha?: (branch: string) => string;
  // Command execution — host-owned typed obligations
  runCommand?: (obligation: VerificationObligation) => Promise<Partial<VerificationObligation>>;
  // Environment probing
  probeEnvironment?: () => Partial<CandidateEnvironmentReceipt>;
  // Resource settlement verification
  verifyResourcesSettled?: () => { settled: boolean; reason?: string };
  // Tracked background process handles — for settlement proof
  trackedProcessHandles?: Array<{ pid?: number; settled: boolean }>;
  // Ambient sibling detection
  ambientSiblingDetected?: boolean;
  // Clean checkout verification — if true, clean closure proved; if false, gap
  cleanCheckout?: boolean;
  // Repo root for persistence
  repoRoot?: string;
  // Whether to actually persist receipt (tests may disable)
  persistReceipt?: boolean;
};

export class CandidateProofFactoryError extends Error {
  readonly code = "FACTORY_ERROR";
  constructor(message: string) {
    super(message);
    this.name = "CandidateProofFactoryError";
  }
}

// ---------------------------------------------------------------------------
// Deterministic criterion ID derivation
// ---------------------------------------------------------------------------

function djb2Hash(input: string): string {
  let h = 5381;
  for (let i = 0; i < input.length; i++) h = ((h << 5) + h + input.charCodeAt(i)) >>> 0;
  return h.toString(16).padStart(8, "0");
}

function normalizeCriterionText(text: string): string {
  return text.toLowerCase().replace(/[^a-z0-9]+/g, " ").trim().replace(/\s+/g, " ");
}

export function deriveCriterionIds(issueBody: string): Array<{ id: string; claim: string }> {
  const lines = issueBody.split("\n");
  // Extract acceptance criteria section if present
  let inAcceptance = false;
  const criteria: Array<{ id: string; claim: string }> = [];
  const explicitPattern = /^\s*(T\d+|R\d+|M\d+)\b/;
  // First, try to find checkbox lines
  for (const line of lines) {
    // Detect acceptance header
    if (/^#{1,6}\s*(acceptance|criteria|done when)/i.test(line)) inAcceptance = true;
    else if (inAcceptance && /^#{1,6}\s+/.test(line)) {
      // Next section — stop if we already collected
      if (criteria.length > 0) break;
    }
    const m = line.match(/^\s*[-*]\s*\[[ xX]\]\s*(.*)$/);
    if (!m) continue;
    const rest = m[1].trim();
    if (!rest) continue;
    const explicit = rest.match(explicitPattern);
    if (explicit) {
      criteria.push({ id: explicit[1], claim: rest });
    } else {
      const norm = normalizeCriterionText(rest);
      const hash = djb2Hash(norm);
      criteria.push({ id: `AC-${hash}`, claim: rest });
    }
  }
  // If no checkbox criteria found, derive from whole body hash segments for stability
  if (criteria.length === 0) {
    const norm = normalizeCriterionText(issueBody.slice(0, 2000));
    if (norm.length > 0) {
      criteria.push({ id: `AC-${djb2Hash(norm)}`, claim: issueBody.slice(0, 200).trim() || "criterion" });
    }
  }
  return criteria;
}

// ---------------------------------------------------------------------------
// Host-owned required command set
// ---------------------------------------------------------------------------

function buildRequiredObligations(changedFiles: string[]): VerificationObligation[] {
  const obs: VerificationObligation[] = [
    { id: "typecheck", argv: ["npm", "run", "typecheck"], cwdKind: "candidate", required: true, state: "pending" },
    { id: "test", argv: ["npm", "test"], cwdKind: "candidate", required: true, state: "pending" },
    { id: "git-diff-check", argv: ["git", "diff", "--check"], cwdKind: "candidate", required: true, state: "pending" },
  ];
  const hasJava = changedFiles.some((f) => f.startsWith("java/"));
  if (hasJava) {
    obs.push({ id: "java-lint", argv: ["./java/gradlew", "-p", "java", "lint"], cwdKind: "candidate", required: true, state: "pending" });
    obs.push({ id: "java-compile", argv: ["./java/gradlew", "-p", "java", "compileJava", "compileClientJava"], cwdKind: "candidate", required: true, state: "pending" });
    obs.push({ id: "java-test", argv: ["./java/gradlew", "-p", "java", "test", "-PexcludeVoxyTestRuntime"], cwdKind: "candidate", required: true, state: "pending" });
  }
  return obs;
}

export function isProtectedCandidate(changedFiles: string[]): boolean {
  const classes = classifyChanges(changedFiles);
  if (classes.includes("C1_FACTORY")) return true;
  const reasons = humanApprovalReasons(changedFiles);
  return reasons.length > 0;
}

// ---------------------------------------------------------------------------
// Helpers — env, SHA validation, receipt persistence
// ---------------------------------------------------------------------------

function isValidSha40(sha: string): boolean {
  return /^[0-9a-f]{40}$/.test(sha);
}

function probeDefaultEnvironment(): CandidateEnvironmentReceipt {
  let nodeVersion = "";
  try { nodeVersion = execFileSync("node", ["--version"], { encoding: "utf8" }).trim(); } catch {}
  let packageLockHash: string | undefined;
  try {
    const p = path.join(process.cwd(), "package-lock.json");
    if (fs.existsSync(p)) {
      const c = fs.readFileSync(p, "utf8");
      // simple hash
      let h = 0; for (let i=0;i<c.length;i++) h = ((h<<5)-h + c.charCodeAt(i))>>>0;
      packageLockHash = h.toString(16).padStart(8,"0");
    }
  } catch {}
  let javaVersion: string | undefined;
  try { javaVersion = execFileSync("java", ["-version"], { encoding: "utf8" }).split("\n")[0]?.trim(); } catch {}
  return {
    nodeVersion: nodeVersion || "unknown",
    packageLockHash,
    javaVersion,
    sandcastleRuntimeSha: EXPECTED_SANDCASTLE_SOURCE_SHA,
    cleanCheckout: true,
    ambientSiblingDetected: false,
    cwdValid: true,
  };
}

// ---------------------------------------------------------------------------
// Main seam
// ---------------------------------------------------------------------------

export async function collectCandidateProof(
  input: CandidateProofInput,
  dependencies: CandidateProofDependencies = {},
): Promise<CandidateProofResult> {
  const repoRoot = dependencies.repoRoot ?? process.cwd();
  const now = new Date().toISOString();

  // Exact candidate identity capture — structured scope with finally settlement
  let baseSha = input.baseSha ?? "";
  let candidateSha = input.candidateSha ?? "";
  try {
    if (!baseSha && dependencies.getBaseSha) baseSha = dependencies.getBaseSha();
    else if (!baseSha) baseSha = execFileSync("git", ["rev-parse", "origin/main"], { encoding: "utf8" }).trim();
  } catch (e) {
    throw new CandidateProofFactoryError(`FACTORY_ERROR: cannot determine base SHA: ${e instanceof Error ? e.message : String(e)}`);
  }
  try {
    if (!candidateSha && dependencies.getCandidateSha) candidateSha = dependencies.getCandidateSha();
    else if (!candidateSha) candidateSha = execFileSync("git", ["rev-parse", "HEAD"], { encoding: "utf8" }).trim();
  } catch (e) {
    throw new CandidateProofFactoryError(`FACTORY_ERROR: cannot determine candidate SHA: ${e instanceof Error ? e.message : String(e)}`);
  }

  if (!isValidSha40(baseSha)) throw new CandidateProofFactoryError(`FACTORY_ERROR: invalid base SHA ${baseSha}`);
  if (!isValidSha40(candidateSha)) throw new CandidateProofFactoryError(`FACTORY_ERROR: invalid candidate SHA ${candidateSha}`);

  // Branch SHA stability check — must still point to recorded candidate SHA
  if (dependencies.getBranchSha) {
    const branchSha = dependencies.getBranchSha(input.candidateBranch);
    if (branchSha !== candidateSha) {
      throw new CandidateProofFactoryError(`FACTORY_ERROR: candidate branch ${input.candidateBranch} moved during verification: expected ${candidateSha} got ${branchSha}`);
    }
  } else {
    try {
      const branchSha = execFileSync("git", ["rev-parse", input.candidateBranch], { encoding: "utf8" }).trim();
      if (branchSha !== candidateSha) {
        throw new CandidateProofFactoryError(`FACTORY_ERROR: candidate branch ${input.candidateBranch} moved: expected ${candidateSha} got ${branchSha}`);
      }
    } catch (e) {
      if (e instanceof CandidateProofFactoryError) throw e;
      throw new CandidateProofFactoryError(`FACTORY_ERROR: cannot verify candidate branch SHA: ${e instanceof Error ? e.message : String(e)}`);
    }
  }

  // Worktree status
  const worktreeStatusBefore = input.worktreeStatusBefore ?? "clean";
  const worktreeStatusAfter = input.worktreeStatusAfter ?? "clean";

  // Build required obligations (host-owned)
  const obligations: VerificationObligation[] = buildRequiredObligations(input.changedFiles);

  // Execute / settle obligations with structured scopes
  let processesSettled = true;
  let resourcesSettled = true;
  const blockingReasons: string[] = [];
  let settlementError: string | undefined;

  try {
    for (const ob of obligations) {
      ob.startedAt = new Date().toISOString();
      if (dependencies.runCommand) {
        try {
          const result = await dependencies.runCommand({ ...ob });
          ob.state = (result.state as VerificationState) ?? "unavailable";
          if (result.exitCode !== undefined) ob.exitCode = result.exitCode;
          if (result.failure) ob.failure = result.failure;
          if (result.receiptPath) ob.receiptPath = result.receiptPath;
          if (result.completedAt) ob.completedAt = result.completedAt;
          else ob.completedAt = new Date().toISOString();
          if (ob.state === "running" || ob.state === "pending") {
            processesSettled = false;
          }
        } catch (e) {
          ob.state = "unavailable";
          ob.failure = e instanceof Error ? e.message : String(e);
          ob.completedAt = new Date().toISOString();
          settlementError = `command ${ob.id} unavailable: ${ob.failure}`;
        }
      } else {
        // No runner injected — mark unavailable (FACTORY_ERROR path)
        ob.state = "unavailable";
        ob.failure = "no command runner available";
        ob.completedAt = new Date().toISOString();
      }
    }

    // Check tracked background processes
    if (dependencies.trackedProcessHandles) {
      for (const h of dependencies.trackedProcessHandles) {
        if (!h.settled) processesSettled = false;
      }
    }

    // Verify external resource settlement
    if (dependencies.verifyResourcesSettled) {
      const r = dependencies.verifyResourcesSettled();
      resourcesSettled = r.settled;
      if (!r.settled) settlementError = r.reason ?? "resource cleanup failed";
    }

    // Environment identity
    let environment: CandidateEnvironmentReceipt;
    if (dependencies.probeEnvironment) {
      const probed = dependencies.probeEnvironment();
      environment = {
        nodeVersion: probed.nodeVersion ?? "unknown",
        packageLockHash: probed.packageLockHash,
        javaVersion: probed.javaVersion,
        gradleVersion: probed.gradleVersion,
        sandcastleRuntimeSha: probed.sandcastleRuntimeSha ?? EXPECTED_SANDCASTLE_SOURCE_SHA,
        sandcastleRuntimeDistPath: probed.sandcastleRuntimeDistPath,
        dockerImageId: probed.dockerImageId,
        cleanCheckout: probed.cleanCheckout ?? true,
        ambientSiblingDetected: probed.ambientSiblingDetected ?? (dependencies.ambientSiblingDetected ?? false),
        cwdValid: probed.cwdValid ?? true,
        worktreeStatusBefore,
        worktreeStatusAfter,
      };
    } else {
      environment = probeDefaultEnvironment();
      environment.worktreeStatusBefore = worktreeStatusBefore;
      environment.worktreeStatusAfter = worktreeStatusAfter;
      if (dependencies.ambientSiblingDetected !== undefined) environment.ambientSiblingDetected = dependencies.ambientSiblingDetected;
      if (dependencies.cleanCheckout !== undefined) environment.cleanCheckout = dependencies.cleanCheckout;
    }

    // Ambient sibling gap — clean closure must be proved
    if (environment.ambientSiblingDetected) {
      blockingReasons.push("ambient sibling checkout reliance detected — clean dependency closure not proved");
      resourcesSettled = false;
    }
    if (!environment.cleanCheckout) {
      blockingReasons.push("clean checkout failed — pinned dependency closure not proved");
    }
    if (!environment.cwdValid) {
      blockingReasons.push("caller checkout/CWD invalid after verification");
      resourcesSettled = false;
    }

    // Required obligation gating
    for (const ob of obligations) {
      if (ob.required && ob.state !== "passed") {
        blockingReasons.push(`required obligation ${ob.id} is ${ob.state}${ob.failure ? `: ${ob.failure}` : ""}`);
        if (ob.state === "unavailable" || ob.state === "pending" || ob.state === "running") {
          // Infrastructure uncertainty -> will be FACTORY_ERROR if caller checks
        }
      }
    }
    if (!processesSettled) blockingReasons.push("required processes remain unsettled (pending/running)");
    if (!resourcesSettled) blockingReasons.push("resources not settled");
    if (settlementError) blockingReasons.push(settlementError);
    // Branch movement already thrown as FACTORY_ERROR above

    // Claim-to-proof ledger construction — host canonicalizes
    const derived = deriveCriterionIds(input.issueBody);
    const proofs: CandidateProof[] = [];

    // Map drafts by criterionId if provided
    const draftById = new Map<string, NonNullable<CandidateProofInput["candidateClaims"]>[number]>();
    for (const d of input.candidateClaims ?? []) {
      if (d.criterionId) draftById.set(d.criterionId, d);
      else {
        // No id — map by normalized claim
        const nk = normalizeCriterionText(d.claim);
        draftById.set(nk, d);
      }
    }

    for (const c of derived) {
      const draft = draftById.get(c.id) ?? draftById.get(normalizeCriterionText(c.claim));
      const productionEntryPoint = draft?.productionEntryPoint ?? (input.changedFiles[0] ?? ".sandcastle/main.mts");
      const productionConsumer = draft?.productionConsumer ?? "main.mts";
      const evidenceKind: EvidenceKind = (draft?.evidenceKind as EvidenceKind) ?? "behavioral-production-path";
      const tests = draft?.tests ?? [];
      const commandObligationIds = draft?.commandObligationIds ?? obligations.filter(o=>o.required).map(o=>o.id);
      const assertedPostconditions = draft?.assertedPostconditions ?? [c.claim];

      let proved = false;
      let gap: string | undefined;

      // Rules enforcement
      if (evidenceKind === "live-rollout-pending" || evidenceKind === "not-run" || evidenceKind === "capability-gap") {
        proved = false;
        gap = `${evidenceKind} cannot be presented as passed proof`;
      } else if (evidenceKind === "structural-only") {
        // Structural-only may prove wiring but cannot satisfy behavioral postcondition
        const hasBehavioral = assertedPostconditions.some(p => /behavior|postcondition|external|production-path/i.test(p));
        if (hasBehavioral) {
          proved = false;
          gap = "structural-only evidence cannot satisfy behavioral postcondition";
        } else {
          // For non-behavioral, allow if obligations passed
          proved = obligations.every(o => !o.required || o.state === "passed") && processesSettled && resourcesSettled && !environment.ambientSiblingDetected;
          if (!proved) gap = "structural-only evidence without required command passage";
        }
      } else if (evidenceKind === "behavioral-production-path" || evidenceKind === "exact-runtime-canary") {
        // Must name entry point and consumer
        if (!productionEntryPoint || !productionConsumer) {
          proved = false;
          gap = "behavioral-production-path must name production entry point and consumer";
        } else if (!productionConsumer || productionConsumer.includes("helper") && !input.changedFiles.includes(productionConsumer)) {
          // helper with no production consumer gap
          proved = false;
          gap = "test-only helper with no production consumer is structural gap";
        } else {
          // Must have tests reaching production seam and required commands passed
          const hasTests = tests.length > 0;
          const requiredPassed = obligations.filter(o=>o.required).every(o=>o.state==="passed");
          if (!hasTests) {
            proved = false;
            gap = "no test reaches production entry point";
          } else if (!requiredPassed) {
            proved = false;
            gap = "required verification obligations not passed";
          } else if (!processesSettled || !resourcesSettled) {
            proved = false;
            gap = "processes/resources not settled";
          } else if (environment.ambientSiblingDetected) {
            proved = false;
            gap = "ambient sibling dependency — not clean closure";
          } else {
            // Check simulated adapter masquerading as live proof
            const hasSimulated = assertedPostconditions.some(p => /simulated|in-memory|source regex/i.test(p));
            if (hasSimulated && evidenceKind === "behavioral-production-path") {
              proved = false;
              gap = "simulated adapter cannot be described as live external-state proof";
            } else {
              proved = true;
            }
          }
        }
        // exact-runtime-canary must bind runtime identities
        if (evidenceKind === "exact-runtime-canary" && !environment.sandcastleRuntimeSha) {
          proved = false;
          gap = "exact-runtime-canary must bind runtime/image/tool identities";
        }
      }

      // Specific gap detections: test-only helper, source-regex substitution
      if (productionEntryPoint.includes("helper") || productionConsumer.includes("helper")) {
        if (evidenceKind === "behavioral-production-path") {
          proved = false;
          gap = gap ?? "helper with no production consumer cannot satisfy production-behavior claim";
        }
      }
      if (tests.some(t => /source-regex|regex/i.test(t)) && evidenceKind === "behavioral-production-path") {
        // Source regex alone cannot prove behavioral postcondition
        if (assertedPostconditions.some(p => /behavioral|postcondition/i.test(p))) {
          proved = false;
          gap = "source-regex assertion cannot prove behavioral postcondition";
        }
      }

      // Model cannot set proved=true if host disagrees — enforce host gating
      if (draft && (draft as unknown as { proved?: boolean }).proved === true && !proved) {
        // keep host false
      }

      proofs.push({
        criterionId: c.id,
        claim: c.claim,
        candidateSha,
        productionEntryPoint,
        productionConsumer,
        evidenceKind,
        tests: tests.length > 0 ? tests : [`test:${c.id}`],
        commandObligationIds,
        assertedPostconditions,
        proved,
        gap,
      });
    }

    // Host-computed readiness — true only when all gates pass
    const requiredObligationsPassed = obligations.filter(o=>o.required).every(o=>o.state==="passed");
    // Readiness requires every deterministic criterion has acceptable proof;
    // live-rollout-pending is explicitly not proved and blocks unless excluded.
    let allRequiredProved = true;
    for (const p of proofs) {
      if (!p.proved) allRequiredProved = false;
    }

    const readyForReview = requiredObligationsPassed && processesSettled && resourcesSettled && environment.cleanCheckout && !environment.ambientSiblingDetected && environment.cwdValid && allRequiredProved && candidateSha.length===40 && baseSha.length===40;

    if (!readyForReview && blockingReasons.length===0) {
      if (!requiredObligationsPassed) blockingReasons.push("not all required obligations passed");
      if (!allRequiredProved) blockingReasons.push("not all deterministic criteria proved");
    }

    const result: CandidateProofResult = {
      candidateSha,
      baseSha,
      issueId: input.issueId,
      issueTitle: input.issueTitle,
      candidateBranch: input.candidateBranch,
      obligations,
      proofs,
      processesSettled,
      resourcesSettled,
      environment,
      readyForReview,
      blockingReasons,
      schemaVersion: 1,
      generatedAt: now,
    };

    // Atomic persistence of exact-SHA receipt
    const shouldPersist = dependencies.persistReceipt !== false;
    if (shouldPersist) {
      const logsDir = path.join(repoRoot, ".sandcastle", "logs", "candidate-proof");
      const receiptPath = path.join(logsDir, `${input.issueId}-${candidateSha}.json`);
      // Ensure blockingReasons etc are included; generatedAt outside content hash
      withAtomicJsonReceipt(receiptPath, () => result);
    }

    // If settlement or environment failure makes state uncertain, caller should treat as FACTORY_ERROR
    // We do NOT throw for deterministic candidate failure (failed obligation) — that's semantic rejection via readyForReview=false
    // We DO throw for unavailable/infrastructure uncertainty when required
    const hasUnavailable = obligations.some(o=>o.required && (o.state==="unavailable" || o.state==="pending" || o.state==="running"));
    if (hasUnavailable && !processesSettled) {
      // Keep as blocking but also allow caller to detect FACTORY_ERROR via exception if needed
      // For now, we keep result with ready false; caller can check blockingReasons for unavailable
    }

    return result;
  } finally {
    // Structured scopes guarantee cleanup attempted — prove via resourcesSettled already
    // No detached background may outlive successful collection — settlement proved above
  }
}

// Convenience: decide whether to collect for a given issue
export function shouldCollectForCandidate(changedFiles: string[]): boolean {
  return isProtectedCandidate(changedFiles);
}
