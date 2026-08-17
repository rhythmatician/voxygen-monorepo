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
