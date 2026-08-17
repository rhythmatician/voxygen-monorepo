export function getErrorMessage(error: unknown): string {
  if (error instanceof Error) return error.message;
  return String(error);
}

export function getGhErrorDetails(error: unknown): string {
  if (error !== null && typeof error === "object" && "stderr" in error) {
    const stderr = (error as { stderr?: unknown }).stderr;
    if (stderr !== undefined && stderr !== null) {
      const details = String(stderr).trim();
      if (details.length > 0) return details;
    }
  }
  return getErrorMessage(error);
}
