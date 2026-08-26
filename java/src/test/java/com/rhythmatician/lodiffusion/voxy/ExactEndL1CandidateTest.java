package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class ExactEndL1CandidateTest {
    @Test
    void alignedRegionRequestsExactlySixteenChunkColumns() {
        List<ChunkRequest> requests = new ArrayList<>();
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) ->
                        requests.add(new ChunkRequest(chunkX, chunkZ, minY, maxY)));

        VoxelVolume result = candidate.produceExactL1(new SectionPos(-4, 0, 8));

        assertEquals(32, result.extent());
        assertEquals(16, requests.size());
        assertEquals(16, new HashSet<>(requests).size());
        assertEquals(Set.of(-4, -3, -2, -1), values(requests, true));
        assertEquals(Set.of(8, 9, 10, 11), values(requests, false));
        assertTrue(requests.stream().allMatch(r -> r.minY == 0 && r.maxY == 64));
    }

    @Test
    void anySolidCornerSurvivesTheTwoByTwoByTwoReduction() {
        for (int corner = 0; corner < 8; corner++) {
            int dx = corner & 1;
            int dz = (corner >> 1) & 1;
            int dy = (corner >> 2) & 1;
            ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                    (chunkX, chunkZ, minY, maxY, consumer) -> {
                        if (chunkX == 0 && chunkZ == 0) consumer.accept(dx, dy, dz, true);
                    });

            VoxelVolume result = candidate.produceExactL1(new SectionPos(0, 0, 0));

            assertEquals(EndL4DeterministicCandidate.BLOCK_END_STONE,
                    result.blockId(0, 0, 0), "corner " + corner);
            assertEquals(1, result.countNonAir(), "corner " + corner);
        }
    }

    @Test
    void responsibilityBoundsClipSamplingAndAirPadOutsideRegions() {
        int[] calls = {0};
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> calls[0]++);

        assertTrue(candidate.produceExactL1(new SectionPos(0, -4, 0)).isAllAir());
        assertTrue(candidate.produceExactL1(new SectionPos(0, 8, 0)).isAllAir());
        assertEquals(0, calls[0]);

        candidate.produceExactL1(new SectionPos(0, 4, 0));
        assertEquals(16, calls[0]);
    }

    @Test
    void rejectsMisalignmentBeforeRequestingAChunkColumn() {
        int[] calls = {0};
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> calls[0]++);

        assertThrows(IllegalArgumentException.class,
                () -> candidate.produceExactL1(new SectionPos(1, 0, 0)));
        assertEquals(0, calls[0]);
    }

    @Test
    void repeatedProductionIsDeterministicAndNeverRequestsOutsideTheRegion() {
        Set<ChunkRequest> requests = new HashSet<>();
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> {
                    requests.add(new ChunkRequest(chunkX, chunkZ, minY, maxY));
                    consumer.accept(chunkX * 16, minY, chunkZ * 16, true);
                });
        SectionPos origin = new SectionPos(4, 4, -8);

        VoxelVolume first = candidate.produceExactL1(origin);
        VoxelVolume second = candidate.produceExactL1(origin);

        assertEquals(first.countNonAir(), second.countNonAir());
        assertEquals(16, first.countNonAir());
        assertTrue(requests.stream().allMatch(r ->
                r.chunkX >= 4 && r.chunkX < 8 && r.chunkZ >= -8 && r.chunkZ < -4));
    }

    private static Set<Integer> values(List<ChunkRequest> requests, boolean x) {
        Set<Integer> values = new HashSet<>();
        for (ChunkRequest request : requests) values.add(x ? request.chunkX : request.chunkZ);
        return values;
    }

    private record ChunkRequest(int chunkX, int chunkZ, int minY, int maxY) {}
}
