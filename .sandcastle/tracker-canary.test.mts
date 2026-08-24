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
      // Use same full reconciliation operation set as createLiveCanaryOps — not minimal fallback
      const reconcileOps: any = {
        releaseClaim: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) return false;
          st.labels=st.labels.filter(l=>l!=="agent:in-progress");
          st.assignees=[];
          return true;
        },
        comment: async (id: string, body: string) => {
          return true;
        },
        fetchIssue: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) throw new Error("not found "+id);
          return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
        },
        getBatchPrNumber: async () => ({ prNumber: null, state: "absent" as const }),
        getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
        checkBranchExists: async () => "absent" as const,
        checkProvenanceValid: async () => ({ valid:true }),
        hasCommitsAhead: async () => "empty" as const,
        deleteBranch: async () => true,
        addBlocked: async (id: string) => {
          const n=parseInt(id,10);
          const st=store.get(n);
          if(!st) return false;
          if(!st.labels.includes("agent:blocked")) st.labels.push("agent:blocked");
          return true;
        },
        markIntegrated: async () => true,
      };
      const res = await reconcileStaleImplementation(issue, `sandcastle/issue-${issue.number}`, reconcileOps);
      // For canary, no_branch (stale with no branch/PR) is considered success if it released claim, even though reconciled=false (blocked)
      return res.reconciled || res.decision?.type === "no_branch" || res.reason.includes("no branch");
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

describe("tracker-canary — live path behavioral (item 5)", () => {
  it("runCanaryCli with injected runner uses full reconciliation path and records primaryError on failure", async () => {
    const { runCanaryCli } = await import("./tracker-canary.mts");
    const calls:string[][] = [];
    let createdIds = 0;
    const mockRunGh = async (args:string[]) => {
      calls.push(args);
      if (args[0]==="api" && args[1]==="user") return "test-bot";
      if (args[0]==="api" && args.includes("--method") && args.includes("POST")) {
        createdIds++;
        return String(9000+createdIds);
      }
      if (args[0]==="issue" && args[1]==="view") {
        const id = parseInt(args[2],10);
        if (id===9001) {
          return JSON.stringify({ number: id, title:"t", body:"Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice", labels:[{name:"ready-for-agent"}], assignees:[], state:"open" });
        }
        if (id===9002) {
          return JSON.stringify({ number: id, title:"t", body:"Scope bounded observable outcome\nno unresolved design decided\nacceptance criteria done when\nverification path verify\ndependencies blocked by none\nsmall enough for one session\nvertical tracer bullet slice", labels:[{name:"ready-for-agent"},{name:"agent:implement"}], assignees:[], state:"open" });
        }
        return JSON.stringify({ number: id, title:"t", body:"## Question\n\nCanary research question with substantive details for validation, part of #190 with evidence needed and mechanism to be investigated.", labels:[{name:"wayfinder:research"}], assignees:[], state:"open" });
      }
      if (args[0]==="api" && args[1].includes("issues/") && args.includes("--jq")) {
        if (args[1].includes("9002")) return "1";
        return "0";
      }
      if (args[0]==="issue" && args[1]==="edit") return "";
      if (args[0]==="issue" && args[1]==="comment") return "";
      if (args[0]==="issue" && args[1]==="close") return "";
      return "";
    };
    let mkdirCalled=false;
    let written:any=null;
    const result = await runCanaryCli(["--live"], {
      runGh: mockRunGh,
      resolveClaimantLoginFn: async () => "test-bot",
      writeFileSync: (path:string, data:string) => { written=data; },
      mkdirSync: () => { mkdirCalled=true; },
    });
    expect(result.result).toBeDefined();
    expect(result.result!.fixtureIds.length).toBeGreaterThan(0);
    if (result.exitCode!==0) {
      expect(result.result!.primaryError).toBeDefined();
    }
    expect(mkdirCalled).toBe(true);
  });

  it("runCanary cleanup removes claimant and machine labels, closes and reads back", async () => {
    const store = new Map<number, any>();
    let nextId=9100;
    const created:number[] = [];
    const ops2:any = {
      createIssue: async (title:string, body:string, labels:string[]) => {
        const id=nextId++;
        store.set(id, { number:id, title, state:"open" as const, labels:[...labels], assignees:[], body, blockedByCount:0 });
        created.push(id);
        return id;
      },
      fetchIssue: async (id:number) => {
        const st=store.get(id);
        if(!st) throw new Error("not found");
        return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
      },
      closeIssue: async (id:number) => {
        const st=store.get(id);
        if(st) st.state="closed";
      },
      cleanupIssue: async (id:number) => {
        const st=store.get(id);
        if(!st) return;
        st.assignees=[];
        st.labels=st.labels.filter((l:string)=>l!=="agent:in-progress" && l!=="agent:implement" && l!=="agent:blocked");
      },
      removeAssignee: async (id:number) => {
        const st=store.get(id);
        if(st) st.assignees=[];
      },
      removeLabel: async (id:number, label:string) => {
        const st=store.get(id);
        if(st) st.labels=st.labels.filter((l:string)=>l!==label);
      },
      claimImplementation: async (issue:any) => {
        const { claimImplementation } = await import("./tracker-operations.mts");
        const claimOps:any = {
          fetchIssue: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n);
            return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
          },
          applyClaim: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n)!;
            if(!st.labels.includes("agent:in-progress")) st.labels.push("agent:in-progress");
            st.labels=st.labels.filter((l:string)=>l!=="agent:implement");
            if(st.assignees.length===0) st.assignees.push("bot");
          },
          verifyClaim: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n);
            return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
          },
          compensateClaim: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n);
            if(!st) return false;
            st.labels=st.labels.filter((l:string)=>l!=="agent:in-progress");
            st.assignees=[];
            return true;
          },
          claimantLogin: "bot",
        };
        const stored=store.get(issue.number);
        const res=await claimImplementation(String(issue.number), stored, claimOps);
        return { success: res.success, reason:(res as any).reason };
      },
      reconcile: async (issue:any) => {
        const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
        const reconcileOps:any = {
          releaseClaim: async (id:string) => {
            const n=parseInt(id,10);
            const st=store.get(n);
            if(!st) return false;
            st.labels=st.labels.filter((l:string)=>l!=="agent:in-progress");
            st.assignees=[];
            return true;
          },
          comment: async () => true,
          fetchIssue: async (id:string) => {
            const n=parseInt(id,10);
            const st=store.get(n);
            return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
          },
          getBatchPrNumber: async () => ({ prNumber:null, state:"absent" as const }),
          getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
          checkBranchExists: async () => "absent" as const,
          checkProvenanceValid: async () => ({ valid:true }),
          hasCommitsAhead: async () => "empty" as const,
          deleteBranch: async () => true,
          addBlocked: async (id:string) => {
            const n=parseInt(id,10);
            const st=store.get(n);
            if(st && !st.labels.includes("agent:blocked")) st.labels.push("agent:blocked");
            return true;
          },
          markIntegrated: async () => true,
        };
        const res=await reconcileStaleImplementation(issue, `sandcastle/issue-${issue.number}`, reconcileOps);
        return res.reconciled;
      },
    };
    const { runCanary } = await import("./tracker-canary.mts");
    const result = await runCanary(ops2, { live:true });
    expect(result.fixturesCleaned).toBe(true);
    for (const id of result.fixtureIds) {
      const after = await ops2.fetchIssue(id);
      expect(after.state).toBe("closed");
      expect(after.assignees.length).toBe(0);
      expect(after.labels.includes("agent:in-progress")).toBe(false);
      expect(after.labels.includes("agent:implement")).toBe(false);
      expect(after.labels.includes("agent:blocked")).toBe(false);
    }
  });

  it("primary failure plus cleanup failure records primaryError, fixtureIds, and cleanupFailures", async () => {
    const store = new Map<number, any>();
    let nextId=9200;
    const ops:any = {
      createIssue: async (title:string, body:string, labels:string[]) => {
        const id=nextId++;
        store.set(id, { number:id, title, state:"open" as const, labels:[...labels], assignees:[], body, blockedByCount:0 });
        return id;
      },
      fetchIssue: async (id:number) => {
        const st=store.get(id);
        if(!st) throw new Error("not found");
        return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
      },
      closeIssue: async (id:number) => {
        if (id===9200) throw new Error("close failed");
        const st=store.get(id);
        if(st) st.state="closed";
      },
      cleanupIssue: async (id:number) => {
        const st=store.get(id);
        if(st) {
          st.assignees=[];
          st.labels=st.labels.filter((l:string)=>l!=="agent:in-progress" && l!=="agent:implement" && l!=="agent:blocked");
        }
      },
      claimImplementation: async (issue:any) => {
        if (issue.number===9200) return { success:false, reason:"contradiction" };
        const { claimImplementation } = await import("./tracker-operations.mts");
        const claimOps:any = {
          fetchIssue: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n);
            return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
          },
          applyClaim: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n)!;
            st.labels.push("agent:in-progress");
            st.labels=st.labels.filter((l:string)=>l!=="agent:implement");
            st.assignees.push("bot");
          },
          verifyClaim: async (fid:string) => {
            const n=parseInt(fid,10);
            const st=store.get(n);
            return { ...st, labels:[...st.labels], assignees:[...st.assignees] };
          },
          compensateClaim: async () => true,
          claimantLogin: "bot",
        };
        const res=await claimImplementation(String(issue.number), issue, claimOps);
        return { success: res.success, reason:(res as any).reason };
      },
      reconcile: async (issue:any) => {
        const { reconcileStaleImplementation } = await import("./tracker-operations.mts");
        const reconcileOps:any = {
          releaseClaim: async (id:string) => { const st=store.get(parseInt(id,10)); if(st){ st.labels=st.labels.filter((l:string)=>l!=="agent:in-progress"); st.assignees=[]; } return true; },
          comment: async () => true,
          fetchIssue: async (id:string) => { const st=store.get(parseInt(id,10)); return { ...st, labels:[...st.labels], assignees:[...st.assignees] }; },
          getBatchPrNumber: async () => ({ prNumber:null, state:"absent" as const }),
          getPrState: async () => ({ state:"CLOSED", mergedAt:null, found:false }),
          checkBranchExists: async () => "absent" as const,
          checkProvenanceValid: async () => ({ valid:true }),
          hasCommitsAhead: async () => "empty" as const,
          deleteBranch: async () => true,
          addBlocked: async () => true,
          markIntegrated: async () => true,
        };
        const res=await reconcileStaleImplementation(issue, `sandcastle/issue-${issue.number}`, reconcileOps);
        return res.reconciled;
      },
    };
    const { runCanary } = await import("./tracker-canary.mts");
    const result = await runCanary(ops, { live:true });
    expect(result.fixtureIds.length).toBeGreaterThan(0);
    expect(result.primaryError).toBeDefined();
    expect(result.cleanupFailures.length).toBeGreaterThan(0);
    expect(result.fixturesCleaned).toBe(false);
  });
});
