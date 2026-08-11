# Shadow Router: System Architecture Diagram

## Data Flow: From Minecraft to GPU

```
┌─────────────────────────────────────────────────────────────────────────┐
│                          MINECRAFT WORLD LOAD                            │
│                    (ServerLevelEvent.Load triggered)                     │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│  serverLevel.getChunkSource().getGenerator()                            │
│                     ↓                                                     │
│  Extract: NoiseRouter (record of 15 DensityFunction nodes)              │
│                     - finalDensity (used for terrain)                   │
│                     - continents, erosion, ridges (modifiers)           │
│                     - temperature, vegetation (biome data)              │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                                 ▼
┌─────────────────────────────────────────────────────────────────────────┐
│              ShadowRouterExtractor (Java Visitor Pattern)               │
├─────────────────────────────────────────────────────────────────────────┤
│  Phase 1: Graph Traversal                                                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ router.mapAll(this)  [DensityFunction.Visitor implementation]  │  │
│  │   ├─ Find all NormalNoise instances → assign GPU index 0..N    │  │
│  │   ├─ For each NormalNoise, extract parent PerlinNoise pair     │  │
│  │   └─ For each PerlinNoise, extract parent ImprovedNoise octaves│  │
│  │                                                                   │  │
│  │ Result: Maps                                                     │  │
│  │   noiseIndexMap:        NormalNoise → GPU instance index        │  │
│  │   perlinNoiseIndexMap:  PerlinNoise → GPU instance index        │  │
│  │   improvedNoiseIndexMap: ImprovedNoise → GPU instance index     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  Phase 2: Data Extraction                                                │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ For each discovered instance:                                   │  │
│  │   • ImprovedNoise.p (byte[256]) → IntBuffer (unsigned)          │  │
│  │   • ImprovedNoise.xo/yo/zo → FloatBuffer (origins)             │  │
│  │   • PerlinNoise.noiseLevels[] → index array                     │  │
│  │   • PerlinNoise.amplitudes → amplitude array                   │  │
│  │   • NormalNoise.first/second → perlin index pair                │  │
│  │   • NormalNoise.valueFactor → blend scalar                      │  │
│  │   • CubicSpline control points → flattened array (TODO)        │  │
│  │                                                                   │  │
│  │ Result: NoiseRouterData                                          │  │
│  │   ├─ improvedOrigins: FloatBuffer  (vec3 per instance)          │  │
│  │   ├─ improvedPerms: IntBuffer      (256 ints per instance)      │  │
│  │   ├─ perlinInts: IntBuffer         ([firstOctave + indices])    │  │
│  │   ├─ perlinFloats: FloatBuffer     ([factors + amplitudes])     │  │
│  │   ├─ normalNoiseInts: IntBuffer    ([idx1, idx2])               │  │
│  │   ├─ normalNoiseFloats: FloatBuffer ([valueFactor])             │  │
│  │   └─ splineData: FloatBuffer       (control points)             │  │
│  └──────────────────────────────────────────────────────────────────┘  │
└────────────────────────────────┬────────────────────────────────────────┘
                                 │
                 ┌───────────────┼───────────────┐
                 │               │               │
                 ▼               ▼               ▼
        ┌─────────────┐  ┌──────────────┐  ┌──────────────┐
        │ SSBO Upload │  │ RouterConfig │  │ Density Out  │
        │  (Bindings  │  │   UBO        │  │   SSBO       │
        │   0-6)      │  │ (Binding 8)  │  │ (Binding 7)  │
        └──────┬──────┘  └──────┬───────┘  └──────┬───────┘
               │                │                 │
               └────────────────┼─────────────────┘
                                │
                                ▼
┌─────────────────────────────────────────────────────────────────────────┐
│              GPU: GLSL Compute Shader Execution                          │
├─────────────────────────────────────────────────────────────────────────┤
│  terrain_compute.comp                                                    │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ Layout (compute):                                                │  │
│  │   local_size_x = 16  (chunk XZ width)                           │  │
│  │   local_size_y = 1   (Y levels processed per-thread)            │  │
│  │   local_size_z = 16  (chunk XZ depth)                           │  │
│  │                      [Total: 256 threads per dispatch]          │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  Shader Composition:                                                     │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ #version 450                                                     │  │
│  │ // Include: improved_noise.glsl                                  │  │
│  │ //   - MC_GRADIENT[16]                                           │  │
│  │ //   - mc_improved_noise()                                       │  │
│  │ // Include: perlin_noise.glsl                                    │  │
│  │ //   - mc_perlin_noise()                                         │  │
│  │ // Include: normal_noise.glsl                                    │  │
│  │ //   - mc_normal_noise()                                         │  │
│  │                                                                   │  │
│  │ // Main kernel:                                                  │  │
│  │ void main() {                                                    │  │
│  │   for (int y = MIN_Y; y <= MAX_Y; y++) {  // 384 iterations     │  │
│  │     float density = computeFinalDensity(blockX, y, blockZ);     │  │
│  │     density_out[outputIndex] = density;                         │  │
│  │   }                                                              │  │
│  │ }                                                                │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  computeFinalDensity() [5-step vanilla graph]:                           │
│  ┌──────────────────────────────────────────────────────────────────┐  │
│  │ 1. Sample 2D base noises at horizontal coords                    │  │
│  │    • continents = mc_normal_noise(...)  [determines biome shape] │  │
│  │    • erosion = mc_normal_noise(...)     [modifies terrain]      │  │
│  │                                                                   │  │
│  │ 2. Evaluate ridge offset via cubic spline                        │  │
│  │    • spline_eval(continents, erosion) → ridge_offset            │  │
│  │    • [Currently linear fallback: ridge_offset = mid_value]      │  │
│  │                                                                   │  │
│  │ 3. Apply Y-gradient (depth from surface)                         │  │
│  │    • depth = y_gradient(blockY, from_y, from_val, to_val)       │  │
│  │    • [Clamps Y to [-64, 320], linear interpolation]             │  │
│  │                                                                   │  │
│  │ 4. Sample jaggedness (high-frequency detail)                     │  │
│  │    • jaggedness = mc_normal_noise(...) * jagg_factor            │  │
│  │    • [Adds cliffs, cave entrances]                              │  │
│  │                                                                   │  │
│  │ 5. Combine into final density                                    │  │
│  │    • base = (ridge_offset + depth + jaggedness)                 │  │
│  │    • final = clamp(base, -64.0, 64.0)                           │  │
│  │    • Positive = air, Negative = solid block                     │  │
│  └──────────────────────────────────────────────────────────────────┘  │
│                                                                           │
│  Helper Functions:                                                       │
│  ├─ mc_improved_noise() — Core Perlin (from improved_noise.glsl)      │
│  ├─ mc_perlin_noise()   — Octave layering (from perlin_noise.glsl)    │
│  ├─ mc_normal_noise()   — Two-perlin blend (from normal_noise.glsl)   │
│  ├─ mc_squeeze()        — DensityFunction.Mapped.SQUEEZE              │
│  ├─ mc_half_negative()  — DensityFunction.Mapped.HALF_NEGATIVE        │
│  ├─ mc_y_gradient()     — YClampedGradient evaluation                 │
│  └─ mc_spline_eval()    — CubicSpline interpolation (stubbed)         │
└─────────────────────────────────┬────────────────────────────────────────┘
                                  │
                                  ▼
                    ┌──────────────────────────┐
                    │  Density Output (SSBO)   │
                    │  [256×384 terrain grid]  │
                    │                          │
                    │ density_out[xy][z]       │
                    │  = GPU-computed values   │
                    │  [ready for rendering]   │
                    └──────────────────────────┘
```

---

## Correctness Validation Loop

```
┌──────────────────────────────────────────────────────────────┐
│  Test: Random XYZ Sample                                     │
└────────────────┬─────────────────────────────────────────────┘
                 │
     ┌───────────┼───────────┐
     │           │           │
     ▼           ▼           ▼
   CPU       GPU Shader  Compare
   │           │           │
   │ Compute   │ Dispatch  │
   │ finalDensity  comp   ├─ Same sign?
   │ (double64)  shader   ├─ Diff < 0.01?
   │             (float32) ├─ Boundary safe?
   │           │           │
   └───────────┴───────────┘
               │
        ┌──────▼──────┐
        │ PASS / FAIL │
        │             │
        │ Log stats   │
        └─────────────┘
```

---

## SSBO Memory Layout (Total: ~100-200 KB typical)

```
Binding 0: improved_origins
  ┌─────────────────────────────┐
  │ vec3 origin[0]  (3 floats)  │
  │ vec3 origin[1]              │
  │ ...                         │
  │ vec3 origin[N-1]            │
  └─────────────────────────────┘
  Size: 12 bytes × N instances

Binding 1: improved_perms
  ┌─────────────────────────────┐
  │ int p[0][0..255]            │
  │ int p[1][0..255]            │
  │ ...                         │
  │ int p[N-1][0..255]          │
  └─────────────────────────────┘
  Size: 1024 bytes × N instances

Binding 2-5: Perlin & NormalNoise
  ┌─────────────────────────────┐
  │ [Per-instance data]         │
  │ Variable size depending on  │
  │ octave count                │
  └─────────────────────────────┘

Binding 6: spline_data
  ┌─────────────────────────────┐
  │ Float control points,       │
  │ flattened from all splines  │
  │ (Offsets tracked per spline)│
  └─────────────────────────────┘

Binding 7: density_output
  ┌─────────────────────────────┐
  │ float density[x][y][z]      │
  │ [256 × 384 = 98,304 floats] │
  │ [per chunk column]          │
  │ ~393 KB per column          │
  └─────────────────────────────┘

Binding 8: RouterConfig (UBO)
  ┌─────────────────────────────┐
  │ ivec3 chunkOrigin           │
  │ int nn_continents_idx       │
  │ int nn_erosion_idx          │
  │ ... (14 more node indices)  │
  │ float grad_from_y           │
  │ float grad_to_y             │
  │ ... (more params)           │
  └─────────────────────────────┘
  Size: ~256 bytes (cacheline-aligned)
```

---

## Execution Timeline (per world generation)

```
Time          Event
────          ─────
T+0ms    [World Load] ServerLevelEvent.Load triggered
T+5ms    NoiseRouter extracted from world generator
T+15ms   ShadowRouterExtractor.extract() completes
T+20ms   Reflection introspection of ImprovedNoise/PerlinNoise/NormalNoise
T+50ms   ├─ improvedOrigins uploaded to GPU
T+65ms   ├─ improvedPerms uploaded (1MB+ per spline)
T+150ms  ├─ perlinInts, perlinFloats uploaded
T+160ms  ├─ normalNoiseInts, normalNoiseFloats uploaded
T+165ms  └─ splineData uploaded (if implemented)
────────────────────────────────────────────
T+165ms  [Ready for rendering]

Per-frame (view distance 20):
T+0ms    [Render] Request 20 chunk columns from GPU
T+2ms    Dispatch: glDispatchCompute(20, 1, 20) [400 chunks]
T+5ms    Density output read back (optional, for validation)
T+7ms    GPU to CPU density grid streaming
```

---

## Integration Points

| Component | Location | Purpose |
|-----------|----------|---------|
| Event Hook | TBD | Capture ServerLevelEvent.Load |
| NoiseRouter Property | TBD | Extract from ChunkGenerator |
| ShadowRouterExtractor.java | `src/main/java/io/github/lodiffusion/worldgen/` | Visitor + Extraction |
| SSBO Manager | TBD | Allocate & manage GPU buffers |
| Shader Loader | TBD | Compile & link all 4 .glsl files |
| Compute Dispatcher | TBD | Invoke glDispatchCompute() |
| Validation Harness | TBD (optional) | Side-by-side comparison |

---

## Next: Identify Integration Points

To proceed, determine:
1. **Which event system?** Fabric Events? Forge GameTickEvent?
2. **Which rendering backend?** LWJGL3 direct? Iris/Sodium? Custom?
3. **Where does chunk rendering hook?** Where to inject compute dispatch?
4. **Who manages SSBOs?** Dedicated manager class? Rendering system?

Answer these, and we can implement the remaining 5-6 integration classes.
