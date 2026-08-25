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
  type ClaimResult,
  type ClaimOps,
  type ResearchClaimOps,
} from "./tracker-operations.mts";
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
      const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
      return { kind: "indeterminate", lastObserved: beforeSnap, receipt, factoryError: true };
    }
    if (!beforeSnap) beforeSnap = snapshot(fresh);

    if (step.validateBefore) {
      const problem = step.validateBefore(fresh);
      if (problem) {
        const receipt = persistOrIndeterminate(sink, {
          transition, issueNumber, kind: "rejected", reason: problem, code: "PRECONDITION_FAILED",
          before: beforeSnap, after: snapshot(fresh),
        });
        return { kind: "rejected", before: beforeSnap, after: snapshot(fresh), receipt };
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
    const receipt = persistOrIndeterminate(sink, {
      transition, issueNumber, kind: "committed", before: beforeSnap!, after: snapshot(final),
    });
    return { kind: "committed", before: beforeSnap!, after: snapshot(final), receipt };
  } catch (e) {
    const reason = `final fresh read failed for #${issueNumber}: ${getMsg(e)}`;
    const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
    return { kind: "indeterminate", lastObserved: beforeSnap, receipt, factoryError: true };
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
    const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "UNSAFE_TO_RESTORE" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
  let compensated: boolean;
  try {
    compensated = await step.compensate();
  } catch {
    compensated = false;
  }
  if (!compensated) {
    const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason: `${reason}; compensation failed`, code: "COMPENSATION_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
  // Prove the compensation reached its intended safe state on a fresh read.
  try {
    const afterComp = await fetchFresh(gh, issueNumber);
    const compViolation = step.verifyCompensation
      ? step.verifyCompensation(afterComp)
      : null;
    if (compViolation) {
      const r2 = `${reason}; compensation applied but safe state not proven: ${compViolation}`;
      const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason: r2, code: "COMPENSATION_VERIFY_FAILED" });
      return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
    }
    const receipt = persistOrIndeterminate(sink, {
      transition, issueNumber, kind: "compensated", reason, code: "COMPENSATED",
      before, after: snapshot(afterComp),
    });
    return { kind: "compensated", before, after: snapshot(afterComp), receipt };
  } catch (e) {
    const r2 = `${reason}; compensation applied but verification read failed: ${getMsg(e)}`;
    const receipt = persistOrIndeterminate(sink, { transition, issueNumber, kind: "indeterminate", reason: r2, code: "COMPENSATION_VERIFY_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
}

function getMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

/**
 * Persist a receipt WITHOUT suppressing sink failures. If persistence throws,
 * wrap into an indeterminate FACTORY_ERROR receipt (persisted best-effort)
 * so a committed result can never lack durable evidence silently.
 */
function persistOrIndeterminate(sink: ReceiptSink, partial: Omit<TransitionReceipt, "at"> & { at?: string }): TransitionReceipt {
  const receipt: TransitionReceipt = { ...partial, at: partial.at ?? new Date().toISOString() } as TransitionReceipt;
  try {
    sink.persist(receipt);
  } catch (e) {
    const sinkError = getMsg(e);
    const failureReceipt: TransitionReceipt = {
      ...receipt,
      kind: "indeterminate",
      code: "RECEIPT_PERSIST_FAILED",
      reason: `${partial.reason ?? "transition completed"}; receipt persistence failed: ${sinkError}`,
    };
    try { sink.persist(failureReceipt); } catch {}
    return failureReceipt;
  }
  return receipt;
}

// ---------------------------------------------------------------------------
// Adapter
// ---------------------------------------------------------------------------

export interface TrackerAdapterDeps {
  gh: GhTransport;
  receiptSink: ReceiptSink;
}

export interface TrackerAdapter {
  claimImplementation(issue: IssueInput): Promise<TrackerTransitionResult>;
  claimResearch(issue: IssueInput): Promise<TrackerTransitionResult>;
  transitionToBlocked(issueNumber: number): Promise<TrackerTransitionResult>;
  releaseAfterFactoryError(issueNumber: number): Promise<TrackerTransitionResult>;
  finalizeIntegrated(issueNumber: number, branch: string): Promise<TrackerTransitionResult>;
  cleanupClosedIssueStaleLabels(issue: IssueInput): Promise<{ cleaned: boolean; removed: string[] }>;
  comment(issueNumber: number, body: string): Promise<boolean>;
}

export function createTrackerAdapter(deps: TrackerAdapterDeps): TrackerAdapter {
  const { gh, receiptSink } = deps;

  async function editIssue(issueNumber: number, args: string[]): Promise<void> {
    await gh.run(["issue", "edit", String(issueNumber), ...args]);
  }

  async function fetchById(id: string): Promise<IssueInput> {
    return fetchFresh(gh, Number(id));
  }

  return {
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
      const ops: ClaimOps & { claimantLogin: string } = {
        claimantLogin,
        fetchIssue: (id) => fetchById(id),
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
      const result = await claimImplementation(String(n), issue, ops);
      return claimResultToTransition("claimImplementation", n, result, receiptSink);
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
      const ops: ResearchClaimOps & { claimantLogin: string } = {
        claimantLogin,
        fetchIssue: (id) => fetchById(id),
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
      return claimResultToTransition("claimResearch", n, result, receiptSink);
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
          // Validate the exact claimed before-state BEFORE releasing — blind
          // compensation could otherwise create a claim that never existed.
          validateBefore: (fresh) => {
            if (fresh.state !== "open") return `issue #${issueNumber} not open`;
            if (!fresh.labels.includes(AGENT_IN_PROGRESS)) return `#${issueNumber} missing ${AGENT_IN_PROGRESS} — nothing to release`;
            if (!fresh.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned on #${issueNumber} — refusing to create a claim`;
            return null;
          },
          mutate: () => editIssue(issueNumber, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]),
          verifyAfter: (after) => {
            if (after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} still present`;
            if (after.assignees.includes(claimantLogin)) return `claimant still assigned`;
            return null;
          },
          compensate: async () => {
            try {
              await editIssue(issueNumber, ["--add-label", AGENT_IN_PROGRESS, "--add-assignee", claimantLogin]);
              return true;
            } catch { return false; }
          },
          verifyCompensation: (afterComp) => {
            if (!afterComp.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent after restore`;
            if (!afterComp.assignees.includes(claimantLogin)) return `claimant absent after restore`;
            if (afterComp.labels.includes(AGENT_BLOCKED)) return `${AGENT_BLOCKED} present after restore`;
            return null;
          },
        },
        {
          name: "add-blocked",
          // No compensation: restoring agent:implement would be unsafe.
          mutate: () => editIssue(issueNumber, ["--add-label", AGENT_BLOCKED]),
          verifyAfter: (after) =>
            after.labels.includes(AGENT_BLOCKED) ? null : `${AGENT_BLOCKED} absent`,
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

    /** Closed-issue stale label cleanup (delegates decision to tracker-operations). */
    async cleanupClosedIssueStaleLabels(issue) {
      return cleanupClosedIssue(issue, {
        removeLabel: async (id, label) => {
          try {
            await editIssue(Number(id), ["--remove-label", label]);
            return true;
          } catch { return false; }
        },
      });
    },

    async comment(issueNumber, body) {
      try {
        await gh.run(["issue", "comment", String(issueNumber), "--body", body]);
        return true;
      } catch { return false; }
    },
  };
}

/**
 * Convert a canonical ClaimResult into the typed transition taxonomy:
 * success → committed; ineligible-before-mutation → rejected; mutation/
 * verification failure with proven compensation → compensated; anything
 * uncertain → indeterminate FACTORY_ERROR.
 */
function claimResultToTransition(
  transition: string,
  issueNumber: number,
  result: ClaimResult,
  sink: ReceiptSink,
): TrackerTransitionResult {
  const base = { transition, issueNumber } as const;
  if (result.success) {
    // Canonical claimImplementation returns the post-claim state; reconstruct
    // the before-state from it: implement was consumed, in-progress/claimant added.
    const after = snapshot(result.issue);
    const before: IssueSnapshot = {
      ...after,
      labels: after.labels
        .filter((l) => l !== AGENT_IN_PROGRESS)
        .concat(after.assignees.length > 0 ? [AGENT_IMPLEMENT] : []),
    };
    const receipt = persistOrIndeterminate(sink, { ...base, kind: "committed", before, after });
    return { kind: "committed", before, after, receipt };
  }
  const lastObserved = result.issue ? snapshot(result.issue) : null;

  // Precondition/eligibility failures happen BEFORE any mutation — rejected.
  const isPrecondition =
    result.code === "NOT_ELIGIBLE" ||
    result.code === "ELIGIBILITY_FAILED" ||
    (result.compensated === true && !result.factoryError && lastObserved !== null && lastObserved.labels.includes(AGENT_IN_PROGRESS) === false);

  if (isPrecondition && lastObserved) {
    const receipt = persistOrIndeterminate(sink, {
      ...base, kind: "rejected", reason: result.reason, code: result.code, before: lastObserved, after: lastObserved,
    });
    return { kind: "rejected", before: lastObserved, after: lastObserved, receipt };
  }

  if (result.factoryError || !result.compensated) {
    const receipt = persistOrIndeterminate(sink, {
      ...base, kind: "indeterminate", reason: result.reason, code: result.code, lastObserved,
    });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }

  // Compensated path — canonical ops verified compensation (compensated=true,
  // no factoryError); record as compensated with observed state as evidence.
  const after = lastObserved ?? ({ number: issueNumber, labels: [], assignees: [], state: "open" } as IssueSnapshot);
  const receipt = persistOrIndeterminate(sink, {
    ...base, kind: "compensated", reason: result.reason, code: result.code, after,
  });
  return { kind: "compensated", before: after, after, receipt };
}

// Re-export for consumers that need the underlying ops-level results.
export type { ClaimResult };
