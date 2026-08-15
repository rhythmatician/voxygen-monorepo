# TASK

Merge the following branches into the current branch:

{{BRANCHES}}

For each branch:
1. Run `git merge <branch> --no-edit`
2. If conflicts, resolve by reading both sides and choosing correct resolution — prefer semantic correctness over syntactic merge.
3. After resolving conflicts, run `npm run typecheck` and `npm run test` to verify.
4. If verification fails, fix the integration (not the oracle) before proceeding.

After all branches are merged, make a single commit summarizing the batch: `RALPH: merge sandcastle batch [branches] — integrations verified`.

# INTEGRATION

Do NOT close GitHub issues — the host factory will create the PR, auto-merge when gates pass, and close issues with an audit comment after the code is on `main`.

Do NOT push — host handles push/PR.

Once merged and verified locally, output <promise>COMPLETE</promise>.

# CLOSE ISSUES (for host reference only)

Host will close each merged issue via:
`gh issue close <ID> --comment "Completed by Sandcastle — merged via branch <branch>, integrated in batch"`

Issues being integrated:

{{ISSUES}}
