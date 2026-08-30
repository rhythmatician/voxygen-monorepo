package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Collections;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable oracle fixture captured from real vanilla->Voxy ingest.
 *
 * <p>Holds semantic VoxelVolumes per Level for the target region, with full provenance
 * from the executable contract. Raw Voxy packed longs and YZX layout never leak;
 * fixture stores only canonical block/biome ids.
 *
 * <p>Two identities are kept separate:
 * <ul>
 *   <li>{@code provenanceId} from contract (protocol/provenance identity)
 *   <li>{@code contentSha256} true SHA-256 of canonical voxel contents (all Levels, block+biome)
 * </ul>
 * Legacy {@code fixtureSha256} delegates to {@code contentSha256} for backward compat.
 */
public final class OracleFixture {
    /** Evidence origin — distinguishes real vanilla→Voxy capture from in-memory synthetic test data. */
    public enum EvidenceKind { REAL_CAPTURE, SYNTHETIC_TEST }

    private final OracleContract contract;
    private final Map<Level, VoxelVolume> volumes;
    private final String provenanceId;
    private final String contentSha256;
    private final String protocolSha256;
    private final EvidenceKind evidenceKind;
    private final long createdAtEpochMs;
    private final String actualCaptureStage;

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs) {
        this(contract, volumes, contentSha256, createdAtEpochMs, contract.authoritativeGenerationStage(), EvidenceKind.REAL_CAPTURE);
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage) {
        this(contract, volumes, contentSha256, createdAtEpochMs, actualCaptureStage, EvidenceKind.REAL_CAPTURE);
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind) {
        this(contract, volumes, contentSha256, createdAtEpochMs, actualCaptureStage, evidenceKind, contract.protocolSha256());
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind, String protocolSha256) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(volumes, "volumes");
        if (volumes.isEmpty()) throw new IllegalArgumentException("volumes must be non-empty");
        for (Map.Entry<Level, VoxelVolume> e : volumes.entrySet()) {
            Objects.requireNonNull(e.getKey(), "level");
            Objects.requireNonNull(e.getValue(), "volume for " + e.getKey());
            if (e.getValue().extent() != 32) throw new IllegalArgumentException("volume extent must be 32 for " + e.getKey());
        }
        if (contentSha256 == null || contentSha256.isBlank()) throw new IllegalArgumentException("contentSha256 required");
        if (evidenceKind == null) throw new IllegalArgumentException("evidenceKind required");
        if (protocolSha256 == null || protocolSha256.isBlank()) throw new IllegalArgumentException("protocolSha256 required");
        String expectedProtocolSha = contract.protocolSha256();
        if (!expectedProtocolSha.equalsIgnoreCase(protocolSha256)) {
            throw new IllegalArgumentException("protocolSha256 mismatch: contract " + expectedProtocolSha + " was " + protocolSha256 + " — fixture provenance out of sync with contract");
        }
        this.contract = contract;
        this.volumes = Collections.unmodifiableMap(new EnumMap<>(volumes));
        this.provenanceId = contract.provenanceId();
        this.contentSha256 = contentSha256;
        this.protocolSha256 = protocolSha256.toLowerCase();
        this.evidenceKind = evidenceKind;
        this.createdAtEpochMs = createdAtEpochMs;
        this.actualCaptureStage = actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage();
        contract.validate();
        // Verify content hash matches actual voxel data (defense against synthetic substitution)
        String computed = computeContentSha256(volumes);
        if (!computed.equalsIgnoreCase(contentSha256)) {
            throw new IllegalArgumentException("contentSha256 mismatch: expected " + computed + " was " + contentSha256);
        }
    }

    // Legacy constructor for synthetic factory that previously used provenance-derived sha
    public static String computeContentSha256(Map<Level, VoxelVolume> volumes) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            for (Level l : Level.values()) {
                VoxelVolume v = volumes.get(l);
                if (v == null) continue;
                md.update((byte) l.value());
                for (int y = 0; y < 32; y++) for (int z = 0; z < 32; z++) for (int x = 0; x < 32; x++) {
                    int b = v.blockId(x, y, z);
                    int bi = v.biomeId(x, y, z);
                    md.update((byte) (b & 0xFF));
                    md.update((byte) ((b >> 8) & 0xFF));
                    md.update((byte) (bi & 0xFF));
                }
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public OracleContract contract() { return contract; }
    public VoxelVolume volume(Level level) {
        VoxelVolume v = volumes.get(Objects.requireNonNull(level, "level"));
        if (v == null) throw new IllegalArgumentException("no fixture volume for " + level);
        return v.copy();
    }
    public boolean hasLevel(Level level) { return volumes.containsKey(level); }
    public Map<Level, VoxelVolume> volumesView() { return volumes; }
    public String provenanceId() { return provenanceId; }
    public String contentSha256() { return contentSha256; }
    public String protocolSha256() { return protocolSha256; }
    public EvidenceKind evidenceKind() { return evidenceKind; }
    public boolean isRealCapture() { return evidenceKind == EvidenceKind.REAL_CAPTURE; }
    // Legacy accessor
    public String fixtureSha256() { return contentSha256; }
    public long createdAtEpochMs() { return createdAtEpochMs; }
    public String actualCaptureStage() { return actualCaptureStage; }
    public SectionPos origin() {
        return new SectionPos(contract.region().originSectionX(), contract.region().originSectionY(), contract.region().originSectionZ());
    }
}
