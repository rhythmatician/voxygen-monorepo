# Shadow Router: Bitwise Correctness Validation

## The Critical Issue: Sign-Bit Errors in Permutation Lookup

Your shader's `mc_grad_dot()` function indexes the gradient table via:

```glsl
int hash = permutation[index] & 0xF;  // Extract lower 4 bits
ivec3 grad = MC_GRADIENT[hash];       // Index 0-15
```

**The Java side must provide these `permutation` values as unsigned integers (0-255)**, not signed bytes (-128 to 127).

### Why This Matters

In Java, `ImprovedNoise.p` is declared as:
```java
private final byte[] p;  // SIGNED! Range: -128 to 127
```

When extracting to SSBO:
```java
byte signedByte = p[x & 0xFF];
int unsignedInt = ???;  // What goes here?
```

**WRONG:**
```java
int unsignedInt = (int) signedByte;  // -1 becomes 0xFFFFFFFF (sign-extended!)
```

**CORRECT:**
```java
int unsignedInt = signedByte & 0xFF;  // -1 becomes 0x000000FF (masked to 0-255)
```

### Failure Symptom

If you use the WRONG approach:
- **Java:** `byte p[5] = (byte)-15` → desired value is 241
- **Wrong conversion:** `(int)-15` → `0xFFFFFFF1` (32-bit signed)
- **Shader reads:** `0xFFFFFFF1 & 0xF` → hash = 1 (correct by accident!)
- **But:** `0xFFFFFFF1 >> 24` would give `-1` instead of `0xFF`

The hash value itself might be correct occasionally, but other bitwise operations (like deriving secondary indices) **will diverge**.

### Validation Check

**In your `extractImprovedPerms()`:**

```java
✅ CURRENT (CORRECT):
```java
byte[] perms = getFieldByteArray(improved, "p");
for (byte perm : perms) {
    buffer.put(perm & 0xFF);  // ← Correct masking
}
```

**Verification test:**
```java
byte[] testPerms = {0, 127, -1, -128};
for (byte b : testPerms) {
    System.out.println((b & 0xFF) + " should be [0, 127, 255, 128]");
}
// Output:
// 0 should be [0, 127, 255, 128]
// 127 should be [0, 127, 255, 128]
// 255 should be [0, 127, 255, 128]
// 128 should be [0, 127, 255, 128]
```

---

## Coordinate Wrapping: The 2^25 Threshold

Your shader's `mc_wrap()` function prevents precision loss at extreme coordinates:

```glsl
float mc_wrap(float x) {
    return mod(x + 33554432.0, 67108864.0) - 33554432.0;
}
```

This is a **faithful port** of Minecraft's:
```java
int ROUND_OFF = 0x2000000;  // = 33554432 in decimal
return (x + ROUND_OFF) % (2 * ROUND_OFF) - ROUND_OFF;
```

### Why This Matters

At extreme coordinates (e.g., X = 5,000,000):
- **Without wrapping:** Z-fights occur because float32 can't represent the difference between 5M + 1 and 5M + 2
- **With wrapping:** All coordinates are remapped to [-2^25, +2^25), where float32 has 1-unit precision

### Validation Check

**Test in Java:**
```java
double[] testCoords = {0, 1000, 1000000, 5000000};
for (double x : testCoords) {
    double wrapped = ((x + 33554432) % 67108864) - 33554432;
    System.out.println("x=" + x + " → wrapped=" + wrapped);
}
// Output should show all values in range [-33M, +33M]
```

**Test in GLSL:**
```glsl
void main() {
    float x = 5000000.0;
    float wrapped = mc_wrap(x);
    // wrapped should be in range [-33554432, 33554432]
    // Check that 2 calls with x and x+1 produce different results
}
```

---

## Float32 vs. Double64 Boundary Crossing

The most critical correctness issue occurs **at terrain generation boundaries** where density crosses zero:

```
CPU-side (double64):
  density = 0.999999999999  → clamp([-64, 64]) → 0.999...
  
GPU-side (float32):
  density = 0.9999999f     → clamp([-64, 64]) → "Did it go positive?"
```

If the GPU says yes and CPU says no (or vice versa), **you get visible cracks** between ghost chunks and real terrain.

### Mitigation Strategy

Two options:

**Option 1: Accept small FP32 drift (recommended initially)**
- Allow ±0.001 ULP (Units in Last Place) error
- Most terrain boundaries have sufficient margin that float→double doesn't flip the sign

**Option 2: Emulate double64 on GPU (future enhancement)**
- Store density as `float32 density_high + float32 density_low`  (FMA approach)
- Expensive but guarantees bitwise parity

### Validation Test

After implementing `computeFinalDensity()`:

```java
// CPU side
double cpuDensity = noiseRouter.finalDensity().compute(
    new DensityFunction.SinglePointContext(x, y, z));

// GPU side (dispatch compute shader, read back)
float gpuDensity = densityBuffer[compute_idx];

// Check that sign matches AND difference is small
assert Math.signum(cpuDensity) == Math.signum(gpuDensity);
assert Math.abs(cpuDensity - gpuDensity) < 0.01;  // Tolerance
```

---

## Checksum Validation

Add optional debug logging to verify SSBO contents:

```java
public void validateSSBOContents(ShadowRouterExtractor.ShadowRouterData data) {
    // Checksum improved permutations
    int checksum = 0;
    while (data.improvedPerms.hasRemaining()) {
        checksum += data.improvedPerms.get();
    }
    LOGGER.info("ImprovedPerms checksum: {} (0x{})", checksum, 
        Integer.toHexString(checksum));
    
    // Should be consistent across runs with same seed
}
```

---

## Validation Checklist

**Before claiming correctness:**

- [ ] **Permutation masking:** Verify `& 0xFF` is used in `extractImprovedPerms()`
- [ ] **No sign-extension:** Confirm no `(int)` cast without masking
- [ ] **Coordinate wrapping:** Test `mc_wrap()` produces values in [-33554432, 33554432]
- [ ] **SSBO content:** Dump and inspect first 256 values of `improved_perms[0]` (should be 0-255 mixed)
- [ ] **Gradient indices:** In shader, verify `hash & 0xF` produces 0-15, not negative
- [ ] **Density sign parity:** Run 100 random XYZ samples, check CPU vs GPU have same sign
- [ ] **Extreme coordinates:** Test world gen at X=±10,000,000, verify no crashes or z-fights

---

## Common Pitfalls

| Pitfall | Symptom | Fix |
|---------|---------|-----|
| Missing `& 0xFF` | Gradient indices wrap incorrectly, creating "tiled" artifacts | Add `& 0xFF` after byte read |
| Float32 vs Float64 | Cracks at chunk boundaries, visible seams | Use delta-tolerance in validation (<0.01) |
| Octave scaling mismatch | Terrain looks "stretched" or "squashed" | Verify `factor *= 2.0` in shader loop |
| Spline not implemented | Caves/ravines missing or incorrect | For now, use linear fallback; implement CubicSpline later |
| ShiftedNoise not implemented | Overworld slope/offset incorrect | Core NormalNoise works; ShiftedNoise is secondary |

---

## Next Validation Step

1. Implement a **side-by-side test harness:**
   - GPU: Dispatch compute shader for 16×16×384 block
   - CPU: Call `noiseRouter.finalDensity().compute()` for same coordinates
   - Compare outputs in debug view

2. **Accept success** if:
   - Density values differ by <0.01
   - Sign is always identical (no cracks at boundaries)
   - Performance is >50 FPS for view distance 16
