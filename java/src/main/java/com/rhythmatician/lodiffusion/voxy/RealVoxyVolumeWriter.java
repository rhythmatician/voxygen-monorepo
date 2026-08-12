package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rhythmatician.lodiffusion.onnx.BlockVocabulary;
import net.minecraft.registry.Registry;
import net.minecraft.world.biome.Biome;

/**
 * Production {@link VoxelVolumeWriter} that owns all Voxy-specific encoding.
 *
 * <p>Hides: YZX indexing, packed long layout, mapper translation,
 * WorldSection lifecycle, CAS/VarHandle, mip/insertUpdate paths,
 * and coordinate transforms. Callers see only semantic
 * {@link SectionPos} + {@link Level} + {@link VoxelVolume}.
 */
public final class RealVoxyVolumeWriter implements VoxelVolumeWriter {

    private static final Logger LOGGER = LoggerFactory.getLogger(RealVoxyVolumeWriter.class);
    private static final int DEFAULT_LIGHT = 0x0F;

    private final Object worldEngine;
    private final Object voxyMapper;
    private final int[] canonicalBiomeToVoxy;
    private final int[] canonicalBlockToVoxy;

    public RealVoxyVolumeWriter(Object worldEngine, Object voxyMapper,
                                int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        this.worldEngine = Objects.requireNonNull(worldEngine, "worldEngine");
        this.voxyMapper = Objects.requireNonNull(voxyMapper, "voxyMapper");
        this.canonicalBiomeToVoxy = Objects.requireNonNull(canonicalBiomeToVoxy, "canonicalBiomeToVoxy");
        this.canonicalBlockToVoxy = Objects.requireNonNull(canonicalBlockToVoxy, "canonicalBlockToVoxy");
        if (canonicalBiomeToVoxy.length != CanonicalRegistries.BIOME_COUNT) {
            throw new IllegalArgumentException(
                    "canonicalBiomeToVoxy length must be " + CanonicalRegistries.BIOME_COUNT);
        }
        if (canonicalBlockToVoxy.length != CanonicalRegistries.BLOCK_COUNT) {
            throw new IllegalArgumentException(
                    "canonicalBlockToVoxy length must be " + CanonicalRegistries.BLOCK_COUNT);
        }
    }

    public Object getWorldEngine() {
        return worldEngine;
    }

    // ------------------------------------------------------------------
    // Map builders
    // ------------------------------------------------------------------

    /** Build canonical-biome → Voxy biome ID via Voxy Mapper + game registry. */
    public static int[] buildBiomeMap(Object voxyMapper, Registry<Biome> biomeRegistry) {
        // Delegate to HeightmapFallbackGenerator's existing logic then
        // also via VoxyBlockMapper path — both produce same.
        // Reuse VoxyBlockMapper's resolution so behavior stays identical;
        // we cannot call private method so replicate via HeightmapFallbackGenerator.
        return HeightmapFallbackGenerator.resolveBiomeMappings(voxyMapper, biomeRegistry);
    }

    /** Build canonical-block → Voxy block ID from {@link BlockVocabulary}. */
    public static int[] buildBlockMap(BlockVocabulary vocab, Object voxyMapper) {
        if (vocab == null || voxyMapper == null) {
            throw new NullPointerException("vocab and voxyMapper must not be null");
        }
        try {
            java.lang.reflect.Method m =
                    voxyMapper.getClass().getMethod("getIdForBlockState",
                            net.minecraft.block.BlockState.class);
            int[] vocabToVoxy = new int[vocab.size()];
            for (int i = 0; i < vocab.size(); i++) {
                Object id = m.invoke(voxyMapper, vocab.getState(i));
                vocabToVoxy[i] = id instanceof Number n ? n.intValue() : 0;
            }
            int[] canonical = new int[CanonicalRegistries.BLOCK_COUNT];
            int copy = Math.min(vocabToVoxy.length, canonical.length);
            System.arraycopy(vocabToVoxy, 0, canonical, 0, copy);
            return canonical;
        } catch (Exception e) {
            throw new RuntimeException("buildBlockMap failed", e);
        }
    }

    /**
     * Build canonical → Voxy map for fallback terrain's small palette.
     * Canonical IDs are from {@link VoxelPredictionDecoder.FallbackPalette#defaults()}
     * so canonical and fallback palette stay in sync.
     */
    public static int[] buildFallbackBlockMap(
            HeightmapFallbackGenerator.FallbackBlockIds voxyIds) {
        int[] map = new int[CanonicalRegistries.BLOCK_COUNT];
        VoxelPredictionDecoder.FallbackPalette palette =
                VoxelPredictionDecoder.FallbackPalette.defaults();
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

    // ------------------------------------------------------------------
    // VoxelVolumeWriter
    // ------------------------------------------------------------------

    @Override
    public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 16) {
            throw new IllegalArgumentException("writeSection requires extent 16, got " + volume.extent());
        }
        if (!VoxyCompat.isAvailable()) {
            throw new VolumeUnavailableException("Voxy not available");
        }
        if (worldEngine == null) {
            throw new VolumeUnavailableException("WorldEngine unavailable");
        }
        if (VoxyCompat.sectionExists(worldEngine, pos.x(), pos.y(), pos.z())) {
            return WriteOutcome.skippedExists();
        }
        if (isAllAir(volume)) {
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
                    "origin " + origin + " not aligned for " + level
                            + " regionSections=" + level.regionSections());
        }
        if (!VoxyCompat.isAvailable()) {
            throw new VolumeUnavailableException("Voxy not available");
        }
        if (worldEngine == null) {
            throw new VolumeUnavailableException("WorldEngine unavailable");
        }
        int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
        int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
        int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
        // Insert-only: if WorldSection already fully populated at this level, skip.
        // For L0, Voxy's nonEmptyChildren=0xFF means occupied; for L1..4 use
        // VoxyCompat.allOctantsPopulated.
        if (isRegionPopulated(level, wsX, wsY, wsZ)) {
            return WriteOutcome.skippedExists();
        }
        if (isAllAir(volume)) {
            return WriteOutcome.skippedAir();
        }
        int nonAir = writeRegionInternal(origin, level, volume);
        if (nonAir == 0) {
            return WriteOutcome.skippedAir();
        }
        return WriteOutcome.written(nonAir);
    }

    /**
     * Whether the WorldSection at (level,wsX,wsY,wsZ) is already fully populated.
     * Uses {@link VoxyCompat#allOctantsPopulated} for L1..4 and a direct
     * occupancy check for L0 (where NEC==0xFF is a whole-section flag, not
     * per-octant).
     */
    private boolean isRegionPopulated(Level level, int wsX, int wsY, int wsZ) {
        if (level == Level.L0) {
            // L0: occupancy means every octant has blocks.
            byte occ = VoxyCompat.getOccupiedOctantMask(worldEngine, 0, wsX, wsY, wsZ);
            return occ == (byte) 0xFF;
        }
        return VoxyCompat.allOctantsPopulated(worldEngine, level.value(), wsX, wsY, wsZ);
    }

    private int writeSectionInternal(SectionPos pos, VoxelVolume volume) {
        Object section = VoxyCompat.createEmptySection();
        VoxyCompat.setSectionPosition(section, pos.x(), pos.y(), pos.z());
        long[] data = VoxyCompat.getSectionData(section);
        int nonAir = 0;
        for (int y = 0; y < 16; y++) {
            for (int z = 0; z < 16; z++) {
                for (int x = 0; x < 16; x++) {
                    int canonBlock = volume.blockId(x, y, z);
                    int canonBiome = volume.biomeId(x, y, z);
                    int voxyBlock = toVoxyBlock(canonBlock);
                    int voxyBiome = toVoxyBiome(canonBiome);
                    long voxel = VoxyCompat.composeVoxel(voxyBlock, voxyBiome, DEFAULT_LIGHT);
                    data[VoxyCompat.l0Index(x, y, z)] = voxel;
                    if (voxyBlock != 0) {
                        nonAir++;
                    }
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
     * Write a 32^3 semantic volume into the WorldSection at Level.
     * Converts XYZ volume into YZX-packed 32^3 long array and delegates to
     * {@link VoxyWorldBinding#writeFullWorldSection} which handles occupancy
     * preservation, NEC, dirty-marking and CAS.
     */
    private int writeRegionInternal(SectionPos origin, Level level, VoxelVolume volume) {
        // Build YZX long[32768] array: index = (y<<10)|(z<<5)|x
        long[] voxels = new long[32 * 32 * 32];
        int nonAir = 0;
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int canonBlock = volume.blockId(x, y, z);
                    int canonBiome = volume.biomeId(x, y, z);
                    int voxyBlock = toVoxyBlock(canonBlock);
                    int voxyBiome = toVoxyBiome(canonBiome);
                    long voxel = VoxyCompat.composeVoxel(voxyBlock, voxyBiome, DEFAULT_LIGHT);
                    voxels[(y << 10) | (z << 5) | x] = voxel;
                    if (voxyBlock != 0) {
                        nonAir++;
                    }
                }
            }
        }
        if (nonAir == 0) {
            return 0;
        }
        int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
        int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
        int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
        VoxyCompat.writeFullWorldSection(worldEngine, level.value(), wsX, wsY, wsZ, voxels);
        LOGGER.debug("[RealVoxy] writeRegion {} {} nonAir={}", origin, level, nonAir);
        return nonAir;
    }

    private int toVoxyBlock(int canonical) {
        if (canonical < 0 || canonical >= canonicalBlockToVoxy.length) {
            return 0;
        }
        return canonicalBlockToVoxy[canonical];
    }

    private int toVoxyBiome(int canonical) {
        if (canonical == CanonicalRegistries.BIOME_UNKNOWN) {
            int plains = BiomeMapping.toCanonicalId("minecraft:plains");
            if (plains >= 0 && plains < canonicalBiomeToVoxy.length) {
                return canonicalBiomeToVoxy[plains];
            }
            return 0;
        }
        if (canonical < 0 || canonical >= canonicalBiomeToVoxy.length) {
            return 0;
        }
        return canonicalBiomeToVoxy[canonical];
    }

    private static boolean isAllAir(VoxelVolume v) {
        int e = v.extent();
        for (int y = 0; y < e; y++) {
            for (int z = 0; z < e; z++) {
                for (int x = 0; x < e; x++) {
                    if (v.blockId(x, y, z) != CanonicalRegistries.BLOCK_AIR) {
                        return false;
                    }
                }
            }
        }
        return true;
    }
}
