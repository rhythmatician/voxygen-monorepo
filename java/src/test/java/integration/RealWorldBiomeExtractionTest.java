package integration;

import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;
import fixtures.SyntheticChunkFixtures;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Behaviour tests for biome extraction via the pure-NBT seam.
 * No committed .mca needed — uses in-memory Fake compounds (tmp/on_mocking.md: Fake).
 */
public class RealWorldBiomeExtractionTest {

    @Test
    public void testParseRegionCoordinates() {
        // Behaviour assertion, not file existence — parse is pure.
        java.io.File f = new java.io.File("r.-1.0.mca");
        int[] coords = ChunkDataExtractor.parseRegionCoordinates(f);
        assertNotNull(coords);
        assertEquals(-1, coords[0]);
        assertEquals(0, coords[1]);
    }

    @Test
    public void testExtractBiomesFromFakeChunk() {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = 64;
        NBTCompound chunk = SyntheticChunkFixtures.chunkWithBiomes(hm);
        String[] biomes = ChunkDataExtractor.extractBiomesFromChunkTag(chunk);
        assertNotNull(biomes, "Fake chunk should yield biome array");
        assertTrue(biomes.length == 256 || biomes.length == 1024,
                "Expected 256 or 1024 entries, got " + biomes.length);
        assertTrue(biomes[0].startsWith("minecraft:"), "Expected minecraft: namespace");
    }
}
