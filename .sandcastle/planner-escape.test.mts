import { describe, it, expect } from "vitest";
import { parsePlannerOutput } from "./planner-helpers.mts";

// This file is the reliability-contract red suite.
// It must FAIL before the fix and PASS after.
// Covers the double-escaped JSON class that blocked #151 on 2026-08-19.

describe("Reliability: planner tolerance — malformed but recoverable JSON", () => {
  const eligible = [
    { number: 151, title: "End L4 tracer: dimension-change-aware rebind", branch: "sandcastle/issue-151" },
    { number: 152, title: "End L4 tracer: finite completion telemetry", branch: "sandcastle/issue-152" },
  ];

  it("parses double-escaped JSON inside <plan> (the live #151 failure)", () => {
    // Live logs 1787099982551: rawMatched = {\"issues\": [{...}]} (escaped quotes)
    const doubleEscaped = `<plan>{\\\"issues\\\": [{\\\"id\\\": \\\"151\\\", \\\"title\\\": \\\"End L4 tracer: dimension-change-aware rebind\\\", \\\"branch\\\": \\\"sandcastle/issue-151\\\"}]}</plan>`;
    const selected = parsePlannerOutput(doubleEscaped, eligible);
    expect(selected).toHaveLength(1);
    expect(selected[0].id).toBe("151");
  });

  it("parses JSON string-wrapped inside <plan> (quoted string)", () => {
    const stringWrapped = `<plan>"{\\\"issues\\\": [{\\\"id\\\": \\\"151\\\", \\\"title\\\": \\\"a\\\", \\\"branch\\\": \\\"sandcastle/issue-151\\\"}]}"</plan>`;
    // Should either unwrap the string layer or be tolerant — at minimum not throw invalid JSON
    // If this case is not supported, it should throw with rawMatched, not silently return wrong
    try {
      const selected = parsePlannerOutput(stringWrapped, eligible);
      // If tolerant, it should return correct id
      expect(selected[0]?.id).toBe("151");
    } catch (e) {
      expect((e as Error).message).toMatch(/invalid JSON|failed schema/);
      expect((e as unknown as { rawMatched?: string }).rawMatched).toBeDefined();
    }
  });

  it("still rejects truly invalid JSON (trailing comma) — fail-closed not infinite tolerance", () => {
    const bad = `<plan>{"issues": [{"id": "151", "title": "a", "branch": "sandcastle/issue-151"},]}</plan>`;
    expect(() => parsePlannerOutput(bad, eligible)).toThrow(/invalid JSON/);
  });

  it("still drops hallucinated IDs after unescaping", () => {
    const withHalluc = `<plan>{\\\"issues\\\": [{\\\"id\\\": \\\"151\\\", \\\"title\\\": \\\"a\\\", \\\"branch\\\": \\\"sandcastle/issue-151\\\"}, {\\\"id\\\": \\\"999\\\", \\\"title\\\": \\\"hallucinated\\\", \\\"branch\\\": \\\"sandcastle/issue-999\\\"}]}</plan>`;
    const selected = parsePlannerOutput(withHalluc, eligible);
    expect(selected).toHaveLength(1);
    expect(selected[0].id).toBe("151");
  });

  it("handles fences + double-escape", () => {
    const fenced = `<plan>\`\`\`json\n{\\\"issues\\\": [{\\\"id\\\": \\\"151\\\", \\\"title\\\": \\\"a\\\", \\\"branch\\\": \\\"sandcastle/issue-151\\\"}]}\n\`\`\`</plan>`;
    const selected = parsePlannerOutput(fenced, eligible);
    expect(selected).toHaveLength(1);
    expect(selected[0].id).toBe("151");
  });
});
