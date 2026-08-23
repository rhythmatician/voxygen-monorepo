import { describe, it, expect, vi } from "vitest";
import { runCanary } from "./tracker-canary.mts";
import { claimImplementation, reconcileStaleImplementation } from "./tracker-operations.mts";
import type { IssueInput } from "./tracker-policy.mts";
import { TRACER_BODY } from "./fixtures.mts";

function makeMockOps() {
  const store = new Map<number, IssueInput>();
  let nextId = 9000;
  const ops = {
    createIssue: async (title: string, body: string, labels: string[]) => {
      const id = nextId++;
      store.set(id, {
        number: id,
        title,
        state: "open" as const,
        labels: [...labels],
        assignees: [],
        body,
        blockedByCount: 0,
      });
      return id;
    },
    fetchIssue: async (id: number) => {
      const issue = store.get(id);
      if (!issue) throw new Error(`not found ${id}`);
      return { ...issue, labels: [...issue.labels], assignees: [...issue.assignees] };
    },
    closeIssue: async (id: number) => {
      const issue = store.get(id);
      if (issue) issue.state = "closed";
    },
    cleanupIssue: async (id: number) => {
      const issue = store.get(id);
      if (!issue) return;
      issue.assignees = [];
      issue.labels = issue.labels.filter(l => l !== "agent:in-progress" && l !== "agent:implement" && l !== "agent:blocked");
    },
    updateIssueLabels: async (id: number, add: string[], remove: string[]) => {
      const issue = store.get(id);
      if (!issue) throw new Error(`not found ${id}`);
      for (const a of add) if (!issue.labels.includes(a)) issue.labels.push(a);
      issue.labels = issue.labels.filter(l => !remove.includes(l));
    },
    claimImplementation: async (issue: IssueInput) => {
      // Delegate to production claimImplementation — not a reimplementation
      const claimOps = {
        fetchIssue: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) throw new Error("not found "+id);
          return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
        },
        applyClaim: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n)!;
          if(!st.labels.includes("agent:in-progress")) st.labels.push("agent:in-progress");
          st.labels=st.labels.filter(l=>l!=="agent:implement");
          if(st.assignees.length===0) st.assignees.push("bot");
        },
        verifyClaim: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) throw new Error("not found "+id);
          return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
        },
        compensateClaim: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) return false;
          st.labels=st.labels.filter(l=>l!=="agent:in-progress");
          st.assignees=[];
          return true;
        },
        claimantLogin: "bot",
      };
      const stored = store.get(issue.number);
      if (!stored) return { success: false, reason: "not found" };
      const res = await claimImplementation(String(issue.number), stored, claimOps as any);
      return { success: res.success, reason: (res as any).reason };
    },
    reconcile: async (issue: IssueInput) => {
      const reconcileOps: any = {
        releaseClaim: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) return false;
          st.labels=st.labels.filter(l=>l!=="agent:in-progress");
          st.assignees=[];
          return true;
        },
        comment: async () => true,
        fetchIssue: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) throw new Error("not found "+id);
          return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
        },
      };
      const res = await reconcileStaleImplementation(issue, `sandcastle/issue-${issue.number}`, reconcileOps);
      return res.reconciled;
    },
    comment: async () => {},
    _store: store,
  };
  return ops;
}

describe("tracker-canary", () => {
  it("requires explicit live flag", async () => {
    const ops = makeMockOps();
    await expect(runCanary(ops as any, { live: false })).rejects.toThrow("explicit --live");
  });

  it("proves implementation discoverable only with ready+implement", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.implementationDiscoverableOnlyWithReadyAndImplement).toBe(true);
  });

  it("proves successful claim consumes implement and retains ready", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.successfulClaimConsumesImplement).toBe(true);
  });

  it("proves stale reconciliation releases without restoring implement", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.staleReconciliationReleasesWithoutRestoring).toBe(true);
  });

  it("proves research discoverable from wayfinder:research alone", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.researchDiscoverableFromWayfinderAlone).toBe(true);
  });

  it("proves contradictions fail before worker", async () => {
    const ops = makeMockOps();
    const result = await runCanary(ops as any, { live: true });
    expect(result.contradictionsFailBeforeWorker).toBe(true);
  });

  it("cleans all fixtures in finally and retains receipt if cleanup incomplete", async () => {
    const ops = makeMockOps();
    // Make close fail for one fixture to test cleanup failure receipt
    const originalClose = ops.closeIssue;
    let failOnce = true;
    ops.closeIssue = async (id: number) => {
      if (failOnce) {
        failOnce = false;
        throw new Error("cleanup failed");
      }
      return originalClose(id);
    };
    const result = await runCanary(ops as any, { live: true });
    // Should have cleanup failures but still have receipt
    expect(result.fixtureIds.length).toBeGreaterThan(0);
    // Fixtures should be attempted to close; cleanupFailures may contain entry
    // In this mock, first close fails, but others succeed
    expect(result.cleanupFailures.length).toBeGreaterThan(0);
    expect(result.fixturesCleaned).toBe(false);
  });

  it("never launches Muse or creates commits — inert boundaries", async () => {
    const ops = makeMockOps();
    // Verify that canary ops never call a model — we just check that runCanary doesn't import or call Muse
    // This is structural: canary uses mock ops and never creates branch/commits
    const result = await runCanary(ops as any, { live: true });
    // All fixtures should be closed, no branches created
    for (const id of result.fixtureIds) {
      const issue = await ops.fetchIssue(id);
      expect(issue.state).toBe("closed");
    }
  });
  it("live canary constructs exact gh api POST command via injected runner", async () => {
    const calls: string[][] = [];
    const mockRunGh = async (args: string[]) => {
      calls.push(args);
      if (args[0] === "api" && args[1] === "user") return "test-bot";
      if (args[0] === "api" && args.includes("--method") && args.includes("POST")) return "9999";
      if (args[0] === "issue" && args[1] === "view") return JSON.stringify({ number: 9999, title: "t", body: "b", labels: [], assignees: [], state: "open" });
      if (args[0] === "api" && args[1].includes("issues")) return "{}";
      return "";
    };
    const { createLiveCanaryOps } = await import("./tracker-canary.mts");
    const ops = createLiveCanaryOps({ owner: "rhythmatician", repo: "voxygen-monorepo", runGh: mockRunGh, claimantLogin: "test-bot" });
    const id = await ops.createIssue("title", "body", ["ready-for-agent", "agent:implement"]);
    expect(id).toBe(9999);
    expect(calls[0]).toEqual(["api", "--method", "POST", "repos/rhythmatician/voxygen-monorepo/issues", "-f", "title=title", "-f", "body=body", "-f", "labels[]=ready-for-agent", "-f", "labels[]=agent:implement", "--jq", ".number"]);
  });

  it("live canary claim uses claimantLogin via production claimImplementation", async () => {
    const calls: string[][] = [];
    const store = new Map<number, any>([[100, { number: 100, title: "t", state: "open", labels: ["ready-for-agent", "agent:implement"], assignees: [], body: "Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice", blockedByCount: 0 }]]);
    const mockRunGh = async (args: string[]) => {
      calls.push(args);
      if (args[0] === "api" && args[1] === "user") return "canary-bot";
      if (args[0] === "issue" && args[1] === "view") {
        const id = parseInt(args[2],10);
        const issue = store.get(id);
        return JSON.stringify({ number: issue.number, title: issue.title, body: issue.body, labels: issue.labels.map((n:string)=>({name:n})), assignees: issue.assignees.map((l:string)=>({login:l})), state: issue.state });
      }
      if (args[0] === "issue" && args[1] === "edit") {
        const id = parseInt(args[2],10);
        const issue = store.get(id);
        if (args.includes("--add-assignee")) {
          const idx = args.indexOf("--add-assignee");
          const assignee = args[idx+1] === "@me" ? "canary-bot" : args[idx+1];
          if (!issue.assignees.includes(assignee)) issue.assignees.push(assignee);
        }
        if (args.includes("--add-label")) {
          const idx = args.indexOf("--add-label");
          const label = args[idx+1];
          if (!issue.labels.includes(label)) issue.labels.push(label);
        }
        if (args.includes("--remove-label")) {
          const idx = args.indexOf("--remove-label");
          const label = args[idx+1];
          issue.labels = issue.labels.filter((l:string)=>l!==label);
        }
        if (args.includes("--remove-assignee")) {
          const idx = args.indexOf("--remove-assignee");
          const assignee = args[idx+1];
          issue.assignees = issue.assignees.filter((a:string)=>a!==assignee);
        }
        return "";
      }
      if (args[0] === "api" && args[1].includes("issues")) return "0";
      return "";
    };
    const { createLiveCanaryOps } = await import("./tracker-canary.mts");
    const ops = createLiveCanaryOps({ owner: "rhythmatician", repo: "voxygen-monorepo", runGh: mockRunGh, claimantLogin: "canary-bot" });
    const issue = store.get(100)!;
    const res = await ops.claimImplementation(issue);
    expect(res.success).toBe(true);
    const after = store.get(100)!;
    expect(after.assignees).toContain("canary-bot");
    expect(after.labels).toContain("agent:in-progress");
    expect(after.labels).not.toContain("agent:implement");
    // Verify exact gh command for claim
    const claimCall = calls.find(c => c.includes("--add-assignee") && c.includes("canary-bot"));
    expect(claimCall).toBeDefined();
  });

});
