# AGENT_RULES.md — VoxyGen Autonomous Development Protocol

> **CRITICAL INSTRUCTION FOR AI AGENTS:**  
> You operate as a **Contract-Enforcing Systems Engineer**. You must strictly adhere to the path permissions, oracle boundaries, and verification mandates defined below. **You are NEVER permitted to modify test assertions, schemas, or golden fixtures to make a failing test pass.**

---

## 1. Authority & Path Permissions Matrix

| Path Pattern | Classification | Authority Level | Agent Permission | Description / Invariants |
| :--- | :--- | :--- | :--- | :--- |
| `spec/` | **Contract** | Primary Oracle | **IMMUTABLE (`READ-ONLY`)** | Machine-readable schemas (`schema_vN.json`). Defines immutable tensor rank, axis ordering, `dtype`, and byte alignments. |
| `fixtures/golden/` | **Golden Vector** | Reference Output | **IMMUTABLE (`READ-ONLY`)** | Cryptographically hashed binary fixtures (`.bin`). Golden test vectors containing asymmetric coordinate sentinels. |
| `fixtures/hashes.manifest` | **Integrity Check** | Security Oracle | **IMMUTABLE (`READ-ONLY`)** | SHA-256 manifest of schemas and golden vectors. Checked before and after every execution gate. |
| `external/` | **Engine & API** | Third-Party Ref | **IMMUTABLE (`READ-ONLY`)** | Symlinked local repositories for Voxy and Minecraft decompiled source mappings. Never edit external dependencies. |
| `java/src/contractTest/` | **Enforcement** | Verification Suite | **IMMUTABLE (`READ-ONLY`*)** | JUnit 5 contract runners asserting Java deserialization and meshing against golden vectors. |
| `dev/` | **Harness** | Control Pipeline | **IMMUTABLE (`READ-ONLY`)** | Shell verification wrappers (`verify-contract`, `verify-java`, `verify-all`, etc.) and diff-scoped `apply_patch` hooks. |
| `python/` | **Reference ML** | Generator / ML | **CONDITIONAL (`EDITABLE`)** | PyTorch/OGN models, ONNX exporters, and fixture bakers (`VoxelTree`). Edit ONLY when working on explicit Python/ML tickets. |
| `java/src/main/` | **Implementation** | Target Surface | **FULL (`EDITABLE`)** | Java/JNI/Voxy adapters (`LODiffusion`). This is your primary editing surface to satisfy contract gates. |

*\*Note: `java/src/contractTest/` and `spec/` may only be modified by humans during an explicit, human-driven contract migration ticket.*

---

## 2. Core Operating Axioms

### Axiom I: "The Oracle is Immutable"
If a test in `java/src/contractTest/` fails against `fixtures/golden/`:
* **The Java implementation (`java/src/main/`) is WRONG.**
* The test runner, schema, and binary fixtures are **RIGHT**.
* You are **STRICTLY FORBIDDEN** from modifying JUnit test files, weakening assertions, mocking out adapters, or altering binary fixtures to achieve a green test status.

### Axiom II: "Obey Structured Failure Payloads"
When `./dev/verify-java` or `./dev/verify-all` fails, it outputs a `VOXYGEN_CONTRACT_FAILURE` diagnostic block.
1. Read the target coordinates, expected block ID vs. observed block ID, and linear index calculations.
2. Identify the broken invariant (e.g., C-contiguous vs. Fortran index flattening, $X \leftrightarrow Z$ transpose, or signedness mismatch).
3. Apply targeted fixes strictly inside `java/src/main/`.

### Axiom III: "Diff-Scoped Verification"
Always run the narrowest applicable gate while iterating:
1. Run `apply_patch` to get immediate compiler/type diagnostics scoped strictly to touched files.
2. Run `qgateFast` for touched-file compile and static analysis checks.
3. Run the specific gate (e.g., `./dev/verify-java`).
4. Run `./dev/verify-all` before marking any ticket complete.

---

## 3. Toolchain & Pinned Runtime Constraints

To prevent API hallucination and version drift, abide by the repository's pinned environment:

* **Java Environment:** Pinned JDK version declared in `java/gradle.properties`.
* **Mod Loader:** Fabric Loom as configured in `java/build.gradle`. Do NOT assume NeoForge or Forge APIs exist.
* **Mappings:** Official / Yarn mappings as declared in `java/gradle.properties`.
* **Dependencies:** Use ONLY libraries imported in `java/build.gradle` and `python/pyproject.toml`. Do NOT invent unapproved third-party imports.

---

## 4. Ticket Escalation Protocol (Max 5 Attempts)

You are permitted a maximum of **5 implementation attempts** against the same failing invariant/gate.

If `./dev/verify-java` or `./dev/verify-all` remains red after 5 attempts:
1. **HALT** editing immediately.
2. Revert all local changes in the working tree (`git reset --hard`).
3. Generate a structured `TICKET_ESCALATION.md` file in the root directory containing:
   * **Failing Gate & Command:** (e.g., `./dev/verify-java`)
   * **Violated Invariant:** (e.g., Sentinel mismatch at `(15, 0, 0)`)
   * **Attempted Hypotheses:** Concise list of the 5 changes tried.
   * **Diagnostics Payload:** The full `VOXYGEN_CONTRACT_FAILURE` console output.
   * **Unresolved Boundary:** A specific question or hypothesis for human review.

---

## 5. Pre-Flight Verification Checklist

Before reporting a ticket as complete, execute this checklist:

```bash
# 1. Assert Oracle Integrity (Fails if any spec/fixture was tampered with)
sha256sum -c fixtures/hashes.manifest

# 2. Run Master Verification Pipeline
./dev/verify-all