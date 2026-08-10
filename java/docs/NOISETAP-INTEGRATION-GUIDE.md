# NoiseTap Integration Guide

## Overview

The `NoiseTap` interface provides efficient vanilla noise signal capture at native API granularities, exactly as you specified. This replaces our previous approach with a clean, zero-upsampling solution that respects Minecraft's native resolutions.

## Key Features

✅ **Native API Granularities**: 
- Router fields: 16×16×16 (block-level)
- Biomes: 4×4×4 (lattice storage)  
- Heightmaps: 16×16 (chunk-level)

✅ **Performance-Tiered Field Selection**:
- Core tier (~15ms): Essential Tier A fields
- Extended tier (~32ms): + Fluid/environmental fields
- Cave-aware tier (~66ms): + 3D density fields
- Full tier (~137ms): All 15 NoiseRouter fields

✅ **Zero Upsampling**: Raw cache at API resolution, downsample in models only

## Usage Examples

### Basic Usage

```java
// Bind to chunk context
var noiseTap = NoiseTap.bind(chunk, noiseConfig, biomeAccess, worldSeed);

// Capture core signals for real-time generation
var cache = noiseTap.captureAll(
    NoiseTap.getTierFields(PerformanceTier.CORE),
    NoiseTap.getDefaultHeightmaps()
);

// Access cached data
float[][][] temperatureData = cache.getRouterField(RouterField.TEMPERATURE); // [16][16][16]
short[][] surfaceHeights = cache.getHeightmap(Heightmap.Type.WORLD_SURFACE_WG); // [16][16]
int biomeId = cache.getBiomeId(0, 0, 0); // 4×4×4 lattice coordinates
```

### Performance-Optimized Usage

```java
// For real-time generation - use core tier only
EnumSet<RouterField> coreFields = NoiseTap.getTierFields(PerformanceTier.CORE);
// Contains: TEMPERATURE, VEGETATION, CONTINENTS, EROSION, DEPTH, RIDGES

// For specialized biomes - add environmental context  
EnumSet<RouterField> extendedFields = NoiseTap.getTierFields(PerformanceTier.EXTENDED);
// Adds: FLUID_FLOODEDNESS, FLUID_SPREAD, LAVA, BARRIER

// For cave-aware models - add 3D density
EnumSet<RouterField> caveFields = NoiseTap.getTierFields(PerformanceTier.CAVE_AWARE);
// Adds: INITIAL_DENSITY_NO_JAG, FINAL_DENSITY
```

### Memory Management

```java
var cache = noiseTap.captureAll(coreFields, defaultHeightmaps);

// Monitor memory usage
long memoryFootprint = cache.getMemoryFootprint(); // bytes
int fieldCount = cache.getRouterFieldCount();

System.out.printf("Cached %d fields using %d KB\n", 
    fieldCount, memoryFootprint / 1024);
```

## Integration with LODiffusion

### Replace TerrainDataCollector

The `NoiseTap` should replace our current `TerrainDataCollector` approach:

**Before** (old approach):
```java
// Old: Manual sampling with potential upsampling issues
var collector = new TerrainDataCollector(chunk, noiseConfig);
float[][] temperatureMap = collector.getTemperatureMap(8, 8); // Manual resolution
```

**After** (NoiseTap approach):
```java
// New: Native resolution capture, downsample in model
var noiseTap = NoiseTap.bind(chunk, noiseConfig, biomeAccess, worldSeed);
var cache = noiseTap.captureAll(
    NoiseTap.getTierFields(PerformanceTier.CORE),
    NoiseTap.getDefaultHeightmaps()
);

// Let the model handle downsampling from 16×16×16 to desired resolution
float[][][] temperatureData = cache.getRouterField(RouterField.TEMPERATURE);
```

### Model Input Pipeline

```java
public class OptimizedTerrainGenerator {
    
    public void generateTerrain(Chunk chunk, NoiseConfig noiseConfig, BiomeAccess biomeAccess) {
        // 1. Capture signals at native resolution
        var noiseTap = NoiseTap.bind(chunk, noiseConfig, biomeAccess, worldSeed);
        var cache = noiseTap.captureAll(
            selectedFields,  // Based on model requirements
            selectedHeightmaps
        );
        
        // 2. Convert to model inputs (downsample as needed)
        OnnxTensor inputs = createModelInputs(cache);
        
        // 3. Run inference
        var outputs = onnxSession.run(inputs);
        
        // 4. Apply results to chunk
        applyTerrainResults(chunk, outputs);
    }
    
    private OnnxTensor createModelInputs(NoiseTap.Cache cache) {
        // Model decides downsampling strategy
        // e.g., 16×16×16 → 8×8×1 for surface features
        // e.g., 4×4×4 → keep as-is for biome lattice
        // e.g., 16×16 → 8×8 for heightmap derivatives
    }
}
```

## Performance Budget

Based on our comprehensive noise analysis:

| Tier | Fields | Cost | Use Case |
|------|--------|------|----------|
| **Core** | 6 (Tier A) | ~15ms | Real-time generation |
| **Extended** | 10 (A+B) | ~32ms | Specialized biomes |
| **Cave-aware** | 12 (A+B+C) | ~66ms | Underground focus |
| **Full** | 15 (all) | ~137ms | Research/training only |

## API Reference

### RouterField Enum

Maps exactly to NoiseRouter's 15 DensityFunction fields:

**Tier A (Surface/Climate)**:
- `TEMPERATURE`, `VEGETATION`, `CONTINENTS`, `EROSION`, `DEPTH`, `RIDGES`

**Tier B (Fluid/Environment)**:
- `FLUID_FLOODEDNESS`, `FLUID_SPREAD`, `LAVA`, `BARRIER`

**Tier C (3D Density)**:
- `INITIAL_DENSITY_NO_JAG`, `FINAL_DENSITY`

**Tier D (Vein/Ore)**:
- `VEIN_TOGGLE`, `VEIN_RIDGED`, `VEIN_GAP`

**⚠️ LEGACY:** This document describes the *old progressive LOD pipeline* (five-model chain) that has since been replaced by the octree‑based pipeline. For the current architecture and model contract see the top‑level `PROJECT-OUTLINE.md` (sections labelled Phase 1/3) and `MODEL-CONTRACT.md`.

### Cache Record

```java
record Cache(
    Map<RouterField, float[][][]> router,     // [16][16][16] per field
    int[][][] biomes4,                        // [4][4][4] biome IDs
    Map<Heightmap.Type, short[][]> heightmaps16, // [16][16] per type
    int chunkMinY, int chunkHeight,           // chunk bounds
    int chunkX, int chunkZ,                   // chunk position  
    long seed                                 // world seed
)
```

## Progressive LOD Model Specification

Your specification provides a comprehensive I/O contract for the **five ONNX models** in our progressive LOD pipeline. This ensures both VoxelTree training and LODiffusion runtime use identical data shapes.

### Model Architecture Overview

The complete progressive LOD pipeline consists of **five models**:
0. **Init** (Bootstrap): Noise → LOD4 (1×1×1 initial voxel)
1. **LOD4 → LOD3**: Refines 1×1×1 → 2×2×2 blocks
2. **LOD3 → LOD2**: Refines 2×2×2 → 4×4×4 blocks  
3. **LOD2 → LOD1**: Refines 4×4×4 → 8×8×8 blocks
4. **LOD1 → LOD0**: Refines 8×8×8 → 16×16×16 blocks (full resolution)

### Shared Input Convention

All five models use the **same cached feature pack** with these normalized data types:

#### **Core Features (Always Present)**
```java
// Heights (planar, 16×16) - 5 channels
x_height_planes:  [1,5,1,16,16]   // surface, ocean_floor, slope_x, slope_z, curvature

// Biomes (3D, 4×4×4) - 6 channels  
x_biome_quart:    [1,6,4,4,4]     // temp, precip_onehot[3], isCold, downfall

// Router-6 (planar, 16×16 @ one Y) - 6 channels
x_router6:        [1,6,1,16,16]   // temperature, vegetation, continents, erosion, depth, ridges

// Scalars
x_chunk_pos:      [1,2]           // (chunkX, chunkZ) 
x_lod:            [1,1]           // LOD level in [0,1]
```

#### **Optional Features (Performance-Dependent)**
```java
x_barrier:        [1,1,1,16,16]   // barrierNoise
x_aquifer3:       [1,3,1,16,16]   // fluidLevelFloodednessNoise, fluidLevelSpreadNoise, lavaNoise  
x_cave_prior4:    [1,1,4,4,4]     // initialDensityWithoutJaggedness OR finalDensity
```

### Five Model Pipeline

#### **Model 0: Init (Bootstrap - Noise → LOD4)**
Creates the very first coarse voxel from pure noise signals.

```java
// Inputs
x_parent_prev:    [1,1,1,1,1]     // All zeros (no parent exists)
x_height_planes:  [1,5,1,16,16]   // Core terrain shape
x_biome_quart:    [1,6,4,4,4]     // Climate context  
x_router6:        [1,6,1,16,16]   // Essential router fields
x_chunk_pos:      [1,2]           // Positional encoding
x_lod:            [1,1]           // LOD level = 0 (initial)

// Outputs  
block_logits:     [1,N_blocks,1,1,1]  // Single voxel block type
air_mask:         [1,1,1,1,1]         // Single voxel solid/air
```

**Training target**: Downscale 16³ labels to 1³ by majority block (ties → lowest index or "air"), and set `air_mask=mean(air)` over 16³.

#### **Model 1: LOD4→LOD3 (First Refinement - 1×1×1 → 2×2×2)**
```java
// Inputs
x_parent_prev:    [1,1,1,1,1]     // From Model 0's air_mask or argmax logits
x_height_planes:  [1,5,1,16,16]   // Same cached terrain shape
x_biome_quart:    [1,6,4,4,4]     // Same cached climate context
x_router6:        [1,6,1,16,16]   // Same cached router fields
x_chunk_pos:      [1,2]           // Same positional encoding
x_lod:            [1,1]           // LOD level = 1

// Outputs  
block_logits:     [1,N_blocks,2,2,2]  // Refined block predictions
air_mask:         [1,1,2,2,2]         // Refined solid/air mask
```

#### **Model 2: LOD3→LOD2 (Second Refinement - 2×2×2 → 4×4×4)**
```java
// Inputs
x_parent_prev:    [1,1,2,2,2]     // From Model 1
x_height_planes:  [1,5,1,16,16]   // Same cached features
x_biome_quart:    [1,6,4,4,4]     // Same cached features
x_router6:        [1,6,1,16,16]   // Same cached features
x_chunk_pos:      [1,2]           // Same coordinates
x_lod:            [1,1]           // LOD level = 2

// Outputs
block_logits:     [1,N_blocks,4,4,4]  // Higher resolution blocks
air_mask:         [1,1,4,4,4]         // Higher resolution mask
```

#### **Model 3: LOD2→LOD1 (Third Refinement - 4×4×4 → 8×8×8)**
```java
// Inputs
x_parent_prev:    [1,1,4,4,4]     // From Model 2
x_height_planes:  [1,5,1,16,16]   // Same cached features
x_biome_quart:    [1,6,4,4,4]     // Same cached features
x_router6:        [1,6,1,16,16]   // Same cached features
x_chunk_pos:      [1,2]           // Same coordinates
x_lod:            [1,1]           // LOD level = 3

// Outputs
block_logits:     [1,N_blocks,8,8,8]  // Higher resolution blocks
air_mask:         [1,1,8,8,8]         // Higher resolution mask
```

#### **Model 4: LOD1→LOD0 (Final Detail - 8×8×8 → 16×16×16)**
```java
// Inputs  
x_parent_prev:    [1,1,8,8,8]     // From Model 3
x_height_planes:  [1,5,1,16,16]   // Native 16×16 resolution (never downsampled)
x_biome_quart:    [1,6,4,4,4]     // Native 4×4×4 lattice
x_router6:        [1,6,1,16,16]   // Native 16×16 router samples
x_chunk_pos:      [1,2]           // Final coordinates
x_lod:            [1,1]           // LOD level = 4

// Outputs
block_logits:     [1,N_blocks,16,16,16]  // Full chunk resolution
air_mask:         [1,1,16,16,16]         // Full chunk mask
```

### Data Sources (Exact Yarn 1.21.4+ API Calls)

#### **Heightmap Features** (via NoiseTap)
```java
// Source: Heightmap.populateHeightmaps() + Chunk.getHeightmap()
surface:     Heightmap.Type.WORLD_SURFACE_WG     // Primary terrain
ocean_floor: Heightmap.Type.OCEAN_FLOOR_WG       // Ocean floor elevation  
slope_x:     derivative(surface, x_direction)    // Computed from surface
slope_z:     derivative(surface, z_direction)    // Computed from surface
curvature:   laplacian(surface)                  // Computed from surface
```

#### **Biome Features** (via NoiseTap)
```java
// Source: BiomeAccess.getBiomeForNoiseGen() at 4×4×4 lattice points
temp:         Biome.getTemperature()              // Continuous temperature
precip_onehot: encode_precipitation_type()        // [none, rain, snow] one-hot
isCold:       Biome.isCold(BlockPos, seaLevel)    // Boolean cold flag
```

#### **Router Features** (via NoiseTap)  
```java
// Source: NoiseConfig.getNoiseRouter() → DensityFunction.sample()
temperature:  router.temperature().sample(pos)    // Climate temperature
vegetation:   router.vegetation().sample(pos)     // Vegetation density
continents:   router.continents().sample(pos)     // Continental classification
erosion:      router.erosion().sample(pos)        // Erosion patterns
depth:        router.depth().sample(pos)          // Underground depth
ridges:       router.ridges().sample(pos)         // Ridge/valley formation
```

### Normalization Strategy

#### **Heights** (MinMax per world)
```java
// Using HeightLimitView API
normalized_height = (raw_height - world.getBottomY()) / world.getHeight()
```

#### **Router Fields** (Z-score per channel)
```java
// Store statistics in model_config.json
normalized_value = (raw_value - channel_mean) / channel_std
```

#### **Biome Features** (Mixed encoding)
```java
temp:         raw_temperature_value           // Continuous
precip_onehot: [0,0,1] for rain, etc.        // One-hot categorical
isCold:       1.0 if cold, 0.0 if warm       // Binary flag
```

#### **Coordinates** (Tanh scaling)
```java
// Prevents coordinate explosion for distant chunks
normalized_coord = tanh(raw_coord / scale_factor)
```

### Model Configuration Template

Each model requires a `model_config.json`:

```json
{
  "model_name": "lodiffusion_lod3_to_lod2", 
  "version": "1.0.0",
  "inputs": {
    "x_parent_prev":    [1,1,2,2,2],
    "x_height_planes":  [1,5,1,16,16], 
    "x_biome_quart":    [1,6,4,4,4],
    "x_router6":        [1,6,1,16,16],
    "x_chunk_pos":      [1,2],
    "x_lod":            [1,1]
  },
  "optional_inputs": {
    "x_barrier":        [1,1,1,16,16],
    "x_aquifer3":       [1,3,1,16,16], 
    "x_cave_prior4":    [1,1,4,4,4]
  },
  "outputs": {
    "block_logits":     [1,256,4,4,4],
    "air_mask":         [1,1,4,4,4]
  },
  "normalization": {
    "heights": {
      "type": "minmax",
      "bottomY": -64,
      "height": 384
    },
    "router6": {
      "type": "zscore", 
      "mean": [0.1, -0.2, 0.8, -0.1, 0.3, 0.0],
      "std": [0.5, 0.8, 0.3, 0.7, 0.4, 0.6]
    },
    "biome": {
      "type": "mixed"
    },
    "coords": {
      "type": "tanh",
      "scale": 1000.0
    }
  },
  "block_palette": {
    "size": 256,
    "mapping": "standard_minecraft_blocks.json"
  }
}
```

### Efficiency Benefits

1. **No Upsampling**: Cache once at API granularity, models resize internally
2. **Shared Features**: Same cached data used across all 4 models  
3. **Native Resolution**: Router/Aquifer as 2D slices (16×16 @ one Y) preserves context with minimal cost
4. **Progressive Context**: Each model builds on previous LOD prediction
5. **Optional Complexity**: Include cave_prior4 only when underground fidelity needed
