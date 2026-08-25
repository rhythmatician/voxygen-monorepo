import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { withTemporaryIssueFixtures, withAtomicJsonReceipt } from "./resource-scopes.mts";

describe("withTemporaryIssueFixtures", () => {
  it("records fixtures at acquisition and cleans all in finally on success", async () => {
    const cleaned: number[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record(1); record(2); record(3);
        return [10, 20, 30];
      },
      {
        cleanup: async (id) => { cleaned.push(id); },
        verify: async () => null,
      },
    );
    expect(result.ok).toBe(true);
    expect(result.value).toEqual([10, 20, 30]);
    expect(result.fixtureIds).toEqual([1, 2, 3]);
    expect(cleaned).toEqual([1, 2, 3]);
    expect(result.cleanupFailures).toEqual([]);
  });

  it("PARTIAL ACQUISITION: first fixture created, second throws — first still cleaned and verified", async () => {
    const cleaned: number[] = [];
    const verified: number[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record(101);
        throw new Error("second fixture creation failed");
      },
      {
        cleanup: async (id) => { cleaned.push(id); },
        verify: async (id) => { verified.push(id); return null; },
      },
    );
    // The recorded fixture was NOT leaked despite the promise never resolving normally
    expect(result.fixtureIds).toEqual([101]);
    expect(cleaned).toEqual([101]);
    expect(verified).toEqual([101]);
    expect(result.primaryError).toContain("second fixture creation failed");
    expect(result.cleanupFailures).toEqual([]);
    expect(result.ok).toBe(false);
  });

  it("cleans up when the primary body fails, keeping failures separated", async () => {
    const cleaned: number[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record(7); record(8);
        throw new Error("primary exploded");
      },
      {
        cleanup: async (id) => { cleaned.push(id); },
        verify: async () => null,
      },
    );
    expect(cleaned).toEqual([7, 8]);
    expect(result.primaryError).toContain("primary exploded");
    expect(result.cleanupFailures).toEqual([]);
    expect(result.ok).toBe(false);
    expect(result.value).toBeUndefined();
  });

  it("one fixture's cleanup failure does not skip another's cleanup", async () => {
    const cleaned: number[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record(1); record(2); record(3); return "done"; },
      {
        cleanup: async (id) => {
          if (id === 2) throw new Error("fixture 2 unclean");
          cleaned.push(id);
        },
        verify: async () => null,
      },
    );
    // Fixtures 1 and 3 still cleaned despite fixture 2 failing
    expect(cleaned).toEqual([1, 3]);
    expect(result.cleanupFailures.length).toBe(1);
    expect(result.cleanupFailures[0]).toContain("#2");
    expect(result.primaryError).toBeUndefined();
    expect(result.ok).toBe(false);
  });

  it("postcondition verification failure counts as cleanup failure", async () => {
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record(5); return "ok"; },
      {
        cleanup: async () => {},
        verify: async () => "still assigned",
      },
    );
    expect(result.cleanupFailures.length).toBe(1);
    expect(result.cleanupFailures[0]).toContain("still assigned");
    expect(result.ok).toBe(false);
  });

  it("verification throwing means state is UNKNOWN — fail closed", async () => {
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record(9); return "ok"; },
      {
        cleanup: async () => {},
        verify: async () => { throw new Error("read-back failed"); },
      },
    );
    expect(result.cleanupFailures.length).toBe(1);
    expect(result.cleanupFailures[0]).toContain("verification failed");
    expect(result.ok).toBe(false);
  });

  it("cleanup failure skips postcondition verification for that fixture but verifies others", async () => {
    const verified: number[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record(1); record(2); return "ok"; },
      {
        cleanup: async (id) => { if (id === 1) throw new Error("nope"); },
        verify: async (id) => { verified.push(id); return null; },
      },
    );
    expect(verified).toEqual([2]);
    expect(result.cleanupFailures.length).toBe(1);
  });
});

describe("withAtomicJsonReceipt", () => {
  function tmpPath(): string {
    return path.join(fs.mkdtempSync(path.join(os.tmpdir(), "atomic-receipt-")), "receipt.json");
  }

  it("writes JSON to target atomically and returns data", () => {
    const p = tmpPath();
    const { path: written, data } = withAtomicJsonReceipt(p, () => ({ checkPassed: true, sha: "abc" }));
    expect(written).toBe(p);
    expect(data).toEqual({ checkPassed: true, sha: "abc" });
    const parsed = JSON.parse(fs.readFileSync(p, "utf8"));
    expect(parsed.checkPassed).toBe(true);
    // No temp files left behind
    const leftovers = fs.readdirSync(path.dirname(p)).filter((f) => f.includes(".tmp-"));
    expect(leftovers).toEqual([]);
  });

  it("creates parent directories recursively", () => {
    const base = fs.mkdtempSync(path.join(os.tmpdir(), "atomic-receipt-dir-"));
    const p = path.join(base, "logs", "nested", "r.json");
    withAtomicJsonReceipt(p, () => ({ ok: true }));
    expect(fs.existsSync(p)).toBe(true);
  });

  it("removes temp file and rethrows when serialization/write fails, leaving no torn receipt", () => {
    const p = tmpPath();
    // Pre-existing old receipt — must remain intact after failed write
    fs.writeFileSync(p, JSON.stringify({ old: true }), "utf8");
    const circular: any = {};
    circular.self = circular;
    expect(() => withAtomicJsonReceipt(p, () => circular)).toThrow();
    // Old receipt untouched
    expect(JSON.parse(fs.readFileSync(p, "utf8"))).toEqual({ old: true });
    const leftovers = fs.readdirSync(path.dirname(p)).filter((f) => f.includes(".tmp-"));
    expect(leftovers).toEqual([]);
  });
});
