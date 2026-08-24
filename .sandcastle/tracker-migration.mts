import * as fsSync from "node:fs";
import { createHash } from "node:crypto";
import {
  detectContradictions,
  getRemovableResidueLabels,
  getWayfinderLabels,
  WAYFINDER_RESEARCH,
  WAYFINDER_TASK,
  WAYFINDER_MAP,
  WAYFINDER_PROTOTYPE,
  WAYFINDER_GRILLING,
  WAYFINDER_PRESERVE_FUTURES_RETIRED,
  AGENT_RESEARCH_RETIRED,
  READY_FOR_AGENT,
  READY_FOR_HUMAN,
  AGENT_IMPLEMENT,
  AGENT_IN_PROGRESS,
  AGENT_BLOCKED,
  WAYFINDER_TYPES,
  type IssueInput,
  isResearchEligible,
  isImplementationEligible,
  getTaskReadiness,
} from "./tracker-policy.mts";

/**
 * Tracker migration — host-owned, idempotent, bounded.
 * Supports --check, --dry-run, --apply. Default is dry-run (no mutation).
 */

export interface MigrationPlan {
  newlyEligibleResearch: number[];
  blockedResearch: number[];
  ambiguousResearch: number[];
  taskClassificationPlan: Array<{ issue: number; current: string[]; planned: string; reason: string }>;
  retiredLabelUsers: Array<{ issue: number; labels: string[] }>;
  contradictions: Array<{ issue: number; code: string; reason: string }>;
  plannedMutations: Array<{ issue: number; addLabels: string[]; removeLabels: string[]; reason: string }>;
  unchangedIssues: number[];
  labelDescriptionUpdates: Array<{ name: string; oldDesc: string; newDesc: string }>;
  retiredLabelsToDelete: string[];
}

export interface MigrationReceipt {
  before: { issue: number; labels: string[] }[];
  plan: MigrationPlan;
  after?: { issue: number; labels: string[] }[];
  applied: boolean;
  checkPassed: boolean;
}

export const CANONICAL_LABEL_DESCRIPTIONS: Record<string, string> = {
  "wayfinder:research": "AFK research — evidence-backed; open, unassigned, unblocked, no in-progress",
  "wayfinder:task": "Task — unblocks decision; executor ready-for-agent (AFK) or ready-for-human (HITL)",
  "wayfinder:prototype": "Prototype — HITL only, raises fidelity with cheap artifact; never AFK",
  "wayfinder:grilling": "Grilling — HITL decision interview; never AFK",
  "wayfinder:map": "Map — single destination map per repo",
  "agent:implement": "One-shot implement — with ready-for-agent, consumed on claim",
  "agent:in-progress": "Transient claim — supplements assignee, released on interruption",
  "agent:blocked": "Blocked — intervention required; not product blocked_by",
  "ready-for-agent": "Ready for agent — fully specified, paired with agent:implement",
  "ready-for-human": "Ready for human — requires human implementation",
};

// Explicit task classification plan for historical tasks — to avoid inferring from prose
export const EXPLICIT_TASK_PLAN: Record<number, "ready-for-agent" | "ready-for-human"> = {
  166: "ready-for-human",
  127: "ready-for-human",
  114: "ready-for-human",
  64: "ready-for-human",
  61: "ready-for-human",
  25: "ready-for-human",
};

export function planMigration(issues: IssueInput[], explicitTaskPlan: Record<number, string> = EXPLICIT_TASK_PLAN): MigrationPlan {
  const plan: MigrationPlan = {
    newlyEligibleResearch: [],
    blockedResearch: [],
    ambiguousResearch: [],
    taskClassificationPlan: [],
    retiredLabelUsers: [],
    contradictions: [],
    plannedMutations: [],
    unchangedIssues: [],
    labelDescriptionUpdates: [],
    retiredLabelsToDelete: [],
  };

  const relevantLabels = [...(WAYFINDER_TYPES as unknown as string[]), AGENT_RESEARCH_RETIRED, WAYFINDER_PRESERVE_FUTURES_RETIRED, READY_FOR_AGENT, READY_FOR_HUMAN, AGENT_IMPLEMENT, AGENT_IN_PROGRESS, AGENT_BLOCKED, "needs-triage", "needs-info", "wontfix"];

  for (const issue of issues) {
    const hasRelevant = issue.labels.some(l => relevantLabels.includes(l));
    if (!hasRelevant) {
      plan.unchangedIssues.push(issue.number);
      continue;
    }

    const validation = detectContradictions(issue);
    if (validation.contradictions.length > 0) {
      for (const c of validation.contradictions) {
        plan.contradictions.push({ issue: issue.number, code: c.code, reason: c.reason });
      }
      if (validation.retired.length > 0) {
        const toRemove = validation.retired.map(r => r.labels?.[0] ?? "").filter(Boolean);
        if (toRemove.length > 0) {
          plan.retiredLabelUsers.push({ issue: issue.number, labels: toRemove });
          plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: toRemove, reason: `remove retired labels ${toRemove.join(", ")}` });
        }
      }
    }

    const wayfinderLabels = getWayfinderLabels(issue);
    const isResearch = wayfinderLabels.length === 1 && wayfinderLabels[0] === WAYFINDER_RESEARCH;
    const isTask = issue.labels.includes(WAYFINDER_TASK);
    const isPreserveFutures = issue.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED);

    if (isPreserveFutures) {
      const body = issue.body ?? "";
      const hasIntent = body.toLowerCase().includes("preserve") || body.toLowerCase().includes("checkpoint") || body.toLowerCase().includes("/preserve-futures");
      if (hasIntent) {
        plan.plannedMutations.push({
          issue: issue.number,
          addLabels: [WAYFINDER_TASK, READY_FOR_AGENT],
          removeLabels: [WAYFINDER_PRESERVE_FUTURES_RETIRED],
          reason: "replace wayfinder:preserve-futures with wayfinder:task + ready-for-agent (checkpoint intent explicit)",
        });
      } else {
        plan.ambiguousResearch.push(issue.number);
        plan.contradictions.push({ issue: issue.number, code: "PRESERVE_FUTURES_NO_INTENT", reason: "wayfinder:preserve-futures without explicit checkpoint intent — requires manual review" });
      }
      continue;
    }

    if (isResearch) {
      // Historical Research carrying retired agent:research — classify truthfully after removal
      if (validation.retired.length > 0 && validation.retired.some(r => r.labels?.includes(AGENT_RESEARCH_RETIRED))) {
        const residue = getRemovableResidueLabels(issue);
        if (residue.length > 0 && !plan.plannedMutations.some(m => m.issue === issue.number)) {
          plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical redundancy residue" });
        }
        const normalized = { ...issue, labels: issue.labels.filter(l => l !== AGENT_RESEARCH_RETIRED) };
        const normalizedEligibility = isResearchEligible(normalized as any);
        if (normalizedEligibility.eligible) {
          plan.newlyEligibleResearch.push(issue.number);
        } else {
          const ncode = (normalizedEligibility as any).code as string | undefined;
          const nreason = (normalizedEligibility as any).reason as string | undefined ?? "";
          const isBlocked = ncode === "BLOCKED" || ncode === "ALREADY_ASSIGNED" || ncode === "ALREADY_IN_PROGRESS" || ncode === "ALREADY_BLOCKED"
            || nreason.includes("already assigned") || nreason.includes("already has") || nreason.includes("blocked by");
          const isUnknownOrInvalid = ncode === "BLOCKED_UNKNOWN" || nreason.includes("blocked state unknown") || nreason.includes("invalid") || nreason.includes("Question") || nreason.toLowerCase().includes("contradiction") || nreason.includes("multiple wayfinder");
          if (isBlocked) {
            plan.blockedResearch.push(issue.number);
          } else if (isUnknownOrInvalid || ncode) {
            plan.ambiguousResearch.push(issue.number);
            // Surface blocking problem for historical research that is ambiguous after cleanup
            if (ncode && !plan.contradictions.some(c => c.issue === issue.number && c.code === ncode)) {
              plan.contradictions.push({ issue: issue.number, code: ncode, reason: nreason });
            } else if (!ncode) {
              plan.contradictions.push({ issue: issue.number, code: "HISTORICAL_RESEARCH_AMBIGUOUS", reason: nreason });
            }
          } else {
            plan.ambiguousResearch.push(issue.number);
          }
        }
        continue;
      }
      const eligibility = isResearchEligible(issue);
      if (issue.blockedByCount === undefined) {
        plan.ambiguousResearch.push(issue.number);
        plan.contradictions.push({ issue: issue.number, code: "BLOCKED_UNKNOWN", reason: "blocked_by unknown — fail closed" });
      } else if (issue.blockedByCount > 0) {
        plan.blockedResearch.push(issue.number);
      } else if (eligibility.eligible) {
        if (validation.warnings.length > 0) {
          const residue = getRemovableResidueLabels(issue);
          if (residue.length > 0) {
            plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical redundancy residue" });
          }
          plan.newlyEligibleResearch.push(issue.number);
        } else if (validation.contradictions.length === 0) {
          plan.newlyEligibleResearch.push(issue.number);
        } else {
          plan.ambiguousResearch.push(issue.number);
        }
      } else {
        const reason = (eligibility as any).reason ?? "ineligible";
        if (reason.includes("already assigned") || reason.includes("already has")) {
          plan.blockedResearch.push(issue.number);
        } else {
          plan.ambiguousResearch.push(issue.number);
        }
      }
      const residue = getRemovableResidueLabels(issue);
      if (residue.length > 0 && !plan.plannedMutations.some(m => m.issue === issue.number)) {
        plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical redundancy residue" });
      }
      continue;
    }

    if (isTask) {
      const readiness = getTaskReadiness(issue);
      if (readiness === "none") {
        const explicit = explicitTaskPlan[issue.number];
        if (explicit && (explicit === READY_FOR_AGENT || explicit === READY_FOR_HUMAN)) {
          plan.taskClassificationPlan.push({ issue: issue.number, current: [], planned: explicit, reason: "explicit plan for missing readiness" });
          plan.plannedMutations.push({ issue: issue.number, addLabels: [explicit], removeLabels: [], reason: `classify wayfinder:task as ${explicit} per explicit plan` });
        } else {
          plan.taskClassificationPlan.push({ issue: issue.number, current: [], planned: "ambiguous", reason: "wayfinder:task requires exactly one readiness — no explicit plan, needs manual review" });
          plan.contradictions.push({ issue: issue.number, code: "TASK_MISSING_READINESS", reason: "wayfinder:task missing readiness — requires explicit classification" });
        }
      } else if (readiness === "both") {
        plan.taskClassificationPlan.push({ issue: issue.number, current: [READY_FOR_AGENT, READY_FOR_HUMAN], planned: "ambiguous", reason: "both readiness labels present — contradictory" });
      } else {
        plan.taskClassificationPlan.push({ issue: issue.number, current: [readiness], planned: readiness, reason: "already classified" });
        const residue = getRemovableResidueLabels(issue);
        if (residue.length > 0) {
          plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove residue" });
        }
      }
      continue;
    }

    const residue = getRemovableResidueLabels(issue);
    if (residue.length > 0) {
      if (!plan.plannedMutations.some(m => m.issue === issue.number && m.removeLabels.some(l => residue.includes(l)))) {
        plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical residue" });
      }
    } else if (validation.contradictions.length === 0) {
      plan.unchangedIssues.push(issue.number);
    }
  }

  for (const [name, newDesc] of Object.entries(CANONICAL_LABEL_DESCRIPTIONS)) {
    plan.labelDescriptionUpdates.push({ name, oldDesc: "(unknown — will fetch live)", newDesc });
  }

  const hasRetiredUsers = plan.retiredLabelUsers.length > 0;
  const hasPreserveFutures = issues.some(i => i.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED));
  const hasAgentResearch = issues.some(i => i.labels.includes(AGENT_RESEARCH_RETIRED));
  if (!hasRetiredUsers && !hasPreserveFutures && !hasAgentResearch) {
    if (!hasAgentResearch) plan.retiredLabelsToDelete.push(AGENT_RESEARCH_RETIRED);
    if (!hasPreserveFutures) plan.retiredLabelsToDelete.push(WAYFINDER_PRESERVE_FUTURES_RETIRED);
  }

  // Canonicalize planned mutations: one combined mutation per issue (merge add/remove), deduplicate retired deletes
  const mutationMap = new Map();
  for (const pending of plan.plannedMutations) {
    if (!mutationMap.has(pending.issue)) mutationMap.set(pending.issue, { add: new Set(), remove: new Set(), reasons: [] });
    const entry = mutationMap.get(pending.issue);
    for (const a of pending.addLabels) entry.add.add(a);
    for (const r of pending.removeLabels) entry.remove.add(r);
    entry.reasons.push(pending.reason);
  }
  plan.plannedMutations = [...mutationMap.entries()].map(([issue, entry]) => ({
    issue,
    addLabels: [...entry.add],
    removeLabels: [...entry.remove],
    reason: entry.reasons.join("; "),
  }));
  plan.retiredLabelsToDelete = [...new Set(plan.retiredLabelsToDelete)];

  return plan;
}

export interface InventoryOps {
  listOpenIssues: () => Promise<IssueInput[]>;
  getLabelDescriptions: () => Promise<Record<string, string>>;
}

export type RetiredLabelState = Record<string, boolean>;

export interface InventoryOps2 {
  listOpenIssues: () => Promise<IssueInput[]>;
  getLabelDescriptions: () => Promise<Record<string, string>>;
  getRetiredLabelsExist: () => Promise<RetiredLabelState | boolean>;
  getHeadSha?: () => Promise<string>;
}
export interface MutationOps2 {
  updateIssueLabels: (issueNumber: number, add: string[], remove: string[]) => Promise<void>;
  updateLabelDescription: (name: string, description: string) => Promise<void>;
  deleteLabel: (name: string) => Promise<void>;
}

export interface ReviewedReceipt {
  candidateHeadSha: string;
  relevantIssueNumbers: number[];
  issues: Array<{ number: number; state: string; labels: string[]; assignees: string[]; blocked_by: number | undefined; bodySha256: string }>;
  labelDescriptions: Record<string, string>;
  retiredLabelsExist: RetiredLabelState;
  plan: MigrationPlan;
  planSha256: string;
  generatedAt: string;
}

function bodySha256(body: string | undefined): string {
  return createHash("sha256").update(body ?? "").digest("hex");
}
function sorted(arr: string[]): string[] { return [...arr].sort(); }
function planSha256(plan: MigrationPlan): string {
  return createHash("sha256").update(JSON.stringify(plan)).digest("hex");
}
function normalizeRetiredState(input: RetiredLabelState | boolean | undefined): RetiredLabelState {
  if (typeof input === "boolean") {
    return {
      [AGENT_RESEARCH_RETIRED]: input,
      [WAYFINDER_PRESERVE_FUTURES_RETIRED]: input,
    };
  }
  if (!input || typeof input !== "object") {
    return {
      [AGENT_RESEARCH_RETIRED]: false,
      [WAYFINDER_PRESERVE_FUTURES_RETIRED]: false,
    };
  }
  return {
    [AGENT_RESEARCH_RETIRED]: !!input[AGENT_RESEARCH_RETIRED],
    [WAYFINDER_PRESERVE_FUTURES_RETIRED]: !!input[WAYFINDER_PRESERVE_FUTURES_RETIRED],
  };
}
export function buildReviewedReceipt(
  issues: IssueInput[],
  labelDescriptions: Record<string,string>,
  retiredLabelsExist: RetiredLabelState | boolean,
  plan: MigrationPlan,
  candidateHeadSha: string = "unknown"
): ReviewedReceipt {
  const normalizedRetired = normalizeRetiredState(retiredLabelsExist);
  return {
    candidateHeadSha,
    relevantIssueNumbers: [...issues.map(i=>i.number)].sort((a,b)=>a-b),
    issues: issues.map(i => ({
      number: i.number,
      state: i.state,
      labels: sorted(i.labels),
      assignees: sorted(i.assignees),
      blocked_by: i.blockedByCount,
      bodySha256: bodySha256(i.body),
    })).sort((a,b)=>a.number-b.number),
    labelDescriptions: Object.fromEntries(Object.entries(labelDescriptions).sort()),
    retiredLabelsExist: normalizedRetired,
    plan,
    planSha256: planSha256(plan),
    generatedAt: new Date().toISOString(),
  };
}

export async function runTrackerMigration(opts: {
  mode: "check" | "dry-run" | "apply";
  inventoryOps: InventoryOps2;
  mutationOps?: MutationOps2;
  reviewedReceipt?: ReviewedReceipt;
  explicitTaskPlan?: Record<number, string>;
  candidateHeadSha?: string;
}): Promise<{ plan: MigrationPlan; receipt: ReviewedReceipt; before: ReviewedReceipt; after?: ReviewedReceipt; afterPlan?: MigrationPlan; applied: boolean; blockingProblems: boolean; migrationRequired: boolean; checkPassed: boolean }> {
  const issues = await opts.inventoryOps.listOpenIssues();
  const labelDescriptions = await opts.inventoryOps.getLabelDescriptions();
  const retiredRaw = await opts.inventoryOps.getRetiredLabelsExist();
  const retiredExist = normalizeRetiredState(retiredRaw as any);
  let headSha = opts.candidateHeadSha ?? "unknown";
  if (opts.inventoryOps.getHeadSha) {
    try { headSha = await opts.inventoryOps.getHeadSha(); } catch {}
  } else if (!opts.candidateHeadSha) {
    try {
      const { execSync } = await import("node:child_process");
      headSha = execSync("git rev-parse HEAD", { encoding: "utf8" }).trim();
    } catch {}
  }
  const relevant = issues.filter(i => i.labels.some(l => l.startsWith("wayfinder:") || l.startsWith("agent:") || ["ready-for-agent","ready-for-human","needs-triage","needs-info","wontfix"].includes(l)));
  const plan = planMigration(relevant, opts.explicitTaskPlan);
  const before = buildReviewedReceipt(relevant, labelDescriptions, retiredExist, plan, headSha);
  if (opts.mode === "dry-run" || opts.mode === "check") {
    const blockingProblems = hasBlockingMigrationProblems(plan);
    const migrationNeeded = migrationRequired(plan, { labelDescriptions, retiredLabelsExist: retiredExist });
    const checkPassed = !blockingProblems && !migrationNeeded;
    return { plan, receipt: before, before, applied: false, blockingProblems, migrationRequired: migrationNeeded, checkPassed };
  }
  // apply mode — reviewedReceipt is required
  if (!opts.reviewedReceipt) {
    throw new Error("apply requires --receipt <path>: missing reviewed receipt");
  }
  // Validate receipt bindings before any write
  const receipt = opts.reviewedReceipt;
  // candidate SHA mismatch
  if (receipt.candidateHeadSha !== headSha) {
    throw new Error(`candidate SHA mismatch: receipt ${receipt.candidateHeadSha} vs current ${headSha}`);
  }
  // issue-set additions/removals
  const currentNumbers = [...relevant.map(i=>i.number)].sort((a,b)=>a-b);
  const receiptNumbers = [...(receipt.relevantIssueNumbers ?? receipt.issues.map(i=>i.number))].sort((a,b)=>a-b);
  if (currentNumbers.join(",") !== receiptNumbers.join(",")) {
    throw new Error(`issue-set drift: receipt [${receiptNumbers.join(",")}] vs current [${currentNumbers.join(",")}]`);
  }
  // check current plan vs reviewed plan SHA
  const currentPlanSha = planSha256(plan);
  if (receipt.planSha256 && receipt.planSha256 !== currentPlanSha) {
    // Also deep compare plan JSON for detailed message
    if (JSON.stringify(receipt.plan) !== JSON.stringify(plan)) {
      throw new Error(`current plan differs from reviewed plan: receipt sha ${receipt.planSha256.slice(0,8)} vs current ${currentPlanSha.slice(0,8)}`);
    }
  } else if (JSON.stringify(receipt.plan) !== JSON.stringify(plan)) {
    throw new Error(`current plan differs from reviewed plan`);
  }
  // Detailed per-issue drift
  const driftErrors: string[] = [];
  for (const expected of receipt.issues) {
    const live = relevant.find(i => i.number === expected.number);
    if (!live) { driftErrors.push(`#${expected.number} missing live`); continue; }
    if (live.state !== expected.state) driftErrors.push(`#${expected.number} state drift: ${expected.state} vs ${live.state}`);
    if (sorted(live.labels).join(",") !== expected.labels.join(",")) driftErrors.push(`#${expected.number} labels drift: ${expected.labels.join(",")} vs ${sorted(live.labels).join(",")}`);
    if (sorted(live.assignees).join(",") !== expected.assignees.join(",")) driftErrors.push(`#${expected.number} assignees drift`);
    if (live.blockedByCount !== expected.blocked_by) driftErrors.push(`#${expected.number} blocked_by drift: ${expected.blocked_by} vs ${live.blockedByCount}`);
    if (bodySha256(live.body) !== expected.bodySha256) driftErrors.push(`#${expected.number} body drift: sha ${expected.bodySha256.slice(0,8)} vs ${bodySha256(live.body).slice(0,8)}`);
  }
  // label-description drift
  for (const [k,v] of Object.entries(receipt.labelDescriptions)) {
    if (labelDescriptions[k] !== v) driftErrors.push(`label ${k} description drift: receipt "${v.slice(0,30)}" vs live "${(labelDescriptions[k] ?? "").slice(0,30)}"`);
  }
  // Also check any live description that receipt expected to be canonical but receipt had unknown? Receipt has actual fetched, so compare live current vs receipt
  // For labels not in receipt but canonical, we already check via plan diff; but ensure live still matches receipt for all keys in receipt
  // retired-label-state drift per-label
  for (const label of [AGENT_RESEARCH_RETIRED, WAYFINDER_PRESERVE_FUTURES_RETIRED]) {
    const receiptVal = (receipt.retiredLabelsExist as any)[label] ?? false;
    const liveVal = retiredExist[label] ?? false;
    if (receiptVal !== liveVal) driftErrors.push(`retired label ${label} existence drift: receipt ${receiptVal} vs live ${liveVal}`);
  }
  if (driftErrors.length > 0) throw new Error(`drift detected before apply: ${driftErrors.join("; ")}`);

  if (hasBlockingMigrationProblems(plan)) {
    throw new Error(`blocking migration problems: ${JSON.stringify(plan.contradictions.slice(0,3))}`);
  }
  if (!migrationRequired(plan, { labelDescriptions, retiredLabelsExist: retiredExist })) {
    const afterIssues = await opts.inventoryOps.listOpenIssues();
    const afterLabelDescs = await opts.inventoryOps.getLabelDescriptions();
    const afterRetiredRaw = await opts.inventoryOps.getRetiredLabelsExist();
    const afterRetired = normalizeRetiredState(afterRetiredRaw as any);
    let afterHead = headSha;
    if (opts.inventoryOps.getHeadSha) { try { afterHead = await opts.inventoryOps.getHeadSha(); } catch {} }
    const afterRelevant = afterIssues.filter(i => i.labels.some(l => l.startsWith("wayfinder:") || l.startsWith("agent:") || ["ready-for-agent","ready-for-human","needs-triage","needs-info","wontfix"].includes(l)));
    const afterPlan = planMigration(afterRelevant, opts.explicitTaskPlan);
    if (migrationRequired(afterPlan, { labelDescriptions: afterLabelDescs, retiredLabelsExist: afterRetired })) {
      throw new Error("postcondition failed: migration still required after no-op");
    }
    const after = buildReviewedReceipt(afterRelevant, afterLabelDescs, afterRetired, afterPlan, afterHead);
    const blockingProblems = hasBlockingMigrationProblems(afterPlan);
    const migrationNeeded = migrationRequired(afterPlan, { labelDescriptions: afterLabelDescs, retiredLabelsExist: afterRetired });
    const checkPassed = !blockingProblems && !migrationNeeded;
    return { plan, receipt: before, before, after, afterPlan, applied: false, blockingProblems, migrationRequired: migrationNeeded, checkPassed };
  }
  if (!opts.mutationOps) throw new Error("mutationOps required for apply");
  // Phase 1: normalize issue labels
  for (const m of plan.plannedMutations) {
    try {
      await opts.mutationOps.updateIssueLabels(m.issue, m.addLabels, m.removeLabels);
    } catch (e) {
      throw new Error(`mutation failed for #${m.issue}: ${e}`);
    }
  }
  // Re-inventory and require zero open retired-label users before deleting repository labels
  {
    let postIssueIssues: any;
    try { postIssueIssues = await opts.inventoryOps.listOpenIssues(); } catch (e) { throw new Error(`post-inventory fetch failed: ${e}`); }
    const postRelevant = postIssueIssues.filter((i: any) => i.labels.some((l: any) => l.startsWith("wayfinder:") || l.startsWith("agent:") || ["ready-for-agent","ready-for-human","needs-triage","needs-info","wontfix"].includes(l)));
    const hasRetiredUsers = postRelevant.some((i: any) => i.labels.includes(AGENT_RESEARCH_RETIRED) || i.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED));
    if (hasRetiredUsers) {
      throw new Error("retired label users still present after issue normalization — must be zero before repository label deletion");
    }
  }
  for (const upd of plan.labelDescriptionUpdates) {
    const liveDesc = (await opts.inventoryOps.getLabelDescriptions())[upd.name];
    if (liveDesc !== upd.newDesc) {
      try { await opts.mutationOps.updateLabelDescription(upd.name, upd.newDesc); } catch (e) { throw new Error(`label description update failed for ${upd.name}: ${e}`); }
    }
  }
  // Delete retired labels independently (per-label)
  for (const del of plan.retiredLabelsToDelete) {
    const liveState = normalizeRetiredState(await opts.inventoryOps.getRetiredLabelsExist() as any);
    if (liveState[del]) {
      try { await opts.mutationOps.deleteLabel(del); } catch (e) { throw new Error(`retired label delete failed for ${del}: ${e}`); }
    }
  }
  // Also attempt to delete any remaining retired labels that exist but were not in plan (independent)
  {
    const liveState = normalizeRetiredState(await opts.inventoryOps.getRetiredLabelsExist() as any);
    for (const label of [AGENT_RESEARCH_RETIRED, WAYFINDER_PRESERVE_FUTURES_RETIRED] as const) {
      if (liveState[label] && !plan.retiredLabelsToDelete.includes(label)) {
        try { await opts.mutationOps.deleteLabel(label); } catch (e) { throw new Error(`retired label delete failed for ${label}: ${e}`); }
      }
    }
  }
  let afterIssues: IssueInput[];
  let afterLabelDescs: Record<string,string>;
  let afterRetiredRaw: RetiredLabelState | boolean;
  try {
    afterIssues = await opts.inventoryOps.listOpenIssues();
    afterLabelDescs = await opts.inventoryOps.getLabelDescriptions();
    afterRetiredRaw = await opts.inventoryOps.getRetiredLabelsExist();
  } catch (e) {
    throw new Error(`post-inventory fetch failed: ${e}`);
  }
  const afterRelevant = afterIssues.filter(i => i.labels.some(l => l.startsWith("wayfinder:") || l.startsWith("agent:") || ["ready-for-agent","ready-for-human","needs-triage","needs-info","wontfix"].includes(l)));
  const afterPlan = planMigration(afterRelevant, opts.explicitTaskPlan);
  const afterRetired = normalizeRetiredState(afterRetiredRaw as any);
  if (migrationRequired(afterPlan, { labelDescriptions: afterLabelDescs, retiredLabelsExist: afterRetired })) {
    throw new Error(`postcondition failed: still requires migration: ${JSON.stringify(afterPlan.plannedMutations.slice(0,2))} descNeeded=${afterPlan.labelDescriptionUpdates.some(u=>afterLabelDescs[u.name]!==u.newDesc)} retired=${JSON.stringify(afterRetired)}`);
  }
  if (hasBlockingMigrationProblems(afterPlan)) throw new Error(`postcondition blocking problems remain`);
  let afterHead = headSha;
  if (opts.inventoryOps.getHeadSha) { try { afterHead = await opts.inventoryOps.getHeadSha(); } catch {} }
  const after = buildReviewedReceipt(afterRelevant, afterLabelDescs, afterRetired, afterPlan, afterHead);
  const blockingProblems = hasBlockingMigrationProblems(afterPlan);
  const migrationNeeded = migrationRequired(afterPlan, { labelDescriptions: afterLabelDescs, retiredLabelsExist: afterRetired });
  const checkPassed = !blockingProblems && !migrationNeeded;
  return { plan, receipt: before, before, after, afterPlan, applied: true, blockingProblems, migrationRequired: migrationNeeded, checkPassed };
}

export interface ApplyOps {
  updateIssueLabels: (issueNumber: number, addLabels: string[], removeLabels: string[]) => Promise<boolean>;
  updateLabelDescription: (name: string, description: string) => Promise<boolean>;
  deleteLabel: (name: string) => Promise<boolean>;
  comment: (issueNumber: number, body: string) => Promise<boolean>;
}

export function hasBlockingMigrationProblems(plan: MigrationPlan): boolean {
  const retiredCodes = new Set(["RETIRED_AGENT_RESEARCH", "RETIRED_PRESERVE_FUTURES"]);
  const hasNonRetiredContradiction = plan.contradictions.some(c => !retiredCodes.has(c.code as any));
  return hasNonRetiredContradiction || plan.ambiguousResearch.length > 0 || plan.taskClassificationPlan.some(t => t.planned === "ambiguous");
}
export function migrationRequired(plan: MigrationPlan, repositoryLabelState?: { labelDescriptions: Record<string,string>, retiredLabelsExist: RetiredLabelState | boolean }): boolean {
  const hasMutations = plan.plannedMutations.length > 0;
  if (repositoryLabelState) {
    const descNeeded = plan.labelDescriptionUpdates.some(u => repositoryLabelState.labelDescriptions[u.name] !== u.newDesc);
    const normalized = normalizeRetiredState(repositoryLabelState.retiredLabelsExist as any);
    const retiredNeeded = plan.retiredLabelsToDelete.some(label => normalized[label] === true);
    const anyRetiredExists = Object.values(normalized).some(Boolean);
    // Independent per-label existence makes migration required (second phase after user cleanup)
    return hasMutations || descNeeded || retiredNeeded || anyRetiredExists;
  }
  // Without repo state, only mutations and real label updates matter; retired existence unknown
  const hasRealLabelUpdates = plan.labelDescriptionUpdates.some(u => u.oldDesc !== "(unknown — will fetch live)");
  const hasLabelDesc = hasRealLabelUpdates;
  return hasMutations || hasLabelDesc;
}
export function isCheckFailed(plan: MigrationPlan): boolean {
  return hasBlockingMigrationProblems(plan) || migrationRequired(plan);
}

export function formatReceipt(plan: MigrationPlan, mode: string, verdict?: { checkPassed: boolean; blockingProblems?: boolean; migrationRequired?: boolean }): string {
  const checkPassed = verdict ? verdict.checkPassed : !isCheckFailed(plan);
  return JSON.stringify({
    mode,
    newlyEligibleResearch: plan.newlyEligibleResearch,
    blockedResearch: plan.blockedResearch,
    ambiguousResearch: plan.ambiguousResearch,
    taskClassificationPlan: plan.taskClassificationPlan,
    retiredLabelUsers: plan.retiredLabelUsers,
    contradictions: plan.contradictions,
    plannedMutations: plan.plannedMutations,
    unchangedIssues: plan.unchangedIssues,
    labelDescriptionUpdates: plan.labelDescriptionUpdates,
    retiredLabelsToDelete: plan.retiredLabelsToDelete,
    checkPassed,
    blockingProblems: verdict?.blockingProblems,
    migrationRequired: verdict?.migrationRequired,
  }, null, 2);
}
export function formatReceiptFromResult(result: { plan: MigrationPlan; checkPassed: boolean; blockingProblems: boolean; migrationRequired: boolean }, mode: string): string {
  return formatReceipt(result.plan, mode, { checkPassed: result.checkPassed, blockingProblems: result.blockingProblems, migrationRequired: result.migrationRequired });
}

// CLI adapter — testable entry point that constructs real ops then delegates to runTrackerMigration only
export interface CliDeps {
  execSync?: (cmd: string, opts?: any) => string;
  runGh?: (args: string[]) => Promise<string>;
  readFileSync?: (path: string, encoding: string) => string;
  writeFileSync?: (path: string, data: string) => void;
  mkdirSync?: (path: string, opts?: any) => void;
  getHeadSha?: () => string;
}

export async function runTrackerMigrationCli(args: string[], deps: CliDeps = {}): Promise<{ exitCode: number; receiptPath?: string; plan?: MigrationPlan }> {
  const isCheck = args.includes("--check");
  const isDryRun = args.includes("--dry-run");
  const isApply = args.includes("--apply");
  let mode: "check" | "dry-run" | "apply" = "dry-run";
  if (isCheck) mode = "check";
  else if (isApply) mode = "apply";
  else if (isDryRun) mode = "dry-run";

  const receiptArgIndex = args.indexOf("--receipt");
  let receiptPathArg: string | undefined;
  if (receiptArgIndex !== -1) receiptPathArg = args[receiptArgIndex + 1];

  // Resolve helpers with defaults
  const execSyncFn = deps.execSync ?? ((await import("node:child_process")).execSync as any);
  const runGhFn = deps.runGh ?? (async (ghArgs: string[]) => {
    const { promisify } = await import("node:util");
    const { execFile } = await import("node:child_process");
    const execFileAsync = promisify(execFile);
    const home = process.env.HOME || "";
    const candidates = ["/usr/bin/gh", home ? `${home}/.local/bin/gh` : "", "/home/jeff/.local/bin/gh"];
    let bin = "gh";
    for (const p of candidates) if (p && fsSync.existsSync(p)) { bin = p; break; }
    const token = process.env.GH_TOKEN || "";
    const env = { ...process.env, GH_TOKEN: token };
    const { stdout } = await execFileAsync(bin, ghArgs, { env, cwd: process.cwd(), maxBuffer: 10*1024*1024 }) as any;
    return (stdout as string).trim();
  });

  const readFile = deps.readFileSync ?? fsSync.readFileSync;
  const writeFile = deps.writeFileSync ?? fsSync.writeFileSync;
  const mkdir = deps.mkdirSync ?? fsSync.mkdirSync;

  const getHeadSha = deps.getHeadSha ?? (() => {
    try { return execSyncFn("git rev-parse HEAD", { encoding: "utf8" }).trim(); } catch { return "unknown"; }
  });

  const parseOwnerRepo = () => {
    try {
      const out = execSyncFn("git remote get-url origin", { encoding: "utf8" }).trim();
      const m = out.match(/github\.com[:\/]([^\/]+)\/([^\/\.]+)/);
      if (m) return { owner: m[1], repo: m[2] };
    } catch {}
    return null;
  };

  const inventoryOps: InventoryOps2 = {
    listOpenIssues: async () => {
      const rawJson = await runGhFn(["issue", "list", "--state", "open", "--limit", "100", "--json", "number,title,body,labels,assignees,state"]);
      const raw: any[] = JSON.parse(rawJson);
      const ownerRepo = parseOwnerRepo();
      return await Promise.all(raw.map(async (r: any) => {
        let blockedByCount: number | undefined = undefined;
        if (ownerRepo) {
          try {
            const summary = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${r.number}`, "--jq", ".issue_dependencies_summary.blocked_by"]);
            const n = parseInt(summary.trim(), 10);
            if (!isNaN(n)) blockedByCount = n;
          } catch { blockedByCount = undefined; }
        }
        return {
          number: r.number,
          title: r.title,
          state: r.state.toLowerCase() as "open" | "closed",
          labels: r.labels.map((l: any) => l.name),
          assignees: r.assignees.map((a: any) => a.login),
          blockedByCount,
          body: r.body,
        };
      }));
    },
    getLabelDescriptions: async () => {
      const ownerRepo = parseOwnerRepo();
      if (!ownerRepo) throw new Error("cannot resolve owner/repo for label descriptions");
      // Try bulk fetch
      try {
        const rawJson = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels`, "--paginate"]);
        const labels: any[] = JSON.parse(rawJson);
        const map: Record<string,string> = {};
        for (const l of labels) map[l.name] = l.description ?? "";
        // Ensure all canonical present, if missing treat as absent (empty) not unknown
        for (const name of Object.keys(CANONICAL_LABEL_DESCRIPTIONS)) {
          if (!(name in map)) map[name] = "";
        }
        return map;
      } catch (e) {
        const msg = String(e).toLowerCase();
        if (msg.includes("404") || msg.includes("not found")) {
          throw new Error(`failed to fetch repository labels (bulk): ${e}`);
        }
        // For bulk failure, try per-label with 404 vs unknown
        const map: Record<string,string> = {};
        for (const name of Object.keys(CANONICAL_LABEL_DESCRIPTIONS)) {
          try {
            const desc = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels/${encodeURIComponent(name)}`, "--jq", ".description"]);
            map[name] = desc ?? "";
          } catch (err) {
            const m2 = String(err).toLowerCase();
            if (m2.includes("404") || m2.includes("not found")) {
              map[name] = "";
            } else {
              throw new Error(`failed to fetch label description for ${name}: ${err}`);
            }
          }
        }
        return map;
      }
    },
    getRetiredLabelsExist: async () => {
      const ownerRepo = parseOwnerRepo();
      if (!ownerRepo) throw new Error("cannot resolve owner/repo for retired labels");
      const result: RetiredLabelState = {
        [AGENT_RESEARCH_RETIRED]: false,
        [WAYFINDER_PRESERVE_FUTURES_RETIRED]: false,
      };
      for (const label of [AGENT_RESEARCH_RETIRED, WAYFINDER_PRESERVE_FUTURES_RETIRED] as const) {
        try {
          await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels/${encodeURIComponent(label)}`]);
          result[label] = true;
        } catch (e) {
          const msg = String(e).toLowerCase();
          if (msg.includes("404") || msg.includes("not found")) {
            result[label] = false;
          } else {
            // Try to distinguish via second call message
            try {
              const msg2 = await runGhFn(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/labels/${encodeURIComponent(label)}`, "--jq", ".message"]);
              if (msg2.toLowerCase().includes("not found")) result[label] = false;
              else throw e;
            } catch (e2) {
              const m2 = String(e2).toLowerCase();
              if (m2.includes("404") || m2.includes("not found")) result[label] = false;
              else throw new Error(`failed to check retired label ${label}: ${e}`);
            }
          }
        }
      }
      return result;
    },
    getHeadSha: async () => getHeadSha(),
  };

  const mutationOps: MutationOps2 = {
    updateIssueLabels: async (issueNumber, add, remove) => {
      const ghArgs: string[] = ["issue", "edit", String(issueNumber)];
      for (const a of add) ghArgs.push("--add-label", a);
      for (const r of remove) ghArgs.push("--remove-label", r);
      await runGhFn(ghArgs);
    },
    updateLabelDescription: async (name, description) => {
      await runGhFn(["api", "--method", "PATCH", `repos/${parseOwnerRepo()?.owner}/${parseOwnerRepo()?.repo}/labels/${encodeURIComponent(name)}`, "-f", `description=${description}`]);
    },
    deleteLabel: async (name) => {
      await runGhFn(["api", "--method", "DELETE", `repos/${parseOwnerRepo()?.owner}/${parseOwnerRepo()?.repo}/labels/${encodeURIComponent(name)}`]);
    },
  };

  console.log(`Tracker migration — mode: ${mode}`);

  if (mode === "check") {
    let result;
    try { result = await runTrackerMigration({ mode: "check", inventoryOps }); } catch (e) { console.error(`CHECK inventory failed: ${e}`); return { exitCode: 1 }; }
    console.log(formatReceipt(result.plan, mode, { checkPassed: result.checkPassed, blockingProblems: result.blockingProblems, migrationRequired: result.migrationRequired }));
    if (!result.checkPassed) {
      console.error("CHECK FAILED: migration required or contradictions exist (live state)");
      return { exitCode: 1, plan: result.plan };
    } else {
      console.log("CHECK PASSED: no migration required and no contradictions");
      return { exitCode: 0, plan: result.plan };
    }
  }

  if (mode === "dry-run") {
    let headSha: string;
    try { headSha = getHeadSha(); } catch (e) { console.error(`Failed to resolve HEAD SHA: ${e}`); return { exitCode: 1 }; }
    if (!headSha || headSha === "unknown") { console.error("Failed to resolve exact HEAD SHA"); return { exitCode: 1 }; }
    let result;
    try { result = await runTrackerMigration({ mode: "dry-run", inventoryOps, candidateHeadSha: headSha }); } catch (e) { console.error(`Dry-run inventory failed: ${e}`); return { exitCode: 1 }; }
    console.log(formatReceipt(result.plan, mode, { checkPassed: result.checkPassed, blockingProblems: result.blockingProblems, migrationRequired: result.migrationRequired }));
    const receiptPath = ".sandcastle/logs/migration-dry-run-receipt.json";
    try { mkdir(".sandcastle/logs", { recursive: true }); } catch (e) { console.error(`Failed to create logs dir: ${e}`); return { exitCode: 1 }; }
    try { writeFile(receiptPath, JSON.stringify(result.receipt, null, 2)); console.log(`Dry-run receipt written to ${receiptPath}`); } catch (e) { console.error(`Failed to write dry-run receipt: ${e}`); return { exitCode: 1 }; }
    console.log("DRY-RUN complete — no writes performed");
    if (hasBlockingMigrationProblems(result.plan)) {
      console.log("NOTE: dry-run shows blocking contradictions/ambiguities that would block apply");
    } else if (migrationRequired(result.plan, { labelDescriptions: result.receipt.labelDescriptions, retiredLabelsExist: result.receipt.retiredLabelsExist })) {
      console.log("NOTE: dry-run shows migration work required — apply will execute");
    }
    return { exitCode: 0, receiptPath, plan: result.plan };
  }

  if (mode === "apply") {
    if (!receiptPathArg) {
      console.error("APPLY requires --receipt <path>: missing receipt argument");
      return { exitCode: 1 };
    }
    let reviewedReceipt: ReviewedReceipt;
    try {
      const raw = readFile(receiptPathArg, "utf8");
      reviewedReceipt = JSON.parse(raw);
    } catch (e) {
      console.error(`Failed to read receipt ${receiptPathArg}: ${e}`);
      return { exitCode: 1 };
    }
    try {
      const result = await runTrackerMigration({ mode: "apply", inventoryOps, mutationOps, reviewedReceipt });
      console.log("Migration successful and idempotent");
      const receiptPath = ".sandcastle/logs/migration-apply-receipt.json";
      const persisted = {
        before: result.before,
        after: result.after,
        afterPlan: result.afterPlan,
        applied: result.applied,
        checkPassed: result.checkPassed,
        blockingProblems: result.blockingProblems,
        migrationRequired: result.migrationRequired,
        receipt: result.receipt,
        beforeReceipt: result.before,
        afterReceipt: result.after,
      };
      try { mkdir(".sandcastle/logs", { recursive: true }); } catch (e) { console.error(`Failed to create logs dir: ${e}`); return { exitCode: 1 }; }
      try { writeFile(receiptPath, JSON.stringify(persisted, null, 2)); } catch (e) { console.error(`Failed to write apply receipt: ${e}`); return { exitCode: 1 }; }
      return { exitCode: 0, receiptPath, plan: result.plan };
    } catch (e) {
      console.error(`APPLY FAILED: ${e}`);
      return { exitCode: 1 };
    }
  }

  return { exitCode: 1 };
}

// CLI entry — thin wrapper around runTrackerMigrationCli
async function main() {
  const args = process.argv.slice(2);
  const result = await runTrackerMigrationCli(args, {});
  process.exit(result.exitCode);
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(e => {
    console.error(e);
    process.exit(1);
  });
}
