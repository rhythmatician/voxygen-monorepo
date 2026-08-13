package fixtures;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.zip.DeflaterOutputStream;
import org.jglrxavpok.hephaistos.collections.ImmutableLongArray;
import org.jglrxavpok.hephaistos.nbt.CompressedProcesser;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.jglrxavpok.hephaistos.nbt.NBTWriter;
import org.jglrxavpok.hephaistos.nbt.mutable.MutableNBTCompound;
import com.rhythmatician.lodiffusion.world.ChunkDataExtractor;

/**
 * Lightweight Fake builders for chunk NBT and region files.
 * In-memory NBT, no committed .mca needed. Asserts behaviour not calls.
 */
public final class SyntheticChunkFixtures {
    private SyntheticChunkFixtures() {}

    public static NBTCompound uniformChunk(int flatHeight) {
        int[][] hm = new int[16][16];
        for (int x = 0; x < 16; x++) for (int z = 0; z < 16; z++) hm[x][z] = flatHeight;
        return chunkWithHeightmap(hm);
    }

    public static NBTCompound chunkWithHeightmap(int[][] heightmap) {
        long[] packed = ChunkDataExtractor.encodeHeightmapToLongArray(heightmap);
        MutableNBTCompound root = new MutableNBTCompound();
        root.setInt("DataVersion", 3218);
        root.setInt("xPos", 0);
        root.setInt("zPos", 0);
        root.setString("Status", "full");
        MutableNBTCompound heightmaps = new MutableNBTCompound();
        heightmaps.setLongArray("MOTION_BLOCKING", new ImmutableLongArray(packed));
        heightmaps.setLongArray("WORLD_SURFACE", new ImmutableLongArray(packed));
        root.set("Heightmaps", heightmaps.toCompound());
        return root.toCompound();
    }

    public static NBTCompound chunkWithBiomes(int[][] heightmap) {
        long[] packed = ChunkDataExtractor.encodeHeightmapToLongArray(heightmap);
        MutableNBTCompound root = new MutableNBTCompound();
        root.setInt("DataVersion", 3218);
        root.setInt("xPos", 0);
        root.setInt("zPos", 0);
        root.setString("Status", "full");
        MutableNBTCompound heightmaps = new MutableNBTCompound();
        heightmaps.setLongArray("MOTION_BLOCKING", new ImmutableLongArray(packed));
        root.set("Heightmaps", heightmaps.toCompound());
        MutableNBTCompound level = new MutableNBTCompound();
        level.setIntArray("Biomes", new int[256]);
        root.set("Level", level.toCompound());
        return root.toCompound();
    }

    public static File writeFakeRegion(File dir, int regionX, int regionZ,
            int[][] localCoords, NBTCompound[] chunks) throws IOException {
        if (localCoords.length != chunks.length) throw new IllegalArgumentException("mismatch");
        dir.mkdirs();
        File regionFile = new File(dir, "r." + regionX + "." + regionZ + ".mca");
        byte[][] deflated = new byte[chunks.length][];
        for (int i = 0; i < chunks.length; i++) deflated[i] = deflateNbt(chunks[i]);
        int headerSectors = 2;
        int[] offsets = new int[chunks.length];
        int[] sectorCounts = new int[chunks.length];
        int nextSector = headerSectors;
        for (int i = 0; i < chunks.length; i++) {
            int payload = 4 + 1 + deflated[i].length;
            int sectors = (payload + 4095) / 4096;
            offsets[i] = nextSector;
            sectorCounts[i] = sectors;
            nextSector += sectors;
        }
        int totalSectors = nextSector;
        int fileLength = totalSectors * 4096;
        try (RandomAccessFile raf = new RandomAccessFile(regionFile, "rw")) {
            raf.setLength(fileLength);
            byte[] header = new byte[8192];
            for (int i = 0; i < chunks.length; i++) {
                int localX = localCoords[i][0];
                int localZ = localCoords[i][1];
                int chunkIndex = (localZ & 31) * 32 + (localX & 31);
                int location = (offsets[i] << 8) | (sectorCounts[i] & 0xFF);
                int pos = chunkIndex * 4;
                header[pos] = (byte) ((location >>> 24) & 0xFF);
                header[pos + 1] = (byte) ((location >>> 16) & 0xFF);
                header[pos + 2] = (byte) ((location >>> 8) & 0xFF);
                header[pos + 3] = (byte) (location & 0xFF);
            }
            raf.seek(0);
            raf.write(header);
            for (int i = 0; i < chunks.length; i++) {
                raf.seek((long) offsets[i] * 4096);
                raf.writeInt(deflated[i].length + 1);
                raf.writeByte(2);
                raf.write(deflated[i]);
                int payload = 4 + 1 + deflated[i].length;
                int pad = sectorCounts[i] * 4096 - payload;
                if (pad > 0) raf.write(new byte[pad]);
            }
        }
        return regionFile;
    }

    private static byte[] deflateNbt(NBTCompound tag) throws IOException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (NBTWriter writer = new NBTWriter(baos, CompressedProcesser.NONE)) {
            writer.writeNamed("", tag);
        }
        byte[] uncompressed = baos.toByteArray();
        ByteArrayOutputStream deflated = new ByteArrayOutputStream();
        try (DeflaterOutputStream dos = new DeflaterOutputStream(deflated)) {
            dos.write(uncompressed);
        }
        return deflated.toByteArray();
    }

    public static File writeSingleChunkRegion(File dir, int regionX, int regionZ,
            int localX, int localZ, NBTCompound chunk) throws IOException {
        return writeFakeRegion(dir, regionX, regionZ,
                new int[][]{{localX, localZ}}, new NBTCompound[]{chunk});
    }
}
