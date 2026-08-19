import { branchForIssue } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";

export interface IterationControlConfig {
  requestedIssueNumber?: string;
}

export interface IterationControl {
  maxIterations: number;
  requestedIssueNumber?: string;
}

export interface PlannedFromQualification {
  plannedIssues: Array<{ id: string; title: string; branch: string }>;
}

export type QualificationDecisionMode =
  | "qualified"
  | "qualify-unsupported"
  | "single-eligible"
  | "planner-required";

export interface IterationPlanningDecision {
  mode: QualificationDecisionMode;
  plannedIssues: Array<{ id: string; title: string; branch: string }>;
  skipIteration: boolean;
}

const DEFAULT_MAX_ITERATIONS = 10;

export function parseQualificationArgs(argv: string[]): IterationControlConfig {
  const args = new Map<string, string>();
  for (let i = 2; i < argv.length - 1; i += 2) {
    const name = argv[i];
    const value = argv[i + 1];
    if (!name || !value || !name.startsWith("--")) continue;
    args.set(name, value);
  }

  const rawIssue = args.get("--issue");
  if (!rawIssue) return {};

  const normalized = rawIssue.startsWith("#") ? rawIssue.slice(1) : rawIssue;
  const issueNumber = normalized.trim();
  if (/^\d+$/.test(issueNumber)) {
    return { requestedIssueNumber: issueNumber };
  }
  return {};
}

export function resolveIterationLimit(defaultLimit: number, control: IterationControlConfig): number {
  return control.requestedIssueNumber ? 1 : defaultLimit;
}

export function planQualificationIssue(
  eligibleIssues: IssueInput[],
  control: IterationControlConfig,
): PlannedFromQualification {
  if (!control.requestedIssueNumber) {
    return { plannedIssues: [] };
  }
  const selected = eligibleIssues.find((issue) => String(issue.number) === control.requestedIssueNumber);
  if (!selected) return { plannedIssues: [] };
  return {
    plannedIssues: [{
      id: String(selected.number),
      title: selected.title,
      branch: branchForIssue(selected.number),
    }],
  };
}

export function planIssuesForIteration(
  eligibleIssues: IssueInput[],
  control: IterationControlConfig,
): IterationPlanningDecision {
  const qualificationSelection = planQualificationIssue(eligibleIssues, control);
  if (qualificationSelection.plannedIssues.length > 0) {
    return {
      mode: "qualified",
      plannedIssues: qualificationSelection.plannedIssues,
      skipIteration: false,
    };
  }

  if (control.requestedIssueNumber) {
    return {
      mode: "qualify-unsupported",
      plannedIssues: [],
      skipIteration: true,
    };
  }

  if (eligibleIssues.length === 1) {
    return {
      mode: "single-eligible",
      plannedIssues: [{
        id: String(eligibleIssues[0]!.number),
        title: eligibleIssues[0]!.title,
        branch: branchForIssue(eligibleIssues[0]!.number),
      }],
      skipIteration: false,
    };
  }

  return {
    mode: "planner-required",
    plannedIssues: [],
    skipIteration: false,
  };
}

export function makeIterationControl(defaultMaxIterations: number, argv: string[]): IterationControl {
  const config = parseQualificationArgs(argv);
  return {
    maxIterations: resolveIterationLimit(defaultMaxIterations, config),
    requestedIssueNumber: config.requestedIssueNumber,
  };
}
