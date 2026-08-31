package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import com.rhythmatician.voxygen.backend.voxy.VoxyWorldBinding;

/**
 * Source-contract guards for the VoxyWorldBinding hot path. Behavioral
 * verification of the reflection-backed write path needs the real Voxy jar
 * (voxyTopologyTest environment); keep these narrow static guards until a
 * fake WorldEngine fixture exists.
 *
 * <p>Per parent refinement the binding writes 8 children; each write used to
 * probe the same 8 grandchild sections three times (existence mask, stored
 * data mask, then stored data mask again for backing). The probes must be
 * fused into one pass per write.
 */
class VoxyWorldBindingSourceGuardTest {

    @Test
    void writeFullWorldSectionProbesChildTopologyOncePerWrite() throws Exception {
        String body = methodBody(
                "public static int writeFullWorldSection(Object worldEngine, int lvl,",
                "@FunctionalInterface");

        assertTrue(body.contains("computeChildTopologyMasks("),
                "writeFullWorldSection must use the fused one-pass topology probe");
        assertFalse(body.contains("computeChildExistenceMask("),
                "writeFullWorldSection must not run a separate existence-mask probe");
        assertFalse(body.contains("computeStoredChildDataMask("),
                "stored-data mask must come from the fused probe, not a separate scan");
    }

    private static String methodBody(String startMarker, String endMarker) throws Exception {
        String source = Files.readString(findSource("VoxyWorldBinding.java"));
        int start = source.indexOf(startMarker);
        if (start < 0) throw new IllegalStateException("start marker not found: " + startMarker);
        int end = source.indexOf(endMarker, start);
        if (end < 0) throw new IllegalStateException("end marker not found: " + endMarker);
        return source.substring(start, end);
    }

    @SuppressWarnings("unused")
    private static int countOccurrences(String haystack, String needle) {
        int count = 0;
        int index = 0;
        while ((index = haystack.indexOf(needle, index)) >= 0) {
            count++;
            index += needle.length();
        }
        return count;
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
            candidate = current.resolve(
                    "src/main/java/com/rhythmatician/voxygen/backend/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
            candidate = current.resolve(
                    "java/src/main/java/com/rhythmatician/voxygen/backend/voxy/" + fileName);
            if (Files.exists(candidate)) return candidate;
        }
        throw new IllegalStateException("source not found: " + fileName);
    }
}
