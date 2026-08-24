import { missingTracerConcepts } from "./tracer-contract.mts";

/**
 * Tracker policy — canonical label roles and eligibility per ADR 0010.
 *
 * Pure, production-used seam. Both dispatch and tests import this module;
 * no separate test policy.
 */

// ---------------------------------------------------------------------------
// Canonical labels
// ---------------------------------------------------------------------------

export const WAYFINDER_MAP = "wayfinder:map" as const;
export const WAYFINDER_RESEARCH = "wayfinder:research" as const;
export const WAYFINDER_PROTOTYPE = "wayfinder:prototype" as const;
export const WAYFINDER_GRILLING = "wayfinder:grilling" as const;
export const WAYFINDER_TASK = "wayfinder:task" as const;

export const WAYFINDER_TYPES = [
  WAYFINDER_MAP,
  WAYFINDER_RESEARCH,
  WAYFINDER_PROTOTYPE,
  WAYFINDER_GRILLING,
  WAYFINDER_TASK,
] as const;

export type WayfinderType = typeof WAYFINDER_TYPES[number];

// Retired labels
export const WAYFINDER_PRESERVE_FUTURES_RETIRED = "wayfinder:preserve-futures" as const;
export const AGENT_RESEARCH_RETIRED = "agent:research" as const;

export const RETIRED_LABELS = [
  AGENT_RESEARCH_RETIRED,
  WAYFINDER_PRESERVE_FUTURES_RETIRED,
] as const;

// Triage durable
export const NEEDS_TRIAGE = "needs-triage" as const;
export const NEEDS_INFO = "needs-info" as const;
export const READY_FOR_AGENT = "ready-for-agent" as const;
export const READY_FOR_HUMAN = "ready-for-human" as const;
export const WONTFIX = "wontfix" as const;

export const TRIAGE_DURABLE = [
  NEEDS_TRIAGE,
  NEEDS_INFO,
  READY_FOR_AGENT,
  READY_FOR_HUMAN,
  WONTFIX,
] as const;

// Sandcastle commands / transient
export const AGENT_IMPLEMENT = "agent:implement" as const;
export const AGENT_REVIEW = "agent:review" as const;
export const AGENT_IN_PROGRESS = "agent:in-progress" as const;
export const AGENT_BLOCKED = "agent:blocked" as const;

export const COMMAND_LABELS = [AGENT_IMPLEMENT, AGENT_REVIEW] as const;
export const TRANSIENT_LABELS = [AGENT_IN_PROGRESS, AGENT_BLOCKED] as const;

// ---------------------------------------------------------------------------
// Issue input
// ---------------------------------------------------------------------------

export interface IssueInput {
  number: number;
  title: string;
  state: "open" | "closed";
  labels: string[];
  assignees: string[];
  blockedByCount?: number;
  body?: string;
}

// ---------------------------------------------------------------------------
// Contradiction identities — structured for dispatch logs, receipts, tests
// ---------------------------------------------------------------------------

export const CONTRADICTION_CODES = {
  MULTIPLE_WAYFINDER: "MULTIPLE_WAYFINDER",
  RESEARCH_WITH_IMPLEMENT: "RESEARCH_WITH_IMPLEMENT",
  PROTOTYPE_WITH_READY_AGENT: "PROTOTYPE_WITH_READY_AGENT",
  PROTOTYPE_WITH_IMPLEMENT: "PROTOTYPE_WITH_IMPLEMENT",
  GRILLING_WITH_READY_AGENT: "GRILLING_WITH_READY_AGENT",
  GRILLING_WITH_IMPLEMENT: "GRILLING_WITH_IMPLEMENT",
  TASK_BOTH_READY: "TASK_BOTH_READY",
  TASK_MISSING_READINESS: "TASK_MISSING_READINESS",
  IMPLEMENT_WITHOUT_READY: "IMPLEMENT_WITHOUT_READY",
  IMPLEMENT_WITH_IN_PROGRESS: "IMPLEMENT_WITH_IN_PROGRESS",
  RETIRED_AGENT_RESEARCH: "RETIRED_AGENT_RESEARCH",
  RETIRED_PRESERVE_FUTURES: "RETIRED_PRESERVE_FUTURES",
  MAP_WITH_COMMAND: "MAP_WITH_COMMAND",
} as const;

export type ContradictionCode = typeof CONTRADICTION_CODES[keyof typeof CONTRADICTION_CODES];

export interface Contradiction {
  code: ContradictionCode;
  reason: string;
  labels?: string[];
}

export interface ValidationResult {
  valid: boolean;
  contradictions: Contradiction[];
  warnings: Contradiction[]; // normalization-safe residue, not authority
  retired: Contradiction[];
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

export function getWayfinderLabels(issue: IssueInput): string[] {
  const allWayfinder = [...WAYFINDER_TYPES, WAYFINDER_PRESERVE_FUTURES_RETIRED] as string[];
  return issue.labels.filter((l) => allWayfinder.includes(l));
}

export function getWayfinderTypesCount(issue: IssueInput): number {
  return getWayfinderLabels(issue).length;
}

export function hasLabel(issue: IssueInput, label: string): boolean {
  return issue.labels.includes(label);
}

// ---------------------------------------------------------------------------
// Contradiction detection — pure, production-used
// ---------------------------------------------------------------------------

export function detectContradictions(issue: IssueInput): ValidationResult {
  const contradictions: Contradiction[] = [];
  const warnings: Contradiction[] = [];
  const retired: Contradiction[] = [];

  const labels = issue.labels;
  const has = (l: string) => labels.includes(l);
  const wayfinderLabels = getWayfinderLabels(issue);
  const wayfinderCount = wayfinderLabels.length;

  // Multiple wayfinder types
  if (wayfinderCount > 1) {
    contradictions.push({
      code: CONTRADICTION_CODES.MULTIPLE_WAYFINDER,
      reason: `multiple wayfinder types: ${wayfinderLabels.join(", ")}`,
      labels: [...wayfinderLabels],
    });
  }

  // Retired labels
  if (has(AGENT_RESEARCH_RETIRED)) {
    retired.push({
      code: CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH,
      reason: `retired label ${AGENT_RESEARCH_RETIRED} present`,
      labels: [AGENT_RESEARCH_RETIRED],
    });
    contradictions.push({
      code: CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH,
      reason: `retired label ${AGENT_RESEARCH_RETIRED} present — fail closed for new work`,
      labels: [AGENT_RESEARCH_RETIRED],
    });
  }
  if (has(WAYFINDER_PRESERVE_FUTURES_RETIRED)) {
    retired.push({
      code: CONTRADICTION_CODES.RETIRED_PRESERVE_FUTURES,
      reason: `retired label ${WAYFINDER_PRESERVE_FUTURES_RETIRED} present`,
      labels: [WAYFINDER_PRESERVE_FUTURES_RETIRED],
    });
    contradictions.push({
      code: CONTRADICTION_CODES.RETIRED_PRESERVE_FUTURES,
      reason: `retired label ${WAYFINDER_PRESERVE_FUTURES_RETIRED} present — use ${WAYFINDER_TASK} + ${READY_FOR_AGENT}`,
      labels: [WAYFINDER_PRESERVE_FUTURES_RETIRED],
    });
  }

  // wayfinder:research + agent:implement
  if (has(WAYFINDER_RESEARCH) && has(AGENT_IMPLEMENT)) {
    contradictions.push({
      code: CONTRADICTION_CODES.RESEARCH_WITH_IMPLEMENT,
      reason: `${WAYFINDER_RESEARCH} with ${AGENT_IMPLEMENT} — research is AFK via wayfinder:research frontier dispatch, not implement command`,
      labels: [WAYFINDER_RESEARCH, AGENT_IMPLEMENT],
    });
  }

  // wayfinder:prototype with ready-for-agent or implement
  if (has(WAYFINDER_PROTOTYPE) && has(READY_FOR_AGENT)) {
    contradictions.push({
      code: CONTRADICTION_CODES.PROTOTYPE_WITH_READY_AGENT,
      reason: `${WAYFINDER_PROTOTYPE} with ${READY_FOR_AGENT} — prototype is HITL only`,
      labels: [WAYFINDER_PROTOTYPE, READY_FOR_AGENT],
    });
  }
  if (has(WAYFINDER_PROTOTYPE) && has(AGENT_IMPLEMENT)) {
    contradictions.push({
      code: CONTRADICTION_CODES.PROTOTYPE_WITH_IMPLEMENT,
      reason: `${WAYFINDER_PROTOTYPE} with ${AGENT_IMPLEMENT} — prototype is HITL only`,
      labels: [WAYFINDER_PROTOTYPE, AGENT_IMPLEMENT],
    });
  }

  // wayfinder:grilling with ready-for-agent or implement
  if (has(WAYFINDER_GRILLING) && has(READY_FOR_AGENT)) {
    contradictions.push({
      code: CONTRADICTION_CODES.GRILLING_WITH_READY_AGENT,
      reason: `${WAYFINDER_GRILLING} with ${READY_FOR_AGENT} — grilling is HITL only`,
      labels: [WAYFINDER_GRILLING, READY_FOR_AGENT],
    });
  }
  if (has(WAYFINDER_GRILLING) && has(AGENT_IMPLEMENT)) {
    contradictions.push({
      code: CONTRADICTION_CODES.GRILLING_WITH_IMPLEMENT,
      reason: `${WAYFINDER_GRILLING} with ${AGENT_IMPLEMENT} — grilling is HITL only`,
      labels: [WAYFINDER_GRILLING, AGENT_IMPLEMENT],
    });
  }

  // wayfinder:task with both ready states
  if (has(WAYFINDER_TASK) && has(READY_FOR_AGENT) && has(READY_FOR_HUMAN)) {
    contradictions.push({
      code: CONTRADICTION_CODES.TASK_BOTH_READY,
      reason: `${WAYFINDER_TASK} with both ${READY_FOR_AGENT} and ${READY_FOR_HUMAN} — exactly one required`,
      labels: [WAYFINDER_TASK, READY_FOR_AGENT, READY_FOR_HUMAN],
    });
  }

  // wayfinder:task missing readiness — for migration, task must have exactly one readiness
  // This is not in the minimum list but required for valid task classification; treat as contradiction for migration,
  // but allow dispatch to treat as ineligible without blocking other profiles? For now add as warning/contradiction
  // that dispatch will surface but not block research; for task classification it's invalid.
  // We will push to warnings if not both present? But to satisfy migration check, we need to detect.
  // Instead, handle separately via task classification validator, not here, to avoid breaking ordinary impl.
  // So NOT adding here.

  // agent:implement without ready-for-agent
  if (has(AGENT_IMPLEMENT) && !has(READY_FOR_AGENT)) {
    // Need to ensure this is not double-counted with research+implement already flagged
    // But it's still a separate contradiction — implement requires readiness
    contradictions.push({
      code: CONTRADICTION_CODES.IMPLEMENT_WITHOUT_READY,
      reason: `${AGENT_IMPLEMENT} without ${READY_FOR_AGENT} — implement is one-shot command paired with durable readiness`,
      labels: [AGENT_IMPLEMENT],
    });
  }

  // agent:implement + agent:in-progress
  if (has(AGENT_IMPLEMENT) && has(AGENT_IN_PROGRESS)) {
    contradictions.push({
      code: CONTRADICTION_CODES.IMPLEMENT_WITH_IN_PROGRESS,
      reason: `${AGENT_IMPLEMENT} with ${AGENT_IN_PROGRESS} — command must be consumed before in-progress`,
      labels: [AGENT_IMPLEMENT, AGENT_IN_PROGRESS],
    });
  }

  // wayfinder:map with command or readiness — map never executable
  if (has(WAYFINDER_MAP) && (has(AGENT_IMPLEMENT) || has(AGENT_IN_PROGRESS) || has(READY_FOR_AGENT))) {
    contradictions.push({
      code: CONTRADICTION_CODES.MAP_WITH_COMMAND,
      reason: `${WAYFINDER_MAP} must not carry execution labels`,
      labels: [WAYFINDER_MAP],
    });
  }

  // Historical redundancy handling — warnings, not contradictions
  // Research + ready-for-agent is matching redundancy, removable residue, not authority
  if (has(WAYFINDER_RESEARCH) && has(READY_FOR_AGENT)) {
    // Only if not already contradictory with implement? If has implement, it's already error, not warning
    if (!has(AGENT_IMPLEMENT)) {
      warnings.push({
        code: CONTRADICTION_CODES.RESEARCH_WITH_IMPLEMENT, // reuse? Better distinct but keep simple
        reason: `${WAYFINDER_RESEARCH} with ${READY_FOR_AGENT} is historical redundancy — removable residue, not authorization`,
        labels: [WAYFINDER_RESEARCH, READY_FOR_AGENT],
      });
      // Also mark as retired-like residue? Keep as warning
    }
  }
  if (has(WAYFINDER_PROTOTYPE) && has(READY_FOR_HUMAN)) {
    warnings.push({
      code: CONTRADICTION_CODES.PROTOTYPE_WITH_READY_AGENT,
      reason: `${WAYFINDER_PROTOTYPE} with ${READY_FOR_HUMAN} is historical redundancy — removable residue`,
      labels: [WAYFINDER_PROTOTYPE, READY_FOR_HUMAN],
    });
  }
  if (has(WAYFINDER_GRILLING) && has(READY_FOR_HUMAN)) {
    warnings.push({
      code: CONTRADICTION_CODES.GRILLING_WITH_READY_AGENT,
      reason: `${WAYFINDER_GRILLING} with ${READY_FOR_HUMAN} is historical redundancy — removable residue`,
      labels: [WAYFINDER_GRILLING, READY_FOR_HUMAN],
    });
  }

  const valid = contradictions.length === 0;
  return { valid, contradictions, warnings, retired };
}

export function hasContradiction(issue: IssueInput): boolean {
  return !detectContradictions(issue).valid;
}

export function getRetiredLabels(issue: IssueInput): string[] {
  return issue.labels.filter((l) => (RETIRED_LABELS as readonly string[]).includes(l));
}

export function getRemovableResidueLabels(issue: IssueInput): string[] {
  const residue: string[] = [];
  const has = (l: string) => issue.labels.includes(l);
  if (has(WAYFINDER_RESEARCH) && has(READY_FOR_AGENT)) residue.push(READY_FOR_AGENT);
  if (has(WAYFINDER_PROTOTYPE) && has(READY_FOR_HUMAN)) residue.push(READY_FOR_HUMAN);
  if (has(WAYFINDER_GRILLING) && has(READY_FOR_HUMAN)) residue.push(READY_FOR_HUMAN);
  // Retired labels are also removable
  for (const r of RETIRED_LABELS) if (has(r)) residue.push(r);
  return [...new Set(residue)];
}

// ---------------------------------------------------------------------------
// Task executor consistency
// ---------------------------------------------------------------------------

export function validateTaskClassification(issue: IssueInput): ValidationResult {
  const contradictions: Contradiction[] = [];
  const warnings: Contradiction[] = [];
  const retired: Contradiction[] = [];
  const has = (l: string) => issue.labels.includes(l);
  if (has(WAYFINDER_TASK)) {
    const hasReadyAgent = has(READY_FOR_AGENT);
    const hasReadyHuman = has(READY_FOR_HUMAN);
    if (hasReadyAgent && hasReadyHuman) {
      contradictions.push({
        code: CONTRADICTION_CODES.TASK_BOTH_READY,
        reason: `${WAYFINDER_TASK} with both ${READY_FOR_AGENT} and ${READY_FOR_HUMAN}`,
        labels: [WAYFINDER_TASK, READY_FOR_AGENT, READY_FOR_HUMAN],
      });
    } else if (!hasReadyAgent && !hasReadyHuman) {
      contradictions.push({
        code: CONTRADICTION_CODES.TASK_MISSING_READINESS,
        reason: `${WAYFINDER_TASK} requires exactly one of ${READY_FOR_AGENT} or ${READY_FOR_HUMAN}`,
        labels: [WAYFINDER_TASK],
      });
    }
    // Only AFK task may have implement
    if (has(AGENT_IMPLEMENT) && !hasReadyAgent) {
      contradictions.push({
        code: CONTRADICTION_CODES.IMPLEMENT_WITHOUT_READY,
        reason: `${WAYFINDER_TASK} with ${AGENT_IMPLEMENT} requires ${READY_FOR_AGENT} (only AFK task may be implemented)`,
        labels: [WAYFINDER_TASK, AGENT_IMPLEMENT],
      });
    }
  }
  return { valid: contradictions.length === 0, contradictions, warnings, retired };
}

// ---------------------------------------------------------------------------
// Eligibility — research
// ---------------------------------------------------------------------------

export type EligibilityResult = { eligible: true } | { eligible: false; reason: string; code?: string };

/**
 * Pure validator for Wayfinder Research ticket body — owned contract.
 * Used by production isResearchEligible and tests.
 * Requires a substantive nonempty "## Question" section, consistent with
 * current research tickets (e.g., #163 fresh-world-scenario-automation,
 * #86 refinement-topology). No tracer, no implementation contract.
 * See CONTEXT.md “Research Ticket” and ADR 0010 research input contract.
 */
export function validateResearchTicketInput(body: string | undefined): { valid: boolean; reason?: string; code?: string } {
  if (body === undefined || body === null || body.trim().length === 0) {
    return { valid: false, reason: "research body must contain a ## Question section", code: "RESEARCH_BODY_INVALID" };
  }
  // Find ## Question heading (case-insensitive, allow 1-3 #)
  const match = body.match(/##\s*Question\b/i);
  if (!match || match.index === undefined) {
    return { valid: false, reason: "research body must contain a ## Question heading", code: "RESEARCH_BODY_INVALID" };
  }
  const after = body.slice(match.index + match[0].length).trim();
  // Require substantive content after heading: at least 20 chars and 5 words
  if (after.length < 20) {
    return { valid: false, reason: "research Question section too short (<20 chars after heading)", code: "RESEARCH_BODY_INVALID" };
  }
  const words = after.split(/\s+/).filter(Boolean);
  if (words.length < 5) {
    return { valid: false, reason: "research Question section too short (<5 words)", code: "RESEARCH_BODY_INVALID" };
  }
  // Check not just placeholder like "please investigate this" (we already require 5 words and 20 chars, so that would pass, but we keep minimal)
  // Ensure contains at least one alphabetic character and not just punctuation
  if (!/[a-zA-Z]{3,}/.test(after)) {
    return { valid: false, reason: "research Question section must contain substantive text", code: "RESEARCH_BODY_INVALID" };
  }
  return { valid: true };
}

export function isResearchEligible(issue: IssueInput): EligibilityResult {
  if (issue.state.toLowerCase() !== "open") {
    return { eligible: false, reason: `state is ${issue.state}, expected open`, code: "STATE_NOT_OPEN" };
  }

  // Retired labels fail closed before other gates
  if (issue.labels.includes(AGENT_RESEARCH_RETIRED)) {
    return { eligible: false, reason: `retired label ${AGENT_RESEARCH_RETIRED} present — fail closed`, code: CONTRADICTION_CODES.RETIRED_AGENT_RESEARCH };
  }
  if (issue.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED)) {
    return { eligible: false, reason: `retired label ${WAYFINDER_PRESERVE_FUTURES_RETIRED} present`, code: CONTRADICTION_CODES.RETIRED_PRESERVE_FUTURES };
  }

  const wayfinderLabels = getWayfinderLabels(issue);
  if (wayfinderLabels.length !== 1 || !issue.labels.includes(WAYFINDER_RESEARCH)) {
    if (wayfinderLabels.length === 0) return { eligible: false, reason: `missing required label ${WAYFINDER_RESEARCH}`, code: "MISSING_RESEARCH_LABEL" };
    if (wayfinderLabels.length > 1) return { eligible: false, reason: `multiple wayfinder types: ${wayfinderLabels.join(", ")}`, code: CONTRADICTION_CODES.MULTIPLE_WAYFINDER };
    return { eligible: false, reason: `requires exactly one wayfinder type ${WAYFINDER_RESEARCH}, found ${wayfinderLabels.join(", ")}`, code: "WRONG_WAYFINDER" };
  }

  // No implement together with research (contradiction)
  if (issue.labels.includes(AGENT_IMPLEMENT)) {
    return { eligible: false, reason: `${WAYFINDER_RESEARCH} with ${AGENT_IMPLEMENT} — contradiction`, code: CONTRADICTION_CODES.RESEARCH_WITH_IMPLEMENT };
  }

  if (issue.labels.includes(AGENT_IN_PROGRESS)) {
    return { eligible: false, reason: `already has ${AGENT_IN_PROGRESS}`, code: "ALREADY_IN_PROGRESS" };
  }
  if (issue.labels.includes(AGENT_BLOCKED)) {
    return { eligible: false, reason: `already has ${AGENT_BLOCKED}`, code: "ALREADY_BLOCKED" };
  }
  if (issue.assignees.length > 0) {
    return { eligible: false, reason: `already assigned to ${issue.assignees.join(",")}`, code: "ALREADY_ASSIGNED" };
  }
  if (issue.blockedByCount === undefined) {
    return { eligible: false, reason: "blocked state unknown — GitHub dependency API unavailable (fail-closed)", code: "BLOCKED_UNKNOWN" };
  }
  if (issue.blockedByCount > 0) {
    return { eligible: false, reason: `blocked by ${issue.blockedByCount} open blocker(s)`, code: "BLOCKED" };
  }

  // Check contradictions that would block research (excluding historical redundancy warnings)
  const validation = detectContradictions(issue);
  // Filter out warnings that are allowed for research (ready-for-agent residue)
  const blocking = validation.contradictions.filter((c) => {
    // RESEARCH_WITH_IMPLEMENT already handled, but keep
    // Retired already handled
    // For research, the only warning is ready-for-agent residue, which should NOT block
    return true;
  });
  // If there's a contradiction involving research+implement etc, already returned above, but handle other contradictions
  // e.g., multiple wayfinder, retired, map, etc.
  // However we must NOT block on warnings (residue)
  // Since we filtered warnings out, blocking is just contradictions
  // But need to ensure that a research issue with prototype label etc would have been caught by count>1
  if (blocking.length > 0) {
    // If the only contradiction is IMPLEMENT_WITHOUT_READY, that shouldn't apply to research (research doesn't have implement)
    // But research with implement already returned. So any remaining contradiction should block.
    // However research with ready-for-agent as warning should not be in blocking, it's in warnings.
    // So if blocking non-empty and contains something not already handled, fail.
    // Check if blocking is only due to IMPLEMENT_WITHOUT_READY when research has no implement — shouldn't happen because implement check already ensures no implement, so that code wouldn't be present.
    // So we can just fail if blocking has any that is not just the residue
    const relevant = blocking.filter((c) => c.code !== CONTRADICTION_CODES.IMPLEMENT_WITHOUT_READY);
    if (relevant.length > 0) {
      return { eligible: false, reason: relevant[0].reason, code: relevant[0].code };
    }
  }

  // Research input contract — per CONTEXT.md Research Ticket and ADR 0010:
  // Research ticket body must contain a substantive nonempty Question section
  // ("## Question" heading) consistent with current Wayfinder Research tickets
  // (e.g., #163, #86, #68, #66, #37). No tracer required.
  const v = validateResearchTicketInput(issue.body);
  if (!v.valid) {
    return { eligible: false, reason: v.reason!, code: v.code! };
  }
  // No tracer required for research.
  return { eligible: true };
}

// ---------------------------------------------------------------------------
// Eligibility — implementation (including AFK Task)
// ---------------------------------------------------------------------------

export function isImplementationEligible(issue: IssueInput): EligibilityResult {
  if (issue.state.toLowerCase() !== "open") {
    return { eligible: false, reason: `state is ${issue.state}, expected open`, code: "STATE_NOT_OPEN" };
  }

  // Contradictions fail before other gates (except warnings)
  const validation = detectContradictions(issue);
  // For implementation, retired labels are contradictions and should block
  if (validation.contradictions.length > 0) {
    // Prioritize specific codes for test readability
    // Return first contradiction reason
    return { eligible: false, reason: validation.contradictions[0].reason, code: validation.contradictions[0].code };
  }

  // Must have both ready-for-agent and agent:implement
  const hasReady = issue.labels.includes(READY_FOR_AGENT);
  const hasImplement = issue.labels.includes(AGENT_IMPLEMENT);
  if (!hasReady || !hasImplement) {
    if (!hasReady && !hasImplement) return { eligible: false, reason: `missing required labels ${READY_FOR_AGENT} + ${AGENT_IMPLEMENT}`, code: "MISSING_READY_AND_IMPLEMENT" };
    if (!hasReady) return { eligible: false, reason: `missing required label ${READY_FOR_AGENT} — ${AGENT_IMPLEMENT} alone is not readiness`, code: CONTRADICTION_CODES.IMPLEMENT_WITHOUT_READY };
    return { eligible: false, reason: `missing required label ${AGENT_IMPLEMENT}`, code: "MISSING_IMPLEMENT" };
  }

  // No already claimed
  if (issue.labels.includes(AGENT_IN_PROGRESS)) {
    return { eligible: false, reason: `already has ${AGENT_IN_PROGRESS}`, code: "ALREADY_IN_PROGRESS" };
  }
  if (issue.labels.includes(AGENT_BLOCKED)) {
    return { eligible: false, reason: `already has ${AGENT_BLOCKED}`, code: "ALREADY_BLOCKED" };
  }
  if (issue.assignees.length > 0) {
    return { eligible: false, reason: `already assigned to ${issue.assignees.join(",")}`, code: "ALREADY_ASSIGNED" };
  }

  // Native blockers
  if (issue.blockedByCount === undefined) {
    return { eligible: false, reason: "blocked state unknown — GitHub dependency API unavailable (fail-closed)", code: "BLOCKED_UNKNOWN" };
  }
  if (issue.blockedByCount > 0) {
    return { eligible: false, reason: `blocked by ${issue.blockedByCount} open blocker(s)`, code: "BLOCKED" };
  }

  // Wayfinder handling for implementation
  const wayfinderLabels = getWayfinderLabels(issue);
  const hasTask = issue.labels.includes(WAYFINDER_TASK);
  const hasOtherWayfinder = wayfinderLabels.some((l) => l !== WAYFINDER_TASK);

  // If has any non-task wayfinder, it's contradictory (already caught by detectContradictions for prototype/grilling/map/research)
  // But for completeness, handle ordinary implementation: should have 0 wayfinder or exactly task
  if (hasOtherWayfinder) {
    // This should have been caught, but double-check
    return { eligible: false, reason: `forbidden Wayfinder type for implementation: ${wayfinderLabels.filter((l) => l !== WAYFINDER_TASK).join(", ")}`, code: "FORBIDDEN_WAYFINDER" };
  }

  if (hasTask) {
    // Validate task classification
    const taskValidation = validateTaskClassification(issue);
    if (!taskValidation.valid) {
      return { eligible: false, reason: taskValidation.contradictions[0].reason, code: taskValidation.contradictions[0].code };
    }
    // AFK Task requires ready-for-agent (already ensured) and tracer
    // No extra check needed beyond contradictions and readiness
  } else {
    // Ordinary implementation — wayfinder count 0 is fine; irrelevant noncontradictory metadata already handled
    // No additional wayfinder check needed
  }

  // Tracer-bullet contract — fail-closed
  {
    const missing = missingTracerConcepts(issue.body);
    if (missing.length > 0) {
      return { eligible: false, reason: `tracer contract missing: ${missing.join(", ")} — see docs/agents/tracer-contract.md`, code: "MISSING_TRACER" };
    }
  }

  return { eligible: true };
}

// ---------------------------------------------------------------------------
// Profile classification
// ---------------------------------------------------------------------------

export type TicketProfile = "implementation" | "research" | "conflicting" | "ineligible";

export interface TicketClassification {
  profile: TicketProfile;
  eligible: boolean;
  reason?: string;
  code?: string;
  contradictions?: Contradiction[];
  warnings?: Contradiction[];
}

export function classifyTicket(issue: IssueInput): TicketClassification {
  const validation = detectContradictions(issue);
  const hasConflicting = validation.contradictions.length > 0;

  // Conflicting profile if any contradiction
  if (hasConflicting) {
    // Determine if it's research vs implementation conflicting
    // If has both implement and research, or retired, or multiple wayfinder, etc — conflicting
    return { profile: "conflicting", eligible: false, reason: validation.contradictions[0].reason, code: validation.contradictions[0].code, contradictions: validation.contradictions, warnings: validation.warnings };
  }

  const hasImplement = issue.labels.includes(AGENT_IMPLEMENT);
  const hasReadyAgent = issue.labels.includes(READY_FOR_AGENT);
  const hasWayfinderResearch = issue.labels.includes(WAYFINDER_RESEARCH);

  // Check research eligibility first (wayfinder:research alone)
  const researchEligibility = isResearchEligible(issue);
  if (researchEligibility.eligible) {
    return { profile: "research", eligible: true, contradictions: [], warnings: validation.warnings };
  }
  // If has wayfinder:research but not eligible, classify as research (ineligible) for visibility
  if (hasWayfinderResearch) {
    // Only if wayfinder count ==1 and is research
    const wayfinderLabels = getWayfinderLabels(issue);
    if (wayfinderLabels.length === 1 && wayfinderLabels[0] === WAYFINDER_RESEARCH) {
      const reason = (researchEligibility as { reason: string }).reason;
      const code = (researchEligibility as { code?: string }).code;
      return { profile: "research", eligible: false, reason, code, contradictions: validation.contradictions, warnings: validation.warnings };
    }
  }

  // Check implementation eligibility
  const implEligibility = isImplementationEligible(issue);
  if (implEligibility.eligible) {
    return { profile: "implementation", eligible: true, warnings: validation.warnings };
  }
  if (hasImplement && hasReadyAgent) {
    // Has both command markers but failed other gates — classify as implementation ineligible
    const reason = (implEligibility as { reason: string }).reason;
    const code = (implEligibility as { code?: string }).code;
    return { profile: "implementation", eligible: false, reason, code, contradictions: validation.contradictions, warnings: validation.warnings };
  }
  if (hasImplement) {
    // Has implement but missing ready — already conflicting, but if not, classify as implementation
    const reason = (implEligibility as { reason: string }).reason;
    return { profile: "implementation", eligible: false, reason, contradictions: validation.contradictions, warnings: validation.warnings };
  }

  // Neither authorization
  // Prefer research missing reason if wayfinder:research present
  if (hasWayfinderResearch) {
    const reason = (researchEligibility as { reason: string }).reason;
    const code = (researchEligibility as { code?: string }).code;
    return { profile: "ineligible", eligible: false, reason, code, warnings: validation.warnings };
  }
  const reason = (implEligibility as { reason: string }).reason;
  const code = (implEligibility as { code?: string }).code;
  return { profile: "ineligible", eligible: false, reason, code, warnings: validation.warnings };
}

export function branchForIssue(id: number | string): string {
  return `sandcastle/issue-${id}`;
}

// Task executor consistency helper for migration
export function getTaskReadiness(issue: IssueInput): "ready-for-agent" | "ready-for-human" | "both" | "none" {
  const hasAgent = issue.labels.includes(READY_FOR_AGENT);
  const hasHuman = issue.labels.includes(READY_FOR_HUMAN);
  if (hasAgent && hasHuman) return "both";
  if (hasAgent) return "ready-for-agent";
  if (hasHuman) return "ready-for-human";
  return "none";
}

export function isValidTaskState(issue: IssueInput): boolean {
  if (!issue.labels.includes(WAYFINDER_TASK)) return false;
  const readiness = getTaskReadiness(issue);
  return readiness === "ready-for-agent" || readiness === "ready-for-human";
}

// Normalization helpers
export function needsNormalization(issue: IssueInput): boolean {
  const v = detectContradictions(issue);
  if (v.contradictions.length > 0) return true;
  if (v.warnings.length > 0) return true;
  if (issue.labels.includes(WAYFINDER_TASK)) {
    const readiness = getTaskReadiness(issue);
    if (readiness === "none" || readiness === "both") return true;
  }
  return false;
}
