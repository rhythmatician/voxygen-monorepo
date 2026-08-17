import { describe, expect, it } from "vitest";
import { getGhErrorDetails } from "./gh-errors.mts";

describe("getGhErrorDetails", () => {
  it("uses non-empty stderr", () => {
    const error = Object.assign(new Error("command failed"), { stderr: "API unavailable\n" });

    expect(getGhErrorDetails(error)).toBe("API unavailable");
  });

  it("falls back to the error message when stderr is empty", () => {
    const error = Object.assign(new Error("command failed"), { stderr: "\n" });

    expect(getGhErrorDetails(error)).toBe("command failed");
  });
});
