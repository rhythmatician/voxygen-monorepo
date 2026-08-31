import { branchForIssue } from "./dispatch.mts";
import type { IssueInput } from "./dispatch.mts";

export type QualificationRequest =
  | { kind: "normal" }
  | { kind: "qualify"; issueNumber: string; issueNumbers: string[] }
  | { kind: "live"; issueNumber: string; issueNumbers: string[] }
  | { kind: "invalid"; reason: string };

export interface IterationControlConfig {
  requestedIssueNumber?: string;
  requestedIssueNumbers?: string[];
}

export interface IterationControl {
  maxIterations: number;
  requestedIssueNumber?: string;
  requestedIssueNumbers?: string[];
  qualification: QualificationRequest;
}

export interface PlannedIssue {
  id: string;
  title: string;
  branch: string;
}

export interface PlannedFromQualification {
  plannedIssues: PlannedIssue[];
}

export type QualificationDecisionMode =
  | "qualified"
  | "qualify-unsupported"
  | "single-eligible"
  | "planner-required";

export interface IterationPlanningDecision {
  mode: QualificationDecisionMode;
  plannedIssues: PlannedIssue[];
  skipIteration: boolean;
}

function toPlannedIssue(issue: IssueInput): PlannedIssue {
  return {
    id: String(issue.number),
    title: issue.title,
    branch: branchForIssue(issue.number),
  };
}

export function issueBodyForPlannedIssue(plannedIssueId: string, eligibleIssues: IssueInput[]): string {
  const issue = eligibleIssues.find((candidate) => String(candidate.number) === plannedIssueId);
  if (!issue?.body) throw new Error(`No eligible issue contract found for #${plannedIssueId}`);
  return issue.body;
}

export interface QualificationLifecyclePolicy {
  claimExternalState: boolean;
  mutateOutcomeState: boolean;
  integrate: boolean;
}

const DEFAULT_MAX_ITERATIONS = 10;

export function parseQualificationArgs(argv: string[]): QualificationRequest {
  const collected: string[] = [];
  let liveRequested = false;
  for (let i = 2; i < argv.length; i++) {
    const name = argv[i];
    if (name === "--live" || name === "--apply") {
      liveRequested = true;
      continue;
    }
    if (name !== "--issue") continue;
    const value = argv[i + 1];
    if (!value || value.startsWith("--")) {
      return { kind: "invalid", reason: value ? `${name} ${value}` : name };
    }
    // Support comma-separated and repeated --issue (e.g. --issue 250,66 --issue 27)
    const parts = value.split(",").map((p) => p.trim()).filter(Boolean);
    for (const part of parts) collected.push(part);
    i++; // consume value
  }

  if (collected.length === 0) {
    if (liveRequested) return { kind: "invalid", reason: "--live without --issue" };
    return { kind: "normal" };
  }

  const issueNumbers: string[] = [];
  for (const raw of collected) {
    const normalized = raw.startsWith("#") ? raw.slice(1) : raw;
    const n = normalized.trim();
    if (!/^\d+$/.test(n)) return { kind: "invalid", reason: raw };
    issueNumbers.push(n);
  }
  // dedupe preserve order
  const deduped = [...new Set(issueNumbers)];
  const issueNumber = deduped[0]!;

  if (liveRequested) return { kind: "live", issueNumber, issueNumbers: deduped };
  return { kind: "qualify", issueNumber, issueNumbers: deduped };
}

export function resolveIterationLimit(defaultLimit: number, control: IterationControlConfig): number {
  return control.requestedIssueNumbers?.length || control.requestedIssueNumber ? 1 : defaultLimit;
}

function getRequestedNumbers(control: IterationControlConfig): string[] {
  if (control.requestedIssueNumbers && control.requestedIssueNumbers.length > 0) return control.requestedIssueNumbers;
  if (control.requestedIssueNumber) return [control.requestedIssueNumber];
  return [];
}

export function planQualificationIssue(
  eligibleIssues: IssueInput[],
  control: IterationControlConfig,
): PlannedFromQualification {
  const requested = getRequestedNumbers(control);
  if (requested.length === 0) {
    return { plannedIssues: [] };
  }
  const selected = eligibleIssues.filter((issue) => requested.includes(String(issue.number)));
  if (selected.length === 0) return { plannedIssues: [] };
  // preserve requested order
  const ordered = requested
    .map((id) => selected.find((s) => String(s.number) === id)!)
    .filter(Boolean);
  return {
    plannedIssues: ordered.map(toPlannedIssue),
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

  const requested = getRequestedNumbers(control);
  if (requested.length > 0) {
    return {
      mode: "qualify-unsupported",
      plannedIssues: [],
      skipIteration: true,
    };
  }

  if (eligibleIssues.length === 1) {
    return {
      mode: "single-eligible",
      plannedIssues: [toPlannedIssue(eligibleIssues[0]!)],
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
  const requestedIssueNumber = config.kind === "qualify" || config.kind === "live" ? config.issueNumber : undefined;
  const requestedIssueNumbers = config.kind === "qualify" || config.kind === "live" ? config.issueNumbers : undefined;
  return {
    maxIterations: resolveIterationLimit(defaultMaxIterations, { requestedIssueNumber, requestedIssueNumbers }),
    requestedIssueNumber,
    requestedIssueNumbers,
    qualification: config,
  };
}

export function planResearchForIteration(
  researchEligible: IssueInput[],
  implementEligible: IssueInput[],
  control: IterationControlConfig,
): PlannedIssue[] {
  const requested = getRequestedNumbers(control);
  if (requested.length === 0) {
    return researchEligible.map(toPlannedIssue);
  }
  const isResearchTarget = requested.some((id) => researchEligible.some((r) => String(r.number) === id));
  const isImplementTarget = requested.some((id) => implementEligible.some((r) => String(r.number) === id));
  // If any requested is research, dispatch only those research tickets (hard-limit)
  if (isResearchTarget) {
    return researchEligible.filter((r) => requested.includes(String(r.number))).map(toPlannedIssue);
  }
  if (isImplementTarget) {
    return [];
  }
  // Requested but not eligible in either profile — dispatch nothing
  return [];
}

export function qualificationLifecyclePolicy(control: QualificationRequest): QualificationLifecyclePolicy {
  switch (control.kind) {
    case "normal":
    case "live":
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
