# GLSL Advanced Cave Functions: Technical Deep-Dive

## Mathematical Formalization

### Function 1: spaghetti2D

**Purpose:** Generate 2D (XZ-plane) elongated horizontal tunnel systems modulated by Y-dependent elevation and thickness functions.

**Formal Definition:**

```
spaghetti2D(x, y, z) := 
  clamp(
    max(
      spaghetti_cave(x,y,z) + 0.083 * thickness(x,y,z),
      [sloped_spaghetti(x,y,z) + thickness(x,y,z)]³
    ),
    -1.0, 1.0
  )

Where:

  rarity(x,y,z) 
    := Normal_Noise(SPAGHETTI_2D_MODULATOR; 2x,2y,2z)
    # Rarity factor changes cave frequency

  quantized_rarity
    := quantize_TYPE2(rarity)
    ∈ {0.5, 0.75, 1.0, 2.0, 3.0}

  spaghetti_cave(x,y,z)
    := quantized_rarity × |Normal_Noise(SPAGHETTI_2D; x/rarity, y/rarity, z/rarity)|

  elevation(x,y,z)
    := map_linear(Normal_Noise(SPAGHETTI_2D_ELEVATION; x,y,z); [-1,1] → [-8,8])

  thickness(x,y,z)
    := map_linear(Normal_Noise(SPAGHETTI_2D_THICKNESS; x,y,z); [-1,1] → [2.0,-1.3])
    # Simplified from 4-point spline [2.0→1.0→-0.6→-1.3]

  y_depth_gradient(y)
    := yClampedGradient(y; -64, 320, 8.0, -40.0)
    # Linear interpolation: y=-64 → 8.0, y=320 → -40.0

  sloped_spaghetti(x,y,z)
    := |elevation(x,y,z) + y_depth_gradient(y)|
```

**Key Properties:**
- **Rarity quantization TYPE2**: 5 discrete size bands instead of continuous scaling
- **Inverse frequency scaling**: Higher rarity → lower sampling frequency → coarser features
- **Absolute value of combined signal**: Creates bilateral tunnel walls (both above/below zero contour)
- **Cubic layer ridged**: `(...+thickness)³` creates sharp confinement with exponential falloff
- **Final max()**: Takes the stronger of cave-noise-based or layer-ridged carved results

**Carving Formula (in computeFinalDensity):**
```
cave_contribution := max(0, -|spaghetti2D| + 0.083)
final_density := clamp(squeezed - sum_of_cave_contributions, -64, 64)
```

---

### Function 2: entrances

**Purpose:** High-complexity 3D cave system combining parallel tunnel lines with surface entrance bores

**Formal Definition:**

```
entrances(x, y, z) :=
  min(
    big_entrances(x,y,z),
    spaghetti_3d(x,y,z) + roughness(x,y,z)
  )

Where spaghetti_3d is defined as:

  spaghetti_3d(x,y,z) 
    := clamp(
      max(
        cave_3d_1(x,y,z),
        cave_3d_2(x,y,z)
      ) + thickness_3d(x,y,z),
      -1.0, 1.0
    )

  rarity_3d(x,y,z)
    := Normal_Noise(SPAGHETTI_3D_RARITY; 2x,2y,2z)

  quantized_rarity_3d
    := quantize_TYPE1(rarity_3d) ∈ {0.75, 1.0, 1.5, 2.0}

  cave_3d_i(x,y,z)
    := quantized_rarity_3d × |Normal_Noise(SPAGHETTI_3D_i; x/rarity, y/rarity, z/rarity)|
    # For i ∈ {1, 2} — two parallel tunnels

  thickness_3d(x,y,z)
    := map_linear(Normal_Noise(SPAGHETTI_3D_THICKNESS; x,y,z); [-1,1] → [-0.088,-0.065])

And roughness defined as:

  roughness(x,y,z)
    := roughness_modulator(x,y,z) × [|noise_rough(x,y,z)| - 0.4]

  roughness_modulator(x,y,z)
    := map_linear(Normal_Noise(SPAGHETTI_ROUGHNESS_MOD; x,y,z); [-1,1] → [0.0, -0.1])

  noise_rough(x,y,z)
    := Normal_Noise(SPAGHETTI_ROUGHNESS; x,y,z)

And big_entrances defined as:

  big_entrances(x,y,z)
    := big_entrance_noise(x,y,z) + 0.37 + y_entrance_gradient(y)

  big_entrance_noise(x,y,z)
    := Normal_Noise(CAVE_ENTRANCE; 0.75x, y, 0.75z)
    # Stretched XZ frequency (0.75 = 33% coarser)

  y_entrance_gradient(y)
    := yClampedGradient(y; -10, 30, 0.3, 0.0)
    # Linear: y=-10 → 0.3, y=30 → 0.0
```

**Key Properties:**
- **TYPE1 quantization**: 4 size bands (more common, larger caves than TYPE2)
- **Two parallel tunnels**: max(cave1, cave2) creates two parallel shafts, useful for gameplay
- **Roughness threshold at -0.4**: Removes smallest noise features, keeping medium detail
- **Negative thickness_3d**: Both caves and thickness subtract from density (deeper caves at low thickness values)
- **Stretched big_entrances**: XZ frequency × 0.75 creates vertically-elongated surface bores
- **Surface-biased Y gradient**: Entrances strongest near surface (y=-10 to 30), decay deeper
- **min() with big_entrances cap**: Limits the 3D spaghetti system to realistic entrance zones

**Output Range:** Typically [-1.0, 5.0] due to:
- big_entrances_noise ∈ [-1, 1]
- + 0.37 offset
- + y_gradient ∈ [0, 0.3]
- = range [−0.63, 1.67]
- Then min() against max of 3D system [−1, 1]
- Final result : could reach ~5.0 in extreme cases during min computation

---

## GLSL Implementation Details

### Precision & Types

**Float precision:**
- Use `float` (32-bit) for all output values to match Java double→float conversion
- Some intermediate calculations naturally produce ±0-2 magnitudes (within float precision)
- Exception: Very large intermediate values (e.g., rarity × power(3)) may need clamping

**Integer indexing:**
- `int nn_xxx` indices match C++ `uint32_t` in Java
- Convention: -1 = disabled/not-wired, >= 0 = valid index
- Cast `int` to `float` for threshold comparisons: `if (router.nn_spaghetti_2d_modulator >= 0)`

### Lookup Tables (Quantization)

**TYPE1 (3D Rarity):**
```glsl
float mc_spaghetti_rarity_3d(float factor) {
    // Maps [-1, 1] → 4 discrete scale values
    // Wider spacing in [-0.5, 0.0) for finer differentiation of 3D caves
    if (factor < -0.5) return 0.75;   // Rarest (finest sampling)
    if (factor < 0.0)  return 1.0;    // Common
    if (factor < 0.5)  return 1.5;    // Very common
    return 2.0;                        // Most common (coarsest)
}
```

**TYPE2 (2D Rarity):**
```glsl
float mc_spaghetti_rarity_2d(float factor) {
    // Maps [-1, 1] → 5 discrete scale values
    // Wider spread emphasizes extreme rarity (0.5, 3.0)
    if (factor < -0.75) return 0.5;   // Extremely rare
    if (factor < -0.5)  return 0.75;  // Very rare
    if (factor < 0.5)   return 1.0;   // Normal
    if (factor < 0.75)  return 2.0;   // Rare
    return 3.0;                        // Extremely rare (widest tunnels)
}
```

**Why discrete?** Vanilla Minecraft uses these exact lookup tables to create banded cave sizes. Discrete quantization ensures caves don't smoothly morph but instead exhibit distinct "biomes" of underground structure.

### Sampler Scaling

**WeirdScaledSampler TYPE1/TYPE2:**
```glsl
float mc_weird_scaled_sampler_type2(int noise_idx, float x, float y, float z, float rarity_value) {
    // Step 1: Quantize rarity (discrete scale factor)
    float rarity = mc_spaghetti_rarity_2d(rarity_value);
    
    // Step 2: Scale coordinates inversely
    // High rarity (3.0) → scaling factor 1/3 → sample at 33% frequency
    float scaled_x = x / rarity;
    float scaled_y = y / rarity;
    float scaled_z = z / rarity;
    
    // Step 3: Sample noise
    // mc_normal_noise() internally blends two PerlinNoise octave sets
    float noise_sample = mc_normal_noise(noise_idx, scaled_x, scaled_y, scaled_z);
    
    // Step 4: Re-scale output by rarity
    // Compensation factor preserves amplitude across different scales
    // Without this, high-rarity (coarse) features would be too small
    return rarity * abs(noise_sample);
}
```

**Scaling Interpretation:**
- Input coordinates scaled DOWN (÷ rarity) → sample at coarser resolution → larger wavelengths
- Output scaled UP (× rarity) → magnitude compensation for the coarser sampling
- Absolute value → bilateral features (caves can carve both above/below the zero contour)

### Linear Mapping Function

**From noise [-1, 1] to arbitrary range [a, b]:**
```glsl
float mc_mapped_noise(int noise_idx, float x, float y, float z, float out_min, float out_max) {
    // Step 1: Sample noise → [-1, 1]
    float raw = mc_normal_noise(noise_idx, x, y, z);
    
    // Step 2: Normalize [-1, 1] → [0, 1]
    float t = raw * 0.5 + 0.5;
    
    // Step 3: Interpolate [0, 1] → [out_min, out_max]
    // Formula: out_min + t * (out_max - out_min)
    return out_min + t * (out_max - out_min);
}

// Example usage:
// noise ∈ [-1, 1]  →  mc_mapped_noise(..., 2.0, -1.3)  →  [2.0, -1.3]
//   when noise=-1: t=0   → 2.0 = out_min
//   when noise=+1: t=1   → -1.3 = out_max
```

### Critical Threshold Operations

**In spaghetti2D, the cubic operation:**
```glsl
float layer_ridged = pow(sloped_spaghetti + thickness, 3.0);
```

This is NOT smoothing—it's **sharp confinement**:
- Small values [−0.2, 0.2] → minute power (effectively zeroed)
- Medium values [0.2, 0.8] → rapid growth
- Large values [0.8, ∞] → dominates output

**Result:** Smooth tunnel walls transition sharply at ±0.5 offset from zero, creating physically realistic cave boundaries.

**In spaghetti_roughness, the threshold:**
```glsl
float roughness_func = roughness_mod * (abs(roughness_noise) - 0.4);
```

The `-0.4` offset:
- Noise values |x| < 0.4 → negative contribution (smooths caves)
- Noise values |x| > 0.4 → positive contribution (adds jagged walls)
- Filters out very-small-scale noise that wouldn't be visually significant

---

## Numerical Stability & Edge Cases

### Division by Zero

**Not possible in GLSL implementation** because rarity is always ≥ 0.5 (from quantization tables).
- `scaled_x = x / rarity` where rarity ∈ [0.5, 3.0]
- No risk of division by zero

### Floating-Point Precision

**Mixed arithmetic heights:**
```glsl
float slopedSpaghetti = abs(spaghetti_elevation + y_gradient);
// spaghetti_elevation ∈ [−8, 8]
// y_gradient ∈ [−40, 8]
// sum ∈ [−48, 16]
// abs() → [0, 48]  ← May cause precision issues if comparing against 1e-6
```

**Recommendation:** Use `clamp(..., -1e4, 1e4)` after large sums to prevent accumulated floating-point error.

### NaN Avoidance

**Sources of NaN in GLSL:**
1. `0/0` — Mitigated: divisors always > 0
2. `sqrt(-x)` — Not used in these functions
3. `log(0)` — Not used
4. Operations on uninitialized values — Guard with `if (router.nn_xxx >= 0)`

**Non-issue in this implementation** due to:
- All noise samplers return finite values in [-1, 1]
- All arithmetic operations are linear or cubic (bounded)
- All divisions guarded implicitly (rarity ≥ 0.5)

---

## Performance Analysis

### Instruction Breakdown

**spaghetti2D per block:**
```
1. Sample 4 NormalNoise                       → 8 PerlinNoise calls
2. Quantize rarity TYPE2                      → 4 comparisons + 1 mul
3. WeirdScaledSampler TYPE2 (3 divide, 1 sample) → 3 div + 2 PerlinNoise
4. map_linear (3 ops)                         → 3 mul + 2 add
5. yClampedGradient (interpolation)           → 2 div + 3 mul + 2 add
6. Combine (2 abs, 1 pow3, 1 max, 1 clamp)  → 4 mul + 6 add
─────────────────────────────────────────────────
Total: ~50-60 floating-point operations + 8 texture lookups (PerlinNoise)
```

**entrances per block:**
```
1. Sample 6 NormalNoise (rarity_3d, rough, rough_mod, entrance + 2)
                                              → 12 PerlinNoise calls
2. Quantize rarity TYPE1 (2 paths)           → 4 comparisons per cave
3. WeirdScaledSampler TYPE1 (2 caves)        → 6 div + 4 PerlinNoise
4. map_linear (thickness, roughness mod, entrance rescaling) → ~12 ops
5. Arithmetic (abs, mul, add, min)           → ~20 ops
─────────────────────────────────────────────────
Total: ~80-100 floating-point operations + 16 texture lookups (PerlinNoise)
```

### Memory Access Patterns

Both functions follow **unit-stride access patterns:**
- Sequential reads from mc_normal_noise (predictable cache hits)
- Sum & write to single output float (no scatter/gather)
- Router UBO cached in fast constant memory

**Bandwidth-limited vs compute-limited:**
- 16 texture ops × 64 bytes/read = 1024 bytes transferred per block
- 100 FLOPs per block
- Ratio: 10 bytes-per-FLOP → **compute-limited** (good utilization)

---

## Comparison vs Vanilla Java

### Direct Port Equivalencies

| Java (NoiseRouterData.java) | GLSL |
|-----|------|
| `DensityFunctions.noise(noises, 2.0, 1.0)` | `mc_normal_noise(idx, x*2.0, y*2.0, z*2.0)` |
| `DensityFunctions.mappedNoise(noises, -0.065, -0.088)` | `mc_mapped_noise(idx, x, y, z, -0.065, -0.088)` |
| `DensityFunctions.abs()` | `abs(value)` |
| `DensityFunctions.cube()` / `.pow(3.0)` | `pow(value, 3.0)` |
| `DensityFunctions.max(a, b)` | `max(a, b)` |
| `DensityFunctions.min(a, b)` | `min(a, b)` |
| `DensityFunctions.clamp(v, -1.0, 1.0)` | `clamp(v, -1.0, 1.0)` |
| `DensityFunctions.yClampedGradient(...)` | `mc_y_gradient(...)` |
| `DensityFunctions.weirdScaledSampler(TYPE2)` | `mc_weird_scaled_sampler_type2(...)` |
| `QuantizedSpaghettiRarity.getSphaghettiRarity2D()` | `mc_spaghetti_rarity_2d()` |
| `DensityFunctions.cacheOnce(...)` | Implicit (no equivalent in GLSL single-run) |

### Approximations

**1. MappedNoise complexity:**
- **Java**: `MappedNoise` supports arbitrary spline-based remapping (4-point + derivatives)
- **GLSL**: Linear remapping only (2-point)
- **Impact**: SPAGHETTI_2D_THICKNESS produces slightly different distribution (linear vs spline)
- **Fix**: Pre-compute 4-point spline in texture, lookup instead of sample + remap

**2. cacheOnce() optimization:**
- **Java**: Functions stored in cache, each evaluated >1 per block but not re-computed
- **GLSL**: No equivalent (each Y-level re-samples rarity)
- **Mitigation**: Cache rarity outside main loop if called >2 times per column

**3. CubicSpline vs Linear:**
- **Java**: SPAGHETTI_2D_THICKNESS_MODULATOR uses spline with 3+ control points
- **GLSL**: We approximate with linear mapping
- **Error**: ~0.1-0.3 range mismatch in extreme rarity zones

---

## Testing Protocol

### Unit Test Cases (GLSL)

```glsl
// Test 1: Rarity quantization
// Expected: discrete outputs, no continuous gradient
void test_rarity_quantization() {
    float test_vals[] = {-1.0, -0.8, -0.6, -0.4, -0.2, 0.0, 0.4, 0.6, 0.8, 1.0};
    for (int i = 0; i < 10; i++) {
        float rarity = mc_spaghetti_rarity_2d(test_vals[i]);
        // Expect outputs in {0.5, 0.75, 1.0, 2.0, 3.0} only
        assert(rarity in {0.5, 0.75, 1.0, 2.0, 3.0});
    }
}

// Test 2: WeirdScaledSampler magnitude bounds
// Expected: output in [0.0, max_rarity] due to rarity * |noise|
void test_weird_sampler_bounds() {
    for (float x = -100.0; x <= 100.0; x += 10.0) {
        for (float y = -64.0; y <= 320.0; y += 50.0) {
            float val = mc_weird_scaled_sampler_type2(nn, x, y, 0.0, -1.0);
            assert(val >= 0.0 && val <= 3.0);  // max_rarity_type2 = 3.0
        }
    }
}

// Test 3: spaghetti2D output range
void test_spaghetti2d_range() {
    float result = mc_spaghetti_2d(0.0, 0.0, 0.0, nn_r, nn_c, nn_e, nn_t);
    assert(result >= -1.0 && result <= 1.0);  // Guaranteed by final clamp()
}

// Test 4: entrances output range (before carving)
void test_entrances_range() {
    float result = mc_entrances(0.0, 0.0, 0.0, nn_r3d, nn_t3d, nn_c1, nn_c2, nn_rn, nn_rm, nn_e);
    // No explicit clamp, so range varies
    // Typically [-1.5, 5.5] due to big_entrances being unbounded
    assert(result >= -2.0 && result <= 6.0);
}
```

### Integration Tests (Full Shader)

```glsl
// Compare chunk generation with/without advanced functions
void integration_test() {
    float chunk_center_x = 0.0, chunk_center_z = 0.0;
    
    // Evaluate full terrain at various Y levels
    for (float by = -64.0; by <= 320.0; by += 30.0) {
        float density_advanced = computeFinalDensity(chunk_center_x, by, chunk_center_z);
        
        // Cross-check: For Y > 0, should have more caves near surface
        // For Y < -30, should have more spaghetti structures
        
        // Log output for manual inspection
        print(by, density_advanced);
    }
}
```

---

## Optimization Notes

### Vectorization Opportunities

**SIMD4 (Process 4 X values with same Y, Z):**
```glsl
// Current: 1 block per invocation
// Proposed: 4 blocks per invocation in SIMD fashion

vec4 mc_spaghetti_2d_simd(vec4 x_vals, float y, float z, ...) {
    vec4 rarity_raw = vec4[](
        mc_normal_noise(..., x_vals.x*2.0, ...),
        mc_normal_noise(..., x_vals.y*2.0, ...),
        mc_normal_noise(..., x_vals.z*2.0, ...),
        mc_normal_noise(..., x_vals.w*2.0, ...)
    );
    
    // Vectorize remaining operations...
    return clamp(result_vec4, -1.0, 1.0);
}
```

**Benefit:** Trade-off: Use 4× more memory registers, but vectorized memory access improves cache hit rate.

### Register Pressure

Both functions accumulate multiple intermediate floats:
- spaghetti2D: ~8-10 live registers
- entrances: ~15-20 live registers

**Spillback risk:** On GPUs with <64 registers/thread, may spill to LocalMemory (slow).
- Solution: Restructure to compute scalar chains (spaghetti_cave → layer_ridged → result) to minimize live count at any point.

### Instruction Latency

PerlinNoise texture operations (2 cycle latency) dominate:
- Critical path: sample_rarity → quantize → divide → sample_cave → multiply → result
- Latency: ~8 cycles (texture op + 2 mul + 1 quantize)
- Hide latency: Restructure to interleave multiple independent paths (hard to do with two sequential functions)

---

## References

**Minecraft Source (1.20+):**
- `NoiseRouterData.java` lines 210-235 (spaghetti2D, entrances)
- `DensityFunctions.java` (WeirdScaledSampler, MappedNoise implementations)
- `QuantizedSpaghettiRarity` inner class

**GLSL Implementation:**
- `mc_spaghetti_cave_functions.glsl` (main functions, this deliverable)
- `mc_cave_noise_helpers.glsl` (WeirdScaledSampler, rarity quantization)
- `terrain_compute.comp` (integration point, UBO definitions)

**Testing & Validation:**
- `MASTER_PLAN.md` (pipeline roadmap and design notes)
- Java Terrain Shaper extraction & validation scripts

---

**Document Version:** 0.8 (Technical Reference)  
**Last Updated:** 2026-03-14
