// ============================================================================
// CAVE DENSITY FUNCTIONS — GLSL Implementation
// Porting from Minecraft Java NoiseRouterData to GLSL compute shader
//
// Functions implemented:
//   1. roughness() — SPAGHETTI_ROUGHNESS_MODULATOR mapped noise
//   2. cheeseCaves() — PILLAR noises with rareness/thickness modulation
//   3. noodleToggle() — Y-limited NOODLE gate
//   4. noodleVal() — Full NOODLE with thickness and ridge ridged
//
// Integration point: terrain_compute.comp, Step 9 (cave carving section)
// ============================================================================

// ---------------------------------------------------------------------------
// PART 1: ROUTER STRUCT ADDITIONS
// ---------------------------------------------------------------------------
// Add these fields to the RouterConfig UBO (binding 8, std140):
//
//    // Previously existing (WS-4.1a)
//    int nn_entrances;               // overworld/caves/entrances
//    int nn_cheese_caves;            // overworld/caves/pillars (simplified current)
//    int nn_spaghetti_2d;            // overworld/caves/spaghetti_2d
//    int nn_roughness;               // overworld/caves/spaghetti_roughness_function
//    int nn_noodle;                  // overworld/caves/noodle
//
//    // NEW for advanced cave carving (WS-4.2)
//    int nn_spaghetti_roughness;     // overworld/caves/spaghetti_roughness
//    int nn_spaghetti_roughness_mod; // overworld/caves/spaghetti_roughness_modulator
//    int nn_pillar;                  // overworld/caves/pillars
//    int nn_pillar_rareness;         // overworld/caves/pillar_rareness
//    int nn_pillar_thickness;        // overworld/caves/pillar_thickness
//    int nn_noodle_thickness;        // overworld/caves/noodle_thickness
//    int nn_noodle_ridge_a;          // overworld/caves/noodle_ridge_a
//    int nn_noodle_ridge_b;          // overworld/caves/noodle_ridge_b
//
// In RouterConfig struct, add after existing nn_noodle:
//    int nn_spaghetti_roughness;
//    int nn_spaghetti_roughness_mod;
//    int nn_pillar;
//    int nn_pillar_rareness;
//    int nn_pillar_thickness;
//    int nn_noodle_thickness;
//    int nn_noodle_ridge_a;
//    int nn_noodle_ridge_b;
//    int _pad_new1;   // padding for std140 alignment (if needed)
//

// ---------------------------------------------------------------------------
// PART 2: HELPER FUNCTIONS
// ---------------------------------------------------------------------------

// mc_range_map: linear interpolation with clamping (vanilla DensityFunctions.RangeChoice)
// Maps input from [base_min, base_max] to [range_min, range_max]
// If input < base_min, returns range_min; if > base_max, returns range_max
float mc_range_map(float value, float base_min, float base_max, 
                   float range_min, float range_max) {
    float t = clamp((value - base_min) / (base_max - base_min), 0.0, 1.0);
    return mix(range_min, range_max, t);
}

// mc_y_limited_noise: samples a NormalNoise only within a Y range, else returns default
// Corresponds to vanilla DensityFunctions.yLimitedInterpolatable
// Returns default_value if y is outside [y_min, y_max)
float mc_y_limited_noise(int nn_index, float x, float y, float z,
                         float y_min, float y_max, float default_value) {
    if (nn_index < 0) return default_value;
    
    // Guard: if y is outside the valid range, return default
    if (y < y_min || y >= y_max) {
        return default_value;
    }
    
    // Sample the noise within range
    return mc_normal_noise(nn_index, x, y, z);
}

// ---------------------------------------------------------------------------
// PART 3: CAVE DENSITY FUNCTIONS
// ---------------------------------------------------------------------------

// 1. ROUGHNESS: Spaghetti roughness modulation
// From: net.minecraft.world.level.levelgen.NoiseRouterData.spaghettiRoughnessFunction()
//
// Logic:
//   roughnessNoise = sample SPAGHETTI_ROUGHNESS at (x, y, z)
//   modulatorNoise = sample SPAGHETTI_ROUGHNESS_MODULATOR at (x, y, z)
//                    → mapped from [base_min, base_max] to [0.0, -0.1]
//   result = modulator * (abs(roughness) - 0.4)
//
// Output range: approx [-0.1, 0.4]
// Usage: applied to spaghetti_2d cave carving as perturbation
float mc_cave_roughness(float x, float y, float z) {
    // Check if indices are wired
    if (router.nn_spaghetti_roughness < 0 || router.nn_spaghetti_roughness_mod < 0) {
        return 0.0;  // Disabled; no roughness perturbation
    }
    
    // Sample both noises (both using freq 1.0)
    float roughness_noise = mc_normal_noise(router.nn_spaghetti_roughness, x, y, z);
    float modulator_raw = mc_normal_noise(router.nn_spaghetti_roughness_mod, x, y, z);
    
    // Map modulator from [-1, 1] (typical) to [0.0, -0.1]
    // Vanilla uses base_min=-64, base_max=64 in the range choice, but since
    // NormalNoise output is typically [-1, 1], we map that directly:
    float modulator = mc_range_map(modulator_raw + 1.0, 0.0, 2.0, 0.0, -0.1);
    
    // Compute: modulator * (abs(roughness) - 0.4)
    float result = modulator * (abs(roughness_noise) - 0.4);
    
    return result;
}

// 2. CHEESE CAVES: Pillar-based cave carving (complex version)
// From: net.minecraft.world.level.levelgen.NoiseRouterData.pillars()
//        (referred to as "cheese" caves in vanilla context)
//
// Logic:
//   pillar = sample PILLAR at (x, 0.3*y, z)  [Y frequency 0.3 = stretched vertically]
//   rareness = sample PILLAR_RARENESS at (x, y, z) → mapped to [0.0, -2.0]
//   thickness = sample PILLAR_THICKNESS at (x, y, z) → mapped to [0.0, 1.1]
//   
//   pillar_with_rareness = 2.0 * pillar + rareness
//   result = pillar_with_rareness * (thickness ^ 3)  // cubic thickness modulation
//
// Output range: approx [-4, 4]
// These caves form tall vertical "cheese" structures (holes/voids)
float mc_cave_cheesecaves(float x, float y, float z) {
    // Check if indices are wired
    if (router.nn_pillar < 0 || router.nn_pillar_rareness < 0 || 
        router.nn_pillar_thickness < 0) {
        return 0.0;  // Disabled; no cheese caves
    }
    
    // Sample pillar noise at reduced Y frequency (0.3 means y/0.3 = 3.33x vertical stretch)
    float pillar_noise = mc_normal_noise(router.nn_pillar, x, y * 0.3, z);
    
    // Sample rareness modulator
    float rareness_raw = mc_normal_noise(router.nn_pillar_rareness, x, y, z);
    // Map from [-1, 1] to [0.0, -2.0]: higher values → more negative (suppress caves)
    float rareness = mc_range_map(rareness_raw + 1.0, 0.0, 2.0, 0.0, -2.0);
    
    // Sample thickness modulator
    float thickness_raw = mc_normal_noise(router.nn_pillar_thickness, x, y, z);
    // Map from [-1, 1] to [0.0, 1.1]
    float thickness = mc_range_map(thickness_raw + 1.0, 0.0, 2.0, 0.0, 1.1);
    
    // Compute: (2.0 * pillar + rareness) * thickness^3
    float pillar_with_rareness = 2.0 * pillar_noise + rareness;
    float thickness_cubed = thickness * thickness * thickness;
    float result = pillar_with_rareness * thickness_cubed;
    
    return result;
}

// 3. NOODLE TOGGLE: Y-gated gate for noodle cave presence
// From: net.minecraft.world.level.levelgen.NoiseRouterData.noodle() (partial)
//
// Logic:
//   if (y >= -60 && y < 320) {
//       result = sample NOODLE at (x, y, z)
//   } else {
//       result = -1.0  // Suppress noodle caves outside range
//   }
//
// Output range: [-1.0, 1.0]
// This acts as a presence/absence gate; when < 0, noodle ridges are ignored
float mc_cave_noodle_toggle(float x, float y, float z) {
    // Check if index is wired
    if (router.nn_noodle < 0) {
        return -1.0;  // Disabled; noodle caves suppressed everywhere
    }
    
    // Y range: [-60, 320) — outside these bounds, suppress layer
    const float NOODLE_Y_MIN = -60.0;
    const float NOODLE_Y_MAX = 320.0;
    
    if (y < NOODLE_Y_MIN || y >= NOODLE_Y_MAX) {
        return -1.0;  // Outside valid Y range
    }
    
    // Sample the toggle noise
    float toggle = mc_normal_noise(router.nn_noodle, x, y, z);
    return toggle;
}

// 4. NOODLE VAL: Full noodle caves with thickness and ridges
// From: net.minecraft.world.level.levelgen.NoiseRouterData.noodle() (full)
//
// Logic:
//   noodle_toggle = y_limited(NOODLE, x, y, z, -60, 320, -1.0)
//   
//   noodle_thickness = y_limited(NOODLE_THICKNESS, x, y, z, -60, 320, 0.0)
//                      mapped to [-0.05, -0.1]
//   
//   ridge_a = y_limited(NOODLE_RIDGE_A, x*2.667, y, z*2.667, -60, 320, 0.0)
//   ridge_b = y_limited(NOODLE_RIDGE_B, x*2.667, y, z*2.667, -60, 320, 0.0)
//   
//   noodle_ridged = 1.5 * max(abs(ridge_a), abs(ridge_b))
//   
//   if (noodle_toggle < 0.0) {
//       result = 64.0  // Large value = force air (no cave)
//   } else {
//       result = noodle_thickness + noodle_ridged
//   }
//
// Output range: [-1.0, 64.0]
// Coordinates are sampled at frequency 2.667 for ridge detection (high-freq detail)
float mc_cave_noodle_val(float x, float y, float z) {
    // Check if all indices are wired (all required for full functionality)
    if (router.nn_noodle < 0 || router.nn_noodle_thickness < 0 || 
        router.nn_noodle_ridge_a < 0 || router.nn_noodle_ridge_b < 0) {
        return 64.0;  // Disabled; suppress noodle caves everywhere
    }
    
    const float NOODLE_Y_MIN = -60.0;
    const float NOODLE_Y_MAX = 320.0;
    const float NOODLE_RIDGE_FREQ = 2.667;  // High-frequency sampling for ridges
    
    // Sample toggle (determines presence/absence)
    float noodle_toggle = mc_y_limited_noise(router.nn_noodle, x, y, z,
                                             NOODLE_Y_MIN, NOODLE_Y_MAX, -1.0);
    
    // If toggle is negative, noodle caves are absent at this location
    if (noodle_toggle < 0.0) {
        return 64.0;  // Large value forces air (no carving)
    }
    
    // Sample thickness (only used if toggle >= 0)
    float thickness_raw = mc_y_limited_noise(router.nn_noodle_thickness, x, y, z,
                                             NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    // Map from [-1, 1] to [-0.05, -0.1]
    float thickness = mc_range_map(thickness_raw + 1.0, 0.0, 2.0, -0.05, -0.1);
    
    // Sample ridge A at high frequency (2.667x = 1/0.375)
    float ridge_a = mc_y_limited_noise(router.nn_noodle_ridge_a,
                                       x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,
                                       NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    
    // Sample ridge B at high frequency (same XZ freq)
    float ridge_b = mc_y_limited_noise(router.nn_noodle_ridge_b,
                                       x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,
                                       NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);
    
    // Combine ridges: take max absolute value and scale by 1.5
    float noodle_ridged = 1.5 * max(abs(ridge_a), abs(ridge_b));
    
    // Final noodle value: thickness + ridged
    float result = thickness + noodle_ridged;
    
    return result;
}

// ============================================================================
// PART 4: INTEGRATION INTO CAVE CARVING SECTION
// ============================================================================

// Replace the existing cave carving section (lines 408-450) with:
/*
    float cave_density_delta = 0.0;

    // Cheese / pillar caves (large spherical voids) — IMPROVED VERSION
    if (router.nn_pillar >= 0) {
        float cheese = mc_cave_cheesecaves(bx, by, bz);
        // Threshold: cave where cheese > 0.03
        float cave_val = cheese - 0.03;
        cave_density_delta = max(cave_density_delta, cave_val);
    } else if (router.nn_cheese_caves >= 0) {
        // Fallback to simplified version if pillar not wired
        float cheese = mc_normal_noise(router.nn_cheese_caves,
                                       bx * 0.4, by * 0.8, bz * 0.4);
        float cave_val = cheese - 0.03;
        cave_density_delta = max(cave_density_delta, cave_val);
    }

    // Spaghetti tunnel caves (2D axis + roughness perturbation) — IMPROVED VERSION
    if (router.nn_spaghetti_2d >= 0) {
        float spaghetti_2d = mc_normal_noise(router.nn_spaghetti_2d, bx, 0.0, bz);
        
        // Roughness: can be computed via improved function or simple version
        float roughness = (router.nn_spaghetti_roughness >= 0)
            ? mc_cave_roughness(bx, by, bz)
            : ((router.nn_roughness >= 0)
                ? mc_normal_noise(router.nn_roughness, bx, by, bz) * 0.03
                : 0.0);
        
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

    // Noodle caves (thin, XZ-scaled) — IMPROVED VERSION with toggle and ridges
    if (router.nn_noodle >= 0) {
        // Check if we have the full advanced noodle set
        if (router.nn_noodle_thickness >= 0 && router.nn_noodle_ridge_a >= 0) {
            // Use advanced noodle with thickness and ridges
            float noodle_val = mc_cave_noodle_val(bx * 1.5, by, bz * 1.5);
            float noodle_cave = -noodle_val + 0.02;
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
// PART 5: VALIDATION NOTES
// ============================================================================

/*
VALIDATION STRATEGY:
1. Output Range Checks:
   - mc_cave_roughness: [-0.1, 0.4] (when scaled by spaghetti_2d modulation)
   - mc_cave_cheesecaves: [-4, 4] (before 0.03 threshold)
   - mc_cave_noodle_toggle: [-1, 1] (NormalNoise output)
   - mc_cave_noodle_val: [-1, 64] (64 = suppressed, negative = carving)

2. Java-GLSL Parity Testing:
   a) Extract noise values at specific (x, y, z) from both Java and GLSL
   b) Verify NormalNoise outputs match (within float precision ~1e-5)
   c) Compare final cave_density_delta values
   
3. Edge Cases:
   a) Y out of range for noodle functions → should return default (-1.0 or 0.0)
   b) All indices = -1 (disabled) → functions should gracefully degrade
   c) Frequency scaling: NOODLE_RIDGE_FREQ = 2.667 ≈ 8/3 (test coordinate transforms)
   d) Mapping edges: thickness/rareness at min/max NormalNoise values

4. Visual Validation:
   a) Cheese caves should form large vertical "pillar holes" (Y freq 0.3 effect)
   b) Spaghetti roughness should add 3D perturbation to 2D tunnels
   c) Noodle caves should be thin horizontal/vertical corridors below Y=320
   d) Roughness modulation should suppress caves in certain areas (rareness effect)

5. Benchmark Noise Distribution:
   - Profile cache hits for NormalNoise SSBO lookups
   - Measure total GPU cycles per block for all 4 functions combined
   - Expected: ~10-15 microseconds per block column on modern GPU
*/

// ============================================================================
// PART 6: PERFORMANCE ANALYSIS
// ============================================================================

/*
PERFORMANCE CHARACTERISTICS:

Function              | Noise Samples | GPU Cost (relative) | Notes
----------------------|---------------|---------------------|-------
roughness             | 2             | 1.0x                | 2 NormalNoise
cheeseCaves           | 3             | 1.5x                | 3 NormalNoise + mapping
noodleToggle          | 1             | 0.5x                | 1 NormalNoise + Y gate
noodleVal             | 4             | 2.5x                | 4 NormalNoise + 2 mappings
----------------------|---------------|---------------------|-------
TOTAL (all 4)         | 10            | 5.5x baseline       | ~10 NormalNoise samples

Baseline (original): 4 simple cave NormalNoise samples = 1.0x

Impact: Adding all 4 functions ~5.5x nominal increase
        But: Functions are branched on index checks (-1 = skip)
        
Cache Behavior:
  - NormalNoise SSBO reads: 32 bytes per access (float)
  - All functions use same (x, y, z) block → same L1 cache line
  - First access: ~200 cycles (miss)
  - Subsequent accesses: ~3 cycles (cache hit)
  - Expected: 2-3 NormalNoise accesses hit cache per function
  
Optimization Opportunities:
  1. Branch prediction: Index checks (-1) are likely uniform across work group
  2. Frequency scaling: Pre-compute x*2.667, y*0.3, etc. if same per column
  3. LDS optimization: Shared memory for duplicate index lookups (future work)
  
Estimated Wall Clock per Block:
  Modern GPU (NVIDIA/AMD 2020+), single block (16x384 voxels):
  - Original 4 caves: ~500 microseconds
  - With 4 functions: ~2000-2500 microseconds
  - Overhead: ~0.5-1.0 milliseconds per chunk column (16x384 blocks)
  
Memory Bandwidth:
  - 10 NormalNoise samples × 32 bytes = 320 bytes per column
  - Overlapped with other texture/buffer reads (shift_a, cheese, etc.)
  - Expected: <2% of total domain memory BW (not a bottleneck)
*/

