package com.rhythmatician.lodiffusion.voxy;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.minecraft.block.BlockState;
import net.minecraft.world.gen.chunk.ChunkNoiseSampler;
import org.junit.jupiter.api.Test;

/** Compile/access smoke test plus an explicitly source-level broad-access guard. */
class WorldNoiseAccessExactSamplingSourceGuardTest {
    @Test
    void pinnedAccessAndSourceGuardKeepTheExactPathNarrow() throws Exception {
        assertEquals(BlockState.class,
                ChunkNoiseSampler.class.getDeclaredMethod("sampleBlockState").getReturnType());

        String source = Files.readString(findSource("WorldNoiseAccess.java"));
        int exactStart = source.indexOf("void sampleExactEndBaseTerrainChunk(");
        String exactMethod = source.substring(
                exactStart, source.indexOf("private NoiseSamplerSetup", exactStart));
        int helperStart = source.indexOf("private NoiseSamplerSetup createNoiseSampler(");
        String constructionHelper = source.substring(
                helperStart, source.indexOf("private record NoiseSamplerSetup", helperStart));

        assertTrue(constructionHelper.contains("new ChunkNoiseSampler("));
        assertTrue(exactMethod.contains("sampler.sampleBlockState()"));
        assertTrue(exactMethod.contains("sampler.swapBuffers()"));
        assertTrue(exactMethod.indexOf("blockY >= retainMinY")
                < exactMethod.indexOf("sampler.sampleBlockState()"));
        String guardedSource = exactMethod + constructionHelper;
        assertFalse(guardedSource.contains("getChunk("));
        assertFalse(guardedSource.contains("getChunkManager("));
        assertFalse(guardedSource.contains("populateNoise("));
        assertFalse(guardedSource.contains("cache"));
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
