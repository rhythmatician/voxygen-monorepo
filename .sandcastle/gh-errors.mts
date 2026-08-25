interface GhCommandError extends Error {
  stderr?: string | Buffer;
}

// child_process Promise rejections are unknown at the catch boundary and attach
// stderr at runtime, so narrow that external value once before direct access.
function isGhCommandError(error: unknown): error is GhCommandError {
  return error instanceof Error && "stderr" in error;
}

export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

export function getGhErrorDetails(error: unknown): string {
  if (isGhCommandError(error)) {
    const stderr = error.stderr;
    if (stderr !== undefined && stderr !== null) {
      const details = String(stderr).trim();
      if (details.length > 0) return details;
    }
  }
  return getErrorMessage(error);
}

export function formatGhFailure(context: string, error: unknown): string {
  return `${context}: ${getErrorMessage(error)}`;
}

/**
 * Structured GitHub HTTP-status discriminator. Only an ACTUAL HTTP status code
 * (as reported by gh CLI in the structured error's stderr/message, e.g.
 * "HTTP 404") proves the corresponding HTTP outcome. Never matches a generic
 * message substring like "not found" — a 500/network/auth failure whose text
 * merely contains "not found" must NOT be treated as an authoritative 404.
 */
export function isHttpStatus(error: unknown, status: number): boolean {
  const e = error as { stderr?: unknown; message?: string };
  const needle = `HTTP ${status}`;
  if (typeof e.stderr === "string" && e.stderr.includes(needle)) return true;
  if (typeof e.message === "string" && e.message.includes(needle)) return true;
  return false;
}

/** True only when the error is an authoritative HTTP 404 (absence proof). */
export function isHttp404(error: unknown): boolean {
  return isHttpStatus(error, 404);
}
