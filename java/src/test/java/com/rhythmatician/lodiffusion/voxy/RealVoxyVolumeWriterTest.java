package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/**
 * Deterministic unit tests for the production {@link RealVoxyVolumeWriter}.
 *
 * <p>Exercises the pure, Voxy-independent surface of the real adapter: constructor
 * contract validation, the YZX index math (the transpose bug the seam was built to
 * localize), and the canonical -&gt; Voxy fallback block map.  The reflection-backed
 * write path itself is guarded by {@code VoxyCompat.isAvailable()}, so without the
 * Voxy jar on the classpath {@code writeSection}/{@code writeRegion} must fail fast
 * with {@link VolumeUnavailableException} rather than NPE or silently no-op.
 */
class RealVoxyVolumeWriterTest {

    private static final int[] BIOME_MAP = new int[CanonicalRegistries.BIOME_COUNT];
    private static final int[] BLOCK_MAP = new int[CanonicalRegistries.BLOCK_COUNT];

    @AfterEach
    void clearTopologyOwnership() {
        VoxyTopologyOwnership.clearForTest();
    }

    private static RealVoxyVolumeWriter writer() {
        return new RealVoxyVolumeWriter(new Object(), new Object(), BIOME_MAP, BLOCK_MAP);
    }

    // ------------------------------------------------------------------
    // Constructor contract
    // ------------------------------------------------------------------

    @Test
    void constructor_rejectsNullArgs() {
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(null, new Object(), BIOME_MAP, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), null, BIOME_MAP, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(), null, BLOCK_MAP));
        assertThrows(NullPointerException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(), BIOME_MAP, null));
    }

    @Test
    void constructor_rejectsWrongArraySizes() {
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        new int[CanonicalRegistries.BIOME_COUNT - 1], BLOCK_MAP));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        new int[CanonicalRegistries.BIOME_COUNT + 1], BLOCK_MAP));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        BIOME_MAP, new int[CanonicalRegistries.BLOCK_COUNT - 1]));
        assertThrows(IllegalArgumentException.class,
                () -> new RealVoxyVolumeWriter(new Object(), new Object(),
                        BIOME_MAP, new int[CanonicalRegistries.BLOCK_COUNT + 1]));
    }

    @Test
    void constructor_acceptsExactSizes() {
        // Should not throw
        writer();
    }

    // ------------------------------------------------------------------
    // YZX index math — the transpose bug the seam localizes
    // ------------------------------------------------------------------

    @Test
    void yzxIndex_isYMajorThenZThenX() {
        // index = (y<<10)|(z<<5)|x for a 32^3 WorldSection
        assertEquals(0, RealVoxyVolumeWriter.yzxIndex(0, 0, 0));
        assertEquals(1, RealVoxyVolumeWriter.yzxIndex(1, 0, 0));
        assertEquals(1 << 5, RealVoxyVolumeWriter.yzxIndex(0, 0, 1));
        assertEquals(1 << 10, RealVoxyVolumeWriter.yzxIndex(0, 1, 0));
        // Asymmetric sentinel: swapping axes must change the index
        assertTrue(RealVoxyVolumeWriter.yzxIndex(1, 2, 3) != RealVoxyVolumeWriter.yzxIndex(3, 2, 1));
        assertTrue(RealVoxyVolumeWriter.yzxIndex(1, 2, 3) != RealVoxyVolumeWriter.yzxIndex(2, 1, 3));
    }

    @Test
    void yzxIndex_coversFull32Cube() {
        // Max index for (31,31,31) must be 32^3 - 1
        assertEquals(32 * 32 * 32 - 1, RealVoxyVolumeWriter.yzxIndex(31, 31, 31));
        // Every index in [0, 32^3) is reachable exactly once (bijective)
        boolean[] seen = new boolean[32 * 32 * 32];
        for (int y = 0; y < 32; y++) {
            for (int z = 0; z < 32; z++) {
                for (int x = 0; x < 32; x++) {
                    int idx = RealVoxyVolumeWriter.yzxIndex(x, y, z);
                    assertTrue(idx >= 0 && idx < seen.length, "index out of range: " + idx);
                    assertTrue(!seen[idx], "duplicate index " + idx);
                    seen[idx] = true;
                }
            }
        }
    }

    // ------------------------------------------------------------------
    // Fallback block map — canonical IDs must match FallbackPalette
    // ------------------------------------------------------------------

    @Test
    void buildFallbackBlockMap_mapsCanonicalIdsFromFallbackPalette() {
        HeightmapFallbackGenerator.FallbackBlockIds voxyIds =
                new HeightmapFallbackGenerator.FallbackBlockIds(
                        0, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111);
        int[] map = RealVoxyVolumeWriter.buildFallbackBlockMap(voxyIds);

        VoxelPredictionDecoder.FallbackPalette palette = VoxelPredictionDecoder.FallbackPalette.defaults();
        assertEquals(voxyIds.air(), map[palette.air()]);
        assertEquals(voxyIds.stone(), map[palette.stone()]);
        assertEquals(voxyIds.deepslate(), map[palette.deepslate()]);
        assertEquals(voxyIds.dirt(), map[palette.dirt()]);
        assertEquals(voxyIds.grassBlock(), map[palette.grassBlock()]);
        assertEquals(voxyIds.sand(), map[palette.sand()]);
        assertEquals(voxyIds.water(), map[palette.water()]);
        assertEquals(voxyIds.redSand(), map[palette.redSand()]);
        assertEquals(voxyIds.gravel(), map[palette.gravel()]);
        assertEquals(voxyIds.snowLayer(), map[palette.snowLayer()]);
        assertEquals(voxyIds.podzol(), map[palette.podzol()]);
        assertEquals(voxyIds.mycelium(), map[palette.mycelium()]);
    }

    @Test
    void buildFallbackBlockMap_unmappedEntriesStayAir() {
        HeightmapFallbackGenerator.FallbackBlockIds voxyIds =
                new HeightmapFallbackGenerator.FallbackBlockIds(
                        0, 100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110, 111);
        int[] map = RealVoxyVolumeWriter.buildFallbackBlockMap(voxyIds);
        assertEquals(CanonicalRegistries.BLOCK_COUNT, map.length);
        // A canonical ID not in the fallback palette must map to 0 (air)
        assertEquals(0, map[1]);
        assertEquals(0, map[500]);
        assertEquals(0, map[CanonicalRegistries.BLOCK_ID_MAX]);
    }

    // ------------------------------------------------------------------
    // Unavailable backend — fail fast, never NPE
    // ------------------------------------------------------------------

    @Test
    void writeSection_whenVoxyUnavailable_throwsVolumeUnavailable() {
        RealVoxyVolumeWriter w = writer();
        VoxelVolume v = VoxelVolume.uniform(16, 1, 0);
        assertThrows(VolumeUnavailableException.class,
                () -> w.writeSection(new SectionPos(0, 0, 0), v));
    }

    @Test
    void writeRegion_whenVoxyUnavailable_throwsVolumeUnavailable() {
        RealVoxyVolumeWriter w = writer();
        VoxelVolume v = VoxelVolume.uniform(32, 1, 0);
        assertThrows(VolumeUnavailableException.class,
                () -> w.writeRegion(new SectionPos(0, 0, 0), Level.L0, v));
    }

    @Test
    void writeSection_rejectsWrongExtent_beforeAvailabilityCheck() {
        RealVoxyVolumeWriter w = writer();
        // Extent validation happens before the availability guard
        assertThrows(IllegalArgumentException.class,
                () -> w.writeSection(new SectionPos(0, 0, 0), VoxelVolume.uniform(32, 1, 0)));
    }

    @Test
    void writeRegion_rejectsMisaligned_beforeAvailabilityCheck() {
        RealVoxyVolumeWriter w = writer();
        assertThrows(IllegalArgumentException.class,
                () -> w.writeRegion(new SectionPos(1, 0, 0), Level.L1, VoxelVolume.uniform(32, 1, 0)));
    }

    @Test
    void childMaterializationAdvertisesGeneratedAndPreservedButNotEmpty() {
        assertTrue(ChildMaterializationOutcome.generatedFallback(7).advertiseToParent());
        assertTrue(ChildMaterializationOutcome.preservedExisting().advertiseToParent());
        assertFalse(ChildMaterializationOutcome.empty().advertiseToParent());
        assertEquals(7, ChildMaterializationOutcome.generatedFallback(7).nonAirWritten());
    }

    @Test
    void preservedExistingChildKeepsNativeNecUnownedAndAdvertisesParentBit() {
        Object nativeSection = new Object();
        FakeRegionBackend backend = new FakeRegionBackend(nativeSection);
        backend.preexistingMask = 1 << 2;
        backend.nativeNec = 0x5D;
        RealVoxyVolumeWriter writer = writer(backend);

        writer.refineParent(intent(Level.L2, 0xFF));

        assertEquals(0x5D, backend.nativeNec);
        assertFalse(VoxyTopologyOwnership.isOwned(nativeSection));
        assertEquals(0xFF, backend.publishedMask);
        assertEquals(1, backend.publicationCount);
    }

    @Test
    void generatedChildIsClaimedByStorageWriteAndCommitDoesNotClaimAgain() {
        Object generatedSection = new Object();
        FakeRegionBackend backend = new FakeRegionBackend(generatedSection);
        backend.claimGeneratedOnWrite = true;
        RealVoxyVolumeWriter writer = writer(backend);

        writer.refineParent(intent(Level.L2, 0xFF));

        assertEquals(8, backend.writeCalls);
        assertEquals(8, backend.claimAttempts);
        assertTrue(VoxyTopologyOwnership.isOwned(generatedSection));
    }

    @Test
    void raceToExistingIsPreservedRatherThanReportedWritten() {
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        backend.raceExistingMask = 1;
        RealVoxyVolumeWriter writer = writer(backend);

        WriteOutcome outcome = writer.writeRegion(
                new SectionPos(0, 0, 0), Level.L1, solidVolume());

        assertEquals(WriteOutcome.Status.SKIPPED_EXISTS, outcome.status());
        assertEquals(0, outcome.nonAirWritten());
    }

    /**
     * A region write that carries a vanilla-owned octant mask must forward
     * that mask to the storage backend so vanilla terrain in those octants
     * survives the coarse candidate overwrite.
     */
    @Test
    void writeRegionForwardsVanillaPreserveMaskToBackend() {
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        RealVoxyVolumeWriter writer = writer(backend);

        writer.writeRegion(new SectionPos(0, 0, 0), Level.L1, solidVolume(), (byte) 0x41);

        assertEquals(0x41, backend.lastPreserveMask);
    }

    @Test
    void plainWriteRegionPassesZeroPreserveMask() {
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        RealVoxyVolumeWriter writer = writer(backend);

        writer.writeRegion(new SectionPos(0, 0, 0), Level.L1, solidVolume());

        assertEquals(0, backend.lastPreserveMask);
    }

    @Test
    void emptyChildIsNotOwnedOrAdvertised() {
        Object section = new Object();
        FakeRegionBackend backend = new FakeRegionBackend(section);
        RealVoxyVolumeWriter writer = writer(backend);

        writer.refineParent(intent(Level.L2, 0));

        assertEquals(0, backend.writeCalls);
        assertEquals(0, backend.publishedMask);
        assertFalse(VoxyTopologyOwnership.isOwned(section));
    }

    @Test
    void allEmptyBatchPublishesCompleteHandoffWithNoPresentChildren() {
        // A solid coarse parent whose eight children are all proved empty is a
        // complete handoff: the renderer must be told so it can retire the
        // coarse false-positive instead of silently keeping the leaf.
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        RealVoxyVolumeWriter writer = writer(backend);

        ParentRefinementResult result = writer.refineParent(intent(Level.L2, 0));

        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(1, backend.publicationCount);
        assertEquals(0x00, backend.publishedPresentMask);
        assertEquals(0xFF, backend.publishedEmptyMask);
    }

    @Test
    void mixedTerminalBatchPublishesSparsePresentMaskWithProvedEmptyBits() {
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        backend.preexistingMask = 0b0000_0010;
        backend.raceExistingMask = 0b0000_0100;
        RealVoxyVolumeWriter writer = writer(backend);

        ParentRefinementResult result = writer.refineParent(
                intent(Level.L2, 0b0011_1111));

        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(1, backend.publicationCount);
        assertEquals(0b0011_1111, backend.publishedPresentMask);
        assertEquals(0b1100_0000, backend.publishedEmptyMask);
    }

    @Test
    void mixedTransactionPublishesOneExactMaskAfterAllChildrenAreTerminal() {
        FakeRegionBackend backend = new FakeRegionBackend(new Object());
        backend.preexistingMask = 0b0000_0010;
        backend.raceExistingMask = 0b0000_0100;
        RealVoxyVolumeWriter writer = writer(backend);

        ParentRefinementResult result = writer.refineParent(
                intent(Level.L2, 0b0011_1111));

        assertEquals(ParentRefinementResult.Status.PUBLISHED, result.status());
        assertEquals(0b0011_1111, backend.publishedMask);
        assertEquals(1, backend.publicationCount);
    }

    @Test
    void preservedNativeChildStillAllowsNativePromotion() {
        Object nativeSection = new Object();
        FakeRegionBackend backend = new FakeRegionBackend(nativeSection);
        backend.preexistingMask = 1;
        RealVoxyVolumeWriter writer = writer(backend);

        writer.refineParent(intent(Level.L2, 0xFF));

        assertFalse(VoxyTopologyOwnership.beginNativePromotion(nativeSection));
        VoxyTopologyOwnership.finishNativePromotion();
    }

    private static RealVoxyVolumeWriter writer(FakeRegionBackend backend) {
        int[] blockMap = BLOCK_MAP.clone();
        blockMap[1] = 1;
        return new RealVoxyVolumeWriter(
                new Object(), new Object(), BIOME_MAP, blockMap, backend);
    }

    private static VoxelVolume solidVolume() {
        return VoxelVolume.uniform(32, 1, 0);
    }

    private static ParentRefinementIntent intent(Level parentLevel, int solidMask) {
        SectionPos parentOrigin = new SectionPos(0, 0, 0);
        return new ParentRefinementIntent(parentOrigin, parentLevel, (childLevel, childOrigin) -> {
            int wsX = WorldSectionCoord.sectionToWorldSection(childOrigin.x(), childLevel.value());
            int wsY = WorldSectionCoord.sectionToWorldSection(childOrigin.y(), childLevel.value());
            int wsZ = WorldSectionCoord.sectionToWorldSection(childOrigin.z(), childLevel.value());
            int octant = (wsX & 1) | ((wsZ & 1) << 1) | ((wsY & 1) << 2);
            return (solidMask & (1 << octant)) != 0
                    ? solidVolume()
                    : VoxelVolume.uniform(32, 0, 0);
        });
    }

    private static final class FakeRegionBackend implements RealVoxyVolumeWriter.RegionBackend {
        private final Object section;
        private int preexistingMask;
        private int raceExistingMask;
        private int nativeNec;
        private boolean claimGeneratedOnWrite;
        private int writeCalls;
        private int lastPreserveMask = -1;
        private int claimAttempts;
        private int publicationCount;
        private int publishedMask = -1;
        private int publishedPresentMask = -1;
        private int publishedEmptyMask = -1;
        private Level coveredParentLevel = Level.L2;

        private FakeRegionBackend(Object section) {
            this.section = section;
        }

        @Override
        public boolean isAvailable() {
            return true;
        }

        @Override
        public boolean isFullyPopulated(Object worldEngine, SectionPos origin, Level level) {
            return (preexistingMask & bitFor(origin, level)) != 0;
        }

        @Override
        public boolean hasCoverage(Object worldEngine, SectionPos origin, Level level) {
            return level == coveredParentLevel;
        }

        @Override
        public int writeFullWorldSection(
                Object worldEngine, Level level, int wsX, int wsY, int wsZ, long[] voxels,
                byte preserveOctantsMask) {
            writeCalls++;
            lastPreserveMask = preserveOctantsMask & 0xFF;
            int bit = 1 << ((wsX & 1) | ((wsZ & 1) << 1) | ((wsY & 1) << 2));
            if ((raceExistingMask & bit) != 0) {
                return 0;
            }
            if (claimGeneratedOnWrite) {
                claimAttempts++;
                VoxyTopologyOwnership.registerGeneratedFallback(section, level.value());
            }
            return 32 * 32 * 32;
        }

        @Override
        public void publishCompleteChildMask(
                Object worldEngine, Level parentLevel, int wsX, int wsY, int wsZ,
                CompleteChildHandoff handoff) {
            publicationCount++;
            publishedMask = Byte.toUnsignedInt(handoff.presentMask());
            publishedPresentMask = Byte.toUnsignedInt(handoff.presentMask());
            publishedEmptyMask = Byte.toUnsignedInt(handoff.emptyMask());
        }

        private static int bitFor(SectionPos origin, Level level) {
            int wsX = WorldSectionCoord.sectionToWorldSection(origin.x(), level.value());
            int wsY = WorldSectionCoord.sectionToWorldSection(origin.y(), level.value());
            int wsZ = WorldSectionCoord.sectionToWorldSection(origin.z(), level.value());
            return 1 << ((wsX & 1) | ((wsZ & 1) << 1) | ((wsY & 1) << 2));
        }
    }
}
