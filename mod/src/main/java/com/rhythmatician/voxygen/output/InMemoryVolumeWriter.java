package com.rhythmatician.voxygen.output;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import com.rhythmatician.voxygen.semantic.Level;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementBatch;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementIntent;
import com.rhythmatician.voxygen.generation.refinement.ParentRefinementResult;
import com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome;

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
    private final java.util.Map<RegionKey, Integer> committedChildMasks = new java.util.HashMap<>();
    private final java.util.Map<RegionKey, Integer> childPublicationCounts = new java.util.HashMap<>();
    private boolean unavailable = false;

    @Override
    public int saveQueueDepth() {
        return 0;
    }

    @Override
    public boolean isRegionFullyPopulated(SectionPos origin, Level level) {
        return false;
    }

    @Override
    public boolean hasRegionCoverage(SectionPos origin, Level level) {
        return writtenRegions.contains(new RegionKey(level, origin.x(), origin.y(), origin.z()));
    }

    public void setUnavailable(boolean unavailable) {
        this.unavailable = unavailable;
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
        committedChildMasks.clear();
        childPublicationCounts.clear();
    }

    public int committedChildMask(SectionPos parentOrigin, Level parentLevel) {
        return committedChildMasks.getOrDefault(
                new RegionKey(parentLevel, parentOrigin.x(), parentOrigin.y(), parentOrigin.z()), 0);
    }

    public int childPublicationCount(SectionPos parentOrigin, Level parentLevel) {
        return childPublicationCounts.getOrDefault(
                new RegionKey(parentLevel, parentOrigin.x(), parentOrigin.y(), parentOrigin.z()), 0);
    }

    @Override
    public ParentRefinementResult refineParent(ParentRefinementIntent intent) {
        if (unavailable) {
            throw new VolumeUnavailableException("InMemory backend marked unavailable");
        }
        Objects.requireNonNull(intent, "intent");
        if (!hasRegionCoverage(intent.parentOrigin(), intent.parentLevel())) {
            return ParentRefinementResult.parentMissing();
        }
        ParentRefinementBatch batch = ParentRefinementBatch.materialize(intent);
        WriteOutcome outcome = commitParentRefinement(batch);
        return ParentRefinementResult.published(
                outcome, batch.nonEmptyMask(), batch.requiredMask() & ~batch.nonEmptyMask());
    }
    @SuppressWarnings("null")

    private WriteOutcome commitParentRefinement(ParentRefinementBatch batch) {
        int nonAir = 0;
        for (ParentRefinementBatch.Child child : batch.children()) {
            WriteOutcome outcome = writeRegion(child.origin(),
                    Level.values()[batch.childLevel()], child.volume());
            batch.recordTerminal(
                    child.octant(), ChildMaterializationOutcome.fromWriteOutcome(outcome));
            nonAir += outcome.nonAirWritten();
        }
        if (!batch.isComplete()) {
            throw new IllegalStateException("parent refinement completed without all child outcomes");
        }
        RegionKey parentKey = new RegionKey(batch.parentLevel(), batch.parentOrigin().x(),
                batch.parentOrigin().y(), batch.parentOrigin().z());
        committedChildMasks.put(parentKey, batch.nonEmptyMask());
        childPublicationCounts.merge(parentKey, 1, Integer::sum);
        return batch.nonEmptyMask() == 0
                ? WriteOutcome.skippedAir()
                : WriteOutcome.written(nonAir);
    }

    @Override
    public WriteOutcome writeSection(SectionPos pos, VoxelVolume volume) {
        if (unavailable) {
            throw new VolumeUnavailableException("InMemory backend marked unavailable");
        }
        Objects.requireNonNull(pos, "pos");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 16) {
            throw new IllegalArgumentException("writeSection requires extent 16, got " + volume.extent());
        }
        SectionKey key = new SectionKey(pos.x(), pos.y(), pos.z());
        if (writtenSections.contains(key)) {
            return WriteOutcome.skippedExists();
        }
        if (volume.isAllAir()) {
            return WriteOutcome.skippedAir();
        }
        int nonAir = volume.countNonAir();
        writtenSections.add(key);
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
            throw new IllegalArgumentException("writeRegion requires extent 32, got " + volume.extent());
        }
        if (!level.isAligned(origin)) {
            throw new IllegalArgumentException(
                    "origin " + origin + " not aligned to " + level + " regionSections=" + level.regionSections());
        }
        RegionKey key = new RegionKey(level, origin.x(), origin.y(), origin.z());
        if (writtenRegions.contains(key)) {
            return WriteOutcome.skippedExists();
        }
        if (volume.isAllAir()) {
            return WriteOutcome.skippedAir();
        }
        int nonAir = volume.countNonAir();
        writtenRegions.add(key);
        records.add(new RegionRecord(origin, level, volume.copy()));
        return WriteOutcome.written(nonAir);
    }
}
