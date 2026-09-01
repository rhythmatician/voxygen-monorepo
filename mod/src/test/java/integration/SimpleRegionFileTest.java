package integration;

import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;
import fixtures.SyntheticChunkFixtures;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Replaces committed r.0.0.mca dependency with in-memory + small fake region.
 */
public class SimpleRegionFileTest {

    @Test
    public void testHeightmapViaPureNbtSeam() {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = 80 + x;
        NBTCompound chunk = SyntheticChunkFixtures.uniformChunk(64);
        NBTCompound varying = SyntheticChunkFixtures.chunkWithHeightmap(hm);
        int[][] decoded = ChunkDataExtractor.extractHeightmapFromChunkTag(varying);
        assertNotNull(decoded, "Pure-NBT seam should decode heightmap");
        assertEquals(16, decoded.length);
        assertEquals(16, decoded[0].length);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            assertEquals(hm[x][z], decoded[x][z], "x=" + x + " z=" + z);
            assertTrue(decoded[x][z] >= 0 && decoded[x][z] <= 512);
        }
        int[][] flat = ChunkDataExtractor.extractHeightmapFromChunkTag(chunk);
        assertNotNull(flat);
        assertEquals(64, flat[5][5]);
    }

    @Test
    public void testFakeRegionRoundTrip(@TempDir Path tmp) throws Exception {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = 90;
        NBTCompound chunk = SyntheticChunkFixtures.chunkWithHeightmap(hm);
        File region = SyntheticChunkFixtures.writeSingleChunkRegion(tmp.toFile(), 0, 0, 1, 1, chunk);
        try {
            assertTrue(region.exists() && region.length() > 0);
            int[] found = ChunkDataExtractor.findValidChunk(region);
            assertNotNull(found, "Fake region should yield valid chunk");
            int[][] fromFile = ChunkDataExtractor.extractHeightmapFromChunk(region, found[0], found[1]);
            assertNotNull(fromFile);
            assertEquals(90, fromFile[0][0]);
        } finally {
            ChunkDataExtractor.clearCache();
        }
    }

    @Test
    public void testEncodeDecodeRoundTrip() {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = (x * 16 + z) % 512;
        long[] packed = ChunkDataExtractor.encodeHeightmapToLongArray(hm);
        int[][] decoded = ChunkDataExtractor.decodeHeightmapFromLongArray(packed);
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) {
            assertEquals(hm[x][z], decoded[x][z], "round-trip x=" + x + " z=" + z);
        }
    }
}
