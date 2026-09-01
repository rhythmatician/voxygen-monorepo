package integration;

import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;
import fixtures.SyntheticChunkFixtures;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.io.File;
import java.io.RandomAccessFile;
import java.nio.file.Path;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Low-level region header + NBT structure test using a Fake region (no committed file).
 * Asserts header location table and raw fallback can parse the chunk.
 */
public class RawNBTStructureTest {

    @Test
    public void testFakeRegionHeaderAndFallback(@TempDir Path tmp) throws Exception {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = 70 + x + z;
        NBTCompound chunk = SyntheticChunkFixtures.chunkWithHeightmap(hm);
        File region = SyntheticChunkFixtures.writeSingleChunkRegion(tmp.toFile(), 0, 0, 2, 3, chunk);

        // Verify location table entry for (2,3) is present and offset/sector sane
        try (RandomAccessFile raf = new RandomAccessFile(region, "r")) {
            int chunkIndex = 3 * 32 + 2;
            raf.seek((long) chunkIndex * 4);
            int loc = raf.readInt();
            assertNotEquals(0, loc, "Location for (2,3) should be non-zero");
            int sectorOffset = (loc >>> 8) & 0xFFFFFF;
            int sectorCount = loc & 0xFF;
            assertTrue(sectorOffset >= 2, "Offset past header");
            assertTrue(sectorCount >= 1);
            // Unused slot should be zero
            raf.seek(0);
            assertEquals(0, raf.readInt(), "Slot (0,0) should be empty");
        }

        // File-backed extraction should succeed via Hephaistos or raw fallback
        try {
            int[][] viaFile = ChunkDataExtractor.extractHeightmapFromChunk(region, 2, 3);
            assertNotNull(viaFile, "Should decode heightmap from fake region via file API");
            assertEquals(hm[2][3], viaFile[2][3]);
        } finally {
            ChunkDataExtractor.clearCache();
        }

        // Pure-NBT seam should decode same values (no file handle, no cache needed)
        NBTCompound tag = SyntheticChunkFixtures.chunkWithHeightmap(hm);
        int[][] viaTag = ChunkDataExtractor.extractHeightmapFromChunkTag(tag);
        assertNotNull(viaTag);
        assertEquals(70, viaTag[0][0]);
    }

    @Test
    public void testEmptyRegionYieldsNoChunk(@TempDir Path tmp) throws Exception {
        File empty = new File(tmp.toFile(), "r.5.5.mca");
        try (RandomAccessFile raf = new RandomAccessFile(empty, "rw")) {
            raf.setLength(8192);
            raf.seek(0);
            raf.write(new byte[8192]);
        }
        try {
            assertNull(ChunkDataExtractor.findValidChunk(empty), "Empty region should have no valid chunk");
        } finally {
            ChunkDataExtractor.clearCache();
        }
    }
}
