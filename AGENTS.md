# AGENTS.md

## Agent skills

### Issue tracker

Issues live in GitHub Issues (`rhythmatician/voxygen-monorepo`). See `docs/agents/issue-tracker.md`.

### Triage labels

Canonical triage roles map 1:1 to labels `needs-triage`, `needs-info`, `ready-for-agent`, `ready-for-human`, `wontfix`. See `docs/agents/triage-labels.md`.

### Domain docs

Single-context layout — `CONTEXT.md` + `docs/adr/` at repo root (created lazily by `/domain-modeling`). See `docs/agents/domain.md`.

### Tracer-bullet contract

AFK implementation tickets must satisfy the tracer-bullet contract in `docs/agents/tracer-contract.md` (enforced in `.sandcastle/dispatch.mts` + `.sandcastle/tracer-contract.mts`).

### Documentation

Repository prose is not a shadow source of truth for code. Current mechanics belong in code/tests/contracts/config; work state belongs in GitHub; `CONTEXT.md` contains domain language only; ADRs contain architectural rationale only; navigation docs stay thin. Do not create implementation summaries, plans, TODO/status/deliverables docs, or prose descriptions of implementation that can be discovered from code. See `docs/agents/documentation.md`.
