import { describe, it, expect } from "vitest";
import { parsePlannerOutput, fallbackToSingle } from "./planner-helpers.mts";

// Validates the fallback invariant: planner failure must not block serial progress
// and must never fan out to all eligible.

describe("Reliability: planner fallback contract — fail-closed single not all", () => {
  it("fallback selects exactly one (lowest number) from overlapping set [151,152]", () => {
    const eligible = [
      { number: 152, title: "End L4 tracer: finite telemetry", branch: "sandcastle/issue-152" },
      { number: 151, title: "End L4 tracer: dimension rebind", branch: "sandcastle/issue-151" },
    ];
    const fallback = fallbackToSingle(eligible);
    expect(fallback).toHaveLength(1);
    expect(fallback[0].id).toBe("151"); // deterministic sort, not input order
  });

  it("fallback from malformed planner never yields all eligible", () => {
    const eligible = [
      { number: 151, title: "a", branch: "sandcastle/issue-151" },
      { number: 152, title: "b", branch: "sandcastle/issue-152" },
    ];
    const malformed = `<plan>{\\"issues\\": [{\\"id\\": \\"151\\"}]}</plan>`;
    let planned: unknown = null;
    try {
      planned = parsePlannerOutput(malformed, eligible);
    } catch {
      planned = fallbackToSingle(eligible);
    }
    expect(Array.isArray(planned)).toBe(true);
    expect((planned as unknown[]).length).toBe(1);
    expect((planned as Array<{id:string}>)[0].id).toBe("151");
  });

  it("single eligible bypasses planner entirely — invariant holds even if planner helper broken", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }];
    const planned = fallbackToSingle(eligible);
    expect(planned).toHaveLength(1);
    expect(planned[0].id).toBe("151");
  });

  it("empty eligible yields empty fallback (no work)", () => {
    expect(fallbackToSingle([])).toEqual([]);
  });

  it("fallback branch is deterministic sandcastle/issue-N", () => {
    const eligible = [{ number: 99, title: "test", branch: "sandcastle/issue-99" }];
    const fb = fallbackToSingle(eligible);
    expect(fb[0].branch).toBe("sandcastle/issue-99");
  });

  it("fallback branch defaults when input branch missing", () => {
    const eligible = [{ number: 42, title: "untitled" } as unknown as { number:number; title:string; branch:string }];
    const fb = fallbackToSingle(eligible as unknown as Array<{ number:number; title:string; branch:string }>);
    expect(fb[0].branch).toBe("sandcastle/issue-42");
  });
});
