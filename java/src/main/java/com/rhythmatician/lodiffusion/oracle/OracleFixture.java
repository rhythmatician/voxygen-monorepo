package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable oracle fixture captured from real vanilla->Voxy ingest.
 *
 * <p>Holds semantic VoxelVolumes per Level for the target region, with full provenance
 * from the executable contract. Raw Voxy packed longs and YZX layout never leak;
 * fixture stores only canonical block/biome ids.
 *
 * <p>Fixture is immutable and deterministic; regeneration with same contract yields same logical content.
 */
public final class OracleFixture {
    private final OracleContract contract;
    private final Map<Level, VoxelVolume> volumes;
    private final String fixtureSha256;
    private final long createdAtEpochMs;

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String fixtureSha256, long createdAtEpochMs) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(volumes, "volumes");
        if (volumes.isEmpty()) throw new IllegalArgumentException("volumes must be non-empty");
        for (Map.Entry<Level, VoxelVolume> e : volumes.entrySet()) {
            Objects.requireNonNull(e.getKey(), "level");
            Objects.requireNonNull(e.getValue(), "volume for " + e.getKey());
            if (e.getValue().extent() != 32) throw new IllegalArgumentException("volume extent must be 32 for " + e.getKey());
        }
        if (fixtureSha256 == null || fixtureSha256.isBlank()) throw new IllegalArgumentException("fixtureSha256 required");
        this.contract = contract;
        this.volumes = Collections.unmodifiableMap(new EnumMap<>(volumes));
        this.fixtureSha256 = fixtureSha256;
        this.createdAtEpochMs = createdAtEpochMs;
        contract.validate();
    }

    public OracleContract contract() { return contract; }
    public VoxelVolume volume(Level level) {
        VoxelVolume v = volumes.get(Objects.requireNonNull(level, "level"));
        if (v == null) throw new IllegalArgumentException("no fixture volume for " + level);
        return v.copy();
    }
    public boolean hasLevel(Level level) { return volumes.containsKey(level); }
    public Map<Level, VoxelVolume> volumesView() { return volumes; }
    public String fixtureSha256() { return fixtureSha256; }
    public long createdAtEpochMs() { return createdAtEpochMs; }
    public SectionPos origin() {
        return new SectionPos(contract.region().originSectionX(), contract.region().originSectionY(), contract.region().originSectionZ());
    }
}
