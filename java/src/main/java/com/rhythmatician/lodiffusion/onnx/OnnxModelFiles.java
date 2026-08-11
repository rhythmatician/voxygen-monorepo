package com.rhythmatician.lodiffusion.onnx;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Helpers for detecting the available ONNX model contract in the configured
 * model directory.
 */
public final class OnnxModelFiles {

    private OnnxModelFiles() {}

    public static boolean hasFullVoxyModelSet(Path modelDir) {
        for (int level = 0; level < VoxyModelRunner.NUM_LEVELS; level++) {
            if (!Files.isRegularFile(modelDir.resolve("voxy_l" + level + ".onnx"))) {
                return false;
            }
        }
        return true;
    }

    public static boolean hasAnyVoxyModel(Path modelDir) {
        for (int level = 0; level < VoxyModelRunner.NUM_LEVELS; level++) {
            if (Files.isRegularFile(modelDir.resolve("voxy_l" + level + ".onnx"))) {
                return true;
            }
        }
        return false;
    }

    public static String describeModelState(Path modelDir) {
        if (hasFullVoxyModelSet(modelDir)) {
            return "Voxy 5-model set present (voxy_l0.onnx through voxy_l4.onnx)";
        }
        if (hasAnyVoxyModel(modelDir)) {
            return "Partial Voxy model set present (expected voxy_l0.onnx through voxy_l4.onnx)";
        }
        return "No Voxy ONNX model files present";
    }
}