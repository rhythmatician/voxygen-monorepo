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
} from "./tracker-operations.mts";
import type { GhTransport } from "./gh-transport.mts";

/**
 * tracker-adapter — the single adapter constructing ALL production tracker
 * operations. Consumers (main.mts, migration, canary) call named transitions
 * here; they never compose `gh issue edit` calls or know machine-state label
 * strings inline.
 *
 * Every named transition runs through one verified-saga sequencing primitive:
 *
 *   fresh read → validate before-state → mutate → fresh read →
 *   verify postcondition → compensate when defined → fresh-read compensation →
 *   persist typed receipt
 *
 * Typed result: committed | compensated | indeterminate(factoryError).
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
  kind: "committed" | "compensated" | "indeterminate";
  reason?: string;
  code?: string;
}

export type TrackerTransitionResult =
  | { kind: "committed"; before: IssueSnapshot; after: IssueSnapshot; receipt: TransitionReceipt }
  | { kind: "compensated"; before: IssueSnapshot; after: IssueSnapshot; receipt: TransitionReceipt }
  | { kind: "indeterminate"; lastObserved: IssueSnapshot | null; receipt: TransitionReceipt; factoryError: true };

export interface ReceiptSink {
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
  /** Validate the fresh before-state; return error code when invalid (no mutation attempted). */
  validateBefore?: (before: IssueInput) => string | null;
  /** The single mutation. */
  mutate: () => Promise<void>;
  /** Verify postcondition against a fresh read taken after mutation. Return null when satisfied. */
  verifyAfter: (after: IssueInput) => string | null;
  /**
   * Compensation. `safe` compensations roll back to before-state on failure of
   * mutate/verify. If compensation itself fails, result is indeterminate.
   * When undefined, a failed step is reported indeterminate directly (unsafe
   * to restore — e.g. release succeeded but blocked-add failed).
   */
  compensate?: () => Promise<boolean>;
}

function snapshot(issue: IssueInput): IssueSnapshot {
  return {
    number: issue.number,
    labels: [...issue.labels],
    assignees: [...issue.assignees],
    state: issue.state,
  };
}

async function fetchFresh(gh: GhTransport, issueNumber: number): Promise<IssueInput> {
  const rawJson = await gh.run([
    "issue", "view", String(issueNumber), "--json", "number,title,body,labels,assignees,state",
  ]);
  let raw: any;
  try { raw = JSON.parse(rawJson); } catch { throw new Error(`failed to parse issue view for #${issueNumber}`); }
  return {
    number: raw.number,
    title: raw.title,
    state: (raw.state?.toLowerCase() ?? "open") as "open" | "closed",
    labels: (raw.labels ?? []).map((l: any) => l.name),
    assignees: (raw.assignees ?? []).map((a: any) => a.login),
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
    // fresh read
    let fresh: IssueInput;
    try {
      fresh = await fetchFresh(gh, issueNumber);
    } catch (e) {
      const reason = `fresh read failed for #${issueNumber} (${step.name}): ${getMsg(e)}`;
      const receipt = persist(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
      return { kind: "indeterminate", lastObserved: beforeSnap, receipt, factoryError: true };
    }
    if (!beforeSnap) beforeSnap = snapshot(fresh);

    // validate before-state
    if (step.validateBefore) {
      const problem = step.validateBefore(fresh);
      if (problem) {
        const receipt = persist(sink, { transition, issueNumber, kind: "compensated", reason: problem, code: "PRECONDITION_FAILED" });
        return { kind: "compensated", before: beforeSnap, after: snapshot(fresh), receipt };
      }
    }

    // mutate
    try {
      await step.mutate();
    } catch (e) {
      return finishFailure(gh, issueNumber, transition, step, beforeSnap, snapshot(fresh), `mutation failed (${step.name}): ${getMsg(e)}`, sink);
    }

    // fresh read + verify postcondition
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

  // All steps done — final fresh read is the committed after-state.
  try {
    const final = await fetchFresh(gh, issueNumber);
    const receipt = persist(sink, { transition, issueNumber, kind: "committed" });
    return { kind: "committed", before: beforeSnap!, after: snapshot(final), receipt };
  } catch (e) {
    const reason = `final fresh read failed for #${issueNumber}: ${getMsg(e)}`;
    const receipt = persist(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "FETCH_FAILED" });
    return { kind: "indeterminate", lastObserved: beforeSnap, receipt, factoryError: true };
  }
}

/**
 * Failure path. Critical asymmetry honored: when a step defines no
 * compensation (unsafe to restore — e.g. claim released but agent:blocked add
 * failed), report indeterminate FACTORY_ERROR with recovery evidence instead
 * of reconstructing the original runnable state.
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
    // Unsafe to restore — preserve recovery evidence, do not reconstruct.
    const receipt = persist(sink, { transition, issueNumber, kind: "indeterminate", reason, code: "UNSAFE_TO_RESTORE" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
  let compensated: boolean;
  try {
    compensated = await step.compensate();
  } catch {
    compensated = false;
  }
  if (!compensated) {
    const receipt = persist(sink, { transition, issueNumber, kind: "indeterminate", reason: `${reason}; compensation failed`, code: "COMPENSATION_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
  // fresh-read compensation proof
  try {
    const afterComp = await fetchFresh(gh, issueNumber);
    const receipt = persist(sink, { transition, issueNumber, kind: "compensated", reason, code: "COMPENSATED" });
    return { kind: "compensated", before, after: snapshot(afterComp), receipt };
  } catch (e) {
    const r2 = `${reason}; compensation applied but verification read failed: ${getMsg(e)}`;
    const receipt = persist(sink, { transition, issueNumber, kind: "indeterminate", reason: r2, code: "COMPENSATION_VERIFY_FAILED" });
    return { kind: "indeterminate", lastObserved, receipt, factoryError: true };
  }
}

function getMsg(e: unknown): string {
  return e instanceof Error ? e.message : String(e);
}

function persist(sink: ReceiptSink, partial: Omit<TransitionReceipt, "at"> & { at?: string }): TransitionReceipt {
  const receipt: TransitionReceipt = { ...partial, at: partial.at ?? new Date().toISOString() } as TransitionReceipt;
  try {
    sink.persist(receipt);
  } catch {}
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

  return {
    /**
     * Implementation claim profile (explicit, per ADR 0010):
     *   add claimant + agent:in-progress, CONSUME agent:implement.
     */
    async claimImplementation(issue) {
      const n = issue.number;
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, n, "claimImplementation", [
        {
          name: "claim",
          validateBefore: (fresh) => {
            if (fresh.state !== "open") return `issue #${n} not open`;
            if (!fresh.labels.includes(READY_FOR_AGENT)) return `#${n} missing ${READY_FOR_AGENT}`;
            if (!fresh.labels.includes(AGENT_IMPLEMENT)) return `#${n} missing ${AGENT_IMPLEMENT}`;
            if (fresh.labels.includes(AGENT_IN_PROGRESS)) return `#${n} already ${AGENT_IN_PROGRESS}`;
            return null;
          },
          mutate: () => editIssue(n, ["--add-assignee", claimantLogin, "--add-label", AGENT_IN_PROGRESS, "--remove-label", AGENT_IMPLEMENT]),
          verifyAfter: (after) => {
            if (!after.labels.includes(READY_FOR_AGENT)) return `${READY_FOR_AGENT} lost`;
            if (after.labels.includes(AGENT_IMPLEMENT)) return `${AGENT_IMPLEMENT} not consumed`;
            if (!after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent`;
            if (!after.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned`;
            return null;
          },
          compensate: async () => {
            try {
              await editIssue(n, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]);
              return true;
            } catch { return false; }
          },
        },
      ], receiptSink);
    },

    /**
     * Research claim profile (explicit, distinct from implementation):
     *   add claimant + agent:in-progress, RETAIN wayfinder:research.
     */
    async claimResearch(issue) {
      const n = issue.number;
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, n, "claimResearch", [
        {
          name: "claim",
          validateBefore: (fresh) => {
            if (fresh.state !== "open") return `issue #${n} not open`;
            if (!fresh.labels.includes("wayfinder:research")) return `#${n} missing wayfinder:research`;
            if (fresh.labels.includes(AGENT_IN_PROGRESS)) return `#${n} already ${AGENT_IN_PROGRESS}`;
            return null;
          },
          mutate: () => editIssue(n, ["--add-assignee", claimantLogin, "--add-label", AGENT_IN_PROGRESS]),
          verifyAfter: (after) => {
            // wayfinder:research RETAINED — this is the genuine distinction from impl claims
            if (!after.labels.includes("wayfinder:research")) return `wayfinder:research not retained`;
            if (!after.labels.includes(AGENT_IN_PROGRESS)) return `${AGENT_IN_PROGRESS} absent`;
            if (!after.assignees.includes(claimantLogin)) return `claimant ${claimantLogin} not assigned`;
            return null;
          },
          compensate: async () => {
            try {
              await editIssue(n, ["--remove-label", AGENT_IN_PROGRESS, "--remove-assignee", claimantLogin]);
              return true;
            } catch { return false; }
          },
        },
      ], receiptSink);
    },

    /**
     * Blocked transition. Per ADR 0010 asymmetry: releasing the claim is safe
     * to compensate (re-add in-progress + assignee); adding agent:blocked is
     * NOT compensated by restoring agent:implement — that step has no
     * compensation, so its failure reports indeterminate FACTORY_ERROR.
     */
    async transitionToBlocked(issueNumber) {
      const claimantLogin = await gh.resolveClaimantLogin();
      return runSaga(gh, issueNumber, "transitionToBlocked", [
        {
          name: "release-claim",
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
     * comment, verify closed-and-clean postcondition.
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
          verifyAfter: (after) => (after.state === "closed" ? null : `issue #${issueNumber} not closed`),
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

// Re-export for consumers that need the underlying ops-level results.
export type { ClaimResult };
