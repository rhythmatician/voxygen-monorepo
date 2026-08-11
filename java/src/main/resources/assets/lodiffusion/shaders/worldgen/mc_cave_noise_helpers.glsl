// =============================================================================
// mc_cave_noise_helpers.glsl
// GLSL Helper Functions for Cave Noise Infrastructure (WS-4.1b)
// 
// This file contains GLSL implementations of:
//   1. QuantizedSpaghettiRarity (TYPE1 & TYPE2 quantization tables)
//   2. WeirdScaledSampler (rarity-based coordinate warping)
//   3. rangeChoice (Y-range conditional branching)
//   4. yLimitedInterpolatable (Y-gated noise with fallback)
//
// Ported from:
//   - net.minecraft.world.level.levelgen.NoiseRouterData.QuantizedSpaghettiRarity
//   - net.minecraft.world.level.levelgen.DensityFunctions.WeirdScaledSampler  
//   - net.minecraft.world.level.levelgen.DensityFunctions.RangeChoice
//   - net.minecraft.world.level.levelgen.NoiseRouterData.yLimitedInterpolatable
//
// Dependencies:
//   - mc_normal_noise(int idx, float x, float y, float z)  [from normal_noise.glsl]
//
// Usage:
//   These functions should be inserted into terrain_compute.comp AFTER
//   the include cut (after normal_noise.glsl concatenation) and BEFORE
//   computeFinalDensity() definition.
//
// =============================================================================

// ---------------------------------------------------------------------------
// QuantizedSpaghettiRarity Lookup Tables
// ---------------------------------------------------------------------------
// Maps rarity factor (range ~-1 to +1) to coordinate scale multipliers.
// These discrete mappings create three bands of cave size/frequency.

// QuantizedSpaghettiRarity.getSpaghettiRarity3D(rarityFactor)
// Used in WeirdScaledSampler TYPE1 (cave entrances, more common)
// Maps: rarity_factor → scale multiplier [0.75, 1.0, 1.5, 2.0]
float mc_spaghetti_rarity_3d(float rarity_factor) {
    if (rarity_factor < -0.5) return 0.75;
    if (rarity_factor < 0.0)  return 1.0;
    if (rarity_factor < 0.5)  return 1.5;
    return 2.0;  // rarity_factor >= 0.5
}

// QuantizedSpaghettiRarity.getSphaghettiRarity2D(rarityFactor)
// Used in WeirdScaledSampler TYPE2 (spaghetti_2d tunnels, rarer)
// Maps: rarity_factor → scale multiplier [0.5, 0.75, 1.0, 2.0, 3.0]
// Wider range produces coarser/rarer formations.
float mc_spaghetti_rarity_2d(float rarity_factor) {
    if (rarity_factor < -0.75) return 0.5;
    if (rarity_factor < -0.5)  return 0.75;
    if (rarity_factor < 0.5)   return 1.0;
    if (rarity_factor < 0.75)  return 2.0;
    return 3.0;  // rarity_factor >= 0.75
}

// ---------------------------------------------------------------------------
// WeirdScaledSampler TYPE1 (3D Cave Entrances)
// ---------------------------------------------------------------------------
// Maps a rarity input through MC_SPAGHETTI_RARITY_3D quantization,
// then scales XYZ coordinates inversely, samples noise, and scales result
// by rarity for appropriate magnitude.
//
// Parameters:
//   noise_idx: Index into NormalNoise SSBOs (bindings 4,5)
//   x, y, z: Block coordinates (usually float(blockX), float(blockY), float(blockZ))
//   rarity_value: Input rarity factor (typically from a noise sampler, range ~-1 to +1)
//
// Returns: rarity * abs(noise_sample) in range [0.0, ~2.0]
//          (maxRarity for TYPE1 is 2.0)
float mc_weird_scaled_sampler_type1(int noise_idx, float x, float y, float z, float rarity_value) {
    // Step 1: Apply rarity quantization via TYPE1 lookup table
    float rarity = mc_spaghetti_rarity_3d(rarity_value);
    
    // Step 2: Scale coordinates inversely by rarity
    //   High rarity (2.0) → coarser sampling (x/2, y/2, z/2) → larger features
    //   Low rarity (0.75) → finer sampling (x/0.75, ...) → smaller features
    float scaled_x = x / rarity;
    float scaled_y = y / rarity;
    float scaled_z = z / rarity;
    
    // Step 3: Sample 3D noise at scaled coordinates
    //   mc_normal_noise blends two PerlinNoise octave sets with INPUT_FACTOR scaling
    float noise_sample = mc_normal_noise(noise_idx, scaled_x, scaled_y, scaled_z);
    
    // Step 4: Apply rarity magnitude scaling and absolute value
    //   Magnitude scaling preserves the amplitude across different rarity scales
    //   Absolute value makes caves carve both above and below zero-line
    return rarity * abs(noise_sample);
}

// ---------------------------------------------------------------------------
// WeirdScaledSampler TYPE2 (2D Spaghetti Tunnels with Y Component)
// ---------------------------------------------------------------------------
// Variant of WeirdScaledSampler using TYPE2 quantization (wider rarity range).
// Results in rarer, more elongated tunnel formations (higher rarity values possible).
//
// Parameters:
//   noise_idx: Index into NormalNoise SSBOs
//   x, y, z: Block coordinates
//   rarity_value: Input rarity factor (range ~-1 to +1)
//
// Returns: rarity * abs(noise_sample) in range [0.0, ~3.0]
//          (maxRarity for TYPE2 is 3.0)
float mc_weird_scaled_sampler_type2(int noise_idx, float x, float y, float z, float rarity_value) {
    // Step 1: Apply rarity quantization via TYPE2 lookup table
    float rarity = mc_spaghetti_rarity_2d(rarity_value);
    
    // Step 2: Scale coordinates inversely by rarity
    //   TYPE2 rarity range [0.5, 3.0] gives finer control and rarer caves overall
    float scaled_x = x / rarity;
    float scaled_y = y / rarity;
    float scaled_z = z / rarity;
    
    // Step 3: Sample 3D noise at scaled coordinates
    float noise_sample = mc_normal_noise(noise_idx, scaled_x, scaled_y, scaled_z);
    
    // Step 4: Apply rarity magnitude scaling and absolute value
    return rarity * abs(noise_sample);
}

// ---------------------------------------------------------------------------
// rangeChoice Helper
// ---------------------------------------------------------------------------
// Conditional branching based on value range.
// Evaluates: if (value >= min && value < max) return when_in_range
//            else return when_out_of_range
//
// Used by: cave carving switches, Y-range limiting, density thresholding.
//
// Note: In vanilla Java, the "when_in_range" and "when_out_of_range" are
// DensityFunctions computed in their own context. In GLSL, we receive them
// as pre-computed floats. The caller is responsible for computing the
// appropriate branch value before passing.
//
// Parameters:
//   value: The value to test (usually a noise sample or Y coordinate)
//   min_inclusive: Lower bound (inclusive)
//   max_exclusive: Upper bound (exclusive)
//   when_in_range: Value to return if test is true
//   when_out_of_range: Value to return if test is false
//
// Returns: when_in_range or when_out_of_range
float mc_range_choice(float value, float min_inclusive, float max_exclusive,
                      float when_in_range, float when_out_of_range) {
    if (value >= min_inclusive && value < max_exclusive) {
        return when_in_range;
    } else {
        return when_out_of_range;
    }
}

// ---------------------------------------------------------------------------
// yLimitedInterpolatable Helper
// ---------------------------------------------------------------------------
// Y-range gated noise evaluation with interpolation.
// Evaluates noise only within a Y band; returns a constant outside.
//
// Used for: Noodle caves (Y band dependent), Ore veins (Y-restricted),
// any density function that should be active only in a vertical slice.
//
// Java equivalent:
//   DensityFunctions.interpolated(
//       DensityFunctions.rangeChoice(
//           y, minYInclusive, maxYInclusive + 1,
//           whenInRange,
//           DensityFunctions.constant(whenOutOfRange)
//       )
//   )
//
// Note: "interpolated()" in Java adds trilinear interpolation via markers.
// In our GLSL, mc_normal_noise() already performs interpolation internally
// via PerlinNoise, so this simplification is valid.
//
// Parameters:
//   y_val: Block Y coordinate to test
//   noise_idx: NormalNoise index to sample (if in range)
//   x, y_sample, z: Coordinates for noise sampling (y_sample is usually == y_val)
//   min_y_inclusive: Minimum Y (inclusive) for the active range
//   max_y_inclusive: Maximum Y (inclusive) for the active range
//   when_out_of_range: Constant value to return when Y is outside [min, max+1)
//
// Returns:
//   If y_val in [min_y, max_y+1): mc_normal_noise(noise_idx, x, y_sample, z)
//   Otherwise: when_out_of_range
float mc_y_limited_interpolatable(float y_val, int noise_idx, float x, float y_sample, float z,
                                  int min_y_inclusive, int max_y_inclusive, float when_out_of_range) {
    // Convert Y bounds to float for comparison
    // Note: RangeChoice uses minInclusive and maxExclusive bounds
    //       So we convert [min_y, max_y+1) as [min_y, max_y+1)
    float min_y_f = float(min_y_inclusive);
    float max_y_f = float(max_y_inclusive) + 1.0;
    
    if (y_val >= min_y_f && y_val < max_y_f) {
        // Y is within the active range: sample noise
        // The noise sampling is implicitly interpolated via mc_normal_noise
        return mc_normal_noise(noise_idx, x, y_sample, z);
    } else {
        // Y is outside the active range: return constant
        return when_out_of_range;
    }
}

// ---------------------------------------------------------------------------
// END mc_cave_noise_helpers.glsl
// ---------------------------------------------------------------------------
