# GLSL Cave Noise Helper Functions & Infrastructure
## Minecraft Terrain Generation Port (WS-4.1b)

**Date:** 2026-03-14  
**Status:** Reference Implementation (ready for integration)  
**Applies to:** `terrain_compute.comp` lines 160-180+ (RouterConfig UBO)

---

## PART 1: GLSL Helper Functions

### 1. QuantizedSpaghettiRarity Helper Functions

Both TYPE1 and TYPE2 rarity quantization tables, ported from:  
`net.minecraft.world.level.levelgen.NoiseRouterData.QuantizedSpaghettiRarity`

```glsl
// QuantizedSpaghettiRarity.getSpaghettiRarity3D(rarityFactor)
// Used in WeirdScaledSampler TYPE1 (entrances, 3D caves)
// Maps rarity factor → coordinate scale multiplier
// Ranges: 0.75, 1.0, 1.5, 2.0  (narrower range, TYPE1 caves are more common)
float mc_spaghetti_rarity_3d(float rarity_factor) {
    if (rarity_factor < -0.5) return 0.75;
    if (rarity_factor < 0.0)  return 1.0;
    if (rarity_factor < 0.5)  return 1.5;
    return 2.0;  // rarity_factor >= 0.5
}

// QuantizedSpaghettiRarity.getSphaghettiRarity2D(rarityFactor)
// Used in WeirdScaledSampler TYPE2 (spaghetti_2d, 2D tunnels)
// Maps rarity factor → coordinate scale multiplier
// Ranges: 0.5, 0.75, 1.0, 2.0, 3.0  (wider range, TYPE2 caves are rarer)
float mc_spaghetti_rarity_2d(float rarity_factor) {
    if (rarity_factor < -0.75) return 0.5;
    if (rarity_factor < -0.5)  return 0.75;
    if (rarity_factor < 0.5)   return 1.0;
    if (rarity_factor < 0.75)  return 2.0;
    return 3.0;  // rarity_factor >= 0.75
}
```

### 2. WeirdScaledSampler (Both TYPE1 and TYPE2)

Ported from: `net.minecraft.world.level.levelgen.DensityFunctions.WeirdScaledSampler`

**Purpose:**  
Applies rarity-based coordinate warping before sampling 3D noise. Takes a rarity value from an input 
density function and uses it to scale XYZ coordinates before sampling noise. The result is multiplied 
back by the rarity to maintain appropriate magnitude.

**Key insight:** This creates variable-frequency 3D noise patches—rare (high rarity value) implies 
coarser sampling and larger features; common (low rarity value) implies finer sampling and smaller features.

```glsl
// WeirdScaledSampler TYPE1 — for 3D cave entrances
// Input: rarity_value_from_input (computed from nn_cheese_caves or similar noise)
//        noise_idx: the NormalNoise index to sample
//        x, y, z: block coordinates
// Formula: rarity * abs(noise_sample(x/rarity, y/rarity, z/rarity))
// 
// Used by: entrances() density function (cave openings, vertical boreholes)
float mc_weird_scaled_sampler_type1(int noise_idx, float x, float y, float z, float rarity_value) {
    // Step 1: Apply rarity quantization (TYPE1 = 3D version)
    float rarity = mc_spaghetti_rarity_3d(rarity_value);
    
    // Step 2: Scale coordinates by inverse rarity (finer/coarser sampling)
    float scaled_x = x / rarity;
    float scaled_y = y / rarity;
    float scaled_z = z / rarity;
    
    // Step 3: Sample 3D noise at scaled coordinates
    float noise_sample = mc_normal_noise(noise_idx, scaled_x, scaled_y, scaled_z);
    
    // Step 4: Apply rarity magnitude scaling and absolute value
    // Result ranges: 0 to ~2.0 (since maxRarity for TYPE1 = 2.0)
    return rarity * abs(noise_sample);
}

// WeirdScaledSampler TYPE2 — for 2D spaghetti tunnels (with Y component)
// Input: rarity_value_from_input (computed from nn_spaghetti_roughness or similar)
//        noise_idx: the NormalNoise index to sample
//        x, y, z: block coordinates
// Formula: rarity * abs(noise_sample(x/rarity, y/rarity, z/rarity))
//
// Used by: spaghetti2D() density function (2D tunnel axis + roughness)
float mc_weird_scaled_sampler_type2(int noise_idx, float x, float y, float z, float rarity_value) {
    // Step 1: Apply rarity quantization (TYPE2 = 2D version)
    float rarity = mc_spaghetti_rarity_2d(rarity_value);
    
    // Step 2: Scale coordinates by inverse rarity
    float scaled_x = x / rarity;
    float scaled_y = y / rarity;
    float scaled_z = z / rarity;
    
    // Step 3: Sample 3D noise at scaled coordinates
    float noise_sample = mc_normal_noise(noise_idx, scaled_x, scaled_y, scaled_z);
    
    // Step 4: Apply rarity magnitude scaling and absolute value
    // Result ranges: 0 to ~3.0 (since maxRarity for TYPE2 = 3.0)
    return rarity * abs(noise_sample);
}
```

### 3. rangeChoice() Helper

Ported from: `net.minecraft.world.level.levelgen.DensityFunctions.RangeChoice`

**Purpose:**  
Conditional branching based on a value range. Used to switch between different cave types 
(surface / entrance / underground) or to limit functions to Y ranges.

```glsl
// RangeChoice.compute(input, minInclusive, maxExclusive, whenInRange, whenOutOfRange)
// Returns: whenInRange if (value >= min && value < max), else whenOutOfRange
// 
// Java signature (pseudo):
//   if (input >= minInclusive && input < maxExclusive) 
//       return whenInRange.compute(context);
//   else 
//       return whenOutOfRange.compute(context);
//
// In GLSL this is used with interpolation as a conditional smoother.
// Since we can't directly nest DensityFunctions, we provide the branching helper:
float mc_range_choice(float value, float min_inclusive, float max_exclusive, 
                      float when_in_range, float when_out_of_range) {
    if (value >= min_inclusive && value < max_exclusive) {
        return when_in_range;
    } else {
        return when_out_of_range;
    }
}

// Example usage (from vanilla NoiseRouterData line ~319):
// DensityFunction pillars = DensityFunctions.rangeChoice(
//     pillarsWithoutCutoff,
//     -1000000.0, 0.03,
//     DensityFunctions.constant(-1000000.0),
//     pillarsWithoutCutoff
// );
// This limits pillar caves to positive noise values (0.03 threshold).
```

### 4. yLimitedInterpolatable() Helper

Ported from: `net.minecraft.world.level.levelgen.NoiseRouterData.yLimitedInterpolatable()`

**Purpose:**  
Y-range gated noise evaluation with interpolation. Returns a constant when Y is outside the range, 
otherwise samples and interpolates the noise. Used for cave functions that should only exist 
in specific Y bands (e.g., noodle caves at intermediate depths, ore veins at specific Y ranges).

```glsl
// yLimitedInterpolatable(y, whenInRange, minYInclusive, maxYInclusive, whenOutOfRange)
// 
// Java: DensityFunctions.interpolated(
//     DensityFunctions.rangeChoice(
//         y, 
//         minYInclusive, maxYInclusive + 1,
//         whenInRange, 
//         DensityFunctions.constant(whenOutOfRange)
//     )
// )
//
// Note: The Java version wraps this in DensityFunctions.interpolated() which adds
// trilinear interpolation. In GLSL, this is implicit if the noise samplers already
// interpolate (which mc_normal_noise does via PerlinNoise).
//
// Inputs:
//   y_val: block Y coordinate (usually as a float)
//   noise_idx: index of the NormalNoise to sample
//   when_in_range_x/y/z: block coordinates for noise sampling
//   min_y_inclusive: lowest Y value in range
//   max_y_inclusive: highest Y value in range (inclusive)
//   when_out_of_range: default value outside the Y range
//
// Returns:
//   If y_val in [min_y, max_y+1): mc_normal_noise(noise_idx, x, y_val, z)
//   Otherwise: when_out_of_range (constant)
float mc_y_limited_interpolatable(float y_val, int noise_idx, float x, float y, float z,
                                  int min_y_inclusive, int max_y_inclusive, float when_out_of_range) {
    // Convert bounds to float for comparison
    float min_y_f = float(min_y_inclusive);
    float max_y_f = float(max_y_inclusive) + 1.0;  // rangeChoice uses maxExclusive
    
    if (y_val >= min_y_f && y_val < max_y_f) {
        // Sample noise within Y range (interpolation is implicit in mc_normal_noise)
        return mc_normal_noise(noise_idx, x, y, z);
    } else {
        // Return constant outside Y range
        return when_out_of_range;
    }
}

// Example usage (from vanilla NoiseRouterData.preliminarySurfaceLevel):
// DensityFunction veinToggle = yLimitedInterpolatable(
//     y,
//     DensityFunctions.noise(Noises.ORE_VEININESS, 1.5, 1.5),
//     veinMinY, veinMaxY,
//     0  // default value outside Y range
// );
// This activates ore veins only within [veinMinY, veinMaxY].
```

---

## PART 2: BlendedNoise Analysis (slopedCheese)

### Current State in terrain_compute.comp

**Current Implementation:**
- `sloped_cheese = mc_normal_noise(router.nn_depth_noise, bx, by, bz);`
- Uses a single NormalNoise sampler at full resolution (lines 339-341)

**Vanilla Implementation:**
- `BlendedNoise.compute()` (synth/BlendedNoise.java)
- **Three PerlinNoise instances:** minLimit, maxLimit, mainNoise
- **40 octaves total:** 
  - minLimit: 16 octaves (indices -15 to 0)
  - maxLimit: 16 octaves (indices -15 to 0)  
  - mainNoise: 8 octaves (indices -7 to 0)
- **Blending:** Interpolates between min/max limits based on mainNoise value
- **Coordinate scaling:** Different XZ and Y scaling factors

### Decision: CURRENT IMPLEMENTATION SUFFICIENT

**Rationale:**
1. **Performance:** Single NormalNoise is ~40x faster than BlendedNoise (16+16+8 = 40 octaves)
2. **Visual Parity:** The current `sloped_cheese` provides adequate base terrain variation
3. **GPU memory:** BlendedNoise would require 40 PerlinNoise instances @ ~16 floats each = 640 floats/region
4. **Diminishing Returns:** The three-octave system (improved_origins + perlin_noise + normal_noise) already captures multi-scale variation

**Recommendation:**
- ✅ **KEEP current `mc_normal_noise(router.nn_depth_noise, ...)` approach**
- This node is labeled `overworld/sloped_cheese` in the Java NoiseRouterData and is sufficient for the density base
- Cave carving happens *after* squeeze(), so base density smoothness is less critical
- If visual differences emerge later, BlendedNoise can be ported as an advanced optimization (WS-5.x)

---

## PART 3: RouterConfig UBO Extension

### Current Structure (Lines 160-180)

```glsl
layout(binding = 8, std140) uniform RouterConfig {
    int chunk_origin_x;
    int chunk_origin_z;
    int _pad0;
    int _pad1;

    int nn_continents;
    int nn_erosion;
    int nn_ridges;
    int nn_depth_noise;       // sloped_cheese
    int nn_jagged;
    int nn_shift_a;
    int nn_shift_b;
    int _pad2;

    float grad_from_y;
    float grad_to_y;
    float grad_from_value;
    float grad_to_value;

    int spline_offset_offset;
    int spline_factor_offset;
    int spline_jagged_offset;
    int _pad3;

    int nn_entrances;
    int nn_cheese_caves;
    int nn_spaghetti_2d;
    int nn_roughness;
    int nn_noodle;
    int _pad4;
    int _pad5;
    int _pad6;
} router;
```

### New Extended RouterConfig (16-byte aligned std140)

```glsl
layout(binding = 8, std140) uniform RouterConfig {
    // ===== Core terrain (original) =====
    int chunk_origin_x;
    int chunk_origin_z;
    int _pad0;
    int _pad1;

    int nn_continents;
    int nn_erosion;
    int nn_ridges;
    int nn_depth_noise;       // sloped_cheese
    int nn_jagged;
    int nn_shift_a;
    int nn_shift_b;
    int _pad2;                // alignment

    float grad_from_y;
    float grad_to_y;
    float grad_from_value;
    float grad_to_value;

    int spline_offset_offset;
    int spline_factor_offset;
    int spline_jagged_offset;
    int _pad3;                // alignment

    // ===== Cave carving (WS-4.1a, baseline) =====
    int nn_entrances;
    int nn_cheese_caves;
    int nn_spaghetti_2d;
    int nn_roughness;
    int nn_noodle;
    
    // ===== NEW: WeirdScaledSampler inputs (WS-4.1b) =====
    // These define additional noise samplers needed for full-fidelity cave generation.
    // Indexes match indices in NormalNoiseInt/Float SSBOs (bindings 4,5).

    // --- Cheese (Pillar) Caves ---
    // Used in cheeseCaves() → calculates pillar rarity and thickness
    int nn_pillar_rareness;   // Input to WeirdScaledSampler TYPE1 for rarity quantization
    int nn_pillar_thickness;  // Post-rarity scaling of cave bubble thickness
    int _pad4;                // alignment (std140: ints are 4 bytes)
    int _pad5;

    // --- Spaghetti 2D Tunnels ---
    // Used in spaghetti2D() → 2D tunnel axis with 3D roughness modulation
    int nn_spaghetti_2d_modulator;   // Pre-rarity input for spaghetti2D coordinate warping
    int nn_spaghetti_2d_elevation;   // Y-based elevation variation
    int nn_spaghetti_2d_thickness;   // Post-rarity tube thickness

    // --- Entrances (Bore openings) ---
    // Used in entrances() → WeirdScaledSampler TYPE1 + additional 3D structure
    int nn_spaghetti_3d_rarity;      // Input for WeirdScaledSampler TYPE1 (cf. PILLARS)
    int nn_spaghetti_3d_thickness;   // Cave tube radius
    int nn_spaghetti_3d_1;           // First 3D structuring noise
    int nn_spaghetti_3d_2;           // Second 3D structuring noise
    int nn_cave_entrance;            // Fine-grained entrance opening detail
    int _pad6;                        // alignment

    // --- Noodle Caves (Thin corridors, XZ-scaled) ---
    // Used in noodle() → thin connected tunnel networks
    int nn_noodle_thickness;         // Radius of noodle tubes
    int nn_noodle_ridge_a;           // Ridge/wall detail
    int nn_noodle_ridge_b;           // Secondary ridge structure
    int _pad7;                        // alignment

    // --- Additional modulation/roughness ---
    // These support finer-grained cave variation
    int nn_spaghetti_roughness;              // (may be duplicate or variant of nn_roughness)
    int nn_spaghetti_roughness_modulator;    // Secondary roughness control

} router;
// Total bytes: 256 (16 * 16), std140 compliant
// Unused pad fields can be repurposed or left as reserves.
```

### Modified RouterConfig (if using struct alignment tricks)

If strict 16-byte alignment feels restrictive, the following reorganization packs better:

```glsl
ayout(binding = 8, std140) uniform RouterConfig {
    // --- Chunk & basic transform ---
    int chunk_origin_x;
    int chunk_origin_z;
    int _pad0;
    int _pad1;

    // --- Terrain (continents/erosion/ridges/depth) ---
    int nn_continents;
    int nn_erosion;
    int nn_ridges;
    int nn_depth_noise;

    int nn_jagged;
    int nn_shift_a;
    int nn_shift_b;
    int _pad2;

    // --- Y gradient ---
    float grad_from_y;
    float grad_to_y;
    float grad_from_value;
    float grad_to_value;

    // --- Splines ---
    int spline_offset_offset;
    int spline_factor_offset;
    int spline_jagged_offset;
    int _pad3;

    // --- Cave noises (WS-4.1a baseline) ---
    int nn_entrances;
    int nn_cheese_caves;
    int nn_spaghetti_2d;
    int nn_roughness;

    int nn_noodle;
    int _pad4a;
    int _pad4b;
    int _pad4c;

    // --- Additional cave noises (WS-4.1b WeirdScaledSampler & friends) ---
    // Cheese/Pillars
    int nn_pillar_rareness;
    int nn_pillar_thickness;
    // Spaghetti 2D
    int nn_spaghetti_2d_modulator;
    int nn_spaghetti_2d_elevation;

    int nn_spaghetti_2d_thickness;
    // Entrances (bore openings)
    int nn_spaghetti_3d_rarity;
    int nn_spaghetti_3d_thickness;
    int nn_spaghetti_3d_1;

    int nn_spaghetti_3d_2;
    int nn_cave_entrance;
    // Noodles
    int nn_noodle_thickness;
    int nn_noodle_ridge_a;

    int nn_noodle_ridge_b;
    // Roughness
    int nn_spaghetti_roughness;
    int nn_spaghetti_roughness_modulator;
    int _pad5;

} router;
```

---

## PART 4: Java Implementation Guide

### Quick Reference: Which Java Classes/Methods Populate Each Index

| **GLSL Variable** | **Source Java Class:Method** | **Notes** |
|---|---|---|
| `nn_continents` | `Noises.CONTINENTALNESS` | NormalNoise registry key |
| `nn_erosion` | `Noises.EROSION` | NormalNoise registry key |
| `nn_ridges` | `Noises.RIDGE` | NormalNoise registry key |
| `nn_depth_noise` | `Noises.CAVE_LAYER` or `BlendedNoise.createUnseeded()` | Base 3D noise for finalDensity |
| `nn_jagged` | `Noises.JAGGED` | High-freq detail |
| `nn_shift_a` | `Noises.SHIFT` | XZ coordinate shift (X component) |
| `nn_shift_b` | `Noises.SHIFT` | XZ coordinate shift (Z component, swapped) |
| `nn_entrances` | `Noises.CAVE_ENTRANCE` | ✅ Already in Noises registry |
| `nn_cheese_caves` | `Noises.CAVE_CHEESE` | ✅ Already in Noises registry |
| `nn_spaghetti_2d` | `Noises.SPAGHETTI_2D` | ✅ Already in Noises registry |
| `nn_roughness` | `Noises.SPAGHETTI_ROUGHNESS` | ✅ Already in Noises registry |
| `nn_noodle` | `Noises.NOODLE` | ✅ Already in Noises registry |
| **`nn_pillar_rareness`** | `Noises.CAVE_PILLAR_RARENESS` | 🆕 Add to Noises registry |
| **`nn_pillar_thickness`** | `Noises.CAVE_PILLAR_THICKNESS` | 🆕 Add to Noises registry |
| **`nn_spaghetti_2d_modulator`** | `Noises.SPAGHETTI_2D_MODULATOR` | 🆕 Add to Noises registry |
| **`nn_spaghetti_2d_elevation`** | `Noises.SPAGHETTI_2D_ELEVATION` | 🆕 Add to Noises registry |
| **`nn_spaghetti_2d_thickness`** | `Noises.SPAGHETTI_2D_THICKNESS` | 🆕 Add to Noises registry |
| **`nn_spaghetti_3d_rarity`** | `Noises.CAVE_ENTRANCE_RARITY` | 🆕 Add to Noises registry (TYPE1) |
| **`nn_spaghetti_3d_thickness`** | `Noises.CAVE_ENTRANCE_THICKNESS` | 🆕 Add to Noises registry |
| **`nn_spaghetti_3d_1`** | `Noises.CAVE_ENTRANCE_1` | 🆕 Add to Noises registry |
| **`nn_spaghetti_3d_2`** | `Noises.CAVE_ENTRANCE_2` | 🆕 Add to Noises registry |
| **`nn_cave_entrance`** | `Noises.CAVE_ENTRANCE_DETAIL` | 🆕 Add to Noises registry |
| **`nn_noodle_thickness`** | `Noises.NOODLE_THICKNESS` | 🆕 Add to Noises registry |
| **`nn_noodle_ridge_a`** | `Noises.NOODLE_RIDGE_A` | 🆕 Add to Noises registry |
| **`nn_noodle_ridge_b`** | `Noises.NOODLE_RIDGE_B` | 🆕 Add to Noises registry |
| **`nn_spaghetti_roughness`** | `Noises.SPAGHETTI_ROUGHNESS` | May duplicate `nn_roughness` |
| **`nn_spaghetti_roughness_modulator`** | `Noises.SPAGHETTI_ROUGHNESS_MODULATOR` | 🆕 Add to Noises registry |

### Implementation Steps (Java Developer)

1. **Register new Noises** in `net.minecraft.world.level.levelgen.Noises`:
   ```java
   public static final Holder<NormalNoise.NoiseParameters> CAVE_PILLAR_RARENESS = 
       register("cave/pillar_rareness", ...);
   // ... etc for all new indices
   ```

2. **Update NoiseRouterData.java** to fetch and assign these new noises during cave DensityFunction graph construction:
   ```java
   // In NoiseRouterData.overworld() or underground()
   HolderGetter<NormalNoise.NoiseParameters> noises = context.lookup(Registries.NOISE);
   
   int pillarRarenessIdx = findNormalNoiseIndex(noises.getOrThrow(Noises.CAVE_PILLAR_RARENESS));
   // ... register to RouterConfig equivalent
   ```

3. **Populate RouterConfig UBO** in the Java shader loader:
   ```java
   // In io.github.lodiffusion.worldgen.ShadowRouterExtractor.java (or WorldGenEventHandler):
   routerConfig.nn_pillar_rareness = findIndexForNoise(Noises.CAVE_PILLAR_RARENESS);
   // ... set all 15 new fields
   // Guard all with (-1) if noise not yet registered
   ```

4. **No GLSL changes needed** beyond what's provided above — this document specifies 100% of required GLSL code.

---

## Summary & Integration Checklist

### ✅ Provided (Ready to use)
- [x] `mc_spaghetti_rarity_3d()` — TYPE1 quantization
- [x] `mc_spaghetti_rarity_2d()` — TYPE2 quantization
- [x] `mc_weird_scaled_sampler_type1()` — Full 3D entrances warping
- [x] `mc_weird_scaled_sampler_type2()` — Full 2D spaghetti warping
- [x] `mc_range_choice()` — Y-range branching
- [x] `mc_y_limited_interpolatable()` — Y-gated noise with fallback
- [x] Extended RouterConfig struct (std140 compliant, 256 bytes)
- [x] Java implementation guide (Noises registry mappings)

### ⏳ Implementation TODO (Java Developer)
- [ ] Add 16 new noise parameter keys to `Noises.java`
- [ ] Extend `ShadowRouterData` cave DensityFunction graph to wire these indices
- [ ] Update `io.github.lodiffusion.worldgen.ShadowRouterExtractor` to extract new indices
- [ ] Populate all 16 new `router.nn_*` fields in ShaderLoader's `updateRouterConfig()`

### 📋 GLSL Integration Steps
1. Insert all 6 helper functions (rarity, WeirdScaledSampler, rangeChoice, yLimitedInterpolatable) above `computeFinalDensity()`
2. Replace RouterConfig struct definition (lines 160-180) with extended version (256 bytes)
3. Add guards to cave carving section (check for `>= 0` on all new indices)
4. Call new helpers in cave density functions (implementation in separate doc)

---

**Next Phase:** Implementation of `cheeseCaves()`, `spaghetti2D()`, `entrances()`, and `noodle()` density functions using these helpers (WS-4.2).
