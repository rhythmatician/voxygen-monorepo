package com.rhythmatician.lodiffusion.voxy;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rhythmatician.lodiffusion.onnx.InferenceResult;

/**
 * Converts model inference output into Voxy {@code VoxelizedSection} objects
 * and pushes them into a Voxy {@code WorldEngine}.
 *
 * <p><b>Pipeline:</b>
 * <ol>
 *   <li>Argmax the block logits → per-voxel model index (class 0 = air)</li>
 *   <li>Translate model indices to Voxy block IDs via {@link VoxyBlockMapper}</li>
 *   <li>Pack into 64-bit Voxy voxels and fill a {@code VoxelizedSection}</li>
 *   <li>Compute mip pyramid via {@code WorldConversionFactory.mipSection()}</li>
 *   <li>Inject via {@code WorldUpdater.insertUpdate()}</li>
 * </ol>
 */
/**
 * @deprecated Prefer {@link VoxelVolumeWriter} seam; this class is retained only
 * for internal {@link RealVoxyVolumeWriter} delegation and will be removed.
 */
@Deprecated
public final class VoxySectionWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxySectionWriter.class);

    /** Default light value: full sky light, no block light → 0x0F. */
    static final int DEFAULT_LIGHT = 0x0F;

    /**
     * Thread-local scratch buffer for 32³ WorldSection voxel data.
     * Avoids allocating {@code new long[32768]} on every writeOctreeToLevel call.
     * Each worker thread gets its own buffer, cleared before each use.
     */
    private static final ThreadLocal<long[]> WS_SCRATCH =
            ThreadLocal.withInitial(() -> new long[32 * 32 * 32]);

    /**
     * Thread-local cache for air-voxel values per Voxy biome ID.
     * Avoids calling {@code composeVoxel(0, biome, DEFAULT_LIGHT)} for every
     * air voxel — typically the majority of all voxels.
     * Maps: biomeVoxyId → composed air voxel long.  Sized to max biome ID.
     */
    private static final ThreadLocal<long[]> AIR_CACHE =
            ThreadLocal.withInitial(() -> {
                long[] cache = new long[4096]; // generous upper bound for biome IDs
                Arrays.fill(cache, Long.MIN_VALUE); // sentinel: not yet computed
                return cache;
            });

    /** Counter for diagnostic logging — log detail for first N sections. Thread-safe. */
    private final AtomicInteger sectionsWritten = new AtomicInteger();

    private final Object worldEngine;
    private final Object voxyMapper;
    private final VoxyBlockMapper blockMapper;

    /**
     * Create a writer for a specific Voxy WorldEngine.
     *
     * @param worldEngine  the Voxy WorldEngine instance (reflected)
     * @param blockMapper  pre-built model→Voxy block ID mapping
     */
    public VoxySectionWriter(Object worldEngine, VoxyBlockMapper blockMapper) {
        this.worldEngine = worldEngine;
        this.voxyMapper  = VoxyCompat.getMapper(worldEngine);
        this.blockMapper = blockMapper;
        LOGGER.info("[VoxySectionWriter] Created — engine={}, mapper={}", 
                worldEngine.getClass().getSimpleName(), voxyMapper.getClass().getSimpleName());
    }

    /** Returns the Voxy WorldEngine instance (or {@code null} in tests). */
    public Object getWorldEngine() { return worldEngine; }

    /**
     * Test constructor for use without a live WorldEngine.
     * Bypasses Voxy runtime requirements.
     *
     * @param blockMapper  model→Voxy block ID mapping (may be a stub)
     */
    public VoxySectionWriter(VoxyBlockMapper blockMapper) {
        this.worldEngine = null;
        this.voxyMapper = null;
        this.blockMapper = blockMapper;
    }

    /**
     * Decode model output and inject it as a Voxy section at the given
     * chunk-section coordinate.
     *
     * @param result        the model's InferenceResult
     * @param vocabSize     number of block types in the model vocabulary
     * @param sectionX      Voxy chunk-section X (block X / 16)
     * @param sectionY      Voxy chunk-section Y (block Y / 16)
     * @param sectionZ      Voxy chunk-section Z (block Z / 16)
     * @param biomeVoxyIds  per-column Voxy biome IDs [16][16], indexed [x][z]
     */
    public void writeSection(InferenceResult result, int vocabSize,
                             int sectionX, int sectionY, int sectionZ,
                             int[][] biomeVoxyIds) {

        // ---- Insert-only guard ----
        // Never overwrite any existing section data.  Each progressive LOD
        // step writes to distinct section coordinates (different resolution
        // grids), so there is no need for self-overwrite tracking.
        if (VoxyCompat.sectionExists(worldEngine, sectionX, sectionY, sectionZ)) {
            if (sectionsWritten.get() < 10) {
                LOGGER.info("[VoxySectionWriter] Skipping ({},{},{}) — section already exists",
                        sectionX, sectionY, sectionZ);
            }
            sectionsWritten.incrementAndGet();
            return;
        }

        boolean detailed = sectionsWritten.get() < 5; // Log detail for first 5 sections

        // Build the filled section
        FilledSectionResult filled = buildFilledSection(result, vocabSize,
                sectionX, sectionY, sectionZ, biomeVoxyIds, detailed);

        Object section = filled.section();
        int nonAirCount = filled.nonAirCount();

        if (detailed) {
            LOGGER.info("[VoxySectionWriter] Section ({},{},{}) — {} non-air voxels out of 4096",
                    sectionX, sectionY, sectionZ, nonAirCount);
        }

        // Short-circuit: if the section is entirely air, skip mip + insert
        if (nonAirCount == 0) {
            if (detailed) {
                LOGGER.warn("[VoxySectionWriter] Skipping all-air section ({},{},{})", sectionX, sectionY, sectionZ);
            }
            sectionsWritten.incrementAndGet();
            return;
        }

        // 3. Compute mip pyramid (L1..L4) via Voxy's WorldConversionFactory
        LOGGER.debug("[VoxySectionWriter] Computing mip pyramid for ({},{},{})", sectionX, sectionY, sectionZ);
        VoxyCompat.mipSection(section, voxyMapper);

        // 4. Push into world
        LOGGER.debug("[VoxySectionWriter] Inserting section ({},{},{}) into Voxy world", sectionX, sectionY, sectionZ);
        VoxyCompat.insertUpdate(worldEngine, section);

        int written = sectionsWritten.incrementAndGet();
        if (detailed || written % 100 == 0) {
            LOGGER.info("[VoxySectionWriter] Wrote section ({},{},{}) — {} solid voxels [total written: {}]",
                    sectionX, sectionY, sectionZ, nonAirCount, written);
        }
    }

    /**
     * Result of building a filled VoxelizedSection (before mip and insert).
     *
     * @param section      the VoxelizedSection object (reflected)
     * @param nonAirCount  count of non-air voxels in L0
     */
    public record FilledSectionResult(Object section, int nonAirCount) {}

    /**
     * Build a filled VoxelizedSection from model output.
     *
     * <p>This method creates the section, sets its position, fills L0 voxels,
     * and returns the result without performing mip computation or insertion.
     *
     * @param result          the model's InferenceResult
     * @param vocabSize       number of block types in the model vocabulary
     * @param sectionX        Voxy chunk-section X (block X / 16)
     * @param sectionY        Voxy chunk-section Y (block Y / 16)
     * @param sectionZ        Voxy chunk-section Z (block Z / 16)
     * @param biomeVoxyIds    per-column Voxy biome IDs [16][16], indexed [x][z]
     * @param logDiagnostics  if true, log argmax distribution statistics
     * @return the filled section and non-air count
     */
    public FilledSectionResult buildFilledSection(InferenceResult result, int vocabSize,
                                            int sectionX, int sectionY, int sectionZ,
                                            int[][] biomeVoxyIds, boolean logDiagnostics) {

        float[][][][][] logits = result.blockLogits();  // [1][N][16][16][16]

        // Diagnostic: check air/solid distribution from argmax
        if (logDiagnostics) {
            int airCount = 0;
            for (int d0 = 0; d0 < 16; d0++)
                for (int d1 = 0; d1 < 16; d1++)
                    for (int d2 = 0; d2 < 16; d2++) {
                        // Quick argmax check: is class 0 (air) the winner?
                        int best = 0;
                        float bestVal = logits[0][0][d0][d1][d2];
                        for (int b = 1; b < vocabSize; b++) {
                                float v = logits[0][b][d0][d1][d2];
                                if (v > bestVal) {
                                    bestVal = v;
                                    best = b;
                                }
                            }
                        if (best == 0) airCount++;
                    }
            LOGGER.info("[VoxySectionWriter] Section ({},{},{}) argmax stats: air={}/4096, solid={}/4096",
                    sectionX, sectionY, sectionZ, airCount, 4096 - airCount);
        }

        // 1. Create empty VoxelizedSection
        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, sectionX, sectionY, sectionZ);
        long[] data = VoxyCompat.getSectionData(section);

        int nonAirCount = 0;

        // 2. Fill L0 (16³) — data[0..4095]
        //    Model output dimensions: [batch][channel][d0][d1][d2]
        //    Model axis convention: d0=Y, d1=Z, d2=X (matches Voxy/training)
        //    Voxy l0Index packs as YZX: (y<<8)|(z<<4)|x
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    long voxel;
                    int biome = biomeVoxyIds[x][z]; // per-column biome

                    // Unified argmax over ALL channels (air = class 0)
                    int bestIdx = 0;
                    float bestVal = logits[0][0][y][z][x];
                    for (int b = 1; b < vocabSize; b++) {
                        float v = logits[0][b][y][z][x];
                        if (v > bestVal) {
                            bestVal = v;
                            bestIdx = b;
                        }
                    }

                    if (bestIdx == 0) {
                        // Air — class 0 won the argmax
                        voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                    } else {
                        int voxyBlockId = blockMapper.getVoxyBlockId(bestIdx);
                        if (voxyBlockId == 0) {
                            // Mapped to air despite solid prediction — keep as air
                            voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                        } else {
                            voxel = VoxyCompat.composeVoxel(voxyBlockId, biome, DEFAULT_LIGHT);
                            nonAirCount++;
                        }
                    }

                    data[VoxyCompat.l0Index(x, y, z)] = voxel;
                }
            }
        }

        VoxyCompat.setNonAirCount(section, nonAirCount);

        return new FilledSectionResult(section, nonAirCount);
    }

    /**
     * Write a batch of model results covering a vertical column of sections.
     * Generates 16-block-tall slices from baseY upward.
     *
     * @param result         single 16³ model output
     * @param vocabSize      model vocabulary size
     * @param chunkX         Minecraft chunk X coordinate
     * @param baseY          world Y of the bottom of the 16³ volume
     * @param chunkZ         Minecraft chunk Z coordinate
     * @param biomeVoxyIds   per-column Voxy biome IDs [16][16]
     */
    public void writeChunkSlice(InferenceResult result, int vocabSize,
                                int chunkX, int baseY, int chunkZ,
                                int[][] biomeVoxyIds) {
        // Voxy section coordinates = block / 16 for x,z; block Y / 16 for y
        int sectionX = chunkX;
        int sectionY = baseY / 16;
        int sectionZ = chunkZ;

        writeSection(result, vocabSize, sectionX, sectionY, sectionZ, biomeVoxyIds);
    }

    // ------------------------------------------------------------------ //
    //  Progressive LOD: upsampled insertUpdate
    // ------------------------------------------------------------------ //

    /**
     * Upsample native-resolution block logits to 16³ and write via
     * {@code insertUpdate()}.  This properly propagates
     * {@code nonEmptyChildren} through Voxy's octree, making the data
     * immediately visible to the GPU traversal shader.
     *
     * <p>Each native-resolution voxel is replicated to fill its
     * {@code (scale)³} block region in the 16³ grid, where
     * {@code scale = 2^voxyLvl}.  The resulting section is then mipmapped
     * and inserted via the standard {@code insertUpdate()} path.
     *
     * <p>Unlike {@link #writeSection}, this method has <em>no</em>
     * {@code sectionExists} guard — progressive stages intentionally
     * overwrite earlier coarser predictions with finer ones.
     *
     * @param nativeLogits  model logits [1][N][D][D][D] where D = 16 >> voxyLvl
     * @param vocabSize     block vocabulary size
     * @param voxyLvl       source Voxy level (1-4), determines upsample factor
     * @param sectionX      L0 section X (blockX / 16)
     * @param sectionY      L0 section Y (blockY / 16)
     * @param sectionZ      L0 section Z (blockZ / 16)
     * @param biomeVoxyIds  per-column Voxy biome IDs [16][16], indexed [x][z]
     */
    public void writeUpsampledSection(float[][][][][] nativeLogits, int vocabSize,
                                      int voxyLvl, int sectionX, int sectionY,
                                      int sectionZ, int[][] biomeVoxyIds) {
        if (worldEngine == null) {
            throw new IllegalStateException(
                    "writeUpsampledSection requires a live WorldEngine");
        }

        int nativeRes = 16 >> voxyLvl;  // 1,2,4,8 for lvl 4,3,2,1
        int scale = 1 << voxyLvl;       // 16,8,4,2

        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, sectionX, sectionY, sectionZ);
        long[] data = VoxyCompat.getSectionData(section);

        int nonAirCount = 0;

        // For each 16³ L0 position, find the native-resolution source voxel
        for (int x = 0; x < 16; x++) {
            for (int y = 0; y < 16; y++) {
                for (int z = 0; z < 16; z++) {
                    int nx = Math.min(x / scale, nativeRes - 1);
                    int ny = Math.min(y / scale, nativeRes - 1);
                    int nz = Math.min(z / scale, nativeRes - 1);

                    // Argmax: model d0=Y, d1=Z, d2=X
                    int bestIdx = 0;
                    float bestVal = nativeLogits[0][0][ny][nz][nx];
                    for (int b = 1; b < vocabSize; b++) {
                        float v = nativeLogits[0][b][ny][nz][nx];
                        if (v > bestVal) {
                            bestVal = v;
                            bestIdx = b;
                        }
                    }

                    int biome = biomeVoxyIds[x][z];
                    long voxel;
                    if (bestIdx == 0) {
                        voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                    } else {
                        int voxyBlockId = blockMapper.getVoxyBlockId(bestIdx);
                        if (voxyBlockId == 0) {
                            voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                        } else {
                            voxel = VoxyCompat.composeVoxel(
                                    voxyBlockId, biome, DEFAULT_LIGHT);
                            nonAirCount++;
                        }
                    }

                    data[VoxyCompat.l0Index(x, y, z)] = voxel;
                }
            }
        }

        if (nonAirCount == 0) {
            return;
        }

        VoxyCompat.setNonAirCount(section, nonAirCount);
        VoxyCompat.mipSection(section, voxyMapper);
        VoxyCompat.insertUpdate(worldEngine, section);

        int written = sectionsWritten.incrementAndGet();
        if (written < 5 || written % 500 == 0) {
            LOGGER.info(
                "[VoxySectionWriter] Wrote upsampled LOD{} section ({},{},{}) "
                + "— {} solid voxels [total: {}]",
                voxyLvl, sectionX, sectionY, sectionZ, nonAirCount, written);
        }
    }

    /**
     * Clear is a no-op with insert-only semantics.
     *
     * <p>Retained for API compatibility.  With the insert-only guard,
     * LODiffusion never overwrites any existing section, so there is
     * nothing to "forget".
     *
     * @param sectionX chunk-section X
     * @param sectionZ chunk-section Z
     * @param baseY    lowest section Y (e.g., -4)
     * @param numY     number of Y sections (e.g., 16)
     */
    public void forgetColumn(int sectionX, int sectionZ, int baseY, int numY) {
        // no-op: insert-only guard handles all protection
    }

    // ------------------------------------------------------------------ //
    //  Progressive LOD: direct level writes
    // ------------------------------------------------------------------ //

    /**
     * Write model block logits directly to a specific Voxy storage level,
     * bypassing the L0-first {@code insertUpdate()} path.
     *
     * <p>This is the progressive LOD write path.  Each model stage produces
     * block logits at its native resolution (1³, 2³, 4³, or 8³) and this
     * method writes them to the corresponding Voxy WorldSection level (4, 3,
     * 2, or 1 respectively).
     *
     * <p>Coordinate convention: {@code sectionX/Y/Z} are always L0 section
     * coordinates (blockX/16, etc.).  The WorldSection coordinatesfor the
     * target level are derived internally using Voxy's coordinate math.
     *
     * @param blockLogits   model logits shaped [1][N][D][D][D] where D = 16 >> voxyLvl
     * @param vocabSize     block vocabulary size
     * @param voxyLvl       target Voxy storage level (1-4)
     * @param sectionX      L0 section X (blockX / 16)
     * @param sectionY      L0 section Y (blockY / 16)
     * @param sectionZ      L0 section Z (blockZ / 16)
     * @param biomeVoxyIds  per-column Voxy biome IDs [16][16], indexed [x][z]
     * @return number of non-air voxels written
     */
    public int writeLodSection(float[][][][][] blockLogits, int vocabSize,
                                int voxyLvl, int sectionX, int sectionY, int sectionZ,
                                int[][] biomeVoxyIds) {

        if (worldEngine == null) {
            throw new IllegalStateException("writeLodSection requires a live WorldEngine");
        }

        int cellsPerAxis = 16 >> voxyLvl;  // 8,4,2,1 for lvl 1,2,3,4
        int numCells = cellsPerAxis * cellsPerAxis * cellsPerAxis;

        // Validate logits shape: [1][N][D][D][D]
        if (blockLogits[0][0].length != cellsPerAxis) {
            throw new IllegalArgumentException(
                    "writeLodSection: logits spatial dim " + blockLogits[0][0].length
                    + " doesn't match expected " + cellsPerAxis + " for lvl " + voxyLvl);
        }

        boolean detailed = sectionsWritten.get() < 5;

        // Build packed voxel array in YZX order (matching writeAtLevel expectations)
        long[] voxels = new long[numCells];
        int idx = 0;
        int nonAirCount = 0;

        for (int ly = 0; ly < cellsPerAxis; ly++) {
            for (int lz = 0; lz < cellsPerAxis; lz++) {
                for (int lx = 0; lx < cellsPerAxis; lx++) {
                    // Argmax over channels — model axis convention: d0=Y, d1=Z, d2=X
                    int bestIdx = 0;
                    float bestVal = blockLogits[0][0][ly][lz][lx];
                    for (int b = 1; b < vocabSize; b++) {
                        float v = blockLogits[0][b][ly][lz][lx];
                        if (v > bestVal) {
                            bestVal = v;
                            bestIdx = b;
                        }
                    }

                    // Map biome — at coarse levels, pick the center biome
                    //   LOD1 covers 2 blocks/voxel, LOD4 covers 16 blocks/voxel
                    //   Use weighted center of the sub-region
                    int bx = Math.min(lx * (1 << voxyLvl) + ((1 << voxyLvl) >> 1), 15);
                    int bz = Math.min(lz * (1 << voxyLvl) + ((1 << voxyLvl) >> 1), 15);
                    int biome = biomeVoxyIds[bx][bz];

                    long voxel;
                    if (bestIdx == 0) {
                        voxel = VoxyCompat.composeVoxel(0, biome, VoxySectionWriter.DEFAULT_LIGHT);
                    } else {
                        int voxyBlockId = blockMapper.getVoxyBlockId(bestIdx);
                        voxel = VoxyCompat.composeVoxel(voxyBlockId, biome, VoxySectionWriter.DEFAULT_LIGHT);
                        if (voxyBlockId != 0) nonAirCount++;
                    }
                    voxels[idx++] = voxel;
                }
            }
        }

        // Skip all-air sections — avoids writing empty data and propagating
        // existence bits that would trigger Voxy's NodeManager warning loop
        if (nonAirCount == 0) {
            return 0;
        }

        // Write directly to the target Voxy level
        int nonAir = VoxyCompat.writeAtLevel(worldEngine, voxyLvl,
                sectionX, sectionY, sectionZ, voxels);

        int written = sectionsWritten.incrementAndGet();
        if (detailed || written % 500 == 0) {
            LOGGER.info("[VoxySectionWriter] Wrote LOD{} section ({},{},{}) — {} solid voxels [total: {}]",
                    voxyLvl, sectionX, sectionY, sectionZ, nonAir, written);
        }

        return nonAir;
    }

    /**
     * Write octree model output (32³ voxels) directly to a Voxy WorldSection
     * at the matching LOD level.  No upsampling — the model's 32³ grid maps
     * 1:1 to Voxy's 32³ WorldSection at the same level.
     *
     * <p>This is the progressive-LOD write path for levels 1–2.  Each level's
     * voxels are written at native resolution to the sparse octree.  When
     * finer levels are computed later, Voxy's {@code nonEmptyChildren} bits
     * guide the GPU to descend from coarse to fine.
     *
     * <p>Accepts pre-computed argmax IDs to avoid redundant argmax
     * computation (already computed in OctreeModelRunner).
     *
     * @param blockArgmax  {@code int[32][32][32]} argmax class indices in Y,Z,X order
     * @param biomeIdx32   {@code int[32][32]} canonical biome indices, indexed [z][x]
     * @param level        octree level (1–4)
     * @param wsX          WorldSection X at this level
     * @param wsY          WorldSection Y at this level
     * @param wsZ          WorldSection Z at this level
     * @return number of non-air voxels written, or 0 if section is all-air
     */
    public int writeOctreeToLevel(int[][][] blockArgmax,
                                  int[][] biomeIdx32,
                                  int level,
                                  int wsX, int wsY, int wsZ) {
        if (worldEngine == null) {
            throw new IllegalStateException("writeOctreeToLevel requires a live WorldEngine");
        }

        // Reuse thread-local scratch buffer instead of allocating 32K longs each call
        long[] voxels = WS_SCRATCH.get();
        Arrays.fill(voxels, 0L);

        // Air-voxel cache: avoids composeVoxel() for the majority-air voxels
        long[] airCache = AIR_CACHE.get();

        int nonAir = 0;

        for (int iy = 0; iy < 32; iy++) {
            for (int iz = 0; iz < 32; iz++) {
                int biome = blockMapper.getVoxyBiomeId(biomeIdx32[iz][0]);
                // Pre-fetch air voxel for this biome column (biome only varies by z,x)
                for (int ix = 0; ix < 32; ix++) {
                    int bestIdx = blockArgmax[iy][iz][ix];

                    biome = blockMapper.getVoxyBiomeId(biomeIdx32[iz][ix]);

                    long voxel;
                    if (bestIdx == 0) {
                        // Cached air voxel lookup
                        if (biome >= 0 && biome < airCache.length
                                && airCache[biome] != Long.MIN_VALUE) {
                            voxel = airCache[biome];
                        } else {
                            voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                            if (biome >= 0 && biome < airCache.length) {
                                airCache[biome] = voxel;
                            }
                        }
                    } else {
                        int voxyBlockId = blockMapper.getVoxyBlockId(bestIdx);
                        voxel = VoxyCompat.composeVoxel(voxyBlockId, biome, DEFAULT_LIGHT);
                        if (voxyBlockId != 0) nonAir++;
                    }

                    // WorldSection index: (y<<10)|(z<<5)|x
                    voxels[(iy << 10) | (iz << 5) | ix] = voxel;
                }
            }
        }

        if (nonAir == 0) {
            return 0; // Skip all-air sections
        }

        VoxyCompat.writeFullWorldSection(worldEngine, level, wsX, wsY, wsZ, voxels);

        int written = sectionsWritten.incrementAndGet();
        boolean detailed = written < 5;
        if (detailed || written % 500 == 0) {
            LOGGER.info(
                    "[VoxySectionWriter] Wrote L{} WorldSection ({},{},{}) "
                    + "— {} solid voxels [total: {}]",
                    level, wsX, wsY, wsZ, nonAir, written);
        }

        return nonAir;
    }

    /**
     * Write an octree L0 leaf section (32³ voxels at 1m/voxel) directly to a
     * Voxy WorldSection at level 0.
     *
     * <p>Previous implementation split the 32³ output into 2×2×2 = 8 native
     * 16³ {@code VoxelizedSection}s, each requiring {@code createEmptySection},
     * {@code mipSection}, and {@code insertUpdate}.  This version writes all
     * 32³ voxels in a single {@code writeFullWorldSection()} call — eliminating
     * 7 redundant mip computations and 8 section-lifecycle round-trips.
     *
     * <p>Parent-level voxel data (L1–L2) is written by separate model passes
     * via {@link #writeOctreeToLevel}, so the lack of insertUpdate's automatic
     * L0→L4 mip propagation is not a concern.  The
     * {@code nonEmptyChildren} existence bits <em>are</em> propagated by
     * {@code writeFullWorldSection}.
     *
     * <p>Accepts pre-computed argmax IDs to avoid redundant argmax
     * computation (already computed in OctreeModelRunner).
     *
     * @param blockArgmax  {@code int[32][32][32]} argmax class indices in Y,Z,X order
     * @param biomeIdx32   {@code int[32][32]} canonical biome indices covering the section footprint
     * @param wsX          octree L0 WorldSection X coordinate
     * @param wsY          octree L0 WorldSection Y coordinate
     * @param wsZ          octree L0 WorldSection Z coordinate
     * @return number of non-air voxels written, or 0 if section is all-air/already exists
     */
    public int writeOctreeBlockData(int[][][] blockArgmax,
                                      int[][] biomeIdx32,
                                      int wsX, int wsY, int wsZ) {
        if (worldEngine == null) {
            throw new IllegalStateException("writeOctreeBlockData requires a live WorldEngine");
        }

        // Reuse thread-local scratch buffer (same as writeOctreeToLevel)
        long[] voxels = WS_SCRATCH.get();
        Arrays.fill(voxels, 0L);

        // Air-voxel cache
        long[] airCache = AIR_CACHE.get();

        int nonAir = 0;

        // Fill 32³ voxels — WorldSection index: (y<<10)|(z<<5)|x
        for (int iy = 0; iy < 32; iy++) {
            for (int iz = 0; iz < 32; iz++) {
                for (int ix = 0; ix < 32; ix++) {
                    int bestIdx = blockArgmax[iy][iz][ix];
                    int biome = blockMapper.getVoxyBiomeId(biomeIdx32[iz][ix]);

                    long voxel;
                    if (bestIdx == 0) {
                        // Cached air voxel lookup
                        if (biome >= 0 && biome < airCache.length
                                && airCache[biome] != Long.MIN_VALUE) {
                            voxel = airCache[biome];
                        } else {
                            voxel = VoxyCompat.composeVoxel(0, biome, DEFAULT_LIGHT);
                            if (biome >= 0 && biome < airCache.length) {
                                airCache[biome] = voxel;
                            }
                        }
                    } else {
                        int voxyBlockId = blockMapper.getVoxyBlockId(bestIdx);
                        voxel = VoxyCompat.composeVoxel(voxyBlockId, biome, DEFAULT_LIGHT);
                        if (voxyBlockId != 0) nonAir++;
                    }

                    voxels[(iy << 10) | (iz << 5) | ix] = voxel;
                }
            }
        }

        if (nonAir == 0) {
            return 0; // Skip all-air sections
        }

        VoxyCompat.writeFullWorldSection(worldEngine, 0, wsX, wsY, wsZ, voxels);

        int written = sectionsWritten.incrementAndGet();
        boolean detailed = written < 5;
        if (detailed || written % 100 == 0) {
            LOGGER.info(
                    "[VoxySectionWriter] Wrote L0 WorldSection ({},{},{}) "
                    + "— {} solid voxels [total: {}]",
                    wsX, wsY, wsZ, nonAir, written);
        }

        return nonAir;
    }
}
