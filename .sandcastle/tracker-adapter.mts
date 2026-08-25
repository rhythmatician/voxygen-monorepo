import type { IssueInput } from "./tracker-policy.mts";
import {
  READY_FOR_AGENT,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
  detectContradictions,
  validateResearchTicketInput,
  validateImplementationTicketInput,
} from "./tracker-policy.mts";
import {
  claimImplementation,
  claimResearch,
  reconcileStaleImplementation,
  type ClaimResult,
  type ClaimOps,
  type ResearchClaimOps,
  type ReconcileResult,
  type ReconcileGitHubTransitions,
} from "./tracker-operations.mts";
import { createProductionReconcileOps } from "./reconcile-adapter.mts";
import type { GhTransport } from "./gh-transport.mts";
import { isHttp404 } from "./gh-errors.mts";
import { createHash } from "node:crypto";

/**
 * The reviewed snapshot of a single issue, as captured in the reviewed
 * receipt. The label-mutation port validates its own fresh pre-mutation read
 * against this snapshot (state, labels, assignees, blocked_by, body hash)
 * before any write — drift between review and apply is evidence loss.
 */
export interface ReviewedIssueSnapshot {
  number: number;
  state: string;
  labels: string[];
  assignees: string[];
  blocked_by: number | undefined;
  bodySha256: string;
}

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
): (issueNumber: number, addLabels: string[], removeLabels: string[], expected?: ReviewedIssueSnapshot) => Promise<{ committed: boolean; reason?: string }> {
  return async (issueNumber, addLabels, removeLabels, expected) => {
    const args: string[] = [];
    for (const a of addLabels) args.push("--add-label", a);
    for (const r of removeLabels) args.push("--remove-label", r);
    if (args.length === 0) return { committed: true };
    const result = await runSaga(gh, issueNumber, "migrationLabelMutation", [
      {
        name: "apply-label-mutation",
        // Bind the mutation to the REVIEWED state: the port's own fresh
        // pre-mutation read must match the reviewed snapshot (state, labels,
        // assignees, blocked_by, body hash) before any write. Drift between
        // review and apply is evidence loss — reject with zero mutation.
        validateBefore: (before) => {
          if (!expected) return null;
          if (before.state !== expected.state) return `state drifted: reviewed ${expected.state}, live ${before.state}`;
          const labelDiff = (l: string[]) => [...l].sort().join(",");
          if (labelDiff(before.labels) !== labelDiff(expected.labels)) return `labels drifted from reviewed snapshot`;
          const assigneeDiff = (a: string[]) => [...a].sort().join(",");
          if (assigneeDiff(before.assignees) !== assigneeDiff(expected.assignees)) return `assignees drifted from reviewed snapshot`;
          if (before.blockedByCount !== expected.blocked_by) return `blocked_by drifted: reviewed ${expected.blocked_by}, live ${before.blockedByCount}`;
          if (bodySha256(before.body) !== expected.bodySha256) return `body hash drifted from reviewed snapshot`;
          return null;
        },
        mutate: () => runEdit(gh, issueNumber, args),
        verifyAfter: (after) => {
          // Prove the ENTIRE reviewed issue state after mutation (item 5):
          // state unchanged from reviewed; labels exactly equal reviewed labels
          // transformed by add/remove; assignees unchanged; blocked_by
          // unchanged; body hash unchanged. Any drift after the precondition
          // but before terminal verification is evidence loss — not committed.
          if (expected) {
            if (after.state !== expected.state) return `state changed from reviewed: reviewed ${expected.state}, live ${after.state}`;
            const expectedLabels = new Set(expected.labels);
            for (const a of addLabels) expectedLabels.add(a);
            for (const r of removeLabels) expectedLabels.delete(r);
            const liveLabels = new Set(after.labels);
            const expectedArr = [...expectedLabels].sort();
            const liveArr = [...liveLabels].sort();
            if (expectedArr.join(",") !== liveArr.join(",")) return `labels not exactly reviewed-transformed: expected [${expectedArr.join(",")}], live [${liveArr.join(",")}]`;
            const assigneeDiff = (a: string[]) => [...a].sort().join(",");
            if (assigneeDiff(after.assignees) !== assigneeDiff(expected.assignees)) return `assignees changed from reviewed`;
            if (after.blockedByCount !== expected.blocked_by) return `blocked_by changed from reviewed: reviewed ${expected.blocked_by}, live ${after.blockedByCount}`;
            if (bodySha256(after.body) !== expected.bodySha256) return `body hash changed from reviewed`;
            return null;
          }
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

/** sha256 hex digest of a body string (empty string when undefined). */
function bodySha256(body: string | undefined): string {
  return createHash("sha256").update(body ?? "").digest("hex");
}

async function runEdit(gh: GhTransport, issueNumber: number, args: string[]): Promise<void> {
  await gh.run(["issue", "edit", String(issueNumber), ...args]);
}

// ---------------------------------------------------------------------------
// Saga primitive
// ---------------------------------------------------------------------------

/**
 * Phase-aware precondition for the SECOND step of a release→block sequence.
 * The step's own fresh read must re-prove the released intermediate state:
 * open + ready-for-agent, with agent:in-progress, the expected claimant,
 * agent:implement, and agent:blocked all absent. A failure here means an
 * external actor mutated the issue after the first step committed — the
 * caller reports it INDETERMINATE (never rejected) and does NOT proceed to
 * the blocked mutation.
 */
function validateReleasedPrecondition(
  fresh: IssueInput,
  issueNumber: number,
  claimantLogin: string,
): string | null {
  if (fresh.state !== "open") return `issue #${issueNumber} not open on pre-mutation read`;
  if (!fresh.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent on pre-mutation read`;
  if (fresh.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} present on pre-mutation read — claim was reintroduced concurrently`;
  if (fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} assigned on pre-mutation read — claim was reintroduced concurrently`;
  if (fresh.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} present on pre-mutation read — concurrent mutation`;
  if (fresh.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} already present on pre-mutation read — concurrent mutation`;
  return null;
}

/**
 * Exact-ownership precondition for releasing an IMPLEMENTATION claim. The
 * release mutation must freshly prove the exact claimed state before it may
 * remove in-progress + claimant: open, ready-for-agent present, in-progress
 * present, the authenticated claimant assigned, agent:implement absent
 * (consumed by the claim), and agent:blocked absent. Any drift rejects with
 * zero mutation — never release another actor's claim.
 */
function validateOwnedImplementationClaim(
  fresh: IssueInput,
  issueNumber: number,
  claimantLogin: string,
): string | null {
  if (fresh.state !== "open") return `issue #${issueNumber} not open on pre-mutation read`;
  if (!fresh.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent on pre-mutation read`;
  if (!fresh.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent on pre-mutation read — nothing to release`;
  if (!fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned on #${issueNumber} — refusing to release another actor's claim`;
  if (fresh.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} present on pre-mutation read — command not consumed`;
  if (fresh.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} already present on pre-mutation read`;
  return null;
}

/**
 * Exact-ownership precondition for releasing a RESEARCH claim: open,
 * in-progress present, the authenticated claimant assigned, and
 * wayfinder:research retained. agent:implement is not part of a research
 * claim's command state.
 */
function validateOwnedResearchClaim(
  fresh: IssueInput,
  issueNumber: number,
  claimantLogin: string,
): string | null {
  if (fresh.state !== "open") return `issue #${issueNumber} not open on pre-mutation read`;
  if (!fresh.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent on pre-mutation read — nothing to release`;
  if (!fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned on #${issueNumber} — refusing to release another actor's claim`;
  if (!fresh.labels.includes("wayfinder:research")) return `wayfinder:research absent on pre-mutation read`;
  return null;
}

/**
 * Exact-ownership precondition for compensating a BOTH-PRESENT contradiction:
 * open, in-progress present, agent:implement present (the command was NOT
 * consumed), and the authenticated claimant assigned. Compensation removes
 * in-progress + claimant but RETAINS agent:implement.
 */
function validateBothPresentClaim(
  fresh: IssueInput,
  issueNumber: number,
  claimantLogin: string,
): string | null {
  if (fresh.state !== "open") return `issue #${issueNumber} not open on pre-mutation read`;
  if (!fresh.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent on pre-mutation read — nothing to compensate`;
  if (!fresh.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} absent on pre-mutation read — not a both-present contradiction`;
  if (!fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned on #${issueNumber} — refusing to compensate another actor's claim`;
  return null;
}

/**
 * UNCONDITIONAL closed-cleanup terminal invariant. Regardless of the initial
 * hasClaimant value or which mutation steps were selected, the final and
 * terminal verifier must prove ALL of: state closed; authenticated claimant
 * absent; agent:in-progress absent; agent:implement absent; agent:blocked
 * absent. A claimant that appears AFTER the initial read (concurrent) is
 * caught here — cleanup must not report cleaned.
 */
function verifyClosedCleanupInvariant(
  after: IssueInput,
  issueNumber: number,
  claimantLogin: string,
): string | null {
  if (after.state !== "closed") return `issue #${issueNumber} no longer closed — reopen during transition`;
  if (after.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} still assigned on closed issue #${issueNumber}`;
  for (const l of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
    if (after.labels.includes(l)) return `${l} still present on closed issue #${issueNumber}`;
  }
  return null;
}

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
        // A failed precondition AFTER a prior external mutation is evidence
        // loss, not a clean rejection: when this saga already executed an
        // earlier step, the state cannot be trusted as untouched — report
        // indeterminate FACTORY_ERROR so callers stop progression.
        const priorMutation = steps.indexOf(step) > 0;
        const outcome = persistReceipt(sink, {
          transition, issueNumber, kind: priorMutation ? "indeterminate" : "rejected",
          reason: problem, code: priorMutation ? "PRECONDITION_DRIFT" : "PRECONDITION_FAILED",
          before: beforeSnap, after: afterSnap,
        });
        if (outcome.persistFailed || priorMutation) {
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

  // Terminal committed snapshot: the final fetch is a NEW read that must be
  // re-verified against the LAST step's postcondition before it may be
  // persisted as the committed `after`. A drift between the in-loop verified
  // read and this final read is evidence loss — indeterminate, never committed.
  const lastStep = steps[steps.length - 1];
  try {
    const final = await fetchFresh(gh, issueNumber);
    const finalSnap = snapshot(final);
    if (lastStep.verifyAfter) {
      const terminalViolation = lastStep.verifyAfter(final);
      if (terminalViolation) {
        const reason = `terminal committed snapshot drifted for #${issueNumber} (${lastStep.name}): ${terminalViolation}`;
        const outcome = persistReceipt(sink, {
          transition, issueNumber, kind: "indeterminate", reason, code: "TERMINAL_DRIFT",
          before: beforeSnap!, lastObserved: finalSnap,
        });
        return { kind: "indeterminate", lastObserved: finalSnap, receipt: outcome.receipt, factoryError: true };
      }
    }
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
 *
 * Command-awareness: whether agent:implement was consumed is determined from
 * the ACTUAL POST-MUTATION state (captured immediately after applyClaim), NOT
 * inferred from the pre-claim snapshot. If the post-mutation state is
 * unavailable, the compensation cannot be proven — return indeterminate.
 */
async function proveClaimCompensation(
  gh: GhTransport,
  issueNumber: number,
  claimantLogin: string,
  profile: "implementation" | "research",
  postMutationState: IssueSnapshot | null,
): Promise<{ proven: boolean; finalState: IssueSnapshot | null; violation?: string; unavailable?: boolean }> {
  if (postMutationState === null) {
    // Command-consumption state unavailable — cannot prove compensation.
    return { proven: false, finalState: null, unavailable: true };
  }
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
  // consumed it (it was absent in the ACTUAL POST-MUTATION state). If the
  // mutation never applied, implement legitimately remains from the pre-claim
  // state — but we use the observed post-mutation state, not the pre-claim
  // snapshot, so a consumed-then-reintroduced command is detected.
  const implementWasConsumed = !postMutationState.labels.includes(AGENT_IMPLEMENT);
  if (implementWasConsumed && afterComp.labels.includes(AGENT_IMPLEMENT)) violations.push(`${AGENT_IMPLEMENT} unexpectedly restored`);
  if (profile === "implementation" && !afterComp.labels.includes(READY_FOR_AGENT)) violations.push(`${READY_FOR_AGENT} not retained`);
  if (profile === "research" && !afterComp.labels.includes("wayfinder:research")) violations.push(`wayfinder:research not retained`);
  if (violations.length > 0) return { proven: false, finalState: snapshot(afterComp), violation: violations.join("; ") };
  return { proven: true, finalState: snapshot(afterComp) };
}

/**
 * Terminal claim proof: after canonical claim SUCCESS, perform a FINAL fresh
 * read and validate the FULL claimed state before the claim may be persisted
 * as committed and a worker launched. A closed, newly blocked,
 * dependency-blocked, contradictory, unreadable, or otherwise drifted terminal
 * state must NOT be committed — the claim is reported indeterminate
 * FACTORY_ERROR so the caller stops before worker launch.
 *
 * Implementation final invariant:
 *   state open; ready-for-agent present; agent:in-progress present;
 *   expected claimant assigned; agent:implement absent; agent:blocked absent;
 *   blocked_by known and zero; implementation profile/body still valid;
 *   no contradiction.
 *
 * Research final invariant:
 *   state open; wayfinder:research present; agent:in-progress present;
 *   expected claimant assigned; agent:blocked absent; blocked_by known and
 *   zero; Research body still valid; no contradiction.
 */
async function proveTerminalClaimState(
  gh: GhTransport,
  issueNumber: number,
  claimantLogin: string,
  profile: "implementation" | "research",
): Promise<{ proven: boolean; finalState: IssueSnapshot | null; violation?: string }> {
  let final: IssueInput;
  try {
    final = await fetchFresh(gh, issueNumber);
  } catch (e) {
    return { proven: false, finalState: null, violation: `terminal claim read failed for #${issueNumber}: ${getMsg(e)}` };
  }
  const violations: string[] = [];
  if (final.state !== "open") violations.push(`state ${final.state}, not open`);
  if (profile === "implementation") {
    if (!final.labels.includes(READY_FOR_AGENT)) violations.push(`${READY_FOR_AGENT} absent`);
    if (!final.labels.includes(AGENT_IN_PROGRESS)) violations.push(`${AGENT_IN_PROGRESS} absent`);
    if (!final.assignees.includes(claimantLogin)) violations.push(`claimant ${claimantLogin} not assigned`);
    // Reject unexpected assignees: the claimant is the single concurrency
    // owner of the claim. Any additional assignee is concurrent drift — the
    // terminal state must not be committed with an unowned assignee present.
    const unexpectedAssignees = final.assignees.filter((a) => a !== claimantLogin);
    if (unexpectedAssignees.length > 0) violations.push(`unexpected assignee(s) ${unexpectedAssignees.join(",")} present`);
    if (final.labels.includes(AGENT_IMPLEMENT)) violations.push(`${AGENT_IMPLEMENT} present`);
    if (final.labels.includes(AGENT_BLOCKED)) violations.push(`${AGENT_BLOCKED} present`);
    if (final.blockedByCount === undefined) violations.push(`blocked_by unknown`);
    else if (final.blockedByCount > 0) violations.push(`blocked_by ${final.blockedByCount}, not zero`);
    // Implementation profile/body still valid — no contradiction. (Full
    // eligibility is NOT required here: after a claim the issue legitimately
    // carries in-progress + assignee, which isImplementationEligible rejects.)
    const implContra = detectContradictions(final);
    if (implContra.contradictions.length > 0) violations.push(`contradiction: ${implContra.contradictions[0].reason}`);
    // Reuse the canonical implementation body contract — the SAME validator
    // isImplementationEligible uses, so the terminal claim proof can never
    // drift from the eligibility body contract.
    const implBody = validateImplementationTicketInput(final.body);
    if (!implBody.valid) violations.push(`implementation body invalid: ${implBody.reason ?? "unknown"}`);
  } else {
    if (!final.labels.includes("wayfinder:research")) violations.push(`wayfinder:research absent`);
    if (!final.labels.includes(AGENT_IN_PROGRESS)) violations.push(`${AGENT_IN_PROGRESS} absent`);
    if (!final.assignees.includes(claimantLogin)) violations.push(`claimant ${claimantLogin} not assigned`);
    if (final.labels.includes(AGENT_BLOCKED)) violations.push(`${AGENT_BLOCKED} present`);
    if (final.blockedByCount === undefined) violations.push(`blocked_by unknown`);
    else if (final.blockedByCount > 0) violations.push(`blocked_by ${final.blockedByCount}, not zero`);
    // Research body still valid — the ## Question contract must hold; no
    // contradiction. (Full eligibility is NOT required after a claim.)
    const researchBody = validateResearchTicketInput(final.body);
    if (!researchBody.valid) violations.push(`research body invalid: ${researchBody.reason ?? "unknown"}`);
    const researchContra = detectContradictions(final);
    if (researchContra.contradictions.length > 0) violations.push(`contradiction: ${researchContra.contradictions[0].reason}`);
  }
  if (violations.length > 0) return { proven: false, finalState: snapshot(final), violation: violations.join("; ") };
  return { proven: true, finalState: snapshot(final) };
}

export interface TrackerAdapter {
  claimImplementation(issue: IssueInput): Promise<TrackerTransitionResult>;
  claimResearch(issue: IssueInput): Promise<TrackerTransitionResult>;
  transitionToBlocked(issueNumber: number): Promise<TrackerTransitionResult>;
  /**
   * Release an owned IMPLEMENTATION claim — validates exact ownership
   * (claimant, ready-for-agent, in-progress, implement absent) on its own
   * fresh read before removing in-progress + claimant. Never releases another
   * actor's claim.
   */
  releaseOwnedImplementationClaim(issueNumber: number): Promise<TrackerTransitionResult>;
  /**
   * Release an owned RESEARCH claim — validates exact ownership (claimant,
   * in-progress, wayfinder:research) before removing in-progress + claimant.
   */
  releaseOwnedResearchClaim(issueNumber: number): Promise<TrackerTransitionResult>;
  /**
   * Compensate a BOTH-PRESENT contradiction — validates exact ownership
   * (claimant, in-progress, implement present) before removing in-progress +
   * claimant while RETAINING agent:implement (command not consumed).
   */
  compensateBothPresentClaim(issueNumber: number): Promise<TrackerTransitionResult>;
  /**
   * Release an owned implementation claim then add agent:blocked — ONE
   * two-step saga. The release step proves exact ownership; the add-blocked
   * step re-proves the released intermediate state on its own fresh read.
   */
  releaseAndBlockOwnedImplementation(issueNumber: number): Promise<TrackerTransitionResult>;
  finalizeIntegrated(issueNumber: number, branch: string): Promise<TrackerTransitionResult>;
  /**
   * Closed-issue stale label cleanup — FRESH-STATE-OWNED. Authority begins
   * from a fresh issue read by issue number (not a stale snapshot): requires
   * the issue remains closed, determines all current machine labels and the
   * authenticated claimant's current assignment from that fresh read, removes
   * agent:in-progress / agent:implement / agent:blocked and ONLY the
   * authenticated claimant (preserving unrelated assignees), terminally proves
   * closed + claimant absent + all three labels absent, and persists one
   * truthful typed receipt.
   */
  cleanupClosedIssueStaleLabels(issueNumber: number): Promise<{ cleaned: boolean; removed: string[] }>;
  comment(issueNumber: number, body: string): Promise<boolean>;
  /**
   * Update a canonical repository label's description — adapter-owned
   * repository port with fresh read-back and typed receipt. Binds to the
   * REVIEWED old description: the port's own fresh pre-mutation read must
   * match `expectedOldDescription` before any write (drift between review and
   * apply is evidence loss). Proves the final description on a fresh read.
   * Receipt persistence failure => indeterminate/not committed. Returns false
   * when the live description already matches (no mutation needed).
   */
  updateCanonicalLabelDescription(name: string, description: string, expectedOldDescription?: string): Promise<{ committed: boolean; reason?: string }>;
  /**
   * Delete a retired repository label — adapter-owned repository port with
   * fresh read-back and typed receipt. Binds to the REVIEWED existence: the
   * port's own fresh pre-mutation read must confirm the label exists before
   * any write. Proves ZERO current open users immediately before deletion.
   * Only an authoritative 404 proves final absence; auth/network/timeout/5xx
   * => indeterminate. Receipt persistence failure => indeterminate. Returns
   * false when the label no longer exists (already deleted).
   */
  deleteRetiredLabel(name: string, expectedExists?: boolean): Promise<{ committed: boolean; reason?: string }>;
  /**
   * Create a canary fixture issue — adapter-owned port. Freshly proves the
   * created issue state/body/labels/assignees. Receipt persistence failure
   * must NOT report committed. Returns the created fixture id (so cleanup can
   * occur even after an indeterminate creation) plus a committed flag; throws
   * on creation failure (never swallows).
   */
  createCanaryFixture(title: string, body: string, labels: string[]): Promise<{ id: number; committed: boolean; reason?: string }>;
  /**
   * Clean up a canary fixture — adapter-owned port. Removes stale machine
   * labels, removes the authenticated claimant assignee, and closes the issue,
   * each saga-verified with typed receipts. Throws on any failure (never
   * swallows).
   */
  cleanupCanaryFixture(id: number): Promise<void>;
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
    const claimantLogin = await gh.resolveClaimantLogin();
    return runSaga(gh, issueNumber, "addBlockedAfterRelease", [
      {
        name: "add-blocked",
        // Phase-aware precondition: this step's OWN fresh read must prove the
        // released intermediate state before mutating. Drift here is reported
        // indeterminate by runSaga (second step) and NO blocked label is added.
        validateBefore: (fresh) => validateReleasedPrecondition(fresh, issueNumber, claimantLogin),
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
      // The ACTUAL POST-MUTATION state (captured from verifyClaim immediately
      // after applyClaim) — used for command-aware compensation. Whether
      // agent:implement was consumed is determined from THIS observed state,
      // never inferred from the pre-claim snapshot.
      let canonicalPostMutationState: IssueSnapshot | null = null;
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
        verifyClaim: async (id) => {
          const fetched = await fetchById(id);
          if (Number(id) === n) {
            canonicalPostMutationState = snapshot(fetched);
          }
          return fetched;
        },
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

      // TERMINAL claim proof (item 1): after canonical claim SUCCESS, perform
      // a FINAL fresh read and validate the FULL claimed state before the
      // claim may be persisted committed and a worker launched. A closed,
      // newly blocked, dependency-blocked, contradictory, unreadable, or
      // otherwise drifted terminal state is NOT committed — indeterminate
      // FACTORY_ERROR so the caller stops before worker launch.
      if (result.success) {
        const terminal = await proveTerminalClaimState(gh, n, claimantLogin, "implementation");
        if (!terminal.proven) {
          return outcomeToTransition(
            {
              phase: "indeterminate",
              reason: `terminal claim state not proven for #${n}: ${terminal.violation}`,
              code: "TERMINAL_CLAIM_DRIFT",
              beforeState: canonicalBeforeState,
              finalState: terminal.finalState,
            },
            "claimImplementation", n, receiptSink,
          );
        }
        // The terminal fresh read is the authoritative committed `after`.
        return outcomeToTransition(
          { phase: "committed", beforeState: canonicalBeforeState, finalState: terminal.finalState },
          "claimImplementation", n, receiptSink,
        );
      }

      // Prove compensation on a fresh read whenever the canonical ops report it.
      // Command-awareness: pass the ACTUAL POST-MUTATION state so a
      // consumed-then-reintroduced command is detected. If that state is
      // unavailable, proveClaimCompensation returns indeterminate.
      let compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string; unavailable?: boolean } | null = null;
      if (!result.success && result.compensated && !result.factoryError) {
        compensationProof = await proveClaimCompensation(gh, n, claimantLogin, "implementation", canonicalPostMutationState);
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
      // Actual post-mutation state captured from verifyClaim — used for
      // command-aware compensation.
      let canonicalPostMutationState: IssueSnapshot | null = null;

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
        verifyClaim: async (id) => {
          const fetched = await fetchById(id);
          if (Number(id) === n) {
            canonicalPostMutationState = snapshot(fetched);
          }
          return fetched;
        },
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

      // TERMINAL claim proof (item 1): after canonical research claim SUCCESS,
      // perform a FINAL fresh read and validate the FULL claimed state before
      // the claim may be persisted committed and a worker launched. A closed,
      // newly blocked, dependency-blocked, contradictory, unreadable, or
      // otherwise drifted terminal state is NOT committed — indeterminate
      // FACTORY_ERROR so the caller stops before worker launch.
      if (result.success) {
        const terminal = await proveTerminalClaimState(gh, n, claimantLogin, "research");
        if (!terminal.proven) {
          return outcomeToTransition(
            {
              phase: "indeterminate",
              reason: `terminal research claim state not proven for #${n}: ${terminal.violation}`,
              code: "TERMINAL_CLAIM_DRIFT",
              beforeState: canonicalBeforeState,
              finalState: terminal.finalState,
            },
            "claimResearch", n, receiptSink,
          );
        }
        // The terminal fresh read is the authoritative committed `after`.
        return outcomeToTransition(
          { phase: "committed", beforeState: canonicalBeforeState, finalState: terminal.finalState },
          "claimResearch", n, receiptSink,
        );
      }

      // Prove compensation on a fresh read whenever the canonical ops report it.
      // Command-awareness: pass the ACTUAL POST-MUTATION state.
      let compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string; unavailable?: boolean } | null = null;
      if (!result.success && result.compensated && !result.factoryError) {
        compensationProof = await proveClaimCompensation(gh, n, claimantLogin, "research", canonicalPostMutationState);
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
          // Phase-aware precondition: re-prove the released state on THIS
          // step's own fresh read. A concurrent reclaim between the release
          // postcondition and this read is indeterminate — never blocked.
          validateBefore: (fresh) => validateReleasedPrecondition(fresh, issueNumber, claimantLogin),
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
     * Release an owned IMPLEMENTATION claim. The release mutation's own
     * validateBefore freshly proves exact ownership (claimant, ready-for-agent,
     * in-progress, implement absent) before removing in-progress + claimant.
     * Any drift rejects with zero mutation — never release another actor's claim.
     */
    async releaseOwnedImplementationClaim(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "releaseOwnedImplementationClaim", [
        {
          name: "release-owned-implementation",
          validateBefore: (fresh) => validateOwnedImplementationClaim(fresh, issueNumber, claimantLogin),
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} not retained`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} unexpectedly restored`;
            return null;
          },
          // No compensation: partial release cannot be safely reconstructed.
        },
      ], receiptSink);
    },

    /**
     * Release an owned RESEARCH claim. The release mutation's own validateBefore
     * freshly proves exact ownership (claimant, in-progress, wayfinder:research)
     * before removing in-progress + claimant.
     */
    async releaseOwnedResearchClaim(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "releaseOwnedResearchClaim", [
        {
          name: "release-owned-research",
          validateBefore: (fresh) => validateOwnedResearchClaim(fresh, issueNumber, claimantLogin),
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            if (!after.labels.includes("wayfinder:research")) return `wayfinder:research not retained`;
            return null;
          },
          // No compensation: partial release cannot be safely reconstructed.
        },
      ], receiptSink);
    },

    /**
     * Compensate a BOTH-PRESENT contradiction. The mutation's own validateBefore
     * freshly proves exact ownership (claimant, in-progress, implement present)
     * before removing in-progress + claimant while RETAINING agent:implement
     * (the command was not consumed).
     */
    async compensateBothPresentClaim(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "compensateBothPresentClaim", [
        {
          name: "compensate-both-present",
          validateBefore: (fresh) => validateBothPresentClaim(fresh, issueNumber, claimantLogin),
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            // The command was NOT consumed — agent:implement must be retained.
            if (!after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} unexpectedly removed during compensation`;
            return null;
          },
          // No compensation: partial compensation cannot be safely reconstructed.
        },
      ], receiptSink);
    },

    /**
     * Release an owned implementation claim then add agent:blocked — ONE
     * two-step saga. The release step proves exact ownership; the add-blocked
     * step re-proves the released intermediate state on its own fresh read
     * (phase-aware precondition). A drift between the two steps is
     * indeterminate, never a clean rejection.
     */
    async releaseAndBlockOwnedImplementation(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "releaseAndBlockOwnedImplementation", [
        {
          name: "release-owned-implementation",
          validateBefore: (fresh) => validateOwnedImplementationClaim(fresh, issueNumber, claimantLogin),
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.state !== "open") return `issue #${issueNumber} not open after release`;
            if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} absent after release`;
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} appeared after release — concurrent mutation`;
            if (after.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} appeared after release — concurrent mutation`;
            return null;
          },
          // No compensation: if the released state cannot be PROVEN, another
          // actor may own the claim — restoring it could fabricate a claim.
        },
        {
          name: "add-blocked",
          // Phase-aware precondition: re-prove the released intermediate state
          // on THIS step's own fresh read. A concurrent reclaim between the
          // release postcondition and this read is indeterminate — never blocked.
          validateBefore: (fresh) => validateReleasedPrecondition(fresh, issueNumber, claimantLogin),
          // No compensation: restoring agent:implement would be unsafe.
          mutate: () => editIssue(issueNumber, ["--add-label", AGENT_BLOCKED]),
          verifyAfter: (after) => {
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
     * Closed-issue stale label cleanup — ONE verified closed-state transition
     * owned by this adapter. The saga's own fresh read requires state ==
     * closed BEFORE any mutation (a reopened issue is rejected with zero
     * writes); after mutation a fresh read proves the FULL final invariant:
     * still closed, agent:in-progress / agent:implement / agent:blocked all
     * absent. A reopen DURING the transition is indeterminate and stops the
     * factory. Every executed transition persists its typed receipt.
     */
    /**
     * Closed-issue stale label cleanup — FRESH-STATE-OWNED. Authority begins
     * from a fresh issue read by issue number (never a stale snapshot). The
     * fresh read determines: whether the issue remains closed, all current
     * machine labels, and whether the authenticated claimant is currently
     * assigned. It removes agent:in-progress / agent:implement / agent:blocked
     * and ONLY the authenticated claimant (preserving unrelated assignees),
     * terminally proves closed + claimant absent + all three labels absent,
     * and persists one truthful typed receipt. A reopened issue is rejected
     * with zero writes; a reopen DURING the transition is indeterminate.
     */
    async cleanupClosedIssueStaleLabels(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();

      // FRESH read by issue number — the authority begins here, not from any
      // caller-supplied snapshot. Determine current machine labels and the
      // claimant's current assignment from THIS read.
      let fresh: IssueInput;
      try {
        fresh = await fetchById(String(issueNumber));
      } catch (e) {
        // Unreadable state — fail closed, zero mutation.
        return { cleaned: false, removed: [] };
      }
      if (fresh.state !== "closed") {
        // Reopened issue — refuse cleanup mutation with zero writes. Persist a
        // rejected receipt so the rejection is evidenced.
        const beforeSnap = snapshot(fresh);
        persistReceipt(receiptSink, {
          transition: "cleanupClosedStaleLabels", issueNumber, kind: "rejected",
          reason: `issue #${issueNumber} is ${fresh.state}, not closed — refusing cleanup mutation`,
          code: "PRECONDITION_FAILED", before: beforeSnap, after: beforeSnap,
        });
        return { cleaned: false, removed: [] };
      }
      const staleLabels = [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED].filter((l) => fresh.labels.includes(l));
      const hasClaimant = fresh.assignees.includes(claimantLogin);

      // Fast path: nothing listed to remove AND claimant not assigned. Prove
      // the FULL unconditional invariant (closed + claimant absent + all three
      // machine labels absent) on a fresh read anyway. A claimant that appears
      // after the initial read is caught here — not clean.
      if (staleLabels.length === 0 && !hasClaimant) {
        try {
          const fresh2 = await fetchById(String(issueNumber));
          const violation = verifyClosedCleanupInvariant(fresh2, issueNumber, claimantLogin);
          if (violation) return { cleaned: false, removed: [] };
          return { cleaned: true, removed: [] };
        } catch {
          return { cleaned: false, removed: [] };
        }
      }

      // Two-step saga: (1) remove stale machine labels, (2) remove the
      // authenticated claimant assignee while preserving unrelated assignees.
      // The FINAL and TERMINAL verifier is the UNCONDITIONAL invariant — it
      // proves closed + claimant absent + all three machine labels absent
      // regardless of which steps were selected, so a claimant that appears
      // after the initial read is never reported cleaned. Intermediate steps
      // use partial verifyAfter (the claimant is removed by a LATER step).
      const steps: SagaStep[] = [];
      if (staleLabels.length > 0) {
        steps.push({
          name: "remove-stale-labels",
          validateBefore: (fresh2) => {
            if (fresh2.state !== "closed") return `issue #${issueNumber} is ${fresh2.state}, not closed — refusing cleanup mutation`;
            return null;
          },
          mutate: () => editIssue(issueNumber, staleLabels.flatMap((l) => ["--remove-label", l])),
          // Partial intermediate verifier: labels + closed only. The claimant
          // is removed by the later assignee step (or was never assigned).
          verifyAfter: (after) => {
            if (after.state !== "closed") return `issue #${issueNumber} no longer closed — reopen during transition`;
            for (const l of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
              if (after.labels.includes(l)) return `${l} still present on closed issue #${issueNumber}`;
            }
            return null;
          },
          // No compensation: re-adding transient labels to a closed issue is unsafe.
        });
      }
      if (hasClaimant) {
        steps.push({
          name: "remove-claimant-assignee",
          validateBefore: (fresh2) => {
            if (fresh2.state !== "closed") return `issue #${issueNumber} is ${fresh2.state}, not closed — refusing assignee cleanup`;
            // Only remove the AUTHENTICATED claimant; unrelated assignees are
            // preserved. If the claimant is no longer assigned, nothing to do.
            return null;
          },
          mutate: () => editIssue(issueNumber, ["--remove-assignee", claimantLogin]),
          // UNCONDITIONAL terminal verifier: closed + claimant absent + all
          // three machine labels absent. This is the LAST step, so runSaga's
          // terminal revalidation re-proves the full invariant.
          verifyAfter: (after) => verifyClosedCleanupInvariant(after, issueNumber, claimantLogin),
          // No compensation: re-adding the claimant to a closed issue is unsafe.
        });
      } else if (staleLabels.length > 0) {
        // No claimant to remove — the label-removal step IS the last step, so
        // its verifyAfter must be the UNCONDITIONAL invariant to catch a
        // claimant that appears after the initial read.
        steps[steps.length - 1].verifyAfter = (after) => verifyClosedCleanupInvariant(after, issueNumber, claimantLogin);
      }

      const result = await runSaga(gh, issueNumber, "cleanupClosedStaleLabels", steps, receiptSink);
      if (result.kind === "committed") {
        const removed: string[] = [...staleLabels];
        if (hasClaimant) removed.push(claimantLogin);
        return { cleaned: true, removed };
      }
      return { cleaned: false, removed: [] };
    },

    async comment(issueNumber, body) {
      try {
        await gh.run(["issue", "comment", String(issueNumber), "--body", body]);
        return true;
      } catch { return false; }
    },

    /**
     * Update a canonical repository label's description — adapter-owned
     * repository port. Binds to the REVIEWED old description: the port's own
     * fresh pre-mutation read must match `expectedOldDescription` before any
     * write (drift between review and apply is evidence loss). The PATCH is
     * applied, then the label is read back on a fresh read to prove the
     * description actually landed. A read-back that fails or shows a
     * mismatched description is NOT committed — it is INDETERMINATE and
     * receipted as durable recovery evidence. Every path persists a typed
     * committed/rejected/indeterminate receipt.
     */
    async updateCanonicalLabelDescription(name, description, expectedOldDescription) {
      const ownerRepo = gh.resolveOwnerRepo();
      if (!ownerRepo) {
        persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "indeterminate", reason: "repository identity unavailable", code: "REPO_UNKNOWN" });
        return { committed: false, reason: "repository identity unavailable" };
      }
      const encoded = encodeURIComponent(name);
      const url = `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels/${encoded}`;
      // Bind to the REVIEWED old description: the port's own fresh pre-mutation
      // read must match before any write.
      if (expectedOldDescription !== undefined) {
        try {
          const raw = await gh.run(["api", url, "--jq", ".description"]);
          const live = raw.trim();
          if (live !== expectedOldDescription) {
            persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "rejected", reason: `label ${name} description drifted from reviewed: expected "${expectedOldDescription}", live "${live}"`, code: "PRECONDITION_FAILED" });
            return { committed: false, reason: `label ${name} description drifted from reviewed: expected "${expectedOldDescription}", live "${live}"` };
          }
        } catch (e) {
          persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "indeterminate", reason: `label ${name} pre-mutation read failed: ${getMsg(e)}`, code: "FETCH_FAILED" });
          return { committed: false, reason: `label ${name} pre-mutation read failed: ${getMsg(e)}` };
        }
      }
      try {
        await gh.run(["api", "--method", "PATCH", url, "-f", `description=${description}`]);
      } catch (e) {
        persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "rejected", reason: `label description PATCH failed for ${name}: ${getMsg(e)}`, code: "MUTATE_FAILED" });
        return { committed: false, reason: `label description PATCH failed for ${name}: ${getMsg(e)}` };
      }
      // Fresh read-back: prove the description actually landed. A failed
      // read-back after a SUCCESSFUL PATCH is INDETERMINATE — the external
      // mutation may have applied, so durable recovery evidence is required.
      try {
        const raw = await gh.run(["api", url, "--jq", ".description"]);
        const live = raw.trim();
        if (live !== description) {
          persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "indeterminate", reason: `label description read-back mismatch for ${name}: expected "${description}", live "${live}"`, code: "READBACK_MISMATCH" });
          return { committed: false, reason: `label description read-back mismatch for ${name}: expected "${description}", live "${live}"` };
        }
      } catch (e) {
        persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "indeterminate", reason: `label description read-back failed for ${name}: ${getMsg(e)}`, code: "READBACK_FAILED" });
        return { committed: false, reason: `label description read-back failed for ${name}: ${getMsg(e)}` };
      }
      // Receipt persistence failure => indeterminate/not committed.
      const persisted = persistReceipt(receiptSink, { transition: "updateCanonicalLabelDescription", issueNumber: 0, kind: "committed", reason: `updated description for label ${name}` });
      if (persisted.persistFailed) {
        return { committed: false, reason: `label description updated for ${name} but receipt persistence failed: ${persisted.receipt.reason}` };
      }
      return { committed: true };
    },

    /**
     * Delete a retired repository label — adapter-owned repository port. Binds
     * to the REVIEWED existence via `expectedExists`: true + absent = drift;
     * false + exists = drift and zero DELETE; false + authoritative 404 =
     * no-op; unknown = indeterminate. Proves ZERO current open users
     * immediately before deletion. The DELETE is applied, then the label's
     * absence is proved on a fresh read — ONLY an authoritative HTTP 404
     * confirms deletion; auth/network/timeout/5xx => indeterminate. Every path
     * persists a typed committed/rejected/indeterminate receipt.
     */
    async deleteRetiredLabel(name, expectedExists) {
      const ownerRepo = gh.resolveOwnerRepo();
      if (!ownerRepo) {
        persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: "repository identity unavailable", code: "REPO_UNKNOWN" });
        return { committed: false, reason: "repository identity unavailable" };
      }
      const encoded = encodeURIComponent(name);
      const url = `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels/${encoded}`;
      // Bind to the REVIEWED existence. Only an authoritative HTTP 404 proves
      // absence — never a generic "not found" message substring.
      if (expectedExists !== undefined) {
        try {
          await gh.run(["api", url]);
        } catch (e) {
          if (isHttp404(e)) {
            if (expectedExists) {
              // true + absent = drift before delete.
              persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "rejected", reason: `label ${name} no longer exists (reviewed as existing) — drift before delete`, code: "PRECONDITION_FAILED" });
              return { committed: false, reason: `label ${name} no longer exists (reviewed as existing) — drift before delete` };
            }
            // false + authoritative 404 = no-op (already absent).
            persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "rejected", reason: `label ${name} already absent (expected absent) — no-op`, code: "ALREADY_ABSENT" });
            return { committed: false, reason: `label ${name} already absent (expected absent) — no-op` };
          }
          // Unknown existence — indeterminate, never a clean rejection.
          persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: `label ${name} pre-mutation existence read failed: ${getMsg(e)}`, code: "FETCH_FAILED" });
          return { committed: false, reason: `label ${name} pre-mutation existence read failed: ${getMsg(e)}` };
        }
        // The label EXISTS on the pre-mutation read.
        if (!expectedExists) {
          // false + exists = drift and zero DELETE.
          persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "rejected", reason: `label ${name} exists but was reviewed as absent — drift, zero DELETE`, code: "PRECONDITION_FAILED" });
          return { committed: false, reason: `label ${name} exists but was reviewed as absent — drift, zero DELETE` };
        }
      }
      // Prove ZERO current open users immediately before deletion.
      try {
        const rawJson = await gh.run(["issue", "list", "--state", "open", "--label", name, "--limit", "100", "--json", "number"]);
        const users: any[] = JSON.parse(rawJson);
        if (users.length > 0) {
          persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "rejected", reason: `label ${name} still has ${users.length} open users — refusing deletion`, code: "OPEN_USERS" });
          return { committed: false, reason: `label ${name} still has ${users.length} open users — refusing deletion` };
        }
      } catch (e) {
        persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: `failed to verify zero open users for ${name}: ${getMsg(e)}`, code: "FETCH_FAILED" });
        return { committed: false, reason: `failed to verify zero open users for ${name}: ${getMsg(e)}` };
      }
      try {
        await gh.run(["api", "--method", "DELETE", url]);
      } catch (e) {
        // A GhTokenMissingError (or any non-404 error) after DELETE is NOT an
        // authoritative 404 — indeterminate, receipted.
        persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: `label DELETE failed for ${name}: ${getMsg(e)}`, code: "MUTATE_FAILED" });
        return { committed: false, reason: `label DELETE failed for ${name}: ${getMsg(e)}` };
      }
      // Fresh read-back: prove the label is gone. ONLY an authoritative HTTP
      // 404 confirms deletion; auth/network/timeout/5xx => indeterminate.
      try {
        await gh.run(["api", url]);
        persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: `label ${name} still exists after DELETE — read-back found it`, code: "READBACK_MISMATCH" });
        return { committed: false, reason: `label ${name} still exists after DELETE — read-back found it` };
      } catch (e) {
        if (!isHttp404(e)) {
          // Auth/network/timeout/5xx — indeterminate, not committed.
          persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "indeterminate", reason: `label ${name} deletion read-back failed (not authoritative 404): ${getMsg(e)}`, code: "READBACK_FAILED" });
          return { committed: false, reason: `label ${name} deletion read-back failed (not authoritative 404): ${getMsg(e)}` };
        }
        // Authoritative 404 confirms the label is gone.
      }
      // Receipt persistence failure => indeterminate.
      const persisted = persistReceipt(receiptSink, { transition: "deleteRetiredLabel", issueNumber: 0, kind: "committed", reason: `deleted retired label ${name}` });
      if (persisted.persistFailed) {
        return { committed: false, reason: `retired label ${name} deleted but receipt persistence failed: ${persisted.receipt.reason}` };
      }
      return { committed: true };
    },

    /**
     * Create a canary fixture issue — adapter-owned port. The POST is applied,
     * then the created issue is freshly read back to prove the EXACT created
     * state: exact title, exact body, state open, exact expected label set,
     * no assignees, blocked_by known and zero. Receipt persistence failure
     * must NOT report committed. Returns the created fixture id (so cleanup
     * can occur even after an indeterminate creation) plus a committed flag;
     * throws on creation failure (never swallows).
     */
    async createCanaryFixture(title, body, labels) {
      const ownerRepo = gh.resolveOwnerRepo();
      if (!ownerRepo) throw new Error("repository identity unavailable for canary fixture creation");
      const args: string[] = ["api", "--method", "POST", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues`, "-f", `title=${title}`, "-f", `body=${body}`];
      for (const l of labels) args.push("-f", `labels[]=${l}`);
      args.push("--jq", ".number");
      const out = await gh.run(args);
      const n = parseInt(out.trim(), 10);
      if (isNaN(n)) throw new Error(`failed to create canary fixture issue: ${out}`);
      // Freshly prove the EXACT created issue state: exact title, exact body,
      // state open, exact expected label set, no assignees, blocked_by known
      // and zero.
      try {
        const fresh = await fetchById(String(n));
        if (fresh.state !== "open") throw new Error(`canary fixture #${n} not open after creation`);
        if (fresh.title !== title) throw new Error(`canary fixture #${n} title mismatch after creation`);
        if (fresh.body !== body) throw new Error(`canary fixture #${n} body mismatch after creation`);
        const expectedLabels = [...labels].sort();
        const liveLabels = [...fresh.labels].sort();
        if (expectedLabels.join(",") !== liveLabels.join(",")) throw new Error(`canary fixture #${n} label set mismatch after creation: expected [${expectedLabels.join(",")}], live [${liveLabels.join(",")}]`);
        if (fresh.assignees.length > 0) throw new Error(`canary fixture #${n} unexpectedly assigned after creation: ${fresh.assignees.join(",")}`);
        if (fresh.blockedByCount === undefined) throw new Error(`canary fixture #${n} blocked_by unknown after creation`);
        if (fresh.blockedByCount > 0) throw new Error(`canary fixture #${n} blocked_by ${fresh.blockedByCount}, not zero after creation`);
      } catch (e) {
        // Created but not provable — indeterminate, NOT committed. Persist a
        // durable indeterminate recovery receipt (the POST succeeded but
        // read-back/receipt persistence failed). Still return the id so
        // cleanup can occur.
        persistReceipt(receiptSink, { transition: "createCanaryFixture", issueNumber: n, kind: "indeterminate", reason: `canary fixture #${n} created but state not proven: ${getMsg(e)}`, code: "READBACK_FAILED" });
        return { id: n, committed: false, reason: `canary fixture #${n} created but state not proven: ${getMsg(e)}` };
      }
      // Receipt persistence failure must NOT report committed.
      const persisted = persistReceipt(receiptSink, { transition: "createCanaryFixture", issueNumber: n, kind: "committed", reason: `created canary fixture #${n}` });
      if (persisted.persistFailed) {
        return { id: n, committed: false, reason: `canary fixture #${n} created but receipt persistence failed: ${persisted.receipt.reason}` };
      }
      return { id: n, committed: true };
    },

    /**
     * Clean up a canary fixture — ONE adapter-owned cleanup operation, invoked
     * exactly once per fixture. A single verified saga removes stale machine
     * labels, removes the authenticated claimant assignee (preserving unrelated
     * assignees), and closes the issue. The final overall postcondition and
     * terminal revalidation prove: closed; claimant absent; agent:in-progress
     * absent; agent:implement absent; agent:blocked absent. ONE truthful typed
     * receipt per fixture. Throws on any failure (never swallows).
     */
    async cleanupCanaryFixture(id) {
      const claimantLogin = await gh.resolveClaimantLogin();
      const result = await runSaga(gh, id, "cleanupCanaryFixture", [
        {
          name: "remove-stale-labels",
          mutate: () => editIssue(id, [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED].flatMap((l) => ["--remove-label", l])),
          verifyAfter: (after) => {
            for (const l of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
              if (after.labels.includes(l)) return `${l} still present on canary fixture #${id}`;
            }
            return null;
          },
        },
        {
          name: "remove-claimant-assignee",
          validateBefore: (fresh) => {
            // Only remove the AUTHENTICATED claimant; unrelated assignees are
            // preserved. If the claimant is no longer assigned, nothing to do.
            if (!fresh.assignees.includes(claimantLogin)) return null;
            return null;
          },
          mutate: () => editIssue(id, ["--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} still assigned on canary fixture #${id}`;
            return null;
          },
        },
        {
          name: "close-fixture",
          mutate: async () => { await gh.run(["issue", "close", String(id), "--comment", "Canary fixture — cleaning up"]); },
          verifyAfter: (after) => {
            // Final overall postcondition: closed + claimant absent + all three
            // machine labels absent.
            if (after.state !== "closed") return `canary fixture #${id} not closed after close`;
            if (after.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} still assigned on closed canary fixture #${id}`;
            for (const l of [AGENT_IN_PROGRESS, AGENT_IMPLEMENT, AGENT_BLOCKED]) {
              if (after.labels.includes(l)) return `${l} still present on closed canary fixture #${id}`;
            }
            return null;
          },
        },
      ], receiptSink);
      if (result.kind !== "committed") {
        throw new Error(`canary fixture #${id} cleanup not committed: ${result.receipt.reason ?? result.receipt.code}`);
      }
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
        // Transport-owned repository identity — the inspection port never
        // parses git remotes locally.
        ownerRepo: gh.resolveOwnerRepo(),
      });

      // The adapter's own saga transitions — reconciliation never composes raw edits.
      // `adapter` is assigned by the time any transition port is invoked.
      const github: ReconcileGitHubTransitions = {
        // ONE two-step saga: release (proving exact ownership) then add-blocked
        // (re-proving the released intermediate state). Never split into a
        // release saga + a one-step addBlocked saga whose precondition could be
        // misclassified as a clean rejection.
        releaseAndBlockOwnedImplementation: (issueNumber) => adapter.releaseAndBlockOwnedImplementation(issueNumber),
        // Single owned release for the empty-branch cleanup path (no block).
        releaseOwnedImplementationClaim: (issueNumber) => adapter.releaseOwnedImplementationClaim(issueNumber),
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
  compensationProof: { proven: boolean; finalState: IssueSnapshot | null; violation?: string; unavailable?: boolean } | null,
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
    // Command-consumption state unavailable — the compensation cannot be
    // proven. Indeterminate, never "compensated".
    if (compensationProof.unavailable) {
      return {
        phase: "indeterminate",
        reason: `${result.reason}; compensation applied but post-mutation command-consumption state unavailable — cannot prove compensation`,
        code: "COMPENSATION_STATE_UNAVAILABLE",
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
