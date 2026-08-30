package com.rhythmatician.voxygen.backend.voxy;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.semantic.CanonicalRegistries;
import com.rhythmatician.voxygen.output.VoxelVolumeWriter;
import com.rhythmatician.voxygen.output.WriteOutcome;
import com.rhythmatician.voxygen.output.VolumeUnavailableException;
import com.rhythmatician.voxygen.semantic.WorldSectionCoord;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;
import com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome;
import com.rhythmatician.voxygen.semantic.biome.BiomeMapping;
import com.rhythmatician.voxygen.generation.refinement.CompleteChildHandoff;
import com.rhythmatician.voxygen.worldgen.heightmap.HeightmapFallbackGenerator;
import com.rhythmatician.voxygen.inference.onnx.VoxelPredictionDecoder;

/**
 * Production {@link VoxelVolumeWriter} that translates semantic {@link VoxelVolume}
 * into Voxy''s packed storage.
 *
 * <p>Owns all Voxy-specific mechanics:
 * canonical -&gt; Voxy Mapper translation, 32^3 WorldSection storage mapping,
 * YZX indexing, packed long voxel layout (block/biome/light bits), light defaults,
 * reflection/MethodHandle access, CAS, nonEmptyChildren, markDirty, mip/update lifecycle.
 *
 * <p>Public API never exposes Voxy types: only {@link SectionPos}, {@link Level},
 * {@link VoxelVolume}, {@link WriteOutcome}.
 *
 * <p><b>Primitive Obsession note (Fowler):</b> {@code worldEngine} and
 * {@code voxyMapper} are typed as {@code Object} intentionally — Voxy's
 * {@code WorldEngine} and {@code Mapper} are not on the compile classpath.
 * This is a <em>deep-module seam</em>: production types are erased to
 * {@code Object} at the boundary and accessed only via reflection in
 * {@link VoxyEngine}/{@link VoxyWorldBinding}. A wrapper like
 * {@code WorldEngineHandle} would add indirection without safety (still
 * reflection underneath). The erasure is intentional; see
 * {@link VoxyCompat#getMapper(Object)} and {@link VoxyEngine#ensureEngineBindings()}.
 * Type aliases:
 * <ul>
 *   <li>{@code Object worldEngine} — Voxy {@code WorldEngine} handle (WorldEngineHandle)</li>
 *   <li>{@code Object voxyMapper} — Voxy {@code Mapper} handle (VoxyMapperHandle)</li>
 * </ul>
 */
@SuppressWarnings("deprecation")
public final class RealVoxyVolumeWriter implements VoxelVolumeWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealVoxyVolumeWriter.class);
    private static final int DEFAULT_LIGHT = 0x0F;

    private final Object worldEngine;
    private final Object voxyMapper;
    // Data Clumps fix: two maps travel together — grouped as VoxyIdMaps (see below).
    private final int[] canonicalBiomeToVoxy;
    private final int[] canonicalBlockToVoxy;
    private final RegionBackend regionBackend;

    public interface RegionBackend {
        boolean isAvailable();

        boolean isFullyPopulated(Object worldEngine, SectionPos origin, Level level);

        boolean hasCoverage(Object worldEngine, SectionPos origin, Level level);

        int writeFullWorldSection(
                Object worldEngine, Level level, int wsX, int wsY, int wsZ, long[] voxels,
                byte preserveOctantsMask);

        void publishCompleteChildMask(
                Object worldEngine, Level parentLevel, int wsX, int wsY, int wsZ,
                CompleteChildHandoff handoff);
    }

    private static final RegionBackend VOXY_REGION_BACKEND = new RegionBackend() {
        @Override
        public boolean isAvailable() {
            return VoxyCompat.isAvailable();
        }

        @Override
        public boolean isFullyPopulated(Object worldEngine, SectionPos origin, Level level) {
            int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
            int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
            int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
            if (level == Level.L0) {
                return checkExistsViaAcquire(worldEngine, level.value(), wsX, wsY, wsZ);
            }
            return VoxyCompat.allOctantsPopulated(worldEngine, level.value(), wsX, wsY, wsZ);
        }

        @Override
        public boolean hasCoverage(Object worldEngine, SectionPos origin, Level level) {
            int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
            int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
            int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
            return VoxyCompat.sectionExistsAtLevel(
                    worldEngine, level.value(), wsX, wsY, wsZ);
        }

        @Override
        public int writeFullWorldSection(
                Object worldEngine, Level level, int wsX, int wsY, int wsZ, long[] voxels,
                byte preserveOctantsMask) {
            return VoxyCompat.writeFullWorldSection(
                    worldEngine, level.value(), wsX, wsY, wsZ, voxels, preserveOctantsMask);
        }

        @Override
        public void publishCompleteChildMask(
                Object worldEngine, Level parentLevel, int wsX, int wsY, int wsZ,
                CompleteChildHandoff handoff) {
            VoxyWorldBinding.publishCompleteChildMask(
                    worldEngine, parentLevel.value(), wsX, wsY, wsZ, handoff);
        }
    };

    /**
     * @param worldEngine Voxy WorldEngine (from {@link VoxyCompat#getWorldEngine})
     * @param voxyMapper Voxy Mapper (from {@link VoxyCompat#getMapper})
     * @param canonicalBiomeToVoxy size 54 mapping canonical biome 0..53 -&gt; Voxy biome ID
     * @param canonicalBlockToVoxy size {@value CanonicalRegistries#BLOCK_COUNT} mapping canonical block -&gt; Voxy block ID
     */
    public RealVoxyVolumeWriter(
            Object worldEngine, Object voxyMapper, int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        this(worldEngine, voxyMapper, canonicalBiomeToVoxy, canonicalBlockToVoxy, VOXY_REGION_BACKEND);
    }

    public RealVoxyVolumeWriter(
            Object worldEngine,
            Object voxyMapper,
            int[] canonicalBiomeToVoxy,
            int[] canonicalBlockToVoxy,
            RegionBackend regionBackend) {
        this.worldEngine = Objects.requireNonNull(worldEngine, "worldEngine");
        this.voxyMapper = Objects.requireNonNull(voxyMapper, "voxyMapper");
        this.canonicalBiomeToVoxy =
                Objects.requireNonNull(canonicalBiomeToVoxy, "canonicalBiomeToVoxy").clone();
        this.canonicalBlockToVoxy =
                Objects.requireNonNull(canonicalBlockToVoxy, "canonicalBlockToVoxy").clone();
        this.regionBackend = Objects.requireNonNull(regionBackend, "regionBackend");
        if (this.canonicalBiomeToVoxy.length != CanonicalRegistries.BIOME_COUNT) {
            throw new IllegalArgumentException(
                    "canonicalBiomeToVoxy must be size " + CanonicalRegistries.BIOME_COUNT);
        }
        if (this.canonicalBlockToVoxy.length != CanonicalRegistries.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "canonicalBlockToVoxy must be size " + CanonicalRegistries.BLOCK_COUNT);
        }
    }

    /**
     * Convenience overload that accepts grouped {@link VoxyIdMaps}.
     */
    public RealVoxyVolumeWriter(Object worldEngine, Object voxyMapper, VoxyIdMaps idMaps) {
        this(worldEngine, voxyMapper, idMaps.biomeMapRaw().clone(), idMaps.blockMapRaw().clone());
    }

    /**
     * Factory that hides the {@code VoxyCompat.getMapper(worldEngine) -> VoxyEngine -> reflection}
     * chain (Fowler Message Chains). Callers no longer navigate {@code VoxyCompat -> VoxyEngine -> reflection}.
     * This is the preferred creation path for demand/fallback pipelines.
     *
     * @param worldEngine Voxy WorldEngine handle (WorldEngineHandle typedef — {@code Object} erasure intentional)
     * @param idMaps grouped canonical-to-Voxy ID maps
     * @return ready-to-write {@link RealVoxyVolumeWriter}
     */
    public static RealVoxyVolumeWriter create(Object worldEngine, VoxyIdMaps idMaps) {
        java.util.Objects.requireNonNull(worldEngine, "worldEngine");
        java.util.Objects.requireNonNull(idMaps, "idMaps");
        Object mapper = VoxyCompat.getMapper(worldEngine);
        return new RealVoxyVolumeWriter(worldEngine, mapper, idMaps);
    }

    /**
     * Overload for callers that have not yet grouped maps into {@link VoxyIdMaps}.
     */
    public static RealVoxyVolumeWriter create(Object worldEngine, int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        return create(worldEngine, new VoxyIdMaps(canonicalBiomeToVoxy, canonicalBlockToVoxy));
    }

    /**
     * Build fallback block map (canonical 0..1103 -&gt; Voxy) from fallback voxy IDs.
     *
     * <p>Only 12 canonical IDs are used by {@link VoxelPredictionDecoder} fallback path;
     * remaining entries stay 0 (air) which is correct because fallback never emits those IDs.
     * Canonical IDs come from {@link VoxelPredictionDecoder.FallbackPalette#defaults()}
     * (verified against {@code python/config/voxy_vocab.json}) so the two stay in sync.
     */
    public static int[] buildFallbackBlockMap(HeightmapFallbackGenerator.FallbackBlockIds voxyIds) {
        int[] map = new int[CanonicalRegistries.BLOCK_COUNT];
        VoxelPredictionDecoder.FallbackPalette palette = VoxelPredictionDecoder.FallbackPalette.defaults();
        map[palette.air()] = voxyIds.air();
        map[palette.stone()] = voxyIds.stone();
        map[palette.deepslate()] = voxyIds.deepslate();
        map[palette.dirt()] = voxyIds.dirt();
        map[palette.grassBlock()] = voxyIds.grassBlock();
        map[palette.sand()] = voxyIds.sand();
        map[palette.water()] = voxyIds.water();
        map[palette.redSand()] = voxyIds.redSand();
        map[palette.gravel()] = voxyIds.gravel();
        map[palette.snowLayer()] = voxyIds.snowLayer();
        map[palette.podzol()] = voxyIds.podzol();
        map[palette.mycelium()] = voxyIds.mycelium();
        return map;
    }

    public static int[] buildBiomeMap(
            Object voxyMapper, net.minecraft.registry.Registry<net.minecraft.world.biome.Biome> biomeRegistry) {
        return VoxyBlockMapper.resolveBiomeMappings(voxyMapper, biomeRegistry);
    }

    public Object getWorldEngine() {
        return worldEngine;
    }

    @Override
    public int saveQueueDepth() {
        return VoxyCompat.getSaveQueueDepth(worldEngine);
    }

    @Override
    public boolean isRegionFullyPopulated(SectionPos origin, Level level) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(level, "level");
        return regionBackend.isFullyPopulated(worldEngine, origin, level);
    }

    @Override
    public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 16) {
            throw new IllegalArgumentException("writeSection requires extent 16, got " + volume.extent());
        }
        try {
            if (!VoxyCompat.isAvailable()) {
                throw new VolumeUnavailableException("Voxy not available");
            }
            if (VoxyCompat.sectionExists(worldEngine, pos.x(), pos.y(), pos.z())) {
                return WriteOutcome.skippedExists();
            }
        } catch (VolumeUnavailableException e) {
            throw e;
        } catch (IllegalStateException | LinkageError e) {
            throw new VolumeUnavailableException("Voxy not available: " + e.getMessage(), e);
        }
        if (volume.isAllAir()) {
            return WriteOutcome.skippedAir();
        }
        int nonAir = writeSectionInternal(pos, volume);
        if (nonAir == 0) {
            return WriteOutcome.skippedAir();
        }
        return WriteOutcome.written(nonAir);
    }

    @Override
    public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 32) {
            throw new IllegalArgumentException("writeRegion requires extent 32, got " + volume.extent());
        }
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level + " regionSections=" + level.regionSections());
        }
        try {
            return materializeRegion(origin, level, volume, (byte) 0).asWriteOutcome();
        } catch (VolumeUnavailableException e) {
            throw e;
        } catch (IllegalStateException | LinkageError e) {
            throw new VolumeUnavailableException("Voxy not available: " + e.getMessage(), e);
        }
    }

    @Override
    public ParentRefinementResult refineParent(ParentRefinementIntent intent) {
        Objects.requireNonNull(intent, "intent");
        if (!hasRegionCoverage(intent.parentOrigin(), intent.parentLevel())) {
            return ParentRefinementResult.parentMissing();
        }
        ParentRefinementBatch batch = ParentRefinementBatch.materialize(intent);
        WriteOutcome outcome = commitParentRefinement(batch);
        return ParentRefinementResult.published(
                outcome, batch.nonEmptyMask(), batch.requiredMask() & ~batch.nonEmptyMask());
    }

    private WriteOutcome commitParentRefinement(ParentRefinementBatch batch) {
        int nonAir = 0;
        for (ParentRefinementBatch.Child child : batch.children()) {
            ChildMaterializationOutcome outcome = materializeRegion(
                    child.origin(), Level.values()[batch.childLevel()], child.volume(), (byte) 0);
            batch.recordTerminal(child.octant(), outcome);
            nonAir += outcome.nonAirWritten();
        }
        if (!batch.isComplete()) {
            throw new IllegalStateException("parent refinement completed without all child outcomes");
        }

        int parentLevel = batch.parentLevel().value();
        int parentWsX = WorldSectionCoord.sectionToWorldSection(
                batch.parentOrigin().x(), parentLevel);
        int parentWsY = WorldSectionCoord.sectionToWorldSection(
                batch.parentOrigin().y(), parentLevel);
        int parentWsZ = WorldSectionCoord.sectionToWorldSection(
                batch.parentOrigin().z(), parentLevel);
        // This is the only parent advertisement seam. Child writes above have
        // already dirtied their geometry; no partial mask is ever published.
        // Handoff completeness (all octants terminal) travels separately from
        // child occupancy (presentMask) so proved-empty octants are explicit.
        regionBackend.publishCompleteChildMask(
                worldEngine, batch.parentLevel(), parentWsX, parentWsY, parentWsZ,
                CompleteChildHandoff.ofMasks(batch.nonEmptyMask(),
                        batch.requiredMask() & ~batch.nonEmptyMask()));
        return batch.nonEmptyMask() == 0
                ? WriteOutcome.skippedAir()
                : WriteOutcome.written(nonAir);
    }

    @Override
    public boolean hasRegionCoverage(SectionPos origin, Level level) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(level, "level");
        return regionBackend.hasCoverage(worldEngine, origin, level);
    }

    /**
     * Check existence via {@code acquireIfExists} and {@code nonEmptyChildren == 0xFF}.
     * Delegates reflection to {@link VoxyEngine} helpers to hide the chain; falls back
     * to {@link VoxyCompat#sectionExists} on reflection failure and logs the failure
     * so SKIPPED_EXISTS is not silently lost.
     */
    private static boolean checkExistsViaAcquire(
            Object worldEngine, int lvl, int wsX, int wsY, int wsZ) {
        try {
            Object sec = VoxyEngine.acquireIfExists(worldEngine, lvl, wsX, wsY, wsZ);
            if (sec != null) {
                try {
                    boolean full = VoxyEngine.isNonEmptyChildrenFull(sec);
                    return full;
                } finally {
                    VoxyEngine.releaseSection(sec);
                }
            }
            return false;
        } catch (VolumeUnavailableException e) {
            throw e;
        } catch (IllegalStateException | LinkageError e) {
            throw new VolumeUnavailableException("Voxy not available: " + e.getMessage(), e);
        } catch (Exception e) {
            LOGGER.warn("checkExistsViaAcquire failed lvl={} ws=({},{},{}): {}", lvl, wsX, wsY, wsZ, e.toString());
            // Conservative: if we cannot verify, do not claim existence; caller will write.
            return false;
        }
    }

    private int writeSectionInternal(SectionPos pos, VoxelVolume volume) {
        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, pos.x(), pos.y(), pos.z());
        long[] data = VoxyCompat.getSectionData(section);
        int nonAir = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int voxyBlock = toVoxyBlock(volume.blockId(x, y, z));
                    int voxyBiome = toVoxyBiome(volume.biomeId(x, y, z));
                    long voxel = VoxyCompat.composeVoxel(voxyBlock, voxyBiome, DEFAULT_LIGHT);
                    data[VoxyCompat.l0Index(x, y, z)] = voxel;
                    if (voxyBlock != 0) nonAir++;
                }
            }
        }
        VoxyCompat.setNonAirCount(section, nonAir);
        VoxyCompat.mipSection(section, voxyMapper);
        VoxyCompat.insertUpdate(worldEngine, section);
        LOGGER.debug("[RealVoxy] writeSection {} nonAir={}", pos, nonAir);
        return nonAir;
    }

    /**
     * Region write carrying a caller-computed vanilla-preserve octant mask.
     * Octants named in the mask are protected from candidate overwrite by the
     * storage backend so loaded vanilla terrain survives coarse writes.
     */
    public WriteOutcome writeRegion(
            SectionPos origin, Level level, VoxelVolume volume, byte preserveOctantsMask) {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 32) {
            throw new IllegalArgumentException("writeRegion requires extent 32, got " + volume.extent());
        }
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level + " regionSections=" + level.regionSections());
        }
        try {
            return materializeRegion(origin, level, volume, preserveOctantsMask).asWriteOutcome();
        } catch (VolumeUnavailableException e) {
            throw e;
        } catch (IllegalStateException | LinkageError e) {
            throw new VolumeUnavailableException("Voxy not available: " + e.getMessage(), e);
        }
    }

    private ChildMaterializationOutcome materializeRegion(
            SectionPos origin, Level level, VoxelVolume volume, byte preserveOctantsMask) {
        if (!regionBackend.isAvailable()) {
            throw new VolumeUnavailableException("Voxy not available");
        }
        if (isRegionFullyPopulated(origin, level)) {
            return ChildMaterializationOutcome.preservedExisting();
        }
        if (volume.isAllAir()) {
            return ChildMaterializationOutcome.empty();
        }
        return writeRegionInternal(origin, level, volume, preserveOctantsMask);
    }

    private ChildMaterializationOutcome writeRegionInternal(
            SectionPos origin, Level level, VoxelVolume volume, byte preserveOctantsMask) {
        // Reusable per-thread scratch: a fresh long[32768] here is 256 KB of
        // garbage per child write — 2 MB per 8-child parent refinement. The
        // tracer pipeline runs on one worker thread; tests may call from
        // others, so ThreadLocal keeps buffers independent and reused.
        long[] voxels = regionScratchBuffer();
        int nonAir = 0;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int voxyBlock = toVoxyBlock(volume.blockId(x, y, z));
                    int voxyBiome = toVoxyBiome(volume.biomeId(x, y, z));
                    long voxel = VoxyCompat.composeVoxel(voxyBlock, voxyBiome, DEFAULT_LIGHT);
                    voxels[yzxIndex(x, y, z)] = voxel;
                    if (voxyBlock != 0) nonAir++;
                }
            }
        }
        if (nonAir == 0) return ChildMaterializationOutcome.empty();
        int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
        int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
        int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
        int written = regionBackend.writeFullWorldSection(
                worldEngine, level, wsX, wsY, wsZ, voxels, preserveOctantsMask);
        if (written == 0) {
            return ChildMaterializationOutcome.preservedExisting();
        }
        LOGGER.debug("[RealVoxy] writeRegion {} {} ws=({},{},{}) nonAir={}",
                origin, level, wsX, wsY, wsZ, written);
        return ChildMaterializationOutcome.generatedFallback(written);
    }

    /** YZX index for 32^3 WorldSection: (y<<10)|(z<<5)|x. Single source of truth. */
    public static int yzxIndex(int x, int y, int z) {
        return (y << 10) | (z << 5) | x;
    }

    private static final ThreadLocal<long[]> SCRATCH = ThreadLocal.withInitial(
            () -> new long[32 * 32 * 32]);

    /** Per-thread reusable 32^3 voxel buffer for region writes. */
    private static long[] regionScratchBuffer() {
        return SCRATCH.get();
    }

    public static long[] regionScratchBufferForTest() {
        return regionScratchBuffer();
    }

    private int toVoxyBlock(int canonical) {
        if (canonical < 0 || canonical >= canonicalBlockToVoxy.length) return 0;
        return canonicalBlockToVoxy[canonical];
    }

    private int toVoxyBiome(int canonical) {
        // Tracer-only rendering concession: End L4 deterministic tracer emits
        // BIOME_UNKNOWN (255) for all voxels; translating to plains for
        // display is tolerated only in that tracer path. Not a general contract.
        if (canonical == CanonicalRegistries.BIOME_UNKNOWN) {
            int plains = BiomeMapping.toCanonicalId("minecraft:plains");
            if (plains >= 0 && plains < canonicalBiomeToVoxy.length) {
                return canonicalBiomeToVoxy[plains];
            }
            return 0;
        }
        if (canonical < 0 || canonical >= canonicalBiomeToVoxy.length) return 0;
        return canonicalBiomeToVoxy[canonical];
    }

    // Direct writes publish completed geometry only; refinement demand is
    // owned by the screen-space selector and generation scheduler.
}
