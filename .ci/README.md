# Factory CI

`Factory / Merge Oracle` is the only authoritative repository check. The workflow always runs on pull requests; path filters never decide whether evidence is required.

The policy engine in `.sandcastle/ci-policy.mts` classifies the complete base-to-candidate diff, expands cumulative requirements, rejects missing/stale/non-passing evidence, and writes `factory-evidence-manifest.json`. `.ci/checks.json` is the single check and protected-path registry. Control-plane and protected-oracle changes are C5, are covered by `.github/CODEOWNERS`, and require human approval; Sandcastle will not enable autonomous merge for them. Repository rulesets must require both this stable oracle check and Code Owner review—repository code cannot safely bootstrap those platform settings itself.

Local policy tests: `npm test -- --run .sandcastle/ci-policy.test.mts`. Full factory checks: `npm run typecheck && npm test`. Language lanes use the same Gradle, uv, and test commands recorded in `.github/workflows/factory-ci.yml`.

Infrastructure failure is never a pass. The real Voxy artifact is pinned by version and digest in `.ci/voxy-artifact.json`; no CI-generated API stub is permitted. Production ONNX export failure is likewise fatal and no replacement model is generated.

Checks absent from a lane in `.ci/checks.json` are deliberately unavailable, not silently satisfied. Today X-01..X-06 and X-03 have no authoritative evidence producer, so contract/model changes that require them fail closed until the protected cross-language oracle is implemented. I-02 and I-05 remain non-required integration follow-ups; the active C3 gate proves pinned real-API compilation and deployable Fabric/Minecraft packaging without claiming a runtime smoke.
