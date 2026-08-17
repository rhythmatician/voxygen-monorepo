# Factory CI

`Factory / Merge Oracle` is the authoritative automated-evidence check. The workflow always runs on pull requests; path filters never decide whether evidence is required.

The policy engine in `.sandcastle/ci-policy.mts` classifies the complete base-to-candidate diff, expands cumulative evidence requirements, rejects missing/stale/non-passing evidence, and writes `factory-evidence-manifest.json`. `.ci/checks.json` is the single check and path-policy registry. Evidence selection and human approval are independent dimensions: a path set may add checks, require human approval, or both.

Human approval protects the software factory and explicitly designated roots of trust. Ordinary product tests are verified by CI but are not privileged merely because they are tests. The protected set is deliberately narrow: factory code and configuration that can change agent behavior, evidence, or merge authority; plus accepted domain/architecture policy roots used as normative inputs. A protected oracle must be named as its own narrow path set with a concrete failure or attack mode; importance alone is insufficient.

The `factory-control-plane` environment is the operational human gate, and Sandcastle separately declines to enable autonomous merge for the same configured paths. Because the environment job is selected by candidate workflow code, this is procedural protection rather than an independent security principal. The durable boundary requires factory PRs to use an automation identity distinct from `@rhythmatician` and the default-branch ruleset to enable **Require review from Code Owners**. Repository code cannot create that identity safely. Policy tests keep `.github/CODEOWNERS` exactly aligned with `.ci/checks.json` so the base-controlled gate is ready once repository administration enables it.

Local policy tests: `npm test -- --run .sandcastle/ci-policy.test.mts`. Full factory checks: `npm run typecheck && npm test`. Language lanes use the same Gradle, uv, and test commands recorded in `.github/workflows/factory-ci.yml`.

Infrastructure failure is never a pass. The real Voxy artifact is pinned by version and digest in `.ci/voxy-artifact.json`; no CI-generated API stub is permitted. Production ONNX export failure is likewise fatal and no replacement model is generated.

Checks absent from a lane in `.ci/checks.json` are deliberately unavailable, not silently satisfied. I-02 and I-05 remain non-required integration follow-ups; the active C3 gate proves pinned real-API compilation and deployable Fabric/Minecraft packaging without claiming a runtime smoke.
