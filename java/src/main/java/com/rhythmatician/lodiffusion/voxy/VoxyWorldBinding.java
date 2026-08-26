package com.rhythmatician.lodiffusion.voxy;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Manages direct field-level access to Voxy's internal {@code WorldSection} storage arrays
 * and provides world-level write operations for progressive LOD generation.
 *
 * <p><b>Responsibilities:</b>
 * <ul>
 *   <li>Lazily resolving {@code WorldSection.data} and {@code WorldSection.nonEmptyChildren}
 *       via reflection (deferred from {@link VoxyEngine#ensureEngineBindings()} because they
 *       require accessible field access, not just method handles).</li>
 *   <li>Providing {@code VoxelizedSection} field accessors: {@link #setSectionPosition},
 *       {@link #getSectionData}, {@link #setNonAirCount}.</li>
 *   <li>Providing direct-write operations that bypass the normal {@code insertUpdate()} path:
 *       {@link #writeAtLevel} and {@link #sectionExistsAtLevel}.</li>
 *   <li>Hosting the 64-bit voxel encoding constants and helpers ({@link #composeVoxel},
 *       {@link #isAir}, {@link #l0Index}).</li>
 * </ul>
 *
 * <p><b>Internal — do not use outside {@code voxy}; use {@link VoxelVolumeWriter}.</b>
 * YZX order, scale-clamp, CAS + markDirty are hidden details owned by the writer seam.
 * This class will become package-private once the migration facade ({@link VoxyCompat}) is removed.
 *
 * <p>Detection logic lives in {@link VoxyDetection}.
 * Engine-level reflection setup and operations live in {@link VoxyEngine}.
 */
final class VoxyWorldBinding {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyWorldBinding.class);

    static final int BLOCK_UPDATE_FLAG = 1;
    static final int CHILD_EXISTENCE_UPDATE_FLAG = 2;

    // ------------------------------------------------------------------ //
    //  WorldSection field bindings (package-private — resolved lazily)
    // ------------------------------------------------------------------ //

    private static volatile boolean worldSectionFieldsReady;

    /** {@code WorldSection.data} — the raw 32³ packed-voxel array. */
    static Field worldSectionDataField;

    /** {@code WorldSection.nonEmptyChildren} — octant-presence bitmask for GPU octree traversal. */
    static Field worldSectionNonEmptyChildrenField;

    /**
     * VarHandle for {@code WorldSection.nonEmptyChildren}, used for CAS updates in
     * complete parent refinement publication. Mirrors the approach Voxy itself uses inside
     * {@code WorldSection.updateEmptyChildState()} — a CAS loop prevents lost-update
     * races against concurrent Voxy write paths.
     *
     * <p>Must be available before this class mutates NEC. A non-atomic fallback
     * could discard a concurrent native Voxy child-state update.
     */
    static VarHandle worldSectionNecVarHandle;

    private VoxyWorldBinding() {}

    // ------------------------------------------------------------------ //
    //  Voxel bit-layout constants  (match Voxy's Mapper)
    // ------------------------------------------------------------------ //

    /** Block ID is stored in bits [27, 46] of the packed voxel long. */
    public static final int  BLOCK_ID_SHIFT = 27;
    public static final int  BLOCK_ID_BITS  = 20;
    public static final long BLOCK_ID_MASK  = ((1L << BLOCK_ID_BITS) - 1) << BLOCK_ID_SHIFT;

    /** Biome ID is stored in bits [47, 55] of the packed voxel long. */
    public static final int  BIOME_ID_SHIFT = 47;
    public static final int  BIOME_ID_BITS  = 9;
    public static final long BIOME_ID_MASK  = ((1L << BIOME_ID_BITS) - 1) << BIOME_ID_SHIFT;

    /** Sky/block light packed into bits [56, 63]. */
    public static final int  LIGHT_SHIFT    = 56;

    // ------------------------------------------------------------------ //
    //  Lazy initialization
    // ------------------------------------------------------------------ //

    /**
     * Ensure {@code WorldSection.data} and {@code WorldSection.nonEmptyChildren} field handles
     * are resolved.  Calls {@link VoxyEngine#ensureEngineBindings()} first to guarantee that
     * {@link VoxyEngine#worldSectionClass} is populated.
     *
     * @throws IllegalStateException if the required fields cannot be found
     */
    static void ensureWorldSectionBindings() {
        VoxyEngine.ensureEngineBindings();
        if (worldSectionFieldsReady) return;
        synchronized (VoxyWorldBinding.class) {
            if (worldSectionFieldsReady) return;
            try {
                worldSectionDataField = VoxyEngine.worldSectionClass.getDeclaredField("data");
                worldSectionDataField.setAccessible(true);

                worldSectionNonEmptyChildrenField =
                        VoxyEngine.worldSectionClass.getDeclaredField("nonEmptyChildren");
                worldSectionNonEmptyChildrenField.setAccessible(true);

                // Attempt to resolve a VarHandle for nonEmptyChildren so that
                // complete parent-mask publication can use a CAS loop instead of a raw
                // field write.  privateLookupIn may fail on JVMs with strict
                // strong encapsulation — the fallback Field path remains safe.
                try {
                    MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(
                            VoxyEngine.worldSectionClass, MethodHandles.lookup());
                    worldSectionNecVarHandle = privateLookup.findVarHandle(
                            VoxyEngine.worldSectionClass, "nonEmptyChildren", byte.class);
                    LOGGER.info("Voxy nonEmptyChildren VarHandle resolved");
                } catch (IllegalAccessException | NoSuchFieldException e) {
                    throw new IllegalStateException(
                            "Voxy nonEmptyChildren VarHandle unavailable; refusing unsafe NEC writes", e);
                }

                worldSectionFieldsReady = true;
                LOGGER.info("Voxy WorldSection field bindings resolved");
            } catch (NoSuchFieldException e) {
                throw new IllegalStateException(
                        "Voxy WorldSection field bindings not available: " + e.getMessage(), e);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  VoxelizedSection field accessors
    // ------------------------------------------------------------------ //

    /**
     * Set the {@code x}, {@code y}, {@code z} position fields on a {@code VoxelizedSection}.
     *
     * @param section the VoxelizedSection object
     * @param x       section X (blockX &gt;&gt; 4)
     * @param y       section Y (blockY &gt;&gt; 4)
     * @param z       section Z (blockZ &gt;&gt; 4)
     */
    public static void setSectionPosition(Object section, int x, int y, int z) {
        VoxyDetection.ensureAvailable();
        try {
            var xField = VoxyDetection.voxelizedSectionClass.getField("x");
            var yField = VoxyDetection.voxelizedSectionClass.getField("y");
            var zField = VoxyDetection.voxelizedSectionClass.getField("z");
            xField.setInt(section, x);
            yField.setInt(section, y);
            zField.setInt(section, z);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set section position", e);
        }
    }

    /**
     * Get the raw packed-voxel data array ({@code VoxelizedSection.section[]}) from a
     * {@code VoxelizedSection}.
     *
     * @param section the VoxelizedSection object
     * @return the underlying {@code long[]} with 4681 entries (L0 16³ + mip pyramid)
     */
    public static long[] getSectionData(Object section) {
        VoxyDetection.ensureAvailable();
        try {
            var field = VoxyDetection.voxelizedSectionClass.getField("section");
            return (long[]) field.get(section);
        } catch (Exception e) {
            throw new RuntimeException("Failed to get section data", e);
        }
    }

    /**
     * Set the {@code lvl0NonAirCount} field on a {@code VoxelizedSection}.
     * Must be kept in sync with the actual number of non-air voxels written at L0.
     *
     * @param section the VoxelizedSection object
     * @param count   number of non-air voxels at LOD level 0
     */
    public static void setNonAirCount(Object section, int count) {
        VoxyDetection.ensureAvailable();
        try {
            var field = VoxyDetection.voxelizedSectionClass.getField("lvl0NonAirCount");
            field.setInt(section, count);
        } catch (Exception e) {
            throw new RuntimeException("Failed to set non-air count", e);
        }
    }

    // ------------------------------------------------------------------ //
    //  Voxel encoding helpers
    // ------------------------------------------------------------------ //

    /**
     * Compose a 64-bit packed voxel value from a block ID, biome ID, and light level,
     * using the same bit layout as Voxy's {@code Mapper}.
     *
     * @param blockId Voxy block registry ID
     * @param biomeId Voxy biome registry ID
     * @param light   combined sky+block light packed into 8 bits
     * @return packed 64-bit voxel
     */
    public static long composeVoxel(int blockId, int biomeId, int light) {
        return ((long) light << LIGHT_SHIFT)
             | ((long)(biomeId & 0x1FF) << BIOME_ID_SHIFT)
             | ((long)(blockId & ((1 << BLOCK_ID_BITS) - 1)) << BLOCK_ID_SHIFT);
    }

    /**
     * Return {@code true} if the packed voxel represents air (block-ID field is zero).
     *
     * @param voxel packed 64-bit voxel value
     */
    public static boolean isAir(long voxel) {
        return (voxel & BLOCK_ID_MASK) == 0;
    }

    /**
     * Compute the L0 index into {@code VoxelizedSection.section[]} for local coordinates
     * in [0, 15].  Index layout: {@code (y << 8) | (z << 4) | x} (YZX order).
     *
     * @param x local X in [0, 15]
     * @param y local Y in [0, 15]
     * @param z local Z in [0, 15]
     * @return flat array index in [0, 4095]
     */
    public static int l0Index(int x, int y, int z) {
        return (y << 8) | (z << 4) | x;
    }

    /**
     * Scale-clamp helper that encapsulates the native-resolution clamp
     * ({@code Math.min(coord / scale, nativeRes - 1)}). Centralizes the hidden
     * clamp detail for compatibility callers.
     */
    static int clampToNativeRes(int coord, int scale, int nativeRes) {
        return Math.min(coord / scale, nativeRes - 1);
    }

    // ------------------------------------------------------------------ //
    //  Direct WorldSection level writes  (bypass insertUpdate)
    // ------------------------------------------------------------------ //

    /**
     * Write voxel data directly into a Voxy {@code WorldSection} at a specific LOD level,
     * bypassing the {@code insertUpdate()} path that always starts at L0.
     *
     * <p>This is the core primitive for progressive LOD generation.  Each model stage outputs
     * block predictions at a specific resolution that maps 1:1 to a Voxy storage level.  We
     * acquire the target-level {@code WorldSection}, write voxels at the correct sub-position
     * within the 32³ grid, mark it dirty, and release.
     *
     * <h4>Coordinate math (from {@code WorldUpdater.java}):</h4>
     * <pre>
     *   WorldSection coords: (lvl, sectionX &gt;&gt; (lvl+1), sectionY &gt;&gt; (lvl+1), sectionZ &gt;&gt; (lvl+1))
     *   Sub-position:        bx = (sectionX &amp; mask) &lt;&lt; (4 - lvl)   where mask = (1 &lt;&lt; (lvl+1)) - 1
     *   World section index: bx | (bz &lt;&lt; 5) | (by &lt;&lt; 10)
     * </pre>
     *
     * @param worldEngine the Voxy WorldEngine instance
     * @param lvl         Voxy storage level (1 = LOD1 8³, 2 = LOD2 4³, 3 = LOD3 2³, 4 = LOD4 1³)
     * @param sectionX    L0 section X (blockX / 16)
     * @param sectionY    L0 section Y (blockY / 16)
     * @param sectionZ    L0 section Z (blockZ / 16)
     * @param voxels      packed 64-bit voxels, sized {@code (16>>lvl)³}, YZX-ordered
     * @return number of non-air voxels written
     * @throws IllegalArgumentException if {@code lvl} is outside [1, 4] or {@code voxels.length}
     *                                  does not match the expected size for the level
     */
    public static int writeAtLevel(Object worldEngine, int lvl,
                                    int sectionX, int sectionY, int sectionZ,
                                    long[] voxels) {
        if (lvl < 1 || lvl > 4) {
            throw new IllegalArgumentException("writeAtLevel: lvl must be 1-4, got " + lvl);
        }

        int cellsPerAxis = 16 >> lvl;  // 8, 4, 2, 1 for lvl 1, 2, 3, 4
        int expectedSize = cellsPerAxis * cellsPerAxis * cellsPerAxis;
        if (voxels.length != expectedSize) {
            throw new IllegalArgumentException("writeAtLevel: expected " + expectedSize
                    + " voxels for lvl " + lvl + ", got " + voxels.length);
        }

        ensureWorldSectionBindings();

        // WorldSection coords at the target level
        int wsX = sectionX >> (lvl + 1);
        int wsY = sectionY >> (lvl + 1);
        int wsZ = sectionZ >> (lvl + 1);

        try {
            // Acquire (or create) the WorldSection at the target level
            Object worldSection = VoxyEngine.acquireMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);

            // Compute base offset within the 32³ grid
            int mask = (1 << (lvl + 1)) - 1;
            int bx = (sectionX & mask) << (4 - lvl);
            int by = (sectionY & mask) << (4 - lvl);
            int bz = (sectionZ & mask) << (4 - lvl);

            // Octant-level merge: if Voxy already has data for this 16³ sub-cube
            // (the corresponding nonEmptyChildren bit is set), preserve its data
            // and return without writing.
            int octXHalf = (bx >= 16) ? 1 : 0;
            int octZHalf = (bz >= 16) ? 1 : 0;
            int octYHalf = (by >= 16) ? 1 : 0;
            int octant = octXHalf | (octZHalf << 1) | (octYHalf << 2);
            byte childBit = (byte)(1 << octant);
            if ((readNec(worldSection) & childBit) != 0) {
                VoxyEngine.worldSectionReleaseMethod.invoke(worldSection);
                return 0;
            }

            // Get the raw 32³ data array
            long[] data = (long[]) worldSectionDataField.get(worldSection);

            // Write voxels into the correct sub-region
            int nonAir = 0;
            int srcIdx = 0;
            for (int ly = 0; ly < cellsPerAxis; ly++) {
                for (int lz = 0; lz < cellsPerAxis; lz++) {
                    for (int lx = 0; lx < cellsPerAxis; lx++) {
                        int dstIdx = (bx + lx) | ((bz + lz) << 5) | ((by + ly) << 10);
                        data[dstIdx] = voxels[srcIdx];
                        if (!isAir(voxels[srcIdx])) {
                            nonAir++;
                        }
                        srcIdx++;
                    }
                }
            }

            // Set the nonEmptyChildren bit for the octant we just wrote to.
            if (nonAir > 0) {
                mergeNonEmptyChildren(worldSection, childBit);
            }

            // Mark dirty → triggers save + mesh rebuild
            markDirty(worldEngine, worldSection,
                    BLOCK_UPDATE_FLAG | (nonAir > 0 ? CHILD_EXISTENCE_UPDATE_FLAG : 0));

            // Release the WorldSection reference
            VoxyEngine.worldSectionReleaseMethod.invoke(worldSection);

            return nonAir;

        } catch (Exception e) {
            throw new RuntimeException("writeAtLevel failed at lvl=" + lvl
                    + " section=(" + sectionX + "," + sectionY + "," + sectionZ + ")", e);
        }
    }

    /** Merge one complete child mask after every child outcome is terminal. */
    static void publishCompleteChildMask(Object worldEngine, int parentLvl,
                                         int parentWsX, int parentWsY, int parentWsZ,
                                         int completeMask) {
        if (parentLvl < 1 || parentLvl > 4) {
            throw new IllegalArgumentException("parent level must be 1..4");
        }
        if ((completeMask & ~0xFF) != 0) {
            throw new IllegalArgumentException("child mask must fit eight children");
        }
        ensureWorldSectionBindings();
        try {
            Object parentSection = VoxyEngine.acquireMethod.invoke(
                    worldEngine, parentLvl, parentWsX, parentWsY, parentWsZ);
            byte storedChildren = computeStoredChildDataMask(
                    worldEngine, parentLvl, parentWsX, parentWsY, parentWsZ);
            byte finalMask = completeHandoffMask((byte) completeMask, storedChildren);
            if (publishCompleteChildMaskToSection(parentSection, finalMask)) {
                markDirty(worldEngine, parentSection, completeHandoffUpdateFlags(false));
            }
            VoxyEngine.worldSectionReleaseMethod.invoke(parentSection);
        } catch (Exception e) {
            throw new RuntimeException("publishCompleteChildMask failed at parent lvl=" + parentLvl
                    + " ws=(" + parentWsX + "," + parentWsY + "," + parentWsZ + ")", e);
        }
    }

    /** Publish topology before releasing ownership so observers cannot see a handoff void. */
    private static boolean publishCompleteChildMaskToSection(Object parentSection, byte finalMask) {
        if (!shouldPublishCompleteHandoff(finalMask)) {
            return false;
        }
        boolean changed = mergeNonEmptyChildren(parentSection, finalMask);
        byte publishedMask = (byte) worldSectionNecVarHandle.getVolatile(parentSection);
        // Sparse publication still relies on the parent for every clear bit.
        // Ownership ends only after every octant has a child fallback.
        boolean released = publishedMask == (byte) 0xFF
                && VoxyTopologyOwnership.releaseAfterHandoff(parentSection);
        return changed || released;
    }

    /**
     * Write all 32³ voxels directly into a Voxy {@code WorldSection} at a
     * specific LOD level, using WorldSection coordinates (not L0 section coords).
     *
     * <p>This is the natural write path for octree model output, whose 32³
     * grid maps 1:1 to a Voxy WorldSection at the same level. Parent child
     * existence is published only by {@link #publishCompleteChildMask} after
     * a complete refinement batch.
     *
     * @param worldEngine the Voxy WorldEngine instance
     * @param lvl         Voxy storage level (0–4)
     * @param wsX         WorldSection X at this level
     * @param wsY         WorldSection Y at this level
     * @param wsZ         WorldSection Z at this level
     * @param voxels      packed 64-bit voxels, exactly {@code 32*32*32} entries,
     *                    indexed as {@code (y<<10)|(z<<5)|x}
     * @return number of non-air voxels written
     */
    public static int writeFullWorldSection(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ,
                                             long[] voxels) {
        return writeFullWorldSection(worldEngine, lvl, wsX, wsY, wsZ, voxels, (byte) 0);
    }

    public static int writeFullWorldSection(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ,
                                             long[] voxels,
                                             byte preserveOctantsMask) {
        if (lvl < 0 || lvl > 4) {
            throw new IllegalArgumentException(
                    "writeFullWorldSection: lvl must be 0-4, got " + lvl);
        }
        if (voxels.length != 32 * 32 * 32) {
            throw new IllegalArgumentException(
                    "writeFullWorldSection: expected 32768 voxels, got " + voxels.length);
        }

        ensureWorldSectionBindings();

        int nonAir = 0;
        try {
                // Determine existing section ownership markers.
                byte existingNec = 0;
            Object existingSection = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            if (existingSection != null) {
                existingNec = readNec(existingSection);
                if (lvl == 0 && existingNec == (byte) 0xFF) {
                    // L0 NEC=0xFF means "has some blocks", NOT "all 8 octants full".
                    // Scan actual voxel data to find which octants have real blocks.
                    long[] existingData = (long[]) worldSectionDataField.get(existingSection);
                    byte occupied = computeOccupiedOctantMask(existingData);
                    VoxyEngine.worldSectionReleaseMethod.invoke(existingSection);
                    if (occupied == (byte) 0xFF) {
                        return 0; // every octant genuinely has blocks
                    }
                    // Preserve only the octants that actually contain data.
                    preserveOctantsMask |= occupied;
                } else {
                    VoxyEngine.worldSectionReleaseMethod.invoke(existingSection);
                    // For L1-4, do not trust existing NEC blindly: stale masks from older
                    // runs may advertise children that do not actually exist. Skip only when
                    // all child world-sections are truly present.
                    if (lvl > 0) {
                        byte childMask = computeChildExistenceMask(worldEngine, lvl, wsX, wsY, wsZ);
                        if (childMask == (byte) 0xFF) {
                            return 0;
                        }
                    }
                }
            }

            for (long voxel : voxels) {
                if (!isAir(voxel)) {
                    nonAir++;
                }
            }

            // Nothing useful to write — model predictions for unclaimed octants
            // are all-air.  Skip acquiring and dirtying the section.
            if (nonAir == 0) {
                return 0;
            }

            // Acquire (or create) the WorldSection and claim its topology before
            // any generated nonterminal fallback can be dirtied for rendering.
            Object worldSection = VoxyEngine.acquireMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            byte preserveMask = preserveOctantsMask;
            if (lvl > 0) {
                // Preserve physical child data, not the parent's advertised topology.
                // Stored finer sections and this section's coarse voxel octants are
                // separate representations even when they cover the same space.
                preserveMask |= computeStoredChildDataMask(worldEngine, lvl, wsX, wsY, wsZ);
            }
            writeAcquiredWorldSection(
                    worldSection,
                    lvl,
                    voxels,
                    preserveMask,
                    (section, flags) -> markDirty(worldEngine, section, flags));
            VoxyEngine.worldSectionReleaseMethod.invoke(worldSection);

        } catch (Exception e) {
            throw new RuntimeException(
                    "writeFullWorldSection failed at lvl=" + lvl
                    + " ws=(" + wsX + "," + wsY + "," + wsZ + ")", e);
        }

        return nonAir;
    }

    @FunctionalInterface
    interface SectionDirtyNotifier {
        void mark(Object worldSection, int updateFlags) throws Exception;
    }

    static void writeAcquiredWorldSection(Object worldSection,
                                          int lvl,
                                          long[] voxels,
                                          byte preserveOctantsMask,
                                          SectionDirtyNotifier dirtyNotifier) {
        ensureWorldSectionBindings();
        try {
            writeAcquiredWorldSectionInternal(
                    worldSection, lvl, voxels, preserveOctantsMask, dirtyNotifier);
        } catch (Exception e) {
            throw new IllegalStateException("Could not write acquired Voxy section", e);
        }
    }

    private static void writeAcquiredWorldSectionInternal(Object worldSection,
                                                           int lvl,
                                                           long[] voxels,
                                                           byte preserveOctantsMask,
                                                           SectionDirtyNotifier dirtyNotifier)
            throws Exception {
        boolean topologyChanged = lvl > Level.L0.value()
                && claimGeneratedFallback(worldSection, lvl);
        long[] data = (long[]) worldSectionDataField.get(worldSection);
        byte preserveMask = preserveOctantsMask;
        if (lvl == 0) {
            // At L0, preserve octants that already contain vanilla voxel data.
            preserveMask |= computeOccupiedOctantMask(data);
        }

        if (preserveMask == 0) {
            System.arraycopy(voxels, 0, data, 0, voxels.length);
        } else {
            copyUnpreservedOctants(voxels, data, preserveMask);
        }

        // nonEmptyChildren is renderer topology, not occupancy inside this
        // 32^3 array. L1-L4 generated fallback remains a leaf with NEC=0
        // until publishCompleteChildMask atomically advertises finer sections.
        if (lvl == 0 && containsNonAir(data)) {
            // Never replace NEC with a stale read. Native ingestion may update
            // it concurrently; this monotonic CAS only adds L0's terminal state.
            topologyChanged |= mergeNonEmptyChildren(worldSection, (byte) 0xFF);
        }

        dirtyNotifier.mark(worldSection, generatedFallbackUpdateFlags(topologyChanged));
    }

    private static void copyUnpreservedOctants(long[] source, long[] destination, byte preserveMask) {
        for (int octant = 0; octant < 8; octant++) {
            if ((preserveMask & (byte) (1 << octant)) != 0) {
                continue;
            }
            int ox = (octant & 1) * 16;
            int oz = ((octant >> 1) & 1) * 16;
            int oy = ((octant >> 2) & 1) * 16;
            for (int iy = oy; iy < oy + 16; iy++) {
                for (int iz = oz; iz < oz + 16; iz++) {
                    int base = (iy << 10) | (iz << 5) | ox;
                    System.arraycopy(source, base, destination, base, 16);
                }
            }
        }
    }

    /** Claim a fallback and report whether stale/native child topology was cleared. */
    private static boolean claimGeneratedFallback(Object worldSection, int lvl) throws Exception {
        if (!VoxyTopologyOwnership.registerGeneratedFallback(worldSection, lvl)) {
            return false;
        }
        return normalizeOwnedFallbackTopology(worldSection);
    }

    static boolean claimGeneratedFallbackForTest(Object worldSection, int lvl) {
        ensureWorldSectionBindings();
        try {
            return claimGeneratedFallback(worldSection, lvl);
        } catch (Exception e) {
            throw new IllegalStateException("Could not claim raw Voxy section", e);
        }
    }

    static boolean publishCompleteChildMaskForTest(Object parentSection, byte finalMask) {
        ensureWorldSectionBindings();
        return publishCompleteChildMaskToSection(parentSection, finalMask);
    }

    /**
     * An owned L1-L4 section remains a leaf even when vanilla data already
     * populated its descendants. The promotion gate serializes this CAS against
     * native {@code updateEmptyChildState}; voxel and mip data are untouched.
     */
    private static boolean normalizeOwnedFallbackTopology(Object worldSection) throws Exception {
        if (worldSectionNecVarHandle == null) {
            throw new IllegalStateException("Voxy nonEmptyChildren VarHandle is unavailable");
        }
        byte previous;
        do {
            previous = (byte) worldSectionNecVarHandle.getVolatile(worldSection);
            if (previous == 0) {
                return false;
            }
        } while (!worldSectionNecVarHandle.compareAndSet(worldSection, previous, (byte) 0));
        return true;
    }

    static byte fallbackPresentationNec(boolean ownedFallback, byte currentNec) {
        return ownedFallback ? 0 : currentNec;
    }

    static byte completeHandoffMask(byte generatedChildren, byte storedChildren) {
        return mergeChildMasks(generatedChildren, storedChildren);
    }

    private static byte computeChildExistenceMask(Object worldEngine, int lvl,
                                                  int wsX, int wsY, int wsZ) throws Exception {
        if (lvl <= 0) {
            return 0;
        }

        int childLvl = lvl - 1;
        byte mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int childWsX = (wsX << 1) + (octant & 1);
            int childWsY = (wsY << 1) + ((octant >> 2) & 1);
            int childWsZ = (wsZ << 1) + ((octant >> 1) & 1);

            Object childSection = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, childLvl, childWsX, childWsY, childWsZ);
            if (childSection != null) {
                // Match Voxy's own semantic (DebugUtils.java L63):
                // bit i is set only if the child's own NEC is non-zero,
                // not merely if the section object exists in the LRU cache.
                byte childNec = readNec(childSection);
                VoxyEngine.worldSectionReleaseMethod.invoke(childSection);
                if (childNec != 0) {
                    mask |= (byte) (1 << octant);
                }
            }
        }
        return mask;
    }

    /**
     * Derive child presence from stored voxel data rather than parent NEC. This
     * remains valid while a generated child is deliberately presented as a leaf.
     */
    private static byte computeStoredChildDataMask(Object worldEngine, int lvl,
                                                   int wsX, int wsY, int wsZ) throws Exception {
        if (lvl <= 0) {
            return 0;
        }
        int childLvl = lvl - 1;
        byte mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int childWsX = (wsX << 1) + (octant & 1);
            int childWsY = (wsY << 1) + ((octant >> 2) & 1);
            int childWsZ = (wsZ << 1) + ((octant >> 1) & 1);
            Object childSection = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, childLvl, childWsX, childWsY, childWsZ);
            if (childSection == null) {
                continue;
            }
            try {
                long[] childData = (long[]) worldSectionDataField.get(childSection);
                if (containsNonAir(childData)) {
                    mask |= (byte) (1 << octant);
                }
            } finally {
                VoxyEngine.worldSectionReleaseMethod.invoke(childSection);
            }
        }
        return mask;
    }

    private static boolean containsNonAir(long[] data) {
        for (long voxel : data) {
            if (!isAir(voxel)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Scan a packed 32³ voxel array for occupancy in its eight 16³ octants.
     * This remains a data-preservation helper; it is not used to advertise
     * future finer children in {@code nonEmptyChildren}.
     */
    private static byte computeOccupiedOctantMask(long[] data) {
        byte mask = 0;
        for (int octant = 0; octant < 8; octant++) {
            int ox = (octant & 1) * 16;
            int oz = ((octant >> 1) & 1) * 16;
            int oy = ((octant >> 2) & 1) * 16;
            boolean hasBlock = false;
            for (int iy = oy; iy < oy + 16 && !hasBlock; iy++) {
                for (int iz = oz; iz < oz + 16 && !hasBlock; iz++) {
                    for (int ix = ox; ix < ox + 16; ix++) {
                        if (!isAir(data[(iy << 10) | (iz << 5) | ix])) {
                            hasBlock = true;
                            break;
                        }
                    }
                }
            }
            if (hasBlock) {
                mask |= (byte) (1 << octant);
            }
        }
        return mask;
    }

    /**
     * Keep renderer advertisement separate from scheduler refinement intent.
     * The only input is the mask read from already stored child sections.
     */
    static byte advertisedChildMask(byte storedChildren) {
        return storedChildren;
    }

    static int generatedFallbackUpdateFlags(boolean topologyChanged) {
        return BLOCK_UPDATE_FLAG | (topologyChanged ? CHILD_EXISTENCE_UPDATE_FLAG : 0);
    }

    static int completeHandoffUpdateFlags(boolean parentGeometryChanged) {
        return CHILD_EXISTENCE_UPDATE_FLAG | (parentGeometryChanged ? BLOCK_UPDATE_FLAG : 0);
    }

    static boolean shouldPublishCompleteHandoff(byte completeMask) {
        return completeMask != 0;
    }

    private static void markDirty(Object worldEngine, Object worldSection, int updateFlags)
            throws Exception {
        VoxyEngine.markDirtyWithFlagsMethod.invoke(worldEngine, worldSection, updateFlags, 0);
    }

    /**
     * Read {@code nonEmptyChildren} from an acquired {@code WorldSection} instance.
     * <p>The section must be held acquired (reference count) by the caller.
     */
    private static byte readNec(Object worldSection) throws Exception {
        if (worldSectionNecVarHandle != null) {
            return (byte)(Byte) worldSectionNecVarHandle.get(worldSection);
        } else {
            return worldSectionNonEmptyChildrenField.getByte(worldSection);
        }
    }

    /**
     * Duplicated-Code fix: centralize CAS on {@code nonEmptyChildren} (NEC).
     * Mirrors {@code WorldSection.updateEmptyChildState()} with a VarHandle CAS
     * loop. Returns true if a requested bit was newly set.
     * Caller is responsible for {@code markDirty(worldEngine, section)} when true.
     */
    static boolean mergeNonEmptyChildren(Object worldSection, byte childMask) {
        if (worldSection == null || childMask == 0) return false;
        if (worldSectionNecVarHandle != null) {
            byte prev;
            byte next;
            do {
                prev = (byte) worldSectionNecVarHandle.getVolatile(worldSection);
                if ((prev & childMask) == childMask) return false;
                next = mergeChildMasks(prev, childMask);
            } while (!worldSectionNecVarHandle.compareAndSet(worldSection, prev, next));
            return (prev & childMask) != childMask;
        }
        throw new IllegalStateException("Voxy nonEmptyChildren VarHandle is unavailable");
    }

    static byte mergeChildMasks(byte existingMask, byte committedMask) {
        return (byte) (existingMask | committedMask);
    }

    /**
     * Feature-Envy fix: parent octant extract+upsample lives on the binding.
     */
    static long[] readAndUpsampleParentOctant(Object worldEngine, int parentLevel, int pX, int pY, int pZ, int octant) {
        int[][][] blocks = readWorldSectionBlocks(worldEngine, parentLevel, pX, pY, pZ);
        if (blocks == null) return null;
        return extractOctantAndUpsample(blocks, octant);
    }

    /**
     * Returns the bitmask of which child WorldSections actually exist and
     * have non-zero NEC.  Bit layout: bit0=X, bit1=Z, bit2=Y.
     *
     * @return 0x00–0xFF child existence mask, or 0 on error
     */
    public static byte getChildExistenceMask(Object worldEngine, int lvl,
                                              int wsX, int wsY, int wsZ) {
        ensureWorldSectionBindings();
        try {
            return computeChildExistenceMask(worldEngine, lvl, wsX, wsY, wsZ);
        } catch (Exception e) {
            LOGGER.warn("getChildExistenceMask failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Returns the octant occupancy mask for an existing WorldSection's voxel data.
     * For L0 this is the only way to tell whether all 8 octants actually contain data.
     */
    public static byte getOccupiedOctantMask(Object worldEngine, int lvl,
                                             int wsX, int wsY, int wsZ) {
        ensureWorldSectionBindings();
        try {
            Object section = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            if (section == null) {
                return 0;
            }
            long[] data = (long[]) worldSectionDataField.get(section);
            byte mask = computeOccupiedOctantMask(data);
            VoxyEngine.worldSectionReleaseMethod.invoke(section);
            return mask;
        } catch (Exception e) {
            LOGGER.warn("getOccupiedOctantMask failed: {}", e.getMessage());
            return 0;
        }
    }

    /**
     * Returns {@code true} if Voxy has fully claimed all 8 octants of the specified
     * WorldSection ({@code nonEmptyChildren == 0xFF}).  This is the correct guard
     * for skipping model inference entirely: if all octants are already populated,
     * there is nothing for LODiffusion to contribute.
     *
     * <p>At L0 {@code nonEmptyChildren} is whole-section (0 or 0xFF), not per-octant;
     * any non-zero value is treated as fully claimed.
     *
     * @param worldEngine the Voxy WorldEngine instance
     * @param lvl         storage level (0–4)
     * @param wsX         WorldSection X at this level
     * @param wsY         WorldSection Y at this level
     * @param wsZ         WorldSection Z at this level
     * @return {@code true} when all octants are already populated
     */
    public static boolean allOctantsPopulated(Object worldEngine, int lvl,
                                               int wsX, int wsY, int wsZ) {
        ensureWorldSectionBindings();
        try {
            Object section = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            if (section == null) return false;
            if (lvl == 0) {
                long[] data = (long[]) worldSectionDataField.get(section);
                byte occupied = computeOccupiedOctantMask(data);
                VoxyEngine.worldSectionReleaseMethod.invoke(section);
                return occupied == (byte) 0xFF;
            }

            VoxyEngine.worldSectionReleaseMethod.invoke(section);

            // True completeness for L1+: every child world-section exists.
            return computeChildExistenceMask(worldEngine, lvl, wsX, wsY, wsZ) == (byte) 0xFF;
        } catch (Exception e) {
            LOGGER.warn("allOctantsPopulated check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check whether a Voxy {@code WorldSection} exists at a specific level and
     * WorldSection coordinate.
     *
     * @param worldEngine the Voxy WorldEngine
     * @param lvl         storage level (0–4)
     * @param wsX         WorldSection X at this level
     * @param wsY         WorldSection Y at this level
     * @param wsZ         WorldSection Z at this level
     * @return {@code true} if Voxy already holds data at this level/position
     */
    public static boolean sectionExistsAtLevel(Object worldEngine, int lvl,
                                                int wsX, int wsY, int wsZ) {
        ensureWorldSectionBindings();
        try {
            Object section = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            if (section != null) {
                VoxyEngine.worldSectionReleaseMethod.invoke(section);
                return true;
            }
            return false;
        } catch (Exception e) {
            LOGGER.warn("sectionExistsAtLevel check failed: " + e.getMessage());
            return false;
        }
    }

    /**
     * Read block IDs from a stored 32^3 WorldSection at a given level.
     *
     * @return int[32][32][32] in [Y][Z][X] order, or null when absent
     */
    public static int[][][] readWorldSectionBlocks(Object worldEngine, int lvl,
                                                   int wsX, int wsY, int wsZ) {
        ensureWorldSectionBindings();
        try {
            Object section = VoxyEngine.acquireIfExistsMethod.invoke(
                    worldEngine, lvl, wsX, wsY, wsZ);
            if (section == null) {
                return null;
            }

            long[] data = (long[]) worldSectionDataField.get(section);
            int[][][] out = new int[32][32][32];
            for (int y = 0; y < 32; y++) {
                for (int z = 0; z < 32; z++) {
                    for (int x = 0; x < 32; x++) {
                        int idx = (y << 10) | (z << 5) | x;
                        long voxel = data[idx];
                        out[y][z][x] = (int) ((voxel & BLOCK_ID_MASK) >> BLOCK_ID_SHIFT);
                    }
                }
            }

            VoxyEngine.worldSectionReleaseMethod.invoke(section);
            return out;
        } catch (Exception e) {
            LOGGER.warn("readWorldSectionBlocks failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Extract one 16^3 octant from a parent 32^3 block-id grid and nearest-neighbor
     * upsample back to 32^3 for child model parent input.
     *
     * Octant bit layout: bit0=x, bit1=z, bit2=y.
     */
    public static long[] extractOctantAndUpsample(int[][][] parent32, int octant) {
        long[] out = new long[32 * 32 * 32];

        int ox = (octant & 1) * 16;
        int oz = ((octant >> 1) & 1) * 16;
        int oy = ((octant >> 2) & 1) * 16;

        int i = 0;
        for (int y = 0; y < 32; y++) {
            int py = oy + (y >> 1);
            for (int z = 0; z < 32; z++) {
                int pz = oz + (z >> 1);
                for (int x = 0; x < 32; x++) {
                    int px = ox + (x >> 1);
                    out[i++] = parent32[py][pz][px];
                }
            }
        }
        return out;
    }
}
