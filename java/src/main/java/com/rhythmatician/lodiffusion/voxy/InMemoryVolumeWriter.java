package com.rhythmatician.lodiffusion.voxy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * In-memory adapter of {@link VoxelVolumeWriter} used in unit tests.
 *
 * <p>Captures semantic {@code WriteRecord} entries - SectionPos + Level +
 * VoxelVolume snapshot - never stores Voxy packed {@code long[]} or
 * LevelCoord / WorldSection internals. Enforces two guards:
 * insert-only (second write to same key returns SKIPPED_EXISTS) and
 * all-air (SKIPPED_AIR). SKIPPED_EXISTS takes precedence over
 * SKIPPED_AIR so an already-written location always returns
 * SKIPPED_EXISTS even if the new volume is all air.
 *
 * <p>Fully deterministic; fails fast with {@link VolumeUnavailableException}
 * when marked unavailable. No Voxy or Minecraft runtime required.
 */
public final class InMemoryVolumeWriter implements VoxelVolumeWriter {

    public sealed interface WriteRecord permits SectionRecord, RegionRecord {}

    public record SectionRecord(SectionPos pos, VoxelVolume volume) implements WriteRecord {}

    public record RegionRecord(SectionPos origin, Level level, VoxelVolume volume) implements WriteRecord {}

    private record SectionKey(int x, int y, int z) {}

    private record RegionKey(Level level, int x, int y, int z) {}

    private final List<WriteRecord> records = new ArrayList<>();
    private final Set<SectionKey> writtenSections = new HashSet<>();
    private final Set<RegionKey> writtenRegions = new HashSet<>();
    private boolean unavailable = false;

    public void setUnavailable(boolean v) {
        this.unavailable = v;
    }

    public List<WriteRecord> records() {
        return Collections.unmodifiableList(records);
    }

    public List<SectionRecord> sectionRecords() {
        return records.stream()
                .filter(r -> r instanceof SectionRecord)
                .map(r -> (SectionRecord) r)
                .toList();
    }

    public List<RegionRecord> regionRecords() {
        return records.stream()
                .filter(r -> r instanceof RegionRecord)
                .map(r -> (RegionRecord) r)
                .toList();
    }

    public void clear() {
        records.clear();
        writtenSections.clear();
        writtenRegions.clear();
    }

    @Override
    public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
        if (unavailable) {
            throw new VolumeUnavailableException("InMemory backend marked unavailable");
        }
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 16) {
            throw new IllegalArgumentException(
                    "writeSection requires extent 16, got " + volume.extent());
        }
        SectionKey key = new SectionKey(pos.x(), pos.y(), pos.z());
        if (writtenSections.contains(key)) {
            return WriteOutcome.skippedExists();
        }
        if (isAllAir(volume)) {
            return WriteOutcome.skippedAir();
        }
        writtenSections.add(key);
        int nonAir = countNonAir(volume);
        records.add(new SectionRecord(pos, volume.copy()));
        return WriteOutcome.written(nonAir);
    }

    @Override
    public WriteOutcome writeRegion(SectionPos origin, Level level, VoxelVolume volume) {
        if (unavailable) {
            throw new VolumeUnavailableException("InMemory backend marked unavailable");
        }
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 32) {
            throw new IllegalArgumentException(
                    "writeRegion requires extent 32, got " + volume.extent());
        }
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level
                            + " regionSections=" + level.regionSections());
        }
        RegionKey key = new RegionKey(level, origin.x(), origin.y(), origin.z());
        if (writtenRegions.contains(key)) {
            return WriteOutcome.skippedExists();
        }
        if (isAllAir(volume)) {
            return WriteOutcome.skippedAir();
        }
        writtenRegions.add(key);
        int nonAir = countNonAir(volume);
        records.add(new RegionRecord(origin, level, volume.copy()));
        return WriteOutcome.written(nonAir);
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

    private static int countNonAir(VoxelVolume v) {
        int c = 0;
        int e = v.extent();
        for (int y = 0; y < e; y++) {
            for (int z = 0; z < e; z++) {
                for (int x = 0; x < e; x++) {
                    if (v.blockId(x, y, z) != CanonicalRegistries.BLOCK_AIR) {
                        c++;
                    }
                }
            }
        }
        return c;
    }
}
