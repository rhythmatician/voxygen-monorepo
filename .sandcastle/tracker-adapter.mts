import type { IssueInput } from "./tracker-policy.mts";
import {
  READY_FOR_AGENT,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
} from "./tracker-policy.mts";
import {
  claimImplementation,
  claimResearch,
  cleanupClosedIssue,
  reconcileStaleImplementation,
  type ClaimResult,
  type ClaimOps,
  type ResearchClaimOps,
  type ReconcileResult,
  type ReconcileGitHubTransitions,
} from "./tracker-operations.mts";
import { createProductionReconcileOps } from "./reconcile-adapter.mts";
import type { GhTransport } from "./gh-transport.mts";

/**
 * tracker-adapter — the single adapter constructing ALL production tracker
 * operations. Consumers (main.mts, migration, canary) call named transitions
 * here; they never compose `gh issue edit` calls or know machine-state label
 * strings inline.
 *
 * Claims DELEGATE to the canonical production policy (tracker-operations
 * claimImplementation/claimResearch) — no second eligibility implementation
 * lives here. Fresh reads fetch native blocked_by so eligibility is complete.
 *
 * Outcome transitions run through one verified-saga sequencing primitive:
 *
 *   fresh read → validate before-state → mutate → fresh read →
 *   verify postcondition → compensate when defined → verify compensation
 *   postcondition → persist typed receipt (persistence failure = indeterminate)
 *
 * Typed result: committed | rejected | compensated | indeterminate(factoryError).
 * NOT a generic workflow engine. NOT an auto-releasing withClaim() scope.
 */

// ---------------------------------------------------------------------------
// Types
// ---------------------------------------------------------------------------

export interface IssueSnapshot {
  number: number;
  labels: string[];
  assignees: string[];
  state: "open" | "closed";
}

export interface TransitionReceipt {
  transition: string;
  issueNumber: number;
  at: string;
  kind: "committed" | "rejected" | "compensated" | "indeterminate";
  reason?: string;
  code?: string;
  before?: IssueSnapshot;
  after?: IssueSnapshot;
  lastObserved?: IssueSnapshot | null;
}

export type TrackerTransitionResult =
  | { kind: "committed"; before: IssueSnapshot; after: IssueSnapshot; receipt: TransitionReceipt }
  /** Invalid precondition — no mutation attempted, nothing to compensate. */
  | { kind: "rejected"; before: IssueSnapshot; after: IssueSnapshot; receipt: TransitionReceipt }
  | { kind: "compensated"; before: IssueSnapshot; after: IssueSnapshot; receipt: TransitionReceipt }
  | { kind: "indeterminate"; lastObserved: IssueSnapshot | null; receipt: TransitionReceipt; factoryError: true };

export interface ReceiptSink {
  /**
   * Persist a receipt. Throw on failure — the saga converts an unpersistable
   * required receipt into indeterminate FACTORY_ERROR rather than reporting
   * committed without durable evidence.
   */
  persist(receipt: TransitionReceipt): void;
}

/** In-memory sink for tests; production passes a file-backed sink. */
export function makeMemoryReceiptSink(): ReceiptSink & { receipts: TransitionReceipt[] } {
  const receipts: TransitionReceipt[] = [];
  return { receipts, persist(r) { receipts.push(r); } };
}

/**
 * Apply a combined add/remove label mutation to an open issue through the
 * adapter's verified saga: fresh read → mutate → fresh read → prove the exact
 * final label set → persist typed receipt. Used by the migration consumer so
 * no issue mutation bypasses the single tracker authority.
 */
export function makeIssueLabelMutationPort(
  gh: GhTransport,
  sink: ReceiptSink,
): (issueNumber: number, addLabels: string[], removeLabels: string[]) => Promise<{ committed: boolean; reason?: string }> {
  return async (issueNumber, addLabels, removeLabels) => {
    const args: string[] = [];
    for (const a of addLabels) args.push("--add-label", a);
    for (const r of removeLabels) args.push("--remove-label", r);
    if (args.length === 0) return { committed: true };
    const result = await runSaga(gh, issueNumber, "migrationLabelMutation", [
      {
        name: "apply-label-mutation",
        mutate: () => runEdit(gh, issueNumber, args),
        verifyAfter: (after) => {
          for (const l of addLabels) if (!after.labels.includes(l)) return `${l} absent after mutation`;
          for (const l of removeLabels) if (after.labels.includes(l)) return `${l} still present after mutation`;
          return null;
        },
      },
    ], sink);
    if (result.kind === "committed") return { committed: true };
    return { committed: false, reason: result.receipt.reason ?? result.receipt.code };
  };
}

async function runEdit(gh: GhTransport, issueNumber: number, args: string[]): Promise<void> {
  await gh.run(["issue", "edit", String(issueNumber), ...args]);
}

// ---------------------------------------------------------------------------
// Saga primitive
// ---------------------------------------------------------------------------

interface SagaStep {
  name: string;
  /**
   * Validate the fresh before-state; return error code when invalid (no
   * mutation attempted). Invalid preconditions produce kind:"rejected".
   */
  validateBefore?: (before: IssueInput) => string | null;
  /** The single mutation. */
  mutate: () => Promise<void>;
  /** Verify postcondition against a fresh read taken after mutation. Return null when satisfied. */
  verifyAfter: (after: IssueInput) => string | null;
  /**
   * Compensation attempt. When undefined, a failed step is reported
   * indeterminate directly (unsafe to restore).
   */
  compensate?: () => Promise<boolean>;
  /**
   * Compensation postcondition: prove the safe state was actually reached on a
   * fresh read taken after compensation. Return null when proven safe;
   * a violation means indeterminate, never "compensated".
   */
  verifyCompensation?: (afterCompensation: IssueInput) => string | null;
}

function snapshot(issue: IssueInput): IssueSnapshot {
  return {
    number: issue.number,
    labels: [...issue.labels],
    assignees: [...issue.assignees],
    state: issue.state,
  };
}

/**
 * Fresh read including native blocked_by — eligibility gates require known
 * dependency state (fail-closed when unknown), so every saga boundary read
 * must carry it.
 */
async function fetchFresh(gh: GhTransport, issueNumber: number): Promise<IssueInput> {
  const rawJson = await gh.run([
    "issue", "view", String(issueNumber), "--json", "number,title,body,labels,assignees,state",
  ]);
  let raw: any;
  try { raw = JSON.parse(rawJson); } catch { throw new Error(`failed to parse issue view for #${issueNumber}`); }
  const ownerRepo = gh.resolveOwnerRepo();
  let blockedByCount: number | undefined = undefined;
  if (ownerRepo) {
    try {
      const summary = await gh.run(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${issueNumber}`, "--jq", ".issue_dependencies_summary.blocked_by"]);
      const n = parseInt(summary.trim(), 10);
      if (!isNaN(n)) blockedByCount = n;
    } catch {
      // Unknown dependency state stays undefined — policy fails closed on it.
      blockedByCount = undefined;
    }
  }
  return {
    number: raw.number,
    title: raw.title,
    state: (raw.state?.toLowerCase() ?? "open") as "open" | "closed",
    labels: (raw.labels ?? []).map((l: any) => l.name),
    assignees: (raw.assignees ?? []).map((a: any) => a.login),
    blockedByCount,
    body: raw.body,
  };
}

/**
 * One shared sequencing primitive for every tracker transition. Enforces:
 * fresh state reads at each boundary, exact claimant identity via transport,
 * unknown-versus-absent separation, compensation rules, postcondition proof,
 * primary/recovery error separation, and typed receipt persistence.
 */
async function runSaga(
  gh: GhTransport,
  issueNumber: number,
  transition: string,
  steps: SagaStep[],
  sink: ReceiptSink,
): Promise<TrackerTransitionResult> {
  let beforeSnap: IssueSnapshot | null = null;

  for (const step of steps) {
    let fresh: IssueInput;
    try {
      fresh = await fetchFresh(gh, issueNumber);
    } catch (e) {
      const reason = `fresh read failed for #${issueNumber} (${step.name}): ${getMsg(e)}`;
      const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
      return { kind: "indeterminate", lastObserved: beforeSnap, receipt: outcome.receipt, factoryError: true };
    }
    if (!beforeSnap) beforeSnap = snapshot(fresh);

    if (step.validateBefore) {
      const problem = step.validateBefore(fresh);
      if (problem) {
        const afterSnap = snapshot(fresh);
        const outcome = persistReceipt(sink, {
          transition, issueNumber, kind: "rejected", reason: problem, code: "PRECONDITION_FAILED",
          before: beforeSnap, after: afterSnap,
        });
        if (outcome.persistFailed) {
          return { kind: "indeterminate", lastObserved: afterSnap, receipt: outcome.receipt, factoryError: true };
        }
        return { kind: "rejected", before: beforeSnap, after: afterSnap, receipt: outcome.receipt };
      }
    }

    try {
      await step.mutate();
    } catch (e) {
      return finishFailure(gh, issueNumber, transition, step, beforeSnap, snapshot(fresh), `mutation failed (${step.name}): ${getMsg(e)}`, sink);
    }

    let after: IssueInput;
    try {
      after = await fetchFresh(gh, issueNumber);
    } catch (e) {
      return finishFailure(gh, issueNumber, transition, step, beforeSnap, snapshot(fresh), `post-mutation fresh read failed (${step.name}): ${getMsg(e)}`, sink);
    }
    const violation = step.verifyAfter(after);
    if (violation) {
      return finishFailure(gh, issueNumber, transition, step, beforeSnap, snapshot(after), `postcondition failed (${step.name}): ${violation}`, sink);
    }
  }

  try {
    const final = await fetchFresh(gh, issueNumber);
    const finalSnap = snapshot(final);
    const outcome = persistReceipt(sink, {
      transition, issueNumber, kind: "committed", before: beforeSnap!, after: finalSnap,
    });
    if (outcome.persistFailed) {
      return { kind: "indeterminate", lastObserved: finalSnap, receipt: outcome.receipt, factoryError: true };
    }
    return { kind: "committed", before: beforeSnap!, after: finalSnap, receipt: outcome.receipt };
  } catch (e) {
    const reason = `final fresh read failed for #${issueNumber}: ${getMsg(e)}`;
    const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
    return { kind: "indeterminate", lastObserved: beforeSnap, receipt: outcome.receipt, factoryError: true };
  }
}

/**
 * Failure path. Critical asymmetry honored: when a step defines no
 * compensation (unsafe to restore), report indeterminate FACTORY_ERROR with
 * recovery evidence instead of reconstructing the original runnable state.
 *
 * A successful compensation invocation is NOT sufficient: the compensation
 * postcondition must be PROVEN on a fresh read. Any mismatch or unreadable
 * state is indeterminate, never "compensated".
 */
async function finishFailure(
  gh: GhTransport,
  issueNumber: number,
  transition: string,
  step: SagaStep,
  before: IssueSnapshot,
  lastObserved: IssueSnapshot,
  reason: string,
  sink: ReceiptSink,
): Promise<TrackerTransitionResult> {
  if (!step.compensate) {
    const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "UNSAFE_TO_RESTORE" });
    return { kind: "indeterminate", lastObserved, receipt: outcome.receipt, factoryError: true };
  }
  let compensated: boolean;
  try {
    compensated = await step.compensate();
  } catch {
    compensated = false;
  }
  if (!compensated) {
    const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason: `${reason}; compensation failed`, code: "COMPENSATION_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt: outcome.receipt, factoryError: true };
  }
  // Prove the compensation reached its intended safe state on a fresh read.
  try {
    const afterComp = await fetchFresh(gh, issueNumber);
    const compViolation = step.verifyCompensation
      ? step.verifyCompensation(afterComp)
      : null;
    if (compViolation) {
      const r2 = `${reason}; compensation applied but safe state not proven: ${compViolation}`;
      const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason: r2, code: "COMPENSATION_VERIFY_FAILED" });
      return { kind: "indeterminate", lastObserved, receipt: outcome.receipt, factoryError: true };
    }
    const afterCompSnap = snapshot(afterComp);
    const outcome = persistReceipt(sink, {
      transition, issueNumber, kind: "compensated", reason, code: "COMPENSATED",
      before, after: afterCompSnap,
    });
    if (outcome.persistFailed) {
      return { kind: "indeterminate", lastObserved: afterCompSnap, receipt: outcome.receipt, factoryError: true };
    }
    return { kind: "compensated", before, after: afterCompSnap, receipt: outcome.receipt };
  } catch (e) {
    const r2 = `${reason}; compensation applied but verification read failed: ${getMsg(e)}`;
    const outcome = persistReceipt(sink, { transition, issueNumber, kind: "indeterminate", reason: r2, code: "COMPENSATION_VERIFY_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt: outcome.receipt, factoryError: true };
  }
}

function getMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Outcome of receipt persistence. When persistence fails, callers MUST return
 * indeterminate FACTORY_ERROR regardless of what GitHub did — a committed
 * result can never lack durable evidence.
 */
interface PersistOutcome {
  receipt: TransitionReceipt;
  /** True when the sink threw — outer result must become indeterminate. */
  persistFailed: boolean;
}

function persistReceipt(sink: ReceiptSink, partial: Omit<TransitionReceipt, "at"> & { at?: string }): PersistOutcome {
  const receipt: TransitionReceipt = { ...partial, at: partial.at ?? new Date().toISOString() } as TransitionReceipt;
  try {
    sink.persist(receipt);
    return { receipt, persistFailed: false };
  } catch (e) {
    const sinkError = getMsg(e);
    const failureReceipt: TransitionReceipt = {
      ...receipt,
      kind: "indeterminate",
      code: "RECEIPT_PERSIST_FAILED",
      reason: `${partial.reason ?? "transition completed"}; receipt persistence failed: ${sinkError}`,
    };
    try { sink.persist(failureReceipt); } catch {}
    return { receipt: failureReceipt, persistFailed: true };
  }
}

/**
 * Authoritative claim outcome — every field is observed fact, never inferred
 * from labels or codes. The adapter captures the actual fresh before-state
 * before mutation and the actual final observed state after compensation.
 */
interface AuthoritativeClaimOutcome {
  phase: "rejected-before-mutation" | "committed" | "compensated" | "indeterminate";
  reason?: string;
  code?: string;
  /** Actual fresh state read immediately before mutation. Null when never read. */
  beforeState: IssueSnapshot | null;
  /** Actual final observed state (post-claim or post-compensation). Null when unknown. */
  finalState: IssueSnapshot | null;
}

/**
 * Claim compensation proof: perform a fresh read and verify the FULL safe
 * state — expected claimant absent, agent:in-progress absent,
 * agent:implement not restored, plus profile-specific retention.
 */
async function proveClaimCompensation(
  gh: GhTransport,
  issueNumber: number,
  claimantLogin: string,
  profile: "implementation" | "research",
  beforeState: IssueSnapshot | null,
): Promise<{ proven: boolean; finalState: IssueSnapshot | null; violation?: string }> {
  let afterComp: IssueInput;
  try {
    afterComp = await fetchFresh(gh, issueNumber);
  } catch {
    return { proven: false, finalState: null };
  }
  const violations: string[] = [];
  if (afterComp.assignees.includes(claimantLogin)) violations.push(`claimant ${claimantLogin} still assigned`);
  if (afterComp.labels.includes(AGENT_IN_PROGRESS)) violations.push(`${AGENT_IN_PROGRESS} still present`);
  // implement is only "unexpectedly restored" if the claim had actually
  // consumed it (it was absent before the claim attempt). If the mutation
  // never applied, implement legitimately remains from the pre-claim state.
  const implementWasConsumed = beforeState !== null && !beforeState.labels.includes(AGENT_IMPLEMENT);
  if (implementWasConsumed && afterComp.labels.includes(AGENT_IMPLEMENT)) violations.push(`${AGENT_IMPLEMENT} unexpectedly restored`);
  if (profile === "implementation" && !afterComp.labels.includes(READY_FOR_AGENT)) violations.push(`${READY_FOR_AGENT} not retained`);
  if (profile === "research" && !afterComp.labels.includes("wayfinder:research")) violations.push(`wayfinder:research not retained`);
  if (violations.length > 0) return { proven: false, finalState: snapshot(afterComp), violation: violations.join("; ") };
  return { proven: true, finalState: snapshot(afterComp) };
}

export interface TrackerAdapter {
  claimImplementation(issue: IssueInput): Promise<TrackerTransitionResult>;
  claimResearch(issue: IssueInput): Promise<TrackerTransitionResult>;
  transitionToBlocked(issueNumber: number): Promise<TrackerTransitionResult>;
  releaseAfterFactoryError(issueNumber: number): Promise<TrackerTransitionResult>;
  finalizeIntegrated(issueNumber: number, branch: string): Promise<TrackerTransitionResult>;
  cleanupClosedIssueStaleLabels(issue: IssueInput): Promise<{ cleaned: boolean; removed: string[] }>;
  comment(issueNumber: number, body: string): Promise<boolean>;
  /**
   * Stale implementation reconciliation — the single consumer-facing port.
   * Internally constructs the Git/worktree inspection implementation.
   */
  reconcileStaleImplementation(issue: IssueInput, branch: string): Promise<ReconcileResult>;
}

export function createTrackerAdapter(deps: TrackerAdapterDeps): TrackerAdapter {
  const { gh, receiptSink } = deps;

  async function editIssue(issueNumber: number, args: string[]): Promise<void> {
    await gh.run(["issue", "edit", String(issueNumber), ...args]);
  }

  async function fetchById(id: string): Promise<IssueInput> {
    return fetchFresh(gh, Number(id));
  }

  /**
   * Add agent:blocked on the PROVEN released state — used by reconciliation
   * after its own release step. No claim validation (the claim is already
   * released); proves the final invariant minus the claimant check (claimant
   * was removed by the preceding release saga and receipted there).
   */
  async function addBlockedLabelSaga(issueNumber: number): Promise<TrackerTransitionResult> {
    return runSaga(gh, issueNumber, "addBlockedAfterRelease", [
      {
        name: "add-blocked",
        // No compensation: restoring agent:implement would be unsafe.
        mutate: () => editIssue(issueNumber, ["--add-label", AGENT_BLOCKED]),
        verifyAfter: (after) => {
          if (after.state !== "open") return `issue #${issueNumber} not open`;
          if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent in final state`;
          if (!after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} absent`;
          if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present in final state`;
          if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} present in final state`;
          return null;
        },
      },
    ], receiptSink);
  }

  const adapter: TrackerAdapter = {
    /**
     * Implementation claim — DELEGATES to canonical tracker-operations
     * claimImplementation (full eligibility revalidation: assignee, blocked,
     * native dependency state fail-closed, contradictions, tracer contract,
     * wayfinder restrictions), then wraps the outcome in the typed saga
     * taxonomy with receipt persistence.
     */
    async claimImplementation(issue) {
      const n = issue.number;
      const claimantLogin = await gh.resolveClaimantLogin();

      // The canonical operation's OWN pre-mutation fetch is the authoritative
      // snapshot: ops.fetchIssue is invoked by claimImplementation itself and
      // captured here verbatim — no separate adapter prefetch that could race.
      let canonicalBeforeState: IssueSnapshot | null = null;
      let canonicalFetchFailed = false;

      const ops: ClaimOps & { claimantLogin: string } = {
        claimantLogin,
        fetchIssue: async (id) => {
          const fetched = await fetchById(id);
          if (Number(id) === n && !canonicalFetchFailed) {
            canonicalBeforeState = snapshot(fetched);
          }
          return fetched;
        },
        applyClaim: async (id) => {
          await editIssue(Number(id), ["--add-assignee", claimantLogin, "--add-label", AGENT_IN_PROGRESS, "--remove-label", AGENT_IMPLEMENT]);
        },
        verifyClaim: (id) => fetchById(id),
        compensateClaim: async (id) => {
          try {
            await editIssue(Number(id), ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]);
            return true;
          } catch { return false; }
        },
      };
      const result = await claimImplementation(String(n), issue, ops).catch((e) => {
        canonicalFetchFailed = true;
        return { success: false as const, reason: `claim operation threw for #${n}: ${getMsg(e)}`, code: "FETCH_FAILED", compensated: false, factoryError: true };
      });

      if (!result.success && result.code === "FETCH_FAILED") {
        // Canonical revalidation read failed — state unknown, zero mutation
        // attempted. Indeterminate FACTORY_ERROR, never rejected.
        return outcomeToTransition(
          { phase: "indeterminate", reason: result.reason, code: result.code, beforeState: canonicalBeforeState, finalState: null },
          "claimImplementation", n, receiptSink,
        );
      }

      // Prove compensation on a fresh read whenever the canonical ops report it.
      let compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string } | null = null;
      if (!result.success && result.compensated && !result.factoryError) {
        compensationProof = await proveClaimCompensation(gh, n, claimantLogin, "implementation", canonicalBeforeState);
      }

      const outcome = classifyClaimOutcome("claimImplementation", result, canonicalBeforeState, compensationProof);
      return outcomeToTransition(outcome, "claimImplementation", n, receiptSink);
    },

    /**
     * Research claim — DELEGATES to canonical tracker-operations claimResearch
     * (full eligibility revalidation including ## Question body contract,
     * contradiction detection, wayfinder profile restrictions, native
     * dependency state fail-closed), wrapped in the typed saga taxonomy.
     */
    async claimResearch(issue) {
      const n = issue.number;
      const claimantLogin = await gh.resolveClaimantLogin();

      // Same authoritative pre-mutation capture as implementation claims:
      // the canonical operation's own fetch IS the receipted before-state.
      let canonicalBeforeState: IssueSnapshot | null = null;

      const ops: ResearchClaimOps & { claimantLogin: string } = {
        claimantLogin,
        fetchIssue: async (id) => {
          const fetched = await fetchById(id);
          if (Number(id) === n) {
            canonicalBeforeState = snapshot(fetched);
          }
          return fetched;
        },
        applyClaim: async (id) => {
          await editIssue(Number(id), ["--add-assignee", claimantLogin, "--add-label", AGENT_IN_PROGRESS]);
        },
        verifyClaim: (id) => fetchById(id),
        compensateClaim: async (id) => {
          try {
            await editIssue(Number(id), ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]);
            return true;
          } catch { return false; }
        },
      };
      const result = await claimResearch(String(n), ops);

      if (!result.success && result.code === "FETCH_FAILED") {
        return outcomeToTransition(
          { phase: "indeterminate", reason: result.reason, code: result.code, beforeState: canonicalBeforeState, finalState: null },
          "claimResearch", n, receiptSink,
        );
      }

      // Prove compensation on a fresh read whenever the canonical ops report it.
      let compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string } | null = null;
      if (!result.success && result.compensated && !result.factoryError) {
        compensationProof = await proveClaimCompensation(gh, n, claimantLogin, "research", canonicalBeforeState);
      }

      const outcome = classifyClaimOutcome("claimResearch", result, canonicalBeforeState, compensationProof);
      return outcomeToTransition(outcome, "claimResearch", n, receiptSink);
    },

    /**
     * Blocked transition. Per ADR 0010 asymmetry: releasing the claim is safe
     * to compensate ONLY from a proven claimed before-state (validated first);
     * adding agent:blocked is NOT compensated by restoring agent:implement —
     * that step has no compensation, so its failure reports indeterminate.
     */
    async transitionToBlocked(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "transitionToBlocked", [
        {
          name: "release-claim",
          // Validate the COMPLETE claimed state BEFORE releasing — blind
          // compensation could otherwise create a claim that never existed.
          validateBefore: (fresh) => {
            if (fresh.state !== "open") return `issue #${issueNumber} not open`;
            if (!fresh.labels.includes(READY_FOR_AGENT)) return `#${issueNumber} missing ${READY_FOR_AGENT}`;
            if (!fresh.labels.includes(AGENT_IN_PROGRESS)) return `#${issueNumber} missing ${AGENT_IN_PROGRESS} — nothing to release`;
            if (!fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned on #${issueNumber} — refusing to create a claim`;
            if (fresh.labels.includes(AGENT_IMPLEMENT)) return `#${issueNumber} has ${AGENT_IMPLEMENT} — implement must not be reintroduced before blocking`;
            if (fresh.labels.includes(AGENT_BLOCKED)) return `#${issueNumber} already has ${AGENT_BLOCKED}`;
            return null;
          },
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            // PROVE the exact released intermediate state immediately before
            // add-blocked runs. A mismatch here means another actor touched the
            // issue between release and block — indeterminate, not committed.
            if (after.state !== "open") return `issue #${issueNumber} not open after release`;
            if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent after release`;
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} appeared after release — concurrent mutation`;
            if (after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} appeared after release — concurrent mutation`;
            return null;
          },
          // No compensation: if the released state cannot be PROVEN, another
          // actor may own the claim — restoring it could fabricate a claim
          // that never existed. Any release/postcondition failure here is
          // indeterminate FACTORY_ERROR.
        },
        {
          name: "add-blocked",
          // No compensation: restoring agent:implement would be unsafe.
          mutate: () => editIssue(issueNumber, ["--add-label", AGENT_BLOCKED]),
          verifyAfter: (after) => {
            // Final committed state must prove the FULL invariant:
            // open + ready-for-agent + agent:blocked − in-progress − claimant − implement.
            if (after.state !== "open") return `issue #${issueNumber} not open`;
            if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent in final state`;
            if (!after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} absent`;
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present in final state`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} present in final state`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned in final state`;
            return null;
          },
        },
      ], receiptSink);
    },

    /**
     * Factory-error release: remove in-progress + claimant, never restore
     * agent:implement. Release failure is unsafe-to-restore → indeterminate.
     */
    async releaseAfterFactoryError(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "releaseAfterFactoryError", [
        {
          name: "release",
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            return null;
          },
          // No compensation: partial release cannot be safely reconstructed.
        },
      ], receiptSink);
    },

    /**
     * Integration close: strip transient/command labels, close with audit
     * comment. Final postcondition proves ALL of: closed, claimant absent,
     * in-progress/implement/blocked all absent — matching the reconciliation
     * adapter's integration check strength.
     */
    async finalizeIntegrated(issueNumber, branch) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "finalizeIntegrated", [
        {
          name: "strip-transient",
          mutate: async () => {
            for (const label of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
              try { await editIssue(issueNumber, ["--remove-label", label]); } catch {}
            }
            try { await editIssue(issueNumber, ["--remove-assignee", claimantLogin]); } catch {}
          },
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} still present`;
            if (after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            return null;
          },
        },
        {
          name: "close",
          mutate: async () => {
            try {
              await gh.run(["issue", "close", String(issueNumber), "--comment",
                `Completed by Sandcastle -- branch \`${branch}\` merged and integrated. Auto-merged to main after verification.`]);
            } catch {
              // fallback: comment then close
              await gh.run(["issue", "comment", String(issueNumber), "--body",
                `Completed by Sandcastle -- branch \`${branch}\` integrated.`]);
              await gh.run(["issue", "close", String(issueNumber)]);
            }
          },
          verifyAfter: (after) => {
            if (after.state !== "closed") return `issue #${issueNumber} not closed`;
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} present on closed issue`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} present on closed issue`;
            if (after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} present on closed issue`;
            if (after.assignees.includes(claimantLogin)) return `claimant assigned on closed issue`;
            return null;
          },
          // No compensation: reopening a closed integrated issue on failure is unsafe.
        },
      ], receiptSink);
    },

    /**
     * Closed-issue stale label cleanup — the adapter owns the GitHub mutation
     * AND its typed receipt, with a fresh read-back proving the final state.
     * A partial failure (some labels removed, some not) is reported truthfully
     * via `cleaned:false` so callers can stop progression.
     */
    async cleanupClosedIssueStaleLabels(issue) {
      const outcome = await cleanupClosedIssue(issue, {
        removeLabel: async (id, label) => {
          try {
            const result = await runSaga(gh, Number(id), "cleanupClosedStaleLabel", [
              {
                name: `remove-${label}`,
                mutate: () => editIssue(Number(id), ["--remove-label", label]),
                verifyAfter: (after) =>
                  after.labels.includes(label) ? `${label} still present` : null,
              },
            ], receiptSink);
            return result.kind === "committed";
          } catch { return false; }
        },
      });
      // Fresh read-back: prove no transient label remains on the closed issue.
      if (outcome.cleaned) {
        try {
          const after = await fetchById(String(issue.number));
          const residue = [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED].filter((l) => after.labels.includes(l));
          if (residue.length > 0) return { cleaned: false, removed: outcome.removed };
        } catch {
          return { cleaned: false, removed: outcome.removed };
        }
      }
      return outcome;
    },

    async comment(issueNumber, body) {
      try {
        await gh.run(["issue", "comment", String(issueNumber), "--body", body]);
        return true;
      } catch { return false; }
    },

    /**
     * Stale implementation reconciliation — single consumer-facing port.
     * Constructs the internal Git/worktree INSPECTION implementation from
     * transport-owned identity plus the injected local git runner, and wires
     * its own verified-saga transitions as the ONLY GitHub state-transition
     * authority used by reconciliation. Every release/block/integrate inside
     * reconciliation is validated, proved on fresh reads, and receipted here.
     */
    async reconcileStaleImplementation(issue, branch) {
      if (!deps.runGit || !deps.repoRoot) {
        throw new Error("reconcileStaleImplementation requires runGit and repoRoot on the adapter deps");
      }
      const claimantLogin = await gh.resolveClaimantLogin();
      const inspections = createProductionReconcileOps({
        runGh: (args) => gh.run(args),
        runGit: deps.runGit,
        repoRoot: deps.repoRoot,
        claimantLogin,
      });

      // The adapter's own saga transitions — reconciliation never composes raw edits.
      // `adapter` is assigned by the time any transition port is invoked.
      const github: ReconcileGitHubTransitions = {
        releaseClaim: (issueNumber) => adapter.releaseAfterFactoryError(issueNumber),
        addBlockedAfterRelease: (issueNumber) => addBlockedLabelSaga(issueNumber),
        integrateAndClose: (issueNumber, integrationBranch) => adapter.finalizeIntegrated(issueNumber, integrationBranch),
        comment: (issueNumber, body) => adapter.comment(issueNumber, body),
      };

      return reconcileStaleImplementation(issue, branch, { ...inspections, github });
    },
  };
  return adapter;
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

export interface TrackerAdapterDeps {
  gh: GhTransport;
  receiptSink: ReceiptSink;
  /**
   * Local git runner for reconciliation's branch/worktree/provenance
   * inspections. Required only when reconcileStaleImplementation is used.
   */
  runGit?: (args: string[]) => { exitCode: number; stdout: string; stderr: string };
  /** Stable repository root for reconciliation's local git operations. */
  repoRoot?: string;
}

/**
 * Classify a canonical ClaimResult into an authoritative phase using the
 * EXPLICIT phase marker returned by the canonical operation plus OBSERVED
 * facts (compensation proven on a fresh read, actual final state). No string
 * code inference — the canonical operation declares its own phase.
 */
function classifyClaimOutcome(
  transition: string,
  result: ClaimResult,
  beforeState: IssueSnapshot | null,
  compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string } | null,
): AuthoritativeClaimOutcome {
  if (result.success) {
    return {
      phase: "committed",
      beforeState,
      finalState: result.issue ? snapshot(result.issue) : null,
    };
  }

  // Rejected-before-mutation: the canonical operation explicitly marks
  // failures that occurred before applyClaim was ever invoked.
  if (result.phase === "rejected-before-mutation") {
    return {
      phase: "rejected-before-mutation",
      reason: result.reason,
      code: result.code,
      beforeState,
      finalState: result.issue ? snapshot(result.issue) : beforeState,
    };
  }

  // Compensation attempted — only "compensated" if PROVEN on fresh read.
  if (!result.factoryError && compensationProof !== null) {
    if (compensationProof.proven) {
      return {
        phase: "compensated",
        reason: result.reason,
        code: result.code,
        beforeState,
        finalState: compensationProof.finalState,
      };
    }
    return {
      phase: "indeterminate",
      reason: `${result.reason}; compensation applied but safe state not proven${compensationProof.violation ? `: ${compensationProof.violation}` : ""}`,
      code: "COMPENSATION_VERIFY_FAILED",
      beforeState,
      finalState: compensationProof.finalState,
    };
  }

  // Anything else is indeterminate.
  return {
    phase: "indeterminate",
    reason: result.reason,
    code: result.code,
    beforeState,
    finalState: result.issue ? snapshot(result.issue) : compensationProof?.finalState ?? null,
  };
}

/**
 * Convert an authoritative claim outcome into the typed transition taxonomy.
 * Receipt persistence failure FORCES indeterminate regardless of phase.
 */
function outcomeToTransition(
  outcome: AuthoritativeClaimOutcome,
  transition: string,
  issueNumber: number,
  sink: ReceiptSink,
): TrackerTransitionResult {
  const base = { transition, issueNumber } as const;

  if (outcome.phase === "committed") {
    if (!outcome.beforeState || !outcome.finalState) {
      // A committed claim without both observed states cannot be evidenced.
      const failureReceipt = persistReceipt(sink, {
        ...base, kind: "indeterminate", code: "RECEIPT_PERSIST_FAILED",
        reason: "committed claim missing observed before/final state",
        lastObserved: outcome.finalState,
      });
      return { kind: "indeterminate", lastObserved: outcome.finalState, receipt: failureReceipt.receipt, factoryError: true };
    }
    const outcome2 = persistReceipt(sink, {
      ...base, kind: "committed", before: outcome.beforeState, after: outcome.finalState,
    });
    if (outcome2.persistFailed) {
      return { kind: "indeterminate", lastObserved: outcome.finalState, receipt: outcome2.receipt, factoryError: true };
    }
    return { kind: "committed", before: outcome.beforeState, after: outcome.finalState, receipt: outcome2.receipt };
  }

  if (outcome.phase === "rejected-before-mutation") {
    const state = outcome.finalState ?? outcome.beforeState;
    if (!state) {
      const failureReceipt = persistReceipt(sink, {
        ...base, kind: "indeterminate", code: "RECEIPT_PERSIST_FAILED",
        reason: outcome.reason ?? "rejection without observed state",
      });
      return { kind: "indeterminate", lastObserved: null, receipt: failureReceipt.receipt, factoryError: true };
    }
    const persisted = persistReceipt(sink, {
      ...base, kind: "rejected", reason: outcome.reason, code: outcome.code, before: state, after: state,
    });
    if (persisted.persistFailed) {
      return { kind: "indeterminate", lastObserved: state, receipt: persisted.receipt, factoryError: true };
    }
    return { kind: "rejected", before: state, after: state, receipt: persisted.receipt };
  }

  if (outcome.phase === "compensated") {
    if (!outcome.beforeState || !outcome.finalState) {
      const failureReceipt = persistReceipt(sink, {
        ...base, kind: "indeterminate", code: "RECEIPT_PERSIST_FAILED",
        reason: outcome.reason ?? "compensated claim missing observed states",
        lastObserved: outcome.finalState,
      });
      return { kind: "indeterminate", lastObserved: outcome.finalState, receipt: failureReceipt.receipt, factoryError: true };
    }
    const persisted = persistReceipt(sink, {
      ...base, kind: "compensated", reason: outcome.reason, code: outcome.code,
      before: outcome.beforeState, after: outcome.finalState,
    });
    if (persisted.persistFailed) {
      return { kind: "indeterminate", lastObserved: outcome.finalState, receipt: persisted.receipt, factoryError: true };
    }
    return { kind: "compensated", before: outcome.beforeState, after: outcome.finalState, receipt: persisted.receipt };
  }

  // indeterminate
  const persisted = persistReceipt(sink, {
    ...base, kind: "indeterminate", reason: outcome.reason, code: outcome.code,
    lastObserved: outcome.finalState,
  });
  return { kind: "indeterminate", lastObserved: outcome.finalState, receipt: persisted.receipt, factoryError: true };
}
