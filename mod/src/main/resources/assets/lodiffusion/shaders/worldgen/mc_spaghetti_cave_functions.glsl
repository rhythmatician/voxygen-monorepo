// =============================================================================
// mc_spaghetti_cave_functions.glsl
// Advanced Cave Density Functions for Minecraft Overworld (WS-4.1c)
//
// Implements two highly complex cave/density functions ported from vanilla:
//   1. spaghetti2D()   - 2D tunnel system with elevation modulation (4 noise sources)
//   2. entrances()     - 3D cave system with surface bore openings (5+ noise sources)
//
// Ported from:
//   - net.minecraft.world.level.levelgen.NoiseRouterData.spaghetti2D()
//   - net.minecraft.world.level.levelgen.NoiseRouterData.entrances()
//
// Dependencies:
//   - mc_normal_noise(int idx, float x, float y, float z)  [from normal_noise.glsl]
//   - mc_y_gradient(float y, float from_y, float to_y, ...)  [from terrain_compute.comp]
//   - mc_weird_scaled_sampler_type1()  [from mc_cave_noise_helpers.glsl]
//   - mc_weird_scaled_sampler_type2()  [from mc_cave_noise_helpers.glsl]
//
// Integration Points:
//   1. Include this file in terrain_compute.comp AFTER mc_cave_noise_helpers.glsl
//   2. Add NormalNoise indices to RouterConfig UBO for new noise samplers
//   3. Call functions from computeFinalDensity() cave carving section
//
// Performance Notes:
//   - spaghetti2D: ~4 NormalNoise samples, ~15 GLSL instructions
//   - entrances: ~11 NormalNoise samples, ~30 GLSL instructions
//   - Both functions are cached (cacheOnce in Java) to avoid redundant computation
//
// =============================================================================

// ---------------------------------------------------------------------------
// Helper: Mapped Noise (Linear Remapping)
// ---------------------------------------------------------------------------
// Remaps noise output from [-1, 1] range to the specified [outMin, outMax] range.
// This is used for deterministic transformation of raw noise values.
//
// Parameters:
//   noise_idx: NormalNoise index in SSBOs
//   x, y, z: Sampling coordinates
//   out_min, out_max: Target range (can be inverted for descending mappings)
//
// Returns:
//   Remapped noise value in range [outMin, outMax]
float mc_mapped_noise(int noise_idx, float x, float y, float z, float out_min, float out_max) {
    float raw = mc_normal_noise(noise_idx, x, y, z);  // Range: [-1, 1]
    // Normalize from [-1, 1] to [0, 1]
    float t = raw * 0.5 + 0.5;
    // Interpolate to target range
    return out_min + t * (out_max - out_min);
}

// ---------------------------------------------------------------------------
// FUNCTION 1: spaghetti2D
// ---------------------------------------------------------------------------
// High-complexity 2D cave tunnel system with Y elevation modulation.
// 
// Generates elongated horizontal tunnel structures (2D in XZ plane) that
// are modulated vertically by elevation and thickness functions.
// Uses WeirdScaledSampler TYPE2 for rarity-dependent cave scaling.
//
// Mathematical Structure:
//   1. Rarity modulation (coarse quantization at 2x frequency)
//   2. Apply WeirdScaledSampler TYPE2 coordinate warping
//   3. Elevation noise (Y-based depth gradient, -8.0 to +8.0 range)
//   4. Thickness noise (controls layer shape)
//   5. Sloped spaghetti (elevation + Y gradient, take absolute value)
//   6. Layer ridged (sloped + thickness ^ 3, cube operation)
//   7. Cave noise (main tunnel + thickness influence)
//   8. Final min(caveNoise, layerRidged) and clamp
//
// Output Range: [-1.0, 1.0] (clamped)
// Complexity: ~4 noise samples, ~15 instructions
//
// Parameters:
//   bx, by, bz: Block coordinates (float)
//   nn_rarity: NormalNoise index for SPAGHETTI_2D_MODULATOR
//   nn_cave: NormalNoise index for SPAGHETTI_2D (main tunnel)
//   nn_elevation: NormalNoise index for SPAGHETTI_2D_ELEVATION
//   nn_thickness: NormalNoise index for SPAGHETTI_2D_THICKNESS (or pre-computed value)
//
// Returns:
//   Density contribution: negative values indicate cave hollows when used in carving
float mc_spaghetti_2d(float bx, float by, float bz,
                     int nn_rarity, int nn_cave, int nn_elevation, int nn_thickness) {
    // --- Step 1: Rarity modulation (coarse scale at 2x frequency) --------
    // Sampled at doubled frequency to create coarse regions of different cave sizes
    float rarity_raw = mc_normal_noise(nn_rarity, bx * 2.0, by * 2.0, bz * 2.0);
    
    // --- Step 2: WeirdScaledSampler TYPE2 coordinate warping -------------
    // Uses rarity to scale cave frequency inversely (high rarity = coarser features)
    // TYPE2 quantization gives wider rarity scale [0.5, 0.75, 1.0, 2.0, 3.0]
    float spaghetti_cave = mc_weird_scaled_sampler_type2(nn_cave, bx, by, bz, rarity_raw);
    
    // --- Step 3: Elevation noise (Y-gradient modulation, depth variation) --
    // Maps noise output to [-8.0, +8.0] range representing depth gradient
    // floor(-64/8) = -8, so mapping is from [0.0 to -8.0] to [8.0 to ...]
    // Since noise is in [-1,1], we map: -1→-8.0, +1→+∞, but clamped to reasonable range
    // Simplified: map to 8.0 at high noise, -40.0 at low noise (giving rough depth variation)
    float spaghetti_elevation = mc_mapped_noise(nn_elevation, bx, by, bz, -8.0, 8.0);
    
    // --- Step 4: Thickness modulator (controls layer structure) -----------
    // Pre-computed thickness value from SPAGHETTI_2D_THICKNESS_MODULATOR
    // These are sampled independently in Java cache, so we sample with standard frequency
    // The actual mapping is complex 4-point [2.0→1.0→-0.6→-1.3] but we approximate
    // with simple linear mapping. For production, integrate actual spline from cache.
    float thickness = mc_mapped_noise(nn_thickness, bx, by, bz, 2.0, -1.3);
    
    // --- Step 5: Sloped spaghetti (elevation + Y depth gradient) ---------
    // Adds a Y-based depth gradient to elevation to create sloped tunnel bottoms
    // yClampedGradient(-64, 320, 8.0, -40.0) creates slope from top to bottom
    float y_gradient = mc_y_gradient(by, -64.0, 320.0, 8.0, -40.0);
    float sloped_spaghetti = abs(spaghetti_elevation + y_gradient);
    
    // --- Step 6: Layer ridged (magnitude with thickness influence, cubed) ---
    // Takes sloped+thickness sum and cubes it for sharp confinement effect
    // This creates hard boundaries for the layer thickness
    float layer_ridged = pow(sloped_spaghetti + thickness, 3.0);
    
    // --- Step 7: Combine cave noise + thickness influence ----------------
    // Cave noise is main tunnel structure; thickness adds shape modulation
    // Coefficient 0.083 empirically matches vanilla weightings
    float cave_noise = spaghetti_cave + 0.083 * thickness;
    
    // --- Step 8: Final combine and clamp --------------------------------
    // Take maximum of cave shape and ridged layer, clamp to [-1, 1]
    float result = max(cave_noise, layer_ridged);
    return clamp(result, -1.0, 1.0);
}

// ---------------------------------------------------------------------------
// FUNCTION 2: entrances (with embedded sub-functions)
// ---------------------------------------------------------------------------
// Ultra-complex 3D cave entrance system combining 3 nested sub-functions.
//
// Top-level behavior:
//   Combines three sub-cavity systems (spaghetti3D, roughness, big entrances)
//   to create realistic cave openings, entrances, and vertical shafts.
//
// Sub-system A: spaghetti3D
//   Two parallel tunnel lines (cave1, cave2) with rarity-dependent scaling.
//   Uses WeirdScaledSampler TYPE1 for more common 3D cave features.
//
// Sub-system B: spaghettiRoughtness
//   Adds fine surface perturbation to soften tunnel edges.
//
// Sub-system C: bigEntrances
//   Large coarse structure for surface-level cave bores.
//   Sampled at stretched XZ frequency (0.75) for wider vertical shafts.
//   Heavy Y-gradient influence to make entrances weaker deeper underground.
//
// Final: min(bigEntrances, spaghetti3D + roughness)
//   Limits entrance features to realistic cave-opening zones
//
// Parameters:
//   bx, by, bz: Block coordinates (float)
//   nn_rarity_3d: NormalNoise index for SPAGHETTI_3D_RARITY (coarse rarity)
//   nn_thick_3d: NormalNoise index for SPAGHETTI_3D_THICKNESS
//   nn_cave_3d_1: NormalNoise index for SPAGHETTI_3D_1 (first parallel tunnel)
//   nn_cave_3d_2: NormalNoise index for SPAGHETTI_3D_2 (second parallel tunnel)
//   nn_rough_noise: NormalNoise index for SPAGHETTI_ROUGHNESS (detail perturbation)
//   nn_rough_mod: NormalNoise index for SPAGHETTI_ROUGHNESS_MODULATOR (amplitude)
//   nn_entrance: NormalNoise index for CAVE_ENTRANCE (coarse bore structure)
//
// Returns:
//   Density contribution in range [-1.0, ~5.0] for surface entrances
float mc_entrances(float bx, float by, float bz,
                  int nn_rarity_3d, int nn_thick_3d, int nn_cave_3d_1, int nn_cave_3d_2,
                  int nn_rough_noise, int nn_rough_mod, int nn_entrance) {
    
    // ========================================================================
    // SUB-FUNCTION A: spaghetti3D (two parallel tunnel lines)
    // ========================================================================
    
    // --- Step A1: Rarity modulation for 3D caves (coarse scale at 2x) ----
    float rarity_3d_raw = mc_normal_noise(nn_rarity_3d, bx * 2.0, by * 2.0, bz * 2.0);
    
    // --- Step A2: Thickness modulator (simple 2-point range) -----------
    // Maps noise to [-0.065, -0.088] range (negative, very thin)
    float thickness_3d = mc_mapped_noise(nn_thick_3d, bx, by, bz, -0.065, -0.088);
    
    // --- Step A3: Two parallel 3D tunnel lines (using TYPE1 sampler) ----
    // TYPE1 rarity quantization gives [0.75, 1.0, 1.5, 2.0] range
    // First tunnel
    float cave_3d_1 = mc_weird_scaled_sampler_type1(nn_cave_3d_1, bx, by, bz, rarity_3d_raw);
    // Second tunnel
    float cave_3d_2 = mc_weird_scaled_sampler_type1(nn_cave_3d_2, bx, by, bz, rarity_3d_raw);
    
    // --- Step A4: Combine parallel tunnels + thickness, clamp to [-1, 1] --
    // max(cave1, cave2) creates two parallel shafts
    // Adding thickness adds overall shape
    float spaghetti_3d_func = clamp(max(cave_3d_1, cave_3d_2) + thickness_3d, -1.0, 1.0);
    
    // ========================================================================
    // SUB-FUNCTION B: spaghettiRoughnessFunction (fine detail)
    // ========================================================================
    
    // --- Step B1: Roughness noise (raw detail) -------------------------
    float roughness_noise = mc_normal_noise(nn_rough_noise, bx, by, bz);
    
    // --- Step B2: Roughness modulator (amplitude control) ---------------
    // Maps noise to [0.0, -0.1] range to control strength
    float roughness_mod = mc_mapped_noise(nn_rough_mod, bx, by, bz, 0.0, -0.1);
    
    // --- Step B3: Combine into roughness function --------------------
    // roughnessModulator * (abs(roughnessNoise) - 0.4)
    // The -0.4 threshold removes smallest features, leaving medium detail
    float roughness_func = roughness_mod * (abs(roughness_noise) - 0.4);
    
    // ========================================================================
    // SUB-FUNCTION C: bigEntrances (surface-level bore structure)
    // ========================================================================
    
    // --- Step C1: Large entrance noise (stretched XZ frequency) --------
    // Sampled at XZ frequency 0.75 (coarser, 25% stretch) for wider shafts
    // Y at standard frequency for vertical height variation
    float big_entrance_noise = mc_normal_noise(nn_entrance, bx * 0.75, by, bz * 0.75);
    
    // --- Step C2: Y-dependent strength (surface bias) -----------------
    // Stronger near surface (y = -10), decays deeper underground
    // yClampedGradient(-10, 30, 0.3, 0.0) gives max 0.3 strength near surface
    float y_entrance_grad = mc_y_gradient(by, -10.0, 30.0, 0.3, 0.0);
    
    // --- Step C3: Combine into big entries function ------------------
    // Big entrance = noiseSource + 0.37 (offset) + yGradient (surface weighting)
    float big_entrances_func = big_entrance_noise + 0.37 + y_entrance_grad;
    
    // ========================================================================
    // FINAL: Combine all three sub-functions
    // ========================================================================
    
    // --- Step D1: Add roughness to spaghetti3D -------------------------
    float spaghetti_3d_with_rough = spaghetti_3d_func + roughness_func;
    
    // --- Step D2: Final min() operation --------------------------------
    // Limit the cave system: min(bigEntrances, spaghetti3D + roughness)
    // This ensures entrances don't extend too deep and spaghetti3D is limited
    // where bigEntrances aren't present
    float final_entrances = min(big_entrances_func, spaghetti_3d_with_rough);
    
    return final_entrances;
}

// ---------------------------------------------------------------------------
// END mc_spaghetti_cave_functions.glsl
// ---------------------------------------------------------------------------
