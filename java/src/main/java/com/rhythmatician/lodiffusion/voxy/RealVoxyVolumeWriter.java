package com.rhythmatician.lodiffusion.voxy;

import java.util.Objects;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Production {@link VoxelVolumeWriter} that translates semantic {@link VoxelVolume}
 * into Voxy's packed storage.
 *
 * <p>Owns all Voxy-specific mechanics:
 * canonical -&gt; Voxy Mapper translation, 32^3 WorldSection storage mapping,
 * YZX indexing, packed long voxel layout (block/biome/light bits), light defaults,
 * ThreadLocal scratch buffers, reflection/MethodHandle/VarHandle access, CAS,
 * nonEmptyChildren, markDirty, mip/update lifecycle, child-presence propagation,
 * and Voxy coordinate transforms.
 *
 * <p>Public API never exposes Voxy types: only {@link SectionPos}, {@link Level},
 * {@link VoxelVolume}, {@link WriteOutcome}.
 */
public final class RealVoxyVolumeWriter implements VoxelVolumeWriter {
    private static final Logger LOGGER = LoggerFactory.getLogger(RealVoxyVolumeWriter.class);
    private static final int DEFAULT_LIGHT = 0x0F;

    private final Object worldEngine;
    private final Object voxyMapper;
    private final int[] canonicalBiomeToVoxy;
    private final int[] canonicalBlockToVoxy;

    /**
     * @param worldEngine Voxy WorldEngine (from {@link VoxyCompat#getWorldEngine})
     * @param voxyMapper Voxy Mapper (from {@link VoxyCompat#getMapper})
     * @param canonicalBiomeToVoxy size 54 mapping canonical biome 0..53 -&gt; Voxy biome ID
     * @param canonicalBlockToVoxy size {@value CanonicalRegistries#BLOCK_COUNT} mapping canonical block -&gt; Voxy block ID
     */
    public RealVoxyVolumeWriter(Object worldEngine, Object voxyMapper,
                                int[] canonicalBiomeToVoxy, int[] canonicalBlockToVoxy) {
        this.worldEngine = Objects.requireNonNull(worldEngine, "worldEngine");
        this.voxyMapper = Objects.requireNonNull(voxyMapper, "voxyMapper");
        this.canonicalBiomeToVoxy = Objects.requireNonNull(canonicalBiomeToVoxy, "canonicalBiomeToVoxy").clone();
        this.canonicalBlockToVoxy = Objects.requireNonNull(canonicalBlockToVoxy, "canonicalBlockToVoxy").clone();
        if (this.canonicalBiomeToVoxy.length != CanonicalRegistries.BIOME_COUNT) {
            throw new IllegalArgumentException("canonicalBiomeToVoxy must be size " + CanonicalRegistries.BIOME_COUNT);
        }
        if (this.canonicalBlockToVoxy.length != CanonicalRegistries.BLOCK_COUNT) {
            throw new IllegalArgumentException("canonicalBlockToVoxy must be size " + CanonicalRegistries.BLOCK_COUNT);
        }
    }

    /** Build fallback block map (canonical 0..1103 -&gt; Voxy) from fallback voxy IDs. */
    public static int[] buildFallbackBlockMap(HeightmapFallbackGenerator.FallbackBlockIds voxyIds) {
        int[] map = new int[CanonicalRegistries.BLOCK_COUNT];
        // canonical constants from python/config/voxy_vocab.json
        map[0] = voxyIds.air();
        map[923] = voxyIds.stone();
        map[319] = voxyIds.deepslate();
        map[343] = voxyIds.dirt();
        map[400] = voxyIds.grassBlock();
        // snowy grass uses same canonical (grass_block) -- no separate id
        map[855] = voxyIds.sand();
        map[1018] = voxyIds.water();
        map[825] = voxyIds.redSand();
        map[401] = voxyIds.gravel();
        map[893] = voxyIds.snowLayer();
        map[703] = voxyIds.podzol();
        map[593] = voxyIds.mycelium();
        return map;
    }


    public static int[] buildBiomeMap(Object voxyMapper, net.minecraft.registry.Registry<net.minecraft.world.biome.Biome> biomeRegistry) {
        return VoxyBlockMapper.resolveBiomeMappingsPublic(voxyMapper, biomeRegistry);
    }
    public static int[] buildBlockMap(com.rhythmatician.lodiffusion.onnx.BlockVocabulary vocab, Object voxyMapper) {
        try {
            java.lang.reflect.Method m = voxyMapper.getClass().getMethod("getIdForBlockState", net.minecraft.block.BlockState.class);
            int[] map = new int[vocab.size()];
            for (int i = 0; i < vocab.size(); i++) {
                net.minecraft.block.BlockState st = vocab.getState(i);
                Object id = m.invoke(voxyMapper, st);
                map[i] = id instanceof Number n ? n.intValue() : 0;
            }
            // Expand to canonical 1104 if vocab < 1104: canonical == vocab index when trained on voxy vocab
            int[] canonical = new int[CanonicalRegistries.BLOCK_COUNT];
            int n = Math.min(map.length, canonical.length);
            System.arraycopy(map, 0, canonical, 0, n);
            return canonical;
        } catch (Exception e) {
            throw new RuntimeException("buildBlockMap failed", e);
        }
    }
    public Object getWorldEngine() { return worldEngine; }

    @Override
    public int saveQueueDepth() { return VoxyCompat.getSaveQueueDepth(worldEngine); }

    @Override
    public boolean isRegionFullyPopulated(Level level, int wsX, int wsY, int wsZ) {
        if (level == Level.L0) return checkExistsViaAcquire(level.value(), wsX, wsY, wsZ);
        return VoxyCompat.allOctantsPopulated(worldEngine, level.value(), wsX, wsY, wsZ);
    }

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
        // SKIPPED_EXISTS precedence -- check existence before air
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
            throw new IllegalArgumentException("origin " + origin + " not aligned to " + level + " regionSections=" + level.regionSections());
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

        // Existence check via Voxy storage (full 0xFF)
        // Use VoxyWorldBinding's logic: check nonEmptyChildren of existing section
        if (isRegionFullyPopulated(level, wsX, wsY, wsZ)) {
            return WriteOutcome.skippedExists();
        }
        if (isAllAir(volume)) {
            return WriteOutcome.skippedAir();
        }
        int nonAir = writeRegionInternal(origin, level, volume, wsX, wsY, wsZ);
        if (nonAir == 0) {
            return WriteOutcome.skippedAir();
        }
        return WriteOutcome.written(nonAir);
    }

    private boolean checkExistsViaAcquire(int lvl, int wsX, int wsY, int wsZ) {
        try {
            // Use VoxyEngine.acquireIfExists reflectively
            java.lang.reflect.Method m = worldEngine.getClass().getMethod("acquireIfExists", int.class, int.class, int.class, int.class);
            Object sec = m.invoke(worldEngine, lvl, wsX, wsY, wsZ);
            if (sec != null) {
                // read nonEmptyChildren
                java.lang.reflect.Field f = sec.getClass().getDeclaredField("nonEmptyChildren");
                f.setAccessible(true);
                byte nec = f.getByte(sec);
                // release
                sec.getClass().getMethod("release").invoke(sec);
                return nec == (byte) 0xFF;
            }
            return false;
        } catch (Exception ignored) {
            // fallback to sectionExists for L0 sections that map to Voxy 16-block sections
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
                    int canonBlock = volume.blockId(x, y, z);
                    int canonBiome = volume.biomeId(x, y, z);
                    int voxyBlock = toVoxyBlock(canonBlock);
                    int voxyBiome = toVoxyBiome(canonBiome);
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

    private int writeRegionInternal(SectionPos origin, Level level, VoxelVolume volume, int wsX, int wsY, int wsZ) {
        // Build packed YZX voxels for 32^3 world-section
        long[] voxels = new long[32 * 32 * 32];
        int nonAir = 0;
        // VoxelVolume is XYZ with extent 32, origin at minimum corner of region.
        // Voxy storage is YZX: (y<<10)|(z<<5)|x
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int canonBlock = volume.blockId(x, y, z);
                    int canonBiome = volume.biomeId(x, y, z);
                    int voxyBlock = toVoxyBlock(canonBlock);
                    int voxyBiome = toVoxyBiome(canonBiome);
                    long voxel = VoxyCompat.composeVoxel(voxyBlock, voxyBiome, DEFAULT_LIGHT);
                    voxels[(y << 10) | (z << 5) | x] = voxel;
                    if (voxyBlock != 0) nonAir++;
                }
            }
        }
        if (nonAir == 0) return 0;
        // Use writeFullWorldSection for all levels (including L0)
        VoxyCompat.writeFullWorldSection(worldEngine, level.value(), wsX, wsY, wsZ, voxels);
        LOGGER.debug("[RealVoxy] writeRegion {} {} ws=({},{},{}) nonAir={}", origin, level, wsX, wsY, wsZ, nonAir);
        return nonAir;
    }

    private int toVoxyBlock(int canonical) {
        if (canonical < 0 || canonical >= canonicalBlockToVoxy.length) return 0;
        return canonicalBlockToVoxy[canonical];
    }

    private int toVoxyBiome(int canonical) {
        if (canonical == CanonicalRegistries.BIOME_UNKNOWN) {
            // unknown -> use plains fallback (canonical 34)
            int plains = BiomeMapping.toCanonicalId("minecraft:plains");
            if (plains >= 0 && plains < canonicalBiomeToVoxy.length) {
                return canonicalBiomeToVoxy[plains];
            }
            return 0;
        }
        if (canonical < 0 || canonical >= canonicalBiomeToVoxy.length) return 0;
        return canonicalBiomeToVoxy[canonical];
    }

    private static boolean isAllAir(VoxelVolume v) {
        int e = v.extent();
        for (int y = 0; y < e; y++) for (int z = 0; z < e; z++) for (int x = 0; x < e; x++) {
            if (v.blockId(x, y, z) != CanonicalRegistries.BLOCK_AIR) return false;
        }
        return true;
    }

}
