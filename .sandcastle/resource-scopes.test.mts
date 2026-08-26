import { describe, it, expect } from "vitest";
import * as fs from "node:fs";
import * as os from "node:os";
import * as path from "node:path";
import { withTemporaryIssueFixtures, withAtomicJsonReceipt } from "./resource-scopes.mts";

describe("withTemporaryIssueFixtures", () => {
  it("records fixtures at acquisition and cleans all in finally on success", async () => {
    const cleaned: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record({ title: "a", id: 1 }); record({ title: "b", id: 2 }); record({ title: "c", id: 3 });
        return [10, 20, 30];
      },
      {
        cleanup: async (handle) => { cleaned.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); },
        verify: async () => null,
      },
    );
    expect(result.ok).toBe(true);
    expect(result.value).toEqual([10, 20, 30]);
    expect(result.fixtures.map((f) => f.id)).toEqual([1, 2, 3]);
    expect(cleaned).toEqual(["#1", "#2", "#3"]);
    expect(result.cleanupFailures).toEqual([]);
  });

  it("PARTIAL ACQUISITION: first fixture created, second throws — first still cleaned and verified", async () => {
    const cleaned: string[] = [];
    const verified: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record({ title: "first", id: 101 });
        throw new Error("second fixture creation failed");
      },
      {
        cleanup: async (handle) => { cleaned.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); },
        verify: async (handle) => { verified.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); return null; },
      },
    );
    // The recorded fixture was NOT leaked despite the promise never resolving normally
    expect(result.fixtures.map((f) => f.id)).toEqual([101]);
    expect(cleaned).toEqual(["#101"]);
    expect(verified).toEqual(["#101"]);
    expect(result.primaryError).toContain("second fixture creation failed");
    expect(result.cleanupFailures).toEqual([]);
    expect(result.ok).toBe(false);
  });

  it("cleans up when the primary body fails, keeping failures separated", async () => {
    const cleaned: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        record({ title: "a", id: 7 }); record({ title: "b", id: 8 });
        throw new Error("primary exploded");
      },
      {
        cleanup: async (handle) => { cleaned.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); },
        verify: async () => null,
      },
    );
    expect(cleaned).toEqual(["#7", "#8"]);
    expect(result.primaryError).toContain("primary exploded");
    expect(result.cleanupFailures).toEqual([]);
    expect(result.ok).toBe(false);
    expect(result.value).toBeUndefined();
  });

  it("one fixture's cleanup failure does not skip another's cleanup", async () => {
    const cleaned: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record({ title: "a", id: 1 }); record({ title: "b", id: 2 }); record({ title: "c", id: 3 }); return "done"; },
      {
        cleanup: async (handle) => {
          if (handle.id === 2) throw new Error("fixture 2 unclean");
          cleaned.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`);
        },
        verify: async () => null,
      },
    );
    // Fixtures 1 and 3 still cleaned despite fixture 2 failing
    expect(cleaned).toEqual(["#1", "#3"]);
    expect(result.cleanupFailures.length).toBe(1);
    expect(result.cleanupFailures[0]).toContain("#2");
    expect(result.primaryError).toBeUndefined();
    expect(result.ok).toBe(false);
  });

  it("postcondition verification failure counts as cleanup failure", async () => {
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record({ title: "a", id: 5 }); return "ok"; },
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
      async ({ record }) => { record({ title: "a", id: 9 }); return "ok"; },
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
    const verified: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => { record({ title: "a", id: 1 }); record({ title: "b", id: 2 }); return "ok"; },
      {
        cleanup: async (handle) => { if (handle.id === 1) throw new Error("nope"); },
        verify: async (handle) => { verified.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); return null; },
      },
    );
    expect(verified).toEqual(["#2"]);
    expect(result.cleanupFailures.length).toBe(1);
  });

  it("title-only handle (no id) is cleaned by exact title resolution", async () => {
    const cleaned: string[] = [];
    const result = await withTemporaryIssueFixtures(
      async ({ record }) => {
        // POST uncertain + recovery failed: the handle carries only the title.
        record({ title: "lost-fixture" });
        throw new Error("POST uncertain, no id recovered");
      },
      {
        cleanup: async (handle) => { cleaned.push(handle.id !== undefined ? `#${handle.id}` : `"${handle.title}"`); },
        verify: async () => null,
      },
    );
    expect(result.primaryError).toContain("POST uncertain");
    expect(cleaned).toEqual(['"lost-fixture"']);
    expect(result.cleanupFailures).toEqual([]);
    expect(result.ok).toBe(false);
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
