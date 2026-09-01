// =============================================================================
// EXAMPLE: Using Cave Noise Helpers in computeFinalDensity() (WS-4.1b)
// 
// This document shows HOW each new helper function will be called in the
// context of the updated cave carving section of computeFinalDensity().
//
// These are PSEUDO-CODE examples showing correct usage patterns.
// Actual implementation will be in WS-4.2.
//
// Location: terrain_compute.comp, lines ~350-420 (cave carving section)
// =============================================================================

// ---------------------------------------------------------------------------
// CURRENT CODE (WS-4.1a) — to be enhanced with WS-4.1b helpers
// ---------------------------------------------------------------------------

    // -- Step 9: Cave carving (WS-4.1a) -------
    float cave_density_delta = 0.0;

    // Cheese caves (large spherical voids)
    if (router.nn_cheese_caves >= 0) {
        float cheese = mc_normal_noise(router.nn_cheese_caves,
                                       bx * 0.4, by * 0.8, bz * 0.4);
        float cave_val = cheese - 0.03;
        cave_density_delta = max(cave_density_delta, cave_val);
    }

    // ... rest of caves ...

// ---------------------------------------------------------------------------
// ENHANCED CODE (WS-4.1b with new helpers) — EXAMPLES
// ---------------------------------------------------------------------------

    // -- Step 9: Cave carving (WS-4.1b) -------
    // Using the new helper functions to enable rarity-quantized coordinate
    // warping, Y-range gating, and multi-scale sampling.
    
    float cave_density_delta = 0.0;

    // =========================================================================
    // CHEESE CAVES (Pillar caves) — with WeirdScaledSampler TYPE1
    // =========================================================================
    // BEFORE (WS-4.1a):
    //   float cheese = mc_normal_noise(nn_cheese_caves, 0.4, 0.8, 0.4)
    //
    // AFTER (WS-4.1b):
    //   - Sample rarity input (determines cave size/frequency)
    //   - Apply WeirdScaledSampler TYPE1 quantization
    //   - Sample thickness modifier for fine control
    //   - Blend together
    //
    // Result: Variable-rarity large spherical voids, with size controlled
    //         by regional rarity factors (rarer caves appear larger/fewer)
    
    if (router.nn_cheese_caves >= 0) {
        // Step 1: Sample rarity input (determines cave frequency)
        float pillar_rarity_input = mc_normal_noise(router.nn_pillar_rareness, 
                                                     bx * 0.25, by, bz * 0.25);
        
        // Step 2: Apply WeirdScaledSampler TYPE1 (rarity quantization + warping)
        // This scales the coordinates based on rarity band:
        //   rarity ∈ {0.75, 1.0, 1.5, 2.0}
        float cheese_warped = mc_weird_scaled_sampler_type1(
            router.nn_cheese_caves,
            bx, by, bz,
            pillar_rarity_input  // Input for quantization
        );
        
        // Step 3: Add thickness variation (optional, for finer control)
        float thickness_mod = 1.0;
        if (router.nn_pillar_thickness >= 0) {
            float thickness_factor = mc_normal_noise(router.nn_pillar_thickness,
                                                      bx * 0.4, by * 0.8, bz * 0.4);
            thickness_mod = 0.5 + thickness_factor * 0.5;  // Range [0..1]
        }
        
        // Step 4: Apply threshold and thickness
        // Default vanilla: cave where sample > 0.03
        // With thickness: cave where sample > (0.03 / thickness_mod)
        float threshold = 0.03 / thickness_mod;
        float cave_val = cheese_warped - threshold;
        cave_density_delta = max(cave_density_delta, cave_val);
    }

    // =========================================================================
    // SPAGHETTI 2D TUNNELS — with WeirdScaledSampler TYPE2
    // =========================================================================
    // BEFORE (WS-4.1a):
    //   float spaghetti_2d = mc_normal_noise(nn_spaghetti_2d, bx, 0.0, bz)
    //   float roughness = mc_normal_noise(nn_roughness, bx, by, bz) * 0.03
    //   float tube = -abs(spaghetti_2d + roughness) + 0.03
    //
    // AFTER (WS-4.1b):
    //   - Sample modulator for rarity quantization (TYPE2)
    //   - Apply WeirdScaledSampler TYPE2 for coordinate warping
    //   - Add Y-elevation variation
    //   - Add thickness control
    //   - Apply roughness from nn_roughness or nn_spaghetti_roughness
    //
    // Result: Fine-tuned 2D tunnel generation with variable rarity,
    //         Y-dependent elevation, and regional roughness control
    
    if (router.nn_spaghetti_2d >= 0) {
        // Step 1: Sample modulator for rarity input
        float spaghetti_2d_mod_input = mc_normal_noise(router.nn_spaghetti_2d_modulator,
                                                        bx * 0.25, by, bz * 0.25);
        
        // Step 2: Apply WeirdScaledSampler TYPE2 (wider rarity range: 0.5..3.0)
        float spaghetti_2d_warped = mc_weird_scaled_sampler_type2(
            router.nn_spaghetti_2d,
            bx, 0.0, bz,  // 2D mode: Y=0 for local axis
            spaghetti_2d_mod_input
        );
        
        // Step 3: Add Y-elevation variation (optional)
        float elevation_mod = 0.0;
        if (router.nn_spaghetti_2d_elevation >= 0) {
            float elev = mc_normal_noise(router.nn_spaghetti_2d_elevation,
                                         bx, by, bz);
            elevation_mod = elev * 0.01;  // Small vertical undulation
        }
        
        // Step 4: Apply roughness
        float roughness = 0.0;
        if (router.nn_roughness >= 0) {
            roughness = mc_normal_noise(router.nn_roughness, bx, by, bz) * 0.03;
        } else if (router.nn_spaghetti_roughness >= 0) {
            // Fallback: use alternative roughness noise
            roughness = mc_normal_noise(router.nn_spaghetti_roughness, bx, by, bz) * 0.03;
        }
        
        // Step 5: Apply thickness (post-warping radius)
        float tube_thickness = 0.03;
        if (router.nn_spaghetti_2d_thickness >= 0) {
            float thickness = mc_normal_noise(router.nn_spaghetti_2d_thickness,
                                              bx * 0.5, by, bz * 0.5);
            tube_thickness = 0.025 + thickness * 0.01;  // Range [0.015..0.035]
        }
        
        // Step 6: Compute tube carving (negative abs pattern)
        float spaghetti_tube = -(abs(spaghetti_2d_warped + roughness) 
                                + elevation_mod) + tube_thickness;
        cave_density_delta = max(cave_density_delta, spaghetti_tube);
    }

    // =========================================================================
    // CAVE ENTRANCES (Bore openings) — with WeirdScaledSampler TYPE1 + 3D structure
    // =========================================================================
    // BEFORE (WS-4.1a):
    //   float entrance = mc_normal_noise(nn_entrances, bx, by * 0.5, bz)
    //   float entrance_cave = -abs(entrance) + 0.05
    //
    // AFTER (WS-4.1b):
    //   - Sample rarity for WeirdScaledSampler TYPE1
    //   - Scale Y by 0.5 (vertical bores)
    //   - Add multi-octave 3D structure
    //   - Fine-grained entrance detail
    //   - Variable thickness control
    //
    // Result: Natural-looking cave entrance holes with hierarchical detail
    
    if (router.nn_entrances >= 0) {
        // Step 1: Sample rarity input (TYPE1 for 3D caves)
        float entrance_rarity_input = mc_normal_noise(router.nn_spaghetti_3d_rarity,
                                                       bx * 0.25, by * 0.25, bz * 0.25);
        
        // Step 2: Apply WeirdScaledSampler TYPE1 with scaled Y
        float entrance_warped = mc_weird_scaled_sampler_type1(
            router.nn_entrances,
            bx, by * 0.5, bz,  // Y scaled by 0.5 for wide horizontal boreholes
            entrance_rarity_input
        );
        
        // Step 3: Add 3D structural details (two orthogonal noise fields)
        float structure_detail = 0.0;
        if (router.nn_spaghetti_3d_1 >= 0) {
            float s1 = mc_normal_noise(router.nn_spaghetti_3d_1, bx, by, bz);
            structure_detail += s1 * 0.015;
        }
        if (router.nn_spaghetti_3d_2 >= 0) {
            float s2 = mc_normal_noise(router.nn_spaghetti_3d_2, bz, by, bx);
            structure_detail += s2 * 0.015;
        }
        
        // Step 4: Fine-grained entrance cavitation
        float entrance_detail = 0.0;
        if (router.nn_cave_entrance >= 0) {
            float detail = mc_normal_noise(router.nn_cave_entrance, 
                                          bx * 1.5, by, bz * 1.5);
            entrance_detail = detail * 0.005;  // High-frequency noise detail
        }
        
        // Step 5: Variable thickness
        float bore_radius = 0.05;
        if (router.nn_spaghetti_3d_thickness >= 0) {
            float thick = mc_normal_noise(router.nn_spaghetti_3d_thickness,
                                         bx * 0.5, by * 0.5, bz * 0.5);
            bore_radius = 0.04 + thick * 0.02;  // Range [0.02..0.06]
        }
        
        // Step 6: Compute bore carving
        float total_bore = entrance_warped + structure_detail + entrance_detail;
        float entrance_cave = -abs(total_bore) + bore_radius;
        cave_density_delta = max(cave_density_delta, entrance_cave);
    }

    // =========================================================================
    // NOODLE CAVES (Thin/thin connected tunnels) — with Y-range gating
    // =========================================================================
    // BEFORE (WS-4.1a):
    //   float noodle = mc_normal_noise(nn_noodle, bx * 1.5, by, bz * 1.5)
    //   float noodle_cave = -abs(noodle) + 0.02
    //
    // AFTER (WS-4.1b):
    //   - Use yLimitedInterpolatable() to gate to intermediate depths
    //   - Sample thickness and ridge details
    //   - Blend ridge A and B for wall variation
    //
    // Result: Thin noodle networks only in appropriate Y ranges with
    //         wall texture from ridge noise
    
    if (router.nn_noodle >= 0) {
        // Step 1: Y-limited evaluation (only active between Y_MIN+50 and Y_MAX-50)
        float noodle_base = mc_y_limited_interpolatable(
            by,                      // Current Y coordinate
            router.nn_noodle,        // Noise to sample
            bx * 1.5, by, bz * 1.5,  // Scaled coordinates for thin XZ pattern
            Y_MIN + 50,              // min_y_inclusive
            Y_MAX - 50,              // max_y_inclusive
            0.0                      // Default when outside Y range
        );
        
        // Step 2: Add thickness variation
        float noodle_thickness = 0.02;
        if (router.nn_noodle_thickness >= 0) {
            float thick = mc_normal_noise(router.nn_noodle_thickness,
                                         bx * 2.0, by, bz * 2.0);
            noodle_thickness = 0.015 + thick * 0.01;  // Range [0.005..0.025]
        }
        
        // Step 3: Add ridge/wall structure (two orthogonal components)
        float ridge_detail = 0.0;
        if (router.nn_noodle_ridge_a >= 0) {
            float ridge_a = mc_normal_noise(router.nn_noodle_ridge_a,
                                           bx * 3.0, by, bz * 1.0);
            ridge_detail += ridge_a * 0.01;
        }
        if (router.nn_noodle_ridge_b >= 0) {
            float ridge_b = mc_normal_noise(router.nn_noodle_ridge_b,
                                           bx * 1.0, by, bz * 3.0);
            ridge_detail += ridge_b * 0.01;
        }
        
        // Step 4: Compute noodle carving
        float noodle_tube = -(abs(noodle_base + ridge_detail)) + noodle_thickness;
        cave_density_delta = max(cave_density_delta, noodle_tube);
    }

    // =========================================================================
    // Apply all cave carving (common code — unchanged from WS-4.1a)
    // =========================================================================
    float carved = squeezed - cave_density_delta;
    return clamp(carved * 64.0, -64.0, 64.0);

// =============================================================================
// END EXAMPLE USAGE
// =============================================================================

// Note on Y-range bounds:
//   Y_MIN = -64 (Overworld bottom)
//   Y_MAX = 320 (Overworld top)
//   Y_LEVELS = 384
//
// See the top of terrain_compute.comp for actual definitions.
// Noodle caves traditionally appear around Y[50..250], but bounds
// should match vanilla behavior once NoiseRouterData is ported fully.
