package com.rhythmatician.lodiffusion.onnx;

/**
 * Result of a single model inference pass.
 *
 * <p>Air is represented as block class 0 in the unified softmax — there is
 * no separate air mask.  A voxel is "air" when {@code argmax(blockLogits, dim=1) == 0}.
 *
 * @param blockLogits raw logits  [1, N, 16, 16, 16] — axis order (batch, vocab, y, z, x)
 * @param elapsedMs   wall-clock inference time in milliseconds
 */
public record InferenceResult(
    float[][][][][] blockLogits,
    long elapsedMs
) {}
