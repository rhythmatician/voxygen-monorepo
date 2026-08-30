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
 *
 * <p>Evidence integrity: {@code captureProtocolSha256} is the immutable capture identity
 * (excludes mutable Worldgen Partition/policy state). {@code evidenceIntegritySha256}
 * binds {@code captureProtocolSha256 + contentSha256 + actualCaptureStage + evidenceKind}
 * so tampering any of those four fails fixture loading.
 *
 * <p>REAL_CAPTURE is unforgeable via ordinary public construction — public constructors
 * only produce SYNTHETIC_TEST; only {@link #createValidatedRealCaptureFixture} and
 * the validated loader ({@code OracleFixtureWriter.read}) may produce REAL_CAPTURE.
 */
public final class OracleFixture {
    /** Evidence origin — distinguishes real vanilla→Voxy capture from in-memory synthetic test data. */
    public enum EvidenceKind { REAL_CAPTURE, SYNTHETIC_TEST }

    private final OracleContract contract;
    private final Map<Level, VoxelVolume> volumes;
    private final String provenanceId;
    private final String contentSha256;
    private final String captureProtocolSha256;
    private final String protocolSha256; // legacy full protocol sha, kept for backwards compat
    private final EvidenceKind evidenceKind;
    private final long createdAtEpochMs;
    private final String actualCaptureStage;
    private final String evidenceIntegritySha256;

    // Public constructors — SYNTHETIC_TEST only, REAL_CAPTURE is unforgeable
    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs) {
        this(contract, volumes, contentSha256, createdAtEpochMs, contract.authoritativeGenerationStage(), EvidenceKind.SYNTHETIC_TEST);
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage) {
        this(contract, volumes, contentSha256, createdAtEpochMs, actualCaptureStage, EvidenceKind.SYNTHETIC_TEST);
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind) {
        this(contract, volumes, contentSha256, createdAtEpochMs, actualCaptureStage, evidenceKind, contract.captureProtocolSha256());
    }

    public OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind, String captureProtocolSha256) {
        // Public path: REAL_CAPTURE is forbidden — use validated factory
        if (evidenceKind == EvidenceKind.REAL_CAPTURE) {
            throw new IllegalArgumentException("Public OracleFixture construction cannot create REAL_CAPTURE evidence — use createValidatedRealCaptureFixture or validated loader OracleFixtureWriter.read (unforgeable)");
        }
        // Delegate to private validated constructor with synthetic integrity
        String capSha = captureProtocolSha256 != null ? captureProtocolSha256 : contract.captureProtocolSha256();
        // Verify captureProtocol matches contract (immutable)
        String expectedCap = contract.captureProtocolSha256();
        if (!expectedCap.equalsIgnoreCase(capSha)) {
            throw new IllegalArgumentException("captureProtocolSha256 mismatch: contract " + expectedCap + " was " + capSha + " — fixture capture provenance out of sync");
        }
        String integrity = computeEvidenceIntegritySha256(capSha, contentSha256, actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage(), evidenceKind);
        // Call private constructor
        this.contract = Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(volumes, "volumes");
        if (volumes.isEmpty()) throw new IllegalArgumentException("volumes must be non-empty");
        for (Map.Entry<Level, VoxelVolume> e : volumes.entrySet()) {
            Objects.requireNonNull(e.getKey(), "level");
            Objects.requireNonNull(e.getValue(), "volume for " + e.getKey());
            if (e.getValue().extent() != 32) throw new IllegalArgumentException("volume extent must be 32 for " + e.getKey());
        }
        if (contentSha256 == null || contentSha256.isBlank()) throw new IllegalArgumentException("contentSha256 required");
        this.volumes = Collections.unmodifiableMap(new EnumMap<>(volumes));
        this.provenanceId = contract.provenanceId();
        this.contentSha256 = contentSha256.toLowerCase();
        this.captureProtocolSha256 = capSha.toLowerCase();
        this.protocolSha256 = contract.protocolSha256().toLowerCase(); // legacy full sha for reference
        this.evidenceKind = evidenceKind;
        this.createdAtEpochMs = createdAtEpochMs;
        this.actualCaptureStage = actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage();
        this.evidenceIntegritySha256 = integrity.toLowerCase();
        contract.validate();
        String computedContent = computeContentSha256(volumes);
        if (!computedContent.equalsIgnoreCase(contentSha256)) {
            throw new IllegalArgumentException("contentSha256 mismatch: expected " + computedContent + " was " + contentSha256);
        }
        // Verify integrity binding
        String recomputedIntegrity = computeEvidenceIntegritySha256(this.captureProtocolSha256, this.contentSha256, this.actualCaptureStage, this.evidenceKind);
        if (!recomputedIntegrity.equalsIgnoreCase(this.evidenceIntegritySha256)) {
            throw new IllegalArgumentException("evidenceIntegritySha256 mismatch");
        }
    }

    // Private constructor for validated REAL_CAPTURE (unforgeable path)
    private OracleFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind, String captureProtocolSha256, String evidenceIntegritySha256, boolean validatedReal) {
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
        if (captureProtocolSha256 == null || captureProtocolSha256.isBlank()) throw new IllegalArgumentException("captureProtocolSha256 required");
        if (evidenceIntegritySha256 == null || evidenceIntegritySha256.isBlank()) throw new IllegalArgumentException("evidenceIntegritySha256 required");
        if (!validatedReal || evidenceKind != EvidenceKind.REAL_CAPTURE) {
            throw new IllegalArgumentException("Validated REAL_CAPTURE constructor requires validatedReal=true and REAL_CAPTURE");
        }
        String expectedCap = contract.captureProtocolSha256();
        if (!expectedCap.equalsIgnoreCase(captureProtocolSha256)) {
            throw new IllegalArgumentException("captureProtocolSha256 mismatch: contract " + expectedCap + " was " + captureProtocolSha256 + " — fixture capture provenance out of sync");
        }
        this.contract = contract;
        this.volumes = Collections.unmodifiableMap(new EnumMap<>(volumes));
        this.provenanceId = contract.provenanceId();
        this.contentSha256 = contentSha256.toLowerCase();
        this.captureProtocolSha256 = captureProtocolSha256.toLowerCase();
        this.protocolSha256 = contract.protocolSha256().toLowerCase();
        this.evidenceKind = evidenceKind;
        this.createdAtEpochMs = createdAtEpochMs;
        this.actualCaptureStage = actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage();
        this.evidenceIntegritySha256 = evidenceIntegritySha256.toLowerCase();
        contract.validate();
        String computedContent = computeContentSha256(volumes);
        if (!computedContent.equalsIgnoreCase(contentSha256)) {
            throw new IllegalArgumentException("contentSha256 mismatch: expected " + computedContent + " was " + contentSha256);
        }
        String recomputedIntegrity = computeEvidenceIntegritySha256(this.captureProtocolSha256, this.contentSha256, this.actualCaptureStage, this.evidenceKind);
        if (!recomputedIntegrity.equalsIgnoreCase(this.evidenceIntegritySha256)) {
            throw new IllegalArgumentException("evidenceIntegritySha256 mismatch: expected " + recomputedIntegrity + " was " + evidenceIntegritySha256 + " — evidence binding tampered (captureProtocol/content/stage/kind)");
        }
    }

    /**
     * Validated factory for real capture — only this path and the validated loader may produce REAL_CAPTURE.
     * Used by WorldSectionOracleCapture after live vanilla→Voxy ingest.
     */
    public static OracleFixture createValidatedRealCaptureFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage) {
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(volumes, "volumes");
        if (contentSha256 == null || contentSha256.isBlank()) throw new IllegalArgumentException("contentSha256 required");
        String capSha = contract.captureProtocolSha256();
        String stage = actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage();
        String integrity = computeEvidenceIntegritySha256(capSha, contentSha256, stage, EvidenceKind.REAL_CAPTURE);
        return new OracleFixture(contract, volumes, contentSha256, createdAtEpochMs, stage, EvidenceKind.REAL_CAPTURE, capSha, integrity, true);
    }

    /**
     * Reconstitute a validated REAL_CAPTURE fixture from stored fields — used only by OracleFixtureWriter.read
     * after verifying captureProtocol and evidence integrity from JSON.
     */
    public static OracleFixture reconstituteValidatedRealFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, EvidenceKind evidenceKind, String captureProtocolSha256, String evidenceIntegritySha256) {
        if (evidenceKind != EvidenceKind.REAL_CAPTURE) {
            throw new IllegalArgumentException("reconstituteValidatedRealFixture requires REAL_CAPTURE");
        }
        // Validate captureProtocol and integrity before constructing
        String expectedCap = contract.captureProtocolSha256();
        if (!expectedCap.equalsIgnoreCase(captureProtocolSha256)) {
            throw new IllegalArgumentException("captureProtocolSha256 mismatch on reconstitute: contract " + expectedCap + " was " + captureProtocolSha256);
        }
        String expectedIntegrity = computeEvidenceIntegritySha256(captureProtocolSha256, contentSha256, actualCaptureStage, evidenceKind);
        if (!expectedIntegrity.equalsIgnoreCase(evidenceIntegritySha256)) {
            throw new IllegalArgumentException("evidenceIntegritySha256 mismatch on reconstitute: expected " + expectedIntegrity + " was " + evidenceIntegritySha256 + " — evidence binding tampered");
        }
        return new OracleFixture(contract, volumes, contentSha256, createdAtEpochMs, actualCaptureStage, evidenceKind, captureProtocolSha256, evidenceIntegritySha256, true);
    }

    // For synthetic reconstitution (no integrity binding required beyond content)
    public static OracleFixture reconstituteSyntheticFixture(OracleContract contract, Map<Level, VoxelVolume> volumes, String contentSha256, long createdAtEpochMs, String actualCaptureStage, String captureProtocolSha256) {
        String stage = actualCaptureStage != null ? actualCaptureStage : contract.authoritativeGenerationStage();
        String capSha = captureProtocolSha256 != null ? captureProtocolSha256 : contract.captureProtocolSha256();
        String integrity = computeEvidenceIntegritySha256(capSha, contentSha256, stage, EvidenceKind.SYNTHETIC_TEST);
        // Use private constructor path for synthetic? We can use public path but need to avoid REAL_CAPTURE check
        // Create via public synthetic constructor that computes integrity internally, but we have stored integrity to validate
        // For synthetic we don't require integrity match to be stored; just create via public
        return new OracleFixture(contract, volumes, contentSha256, createdAtEpochMs, stage, EvidenceKind.SYNTHETIC_TEST, capSha);
    }

    public static String computeEvidenceIntegritySha256(String captureProtocolSha256, String contentSha256, String actualCaptureStage, EvidenceKind evidenceKind) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            String s = captureProtocolSha256.toLowerCase() + "|" + contentSha256.toLowerCase() + "|" + (actualCaptureStage != null ? actualCaptureStage : "UNKNOWN") + "|" + evidenceKind.name();
            byte[] d = md.digest(s.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d);
        } catch (Exception e) { throw new RuntimeException(e); }
    }

    public static String computeEvidenceIntegritySha256(OracleFixture f) {
        return computeEvidenceIntegritySha256(f.captureProtocolSha256, f.contentSha256, f.actualCaptureStage, f.evidenceKind);
    }

    // Legacy constructor for synthetic factory that previously used provenance-derived sha — keep for compat but now synthetic only

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
    /** @deprecated Use captureProtocolSha256 for immutable capture identity */
    @Deprecated
    public String protocolSha256() { return protocolSha256; }
    public String captureProtocolSha256() { return captureProtocolSha256; }
    public String evidenceIntegritySha256() { return evidenceIntegritySha256; }
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
