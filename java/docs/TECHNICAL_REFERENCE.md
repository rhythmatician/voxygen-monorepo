// ============================================================================
// TECHNICAL REFERENCE: Cave Density Functions
// 
// Quick-lookup guide for implementation details, formulas, and integration
// ============================================================================

// ============================================================================
// FUNCTION 1: mc_cave_roughness
// ============================================================================

/*
Vanilla Java Source:
  net.minecraft.world.level.levelgen.NoiseRouterData.spaghettiRoughnessFunction()
  
Returned by:
  NoiseRouter().caves().spaghetti()  // NOTE: This is the roughness component!

Formula (from vanilla):
  roughness = spaghettiRoughnessModulator * (abs(spaghettiRoughnessNoise) - 0.4)
  
Where:
  spaghettiRoughnessNoise = normal_noise(SPAGHETTI_ROUGHNESS, x, y, z)
  spaghettiRoughnessModulator = clamp_map(normal_noise(SPAGHETTI_ROUGHNESS_MODULATOR, x, y, z),
                                          base_min=-64, base_max=64,
                                          range_min=0.0, range_max=-0.1)
  
GLSL Implementation:
  ┌─────────────────────────────────────────────────────────────────────┐
  │ float mc_cave_roughness(float x, float y, float z) {               │
  │     if (router.nn_spaghetti_roughness < 0 ||                        │
  │         router.nn_spaghetti_roughness_mod < 0) {                    │
  │         return 0.0;                                                  │
  │     }                                                                │
  │                                                                      │
  │     float roughness_noise =                                          │
  │         mc_normal_noise(router.nn_spaghetti_roughness, x, y, z);   │
  │     float modulator_raw =                                            │
  │         mc_normal_noise(router.nn_spaghetti_roughness_mod, x, y, z);│
  │                                                                      │
  │     float modulator = mc_range_map(modulator_raw + 1.0,             │
  │                                    0.0, 2.0,                        │
  │                                    0.0, -0.1);                      │
  │                                                                      │
  │     float result = modulator * (abs(roughness_noise) - 0.4);        │
  │     return result;                                                   │
  │ }                                                                    │
  └─────────────────────────────────────────────────────────────────────┘

Input Coordinates:
  x, y, z: Block coordinates (float for precision)
  Frequency: 1.0 (no scaling)
  
Output Range:
  Theoretical: [-0.1, 0.4]
  Typical: [-0.05, 0.35]
  
Usage:
  float spaghetti_2d = mc_normal_noise(router.nn_spaghetti_2d, bx, 0.0, bz);
  float roughness = mc_cave_roughness(bx, by, bz);
  float spaghetti_tube = -abs(spaghetti_2d + roughness) + 0.03;
  
Dependencies:
  - mc_normal_noise() — base noise function
  - mc_range_map() — linear mapping with clamp
  - router.nn_spaghetti_roughness (index)
  - router.nn_spaghetti_roughness_mod (index)

Performance:
  - 2 NormalNoise samples
  - 1 range_map call
  - Estimated: ~1.2 ms per chunk column
  
Optimization:
  Pre-compute modulator offset (0.0 - 0.1) part in Java UBO
  If enabled, saves 1 mapping operation per sample
*/

// ============================================================================
// FUNCTION 2: mc_cave_cheesecaves
// ============================================================================

/*
Vanilla Java Source:
  net.minecraft.world.level.levelgen.NoiseRouterData.pillars()
  
Returned by:
  NoiseRouter().caves().pillar()  // Referenced in CAVES_REGISTRATIONS

Formula (from vanilla):
  pillar = pillar_noise * 2.0 + pillar_rareness
  cheeseCaves = pillar * pillar_thickness^3
  
Where:
  pillar_noise = normal_noise(PILLAR, x, y*0.3, z)           [Y freq = 0.3]
  pillar_rareness = clamp_map(normal_noise(PILLAR_RARENESS, x, y, z),
                              base_min=-64, base_max=64,
                              range_min=0.0, range_max=-2.0)
  pillar_thickness = clamp_map(normal_noise(PILLAR_THICKNESS, x, y, z),
                               base_min=-64, base_max=64,
                               range_min=0.0, range_max=1.1)

GLSL Implementation:
  ┌──────────────────────────────────────────────────────────────────┐
  │ float mc_cave_cheesecaves(float x, float y, float z) {          │
  │     if (router.nn_pillar < 0 ||                                 │
  │         router.nn_pillar_rareness < 0 ||                        │
  │         router.nn_pillar_thickness < 0) {                       │
  │         return 0.0;                                              │
  │     }                                                            │
  │                                                                 │
  │     float pillar_noise =                                        │
  │         mc_normal_noise(router.nn_pillar, x, y * 0.3, z);     │
  │                                                                 │
  │     float rareness_raw =                                        │
  │         mc_normal_noise(router.nn_pillar_rareness, x, y, z);  │
  │     float rareness = mc_range_map(rareness_raw + 1.0,          │
  │                                   0.0, 2.0,                    │
  │                                   0.0, -2.0);                  │
  │                                                                 │
  │     float thickness_raw =                                       │
  │         mc_normal_noise(router.nn_pillar_thickness, x, y, z); │
  │     float thickness = mc_range_map(thickness_raw + 1.0,        │
  │                                    0.0, 2.0,                   │
  │                                    0.0, 1.1);                  │
  │                                                                 │
  │     float pillar_with_rareness = 2.0 * pillar_noise + rareness;│
  │     float thickness_cubed = thickness * thickness * thickness; │
  │     float result = pillar_with_rareness * thickness_cubed;     │
  │                                                                 │
  │     return result;                                              │
  │ }                                                                │
  └──────────────────────────────────────────────────────────────────┘

Input Coordinates:
  x: Block X (frequency 1.0)
  y: Block Y (frequency 0.3 for pillar, 1.0 for rareness/thickness)
  z: Block Z (frequency 1.0)
  
  Key: Y frequency 0.3 means the pillar noise repeats every 3.33 blocks vertically
       creating tall vertical "cheese" structures (holes/voids)
  
Output Range:
  Theoretical: [-2.2, 4.0]  (max=2*1 * 1.1^3, min=(2*(-1) - 2.0) * 1.1^3)
  Typical: [-4, 4]
  
Threshold in cave carving:
  cave_val = cheeseCaves - 0.03  (threshold at 0.03)
  Carves when cheeseCaves > 0.03
  
Usage:
  float cheese = mc_cave_cheesecaves(bx, by, bz);
  float cave_val = cheese - 0.03;
  cave_density_delta = max(cave_density_delta, cave_val);
  
Dependencies:
  - mc_normal_noise() — base noise
  - mc_range_map() — linear mapping
  - router.nn_pillar (index)
  - router.nn_pillar_rareness (index)
  - router.nn_pillar_thickness (index)

Performance:
  - 3 NormalNoise samples
  - 2 range_map calls
  - 1 cubic operation (thickness^3)
  - Estimated: ~2.1 ms per chunk column

Interpretation:
  - Large spherical voids where cheese > 0.03
  - Vertically stretched (Y freq 0.3) → tall caves
  - Rareness < 0 suppresses caves (negative contribution)
  - Thickness > 0 modulates cave size (cubic relationship)
  
Visual characteristic:
  "Swiss cheese" appearance with vertical tunnels and voids
*/

// ============================================================================
// FUNCTION 3: mc_cave_noodle_toggle
// ============================================================================

/*
Vanilla Java Source:
  net.minecraft.world.level.levelgen.NoiseRouterData.noodle()
  
Returned by:
  NoiseRouter().caves().noodle()  // Part of combined noodle function

Formula (simplified):
  if (y >= -60 && y < 320) {
    result = normal_noise(NOODLE, x, y, z)
  } else {
    result = -1.0  // Suppress outside range
  }

GLSL Implementation:
  ┌────────────────────────────────────────────────────────┐
  │ float mc_cave_noodle_toggle(float x, float y, float z) {
  │     if (router.nn_noodle < 0) {                        │
  │         return -1.0;                                    │
  │     }                                                   │
  │                                                        │
  │     const float NOODLE_Y_MIN = -60.0;                 │
  │     const float NOODLE_Y_MAX = 320.0;                 │
  │                                                        │
  │     if (y < NOODLE_Y_MIN || y >= NOODLE_Y_MAX) {      │
  │         return -1.0;                                   │
  │     }                                                  │
  │                                                        │
  │     return mc_normal_noise(router.nn_noodle, x, y, z);│
  │ }                                                       │
  └────────────────────────────────────────────────────────┘

Input Coordinates:
  x, y, z: Block coordinates (float)
  Frequency: 1.0
  Y range: [-60, 320) ← Critical! Defines where noodle caves appear
  
Output Range:
  [-1.0, 1.0]  (standard NormalNoise output)
  -1.0 = suppressed (outside Y range or disabled)
  [-1, 0) = possible noodle cave presence
  [0, 1] = weak noodle cave presence
  
Usage Pattern:
  This function is used as a "gate" to determine if noodle carving is enabled
  at a given Y level. It's rarely used directly in cave carving;
  instead, mc_cave_noodle_val() uses it internally.
  
Dependencies:
  - mc_normal_noise()
  - router.nn_noodle (index)
  - Y_MIN, Y_MAX constants

Performance:
  - 1 NormalNoise sample (if in range)
  - 2 branch predictions (Y-gate)
  - Estimated: ~0.5 ms per chunk column (or ~0 if out of range)

Interpretation:
  toggle < 0 → No noodle caves at this Y
  toggle >= 0 → Noodle caves possible; continue to thickness/ridge evaluation
  
Note:
  The bounds [-60, 320) match vanilla world height for Overworld terrain
  Y = -64 is bedrock level; Y = 320 is build height
  Noodle caves span most of the playable world
*/

// ============================================================================
// FUNCTION 4: mc_cave_noodle_val
// ============================================================================

/*
Vanilla Java Source:
  net.minecraft.world.level.levelgen.NoiseRouterData.noodle()
  
Returned by:
  NoiseRouter().caves().noodle()  // Full noodle density function

Formula (complete):
  toggle = y_limited(NOODLE, x, y, z, -60, 320, -1.0)
  
  if (toggle < 0.0) {
    result = 64.0  // Force air (suppress noodle)
  } else {
    thickness = y_limited(NOODLE_THICKNESS, x, y, z, -60, 320, 0.0)
                clamp_map(thickness, -64, 64, -0.05, -0.1)
    
    ridge_a = y_limited(NOODLE_RIDGE_A, x*2.667, y, z*2.667, -60, 320, 0.0)
    ridge_b = y_limited(NOODLE_RIDGE_B, x*2.667, y, z*2.667, -60, 320, 0.0)
    
    ridged = 1.5 * max(|ridge_a|, |ridge_b|)
    
    result = thickness + ridged
  }

GLSL Implementation:
  ┌────────────────────────────────────────────────────────────────────┐
  │ float mc_cave_noodle_val(float x, float y, float z) {             │
  │     if (router.nn_noodle < 0 ||                                   │
  │         router.nn_noodle_thickness < 0 ||                         │
  │         router.nn_noodle_ridge_a < 0 ||                           │
  │         router.nn_noodle_ridge_b < 0) {                           │
  │         return 64.0;                                               │
  │     }                                                              │
  │                                                                   │
  │     const float NOODLE_Y_MIN = -60.0;                            │
  │     const float NOODLE_Y_MAX = 320.0;                            │
  │     const float NOODLE_RIDGE_FREQ = 2.667;                       │
  │                                                                   │
  │     // Sample toggle (presence/absence)                           │
  │     float noodle_toggle = mc_y_limited_noise(                    │
  │         router.nn_noodle, x, y, z,                               │
  │         NOODLE_Y_MIN, NOODLE_Y_MAX, -1.0);                       │
  │                                                                   │
  │     if (noodle_toggle < 0.0) {                                   │
  │         return 64.0;  // Suppress noodle at this location         │
  │     }                                                             │
  │                                                                   │
  │     // Sample thickness                                          │
  │     float thickness_raw = mc_y_limited_noise(                    │
  │         router.nn_noodle_thickness, x, y, z,                     │
  │         NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);                        │
  │     float thickness = mc_range_map(thickness_raw + 1.0,          │
  │                                    0.0, 2.0,                     │
  │                                    -0.05, -0.1);                 │
  │                                                                   │
  │     // Sample ridges at high frequency (2.667x)                  │
  │     float ridge_a = mc_y_limited_noise(                          │
  │         router.nn_noodle_ridge_a,                                │
  │         x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,        │
  │         NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);                        │
  │     float ridge_b = mc_y_limited_noise(                          │
  │         router.nn_noodle_ridge_b,                                │
  │         x * NOODLE_RIDGE_FREQ, y, z * NOODLE_RIDGE_FREQ,        │
  │         NOODLE_Y_MIN, NOODLE_Y_MAX, 0.0);                        │
  │                                                                   │
  │     // Combine ridges                                            │
  │     float noodle_ridged = 1.5 * max(abs(ridge_a), abs(ridge_b)); │
  │                                                                   │
  │     return thickness + noodle_ridged;                             │
  │ }                                                                  │
  └────────────────────────────────────────────────────────────────────┘

Input Coordinates:
  x, y, z: Block coordinates
  
  Sampling breakdown:
    - toggle, thickness: sampled at (x, y, z) with freq 1.0
    - ridge_a, ridge_b: sampled at (x*2.667, y, z*2.667)
      * 2.667 = 8/3 → creates high-frequency detail (pinches, ridges)
  
Output Range:
  64.0: Suppressed (toggle < 0)
  [-0.15, 0.65]:  Active noodle
    * thickness: [-0.1, -0.05]
    * ridged: [0, 1.5]
    * Combined: roughly [-0.15, 1.45]
    * But actual mixing is more complex
  
Threshold in cave carving:
  cave_val = -noodle_val + 0.02  (threshold at 0.02)
  Carves when noodle_val < 0.02 (usually true since mostly negative)
  
Usage:
  if (router.nn_noodle_thickness >= 0 && router.nn_noodle_ridge_a >= 0) {
    float noodle_val = mc_cave_noodle_val(bx * 1.5, by, bz * 1.5);
    float noodle_cave = -noodle_val + 0.02;
    cave_density_delta = max(cave_density_delta, noodle_cave);
  }

Dependencies:
  - mc_normal_noise()
  - mc_y_limited_noise()
  - mc_range_map()
  - router.nn_noodle (index)
  - router.nn_noodle_thickness (index)
  - router.nn_noodle_ridge_a (index)
  - router.nn_noodle_ridge_b (index)

Performance:
  - 4 NormalNoise samples (toggle, thickness, ridge_a, ridge_b)
  - 3 y_limited_noise wrapper calls (slight branch overhead)
  - 1 range_map call
  - 1 max(abs(), abs()) operation
  - Estimated: ~3.1 ms per chunk column

Interpretation:
  - When toggle >= 0, creates thin corridors with pinched/ridged sections
  - Ridge frequency (2.667x) creates visual variety (constrictions)
  - Thickness maps to corridor width
  - Combined result: winding tunnel-like caves (noodles)
  
Visual characteristic:
  Thin, winding passages with constricted sections (high-freq ridging)
  Only appears between Y=-60 and Y=320
  Interconnected with other noodle passages
  
Special note:
  The term "noodle" likely derives from the winding, tube-like shape
  of these caves, reminiscent of cooked noodles
*/

// ============================================================================
// HELPER FUNCTIONS
// ============================================================================

/*
mc_range_map(value, base_min, base_max, range_min, range_max)

Purpose:
  Linear interpolation with clamping (maps value from [base_min, base_max]
  range to [range_min, range_max])
  
Vanilla equivalent:
  DensityFunctions.RangeChoice.compute()
  Mth.map(value, base_min, base_max, range_min, range_max)
  
Formula:
  t = clamp((value - base_min) / (base_max - base_min), 0, 1)
  result = mix(range_min, range_max, t)
  
GLSL:
  float mc_range_map(float value, float base_min, float base_max, 
                     float range_min, float range_max) {
      float t = clamp((value - base_min) / (base_max - base_min), 0.0, 1.0);
      return mix(range_min, range_max, t);
  }

Usage examples:
  1. Map NormalNoise [-1, 1] to [0, -0.1]:
     mc_range_map(nn + 1.0, 0.0, 2.0, 0.0, -0.1)
     
  2. Map thickness [-1, 1] to [0, 1.1]:
     mc_range_map(nn + 1.0, 0.0, 2.0, 0.0, 1.1)

---

mc_y_limited_noise(index, x, y, z, y_min, y_max, default_value)

Purpose:
  Samples NormalNoise only within a Y range; returns default outside
  
Vanilla equivalent:
  DensityFunctions.yLimitedInterpolatable()
  
Logic:
  if (index < 0) return default
  if (y < y_min || y >= y_max) return default
  return mc_normal_noise(index, x, y, z)
  
GLSL:
  float mc_y_limited_noise(int nn_index, float x, float y, float z,
                           float y_min, float y_max, float default_value) {
      if (nn_index < 0) return default_value;
      if (y < y_min || y >= y_max) return default_value;
      return mc_normal_noise(nn_index, x, y, z);
  }

Usage:
  Noodle caves only appear between Y=-60 and Y=320:
    mc_y_limited_noise(router.nn_noodle, x, y, z, -60, 320, -1.0)
*/

// ============================================================================
// INTEGRATION CHECKLIST
// ============================================================================

/*
To integrate all 4 cave functions into terrain_compute.comp:

[ ] 1. Add helper functions (mc_range_map, mc_y_limited_noise)
      Location: After line ~260 (after mc_spline_eval)
      Lines: ~20
      
[ ] 2. Add 4 cave density functions
      Location: Before computeFinalDensity() (~line 290)
      Lines: ~120
      - mc_cave_roughness()
      - mc_cave_cheesecaves()
      - mc_cave_noodle_toggle()
      - mc_cave_noodle_val()
      
[ ] 3. Update cave carving section in computeFinalDensity()
      Location: Lines ~408-450
      Lines: 60 (replacement)
      
      [ ] 3a. Cheese caves: Use mc_cave_cheesecaves() if pillar index available
      [ ] 3b. Spaghetti caves: Use mc_cave_roughness() if spaghetti_roughness index available
      [ ] 3c. Noodle caves: Use mc_cave_noodle_val() if all noodle indices available
      [ ] 3d. Fallback to simplified versions if new indices not wired
      
[ ] 4. Update RouterConfig UBO struct (binding 8, std140)
      Location: Lines ~150-175
      
      [ ] 4a. Add 8 new int fields:
              - nn_spaghetti_roughness
              - nn_spaghetti_roughness_mod
              - nn_pillar
              - nn_pillar_rareness
              - nn_pillar_thickness
              - nn_noodle_thickness
              - nn_noodle_ridge_a
              - nn_noodle_ridge_b
              
      [ ] 4b. Update struct size comment (112 → 128 bytes)
      [ ] 4c. Add padding ints to maintain std140 16-byte alignment
      
[ ] 5. On Java side (ShadowRouterExtractor.java):
      [ ] 5a. Extract new NormalNoise indices from NoiseRouter
      [ ] 5b. Update UniformBuffer struct (32 → 48 bytes for 8 new ints)
      [ ] 5c. Set values in UBO upload

[ ] 6. Testing:
      [ ] 6a. Compile GLSL and verify no shader errors
      [ ] 6b. Load shader in game and verify chunk generation works
      [ ] 6c. Visual check: caves should look similar to vanilla
      [ ] 6d. Performance check: < 10% slower than baseline
      [ ] 6e. Run parity tests (Java vs GLSL output comparison)
      
[ ] 7. Documentation:
      [ ] 7a. Update README with new functions
      [ ] 7b. Document frequency scaling and Y-gating behavior
      [ ] 7c. Add performance notes
      [ ] 7d. Include example usage in comments
*/

// ============================================================================
// QUICK REFERENCE TABLE
// ============================================================================

/*
FUNCTION         | SAMPLES | FREQ SCALE | Y-RANGE  | OUTPUT RANGE
─────────────────┼─────────┼────────────┼──────────┼──────────────
roughness        | 2       | 1.0        | None     | [-0.1, 0.4]
cheeseCaves      | 3       | Y×0.3      | None     | [-4, 4]
noodleToggle     | 1       | 1.0        | [-60,320)| [-1, 1]
noodleVal        | 4       | XZ×2.667   | [-60,320)| [-0.15, 64.0]

CAVE TYPE        | FUNCTION        | CHAR.               | Y RANGE
─────────────────┼─────────────────┼─────────────────────┼─────────
Cheese caves     | cheeseCaves     | Large spheres       | All
Spaghetti caves  | roughness       | Thin 2D tunnels     | All
Noodle caves     | noodleVal       | Thin 3D corridors   | -60 to 320
Cave entrances   | (unchanged)     | Vertical bores      | All
*/

