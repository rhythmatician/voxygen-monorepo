import * as fsSync from "node:fs";
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
  "wayfinder:research": "Wayfinder research — AFK evidence-backed research; eligible when open, unassigned, unblocked, no in-progress (no second label required)",
  "wayfinder:task": "Wayfinder task — unblocks a decision; executor via ready-for-agent (AFK) or ready-for-human (HITL), exactly one required",
  "wayfinder:prototype": "Wayfinder prototype — HITL only, raises fidelity with cheap artifact; never AFK",
  "wayfinder:grilling": "Wayfinder decision interview — HITL only, conversation; never AFK",
  "wayfinder:map": "Wayfinder destination map (single map per repo)",
  "agent:implement": "One-shot Sandcastle implementation command — paired with ready-for-agent, consumed on claim (transient)",
  "agent:in-progress": "Transient Sandcastle claim — supplements native assignee, released on interruption (not a blocker)",
  "agent:blocked": "Sandcastle intervention required — failed attempt, not a product dependency (native blocked_by is dependency)",
  "ready-for-agent": "Fully specified, ready for an AFK agent (durable readiness, paired with agent:implement to launch)",
  "ready-for-human": "Requires human implementation (durable readiness for HITL tasks)",
};

// Explicit task classification plan for historical tasks — to avoid inferring from prose
// If empty, ambiguous tasks will be reported and migration will fail before mutation
export const EXPLICIT_TASK_PLAN: Record<number, "ready-for-agent" | "ready-for-human"> = {
  // Reviewed per #195 verdict provisional classifications — committed, not edited after merge
  // 166: feedback-baseline-run includes IDE/startup/visual evidence => ready-for-human
  // 127: real-client visual/screenshot already assigned => ready-for-human
  // 114: HITL assembly/validation => ready-for-human
  // 64: AFK evidence-task executor not yet available => ready-for-human for now
  // 61: first human-attended capability audit => ready-for-human
  // 25: bounded repo implementation, blocker #24 closed, but body not tracer-ready => ready-for-human for this migration
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
      // For retired labels, plan removal
      if (validation.retired.length > 0) {
        const toRemove = validation.retired.map(r => r.labels?.[0] ?? "").filter(Boolean);
        if (toRemove.length > 0) {
          plan.retiredLabelUsers.push({ issue: issue.number, labels: toRemove });
          plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: toRemove, reason: `remove retired labels ${toRemove.join(", ")}` });
        }
      }
      // For multiple wayfinder, etc., don't auto-mutate other labels — just report contradiction
      // But still check task classification if it's a task without readiness
    }

    const wayfinderLabels = getWayfinderLabels(issue);
    const isResearch = wayfinderLabels.length === 1 && wayfinderLabels[0] === WAYFINDER_RESEARCH;
    const isTask = issue.labels.includes(WAYFINDER_TASK);
    const isPreserveFutures = issue.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED);

    // Handle preserve-futures replacement: only where checkpoint intent already explicit (body contains preserve-futures or checkpoint)
    // Spec says only where checkpoint intent is already explicit; we require body to contain "preserve" or "checkpoint" or explicit plan
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
        // Actually ambiguous for preserve-futures without intent
        plan.contradictions.push({ issue: issue.number, code: "PRESERVE_FUTURES_NO_INTENT", reason: "wayfinder:preserve-futures without explicit checkpoint intent — requires manual review" });
      }
      continue;
    }

    if (isResearch) {
      // Check eligibility under new policy (wayfinder:research alone)
      const eligibility = isResearchEligible(issue);
      // Also check if currently blocked
      if (issue.blockedByCount === undefined) {
        plan.ambiguousResearch.push(issue.number);
        plan.contradictions.push({ issue: issue.number, code: "BLOCKED_UNKNOWN", reason: "blocked_by unknown — fail closed" });
      } else if (issue.blockedByCount > 0) {
        plan.blockedResearch.push(issue.number);
      } else if (eligibility.eligible) {
        // Would be newly eligible — report frontier
        // But also check if it has any other contradictory labels that would have blocked before
        if (validation.warnings.length > 0) {
          // Has residue that would be removed but still eligible
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
        // Not eligible for other reasons (assigned, in-progress, etc.)
        const reason = (eligibility as any).reason ?? "ineligible";
        if (reason.includes("already assigned") || reason.includes("already has")) {
          plan.blockedResearch.push(issue.number);
        } else {
          plan.ambiguousResearch.push(issue.number);
        }
      }
      // Also handle retired residue removal for research
      const residue = getRemovableResidueLabels(issue);
      if (residue.length > 0 && !plan.plannedMutations.some(m => m.issue === issue.number)) {
        plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical redundancy residue" });
      }
      continue;
    }

    if (isTask) {
      const readiness = getTaskReadiness(issue);
      const hasReadyAgent = issue.labels.includes(READY_FOR_AGENT);
      const hasReadyHuman = issue.labels.includes(READY_FOR_HUMAN);
      
      if (readiness === "none") {
        // Need explicit classification
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
        // contradictions already captured
      } else {
        // Has exactly one readiness — check if it's valid and needs no mutation
        plan.taskClassificationPlan.push({ issue: issue.number, current: [readiness], planned: readiness, reason: "already classified" });
        // No mutation needed unless it also has retired/residue
        const residue = getRemovableResidueLabels(issue);
        if (residue.length > 0) {
          plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove residue" });
        } else {
          // Check if unchanged
          if (!plan.plannedMutations.some(m => m.issue === issue.number)) {
            // Will be counted as unchanged if no other mutations
          }
        }
      }
      // Also check for retired
      continue;
    }

    // Non-research, non-task issues: check for retired/residue
    const residue = getRemovableResidueLabels(issue);
    if (residue.length > 0) {
      // If not already planned
      if (!plan.plannedMutations.some(m => m.issue === issue.number && m.removeLabels.some(l => residue.includes(l)))) {
        plan.plannedMutations.push({ issue: issue.number, addLabels: [], removeLabels: residue, reason: "remove historical residue" });
      }
    } else if (validation.contradictions.length === 0) {
      plan.unchangedIssues.push(issue.number);
    }
  }

  // Deduplicate unchanged: issues that had no mutations and no contradictions
  // Already handled above

  // Label description updates: check canonical vs current (we can't fetch current without ops, so plan to update all canonical)
  for (const [name, newDesc] of Object.entries(CANONICAL_LABEL_DESCRIPTIONS)) {
    plan.labelDescriptionUpdates.push({ name, oldDesc: "(unknown — will fetch live)", newDesc });
  }

  // Retired labels to delete: only after no open issue depends on them
  const hasRetiredUsers = plan.retiredLabelUsers.length > 0;
  const hasPreserveFutures = issues.some(i => i.labels.includes(WAYFINDER_PRESERVE_FUTURES_RETIRED));
  const hasAgentResearch = issues.some(i => i.labels.includes(AGENT_RESEARCH_RETIRED));
  if (!hasRetiredUsers && !hasPreserveFutures && !hasAgentResearch) {
    // Check if no open issue depends — we already know none, so safe to retire
    // But we must not delete if any contradiction remains that is retired-related? For now, if no users, we can delete
    // We will only delete if dry-run shows zero retired users after planned mutations would clean them
    // For initial run, there are zero users, so we could delete, but spec says delete only after no open issue depends
    // So we can plan to delete if no current users
    if (!hasAgentResearch) plan.retiredLabelsToDelete.push(AGENT_RESEARCH_RETIRED);
    if (!hasPreserveFutures) plan.retiredLabelsToDelete.push(WAYFINDER_PRESERVE_FUTURES_RETIRED);
  }

  return plan;
}

export interface InventoryOps {
  listOpenIssues: () => Promise<IssueInput[]>;
  getLabelDescriptions: () => Promise<Record<string, string>>;
}

export interface ApplyOps {
  updateIssueLabels: (issueNumber: number, addLabels: string[], removeLabels: string[]) => Promise<boolean>;
  updateLabelDescription: (name: string, description: string) => Promise<boolean>;
  deleteLabel: (name: string) => Promise<boolean>;
  comment: (issueNumber: number, body: string) => Promise<boolean>;
}

export function isCheckFailed(plan: MigrationPlan): boolean {
  const hasRealLabelUpdates = plan.labelDescriptionUpdates.some(u => u.oldDesc !== "(unknown — will fetch live)");
  return plan.contradictions.length > 0 || plan.ambiguousResearch.length > 0 || plan.taskClassificationPlan.some(t => t.planned === "ambiguous") || plan.plannedMutations.length > 0 || hasRealLabelUpdates;
}

export function formatReceipt(plan: MigrationPlan, mode: string): string {
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
    checkPassed: !isCheckFailed(plan),
  }, null, 2);
}

// CLI handling
async function main() {
  const args = process.argv.slice(2);
  const isCheck = args.includes("--check");
  const isDryRun = args.includes("--dry-run");
  const isApply = args.includes("--apply");
  
  let mode: "check" | "dry-run" | "apply" = "dry-run";
  if (isCheck) mode = "check";
  else if (isApply) mode = "apply";
  else if (isDryRun) mode = "dry-run";

  if (mode === "apply" && !isApply) {
    // default should not mutate
  }

  console.log(`Tracker migration — mode: ${mode}`);

  // Dynamic import of gh ops to avoid top-level dependency for tests
  const { execSync } = await import("node:child_process");
  const ghBinary = () => {
    const home = process.env.HOME || "";
    const candidates = ["/usr/bin/gh", home ? `${home}/.local/bin/gh` : "", "/home/jeff/.local/bin/gh"];
    for (const p of candidates) if (p && fsSync.existsSync(p)) return p;
    return "gh";
  };
  const runGh = async (args: string[]): Promise<string> => {
    const { promisify } = await import("node:util");
    const { execFile } = await import("node:child_process");
    const execFileAsync = promisify(execFile);
    const token = process.env.GH_TOKEN || "";
    const env = { ...process.env, GH_TOKEN: token };
    const bin = ghBinary();
    const { stdout } = await execFileAsync(bin, args, { env, cwd: process.cwd(), maxBuffer: 10 * 1024 * 1024 }) as any;
    return (stdout as string).trim();
  };

  // Inventory
  let issues: IssueInput[] = [];
  try {
    const rawJson = await runGh(["issue", "list", "--state", "open", "--limit", "100", "--json", "number,title,body,labels,assignees,state"]);
    const raw: any[] = JSON.parse(rawJson);
    const ownerRepo = (() => {
      try {
        const out = execSync("git remote get-url origin", { encoding: "utf8" }).trim();
        const m = out.match(/github\.com[:/]([^/]+)\/([^/.]+)/);
        if (m) return { owner: m[1], repo: m[2] };
      } catch {}
      return null;
    })();
    issues = await Promise.all(raw.map(async (r: any) => {
      let blockedByCount: number | undefined = undefined;
      if (ownerRepo) {
        try {
          const summary = await runGh(["api", `repos/${ownerRepo.owner}/${ownerRepo.repo}/issues/${r.number}`, "--jq", ".issue_dependencies_summary.blocked_by"]);
          const n = parseInt(summary.trim(), 10);
          if (!isNaN(n)) blockedByCount = n;
        } catch {
          blockedByCount = undefined;
        }
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
  } catch (e) {
    console.error(`Failed to inventory issues: ${e}`);
    process.exit(1);
  }

  console.log(`Inventoried ${issues.length} open issues`);

  // Filter to relevant (those with wayfinder/triage/agent labels)
  const relevant = issues.filter(i => i.labels.some(l => l.startsWith("wayfinder:") || l.startsWith("agent:") || ["ready-for-agent","ready-for-human","needs-triage","needs-info","wontfix"].includes(l)));
  console.log(`Relevant for migration: ${relevant.length}`);

  const plan = planMigration(relevant);

  console.log(formatReceipt(plan, mode));

  if (mode === "check") {
    if (isCheckFailed(plan)) {
      console.error("CHECK FAILED: migration required or contradictions exist");
      process.exit(1);
    } else {
      console.log("CHECK PASSED: no migration required and no contradictions");
      process.exit(0);
    }
  }

  if (mode === "dry-run") {
    console.log("DRY-RUN complete — no writes performed");
    if (isCheckFailed(plan)) {
      console.log("NOTE: dry-run shows contradictions/ambiguities that would block apply");
    }
    process.exit(0);
  }

  if (mode === "apply") {
    if (isCheckFailed(plan)) {
      console.error("APPLY BLOCKED: contradictions or ambiguous classifications exist — resolve before apply");
      console.error(formatReceipt(plan, mode));
      process.exit(1);
    }
    // Verify live state hasn't drifted from reviewed plan before any writes — re-fetch every affected issue
    const driftErrors: string[] = [];
    for (const mut of plan.plannedMutations) {
      try {
        const liveJson = await runGh(["issue", "view", String(mut.issue), "--json", "number,labels,assignees,state,body"]);
        const live = JSON.parse(liveJson);
        const liveLabels: string[] = (live.labels ?? []).map((l:any)=>l.name).sort();
        const planExpectedAdd = (mut.addLabels ?? []).slice().sort();
        const planExpectedRemove = (mut.removeLabels ?? []).slice().sort();
        // If live issue missing or state drifted, fail
        if (live.state?.toLowerCase() !== "open") driftErrors.push(`#${mut.issue} state drifted: ${live.state}`);
        // Check that labels we intend to remove are still present if they were expected to be, and labels we intend to add are not already contradictory
        // Simpler: if live labels differ from inventory snapshot for this issue, drift
        const inventoryIssue = relevant.find(r=>r.number===mut.issue);
        if (inventoryIssue) {
          const invLabels = [...inventoryIssue.labels].sort().join(",");
          const liveLabelStr = liveLabels.join(",");
          if (invLabels !== liveLabelStr && mut.reason !== "retired residue cleanup" ) {
            // Allow retired residue drift? Actually require exact match for safety
            // Compare expected vs live
            const expectedAfterAdd = [...new Set([...liveLabels, ...planExpectedAdd])].filter(l=>!planExpectedRemove.includes(l)).sort().join(",");
            // Just record drift if inventory != live
            if (invLabels !== liveLabelStr) driftErrors.push(`#${mut.issue} labels drifted: inventory [${invLabels}] vs live [${liveLabelStr}]`);
          }
        }
      } catch (e) {
        driftErrors.push(`#${mut.issue} re-fetch failed: ${e}`);
      }
    }
    if (driftErrors.length > 0) {
      console.error("Drift detected — aborting before any mutation:");
      for (const e of driftErrors) console.error("  "+e);
      process.exit(1);
    }
    console.log("Applying mutations...");
    for (const m of plan.plannedMutations) {
      const args: string[] = ["issue", "edit", String(m.issue)];
      for (const add of m.addLabels) args.push("--add-label", add);
      for (const rem of m.removeLabels) args.push("--remove-label", rem);
      try {
        await runGh(args);
        console.log(`  Mutated #${m.issue}: +${m.addLabels.join(",")} -${m.removeLabels.join(",")} (${m.reason})`);
      } catch (e) {
        console.error(`  Failed to mutate #${m.issue}: ${e}`);
        process.exit(1);
      }
    }
    for (const upd of plan.labelDescriptionUpdates) {
      try {
        await runGh(["label", "edit", upd.name, "--description", upd.newDesc]);
        console.log(`  Updated label ${upd.name} description`);
      } catch (e) {
        console.warn(`  Failed to update label ${upd.name}: ${e}`);
      }
    }
    for (const del of plan.retiredLabelsToDelete) {
      try {
        await runGh(["label", "delete", del, "--yes"]);
        console.log(`  Deleted retired label ${del}`);
      } catch (e) {
        console.warn(`  Failed to delete label ${del}: ${e}`);
      }
    }
    console.log("APPLY complete — rerunning check...");
    // Re-fetch full tracker state after mutations for post-check and receipt
    let postIssues: IssueInput[] = relevant;
    try {
      const postJson = await runGh(["issue", "list", "--state","open","--limit","100","--json","number,title,body,labels,assignees,state"]);
      const postRaw: any[] = JSON.parse(postJson);
      const ownerRepo2 = (()=>{ try { const out=require("node:child_process").execSync("git remote get-url origin",{encoding:"utf8"}).trim(); const m=out.match(/github\.com[:/]([^/]+)\/([^/.]+)/); if(m) return {owner:m[1],repo:m[2]};}catch{return null;}})();
      postIssues = await Promise.all(postRaw.map(async (r:any)=>{
        let blockedByCount: number|undefined=undefined;
        if(ownerRepo2){ try { const s=await runGh(["api", `repos/${ownerRepo2.owner}/${ownerRepo2.repo}/issues/${r.number}`, "--jq", ".issue_dependencies_summary.blocked_by"]); const n=parseInt(s.trim(),10); if(!isNaN(n)) blockedByCount=n;}catch{} }
        return { number:r.number, title:r.title, state:r.state.toLowerCase() as "open"|"closed", labels:r.labels.map((l:any)=>l.name), assignees:r.assignees.map((a:any)=>a.login), blockedByCount, body:r.body };
      }));
    } catch {}
    const postPlan = planMigration(postIssues);
    if (isCheckFailed(postPlan)) {
      console.error("POST-APPLY CHECK FAILED");
      process.exit(1);
    }
    console.log("Migration successful and idempotent");
    process.exit(0);
  }
}

if (import.meta.url === `file://${process.argv[1]}`) {
  main().catch(e => {
    console.error(e);
    process.exit(1);
  });
}
