# TASK

Review the code changes on branch `{{BRANCH}}` and improve clarity, consistency, and maintainability while preserving exact functionality.

# CONTEXT

## Branch diff
!`git diff {{TARGET_BRANCH}}...{{BRANCH}}`

## Commits on this branch
!`git log {{TARGET_BRANCH}}..{{BRANCH}} --oneline`

# REVIEW PROCESS

1. Understand intent from diff and commits.
2. Look for unnecessary complexity, redundant code, poor naming, or security issues.
3. Check correctness: does it match intent, handle edge cases, have tests, avoid unsafe casts/any?
4. Apply standards from `@.sandcastle/CODING_STANDARDS.md`.
5. Preserve functionality — never change what the code does, only how.

# EXECUTION

If improvements found:
1. Make changes directly on this branch
2. Run `npm run typecheck` and `npm run test` to verify
3. Commit describing refinements

If already clean, do nothing.

Do not close the issue. Once complete, output <promise>COMPLETE</promise>.
