package com.rhythmatician.lodiffusion.onnx;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Model-level output validator that enforces hard correctness guards on
 * sparse-octree ONNX inference results.
 *
 * <p>This is the <b>second decision layer</b> in the safety stack:
 * <pre>
 *   Layer 1: Sampler-level fallback (GpuNoiseRouterSampler → CPU on timeout)
 *   Layer 2: Model-level defer     (THIS CLASS — validates model output)
 * </pre>
 *
 * <p>The defer policy acts as a gate between the model's raw output and
 * the consumer (block grid).  If the output fails any hard guard, the
 * caller should fall back to the sampler-based generation path.
 *
 * <h2>Design principles</h2>
 * <ul>
 *   <li>Start with <b>hard correctness guards</b>, not heuristics.</li>
 *   <li>No probabilistic gating in v1 — deterministic pass/fail.</li>
 *   <li>Every rejection is logged with the specific trigger.</li>
 * </ul>
 *
 * @see SparseOctreeModelRunner
 */
public final class ModelOutputValidator {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/ModelValidator");

    /** Result of validating a model's raw ONNX outputs before decode. */
    public enum ValidationResult {
        /** Output is valid; proceed with decode. */
        ACCEPT,
        /** Output contains NaN or Inf values. */
        REJECT_NAN_INF,
        /** Split/label tensors have inconsistent structure. */
        REJECT_STRUCTURAL_VIOLATION,
        /** All split logits collapse to the same decision (degenerate tree). */
        REJECT_DEGENERATE_TREE,
        /** Label logits are all identical (model produced uniform output). */
        REJECT_UNIFORM_LABELS
    }

    // ── Pre-decode validation (raw ONNX tensors) ─────────────────────

    /**
     * Validate raw occ/split and label tensors before octree decode.
     *
     * <p>v7+ models emit {@code occ_L{i} [1, N, 8]} per-child occupancy
     * logits, while legacy v6 models emit {@code split_L{i} [1, N]}
     * scalar split logits.  Both formats are validated uniformly.
     *
     * @param occByLevel   occ logit arrays indexed by level (v7+), may be all-null for legacy
     * @param splitByLevel split logit arrays indexed by level (legacy v6), may be all-null for v7+
     * @param labelByLevel label logit arrays indexed by level (flat [N*C])
     * @param cByLevel     class count per level
     * @param levels       number of levels (typically 5)
     * @param hasOcc       true if the model emits occ tensors (v7+)
     * @return validation result
     */
    public static ValidationResult validatePreDecode(
            float[][] occByLevel,
            float[][] splitByLevel,
            float[][] labelByLevel,
            int[] cByLevel,
            int levels,
            boolean hasOcc) {

        // Guard 1: NaN / Inf in any tensor
        for (int lvl = 0; lvl < levels; lvl++) {
            if (occByLevel != null && occByLevel[lvl] != null && containsNanOrInf(occByLevel[lvl])) {
                LOG.warn("[ModelValidator] REJECT: NaN/Inf in occ_L{}", 4 - lvl);
                return ValidationResult.REJECT_NAN_INF;
            }
            if (splitByLevel != null && splitByLevel[lvl] != null && containsNanOrInf(splitByLevel[lvl])) {
                LOG.warn("[ModelValidator] REJECT: NaN/Inf in split_L{}", 4 - lvl);
                return ValidationResult.REJECT_NAN_INF;
            }
            if (labelByLevel[lvl] != null && containsNanOrInf(labelByLevel[lvl])) {
                LOG.warn("[ModelValidator] REJECT: NaN/Inf in label_L{}", 4 - lvl);
                return ValidationResult.REJECT_NAN_INF;
            }
        }

        // Guard 2: Structural — at least one level must have usable expansion + label data.
        boolean anyUsableLevel = false;
        for (int lvl = 0; lvl < levels; lvl++) {
            boolean hasExpansion = (hasOcc && occByLevel != null && occByLevel[lvl] != null)
                    || (splitByLevel != null && splitByLevel[lvl] != null);
            if (hasExpansion || labelByLevel[lvl] != null) {
                anyUsableLevel = true;
                break;
            }
        }
        if (!anyUsableLevel) {
            LOG.warn("[ModelValidator] REJECT: No usable tensor data at any level");
            return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
        }

        // Guard 3: Degenerate tree — root produces no expansion AND all L0
        // labels collapse to a single class.
        boolean rootExpands;
        if (hasOcc && occByLevel != null && occByLevel[0] != null && occByLevel[0].length >= 8) {
            // v7+ occ: check if any child exceeds a minimal threshold
            rootExpands = false;
            for (int i = 0; i < 8; i++) {
                if (sigmoid(occByLevel[0][i]) > 0.01f) {
                    rootExpands = true;
                    break;
                }
            }
        } else if (splitByLevel != null && splitByLevel[0] != null && splitByLevel[0].length > 0) {
            // Legacy v6 split scalar
            rootExpands = sigmoid(splitByLevel[0][0]) >= 0.01f;
        } else {
            // No expansion data at root — can't judge
            rootExpands = true;
        }

        if (!rootExpands && labelByLevel[levels - 1] != null && cByLevel[levels - 1] > 1) {
            if (isUniformArgmax(labelByLevel[levels - 1], cByLevel[levels - 1])) {
                LOG.warn("[ModelValidator] REJECT: Degenerate tree — root nosplit + uniform L0 labels");
                return ValidationResult.REJECT_DEGENERATE_TREE;
            }
        }

        return ValidationResult.ACCEPT;
    }

    /**
     * Legacy overload for v6 models that only emit scalar split tensors.
     */
    public static ValidationResult validatePreDecode(
            float[][] splitByLevel,
            float[][] labelByLevel,
            int[] cByLevel,
            int levels) {
        return validatePreDecode(null, splitByLevel, labelByLevel, cByLevel, levels, false);
    }

    // ── Post-decode validation (block grid) ──────────────────────────

    /**
     * Validate the decoded 16³ block grid for structural impossibilities.
     *
     * @param grid {@code int[16][16][16]} block IDs in [y][z][x] order
     * @return validation result (ACCEPT or specific rejection)
     */
    public static ValidationResult validatePostDecode(int[][][] grid) {
        if (grid == null) {
            LOG.warn("[ModelValidator] REJECT: null grid from decode");
            return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
        }

        // Check dimensions
        if (grid.length != 16) {
            LOG.warn("[ModelValidator] REJECT: grid Y dimension {} != 16", grid.length);
            return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
        }

        // Check for all-same-block (degenerate; usually indicates model failure)
        int firstBlock = grid[0][0][0];
        boolean allSame = true;
        outer:
        for (int y = 0; y < 16; y++) {
            if (grid[y] == null || grid[y].length != 16) {
                LOG.warn("[ModelValidator] REJECT: grid Z dimension at y={} is null or wrong size", y);
                return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
            }
            for (int z = 0; z < 16; z++) {
                if (grid[y][z] == null || grid[y][z].length != 16) {
                    LOG.warn("[ModelValidator] REJECT: grid X dimension at y={},z={} is null or wrong size", y, z);
                    return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
                }
                for (int x = 0; x < 16; x++) {
                    if (grid[y][z][x] != firstBlock) {
                        allSame = false;
                        break outer;
                    }
                    // Negative block IDs are never valid
                    if (grid[y][z][x] < 0) {
                        LOG.warn("[ModelValidator] REJECT: negative block ID {} at ({},{},{})",
                                grid[y][z][x], x, y, z);
                        return ValidationResult.REJECT_STRUCTURAL_VIOLATION;
                    }
                }
            }
        }

        if (allSame) {
            // All-air (block 0) is potentially valid for very high or void sections,
            // but all-stone or all-other is suspicious. Log as info, don't reject.
            if (firstBlock != 0) {
                LOG.info("[ModelValidator] INFO: Entire 16³ section is block ID {} "
                        + "(may indicate model issue)", firstBlock);
            }
            // Don't reject — could be legitimate (e.g. deep underground = all stone)
        }

        return ValidationResult.ACCEPT;
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private static boolean containsNanOrInf(float[] arr) {
        for (float v : arr) {
            if (Float.isNaN(v) || Float.isInfinite(v)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if every node in a label tensor produces the same argmax class.
     */
    private static boolean isUniformArgmax(float[] labelFlat, int c) {
        if (labelFlat.length < c * 2) return false;  // need at least 2 nodes
        int nodeCount = labelFlat.length / c;
        int firstArgmax = argmax(labelFlat, 0, c);
        for (int n = 1; n < nodeCount; n++) {
            if (argmax(labelFlat, n * c, c) != firstArgmax) {
                return false;
            }
        }
        return true;
    }

    private static int argmax(float[] arr, int offset, int length) {
        int best = 0;
        float bestVal = arr[offset];
        for (int i = 1; i < length && offset + i < arr.length; i++) {
            if (arr[offset + i] > bestVal) {
                bestVal = arr[offset + i];
                best = i;
            }
        }
        return best;
    }

    private static float sigmoid(float x) {
        return 1.0f / (1.0f + (float) Math.exp(-x));
    }

    private ModelOutputValidator() {}  // static utility
}
