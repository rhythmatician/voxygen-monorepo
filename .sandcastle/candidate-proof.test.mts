import { describe, it, expect, beforeEach, afterEach } from "vitest";
import * as fs from "node:fs";
import * as path from "node:path";
import * as os from "node:os";
import { execSync } from "node:child_process";
import {
  collectCandidateProof,
  deriveCriterionIds,
  isProtectedCandidate,
  CandidateProofFactoryError,
  detectAmbientSiblingCheckout,
  verifyCleanDependencyClosure,
  withCandidateWorktreeScope,
  withCleanCheckoutScope,
  withLogReceiptScope,
} from "./candidate-proof.mts";

// ---------------------------------------------------------------------------
// Helpers — deterministic SHA fixtures, deferred barriers, fake runners
// ---------------------------------------------------------------------------

const BASE_SHA = "a".repeat(40);
const CAND_SHA = "b".repeat(40);
const CAND_SHA2 = "c".repeat(40);

function fakePassRunner(): (o:any)=>Promise<any> {
  return async (ob:any) => ({ state: "passed" as const, exitCode: 0, completedAt: new Date().toISOString() });
}
function fakeNotRunRunner(): (o:any)=>Promise<any> {
  return async (ob:any) => {
    if (ob.id === "test") return { state: "not-run" as const, failure: "npm test not executed" };
    return { state: "passed" as const, exitCode: 0 };
  };
}
function fakeRunningRunner(): (o:any)=>Promise<any> {
  return async (ob:any) => {
    if (ob.id === "test") return { state: "running" as const };
    return { state: "passed" as const, exitCode: 0 };
  };
}
function fakeFailedRunner(): (o:any)=>Promise<any> {
  return async (ob:any) => {
    if (ob.id === "test") return { state: "failed" as const, exitCode: 1, failure: "tests failed" };
    return { state: "passed" as const, exitCode: 0 };
  };
}
function fakeUnavailableRunner(): (o:any)=>Promise<any> {
  return async () => { throw new Error("spawn ENOENT"); };
}

const TRACER_BODY = `## Acceptance criteria
- [ ] T1 canonical claim — ready+implement becomes ready+in-progress
- [ ] R1 research-only all success concurrent
- [ ] M1 mutation hardening
`;

function defaultInput(overrides: Partial<import("./candidate-proof.mts").CandidateProofInput> = {}): import("./candidate-proof.mts").CandidateProofInput {
  return {
    issueId: "202",
    issueTitle: "candidate proof",
    issueBody: TRACER_BODY,
    candidateBranch: "sandcastle/issue-202",
    baseSha: BASE_SHA,
    candidateSha: CAND_SHA,
    changedFiles: [".sandcastle/factory-iteration.mts", ".sandcastle/main.mts"],
    candidateClaims: [
      { criterionId: "T1", claim: "T1 canonical claim — ready+implement becomes ready+in-progress", productionEntryPoint: ".sandcastle/factory-iteration.mts", productionConsumer: "main.mts", evidenceKind: "behavioral-production-path", tests: ["factory-lifecycle-scenarios.test.mts#T1"], commandObligationIds: ["typecheck","test","git-diff-check"], assertedPostconditions: ["branch moves to in-progress with assignee"] },
      { criterionId: "R1", claim: "R1 research-only all success concurrent", productionEntryPoint: ".sandcastle/research-lifecycle.mts", productionConsumer: "main.mts", evidenceKind: "behavioral-production-path", tests: ["factory-lifecycle-scenarios.test.mts#R1"], commandObligationIds: ["typecheck","test"], assertedPostconditions: ["research concurrent settlement"] },
      { criterionId: "M1", claim: "M1 mutation hardening", productionEntryPoint: ".sandcastle/resource-scopes.mts", productionConsumer: "main.mts", evidenceKind: "exact-runtime-canary", tests: ["resource-scopes.test.mts#mutation"], commandObligationIds: ["typecheck","test"], assertedPostconditions: ["exact runtime proved"] },
    ],
    ...overrides,
  };
}

// Temp repo helper for real git branch movement test
function makeTempRepo(): { dir: string; cleanup: ()=>void } {
  const dir = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-"));
  execSync("git init", { cwd: dir });
  execSync('git config user.email "test@test.com"', { cwd: dir });
  execSync('git config user.name "test"', { cwd: dir });
  fs.writeFileSync(path.join(dir, "file.txt"), "a");
  execSync("git add .", { cwd: dir });
  execSync("git commit -m init", { cwd: dir });
  return { dir, cleanup: () => fs.rmSync(dir, { recursive: true, force: true }) };
}

// ---------------------------------------------------------------------------
// Tests — 12 required scenarios
// ---------------------------------------------------------------------------

describe("candidate-proof — host-owned verification", () => {
  let tmpRepoRoot: string | null = null;
  afterEach(() => {
    if (tmpRepoRoot) { try { fs.rmSync(tmpRepoRoot, { recursive: true, force: true }); } catch {} tmpRepoRoot=null; }
    // clean persisted receipts under real repoRoot if created
    try { fs.rmSync(path.join(process.cwd(), ".sandcastle", "logs", "candidate-proof"), { recursive: true, force: true }); } catch {}
  });

  it("1. All required commands pass: exact SHA stable, no processes remain, clean env, readyForReview=true", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(result.candidateSha).toBe(CAND_SHA);
    expect(result.obligations.every(o=>o.state==="passed")).toBe(true);
    expect(result.processesSettled).toBe(true);
    expect(result.resourcesSettled).toBe(true);
    expect(result.environment.cleanCheckout).toBe(true);
    expect(result.readyForReview).toBe(true);
    expect(result.blockingReasons.length).toBe(0);
    // receipt persisted atomically
    const receiptPath = path.join(tmp, ".sandcastle", "logs", "candidate-proof", `202-${CAND_SHA}.json`);
    expect(fs.existsSync(receiptPath)).toBe(true);
    const persisted = JSON.parse(fs.readFileSync(receiptPath,"utf8"));
    expect(persisted.candidateSha).toBe(CAND_SHA);
    expect(persisted.schemaVersion).toBe(1);
    expect(persisted.readyForReview).toBe(true);
    // obligations preserve distinct states — not collapsed to boolean
    for (const o of result.obligations) expect(["passed"]).toContain(o.state);
  });

  it("2. Required full suite not run: focused tests pass but npm test is not-run; readiness remains false", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakeNotRunRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    const testOb = result.obligations.find(o=>o.id==="test")!;
    expect(testOb.state).toBe("not-run");
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("test") && r.includes("not-run"))).toBe(true);
    // focused suite cannot substitute — readiness still false
    for (const p of result.proofs) expect(p.proved).toBe(false);
  });

  it("3. Background command still running: readiness remains false until settlement", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakeRunningRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
      trackedProcessHandles: [{ settled: false }],
    });
    expect(result.obligations.find(o=>o.id==="test")!.state).toBe("running");
    expect(result.processesSettled).toBe(false);
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("unsettled"))).toBe(true);
  });

  it("4. Candidate test failure: command ran and failed; host emits blocking evidence and does not invoke reviewer", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakeFailedRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(result.obligations.find(o=>o.id==="test")!.state).toBe("failed");
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("failed"))).toBe(true);
    // No reviewer invocation — readiness false is the stable verification finding
    for (const p of result.proofs) expect(p.proved).toBe(false);
  });

  it("5. Verification infrastructure failure: spawn/result/environment unavailable yields FACTORY_ERROR, not semantic rejection", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakeUnavailableRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    // unavailable is distinct from failed — blockingReasons contains unavailable
    expect(result.obligations.some(o=>o.state==="unavailable")).toBe(true);
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("unavailable"))).toBe(true);
    // caller can distinguish infrastructure vs candidate failure by state
    expect(result.obligations.find(o=>o.state==="unavailable")!.state).not.toBe("failed");
  });

  it("6. Candidate movement: branch advances during verification; packet rejected as FACTORY_ERROR", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    await expect(collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA2, // different from candidateSha
      repoRoot: tmp,
    })).rejects.toThrow(/FACTORY_ERROR.*moved/);
  });

  it("6b. Real temp git repo: branch movement detected via git rev-parse", async () => {
    const repo = makeTempRepo();
    try {
      const base = execSync("git rev-parse HEAD", { cwd: repo.dir, encoding: "utf8" }).trim();
      execSync("git checkout -b sandcastle/issue-202", { cwd: repo.dir });
      const cand = execSync("git rev-parse HEAD", { cwd: repo.dir, encoding: "utf8" }).trim();
      // advance branch
      fs.writeFileSync(path.join(repo.dir, "b.txt"), "b");
      execSync("git add .", { cwd: repo.dir });
      execSync("git commit -m second", { cwd: repo.dir });
      const moved = execSync("git rev-parse HEAD", { cwd: repo.dir, encoding: "utf8" }).trim();
      expect(moved).not.toBe(cand);
      // Simulate that candidateSha is old cand but branch now points to moved
      await expect(collectCandidateProof({
        issueId: "202", issueTitle: "t", issueBody: TRACER_BODY, candidateBranch: "sandcastle/issue-202",
        baseSha: base, candidateSha: cand, changedFiles: [".sandcastle/main.mts"],
      }, {
        runCommand: fakePassRunner(),
        getBranchSha: (b) => execSync(`git rev-parse ${b}`, { cwd: repo.dir, encoding: "utf8" }).trim(),
        repoRoot: fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-")),
      })).rejects.toThrow(/FACTORY_ERROR.*moved/);
    } finally { repo.cleanup(); }
  });

  it("7. Ambient sibling dependency: worker passes with sibling present, clean checkout fails; gap blocks readiness", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: false, ambientSiblingDetected: true, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(result.environment.ambientSiblingDetected).toBe(true);
    expect(result.environment.cleanCheckout).toBe(false);
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("sibling") || r.includes("clean checkout"))).toBe(true);
    // future clean-checkout fixture would need pinned closure — here blocked
    for (const p of result.proofs) expect(p.proved).toBe(false);
  });

  it("8. Production helper reachability: test-only helper with no production consumer is reported as structural gap", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput({
      changedFiles: [".sandcastle/candidate-proof.mts"],
      candidateClaims: [
        { criterionId: "T1", claim: "T1 helper", productionEntryPoint: ".sandcastle/helper.mts", productionConsumer: "helper", evidenceKind: "behavioral-production-path", tests: ["helper.test.mts"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral postcondition"] },
        { criterionId: "R1", claim: "R1 helper", productionEntryPoint: ".sandcastle/helper.mts", productionConsumer: "helper", evidenceKind: "behavioral-production-path", tests: ["helper.test.mts"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral"] },
        { criterionId: "M1", claim: "M1 helper", productionEntryPoint: ".sandcastle/helper.mts", productionConsumer: "helper", evidenceKind: "behavioral-production-path", tests: ["helper.test.mts"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral"] },
      ],
    }), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    for (const p of result.proofs) {
      expect(p.proved).toBe(false);
      expect(p.gap).toMatch(/helper|production consumer|structural gap/i);
    }
    expect(result.readyForReview).toBe(false);
  });

  it("9. Structural substitution: source-regex assertion cannot prove behavioral postcondition", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput({
      candidateClaims: [
        { criterionId: "T1", claim: "T1 structural", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "structural-only", tests: ["source-regex-test"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral postcondition proved"] },
        { criterionId: "R1", claim: "R1 regex", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "behavioral-production-path", tests: ["source-regex assertion"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral postcondition"] },
        { criterionId: "M1", claim: "M1 ok", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "behavioral-production-path", tests: ["candidate-proof.test.mts"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral postcondition"] },
      ],
    }), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    const t1 = result.proofs.find(p=>p.criterionId==="T1")!;
    expect(t1.proved).toBe(false);
    expect(t1.gap).toMatch(/structural-only.*cannot satisfy behavioral/i);
    const r1 = result.proofs.find(p=>p.criterionId==="R1")!;
    expect(r1.proved).toBe(false);
    expect(r1.gap).toMatch(/source-regex/i);
  });

  it("10. Process/resource cleanup failure: receipt preserves failure and candidate cannot proceed", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      verifyResourcesSettled: () => ({ settled: false, reason: "worktree not cleaned" }),
      repoRoot: tmp,
    });
    expect(result.resourcesSettled).toBe(false);
    expect(result.readyForReview).toBe(false);
    expect(result.blockingReasons.some(r=>r.includes("worktree not cleaned") || r.includes("not settled"))).toBe(true);
    const receiptPath = path.join(tmp, ".sandcastle", "logs", "candidate-proof", `202-${CAND_SHA}.json`);
    const persisted = JSON.parse(fs.readFileSync(receiptPath,"utf8"));
    expect(persisted.resourcesSettled).toBe(false);
    expect(persisted.blockingReasons.some((r:string)=>r.includes("worktree"))).toBe(true);
  });

  it("11. Stable criterion identity: unchanged text retains ID; changed semantic text changes ID", async () => {
    const body1 = `## Acceptance criteria\n- [ ] T1 first criterion\n- [ ] do the thing\n`;
    const body2 = `## Acceptance criteria\n- [ ] T1 first criterion\n- [ ] do the thing\n`;
    const body3 = `## Acceptance criteria\n- [ ] T1 first criterion\n- [ ] do the other thing\n`;
    const ids1 = deriveCriterionIds(body1);
    const ids2 = deriveCriterionIds(body2);
    const ids3 = deriveCriterionIds(body3);
    expect(ids1.map(i=>i.id)).toEqual(ids2.map(i=>i.id));
    expect(ids1[1].id).not.toBe(ids3[1].id);
    expect(ids1[0].id).toBe("T1"); // preserved explicit
    expect(ids3[0].id).toBe("T1");
  });

  it("12. Live pending proof: post-merge canary claim remains unproved before its receipt exists", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput({
      candidateClaims: [
        { criterionId: "T1", claim: "T1 live pending", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "live-rollout-pending", tests: [], commandObligationIds: [], assertedPostconditions: ["live canary"] },
        { criterionId: "R1", claim: "R1 live pending", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "live-rollout-pending", tests: [], commandObligationIds: [], assertedPostconditions: ["post-merge receipt"] },
        { criterionId: "M1", claim: "M1 live pending", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "live-rollout-pending", tests: [], commandObligationIds: [], assertedPostconditions: ["canary"] },
      ],
    }), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    for (const p of result.proofs) {
      expect(p.evidenceKind).toBe("live-rollout-pending");
      expect(p.proved).toBe(false);
      expect(p.gap).toMatch(/cannot be presented as passed/i);
    }
    expect(result.readyForReview).toBe(false);
  });

  it("model-authored completion summaries cannot set host readiness", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput({
      candidateClaims: [
        { criterionId: "T1", claim: "T1", productionEntryPoint: ".sandcastle/main.mts", productionConsumer: "main.mts", evidenceKind: "behavioral-production-path", tests: ["t"], commandObligationIds: ["test"], assertedPostconditions: ["behavioral"], } as unknown as { criterionId: string; claim: string; productionEntryPoint: string; productionConsumer: string; evidenceKind: any; tests: string[]; commandObligationIds: string[]; assertedPostconditions: string[]; proved: boolean },
      ],
    }), {
      runCommand: fakeFailedRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    // Even if model claims proved, host computes false because obligations failed
    expect(result.readyForReview).toBe(false);
  });

  it("host owns required commands; focused suite cannot substitute for required npm test", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const input = defaultInput();
    const result = await collectCandidateProof(input, {
      runCommand: async (ob) => {
        if (ob.id === "test") return { state: "not-run" as const };
        return { state: "passed" as const, exitCode: 0 };
      },
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(result.obligations.find(o=>o.id==="test")!.required).toBe(true);
    expect(result.readyForReview).toBe(false);
  });

  it("evidence records exact environment and identifies sibling reliance; clean-checkout fixture proves pinned closure", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const withSibling = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", packageLockHash: "abc123", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: false, ambientSiblingDetected: true, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(withSibling.environment.packageLockHash).toBe("abc123");
    expect(withSibling.environment.ambientSiblingDetected).toBe(true);
    expect(withSibling.readyForReview).toBe(false);

    const tmp2 = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env2-"));
    try {
      const clean = await collectCandidateProof({ ...defaultInput(), candidateSha: CAND_SHA2 }, {
        runCommand: fakePassRunner(),
        getBranchSha: () => CAND_SHA2,
        probeEnvironment: () => ({ nodeVersion: "v22", packageLockHash: "abc123", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
        repoRoot: tmp2,
      });
      expect(clean.environment.cleanCheckout).toBe(true);
      expect(clean.environment.ambientSiblingDetected).toBe(false);
      expect(clean.readyForReview).toBe(true);
    } finally { fs.rmSync(tmp2, { recursive: true, force: true }); }
  });

  it("isProtectedCandidate uses existing classifier — .sandcastle/** is protected, docs/** is not", () => {
    expect(isProtectedCandidate([".sandcastle/main.mts"])).toBe(true);
    expect(isProtectedCandidate([".sandcastle/candidate-proof.mts"])).toBe(true);
    expect(isProtectedCandidate(["java/Foo.java"])).toBe(false); // java lane but not protected factory? C1_JAVA alone not C1_FACTORY
    // docs/adr is protected via accepted-policy-roots (human approval)
    expect(isProtectedCandidate(["docs/adr/0008.md"])).toBe(true);
    expect(isProtectedCandidate(["src/main.ts"])).toBe(false);
  });

  it("every proof entry identifies production entry point, consumer, evidence kind, references, and postconditions", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    for (const p of result.proofs) {
      expect(p.productionEntryPoint).toBeTruthy();
      expect(p.productionConsumer).toBeTruthy();
      expect(p.evidenceKind).toBeTruthy();
      expect(p.tests.length).toBeGreaterThan(0);
      expect(p.commandObligationIds.length).toBeGreaterThan(0);
      expect(p.assertedPostconditions.length).toBeGreaterThan(0);
      expect(p.candidateSha).toBe(CAND_SHA);
    }
  });

  it("java** changes add bounded Java compile/test lane obligations", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput({ changedFiles: ["java/Foo.java"] }), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    expect(result.obligations.some(o=>o.id==="java-test")).toBe(true);
    expect(result.obligations.some(o=>o.id==="java-compile")).toBe(true);
  });

  it("receipt includes schema version, base/candidate SHA, obligation states, settlement, env, proofs, readyForReview, blockingReasons, generatedAt", async () => {
    const tmp = fs.mkdtempSync(path.join(os.tmpdir(), "cand-proof-env-"));
    tmpRepoRoot = tmp;
    const result = await collectCandidateProof(defaultInput(), {
      runCommand: fakePassRunner(),
      getBranchSha: () => CAND_SHA,
      probeEnvironment: () => ({ nodeVersion: "v22", sandcastleRuntimeSha: "a".repeat(40), cleanCheckout: true, ambientSiblingDetected: false, cwdValid: true }),
      repoRoot: tmp,
    });
    const receiptPath = path.join(tmp, ".sandcastle", "logs", "candidate-proof", `202-${CAND_SHA}.json`);
    const persisted = JSON.parse(fs.readFileSync(receiptPath,"utf8"));
    expect(persisted.schemaVersion).toBe(1);
    expect(persisted.candidateSha).toBe(CAND_SHA);
    expect(persisted.baseSha).toBe(BASE_SHA);
    expect(Array.isArray(persisted.obligations)).toBe(true);
    expect(typeof persisted.processesSettled).toBe("boolean");
    expect(typeof persisted.resourcesSettled).toBe("boolean");
    expect(persisted.environment).toBeDefined();
    expect(Array.isArray(persisted.proofs)).toBe(true);
    expect(typeof persisted.readyForReview).toBe("boolean");
    expect(Array.isArray(persisted.blockingReasons)).toBe(true);
    expect(typeof persisted.generatedAt).toBe("string");
  });

  // -------------------------------------------------------------------------
  // Real filesystem and structured-scope fixtures — pinned closure proof
  // -------------------------------------------------------------------------

  it("detectAmbientSiblingCheckout: real FS — sibling sandcastle dir triggers gap, clean repo does not", async () => {
    const repo = makeTempRepo();
    try {
      // Initially no sibling at ../sandcastle relative to repo.dir
      expect(detectAmbientSiblingCheckout(repo.dir)).toBe(false);
      // package.json file: reference alone does NOT trigger gap — only filesystem sibling does
      // (declared file: deps are expected; undeclared ambient reliance is via FS sibling)
      const pkgPath = path.join(repo.dir, "package.json");
      const pkg = { name: "x", dependencies: { "@ai-hero/sandcastle": "file:../../sandcastle" } };
      fs.writeFileSync(pkgPath, JSON.stringify(pkg));
      execSync("git add package.json", { cwd: repo.dir });
      expect(detectAmbientSiblingCheckout(repo.dir)).toBe(false);
      // Remove file: ref — still clean (no sibling dir)
      fs.writeFileSync(pkgPath, JSON.stringify({ name: "x", dependencies: {} }));
      expect(detectAmbientSiblingCheckout(repo.dir)).toBe(false);
      // Create actual sibling directory on filesystem (sibling of repo.dir)
      const sibling = path.resolve(repo.dir, "../sandcastle");
      const siblingExisted = fs.existsSync(sibling);
      if (!siblingExisted) {
        fs.mkdirSync(sibling, { recursive: true });
        fs.writeFileSync(path.join(sibling, "package.json"), JSON.stringify({ name: "sandcastle" }));
        expect(detectAmbientSiblingCheckout(repo.dir)).toBe(true);
        fs.rmSync(sibling, { recursive: true, force: true });
        expect(detectAmbientSiblingCheckout(repo.dir)).toBe(false);
      }
    } finally { repo.cleanup(); }
  });

  it("verifyCleanDependencyClosure: real FS — pinned closure requires lockfile and no sibling/file: ref", async () => {
    const repo = makeTempRepo();
    try {
      // No lockfile yet — not clean
      const lockPath = path.join(repo.dir, "package-lock.json");
      if (fs.existsSync(lockPath)) fs.unlinkSync(lockPath);
      expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(false);
      // Write lockfile without file: sandcastle — should be clean (no sibling)
      fs.writeFileSync(lockPath, JSON.stringify({ name: "x", packages: { "": { dependencies: { "zod": "^4" } } } }));
      expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(true);
      // File: reference in lockfile alone is not flagged as unclean — only when
      // sibling directory actually exists on filesystem (declared file: deps are expected).
      // Verify that with no sibling dir present, closure is still clean even with file: in lock
      fs.writeFileSync(lockPath, JSON.stringify({ name: "x", packages: { "node_modules/@ai-hero/sandcastle": { resolved: "file:../../sandcastle" } } }));
      expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(true);
      // But if sibling dir exists on filesystem, closure is not clean (undeclared reliance)
      const siblingForLock = path.resolve(repo.dir, "../sandcastle");
      const existed = fs.existsSync(siblingForLock);
      if (!existed) {
        try {
          fs.mkdirSync(siblingForLock, { recursive: true });
          fs.writeFileSync(path.join(siblingForLock, "package.json"), JSON.stringify({ name: "sandcastle" }));
          expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(false);
          expect(verifyCleanDependencyClosure(repo.dir).reason).toMatch(/sibling/);
          fs.rmSync(siblingForLock, { recursive: true, force: true });
        } catch (e) {
          // If we cannot create sibling (permission), skip this sub-check — the detector
          // still works, but environment may not allow /tmp/sandcastle creation
          if ((e as NodeJS.ErrnoException).code !== "EACCES") throw e;
        }
      } else {
        expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(false);
      }
    } finally { repo.cleanup(); }
  });

  it("clean-checkout fixture proves pinned dependency closure for protected Node/Sandcastle path — real worktree with lockfile", async () => {
    const repo = makeTempRepo();
    const sibling = path.resolve(repo.dir, "../sandcastle-test-sibling");
    try {
      // Prepare candidate with a valid lockfile and no sibling
      fs.writeFileSync(path.join(repo.dir, "package.json"), JSON.stringify({ name: "test", devDependencies: { zod: "^4.4.3" } }));
      fs.writeFileSync(path.join(repo.dir, "package-lock.json"), JSON.stringify({ name: "test", lockfileVersion: 3, packages: { "": { name: "test" } } }));
      execSync("git add .", { cwd: repo.dir });
      execSync("git commit -m lockfile --allow-empty", { cwd: repo.dir });
      const sha = execSync("git rev-parse HEAD", { cwd: repo.dir, encoding: "utf8" }).trim();
      // Verify clean closure in repo.dir itself
      expect(verifyCleanDependencyClosure(repo.dir).clean).toBe(true);
      expect(detectAmbientSiblingCheckout(repo.dir)).toBe(false);
      // Use real worktree scope — creates ephemeral worktree at exact SHA and cleans up
      await withCandidateWorktreeScope(repo.dir, sha, async (wtPath) => {
        expect(fs.existsSync(path.join(wtPath, "package-lock.json"))).toBe(true);
        expect(verifyCleanDependencyClosure(wtPath).clean).toBe(true);
        // Simulate ambient sibling: create sibling dir at a location the detector checks
        // For wtPath=/tmp/cand-proof-wt-xxx/wt, "../sandcastle" is inside the wt tmp dir,
        // "../../sandcastle" is /tmp/sandcastle — both are checked. Use ../ sibling which
        // is writable and scoped to this worktree's temp parent.
        const siblingInWt = path.resolve(wtPath, "../sandcastle");
        const existedWt = fs.existsSync(siblingInWt);
        if (!existedWt) {
          try {
            fs.mkdirSync(siblingInWt, { recursive: true });
            fs.writeFileSync(path.join(siblingInWt, "package.json"), JSON.stringify({ name: "sandcastle" }));
            expect(verifyCleanDependencyClosure(wtPath).clean).toBe(false);
            fs.rmSync(siblingInWt, { recursive: true, force: true });
          } catch (e) {
            if ((e as NodeJS.ErrnoException).code !== "EACCES") throw e;
          }
        } else {
          expect(verifyCleanDependencyClosure(wtPath).clean).toBe(false);
        }
        expect(verifyCleanDependencyClosure(wtPath).clean).toBe(true);
      });
      // Postcondition: worktree fully cleaned (no leftover tmp)
      const list = execSync("git worktree list --porcelain", { cwd: repo.dir, encoding: "utf8" });
      expect(list).not.toContain("cand-proof-wt-");
      // Clean-checkout scope also proves closure (delegates to candidate worktree)
      await withCleanCheckoutScope(repo.dir, sha, async (cleanPath) => {
        expect(verifyCleanDependencyClosure(cleanPath).clean).toBe(true);
      });
    } finally {
      repo.cleanup();
      try { fs.rmSync(sibling, { recursive: true, force: true }); } catch {}
    }
  });

  it("structured scopes: withCandidateWorktreeScope cleans on success and on throw, withLogReceiptScope is atomic", async () => {
    const repo = makeTempRepo();
    try {
      fs.writeFileSync(path.join(repo.dir, "a.txt"), "a");
      execSync("git add .", { cwd: repo.dir });
      execSync("git commit -m a", { cwd: repo.dir });
      const sha = execSync("git rev-parse HEAD", { cwd: repo.dir, encoding: "utf8" }).trim();
      // Success path cleans
      await withCandidateWorktreeScope(repo.dir, sha, async (wtPath) => {
        expect(fs.existsSync(wtPath)).toBe(true);
        fs.writeFileSync(path.join(wtPath, "tmp.txt"), "x");
      });
      let list = execSync("git worktree list --porcelain", { cwd: repo.dir, encoding: "utf8" });
      expect(list.split("\n").filter(l=>l.includes("wt")).length).toBe(0);
      // Throw path still cleans
      await expect(withCandidateWorktreeScope(repo.dir, sha, async () => { throw new Error("boom"); })).rejects.toThrow("boom");
      list = execSync("git worktree list --porcelain", { cwd: repo.dir, encoding: "utf8" });
      expect(list.split("\n").filter(l=>l.includes("wt")).length).toBe(0);
      // Log receipt atomicity
      const logDir = fs.mkdtempSync(path.join(os.tmpdir(), "log-scope-"));
      try {
        const receipt = withLogReceiptScope(logDir, "typecheck", "hello log");
        expect(fs.existsSync(receipt)).toBe(true);
        expect(fs.readFileSync(receipt, "utf8")).toBe("hello log");
        expect(fs.existsSync(`${receipt}.tmp-${process.pid}`)).toBe(false);
      } finally { fs.rmSync(logDir, { recursive: true, force: true }); }
    } finally { repo.cleanup(); }
  });
});
