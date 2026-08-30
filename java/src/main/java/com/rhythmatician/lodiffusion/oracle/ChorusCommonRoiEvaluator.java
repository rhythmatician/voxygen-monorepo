package com.rhythmatician.lodiffusion.oracle;

import com.rhythmatician.lodiffusion.voxy.CanonicalRegistries;
import com.rhythmatician.lodiffusion.voxy.Level;
import com.rhythmatician.lodiffusion.voxy.SectionPos;
import com.rhythmatician.lodiffusion.voxy.VoxelVolume;
import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Common-physical-ROI evaluator for End chorus (#220).
 *
 * <p>Evaluates chorus feature masks over the SAME 32×32×32 block ROI at every Level.
 * The tracer blockRegion X[1600,1632) Y[64,96) Z[128,160) is half-open and aligned to every
 * voxel size (1<<L). For Level L, voxel size = 1<<L blocks, ROI cell count = 32>>L per axis.
 *
 * <p>World-space mapping is derived from BlockRegionSpec + per-Level WorldSection origin + Level,
 * never from hard-coded local indices. Fail-closed on misalignment or partial cells.
 *
 * <p>Representation: 32³ block-space boolean mask (half-open tracer region). A coarse voxel
 * expands over its (1<<L)³ footprint. Feature semantics: ANY_CHORUS = chorus_plant OR
 * chorus_flower, isolated from base terrain (end_stone/air). Expected values come ONLY from
 * REAL_CAPTURE fixture; candidate masks are never used as expected.
 *
 * <p>This is a world-space occupancy Measurement Tracer, not final screen-space Pop.
 */
public final class ChorusCommonRoiEvaluator {

    private ChorusCommonRoiEvaluator() {}

    // ----------------------------------------------------------------------
    // Chorus IDs — resolved via CanonicalRegistries, validated at class load
    // ----------------------------------------------------------------------

    public static final int BLOCK_CHORUS_PLANT;
    public static final int BLOCK_CHORUS_FLOWER;

    static {
        int plant = -1;
        int flower = -1;
        for (int i = 0; i < CanonicalRegistries.BLOCK_COUNT; i++) {
            String name = CanonicalRegistries.canonicalName(i);
            if ("minecraft:chorus_plant".equals(name)) plant = i;
            if ("minecraft:chorus_flower".equals(name)) flower = i;
        }
        if (plant < 0 || flower < 0) {
            throw new IllegalStateException("chorus IDs not found in CanonicalRegistries");
        }
        // Assert pinned values remain 197/196 so magic numbers cannot silently drift
        if (plant != 197) throw new IllegalStateException("chorus_plant id drift: expected 197 was " + plant + " name=" + CanonicalRegistries.canonicalName(plant));
        if (flower != 196) throw new IllegalStateException("chorus_flower id drift: expected 196 was " + flower + " name=" + CanonicalRegistries.canonicalName(flower));
        BLOCK_CHORUS_PLANT = plant;
        BLOCK_CHORUS_FLOWER = flower;
    }

    public static boolean isChorus(int blockId) {
        return blockId == BLOCK_CHORUS_PLANT || blockId == BLOCK_CHORUS_FLOWER;
    }

    // ----------------------------------------------------------------------
    // ROI mapping
    // ----------------------------------------------------------------------

    public record RoiMapping(
            Level level,
            int voxelSize,
            int roiVoxelExtent,
            int offsetX,
            int offsetY,
            int offsetZ,
            int wsOriginX,
            int wsOriginY,
            int wsOriginZ,
            int roiOriginX,
            int roiOriginY,
            int roiOriginZ,
            int blockSize
    ) {
        public int roiBlockExtent() { return 32; }
        public String roiIdentity() {
            return String.format("L%d ws[%d,%d,%d] blockSize=%d voxel=%d offset[%d,%d,%d] extent=%d ROI [%d,%d,%d)+32",
                    level.value(), wsOriginX, wsOriginY, wsOriginZ, blockSize, voxelSize, offsetX, offsetY, offsetZ, roiOriginX, roiOriginY, roiOriginZ);
        }
    }

    public static RoiMapping mappingForLevel(Level level, OracleContract contract) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(contract, "contract");
        var br = contract.blockRegionOrDerived();
        if (br == null) throw new IllegalArgumentException("contract missing blockRegion");
        var per = br.perLevelWorldSectionOrigin(level.value());
        int blockSize = per.blockSize(); // 32 << level
        int wsOriginX = per.wsX() * blockSize;
        int wsOriginY = per.wsY() * blockSize;
        int wsOriginZ = per.wsZ() * blockSize;
        int roiOriginX = br.originBlockX();
        int roiOriginY = br.originBlockY();
        int roiOriginZ = br.originBlockZ();
        int extentBlocks = br.extentBlocks(); // 32
        int voxelSize = 1 << level.value();
        // Verify half-open tracer extent is exactly 32 for this task
        if (extentBlocks != 32) {
            throw new IllegalArgumentException("tracer blockRegion extent must be 32, was " + extentBlocks + " for " + level);
        }
        // Alignment: ROI must be exactly coverable by voxels
        if (extentBlocks % voxelSize != 0) {
            throw new IllegalArgumentException("ROI extent 32 not divisible by voxelSize " + voxelSize + " at " + level + " — partial-cell semantics not supported");
        }
        int diffX = roiOriginX - wsOriginX;
        int diffY = roiOriginY - wsOriginY;
        int diffZ = roiOriginZ - wsOriginZ;
        if (diffX % voxelSize != 0 || diffY % voxelSize != 0 || diffZ % voxelSize != 0) {
            throw new IllegalArgumentException(String.format(
                    "ROI not aligned to voxel grid at %s: wsOrigin [%d,%d,%d] roiOrigin [%d,%d,%d] voxel=%d diff [%d,%d,%d] must be divisible",
                    level, wsOriginX, wsOriginY, wsOriginZ, roiOriginX, roiOriginY, roiOriginZ, voxelSize, diffX, diffY, diffZ));
        }
        int offsetX = Math.floorDiv(diffX, voxelSize);
        int offsetY = Math.floorDiv(diffY, voxelSize);
        int offsetZ = Math.floorDiv(diffZ, voxelSize);
        int roiVoxelExtent = extentBlocks / voxelSize; // 32>>L
        int expectedExtent = 32 >> level.value();
        if (roiVoxelExtent != expectedExtent) {
            throw new IllegalStateException("roiVoxelExtent " + roiVoxelExtent + " != expected " + expectedExtent + " at " + level);
        }
        // Bounds check: ROI must be inside captured WorldSection (32 voxels)
        if (offsetX < 0 || offsetY < 0 || offsetZ < 0 || offsetX + roiVoxelExtent > 32 || offsetY + roiVoxelExtent > 32 || offsetZ + roiVoxelExtent > 32) {
            throw new IllegalArgumentException(String.format(
                    "ROI [%d,%d,%d)+32 falls outside captured WorldSection 32³ at %s: wsOrigin [%d,%d,%d] blockSize=%d voxel=%d offset[%d,%d,%d] extent=%d",
                    roiOriginX, roiOriginY, roiOriginZ, level, wsOriginX, wsOriginY, wsOriginZ, blockSize, voxelSize, offsetX, offsetY, offsetZ, roiVoxelExtent));
        }
        return new RoiMapping(level, voxelSize, roiVoxelExtent, offsetX, offsetY, offsetZ, wsOriginX, wsOriginY, wsOriginZ, roiOriginX, roiOriginY, roiOriginZ, blockSize);
    }

    // ----------------------------------------------------------------------
    // 32³ block-space mask
    // ----------------------------------------------------------------------

    /** 32³ mask index: (y*32 + z)*32 + x, XYZ order, y major like VoxelVolume. */
    public static int maskIndex(int x, int y, int z) {
        return (y * 32 + z) * 32 + x;
    }

    public static boolean[] toBlockMask(Level level, OracleContract contract, VoxelVolume volume) {
        Objects.requireNonNull(level, "level");
        Objects.requireNonNull(contract, "contract");
        Objects.requireNonNull(volume, "volume");
        if (volume.extent() != 32) throw new IllegalArgumentException("volume extent must be 32 at " + level);
        RoiMapping m = mappingForLevel(level, contract);
        boolean[] mask = new boolean[32 * 32 * 32];
        int vs = m.voxelSize();
        int roiExtent = m.roiVoxelExtent();
        int ox = m.offsetX();
        int oy = m.offsetY();
        int oz = m.offsetZ();
        for (int vy = 0; vy < roiExtent; vy++) {
            for (int vz = 0; vz < roiExtent; vz++) {
                for (int vx = 0; vx < roiExtent; vx++) {
                    int blockId = volume.blockId(ox + vx, oy + vy, oz + vz);
                    boolean chorus = isChorus(blockId);
                    if (!chorus) continue;
                    // Expand to vs³ block cells
                    int baseX = vx * vs;
                    int baseY = vy * vs;
                    int baseZ = vz * vs;
                    for (int dy = 0; dy < vs; dy++) {
                        for (int dz = 0; dz < vs; dz++) {
                            for (int dx = 0; dx < vs; dx++) {
                                int bx = baseX + dx;
                                int by = baseY + dy;
                                int bz = baseZ + dz;
                                mask[maskIndex(bx, by, bz)] = true;
                            }
                        }
                    }
                }
            }
        }
        return mask;
    }

    public static boolean[] omitMask() {
        return new boolean[32 * 32 * 32]; // all false
    }

    // Plant/flower diagnostic counts in block-space mask via original volume
    public record PlantFlowerCounts(int plant, int flower, int anyChorus) {}

    public static PlantFlowerCounts countPlantFlowerInMask(Level level, OracleContract contract, VoxelVolume volume) {
        RoiMapping m = mappingForLevel(level, contract);
        int vs = m.voxelSize();
        int roiExtent = m.roiVoxelExtent();
        int ox = m.offsetX();
        int oy = m.offsetY();
        int oz = m.offsetZ();
        int plantBlocks = 0;
        int flowerBlocks = 0;
        for (int vy = 0; vy < roiExtent; vy++) {
            for (int vz = 0; vz < roiExtent; vz++) {
                for (int vx = 0; vx < roiExtent; vx++) {
                    int blockId = volume.blockId(ox + vx, oy + vy, oz + vz);
                    int vs3 = vs * vs * vs;
                    if (blockId == BLOCK_CHORUS_PLANT) plantBlocks += vs3;
                    else if (blockId == BLOCK_CHORUS_FLOWER) flowerBlocks += vs3;
                }
            }
        }
        return new PlantFlowerCounts(plantBlocks, flowerBlocks, plantBlocks + flowerBlocks);
    }

    // ----------------------------------------------------------------------
    // Metrics
    // ----------------------------------------------------------------------

    public record ChorusMetrics(
            int oraclePositives,
            int candidatePositives,
            int tp,
            int fp,
            int fn,
            int tn,
            double precision,
            double recall,
            double iou,
            int disagreements
    ) {}

    /**
     * Compute feature-only metrics. Zero-denominator behavior:
     * precision = 1.0 if (TP+FP)==0, recall = 1.0 if (TP+FN)==0, IoU = 1.0 if (TP+FP+FN)==0.
     * TN = 32768 - TP - FP - FN.
     */
    public static ChorusMetrics computeMetrics(boolean[] oracleMask, boolean[] candidateMask) {
        Objects.requireNonNull(oracleMask, "oracleMask");
        Objects.requireNonNull(candidateMask, "candidateMask");
        if (oracleMask.length != 32768 || candidateMask.length != 32768) {
            throw new IllegalArgumentException("masks must be 32768, were " + oracleMask.length + "," + candidateMask.length);
        }
        int tp = 0, fp = 0, fn = 0;
        int oraclePos = 0, candidatePos = 0;
        for (int i = 0; i < 32768; i++) {
            boolean o = oracleMask[i];
            boolean c = candidateMask[i];
            if (o) oraclePos++;
            if (c) candidatePos++;
            if (o && c) tp++;
            else if (!o && c) fp++;
            else if (o && !c) fn++;
        }
        int tn = 32768 - tp - fp - fn;
        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 1.0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 1.0;
        double iou = (tp + fp + fn) > 0 ? (double) tp / (tp + fp + fn) : 1.0;
        int disagreements = fp + fn;
        return new ChorusMetrics(oraclePos, candidatePos, tp, fp, fn, tn, precision, recall, iou, disagreements);
    }

    public static void requireRealCapture(OracleFixture fixture) {
        Objects.requireNonNull(fixture, "fixture");
        if (fixture.evidenceKind() != OracleFixture.EvidenceKind.REAL_CAPTURE) {
            throw new IllegalArgumentException("ChorusCommonRoiEvaluator requires REAL_CAPTURE fixture, was " + fixture.evidenceKind() + " — synthetic test fixtures cannot satisfy ADR 0015");
        }
    }

    public static SectionPos sectionPosForLevel(Level level, OracleContract contract) {
        var per = contract.blockRegionOrDerived().perLevelWorldSectionOrigin(level.value());
        return new SectionPos(per.wsX() * level.regionSections(), per.wsY() * level.regionSections(), per.wsZ() * level.regionSections());
    }

    // ----------------------------------------------------------------------
    // Per-Level evidence
    // ----------------------------------------------------------------------

    public record LevelEvidence(
            Level level,
            RoiMapping mapping,
            int oraclePositives,
            ChorusMetrics omitMetrics,
            ChorusMetrics deterministicMetrics,
            BenchmarkReceipt deterministicRuntime,
            PlantFlowerCounts oraclePlantFlower,
            PlantFlowerCounts deterministicPlantFlower
    ) {
        public int voxelSize() { return mapping.voxelSize(); }
        public int roiVoxelExtent() { return mapping.roiVoxelExtent(); }
        public String roiIdentity() { return mapping.roiIdentity(); }
    }

    public record TransitionEvidence(
            Level from,
            Level to,
            ChorusMetrics oracleVsOracle,
            ChorusMetrics omitToNext,
            ChorusMetrics deterministicToNext
    ) {}

    public record CommonRoiEvidenceReceipt(
            String provenanceId,
            String contentSha256,
            String captureProtocolSha256,
            String evidenceKind,
            long seed,
            String captureStage,
            Map<Level, LevelEvidence> perLevel,
            Map<String, TransitionEvidence> transitions
    ) {
        public LevelEvidence at(Level l) { return perLevel.get(l); }
    }

    public static CommonRoiEvidenceReceipt evaluateAll(OracleFixture fixture, Map<Level, VoxelVolume> deterministicVolumes, Map<Level, BenchmarkReceipt> runtimes) {
        requireRealCapture(fixture);
        Objects.requireNonNull(deterministicVolumes, "deterministicVolumes");
        Objects.requireNonNull(runtimes, "runtimes");
        OracleContract c = fixture.contract();
        c.validate();
        // Verify fixture identities
        if (!"a5fea400048bb5965602c06e8c7f4fc3e841f842a7347f8e206b964ccee9de33".equalsIgnoreCase(fixture.contentSha256())) {
            throw new IllegalArgumentException("fixture contentSha256 must be a5fea400..., was " + fixture.contentSha256());
        }
        if (!"b08526b4ac28f2033d778977331b407bf207b57334998c970471b91afe0404d5".equalsIgnoreCase(fixture.captureProtocolSha256())) {
            throw new IllegalArgumentException("captureProtocolSha256 must be b08526..., was " + fixture.captureProtocolSha256());
        }

        Map<Level, boolean[]> oracleMasks = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            VoxelVolume v = fixture.volume(l);
            boolean[] mask = toBlockMask(l, c, v);
            oracleMasks.put(l, mask);
        }

        Map<Level, LevelEvidence> perLevel = new EnumMap<>(Level.class);
        for (Level level : Level.values()) {
            RoiMapping mapping = mappingForLevel(level, c);
            boolean[] oracleMask = oracleMasks.get(level);
            int oraclePos = countTrue(oracleMask);
            PlantFlowerCounts oraclePF = countPlantFlowerInMask(level, c, fixture.volume(level));

            // OMIT
            boolean[] omitMask = omitMask();
            ChorusMetrics omitMetrics = computeMetrics(oracleMask, omitMask);

            // Deterministic
            VoxelVolume detVol = deterministicVolumes.get(level);
            if (detVol == null) throw new IllegalArgumentException("missing deterministic volume for " + level);
            boolean[] detMask = toBlockMask(level, c, detVol);
            ChorusMetrics detMetrics = computeMetrics(oracleMask, detMask);
            BenchmarkReceipt rt = runtimes.get(level);
            PlantFlowerCounts detPF = countPlantFlowerInMask(level, c, detVol);

            perLevel.put(level, new LevelEvidence(level, mapping, oraclePos, omitMetrics, detMetrics, rt, oraclePF, detPF));
        }

        // Transitions: L4->L3, L3->L2, L2->L1, L1->L0
        Map<String, TransitionEvidence> transitions = new java.util.LinkedHashMap<>();
        Level[] levels = {Level.L4, Level.L3, Level.L2, Level.L1, Level.L0};
        // Need deterministic masks again for transition
        Map<Level, boolean[]> detMasks = new EnumMap<>(Level.class);
        for (Level l : Level.values()) {
            detMasks.put(l, toBlockMask(l, c, deterministicVolumes.get(l)));
        }
        for (int i = 0; i < levels.length - 1; i++) {
            Level from = levels[i];
            Level to = levels[i + 1];
            boolean[] oracleFrom = oracleMasks.get(from);
            boolean[] oracleTo = oracleMasks.get(to);
            ChorusMetrics oracleVsOracle = computeMetrics(oracleTo, oracleFrom); // treat 'to' as expected? Actually we compare from vs to: report disagreements between oracle(from) and oracle(to). Use symmetric: tp is both true. So order matters for precision/recall but disagreements symmetric. We will treat 'to' (finer) as expected (ground truth) and 'from' (coarser) as candidate, so recall shows how much finer is missed at coarser.
            // For oracle vs oracle: finer is truth, coarser is candidate
            ChorusMetrics oVsO = computeMetrics(oracleTo, oracleFrom);

            boolean[] omitFrom = omitMask();
            ChorusMetrics omitToNext = computeMetrics(oracleTo, omitFrom);

            boolean[] detFrom = detMasks.get(from);
            ChorusMetrics detToNext = computeMetrics(oracleTo, detFrom);

            String key = from.name() + "->" + to.name();
            transitions.put(key, new TransitionEvidence(from, to, oVsO, omitToNext, detToNext));
        }

        return new CommonRoiEvidenceReceipt(
                fixture.provenanceId(),
                fixture.contentSha256(),
                fixture.captureProtocolSha256(),
                fixture.evidenceKind().name(),
                c.seed(),
                fixture.actualCaptureStage(),
                Map.copyOf(perLevel),
                Map.copyOf(transitions)
        );
    }

    private static int countTrue(boolean[] mask) {
        int c = 0;
        for (boolean b : mask) if (b) c++;
        return c;
    }
}
