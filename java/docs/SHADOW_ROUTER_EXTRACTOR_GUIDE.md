# ShadowRouterExtractor Integration Guide

## Overview

### What is the Shadow Router?

Minecraft's terrain generation is controlled by the **`NoiseRouter`** — a graph of 15+ `DensityFunction` nodes that compute terrain density for every block position on the CPU.
The **shadow router** is our GPU-side mirror of that same computation: a set of GLSL compute shaders (`improved_noise.glsl`, `perlin_noise.glsl`, `normal_noise.glsl`, `terrain_compute.comp`) that reproduce vanilla terrain density in parallel on the GPU, enabling far-LOD generation without per-chunk CPU world-gen.

### Role of `ShadowRouterExtractor`

`ShadowRouterExtractor` is the Java-side initialization stage of the shadow router pipeline. It is the critical bridge between Minecraft's CPU-side `NoiseRouter` and the GPU compute shaders: it implements the **Visitor pattern** to walk the `DensityFunction` expression tree and extract all noise parameters into GPU-uploadable SSBOs.

---

## Architecture: Two-Phase Extraction

### Phase 1: Graph Traversal
```
NoiseRouter (15 DensityFunction nodes)
    └─ mapAll(Visitor)
        └─ ShadowRouterExtractor walks the tree
            ├─ Discovers all unique NormalNoise instances
            ├─ Extracts parent PerlinNoise instances
            └─ Extracts parent ImprovedNoise octaves
```

The visitor pattern ensures the extraction is a single linear pass over the graph, with deduplication via `IdentityHashMap`.

### Phase 2: Data Serialization
```
Discovered Instances
    ├─ ImprovedNoise (permutation tables + origin offsets)
    ├─ PerlinNoise (octave indices + amplitudes)
    ├─ NormalNoise (perlin pair indices + blend factor)
    └─ CubicSpline (control point lists)
        ↓
    Flattened into SSBOs
        ├─ BINDING 0: improved_origins (vec3 per instance)
        ├─ BINDING 1: improved_perms (256 ints per instance)
        ├─ BINDING 2: perlin_ints ([firstOctave, indices...])
        ├─ BINDING 3: perlin_floats ([factors, amplitudes...])
        ├─ BINDING 4: normal_noise_ints ([idx1, idx2])
        ├─ BINDING 5: normal_noise_floats ([valueFactor])
        └─ BINDING 6: spline_data (flattened control points)
```

---

## Usage in Your Mod

### Step 1: Extract at World Load
```java
// In your world event handler (e.g., ServerLevelEvent.Load)
public static void onWorldLoad(ServerLevelEvent.Load event) {
    ServerLevel level = event.getLevel();
    NoiseRouter router = level.getChunkSource().getGenerator()
        .getFirstFitGenerator().noiseRouter();
    
    ShadowRouterExtractor extractor = new ShadowRouterExtractor();
    ShadowRouterExtractor.ShadowRouterData data = extractor.extract(router);
    
    // Now upload to GPU (see Step 2)
    uploadSSBOsToGPU(data);
}
```

### Step 2: Upload SSBOs to GPU
```java
public void uploadSSBOsToGPU(ShadowRouterExtractor.ShadowRouterData data) {
    // Pseudo-code (actual implementation depends on your rendering backend)
    
    glBindBuffer(GL_COPY_WRITE_BUFFER, ssbo[0]);  // improved_origins
    glBufferData(GL_COPY_WRITE_BUFFER, data.improvedOrigins, GL_STATIC_DRAW);
    
    glBindBuffer(GL_COPY_WRITE_BUFFER, ssbo[1]);  // improved_perms
    glBufferData(GL_COPY_WRITE_BUFFER, data.improvedPerms, GL_STATIC_DRAW);
    
    // ... similarly for all 6 other SSBOs ...
}
```

### Step 3: Dispatch Compute Shader
```glsl
// In your compute shader dispatch (16x16 workgroup per chunk column)
glDispatchCompute(
    (width / 16),     // Number of chunk columns in width
    1,                // No Y dispatch (Y loop is per-thread)
    (height / 16)     // Number of chunk columns in depth
);
```

---

## Critical Correctness Points

### 1. Bitwise Permutation Lookup
The `ImprovedNoise.p` array stores **signed bytes** (-128 to 127), but shader accesses them with **unsigned semantics** (0-255):

```java
// Java side (byte to unsigned int)
byte signedByte = perms[i];
int unsignedInt = signedByte & 0xFF;  // Critical: & 0xFF masks to 0-255
```

```glsl
// GLSL side (already unsigned)
int perm = perms[idx] & 0xFF;  // Still masked for safety
```

**Failure symptom:** Gradient table indexing wraps incorrectly, creating "seams" in terrain.

### 2. DoubleList → FloatBuffer Conversion
PerlinNoise stores amplitudes in a fastutil `DoubleList` (not a plain `double[]`). The extractor uses reflection to access via `getDouble(int)`:

```java
// DoubleList.getDouble(i) must be called via reflection
Method getDoubleMethod = amplitudesObj.getClass().getMethod("getDouble", int.class);
double amplitude = (double) getDoubleMethod.invoke(amplitudesObj, i);
```

**Why reflection?** The actual type is `it.unimi.dsi.fastutil.doubles.DoubleList`, which isn't directly importable in your package.

### 3. IdentityHashMap for Deduplication
The extractor uses `IdentityHashMap` (based on `System.identityHashCode()`) rather than `HashMap` (based on `equals()`/`hashCode()`):

```java
Map<NormalNoise, Integer> noiseIndexMap = new IdentityHashMap<>();
```

**Why?** Minecraft's noise objects don't override `equals()`, so two conceptually identical noises created in different ways would be treated as equal by `HashMap`, causing index collisions.

---

## Missing: Spline Serialization

The current implementation has a **TODO** for `CubicSpline` flattening. To complete this:

1. **Collect control points** from each discovered spline
2. **Flatten into a single float array** with explicit layout:
   ```
   [spline0_point0_x, spline0_point0_y, spline0_point0_z,
    spline0_point1_x, spline0_point1_y, spline0_point1_z, ...]
   ```
3. **Track offsets** in `splineOffsets` map for shader indexing

This is a medium-complexity task that can be deferred if initial testing works with linear interpolation fallback.

---

## Expected Performance

**Extraction cost (one-time per world load):**
- Reflection overhead: ~50ms (acceptable, happens once at startup)
- GPU upload (glBufferData): ~100-200ms (depends on total noise instance count)
- **Total: <500ms for typical world**

**Per-frame compute dispatch:**
- 16×1×16 workgroup for one chunk column
- All 384 Y levels processed in parallel
- Expected: **2-5ms per column on modern GPU**

---

## Debugging Checklist

If your GPU terrain doesn't match vanilla:

- [ ] **Verify permutation tables:** Dump `improved_perms[0]` and compare to Java's `ImprovedNoise.p`
- [ ] **Check origin offsets:** Confirm `improved_origins` values are in correct range (0-256)
- [ ] **Test octave scaling:** Set all amplitudes except first to 0, render just base

 octave
- [ ] **Float-vs-double precision:** GPU float32 may diverge from Java double64 at threshold boundaries
- [ ] **Ensure SSBO bindings match shader:** Verify all 8 binding numbers are consistent

---

## Next Steps

1. **Integrate** `ShadowRouterExtractor` into your world load event
2. **Implement GPU upload** via your rendering backend (LWJGL, Iris, etc.)
3. **Wire compute dispatch** to your chunk rendering pipeline
4. **Test** with a known seed and compare GPU vs. CPU density output
5. *(Stretch)* Add CubicSpline flattening if basic terrain has visual gaps
