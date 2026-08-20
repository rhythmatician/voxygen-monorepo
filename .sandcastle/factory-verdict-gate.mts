import { blockedReasonForVerdict, isVerdictApproved, type ReviewVerdict } from "./review-verdict.mts";

type WorkerValue = {
  commits: string[];
  verdict: ReviewVerdict | null;
  reviewText?: string;
};

export type WorkerOutcome =
  | { status: "fulfilled"; value: WorkerValue }
  | { status: "rejected"; reason: string };

export type WorkerIssue = { id: string; branch: string; title?: string };

export type WorkerPartition = {
  completed: WorkerIssue[];
  failed: Array<WorkerIssue & { reason: string }>;
  reviewRejected: Array<WorkerIssue & { reason: string; verdict: ReviewVerdict | null; reviewText?: string }>;
  factoryErrors: Array<WorkerIssue & { reason: string; verdict: ReviewVerdict | null; reviewText?: string }>;
  shouldStopOuterLoop: boolean;
};

export type WorkerMutationKind = "failed" | "reviewRejected" | "factoryError";

export type WorkerMutationAction = {
  kind: WorkerMutationKind;
  issue: WorkerIssue;
  reason: string;
  verdict?: ReviewVerdict | null;
  reviewText?: string;
};

export function partitionToMutationPlan(partition: WorkerPartition): WorkerMutationAction[] {
  const actions: WorkerMutationAction[] = [];

  for (const issue of partition.failed) {
    actions.push({
      kind: "failed",
      issue,
      reason: issue.reason,
    });
  }

  for (const issue of partition.reviewRejected) {
    actions.push({
      kind: "reviewRejected",
      issue: { id: issue.id, branch: issue.branch, title: issue.title },
      reason: issue.reason,
      verdict: issue.verdict,
      reviewText: issue.reviewText,
    });
  }

  for (const issue of partition.factoryErrors) {
    actions.push({
      kind: "factoryError",
      issue: { id: issue.id, branch: issue.branch, title: issue.title },
      reason: issue.reason,
      verdict: issue.verdict,
      reviewText: issue.reviewText,
    });
  }

  return actions;
}

export function canClaimNextOuterIteration(partition: WorkerPartition): boolean {
  return partition.factoryErrors.length === 0;
}

export function partitionWorkerOutcomes(issues: WorkerIssue[], settled: WorkerOutcome[]): WorkerPartition {
  const completed: WorkerIssue[] = [];
  const failed: Array<WorkerIssue & { reason: string }> = [];
  const reviewRejected: Array<WorkerIssue & { reason: string; verdict: ReviewVerdict | null; reviewText?: string }> = [];
  const factoryErrors: Array<WorkerIssue & { reason: string; verdict: ReviewVerdict | null; reviewText?: string }> = [];

  for (let i = 0; i < issues.length; i++) {
    const issue = issues[i]!;
    const outcome = settled[i];
    if (!outcome) {
      failed.push({ ...issue, reason: "missing worker result" });
      continue;
    }

    if (outcome.status === "rejected") {
      failed.push({ ...issue, reason: String(outcome.reason) });
      continue;
    }

    if (outcome.value.commits.length === 0) {
      failed.push({ ...issue, reason: "no commits produced" });
      continue;
    }

    const verdict = outcome.value.verdict;

    if (verdict === null) {
      factoryErrors.push({
        ...issue,
        verdict: null,
        reviewText: outcome.value.reviewText,
        reason: blockedReasonForVerdict(null),
      });
      continue;
    }

    if (!isVerdictApproved(verdict)) {
      reviewRejected.push({
        ...issue,
        verdict,
        reviewText: outcome.value.reviewText,
        reason: blockedReasonForVerdict(verdict),
      });
      continue;
    }

    completed.push(issue);
  }

  return {
    completed,
    failed,
    reviewRejected,
    factoryErrors,
    shouldStopOuterLoop: factoryErrors.length > 0,
  };
}
