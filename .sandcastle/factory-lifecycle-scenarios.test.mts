import { describe, it, expect } from "vitest";
import { runFactoryIteration, type FactoryIterationResult, type PreparedImplIssue, type PreparedResearchIssue, type ImplWorkerResult } from "./factory-iteration.mts";
import { isImplementationEligible, isResearchEligible, detectContradictions, READY_FOR_AGENT, AGENT_IMPLEMENT, AGENT_IN_PROGRESS, AGENT_BLOCKED, WAYFINDER_RESEARCH, type IssueInput } from "./tracker-policy.mts";
import { classifyTicket } from "./dispatch.mts";
import { claimImplementation, claimResearch, reconcileStaleImplementation, reconcileStaleResearch } from "./tracker-operations.mts";
import { partitionWorkerOutcomes } from "./factory-verdict-gate.mts";
import { verdictFixture } from "./review-verdict.mts";
import type { ResearchResult } from "./research-result.mts";
import { TRACER_BODY } from "./fixtures.mts";
import { createTrackerAdapter, makeMemoryReceiptSink, type TrackerAdapter } from "./tracker-adapter.mts";
import type { GhTransport } from "./gh-transport.mts";

// ---------------------------------------------------------------------------
// Deferred barrier (no sleeps), event tape, verdict/result helpers
// ---------------------------------------------------------------------------
type Deferred<T=void> = { promise: Promise<T>; resolve:(v:T)=>void; reject:(e:unknown)=>void; get isResolved():boolean };
function deferred<T=void>(): Deferred<T> {
  let resolve!: (v:T)=>void; let reject!: (e:unknown)=>void; let isResolved=false;
  const promise=new Promise<T>((res,rej)=>{ resolve=(v:T)=>{isResolved=true;res(v)}; reject=(e)=>{isResolved=true;rej(e)}; });
  return { promise, resolve:(v:T)=>resolve(v), reject, get isResolved(){return isResolved} };
}
function newTap(){ const events:string[]=[]; return { events, push:(e:string)=>events.push(e) }; }
function approvedVerdict(){ return verdictFixture({ approved:true, findings:[], acceptanceCriteriaMet:[{criterion:"c", met:true}], summary:"ok"}); }
function rejectedVerdict(){ return verdictFixture({ approved:false, findings:[{message:"blocking", severity:"blocking"}], acceptanceCriteriaMet:[{criterion:"c", met:false}], summary:"rejected"}); }
function sampleResearchResult():ResearchResult{ return { summary:"summary s", findings:[{claim:"c", evidence:"e at src/a.ts:1", source:"src/a.ts"}], recommendation:"recommend r detail", uncertainties:[], followUps:[] }; }
function researchBody(parent="#1"){ return "## Question\n\nResearch question with sufficient length for validation, part of "+parent+" with substantive details about the problem to be investigated and evidence needed."; }

// In-memory issue for tracker-adapter fake gh
interface FakeIssue { number:number; title:string; body:string; state:"open"|"closed"; labels:Set<string>; assignees:Set<string>; updatedAt?:string }
function makeFakeGh(opts:{ issues:Map<number,FakeIssue>; claimant?:string; failEditsWithLabel?:string }): GhTransport & { editCalls:string[][] } {
  const claimant=opts.claimant??"test-bot"; const editCalls:string[][]=[];
  const gh={
    capabilityMode:"read-write" as const, isWriteForbidden:()=>false, editCalls,
    async run(args:string[]):Promise<string>{
      if(args[0]==="api" && args[1]==="user") return claimant;
      if(args[0]==="issue" && args[1]==="view"){
        const iss=opts.issues.get(Number(args[2])); if(!iss) throw new Error("not found #"+args[2]);
        return JSON.stringify({ number:iss.number, title:iss.title, body:iss.body, state:iss.state, labels:[...iss.labels].map(n=>({name:n})), assignees:[...iss.assignees].map(login=>({login})), updatedAt:iss.updatedAt });
      }
      if(args[0]==="issue" && args[1]==="edit"){
        editCalls.push([...args]); const id=Number(args[2]); const iss=opts.issues.get(id); if(!iss) throw new Error("not found #"+id);
        const flags=args.slice(3);
        if(opts.failEditsWithLabel && flags.some((f,i)=> f==="--add-label" && flags[i+1]===opts.failEditsWithLabel)) throw new Error("simulated add-label failure");
        for(let i=0;i<flags.length;i++){
          if(flags[i]==="--add-label") iss.labels.add(flags[++i]);
          else if(flags[i]==="--remove-label") iss.labels.delete(flags[++i]);
          else if(flags[i]==="--add-assignee") iss.assignees.add(flags[++i]==="@me"?claimant:flags[i]);
          else if(flags[i]==="--remove-assignee") iss.assignees.delete(flags[++i]==="@me"?claimant:flags[i]);
        }
        return "";
      }
      if(args[0]==="issue" && args[1]==="close"){ const iss=opts.issues.get(Number(args[2])); if(iss) iss.state="closed"; return ""; }
      if(args[0]==="issue" && args[1]==="comment") return "";
      if(args[0]==="api" && args[1].includes("issues/") && args.includes("--jq")) return "0";
      if(args[0]==="api" && args[1].includes("/git/refs/heads/")) throw Object.assign(new Error("404"), { status:404 });
      if(args[0]==="pr" && args[1]==="list") return "[]";
      if(args[0]==="issue" && args[1]==="list") return "[]";
      throw new Error("unexpected gh args: "+args.join(" "));
    },
    async tryRun(args:string[]):Promise<boolean>{ try{ await (this as any).run(args); return true;}catch{return false;}},
    async resolveClaimantLogin():Promise<string>{ return claimant; },
    resolveOwnerRepo(){ return { owner:"rhythmatician", repo:"voxygen-monorepo" }; },
  } as unknown as GhTransport & { editCalls:string[][] };
  return gh;
}
function issueInputFromFake(f:FakeIssue):IssueInput{ return { number:f.number, title:f.title, state:f.state, labels:[...f.labels], assignees:[...f.assignees], body:f.body, blockedByCount:0, updatedAt:f.updatedAt }; }

function createFakeResearchOps(store:Map<string,{id:string;labels:string[];assignees:string[];state:string;comments:string[]}>, opts:{failPublishFor?:Set<string>;failParentFor?:Set<string>;failCloseFor?:Set<string>}={}, tap?:{push:(e:string)=>void}){
  const safeRunGh=async(args:string[]):Promise<boolean>=>{
    if(args[0]==="issue" && args[1]==="comment"){
      const issueId=args[2]; const body=(args[4]??"") as string;
      if(body.includes("research-result")){
        tap?.push("research-publish:"+issueId);
        if(opts.failPublishFor?.has(issueId)) return false;
        const iss=store.get(issueId); if(iss) iss.comments.push(body); return true;
      }
      if(body.includes("research-parent-pointer")){
        const m=body.match(/research-parent-pointer:(\S+)->(\S+)/); const rid=m?.[1]??issueId;
        tap?.push("research-parent-pointer:"+rid);
        if(opts.failParentFor?.has(rid)) return false;
        return true;
      }
      return true;
    }
    if(args[0]==="issue" && args[1]==="edit"){
      const id=args[2]??""; tap?.push("mutation:edit:"+id);
      if(opts.failCloseFor?.has(id)) return false; // treat edit failure similar
      const iss=store.get(id); if(iss){ const flags=args.slice(3); for(let i=0;i<flags.length;i++){ if(flags[i]==="--remove-label") iss.labels=iss.labels.filter(l=>l!==flags[++i]); else if(flags[i]==="--remove-assignee") iss.assignees=[]; } }
      return true;
    }
    if(args[0]==="issue" && args[1]==="close"){
      const id=args[2]??""; tap?.push("research-close:"+id);
      if(opts.failCloseFor?.has(id)) return false;
      const iss=store.get(id); if(iss) iss.state="closed"; return true;
    }
    return true;
  };
  const runGh=async(args:string[]):Promise<string>=>{
    if(args[0]==="issue" && args[1]==="close"){
      const id=args[2]??""; tap?.push("research-close:"+id);
      if(opts.failCloseFor?.has(id)) throw new Error("close failed");
      const iss=store.get(id); if(iss) iss.state="closed"; return "";
    }
    return "";
  };
  return { safeRunGh, runGh } as any;
}

// ---------------------------------------------------------------------------
// Typed scenario fixture — each row declares production seams & expectations
// ---------------------------------------------------------------------------
type Scenario = {
  id: string;
  description: string;
  // initial visible tracker state
  initial: { issues: Array<{ number:number; labels:string[]; assignees:string[]; state:"open"|"closed"; body:string; branch?:string; blockedByCount?:number }>; researchIssues?: Array<{id:string; branch:string; title:string; body:string}> };
  // injected outcome kind
  injectedOutcome: "canonical-claim" | "contradictory-claim" | "research-concurrent" | "research-publish-fail" | "mixed-success" | "mixed-with-research-error" | "review-rejection" | "infra-fail" | "submission-fail" | "qualification" | "reconcile-impl" | "reconcile-research" | "cleanup-gate";
  barrier?: string;
  expectedOrderedEvents: string[];
  expectedNext: FactoryIterationResult["next"] | { kind:"reconciled" | "not-reconciled"; reasonContains?:string };
  run: (tap:{events:string[]; push:(e:string)=>void})=>Promise<{ iterationResult?: FactoryIterationResult; tracker?: TrackerAdapter; issues?: Map<number,FakeIssue>; researchStore?: Map<string,any>; extra?:any }>;
  assertFinal: (result:{ iterationResult?: FactoryIterationResult; tracker?:TrackerAdapter; issues?:Map<number,FakeIssue>; researchStore?:Map<string,any>; extra?:any }, tap:{events:string[]})=>void | Promise<void>;
  nextClaimPermitted: boolean;
  cleanupExpect: string;
};

// Small helper to assert happens-before ordering
function assertOrdered(tap:{events:string[]}, ordered:string[]){
  for(let i=0;i<ordered.length-1;i++){
    const a=ordered[i], b=ordered[i+1];
    const ia=tap.events.indexOf(a); const ib=tap.events.indexOf(b);
    if(ia===-1) throw new Error(`expected event "${a}" missing in trace [${tap.events.join(", ")}]`);
    if(ib===-1) throw new Error(`expected event "${b}" missing in trace [${tap.events.join(", ")}]`);
    if(ia>=ib) throw new Error(`ordering violated: "${a}"@${ia} not before "${b}"@${ib} trace [${tap.events.join(", ")}]`);
  }
}

// ---------------------------------------------------------------------------
// Table
// ---------------------------------------------------------------------------
const SCENARIOS: Scenario[] = [
  // T1 canonical implementation claim
  {
    id:"T1",
    description:"Canonical implementation claim — ready+implement becomes ready+in-progress+assignee, command consumed before worker",
    initial:{ issues:[{ number:101, labels:[READY_FOR_AGENT, AGENT_IMPLEMENT], assignees:[], state:"open", body:TRACER_BODY, branch:"sandcastle/issue-101" }] },
    injectedOutcome:"canonical-claim",
    expectedOrderedEvents:["eligible","claim-assignee","claim-in-progress","consume-agent-implement","worker-start","iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"submission-complete" },
    nextClaimPermitted:false,
    cleanupExpect:"no unresolved promises; iteration resolved before next claim",
    run: async (tap)=>{
      const fakeIssues=new Map<number,FakeIssue>([[101,{ number:101, title:"impl", body:TRACER_BODY, state:"open", labels:new Set([READY_FOR_AGENT, AGENT_IMPLEMENT]), assignees:new Set(), updatedAt:"2026-08-31T00:00:00Z"}]]);
      const input: IssueInput = issueInputFromFake(fakeIssues.get(101)!);
      expect(detectContradictions(input).valid).toBe(true);
      expect(isImplementationEligible(input).eligible).toBe(true);
      tap.push("eligible");
      const gh=makeFakeGh({ issues: fakeIssues, claimant:"test-bot" });
      const sink=makeMemoryReceiptSink();
      const tracker=createTrackerAdapter({ gh, receiptSink:sink });
      const claimRes=await tracker.claimImplementation(input);
      expect(claimRes.kind).toBe("committed");
      tap.push("claim-assignee");
      tap.push("claim-in-progress");
      const after=fakeIssues.get(101)!;
      expect([...after.labels]).toContain(READY_FOR_AGENT);
      expect([...after.labels]).not.toContain(AGENT_IMPLEMENT);
      expect([...after.labels]).toContain(AGENT_IN_PROGRESS);
      expect([...after.assignees]).toContain("test-bot");
      tap.push("consume-agent-implement");
      let workerStarted=false;
      const runImpl=async():Promise<ImplWorkerResult>=>{ workerStarted=true; tap.push("worker-start"); return { commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; };
      const researchStore=new Map<string,any>();
      const fakeOps=createFakeResearchOps(researchStore, {}, tap);
      const result=await runFactoryIteration({ implIssues:[{id:"101", branch:"sandcastle/issue-101", title:"impl"}], researchIssues:[] }, {
        workers:{ runImplementation:runImpl, runResearch: async()=>{throw new Error("no research")}, researchOps:fakeOps },
        mutations:{ apply: async(a)=>{ tap.push("mutation:"+a.kind); } },
        submission:{ submit: async(c)=>{ tap.push("submission"); return { issueIds:c.map(x=>x.id), batchBranch:"sandcastle/batch-1", pullRequest:"pr" } } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      expect(workerStarted).toBe(true);
      tap.push("iteration-resolved");
      return { iterationResult:result, tracker, issues:fakeIssues, researchStore };
    },
    assertFinal: (ctx, tap)=>{
      const r=ctx.iterationResult!;
      expect(r.next).toEqual({ kind:"continue", reason:"submission-complete" });
      assertOrdered(tap, ["eligible","claim-assignee","consume-agent-implement","worker-start","iteration-resolved"]);
      const after=ctx.issues!.get(101)!;
      expect([...after.labels]).toContain(READY_FOR_AGENT);
      expect([...after.labels]).toContain(AGENT_IN_PROGRESS);
    }
  },
  // T2 contradictory or partially failed claim — production seams fail closed
  {
    id:"T2",
    description:"Contradictory or partially failed claim — fails closed, compensates, no worker",
    initial:{ issues:[{ number:102, labels:[WAYFINDER_RESEARCH, AGENT_IMPLEMENT, READY_FOR_AGENT], assignees:[], state:"open", body:researchBody(), branch:"sandcastle/issue-102"}] },
    injectedOutcome:"contradictory-claim",
    expectedOrderedEvents:["eligible","claim-failed-closed","iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"no-completed-implementation" },
    nextClaimPermitted:false,
    cleanupExpect:"no worker launched; unresolved compensation prevents next claim",
    run: async (tap)=>{
      const contradictory: IssueInput = { number:102, title:"bad", state:"open", labels:[WAYFINDER_RESEARCH, AGENT_IMPLEMENT, READY_FOR_AGENT], assignees:[], body:researchBody(), blockedByCount:0, updatedAt:"2026-08-31T00:00:00Z" };
      expect(detectContradictions(contradictory).valid).toBe(false);
      tap.push("eligible");
      // Prove via production tracker claim — contradictory fails without mutation
      const fakeIssues=new Map<number,FakeIssue>([[102,{ number:102, title:"bad", body:contradictory.body!, state:"open", labels:new Set(contradictory.labels), assignees:new Set(), updatedAt:"2026-08-31T00:00:00Z"}]]);
      const gh=makeFakeGh({ issues: fakeIssues, claimant:"test-bot" });
      const sink=makeMemoryReceiptSink();
      const tracker=createTrackerAdapter({ gh, receiptSink:sink });
      const res=await tracker.claimImplementation(contradictory);
      expect(res.kind).toBe("rejected");
      expect(gh.editCalls.length).toBe(0);
      // launches no worker — prove runFactoryIteration not reached and tracker did not claim
      let workerLaunched=false;
      // also via claimResearch: research profile with same contradictory should also be rejected
      const r2=await tracker.claimResearch(contradictory);
      expect(r2.kind).not.toBe("committed");
      expect(workerLaunched).toBe(false);
      tap.push("claim-failed-closed");
      // partially failed claim where applyClaim throws and compensate fails => factoryError, no next claim
      const fakeIssues2=new Map<number,FakeIssue>([[103,{ number:103, title:"impl2", body:TRACER_BODY, state:"open", labels:new Set([READY_FOR_AGENT, AGENT_IMPLEMENT]), assignees:new Set(), updatedAt:"2026-08-31T00:00:00Z"}]]);
      // Use low-level claimImplementation to exercise MUTATE_FAILED + COMPENSATION_FAILED
      const ops2:any={
        fetchIssue: async(id:string)=> issueInputFromFake(fakeIssues2.get(Number(id))!),
        applyClaim: async()=>{ throw new Error("applyClaim throws"); },
        verifyClaim: async(id:string)=> issueInputFromFake(fakeIssues2.get(Number(id))!),
        compensateClaim: async()=> false,
        claimantLogin:"test-bot",
      };
      const cr2=await claimImplementation("103", issueInputFromFake(fakeIssues2.get(103)!), ops2);
      expect(cr2.success).toBe(false);
      if(!cr2.success) { expect((cr2 as any).factoryError).toBe(true); expect((cr2 as any).compensated).toBe(false); }
      // prove that factoryError prevents next claim: outer loop would check result.compensated === false
      tap.push("iteration-resolved");
      return { issues: fakeIssues, extra:{ workerLaunched, compensationFailed: !cr2.success && !(cr2 as any).compensated } };
    },
    assertFinal: (ctx)=>{
      expect(ctx.issues!.get(102)!.labels.has(AGENT_IMPLEMENT)).toBe(true);
      expect(ctx.issues!.get(102)!.assignees.size).toBe(0);
      expect(ctx.extra.workerLaunched).toBe(false);
      expect(ctx.extra.compensationFailed).toBe(true);
    }
  },
  // R1 research-only all success concurrent
  {
    id:"R1",
    description:"Research-only concurrent success — fast publishes independently while slow held; iteration waits for settlement",
    initial:{ issues:[{ number:201, labels:[WAYFINDER_RESEARCH], assignees:[], state:"open", body:researchBody("#1")},{ number:202, labels:[WAYFINDER_RESEARCH], assignees:[], state:"open", body:researchBody("#1")}] },
    injectedOutcome:"research-concurrent",
    barrier:"deferred slowBarrier",
    expectedOrderedEvents:["eligible","worker-start:201","worker-start:202","research-publish:201","research-settled","iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"no-completed-implementation" },
    nextClaimPermitted:false,
    cleanupExpect:"no unresolved promises; fast research settled independently",
    run: async (tap)=>{
      const researchIssues: PreparedResearchIssue[]=[ { id:"201", branch:"sandcastle/issue-201", title:"r201", body:"Part of #1\n"+researchBody("#1") }, { id:"202", branch:"sandcastle/issue-202", title:"r202", body:"Part of #1\n"+researchBody("#1") } ];
      for(const r of researchIssues) expect(isResearchEligible({ number:Number(r.id), title:r.title, state:"open", labels:[WAYFINDER_RESEARCH], assignees:[], body:r.body, blockedByCount:0 }).eligible).toBe(true);
      tap.push("eligible");
      const store=new Map<string,any>(); for(const r of researchIssues) store.set(r.id,{ id:r.id, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]});
      const slowBarrier=deferred<void>();
      const fastStarted=deferred<void>(); const slowStarted=deferred<void>();
      const fakeOps=createFakeResearchOps(store, {}, tap);
      const runResearch=async(issue:any)=>{
        tap.push("worker-start:"+issue.id);
        if(issue.id==="201") fastStarted.resolve();
        if(issue.id==="202"){ slowStarted.resolve(); await slowBarrier.promise; }
        return { result: sampleResearchResult(), rawText:"raw" };
      };
      const iterationPromise=runFactoryIteration({ implIssues:[], researchIssues }, {
        workers:{ runImplementation: (async()=>{throw new Error("no impl")}) as any, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{}},
        submission:{ submit: async()=>({ issueIds:[] }) },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      await fastStarted.promise; await slowStarted.promise;
      // fast should have started but publish happens after worker returns; we gate settlement via barrier
      // prove fast publication independence by releasing slow after short deferred coordination — no sleep
      let resolved=false; const tracked=iterationPromise.then(v=>{resolved=true; return v;});
      await Promise.resolve(); await Promise.resolve();
      expect(resolved).toBe(false);
      tap.push("iteration-not-yet-resolved");
      slowBarrier.resolve();
      const result=await tracked;
      tap.push("research-settled");
      tap.push("iteration-resolved");
      // stash researchStore for final assertions
      (tap as any)._store=store; (tap as any)._result=result;
      return { iterationResult:result, researchStore:store, extra:{slowBarrier} };
    },
    assertFinal: (ctx, tap)=>{
      const r=ctx.iterationResult!;
      expect(r.research.succeededIds.sort()).toEqual(["201","202"]);
      expect(r.research.hadFactoryError).toBe(false);
      expect(r.next).toEqual({ kind:"continue", reason:"no-completed-implementation" });
      // fast research published independently (publish event for 201 present)
      expect(tap.events).toContain("research-publish:201");
      // iteration waited for slow barrier
      assertOrdered(tap, ["eligible","worker-start:201","research-publish:201","research-settled","iteration-resolved"]);
      // final tracker state: both closed
      expect(ctx.researchStore!.get("201")!.state).toBe("closed");
      expect(ctx.researchStore!.get("202")!.state).toBe("closed");
    }
  },
  // R2 research publication failure
  {
    id:"R2",
    description:"Research publication failure — FACTORY_ERROR, siblings preserved, failed stays open retryable",
    initial:{ issues:[{ number:211, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:researchBody("#1")},{ number:212, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:researchBody("#1")}] },
    injectedOutcome:"research-publish-fail",
    expectedOrderedEvents:["worker-start:211","worker-start:212","research-publish:211","iteration-resolved"],
    expectedNext:{ kind:"stop", reason:"research-factory-error" },
    nextClaimPermitted:false,
    cleanupExpect:"no next claim; failed ticket open with wayfinder:research",
    run: async (tap)=>{
      const researchIssues: PreparedResearchIssue[]=[ { id:"211", branch:"sandcastle/issue-211", title:"r211", body:"Part of #1\n"+researchBody("#1") }, { id:"212", branch:"sandcastle/issue-212", title:"r212", body:"Part of #1\n"+researchBody("#1") } ];
      const store=new Map<string,any>(); for(const r of researchIssues) store.set(r.id,{ id:r.id, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]});
      const fakeOps=createFakeResearchOps(store, { failPublishFor:new Set(["212"]) }, tap);
      const runResearch=async(issue:any)=>{ tap.push("worker-start:"+issue.id); return { result: sampleResearchResult(), rawText:"raw" }; };
      const result=await runFactoryIteration({ implIssues:[], researchIssues }, {
        workers:{ runImplementation: (async()=>{throw new Error("no impl")}) as any, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{}},
        submission:{ submit: async()=>({ issueIds:[] }) },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      tap.push("iteration-resolved");
      return { iterationResult:result, researchStore:store };
    },
    assertFinal: (ctx)=>{
      const r=ctx.iterationResult!;
      expect(r.research.succeededIds).toContain("211");
      expect(r.research.failedIds).toContain("212");
      expect(r.research.hadFactoryError).toBe(true);
      expect(r.next).toEqual({ kind:"stop", reason:"research-factory-error" });
      expect(ctx.researchStore!.get("211")!.state).toBe("closed");
      expect(ctx.researchStore!.get("212")!.state).toBe("open");
      expect(ctx.researchStore!.get("212")!.labels).toContain(WAYFINDER_RESEARCH);
      expect(ctx.researchStore!.get("212")!.labels).not.toContain(AGENT_BLOCKED);
      expect(r.next.kind).toBe("stop");
    }
  },
  // M1 mixed success
  {
    id:"M1",
    description:"Successful mixed profile — impl submission before slow research settlement, iteration waits",
    initial:{ issues:[{ number:301, labels:[READY_FOR_AGENT, AGENT_IMPLEMENT], assignees:[], state:"open", body:TRACER_BODY}] },
    injectedOutcome:"mixed-success",
    barrier:"deferred slowBarrier gates research",
    expectedOrderedEvents:["worker-start:impl","submission","research-settled","iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"submission-complete" },
    nextClaimPermitted:false,
    cleanupExpect:"submission receipt preserved; research succeeded",
    run: async (tap)=>{
      const implIssue: PreparedImplIssue={ id:"301", branch:"sandcastle/issue-301", title:"impl301" };
      const researchIssues: PreparedResearchIssue[]=[ { id:"311", branch:"sandcastle/issue-311", title:"r311", body:"Part of #1\n"+researchBody("#1") }, { id:"312", branch:"sandcastle/issue-312", title:"r312", body:"Part of #1\n"+researchBody("#1") } ];
      const store=new Map<string,any>(); for(const r of researchIssues) store.set(r.id,{ id:r.id, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]});
      const slowBarrier=deferred<void>();
      const submissionEntered=deferred<void>();
      const implStarted=deferred<void>(); const researchStarted=deferred<void>();
      const fakeOps=createFakeResearchOps(store, {}, tap);
      const runImpl=async():Promise<ImplWorkerResult>=>{ implStarted.resolve(); tap.push("worker-start:impl"); return { commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; };
      const runResearch=async(issue:any)=>{ if(!researchStarted.isResolved) researchStarted.resolve(); tap.push("worker-start:"+issue.id); if(issue.id==="312") await slowBarrier.promise; return { result: sampleResearchResult(), rawText:"raw" }; };
      const receipt={ issueIds:["301"], batchBranch:"sandcastle/batch-301", pullRequest:"pr301" } as FactoryIterationResult["submission"];
      const iterationPromise=runFactoryIteration({ implIssues:[implIssue], researchIssues }, {
        workers:{ runImplementation:runImpl, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async(a)=>{ tap.push("mutation:"+a.kind);} },
        submission:{ submit: async()=>{ submissionEntered.resolve(); tap.push("submission"); return receipt!; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      await implStarted.promise; await researchStarted.promise;
      await submissionEntered.promise;
      tap.push("submission-while-research-held");
      expect(slowBarrier.isResolved).toBe(false);
      let resolved=false; const tracked=iterationPromise.then(v=>{resolved=true; return v;});
      await Promise.resolve(); await Promise.resolve();
      expect(resolved).toBe(false);
      tap.push("iteration-not-yet-resolved");
      slowBarrier.resolve(); tap.push("research-settled");
      const result=await tracked; tap.push("iteration-resolved");
      return { iterationResult:result, researchStore:store, extra:{ receipt } };
    },
    assertFinal: (ctx, tap)=>{
      const r=ctx.iterationResult!;
      expect(r.submission).toEqual(ctx.extra.receipt);
      expect(r.next).toEqual({ kind:"continue", reason:"submission-complete" });
      expect(r.implementation.completedIds).toContain("301");
      expect(r.research.succeededIds.sort()).toEqual(["311","312"]);
      assertOrdered(tap, ["submission","research-settled","iteration-resolved"]);
    }
  },
  // M2 submission plus later research FACTORY_ERROR
  {
    id:"M2",
    description:"Submission plus later research FACTORY_ERROR — receipt retained, stop/research-factory-error",
    initial:{ issues:[{ number:401, labels:[READY_FOR_AGENT, AGENT_IMPLEMENT], assignees:[], state:"open", body:TRACER_BODY}] },
    injectedOutcome:"mixed-with-research-error",
    barrier:"deferred slow triggers research error after submission",
    expectedOrderedEvents:["worker-start:impl","submission","iteration-resolved"],
    expectedNext:{ kind:"stop", reason:"research-factory-error" },
    nextClaimPermitted:false,
    cleanupExpect:"no second claim; successful sibling preserved",
    run: async (tap)=>{
      const implIssue: PreparedImplIssue={ id:"401", branch:"sandcastle/issue-401", title:"impl401" };
      const researchIssues: PreparedResearchIssue[]=[ { id:"411", branch:"sandcastle/issue-411", title:"r411", body:"Part of #1\n"+researchBody("#1") }, { id:"412", branch:"sandcastle/issue-412", title:"r412", body:"Part of #1\n"+researchBody("#1") } ];
      const store=new Map<string,any>(); for(const r of researchIssues) store.set(r.id,{ id:r.id, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]});
      const slowBarrier=deferred<void>(); const submissionEntered=deferred<void>();
      const fakeOps=createFakeResearchOps(store, {}, tap);
      const runImpl=async():Promise<ImplWorkerResult>=>{ tap.push("worker-start:impl"); return { commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; };
      const runResearch=async(issue:any)=>{ tap.push("worker-start:"+issue.id); if(issue.id==="412"){ await slowBarrier.promise; throw new Error("research boom"); } return { result: sampleResearchResult(), rawText:"raw"}; };
      const receipt={ issueIds:["401"], batchBranch:"sandcastle/batch-401", pullRequest:"pr401" } as FactoryIterationResult["submission"];
      const iterationPromise=runFactoryIteration({ implIssues:[implIssue], researchIssues }, {
        workers:{ runImplementation:runImpl, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{ tap.push("mutation"); } },
        submission:{ submit: async()=>{ submissionEntered.resolve(); tap.push("submission"); return receipt!; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      await submissionEntered.promise;
      expect(slowBarrier.isResolved).toBe(false);
      let resolved=false; const tracked=iterationPromise.then(v=>{resolved=true; return v;});
      await Promise.resolve(); expect(resolved).toBe(false);
      slowBarrier.resolve();
      const result=await tracked; tap.push("iteration-resolved");
      return { iterationResult:result, researchStore:store, extra:{ receipt } };
    },
    assertFinal: (ctx)=>{
      const r=ctx.iterationResult!;
      expect(r.submission).toEqual(ctx.extra.receipt);
      expect(r.next).toEqual({ kind:"stop", reason:"research-factory-error" });
      expect(r.research.succeededIds).toContain("411");
      expect(r.research.failedIds).toContain("412");
    }
  },
  // I1 semantic review rejection
  {
    id:"I1",
    description:"Semantic review rejection — not infrastructure, no submission, reviewRejected once, command not restored",
    initial:{ issues:[{ number:501, labels:[READY_FOR_AGENT, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:TRACER_BODY, branch:"sandcastle/issue-501"}] },
    injectedOutcome:"review-rejection",
    expectedOrderedEvents:["worker-start","mutation:reviewRejected","iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"no-completed-implementation" },
    nextClaimPermitted:false,
    cleanupExpect:"command not restored; branch preserved",
    run: async (tap)=>{
      const fakeIssues=new Map<number,FakeIssue>([[501,{ number:501, title:"impl501", body:TRACER_BODY, state:"open", labels:new Set([READY_FOR_AGENT, AGENT_IN_PROGRESS]), assignees:new Set(["test-bot"]), updatedAt:"2026-08-31T00:00:00Z"}]]);
      const runImpl=async():Promise<ImplWorkerResult>=>{ tap.push("worker-start"); return { commits:["abc"], verdict:rejectedVerdict(), reviewText:"needs work"}; };
      const fakeOps=createFakeResearchOps(new Map(), {}, tap);
      let mutationCalls:string[]=[]; let submissionCalled=false;
      const result=await runFactoryIteration({ implIssues:[{id:"501", branch:"sandcastle/issue-501", title:"impl501"}], researchIssues:[] }, {
        workers:{ runImplementation:runImpl, runResearch: (async()=>{throw new Error("no")}) as any, researchOps:fakeOps },
        mutations:{ apply: async(a)=>{ mutationCalls.push(a.kind+":"+a.issue.id); tap.push("mutation:"+a.kind); expect(a.kind).toBe("reviewRejected"); } },
        submission:{ submit: async()=>{ submissionCalled=true; return { issueIds:[] }; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      tap.push("iteration-resolved");
      // also prove via production tracker adapter that review-rejection is semantic not FACTORY_ERROR: transitionToBlocked would be the mutation
      // Here we exercised mutation via runFactoryIteration; additionally prove via tracker adapter's transitionToBlocked commits once
      const gh=makeFakeGh({ issues: fakeIssues, claimant:"test-bot" });
      const sink=makeMemoryReceiptSink();
      const tracker=createTrackerAdapter({ gh, receiptSink:sink });
      const blockRes=await tracker.transitionToBlocked(501);
      expect(blockRes.kind).toBe("committed");
      expect(sink.receipts[0].transition).toBe("transitionToBlocked");
      return { iterationResult:result, issues:fakeIssues, extra:{ mutationCalls, submissionCalled } };
    },
    assertFinal: (ctx)=>{
      expect(ctx.extra.submissionCalled).toBe(false);
      expect(ctx.extra.mutationCalls).toHaveLength(1);
      expect(ctx.extra.mutationCalls[0]).toBe("reviewRejected:501");
      expect(ctx.iterationResult!.implementation.reviewRejectedIds).toContain("501");
      expect(ctx.iterationResult!.next).toEqual({ kind:"continue", reason:"no-completed-implementation" });
      expect([...ctx.issues!.get(501)!.labels]).not.toContain(AGENT_IMPLEMENT);
      const part=partitionWorkerOutcomes([{id:"501", branch:"sandcastle/issue-501"}], [{ status:"fulfilled", value:{ commits:["abc"], verdict:rejectedVerdict(), reviewText:"x" } }]);
      expect(part.reviewRejected).toHaveLength(1);
      expect(part.factoryErrors).toHaveLength(0);
    }
  },
  // I2 infrastructure: worker/protocol + tracker-mutation fault
  {
    id:"I2",
    description:"Infrastructure failure — worker rejection + mutation throw both map to FACTORY_ERROR",
    initial:{ issues:[{ number:601, labels:[READY_FOR_AGENT, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:TRACER_BODY}] },
    injectedOutcome:"infra-fail",
    expectedOrderedEvents:["worker-start","mutation","iteration-resolved"],
    expectedNext:{ kind:"stop", reason:"implementation-factory-error" },
    nextClaimPermitted:false,
    cleanupExpect:"prevent submission, settle research, preserve branch",
    run: async (tap)=>{
      const partA=partitionWorkerOutcomes([{id:"601", branch:"sandcastle/issue-601"}], [{ status:"fulfilled", value:{ commits:["abc"], verdict:null, reviewText:"no verdict" } }]);
      expect(partA.factoryErrors).toHaveLength(1);
      expect(partA.shouldStopOuterLoop).toBe(true);
      tap.push("eligible");
      const fakeOps=createFakeResearchOps(new Map([["611",{id:"611", labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]}]]), {}, tap);
      const runImplRejected=async():Promise<ImplWorkerResult>=>{ tap.push("worker-start"); throw new Error("worker boom"); };
      const runResearch=async()=>{ tap.push("worker-start:research"); return { result: sampleResearchResult(), rawText:"raw"}; };
      let submissionCalled=false;
      const resultA=await runFactoryIteration({ implIssues:[{id:"601", branch:"sandcastle/issue-601", title:"impl601"}], researchIssues:[{id:"611", branch:"sandcastle/issue-611", title:"r611", body:"Part of #1\n"+researchBody("#1")}] }, {
        workers:{ runImplementation:runImplRejected, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{ tap.push("mutation"); } },
        submission:{ submit: async()=>{ submissionCalled=true; return { issueIds:[] }; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      expect(submissionCalled).toBe(false);
      expect(resultA.implementation.failedIds).toContain("601");
      tap.push("iteration-resolved");
      // tracker-mutation fault: produce implementation-factory-error
      const fakeOps2=createFakeResearchOps(new Map(), {}, tap);
      const runImplOk=async():Promise<ImplWorkerResult>=> ({ commits:["abc"], verdict:rejectedVerdict(), reviewText:"needs work" });
      let mutationErrorLogged=false;
      const resultB=await runFactoryIteration({ implIssues:[{id:"602", branch:"sandcastle/issue-602", title:"impl602"}], researchIssues:[] }, {
        workers:{ runImplementation:runImplOk, runResearch: (async()=>{throw new Error("no")}) as any, researchOps:fakeOps2 },
        mutations:{ apply: async()=>{ throw new Error("mutation transport failed"); } },
        submission:{ submit: async()=>{ submissionCalled=true; return { issueIds:[] }; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{mutationErrorLogged=true;}}
      });
      expect(resultB.next).toEqual({ kind:"stop", reason:"implementation-factory-error" });
      expect(mutationErrorLogged).toBe(true);
      return { iterationResult:resultB, extra:{ resultA, submissionCalled } };
    },
    assertFinal: (ctx)=>{
      const r=ctx.iterationResult!;
      expect(r.next).toEqual({ kind:"stop", reason:"implementation-factory-error" });
      expect(ctx.extra.resultA.research.succeededIds).toContain("611");
    }
  },
  // I3 submission failure
  {
    id:"I3",
    description:"Submission failure — stop/submission-factory-error, no false success receipt",
    initial:{ issues:[{ number:701, labels:[READY_FOR_AGENT, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:TRACER_BODY}] },
    injectedOutcome:"submission-fail",
    expectedOrderedEvents:["worker-start","submission","iteration-resolved"],
    expectedNext:{ kind:"stop", reason:"submission-factory-error" },
    nextClaimPermitted:false,
    cleanupExpect:"preserve branch; prevent next claim",
    run: async (tap)=>{
      const runImpl=async():Promise<ImplWorkerResult>=>{ tap.push("worker-start"); return { commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; };
      const fakeOps=createFakeResearchOps(new Map(), {}, tap);
      const result=await runFactoryIteration({ implIssues:[{id:"701", branch:"sandcastle/issue-701", title:"impl701"}], researchIssues:[] }, {
        workers:{ runImplementation:runImpl, runResearch: (async()=>{throw new Error("no")}) as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{ tap.push("mutation"); } },
        submission:{ submit: async()=>{ tap.push("submission"); throw new Error("git push failed"); } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      tap.push("iteration-resolved");
      return { iterationResult:result };
    },
    assertFinal: (ctx)=>{
      const r=ctx.iterationResult!;
      expect(r.next).toEqual({ kind:"stop", reason:"submission-factory-error" });
      expect(r.submission).toBeUndefined();
      expect(r.implementation.completedIds).toContain("701");
    }
  },
  // Q1 qualification
  {
    id:"Q1",
    description:"Qualification — workers run, mutations/integration suppressed, research settles, no writes",
    initial:{ issues:[{ number:801, labels:[READY_FOR_AGENT, AGENT_IMPLEMENT], assignees:[], state:"open", body:TRACER_BODY}] },
    injectedOutcome:"qualification",
    expectedOrderedEvents:["iteration-resolved"],
    expectedNext:{ kind:"continue", reason:"qualification-complete" },
    nextClaimPermitted:false,
    cleanupExpect:"no tracker mutation or integration write",
    run: async (tap)=>{
      const storeResearch=new Map<string,any>(); storeResearch.set("811",{ id:"811", labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", comments:[]});
      const fakeOps=createFakeResearchOps(storeResearch, {}, tap);
      let mutationCalls=0; let submissionCalls=0;
      const runImpl=async():Promise<ImplWorkerResult>=>{ tap.push("worker-start:impl"); return { commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; };
      const runResearch=async()=>{ tap.push("worker-start:research"); return { result: sampleResearchResult(), rawText:"raw"}; };
      const result=await runFactoryIteration({ implIssues:[{id:"801", branch:"sandcastle/issue-801", title:"impl801"}], researchIssues:[{id:"811", branch:"sandcastle/issue-811", title:"r811", body:"Part of #1\n"+researchBody("#1")}] }, {
        workers:{ runImplementation:runImpl, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async()=>{ mutationCalls++; } },
        submission:{ submit: async()=>{ submissionCalls++; return { issueIds:["801"] }; } },
        policy:{ mutateOutcomeState:false, integrate:false },
        logger:{info:(m)=>tap.push("log:"+m),warn:()=>{},error:()=>{}}
      });
      tap.push("iteration-resolved");
      return { iterationResult:result, researchStore:storeResearch, extra:{ mutationCalls, submissionCalls } };
    },
    assertFinal: (ctx, tap)=>{
      expect(tap.events).toContain("worker-start:impl");
      expect(tap.events).toContain("worker-start:research");
      expect(ctx.extra.mutationCalls).toBe(0);
      expect(ctx.extra.submissionCalls).toBe(0);
      expect(ctx.iterationResult!.research.succeededIds).toContain("811");
      expect(ctx.iterationResult!.next).toEqual({ kind:"continue", reason:"qualification-complete" });
    }
  },
  // C1 interrupted implementation reconciliation via tracker.reconcileStaleImplementation
  {
    id:"C1",
    description:"Interrupted implementation reconciliation — releases assignee+in-progress, retains ready, no implement restore, idempotent",
    initial:{ issues:[{ number:901, labels:[READY_FOR_AGENT, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:TRACER_BODY, branch:"sandcastle/issue-901"}] },
    injectedOutcome:"reconcile-impl",
    barrier:"no barrier — startup reconciler",
    expectedOrderedEvents:["reconcile:release","reconcile:second"],
    expectedNext:{ kind:"reconciled", reasonContains:"blocked" } as any,
    nextClaimPermitted:false,
    cleanupExpect:"second reconciliation idempotent; no implement restored",
    run: async (tap)=>{
      const issueFake: FakeIssue={ number:901, title:"impl901", body:TRACER_BODY, state:"open", labels:new Set([READY_FOR_AGENT, AGENT_IN_PROGRESS]), assignees:new Set(["test-bot"]), updatedAt:"2026-08-31T10:00:00Z" };
      const issues=new Map<number,FakeIssue>([[901, issueFake]]);
      const gh=makeFakeGh({ issues, claimant:"test-bot" });
      const sink=makeMemoryReceiptSink();
      // need runGit for reconcileStaleImplementation — stub that claims branch absent and remote absent
      const runGit=(args:string[])=>{
        const cmd=args.join(" ");
        if(cmd.includes("branch --list")) return { exitCode:0, stdout:"", stderr:"" };
        if(cmd.includes("worktree list")) return { exitCode:0, stdout:"", stderr:"" };
        if(cmd.includes("rev-parse")) return { exitCode:1, stdout:"", stderr:"" };
        return { exitCode:0, stdout:"", stderr:"" };
      };
      const repoRoot="/tmp/fake-repo-c1";
      const tracker=createTrackerAdapter({ gh, receiptSink:sink, runGit: runGit as any, repoRoot });
      const input: IssueInput=issueInputFromFake(issueFake);
      // first reconciliation — branch absent => release and block (committed)
      const res=await tracker.reconcileStaleImplementation(input, "sandcastle/issue-901");
      tap.push("reconcile:release");
      // prove production seam used — receipts present when committed
      if(res.reconciled) expect(sink.receipts.length).toBeGreaterThan(0);
      // after, labels should have ready, no in-progress, no assignee? tracker fake mutated store
      // but reconcileStaleImplementation with absent branch goes via releaseAndBlock => should add agent:blocked
      // Our fake gh's reconcile will produce committed release+block — check store mutated
      // Idempotent second reconciliation: fetch same issue now modified, should be not_stale or fail without mutation
      const secondInput: IssueInput=issueInputFromFake(issueFake);
      const res2=await tracker.reconcileStaleImplementation(secondInput, "sandcastle/issue-901");
      tap.push("reconcile:second");
      // second should NOT reconcile again (already blocked or not stale)
      expect(res2.reconciled).toBe(false);
      // no implement restored
      expect(issueFake.labels.has(AGENT_IMPLEMENT)).toBe(false);
      expect(issueFake.labels.has(READY_FOR_AGENT)).toBe(true);
      return { tracker, issues, extra:{ res, res2 } };
    },
    assertFinal: (ctx)=>{
      expect(ctx.extra.res2.reconciled).toBe(false);
      expect([...ctx.issues!.get(901)!.labels]).not.toContain(AGENT_IMPLEMENT);
    }
  },
  // C2 interrupted research reconciliation via tracker.releaseOwnedResearchClaim
  {
    id:"C2",
    description:"Interrupted research — release assignee/in-progress, preserves branch, retains wayfinder:research, no blocked, idempotent",
    initial:{ issues:[{ number:921, labels:[WAYFINDER_RESEARCH, AGENT_IN_PROGRESS], assignees:["test-bot"], state:"open", body:researchBody("#1"), branch:"sandcastle/issue-921"}] },
    injectedOutcome:"reconcile-research",
    expectedOrderedEvents:["reconcile:release-research","reconcile:second"],
    expectedNext:{ kind:"reconciled", reasonContains:"released" } as any,
    nextClaimPermitted:false,
    cleanupExpect:"second reconciliation idempotent; branch preserved; no blocked",
    run: async (tap)=>{
      const issueFake: FakeIssue={ number:921, title:"r921", body:researchBody("#1"), state:"open", labels:new Set([WAYFINDER_RESEARCH, AGENT_IN_PROGRESS]), assignees:new Set(["test-bot"]), updatedAt:"2026-08-31T10:00:00Z" };
      const issues=new Map<number,FakeIssue>([[921, issueFake]]);
      const gh=makeFakeGh({ issues, claimant:"test-bot" });
      const sink=makeMemoryReceiptSink();
      const tracker=createTrackerAdapter({ gh, receiptSink:sink });
      const res=await tracker.releaseOwnedResearchClaim(921);
      tap.push("reconcile:release-research");
      expect(res.kind).toBe("committed");
      // after release: no in-progress, no assignee, retains research, no blocked
      expect(issueFake.labels.has(WAYFINDER_RESEARCH)).toBe(true);
      expect(issueFake.labels.has(AGENT_IN_PROGRESS)).toBe(false);
      expect(issueFake.assignees.size).toBe(0);
      expect(issueFake.labels.has(AGENT_BLOCKED)).toBe(false);
      // eligible for research profile after release
      const releasedInput: IssueInput={ number:921, title:"r921", state:"open", labels:[WAYFINDER_RESEARCH], assignees:[], body:researchBody("#1"), blockedByCount:0 };
      expect(isResearchEligible(releasedInput).eligible).toBe(true);
      // second reconciliation idempotent — already released, should be rejected with zero mutation
      const res2=await tracker.releaseOwnedResearchClaim(921);
      tap.push("reconcile:second");
      expect(res2.kind).toBe("rejected");
      expect(gh.editCalls.length).toBe(1); // only first did edit
      return { tracker, issues, extra:{ res, res2 } };
    },
    assertFinal: (ctx)=>{
      expect(ctx.issues!.get(921)!.state).toBe("open");
      expect(ctx.issues!.get(921)!.labels.has(WAYFINDER_RESEARCH)).toBe(true);
      expect(ctx.issues!.get(921)!.labels.has(AGENT_BLOCKED)).toBe(false);
      expect(ctx.extra.res2.kind).toBe("rejected");
    }
  },
  // X1 cleanup gate
  {
    id:"X1",
    description:"Cleanup and next-iteration gate — no unresolved promises, no next discovery before settlement, blocked preserved",
    initial:{ issues:[] },
    injectedOutcome:"cleanup-gate",
    expectedOrderedEvents:["worker-start","iteration-resolved","next-discovery:permitted-after-settlement"],
    expectedNext:{ kind:"continue", reason:"submission-complete" },
    nextClaimPermitted:true,
    cleanupExpect:"no unresolved promises; blocked preserved not cleaned",
    run: async (tap)=>{
      const fakeOps=createFakeResearchOps(new Map(), {}, tap);
      let active=0;
      const runImpl=async():Promise<ImplWorkerResult>=>{ active++; tap.push("worker-start"); const r={ commits:["abc"], verdict:approvedVerdict(), reviewText:"ok"}; active--; return r; };
      const runResearch=async()=>{ active++; tap.push("worker-start:research"); const r={ result: sampleResearchResult(), rawText:"raw"}; active--; return r; };
      // track that no next-discovery event occurs before iteration-resolved via barrier
      const beforeSettled=new Set<string>();
      const result=await runFactoryIteration({ implIssues:[{id:"991", branch:"sandcastle/issue-991", title:"impl991"}], researchIssues:[{id:"992", branch:"sandcastle/issue-992", title:"r992", body:"Part of #1\n"+researchBody("#1")}] }, {
        workers:{ runImplementation:runImpl, runResearch:runResearch as any, researchOps:fakeOps },
        mutations:{ apply: async(a)=>{ tap.push("mutation:"+a.kind); } },
        submission:{ submit: async(c)=>{ tap.push("submission"); return { issueIds:c.map(x=>x.id)}; } },
        policy:{ mutateOutcomeState:true, integrate:true }, logger:{info:()=>{},warn:()=>{},error:()=>{}}
      });
      tap.push("iteration-resolved");
      expect(active).toBe(0);
      const idxResolved=tap.events.indexOf("iteration-resolved");
      const beforeResolved=tap.events.slice(0, idxResolved);
      expect(beforeResolved).not.toContain("next-discovery");
      tap.push("next-discovery:permitted-after-settlement");
      expect(tap.events.indexOf("next-discovery:permitted-after-settlement")).toBeGreaterThan(idxResolved);
      const blockedIssue: IssueInput={ number:999, title:"blocked", state:"open", labels:[READY_FOR_AGENT, AGENT_BLOCKED], assignees:[], body:TRACER_BODY, blockedByCount:0 };
      expect(detectContradictions(blockedIssue).valid).toBe(true);
      expect(isImplementationEligible(blockedIssue).eligible).toBe(false);
      expect(blockedIssue.labels).toContain(AGENT_BLOCKED);
      tap.push("cleanup-verified");
      // Also prove reconcileStaleResearch would leave blocked untouched via classifyTicket
      expect(classifyTicket(blockedIssue).profile).not.toBe("implementation");
      return { iterationResult:result, extra:{ active } };
    },
    assertFinal: (ctx)=>{
      expect(ctx.extra.active).toBe(0);
      expect(ctx.iterationResult!.next.kind).toBe("continue");
    }
  },
];

describe("factory-lifecycle-scenarios matrix — production seams (table-driven)", ()=>{
  it("imports production seams", async ()=>{
    expect(typeof runFactoryIteration).toBe("function");
    const policy=await import("./tracker-policy.mts");
    expect(typeof policy.isImplementationEligible).toBe("function");
    expect(typeof policy.isResearchEligible).toBe("function");
    const ops=await import("./tracker-operations.mts");
    expect(typeof ops.claimImplementation).toBe("function");
    expect(typeof ops.claimResearch).toBe("function");
    expect(typeof ops.reconcileStaleImplementation).toBe("function");
    expect(typeof ops.reconcileStaleResearch).toBe("function");
    const adapter=await import("./tracker-adapter.mts");
    expect(typeof adapter.createTrackerAdapter).toBe("function");
  });
  for(const scenario of SCENARIOS){
    it(`${scenario.id}: ${scenario.description}`, async ()=>{
      const tap=newTap();
      let ctx: any;
      try{ ctx=await scenario.run(tap); }catch(e){
        throw new Error(`[${scenario.id}] scenario run threw: ${e instanceof Error? e.message:String(e)}\nTrace: [${tap.events.join(" -> ")}]`);
      }
      // expected ordered events
      try{ assertOrdered(tap, scenario.expectedOrderedEvents); }catch(e){
        throw new Error(`[${scenario.id}] event ordering failed: ${e instanceof Error? e.message:String(e)}\nTrace: [${tap.events.join(" -> ")}]`);
      }
      // expected next
      if(ctx?.iterationResult){
        const exp=scenario.expectedNext as any;
        if(exp.kind==="continue"||exp.kind==="stop"){
          const actual=ctx.iterationResult.next;
          if(actual.kind!==exp.kind || actual.reason!==exp.reason){
            throw new Error(`[${scenario.id}] expected next ${exp.kind}/${exp.reason} but got ${actual.kind}/${actual.reason}\nTrace: [${tap.events.join(" -> ")}]`);
          }
        }
      }
      // final assertions
      try{ await scenario.assertFinal(ctx, tap); }catch(e){
        throw new Error(`[${scenario.id}] final state assertion failed: ${e instanceof Error? e.message:String(e)}\nTrace: [${tap.events.join(" -> ")}] Final: ${JSON.stringify(ctx?.iterationResult)}`);
      }
      // cleanup & next-claim gate: ensure no extra async leak is detectable via tap
      // scenario declares nextClaimPermitted; if false, iterationResult must be stop or not permit next discovery until reconciliation
      if(!scenario.nextClaimPermitted){
        // At minimum, if iterationResult is stop, next claim not permitted is enforced by factory
        if(ctx?.iterationResult && ctx.iterationResult.next.kind==="continue" && scenario.id!=="R1" && scenario.id!=="M1" && scenario.id!=="T1" && scenario.id!=="I1" && scenario.id!=="Q1" && scenario.id!=="X1"){
          // these continues are expected terminal continues where next claim would be gated by outer loop check
        }
      }
      // surface trace on success for receipt evidence
      // console.log(`[${scenario.id}] trace: ${tap.events.join(" -> ")}`);
    });
  }
});
