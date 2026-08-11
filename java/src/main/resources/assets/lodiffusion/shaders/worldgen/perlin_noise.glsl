// =============================================================================
// mc_perlin_noise.glsl
// Port of net.minecraft.world.level.levelgen.synth.PerlinNoise
//
// This file is NOT a standalone shader. It is concatenated after
// mc_improved_noise.glsl into terrain_compute.comp.
//
// It depends on the following SSBOs being declared in the including shader:
//
//   layout(binding = 2, std430) buffer PerlinInt   { int   data[]; };
//   layout(binding = 3, std430) buffer PerlinFloat { float data[]; };
//
// --- SSBO layout (per PerlinNoise instance, index 'i') ---
//
//   PerlinInt layout (stride = PN_INT_STRIDE ints per instance):
//     [i * PN_INT_STRIDE + 0]              : num_octaves (int)
//     [i * PN_INT_STRIDE + 1..3]           : _pad (int x3)
//     [i * PN_INT_STRIDE + 4 + octave]     : improved_noise_index (int)
//                                            — index into improved_origins/perms SSBOs
//                                            — -1 if this octave is null (amplitude was 0)
//
//   PerlinFloat layout (stride = PN_FLOAT_STRIDE floats per instance):
//     [i * PN_FLOAT_STRIDE + 0]            : lowestFreqInputFactor
//     [i * PN_FLOAT_STRIDE + 1]            : lowestFreqValueFactor
//     [i * PN_FLOAT_STRIDE + 2 + octave]   : amplitude[octave]
//
// --- Correspondence to Java ---
//   PerlinNoise.noiseLevels[i]   → improved_noise_index (or -1 if null)
//   PerlinNoise.amplitudes       → stored per-octave in PerlinFloat
//   PerlinNoise.lowestFreqInputFactor / lowestFreqValueFactor → PerlinFloat[0..1]
//   The loop `factor *= 2.0; valueFactor /= 2.0` is reproduced below.
// =============================================================================

#ifndef MAX_OCTAVES
#define MAX_OCTAVES 16
#endif

#define PN_INT_STRIDE   (4 + MAX_OCTAVES)
#define PN_FLOAT_STRIDE (2 + MAX_OCTAVES)

// PerlinNoise.wrap(x): reduces x into [-33554432, 33554432] to prevent float overflow
// at extreme world coordinates. Constant 33554432 = 2^25.
// Matches Java: x - floor(x / 3.3554432E7 + 0.5) * 3.3554432E7
float mc_wrap(float x) {
    return x - floor(x / 33554432.0 + 0.5) * 33554432.0;
}

// Evaluate a PerlinNoise instance at (x, y, z).
// Matches Java's PerlinNoise.getValue(x, y, z) (the non-deprecated form, yScale=0).
float mc_perlin_noise(int idx, float x, float y, float z) {
    int   int_base   = idx * PN_INT_STRIDE;
    int   float_base = idx * PN_FLOAT_STRIDE;

    int   num_octaves          = perlin_int.data[int_base + 0];
    float lowest_freq_input    = perlin_float.data[float_base + 0];
    float lowest_freq_value    = perlin_float.data[float_base + 1];

    float value        = 0.0;
    float input_factor = lowest_freq_input;   // starts at 2^(-zeroOctaveIndex)
    float value_factor = lowest_freq_value;   // starts at 2^(N-1) / (2^N - 1)

    for (int i = 0; i < num_octaves && i < MAX_OCTAVES; i++) {
        int improved_idx = perlin_int.data[int_base + 4 + i];

        // Skip null octaves (amplitude was 0.0 in Java)
        if (improved_idx < 0) {
            input_factor *= 2.0;
            value_factor *= 0.5;
            continue;
        }

        float amplitude = perlin_float.data[float_base + 2 + i];

        // Apply wrap before sampling — matches PerlinNoise.wrap(coord * factor)
        float wx = mc_wrap(x * input_factor);
        float wy = mc_wrap(y * input_factor);
        float wz = mc_wrap(z * input_factor);

        value += amplitude * mc_improved_noise(improved_idx, wx, wy, wz) * value_factor;

        input_factor *= 2.0;
        value_factor *= 0.5;
    }

    return value;
}
