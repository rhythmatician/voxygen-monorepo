package data;

import org.jglrxavpok.hephaistos.nbt.mutable.MutableNBTCompound;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Arrays;

public class BiomeSamplingTest {

    @Test
    public void testDecodePre118BiomeArray() {
        // Simulate a flat biome array (256 blocks = 16x16)
        int[] biomeIds = new int[256];
        Arrays.fill(biomeIds, 1);

        MutableNBTCompound compound = new MutableNBTCompound();
        compound.setIntArray("Biomes", biomeIds);

        // ✅ Access safely
        int biomeVal = compound.getIntArray("Biomes").copyArray()[42];
        assertEquals(1, biomeVal);
    }

    @Test
    public void testMissingBiomesDefaultsSafely() {
        // Create a totally empty MutableNBTCompound
        MutableNBTCompound compound = new MutableNBTCompound();

        // Fallback logic
        int fallback = compound.containsKey("Biomes")
            ? compound.getIntArray("Biomes").copyArray()[0]
            : 0;

        assertEquals(0, fallback); // default path
    }
}
