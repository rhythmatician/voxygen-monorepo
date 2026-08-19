export const EXPECTED_SANDCASTLE_SOURCE_SHA = "95f3a5c7c0ff7c5848bb6f13edaa2ed14e2a6ee4";
export const EXPECTED_SANDCASTLE_SOURCE_PREFIX = EXPECTED_SANDCASTLE_SOURCE_SHA.slice(0, 7);

export function isExpectedSandcastleSourceHead(head: string): boolean {
  if (!head) return false;
  return head.startsWith(EXPECTED_SANDCASTLE_SOURCE_PREFIX);
}

