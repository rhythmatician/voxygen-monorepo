import { startMixedProfileBatch } from "./mixed-profile-coordinator.mts";
import {
  partitionWorkerOutcomes,
  partitionToMutationPlan,
  type WorkerOutcome,
  type WorkerMutationAction,
} from "./factory-verdict-gate.mts";
import type { ReviewVerdict } from "./review-verdict.mts";
import type { ResearchResult } from "./research-result.mts";
import type { BatchOrchestrationOps, ResearchBatchIssue, ResearchWorkerResult as LifecycleResearchWorkerResult, RunResearchWorker } from "./research-lifecycle.mts";

// ---------------------------------------------------------------------------
// Typed result algebra — exact from issue #191
// ---------------------------------------------------------------------------
export type FactoryIterationNext =
  | {
      kind: "continue";
      reason: "no-completed-implementation" | "qualification-complete" | "submission-complete";
    }
  | {
      kind: "stop";
      reason: "implementation-factory-error" | "submission-factory-error" | "research-factory-error";
    };

export type FactoryIterationResult = {
  next: FactoryIterationNext;
  implementation: {
    completedIds: string[];
    failedIds: string[];
    reviewRejectedIds: string[];
    factoryErrorIds: string[];
  };
  research: {
    succeededIds: string[];
    failedIds: string[];
    hadFactoryError: boolean;
  };
  submission?: {
    issueIds: string[];
    batchBranch?: string;
    pullRequest?: string;
  };
};

// ---------------------------------------------------------------------------
// Input — named prepared issue types, body required for research
// ---------------------------------------------------------------------------
export type PreparedImplIssue = { id: string; branch: string; title: string };
export type PreparedResearchIssue = { id: string; branch: string; title: string; body: string };

export type FactoryIterationInput = {
  implIssues: PreparedImplIssue[];
  researchIssues: PreparedResearchIssue[];
  initialResearchHadFactoryError?: boolean;
};

// ---------------------------------------------------------------------------
// Capability groups — small typed boundaries
// ---------------------------------------------------------------------------
export type ImplWorkerResult = {
  commits: string[];
  verdict: ReviewVerdict | null;
  reviewText?: string;
};

// Re-export lifecycle's ResearchWorkerResult for seam consistency but keep local alias
export type ResearchWorkerResult = LifecycleResearchWorkerResult;

export interface FactoryWorkers {
  runImplementation: (issue: PreparedImplIssue) => Promise<ImplWorkerResult>;
  runResearch: RunResearchWorker;
  researchOps: BatchOrchestrationOps;
}

export interface FactoryMutations {
  apply: (action: WorkerMutationAction) => Promise<void>;
}

export interface FactorySubmission {
  submit: (completed: PreparedImplIssue[]) => Promise<{
    issueIds: string[];
    batchBranch?: string;
    pullRequest?: string;
  }>;
}

export interface FactoryPolicy {
  mutateOutcomeState: boolean;
  integrate: boolean;
}

export interface FactoryLogger {
  info: (message: string) => void;
  warn: (message: string) => void;
  error: (message: string) => void;
}

export interface FactoryIterationDependencies {
  workers: FactoryWorkers;
  mutations: FactoryMutations;
  submission: FactorySubmission;
  policy: FactoryPolicy;
  logger: FactoryLogger;
}

// ---------------------------------------------------------------------------
// State machine — one settlement epilogue, memoized promise, fail-closed
// ---------------------------------------------------------------------------
export async function runFactoryIteration(
  input: FactoryIterationInput,
  dependencies: FactoryIterationDependencies,
): Promise<FactoryIterationResult> {
  const { implIssues, researchIssues, initialResearchHadFactoryError } = input;
  const { workers, mutations, submission, policy, logger } = dependencies;

  // Start research and implementation concurrently via production seam — typed without casts
  const mixed = startMixedProfileBatch({
    researchIssues,
    implIssues,
    runResearchWorker: workers.runResearch,
    runImplWorker: workers.runImplementation,
    ops: workers.researchOps,
    shouldMutateOutcomeState: policy.mutateOutcomeState,
  });

  // Idempotent settlement epilogue — exactly once via memoized promise, fail-closed on unexpected rejection
  let settlementPromise: Promise<{
    researchBatch: Awaited<ReturnType<typeof import("./research-lifecycle.mts").orchestrateResearchBatch>> | null;
    researchHadFactoryError: boolean;
  }> | null = null;

  const settleOnce = (): Promise<{
    researchBatch: Awaited<ReturnType<typeof import("./research-lifecycle.mts").orchestrateResearchBatch>> | null;
    researchHadFactoryError: boolean;
  }> => {
    if (settlementPromise) return settlementPromise;
    settlementPromise = (async () => {
      try {
        const res = await mixed.settleResearch();
        return { researchBatch: res.researchBatch, researchHadFactoryError: res.researchHadFactoryError };
      } catch {
        // Unexpected rejection escaping the whole research batch — FACTORY_ERROR, fail closed
        return { researchBatch: null, researchHadFactoryError: true };
      }
    })();
    return settlementPromise;
  };

  // Implementation completes without waiting for slow research
  const settledImpl = await mixed.implSettled;

  // Partition via existing production component
  const workerIssues = implIssues.map((i) => ({ id: i.id, branch: i.branch, title: i.title }));
  const outcomesForPartition: WorkerOutcome[] = settledImpl.map((s) => {
    if (s.status === "fulfilled") {
      const v = s.value;
      return {
        status: "fulfilled",
        value: {
          commits: v.commits,
          verdict: v.verdict,
          reviewText: v.reviewText,
        },
      };
    } else {
      const reason = String((s as PromiseRejectedResult).reason ?? "unknown");
      return { status: "rejected", reason };
    }
  });

  const partition = partitionWorkerOutcomes(workerIssues, outcomesForPartition);
  const mutationPlan = partitionToMutationPlan(partition);

  // Apply mutation plan respecting qualification policy
  // Mutation infrastructure exceptions (thrown) stop submission — fail closed
  let mutationHadFactoryError = false;
  if (policy.mutateOutcomeState) {
    for (const action of mutationPlan) {
      try {
        await mutations.apply(action);
      } catch (e) {
        mutationHadFactoryError = true;
        const msg = e instanceof Error ? e.message : String(e);
        logger.error(`mutation apply failed for #${action.issue.id}: ${msg}`);
        // Do not proceed to submission; settle research and return implementation-factory-error
        // Note: deliberately thrown infrastructure failures stop progression.
        // Helpers that return false (e.g., markBlocked returning false) do not throw and are not affected.
        break;
      }
    }
  } else {
    for (const action of mutationPlan) {
      logger.info(`[qualification] suppressed mutation ${action.kind} for #${action.issue.id}: ${action.reason.slice(0, 200)}`);
    }
  }

  // Provisional implementation/submission result before research settlement
  // Single epilogue: mutation infrastructure error is part of provisional decision
  let provisionalNext: FactoryIterationNext;
  let provisionalSubmission: FactoryIterationResult["submission"] | undefined;

  if (mutationHadFactoryError || partition.factoryErrors.length > 0) {
    provisionalNext = { kind: "stop", reason: "implementation-factory-error" };
  } else if (partition.completed.length === 0) {
    provisionalNext = { kind: "continue", reason: "no-completed-implementation" };
  } else if (!policy.integrate) {
    provisionalNext = { kind: "continue", reason: "qualification-complete" };
  } else {
    try {
      const receipt = await submission.submit(partition.completed.map((i) => ({ id: i.id, branch: i.branch, title: i.title ?? "" })));
      provisionalSubmission = receipt;
      provisionalNext = { kind: "continue", reason: "submission-complete" };
    } catch (e) {
      const reason = e instanceof Error ? e.message : String(e);
      logger.error(`submission failed: ${reason}`);
      provisionalNext = { kind: "stop", reason: "submission-factory-error" };
      provisionalSubmission = undefined;
    }
  }

  // Exactly one research-settlement epilogue before resolving — single exit funnel
  const { researchBatch: finalBatch, researchHadFactoryError: finalHadFactoryError } = await settleOnce();

  const researchSucceededIds = finalBatch?.succeededIds ?? [];
  const researchFailedIds = finalBatch?.failedIds ?? [];
  const hadFactoryError = finalHadFactoryError || !!initialResearchHadFactoryError;

  // Derive final decision: research factory error overrides continue, but not stop from impl/submission
  let finalNext: FactoryIterationNext = provisionalNext;
  if (hadFactoryError && provisionalNext.kind === "continue") {
    finalNext = { kind: "stop", reason: "research-factory-error" };
  }

  const implementation = {
    completedIds: partition.completed.map((i) => i.id),
    failedIds: partition.failed.map((i) => i.id),
    reviewRejectedIds: partition.reviewRejected.map((i) => i.id),
    factoryErrorIds: partition.factoryErrors.map((i) => i.id),
  };

  const research = {
    succeededIds: researchSucceededIds,
    failedIds: researchFailedIds,
    hadFactoryError: hadFactoryError,
  };

  const result: FactoryIterationResult = {
    next: finalNext,
    implementation,
    research,
    ...(provisionalSubmission ? { submission: provisionalSubmission } : {}),
  };

  return result;
}
