package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.backend.voxy.RealVoxyVolumeWriter;
import com.rhythmatician.voxygen.generation.refinement.ChildMaterializationOutcome;

/**
 * Source-contract guards for the region-write buffer path. The tracer hot
 * path allocates a fresh {@code long[32768]} (256 KB) per child write — 2 MB
 * garbage per parent refinement. The scratch buffer must be reused per
 * thread instead. Behavioral verification of the reflection-backed write
 * needs the real Voxy jar; keep these narrow static guards.
 */
class RealVoxyVolumeWriterBufferGuardTest {

    @Test
    void writeRegionInternalUsesReusableScratchNotFreshAllocation() throws Exception {
        String source = Files.readString(findSource("RealVoxyVolumeWriter.java"));
        int start = source.indexOf("private ChildMaterializationOutcome writeRegionInternal(");
        int end = source.indexOf("static int yzxIndex(", start);
        String body = source.substring(start, end);

        assertFalseContains(body, "new long[32 * 32 * 32]",
                "writeRegionInternal must not allocate a fresh 256 KB buffer per write");
        assertTrue(body.contains("regionScratchBuffer()"),
                "writeRegionInternal must obtain its buffer from the reusable scratch seam");
    }

    @Test
    void scratchBufferIsThreadLocalAndCorrectSize() throws Exception {
        // Behavioral check of the seam itself: same thread reuses the array,
        // different threads get independent arrays, size is exactly 32^3.
        long[] first = RealVoxyVolumeWriter.regionScratchBufferForTest();
        long[] second = RealVoxyVolumeWriter.regionScratchBufferForTest();
        assertSame(first, second, "same thread must reuse the scratch buffer");
        assertEquals(32 * 32 * 32, first.length);

        long[] otherThread = new long[1];
        Thread t = new Thread(() -> otherThread[0] =
                System.identityHashCode(RealVoxyVolumeWriter.regionScratchBufferForTest()));
        t.start();
        t.join();
        long[] third = RealVoxyVolumeWriter.regionScratchBufferForTest();
        assertTrue(System.identityHashCode(third) != otherThread[0],
                "different threads must get independent buffers");
    }

    private static void assertFalseContains(String haystack, String needle, String message) {
        if (haystack.contains(needle)) {
            throw new AssertionError(message);
        }
    }

    private static Path findSource(String fileName) {
        Path current = Path.of("").toAbsolutePath();
        for (int i = 0; i < 5 && current != null; i++, current = current.getParent()) {
            Path candidate = current.resolve(
                    "src/main/java/com/rhythmatician/lodiffusion/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
            candidate = current.resolve(
                    "java/src/main/java/com/rhythmatician/lodiffusion/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("source not found: " + fileName);
    }
}
