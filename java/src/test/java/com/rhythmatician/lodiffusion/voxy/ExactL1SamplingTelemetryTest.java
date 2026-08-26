package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class ExactL1SamplingTelemetryTest {
    @Test
    void separatesRawSamplesCoordinateAcceptanceAndReducedSolids() {
        ExactL1SamplingTelemetry telemetry = new ExactL1SamplingTelemetry();
        int[] chunkCalls = {0};
        ExactEndL1Candidate candidate = new ExactEndL1Candidate(
                (chunkX, chunkZ, minY, maxY, consumer) -> {
                    telemetry.recordProtoChunk();
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
        assertEquals("child=1,proto=16,callback=3,air=1,nonair=1,"
                        + "accepted=2,solid=1,heightOracle=0/0/-2147483648"
                        + "@x2147483647..-2147483648,z2147483647..-2147483648"
                        + ",y2147483647..-2147483648",
                telemetry.compact());
    }

    @Test
    void resetStartsANewSessionSnapshot() {
        ExactL1SamplingTelemetry telemetry = new ExactL1SamplingTelemetry();
        telemetry.recordChildCall();
        telemetry.recordReducedSolidVoxels(7);
        assertTrue(telemetry.claimHeightOracleProbe());
        telemetry.recordHeightOracle(2, 3, 0, 64, 64);

        telemetry.reset();

        assertEquals("child=0,proto=0,callback=0,air=0,nonair=0,"
                        + "accepted=0,solid=0,heightOracle=0/0/-2147483648"
                        + "@x2147483647..-2147483648,z2147483647..-2147483648"
                        + ",y2147483647..-2147483648",
                telemetry.compact());
    }
}
