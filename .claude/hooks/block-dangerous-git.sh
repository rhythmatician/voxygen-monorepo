#!/bin/bash

INPUT=$(cat)
COMMAND=$(echo "$INPUT" | jq -r '.tool_input.command // empty' 2>/dev/null)

# If not a Bash tool call, allow.
if [ -z "$COMMAND" ]; then
  exit 0
fi

DANGEROUS_PATTERNS=(
  "git push"
  "git reset --hard"
  "git clean -fd"
  "git clean -f"
  "git branch -D"
  "git checkout \."
  "git restore \."
  "push --force"
  "reset --hard"
)

for pattern in "${DANGEROUS_PATTERNS[@]}"; do
  if echo "$COMMAND" | grep -qE "$pattern"; then
    echo "BLOCKED: '$COMMAND' matches dangerous pattern '$pattern'. The user has prevented you from doing this." >&2
    exit 2
  fi
done

# Branch-aware guard: block git commit/merge/amend on main
if echo "$COMMAND" | grep -qE "git[[:space:]]+(commit|merge|amend)"; then
  branch="$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "")"
  if [ "$branch" = "main" ]; then
    echo "BLOCKED: Direct commits to 'main' are blocked. Create a branch first: git switch -c <branch-name>" >&2
    exit 2
  fi
fi

exit 0
