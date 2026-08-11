// =============================================================================
// mc_normal_noise.glsl
// Port of net.minecraft.world.level.levelgen.synth.NormalNoise
//
// This file is NOT a standalone shader. It is concatenated after
// mc_perlin_noise.glsl into terrain_compute.comp.
//
// It depends on the following SSBOs being declared in the including shader:
//
//   layout(binding = 4, std430) buffer NormalNoiseInt   { int   data[]; };
//   layout(binding = 5, std430) buffer NormalNoiseFloat { float data[]; };
//
// --- SSBO layout (per NormalNoise instance, index 'i') ---
//
//   NormalNoiseInt  (4 ints per instance, stride = 4):
//     [i * 4 + 0]  : first_perlin_idx   — index into PerlinInt/PerlinFloat SSBOs
//     [i * 4 + 1]  : second_perlin_idx  — index into PerlinInt/PerlinFloat SSBOs
//     [i * 4 + 2]  : _pad
//     [i * 4 + 3]  : _pad
//
//   NormalNoiseFloat (1 float per instance, stride = 1):
//     [i]          : value_factor
//                    = 0.16666... / expectedDeviation(maxOctave - minOctave)
//                    Computed on Java side during NormalNoise construction.
//
// --- Correspondence to Java ---
//   NormalNoise.first      → mc_perlin_noise(first_perlin_idx, x, y, z)
//   NormalNoise.second     → mc_perlin_noise(second_perlin_idx, x*IF, y*IF, z*IF)
//   NormalNoise.valueFactor → value_factor
//   INPUT_FACTOR = 1.0181268882175227 (hard constant in NormalNoise.java)
// =============================================================================

// NormalNoise.INPUT_FACTOR — second PerlinNoise is always sampled at this scale offset
// from the first. This is a hard constant in the Java source.
#define MC_NORMAL_INPUT_FACTOR 1.0181268882175227

// Evaluate a NormalNoise instance at (x, y, z).
// Matches Java's NormalNoise.getValue(x, y, z).
// Returns 0.0 when idx < 0 (noise not wired) — safe additive identity for all
// density-field use sites.  Without this guard, a negative index would produce
// an out-of-bounds SSBO read (undefined behaviour).
float mc_normal_noise(int idx, float x, float y, float z) {
    if (idx < 0) return 0.0;
    int first_perlin_idx  = normal_noise_int.data[idx * 4 + 0];
    int second_perlin_idx = normal_noise_int.data[idx * 4 + 1];
    float value_factor    = normal_noise_float.data[idx];

    float v1 = mc_perlin_noise(first_perlin_idx,  x, y, z);
    float v2 = mc_perlin_noise(second_perlin_idx,
                               x * MC_NORMAL_INPUT_FACTOR,
                               y * MC_NORMAL_INPUT_FACTOR,
                               z * MC_NORMAL_INPUT_FACTOR);

    return (v1 + v2) * value_factor;
}
