# AGENTS.md

## Exploration

Use /graphify before grep. Check any project root for graphify_out before beginning exploration of that project.

## Coding

Use YAGNI and DRY principles.

## Testing

Prefer fast headless integration tests against the pinned real Voxy and Minecraft classes over mocks or client flyovers. Fake only expensive external boundaries. Keep AFK flyovers as final client/rendering acceptance for behavior that cannot be proven headlessly.

## Token safety

Use RTK for supported commands and potentially large command output. Do not blindly prefix PowerShell cmdlets, aliases, shell built-ins, or shell syntax with rtk. Prefer RTK-native equivalents when available, e.g. rtk read instead of Get-Content. For unsupported PowerShell operations, invoke PowerShell normally and explicitly bound potentially large output.

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

Repository prose is not a shadow source of truth for code. Current mechanics belong in code/tests/contracts/config; work state belongs in GitHub; `CONTEXT.md` contains domain language only; ADRs contain architectural rationale only; navigation docs stay thin. Do not create implementation summaries, plans, TODO/status/deliverables docs, or prose descriptions of implementation that can be discovered from code. See `docs/agents/documentation.md`. The authority/traceability index for retained prose is `docs/INDEX.md`.
