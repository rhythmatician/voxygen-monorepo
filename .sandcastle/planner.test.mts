import { describe, it, expect } from "vitest";
import fs from "node:fs";
import path from "node:path";
import { parsePlannerOutput } from "./planner-helpers.mts";

describe("Planner parsing — eligible [151,152] → selected [151] via static fixture", () => {
  it("proves planner-151-success.json drives correct serialization (Wayfinder same-map + GenerationSession overlap)", async () => {
    const fixturePath = path.join(process.cwd(), ".sandcastle", "fixtures", "planner-151-success.json");
    expect(fs.existsSync(fixturePath)).toBe(true);
    const fixture = JSON.parse(fs.readFileSync(fixturePath, "utf8"));
    expect(fixture.eligible).toEqual([151, 152]);
    // rawPlan is the exact <plan> tag content the planner emitted
    const rawStdout = fixture.rawPlan as string;
    expect(rawStdout).toContain("<plan>");
    const eligible = [
      { number: 151, title: "End L4 tracer: dimension-change-aware rebind — teleport to the_end activates tracer without rejoin", branch: "sandcastle/issue-151" },
      { number: 152, title: "End L4 tracer: finite completion telemetry — 121/121 terminal outcomes + authoritative timestamp/elapsed", branch: "sandcastle/issue-152" },
    ];
    const selected = parsePlannerOutput(rawStdout, eligible);
    expect(selected).toHaveLength(1);
    expect(selected[0].id).toBe("151");
    expect(selected[0].branch).toBe("sandcastle/issue-151");
    // Also verify surrounding reasoning text does not break extraction (last <plan> wins)
    const noisyStdout = `Reasoning: both are wayfinder:task Part of #22 same map, overlap on GenerationSession.java\n${rawStdout}\nTrailing text`;
    const selectedNoisy = parsePlannerOutput(noisyStdout, eligible);
    expect(selectedNoisy).toEqual(selected);
  });

  it("drops hallucinated IDs not in eligible", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }];
    const stdout = `<plan>{"issues": [{"id": "151", "title": "a", "branch": "sandcastle/issue-151"}, {"id": "999", "title": "hallucinated", "branch": "sandcastle/issue-999"}]}</plan>`;
    const selected = parsePlannerOutput(stdout, eligible);
    expect(selected).toHaveLength(1);
    expect(selected[0].id).toBe("151");
  });

  it("throws on missing <plan> tag", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }];
    expect(() => parsePlannerOutput("no plan here", eligible)).toThrow(/not found/);
  });

  it("throws on invalid JSON inside <plan> and preserves rawMatched", () => {
    const eligible = [{ number: 151, title: "a", branch: "sandcastle/issue-151" }];
    const bad = `<plan>{"issues": [{"id": "151", "title": "a", "branch": "sandcastle/issue-151"},]}</plan>`; // trailing comma
    try {
      parsePlannerOutput(bad, eligible);
      expect.unreachable("should have thrown");
    } catch (e) {
      const err = e as unknown as { rawMatched?: string };
      expect((err as Error).message).toMatch(/invalid JSON/);
      expect(err.rawMatched).toBeDefined();
    }
  });
});
