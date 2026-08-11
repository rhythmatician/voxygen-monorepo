// ============================================================================
// INTEGRATION GUIDE: terrain_compute.comp
// 
// This file shows the exact changes needed to integrate the 4 cave density
// functions into the existing compute shader.
// ============================================================================

// ============================================================================
// STEP 1: Add helper functions (BEFORE the computeFinalDensity function)
// ============================================================================

// Insert after line ~260 (after existing helper functions like mc_y_gradient):

// --- INSERT START (new helper functions) ---

// mc_range_map: linear interpolation with clamping
// Maps input from [base_min, base_max] to [range_min, range_max]
float mc_range_map(float value, float base_min, float base_max, 
                   float range_min, float range_max) {
    float t = clamp((value - base_min) / (base_max - base_min), 0.0, 1.0);
    return mix(range_min, range_max, t);
}

// mc_y_limited_noise: samples a NormalNoise only within a Y range
// Corresponds to vanilla DensityFunctions.yLimitedInterpolatable
float mc_y_limited_noise(int nn_index, float x, float y, float z,
                         float y_min, float y_max, float default_value) {
    if (nn_index < 0) return default_value;
    if (y < y_min || y >= y_max) return default_value;
    return mc_normal_noise(nn_index, x, y, z);
}

// --- INSERT END ---

// ============================================================================
// STEP 2: Add the 4 cave density functions (BEFORE computeFinalDensity)
// ============================================================================

// Insert after the helper functions block (after ~290):

// --- INSERT START (4 cave functions) ---

// Cave density function 1: roughness
// Spaghetti roughness modulation for tunnel detail
float mc_cave_roughness(float x, float y, float z) {
    if (router.nn_spaghetti_roughness < 0 || router.nn_spaghetti_roughness_mod < 0) {
        return 0.0;
    }
    
    float roughness_noise = mc_normal_noise(router.nn_spaghetti_roughness, x, y, z);
    float modulator_raw = mc_normal_noise(router.nn_spaghetti_roughness_mod, x, y, z);
    float modulator = mc_range_map(modulator_raw + 1.0, 0.0, 2.0, 0.0, -0.1);
    float result = modulator * (abs(roughness_noise) - 0.4);
    
    return result;
}

// Cave density function 2: cheeseCaves
// Pillar-based spherical voids with frequency stretching
float mc_cave_cheesecaves(float x, float y, float z) {
    if (router.nn_pillar < 0 || router.nn_pillar_rareness < 0 || 
        router.nn_pillar_thickness < 0) {
        return 0.0;
    }
    
    // Pillar noise sampled at reduced Y frequency (0.3)
    float pillar_noise = mc_normal_noise(router.nn_pillar, x, y * 0.3, z);
    
    // Rareness modulator: suppresses caves
    float rareness_raw = mc_normal_noise(router.nn_pillar_rareness, x, y, z);
    float rareness = mc_range_map(rareness_raw + 1.0, 0.0, 2.0, 0.0, -2.0);
    
    // Thickness modulator: cubic influence
    float thickness_raw = mc_normal_noise(router.nn_pillar_thickness, x, y, z);
    float thickness = mc_range_map(thickness_raw + 1.0, 0.0, 2.0, 0.0, 1.1);
    
    // Combine: (2.0 * pillar + rareness) * thickness^3
    float pillar_with_rareness = 2.0 * pillar_noise + rareness;
    float thickness_cubed = thickness * thickness * thickness;
    float result = pillar_with_rareness * thickness_cubed;
    
    return result;
}

// Cave density function 3: noodleToggle
// Y-limited gate for noodle cave presence
float mc_cave_noodle_toggle(float x, float y, float z) {
    if (router.nn_noodle < 0) {
        return -1.0;
    }
    
    const float NOODLE_Y_MIN = -60.0;
    const float NOODLE_Y_MAX = 320.0;
    
    if (y < NOODLE_Y_MIN || y >= NOODLE_Y_MAX) {
        return -1.0;
    }
    
    return mc_normal_noise(router.nn_noodle, x, y, z);
}

// Cave density function 4: noodleVal
// Full noodle caves with thickness and high-frequency ridge detail
float mc_cave_noodle_val(float x, float y, float z) {
    if (router.nn_noodle < 0 || router.nn_noodle_thickness < 0 || 
        router.nn_noodle_ridge_a < 0 || router.nn_noodle_ridge_b < 0) {
        return 64.0;
    }
    
    const float NOODLE_Y_MIN = -60.0;
    const float NOODLE_Y_MAX = 320.0;
    const float NOODLE_RIDGE_FREQ = 2.667;  // 8/3 for high-frequency detail
    
    // Sample toggle (presence/absence gate)
    float noodle_toggle = mc_y_limited_noise(router.nn_noodle, x, y, z,
                                             NOODLE_Y_MIN, NOODLE_Y_MAX, -1.0);
    
    // If toggle < 0, suppress noodle caves
    if (noodle_toggle < 0.0) {
        return 64.0;
    }
    
    // Sample thickness (only if toggle >= 0)
    float thickness_raw = mc_y_limited_noise(router.nn_noodle_thickness, x, y, z,
                                             NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    float thickness = mc_range_map(thickness_raw + 1.0, 0.0, 2.0, -0.05, -0.1);
    
    // Sample ridge noises at high frequency (2.667x scale)
    float ridge_a = mc_y_limited_noise(router.nn_noodle_ridge_a,
                                       x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,
                                       NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    float ridge_b = mc_y_limited_noise(router.nn_noodle_ridge_b,
                                       x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,
                                       NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    
    // Combine ridges: 1.5 * max(|ridgeA|, |ridgeB|)
    float noodle_ridged = 1.5 * max(abs(ridge_a), abs(ridge_b));
    
    // Final result: thickness + ridged
    return thickness + noodle_ridged;
}

// --- INSERT END ---

// ============================================================================
// STEP 3: Replace the cave carving section in computeFinalDensity()
// ============================================================================

// BEFORE (lines 408-450):
/*
    float cave_density_delta = 0.0;

    // Cheese / pillar caves (large spherical voids)
    if (router.nn_cheese_caves >= 0) {
        float cheese = mc_normal_noise(router.nn_cheese_caves,
                                       bx * 0.4, by * 0.8, bz * 0.4);
        // Threshold: cave where cheese > 0.03 (matches vanilla approx)
        float cave_val = cheese - 0.03;
        cave_density_delta = max(cave_density_delta, cave_val);
    }

    // Spaghetti tunnel caves (2D axis + 3D roughness perturbation)
    if (router.nn_spaghetti_2d >= 0) {
        float spaghetti_2d = mc_normal_noise(router.nn_spaghetti_2d, bx, 0.0, bz);
        float roughness = (router.nn_roughness >= 0)
            ? mc_normal_noise(router.nn_roughness, bx, by, bz) * 0.03
            : 0.0;
        // Spaghetti tube: carve near zero
        float spaghetti_tube = -abs(spaghetti_2d + roughness) + 0.03;
        cave_density_delta = max(cave_density_delta, spaghetti_tube);
    }

    // Cave entrances (vertical bores, scaled Y)
    if (router.nn_entrances >= 0) {
        float entrance = mc_normal_noise(router.nn_entrances, bx, by * 0.5, bz);
        float entrance_cave = -abs(entrance) + 0.05;
        cave_density_delta = max(cave_density_delta, entrance_cave);
    }

    // Noodle caves (thin, XZ-scaled)
    if (router.nn_noodle >= 0) {
        float noodle = mc_normal_noise(router.nn_noodle, bx * 1.5, by, bz * 1.5);
        float noodle_cave = -abs(noodle) + 0.02;
        cave_density_delta = max(cave_density_delta, noodle_cave);
    }

    // Apply cave carving: subtract cavity contribution then clamp to [-64, 64].
    // cave_density_delta > 0 → underground void; subtract it from solid density.
    float carved = squeezed - cave_density_delta;
    return clamp(carved * 64.0, -64.0, 64.0);
*/

// AFTER (new integrated version):
/*
    float cave_density_delta = 0.0;

    // Cheese / pillar caves — prefer advanced pillar version if available
    if (router.nn_pillar >= 0) {
        // Use advanced cheese caves with pillar/rareness/thickness modulation
        float cheese = mc_cave_cheesecaves(bx, by, bz);
        float cave_val = cheese - 0.03;  // Threshold at 0.03
        cave_density_delta = max(cave_density_delta, cave_val);
    } else if (router.nn_cheese_caves >= 0) {
        // Fallback to simplified version
        float cheese = mc_normal_noise(router.nn_cheese_caves,
                                       bx * 0.4, by * 0.8, bz * 0.4);
        float cave_val = cheese - 0.03;
        cave_density_delta = max(cave_density_delta, cave_val);
    }

    // Spaghetti tunnel caves — with improved roughness
    if (router.nn_spaghetti_2d >= 0) {
        float spaghetti_2d = mc_normal_noise(router.nn_spaghetti_2d, bx, 0.0, bz);
        
        // Try advanced roughness first, fallback to simple
        float roughness = (router.nn_spaghetti_roughness >= 0)
            ? mc_cave_roughness(bx, by, bz)  // NEW: advanced function
            : ((router.nn_roughness >= 0)
                ? mc_normal_noise(router.nn_roughness, bx, by, bz) * 0.03
                : 0.0);
        
        float spaghetti_tube = -abs(spaghetti_2d + roughness) + 0.03;
        cave_density_delta = max(cave_density_delta, spaghetti_tube);
    }

    // Cave entrances (unchanged — already well-tuned)
    if (router.nn_entrances >= 0) {
        float entrance = mc_normal_noise(router.nn_entrances, bx, by * 0.5, bz);
        float entrance_cave = -abs(entrance) + 0.05;
        cave_density_delta = max(cave_density_delta, entrance_cave);
    }

    // Noodle caves — prefer advanced version with toggle and ridges if available
    if (router.nn_noodle >= 0) {
        // Check if we have the full advanced noodle set
        if (router.nn_noodle_thickness >= 0 && router.nn_noodle_ridge_a >= 0) {
            // Use advanced noodle with thickness and ridges
            float noodle_val = mc_cave_noodle_val(bx * 1.5, by, bz * 1.5);
            float noodle_cave = -noodle_val + 0.02;  // Threshold at 0.02
            cave_density_delta = max(cave_density_delta, noodle_cave);
        } else {
            // Fallback to simple noodle
            float noodle = mc_normal_noise(router.nn_noodle, bx * 1.5, by, bz * 1.5);
            float noodle_cave = -abs(noodle) + 0.02;
            cave_density_delta = max(cave_density_delta, noodle_cave);
        }
    }

    // Apply cave carving: subtract cavity contribution then clamp to [-64, 64].
    // cave_density_delta > 0 → underground void; subtract it from solid density.
    float carved = squeezed - cave_density_delta;
    return clamp(carved * 64.0, -64.0, 64.0);
*/

// ============================================================================
// STEP 4: Update RouterConfig UBO struct (binding 8, std140)
// ============================================================================

// BEFORE (lines ~150-170):
/*
    // WS-4.1a: Cave noise NormalNoise indices (-1 = disabled / not yet wired)
    int nn_entrances;           // overworld/caves/entrances
    int nn_cheese_caves;        // overworld/caves/pillars
    int nn_spaghetti_2d;        // overworld/caves/spaghetti_2d
    int nn_roughness;           // overworld/caves/spaghetti_roughness_function
    int nn_noodle;              // overworld/caves/noodle
    int _pad4;
    int _pad5;
    int _pad6;
*/

// AFTER (with new WS-4.2 indices):
/*
    // WS-4.1a: Cave noise NormalNoise indices (-1 = disabled / not yet wired)
    int nn_entrances;           // overworld/caves/entrances
    int nn_cheese_caves;        // overworld/caves/pillars (simplified version)
    int nn_spaghetti_2d;        // overworld/caves/spaghetti_2d
    int nn_roughness;           // overworld/caves/spaghetti_roughness_function
    int nn_noodle;              // overworld/caves/noodle
    
    // WS-4.2: Advanced cave density functions (added indices)
    int nn_spaghetti_roughness;     // overworld/caves/spaghetti_roughness
    int nn_spaghetti_roughness_mod; // overworld/caves/spaghetti_roughness_modulator
    int nn_pillar;                  // overworld/caves/pillars (advanced)
    int nn_pillar_rareness;         // overworld/caves/pillar_rareness  
    int nn_pillar_thickness;        // overworld/caves/pillar_thickness
    int nn_noodle_thickness;        // overworld/caves/noodle_thickness
    int nn_noodle_ridge_a;          // overworld/caves/noodle_ridge_a
    int nn_noodle_ridge_b;          // overworld/caves/noodle_ridge_b
    // Padding handled by std140 (struct must be multiple of 16 bytes)
*/

// ---------------------
// RouterConfig size calculation:
// Before: 5 ints + 3 _pad ints = 8 ints = 32 bytes (2 × 16-byte alignment blocks)
// After:  5 + 8 = 13 ints
//         Needs 2 padding ints to reach 15 → 60 bytes → 4 blocks (64 bytes)
//
// In Java (ShadowRouterExtractor), update:
//   - struct UniformBuffer size: 112 → 128 bytes
//   - Add 8 new int fields to the UBO layout
// -----------

// ============================================================================
// SUMMARY OF CHANGES
// ============================================================================

/*
File: terrain_compute.comp

1. Add helper functions (2 functions, ~20 lines)
   - mc_range_map()
   - mc_y_limited_noise()
   Location: After mc_spline_eval() (around line 260)

2. Add 4 cave density functions (~120 lines)
   - mc_cave_roughness()
   - mc_cave_cheesecaves()
   - mc_cave_noodle_toggle()
   - mc_cave_noodle_val()
   Location: Before computeFinalDensity() (around line 290)

3. Replace cave carving section (~60 lines affected)
   - Update Spaghetti block to call mc_cave_roughness()
   - Update Cheese block to call mc_cave_cheesecaves()
   - Update Noodle block to call mc_cave_noodle_val()
   Location: Steps 408-450 in computeFinalDensity()

4. Update RouterConfig UBO struct (13 new ints)
   - Add 8 int fields for new NormalNoise indices
   - Adjust padding to maintain std140 alignment (→ 128 bytes)
   Location: Lines ~150-175

Total lines added: ~200
Total lines modified: ~60
Total file size increase: ~260 lines

Backward compatibility:
- All new functions branch on index checks (-1 = skip)
- Fallback to simplified versions available
- Existing code works with old router format (smaller struct)
- New router format can be phased in gradually
*/

