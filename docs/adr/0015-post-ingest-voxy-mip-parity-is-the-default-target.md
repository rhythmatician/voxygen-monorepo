# 0015 — Post-ingest Voxy mip parity is the default target

Date: 2026-08-28
Status: Accepted

## Context

Voxygen must render distant terrain before vanilla has generated the corresponding playable chunks. Its runtime path therefore cannot use a fully generated vanilla chunk as an intermediate artifact merely to obtain distant terrain. As the player approaches, Voxygen refines the same region through L4 → L3 → L2 → L1 → L0. When vanilla eventually generates the authoritative chunk, Voxy ingests it and derives its own LOD pyramid using Voxy's mip semantics.

This creates a stricter target than "looks Minecraft-like." A pre-generation Voxygen representation should converge on the representation Voxy will eventually retain after ingesting authoritative vanilla terrain. The requirement matters most for responsibilities such as placed features, where vanilla's native implementation is object-oriented and fine-grained while distant rendering needs a cheap coarse representation. A forest canopy can matter at L4 while individual grass blocks may disappear naturally under Voxy's opacity-biased mip rule.

The pinned Voxy 0.2.11-alpha audit establishes that Voxy downsamples each 2×2×2 group by selecting the highest-opacity non-air voxel, with a deterministic corner-priority tie-break. It does not use majority vote. Voxy's eventual L4/L3/L2/L1 therefore provides a concrete external target that already incorporates the renderer's coarse-visibility policy.

At the same time, exact recreation of every vanilla responsibility at every Level may be too expensive for Voxygen's latency budget. Worldgen Partition v1 already provides the vocabulary for that trade-off: reuse vanilla, exact port, deterministic approximation, learned approximation, or omit/defer. Learned prediction remains a valid strategy, particularly at coarse Levels, but it must predict the same target rather than define a separate learned truth.

## Decision

For pre-generation distant terrain, **the default correctness target at each Level is the decoded semantic content of the Voxy WorldSection that would exist after authoritative vanilla terrain for the required region and halo has been generated and ingested by the pinned Voxy implementation**.

Therefore:

- Voxygen runtime generation must not materialize a fully generated vanilla chunk or `ProtoChunk` merely as an intermediate step for distant terrain. Offline oracle construction and verification may generate real vanilla chunks.
- L4, L3, L2, L1, and Render L0 are evaluated against the corresponding post-ingest Voxy representation for the same seed, frozen worldgen profile, coordinates, and upstream versions. Which voxel fields are acceptance-bearing remains governed by the Fidelity Profile; no unclaimed light or biome parity is implied by this ADR.
- The oracle must be independent of the candidate implementation. Expected results come from real vanilla generation followed by real Voxy ingest, or from immutable fixtures captured from that path. A production synthesizer, helper shared with it, or reimplementation of its algorithm may not generate the expected value for a test that claims vanilla/Voxy parity.
- Oracle regions include enough neighboring terrain to make vanilla feature placement and Voxy mipping well-defined across boundaries. The required halo is derived from the inspected upstream responsibility, not guessed globally.
- Exact post-ingest Voxy parity is the default. A Level may intentionally use deterministic approximation, learned approximation, or omit/defer only through an explicit Worldgen Partition decision with measured correctness and performance evidence. Such a decision relaxes the implementation strategy, not the oracle target: residual disagreement, Pop, and Vanilla Convergence remain visible.
- A learned model may predict post-ingest Voxy output directly or predict a residual over a deterministic scaffold. It is accepted only when measured against the independent oracle and a cheaper deterministic baseline; model elegance is not evidence of correctness or runtime value.
- Feature-family implementations must identify the relevant Minecraft generation source and the Voxy mip behavior that determines visibility at each Level. Agents may not infer a distant policy from block names, intuition, or the candidate output itself.
- When the pinned Minecraft or Voxy upstream version changes, oracle fixtures and parity claims derived from the old version are a different evidence population and must be regenerated or explicitly versioned.

## Alternatives considered

* **Treat vanilla feature placement itself as the direct L4–L1 target:** this proves too much work at the wrong abstraction. The player ultimately sees Voxy's mip of vanilla terrain, so exact reconstruction of fine blocks that Voxy deterministically suppresses can waste runtime cost without improving the rendered target.
* **Define a hand-authored visual policy independently at each Level:** cheaper to implement, but allows L4/L3/L2/L1 to drift from the eventual Voxy pyramid and makes reveal-versus-replace failures likely.
* **Use learned models as the authority:** preserves the original ML-centric design but makes correctness circular when models are evaluated against their own abstractions. ML remains available as an implementation strategy under the independent Voxy oracle.
* **Generate the vanilla chunk at distance and let Voxy ingest it immediately:** gives straightforward parity but violates the core latency and work-avoidance goal; it computes B when Voxygen exists specifically to produce C without materializing B at runtime.

## Consequences

* The next durable implementation artifact is an executable feature-policy/oracle contract plus a harness that captures or replays real vanilla → Voxy results per Level; its work state belongs in GitHub rather than this ADR.
* Tests named or described as parity, round-trip, vanilla convergence, or post-ingest agreement require an independent oracle path. Self-referential expected values are invalid even if the test is green.
* Thin or transparent features may disappear automatically at coarse Levels because the target is Voxy's own mip output. Expensive explicit omission logic should not be added until the oracle demonstrates that Voxy itself would suppress the feature or a measured partition decision justifies deviation.
* Coarse learned prediction remains a first-class option, especially for aggregate geometry such as canopy mass, provided it is cheaper than exact or deterministic alternatives and is evaluated against the same post-ingest Voxy target.
* Cross-chunk and cross-WorldSection behavior becomes a first-class test concern. A single isolated chunk is not a sufficient oracle unit for responsibilities whose placement or mip result depends on neighboring blocks.
