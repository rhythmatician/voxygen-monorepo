import { branchForIssue } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";

export type QualificationRequest =
  | { kind: "normal" }
  | { kind: "qualify"; issueNumber: string }
  | { kind: "invalid"; reason: string };

export interface IterationControlConfig {
  requestedIssueNumber?: string;
}

export interface IterationControl {
  maxIterations: number;
  requestedIssueNumber?: string;
  qualification: QualificationRequest;
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

export interface QualificationLifecyclePolicy {
  claimExternalState: boolean;
  mutateOutcomeState: boolean;
  integrate: boolean;
}

const DEFAULT_MAX_ITERATIONS = 10;

export function parseQualificationArgs(argv: string[]): QualificationRequest {
  let requestedIssueValue: string | undefined;
  for (let i = 2; i < argv.length; i++) {
    const name = argv[i];
    if (name !== "--issue") continue;
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      return { kind: "invalid", reason: value ? `${name} ${value}` : name };
    }
    requestedIssueValue = value;
    break;
  }

  if (requestedIssueValue === undefined) return { kind: "normal" };

  const normalized = requestedIssueValue.startsWith("#") ? requestedIssueValue.slice(1) : requestedIssueValue;
  const issueNumber = normalized.trim();
  if (/^\d+$/.test(issueNumber)) return { kind: "qualify", issueNumber };

  return { kind: "invalid", reason: requestedIssueValue };
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
  const requestedIssueNumber = config.kind === "qualify" ? config.issueNumber : undefined;
  return {
    maxIterations: resolveIterationLimit(defaultMaxIterations, { requestedIssueNumber }),
    requestedIssueNumber,
    qualification: config,
  };
}

export function qualificationLifecyclePolicy(control: QualificationRequest): QualificationLifecyclePolicy {
  switch (control.kind) {
    case "normal":
      return {
        claimExternalState: true,
        mutateOutcomeState: true,
        integrate: true,
      };

    case "qualify":
    case "invalid":
      return {
        claimExternalState: false,
        mutateOutcomeState: false,
        integrate: false,
      };

    default:
      throw new Error(`Unhandled qualification kind: ${(control as { kind: string }).kind}`);
  }
}
