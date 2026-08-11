// =============================================================================
// UPDATED RouterConfig UBO Definition (WS-4.1b)
// Replacementfor lines 160-180 in terrain_compute.comp
// 
// This struct extends the original RouterConfig with 13- 16 new NormalNoise
// indices needed for full-fidelity cave generation (WeirdScaledSampler,
// rarity quantization, and per-cave-type density functions).
//
// Total size: 256 bytes (16 × 16), std140-aligned
// All new fields default to -1 (not yet wired) for graceful degradation.
// =============================================================================

layout(binding = 8, std140) uniform RouterConfig {
    // =========================================================================
    // ORIGINAL FIELDS (Chunk + Terrain)
    // =========================================================================
    
    // Chunk origin in block coords (set per dispatch)
    int chunk_origin_x;
    int chunk_origin_z;
    int _pad0;
    int _pad1;                      // (0 bytes in, 16 bytes total)

    // Terrain routing noises (horizontal climate/continents)
    int nn_continents;              // NoiseRouter.continents
    int nn_erosion;                 // NoiseRouter.erosion
    int nn_ridges;                  // NoiseRouter.ridges (weirdness)
    int nn_depth_noise;             // "sloped_cheese" base 3D noise
    
    int nn_jagged;                  // Jaggedness detail (high-freq)
    int nn_shift_a;                 // X/Z coordinate shift (X component)
    int nn_shift_b;                 // X/Z coordinate shift (Z component)
    int _pad2;                      // (64 bytes in, 80 bytes total)

    // Y-gradient (depth) parameters for density mapping
    float grad_from_y;              // Overworld: -64
    float grad_to_y;                // Overworld: 320
    float grad_from_value;          // Overworld: 1.5
    float grad_to_value;            // Overworld: -1.5
                                    // (96 bytes in, 112 bytes total)

    // Spline table offsets (cubic interpolation for terrain shaping)
    int spline_offset_offset;       // offset spline table start idx
    int spline_factor_offset;       // factor spline table start idx
    int spline_jagged_offset;       // jaggedness spline table start idx
    int _pad3;                      // (112 bytes in, 128 bytes total)

    // =========================================================================
    // CAVE CARVING (Baseline — WS-4.1a)
    // =========================================================================
    // These five noises were added in WS-4.1a as the initial cave carving
    // infrastructure. All are now used alongside the new WS-4.1b helpers.

    int nn_entrances;               // Cave entrance bores (scaled Y, vertical bores)
    int nn_cheese_caves;            // Large spherical voids (pillar/bubble caves)
    int nn_spaghetti_2d;            // 2D tunnel axis (XZ plane)
    int nn_roughness;               // 3D roughness perturbation

    int nn_noodle;                  // Thin connected tunnel networks (XZ-scaled)
    // NEXT FREE: _pad4 starts at byte 144
    
    // =========================================================================
    // CAVE ENHANCEMENTS (WS-4.1b — WeirdScaledSampler + full density functions)
    // =========================================================================
    
    // --- Cheese/Pillar Caves Enhancement ---
    // Previous: mc_normal_noise(router.nn_cheese_caves, 0.4, 0.8, 0.4)
    // New: Uses two rarity+thickness-modulated samples for finer control.
    int nn_pillar_rareness;         // Input for WeirdScaledSampler TYPE1
                                    // Maps input noise → rarity [0.75..2.0]
                                    // Determines cave size and frequency
    
    int nn_pillar_thickness;        // Post-rarity scaling of cave bubble radius
                                    // Controls how thick/solid the pillar walls are
    
    // --- Spaghetti 2D Tunnels Enhancement ---
    // Previous: Simple mc_normal_noise(nn_spaghetti_2d, 0, 0, z)
    // New: Multiple-scale approach with modulation and elevation.
    int nn_spaghetti_2d_modulator;  // Pre-rarity for WeirdScaledSampler TYPE2
                                    // Maps input → rarity [0.5..3.0]
                                    // Creates variable-rarity tunnel bands
    
    int nn_spaghetti_2d_elevation;  // Y-dependent variation
                                    // Adds vertical undulation to tunnel axis
    
    int nn_spaghetti_2d_thickness;  // Tube radius modulation (post-warping)
                                    // Controls thickness of spaghetti tubes
    
    // --- Entrances (Bore Openings) Enhancement ---
    // Previous: Simple borehole calculation
    // New: WeirdScaledSampler TYPE1 + multi-octave 3D structure
    int nn_spaghetti_3d_rarity;     // Input for WeirdScaledSampler TYPE1
                                    // (similar to nn_pillar_rareness)
                                    // Rarity values [0.75..2.0]
    
    int nn_spaghetti_3d_thickness;  // Bore tube radius
    int nn_spaghetti_3d_1;          // First 3D structure detail
    int nn_spaghetti_3d_2;          // Second 3D structure detail (orthogonal)
    
    int nn_cave_entrance;           // Fine entrance opening detail
                                    // High-frequency cavitation at bore edges
    
    // --- Noodle Caves Enhancement ---
    // Previous: Simple mc_normal_noise(nn_noodle, 1.5, y, 1.5)
    // New: Thickness and ridge details for visual variety.
    int nn_noodle_thickness;        // Radius of noodle tubes (XZ-scaled context)
    int nn_noodle_ridge_a;          // Ridge/wall structure (first component)
    int nn_noodle_ridge_b;          // Ridge/wall structure (second component)
    
    // --- Additional Roughness Controls ---
    // Fine-grained control over regional cave variation.
    int nn_spaghetti_roughness;              // May be duplicate of nn_roughness
                                             // or a variant for specific contexts
    
    int nn_spaghetti_roughness_modulator;    // Secondary roughness control
                                             // Modulates roughness magnitude by region
    
    // Padding to reach 256 bytes (std140 alignment requirement: multiple of 16)
    int _pad5;
    int _pad6;
    int _pad7;

} router;

// ============================================================================
// FIELD LAYOUT SUMMARY (std140 aligned)
//
// Offsets (bytes):
//   0..3:     chunk_origin_x
//   4..7:     chunk_origin_z
//   8..11:    _pad0
//   12..15:   _pad1
//   16..19:   nn_continents
//   20..23:   nn_erosion
//   24..27:   nn_ridges
//   28..31:   nn_depth_noise
//   32..35:   nn_jagged
//   36..39:   nn_shift_a
//   40..43:   nn_shift_b
//   44..47:   _pad2
//   48..51:   grad_from_y
//   52..55:   grad_to_y
//   56..59:   grad_from_value
//   60..63:   grad_to_value
//   64..67:   spline_offset_offset
//   68..71:   spline_factor_offset
//   72..75:   spline_jagged_offset
//   76..79:   _pad3
//   80..83:   nn_entrances
//   84..87:   nn_cheese_caves
//   88..91:   nn_spaghetti_2d
//   92..95:   nn_roughness
//   96..99:   nn_noodle
//   [WS-4.1b additions below]
//   100..103: nn_pillar_rareness
//   104..107: nn_pillar_thickness
//   108..111: nn_spaghetti_2d_modulator
//   112..115: nn_spaghetti_2d_elevation
//   116..119: nn_spaghetti_2d_thickness
//   120..123: nn_spaghetti_3d_rarity
//   124..127: nn_spaghetti_3d_thickness
//   128..131: nn_spaghetti_3d_1
//   132..135: nn_spaghetti_3d_2
//   136..139: nn_cave_entrance
//   140..143: nn_noodle_thickness
//   144..147: nn_noodle_ridge_a
//   148..151: nn_noodle_ridge_b
//   152..155: nn_spaghetti_roughness
//   156..159: nn_spaghetti_roughness_modulator
//   160..163: _pad5
//   164..167: _pad6
//   168..171: _pad7
//   172..175: (reserved)
//   176..179: (reserved)
//   180..183: (reserved)
//   184..187: (reserved)
//   188..191: (reserved)
//   192..195: (reserved)
//   196..199: (reserved)
//   200..203: (reserved)
//   204..207: (reserved)
//   208..211: (reserved)
//   212..215: (reserved)
//   216..219: (reserved)
//   220..223: (reserved)
//   224..227: (reserved)
//   228..231: (reserved)
//   232..235: (reserved)
//   236..239: (reserved)
//   240..243: (reserved)
//   244..247: (reserved)
//   248..251: (reserved)
//   252..255: (reserved)
//
// Total: 256 bytes (std140 compliant: 16 × 16)
// ============================================================================

// ============================================================================
// INTEGRATION CHECKLIST
// ============================================================================
// 
// [ ] 1. GLSL Side (ShaderLoader.java)
//      - Update terrain_compute.comp to include this struct definition
//      - Update shader concatenation to include mc_cave_noise_helpers.glsl
//      - Add guard checks (>= 0) on all new indices before using
//      - Call mc_weird_scaled_sampler_type1/2 and new cave functions
//
// [ ] 2. Java Side (Noises.java)
//      - Add registry entries for all 13-16 new NormalNoise parameters:
//        * CAVE_PILLAR_RARENESS
//        * CAVE_PILLAR_THICKNESS
//        * SPAGHETTI_2D_MODULATOR
//        * SPAGHETTI_2D_ELEVATION
//        * SPAGHETTI_2D_THICKNESS
//        * CAVE_ENTRANCE_RARITY (TYPE1)
//        * CAVE_ENTRANCE_THICKNESS
//        * CAVE_ENTRANCE_1
//        * CAVE_ENTRANCE_2
//        * CAVE_ENTRANCE_DETAIL
//        * NOODLE_THICKNESS
//        * NOODLE_RIDGE_A
//        * NOODLE_RIDGE_B
//        * SPAGHETTI_ROUGHNESS_MODULATOR
//
// [ ] 3. Java Side (NoiseRouterData.java / underground())
//      - Wire these noises into the cave DensityFunction graph
//      - Use WeirdScaledSampler with TYPE1/TYPE2 appropriately
//      - Compose cave functions for the new infrastructure
//
// [ ] 4. Java Side (ShadowRouterExtractor.java)
//      - Extract all new NormalNoise indices from live RandomState
//      - Build index→column mapping as before
//      - Set all 16 new fields in RouterConfig UBO buffer
//      - Initialize to -1 if not present in live config (graceful degradation)
//
// ============================================================================
