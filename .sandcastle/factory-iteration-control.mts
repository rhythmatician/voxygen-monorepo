import { branchForIssue } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";

export interface IterationControlConfig {
  requestedIssueNumber?: string;
  invalidRequestedIssue?: string;
}

export interface IterationControl {
  maxIterations: number;
  requestedIssueNumber?: string;
  invalidRequestedIssue?: string;
}

export interface PlannedFromQualification {
  plannedIssues: Array<{ id: string; title: string; branch: string }>;
}

export type QualificationDecisionMode =
  | "qualified"
  | "qualify-unsupported"
  | "qualify-invalid"
  | "single-eligible"
  | "planner-required";

export interface IterationPlanningDecision {
  mode: QualificationDecisionMode;
  plannedIssues: Array<{ id: string; title: string; branch: string }>;
  skipIteration: boolean;
}

const DEFAULT_MAX_ITERATIONS = 10;

export function parseQualificationArgs(argv: string[]): IterationControlConfig {
  let requestedIssueValue: string | undefined;
  let invalidRequestedIssue: string | undefined;
  for (let i = 2; i < argv.length; i++) {
    const name = argv[i];
    if (name !== "--issue") continue;
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      invalidRequestedIssue = value ? `${name} ${value}` : name;
      break;
    }
    requestedIssueValue = value;
    break;
  }

  if (requestedIssueValue === undefined) {
    if (invalidRequestedIssue) return { invalidRequestedIssue };
    return {};
  }

  const normalized = requestedIssueValue.startsWith("#") ? requestedIssueValue.slice(1) : requestedIssueValue;
  const issueNumber = normalized.trim();
  if (/^\d+$/.test(issueNumber)) return { requestedIssueNumber: issueNumber };

  return { invalidRequestedIssue: requestedIssueValue };
}

export function resolveIterationLimit(defaultLimit: number, control: IterationControlConfig): number {
  if (control.invalidRequestedIssue) return 0;
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

  if (control.invalidRequestedIssue) {
    return {
      mode: "qualify-invalid",
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
    invalidRequestedIssue: config.invalidRequestedIssue,
  };
}
