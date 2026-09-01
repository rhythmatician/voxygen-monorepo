package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.generation.dimension.end.ExactEndL1Candidate;
import com.rhythmatician.voxygen.generation.dimension.end.ExactL1SamplingTelemetry;
import com.rhythmatician.voxygen.semantic.SectionPos;
import com.rhythmatician.voxygen.semantic.VoxelVolume;

class ExactL1SamplingTelemetryTest {
    @Test
    void separatesRawSamplesCoordinateAcceptanceAndReducedSolids() {
        ExactL1SamplingTelemetry telemetry = new ExactL1SamplingTelemetry();
        int[] chunkCalls = {0};
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> {
                    if (chunkCalls[0]++ != 0) return;

                    telemetry.recordRetainedCallback();
                    consumer.accept(0, 0, 0, true);

                    telemetry.recordRawAir();
                    telemetry.recordRetainedCallback();
                    consumer.accept(1, 0, 0, false);

                    telemetry.recordRawExplicitNonAir();
                    telemetry.recordRetainedCallback();
                    consumer.accept(64, 0, 0, true);
                }, telemetry);

        VoxelVolume result = candidate.produceExactL1(new SectionPos(0, 0, 0));

        assertEquals(1, result.countNonAir());
        assertEquals("child=1,callback=3,air=1,nonair=1,"
                        + "accepted=2,solid=1",
                telemetry.compact());
    }

    @Test
    void resetStartsANewSessionSnapshot() {
        ExactL1SamplingTelemetry telemetry = new ExactL1SamplingTelemetry();
        telemetry.recordChildCall();
        telemetry.recordReducedSolidVoxels(7);

        telemetry.reset();

        assertEquals("child=0,callback=0,air=0,nonair=0,"
                        + "accepted=0,solid=0",
                telemetry.compact());
    }
}
