package net.lodiffusion.shadow;

import org.lwjgl.system.MemoryUtil;

/**
 * Decodes Voxy request queue entries (8-byte uvec2 format).
 * 
 * Request format (from Voxy's pos_util.glsl):
 * - packedPos.x: [LOD(4) | Y(8) | Xhi(20)]
 * - packedPos.y: [Zhi(4) | Xlo(24) | reserved(4)]
 * 
 * Coordinates are signed 30-bit values in Voxy's 16-voxel-per-block scheme.
 */
public class VoxyRequestDecoder {
    
    /**
     * Represents a single terrain generation request from Voxy.
     */
    public static class VoxyNodeRequest {
        public int lodLevel;  // [0, 4] valid; LOD0 = finest, LOD4 = coarsest
        public int worldX;    // World X coordinate (signed, in 16-voxel units)
        public int worldY;    // World Y coordinate (signed, [-128, 127] valid)
        public int worldZ;    // World Z coordinate (signed, in 16-voxel units)
        /** True when this request fills a missing child of a partial WorldSection. */
        public boolean isPartialFill;
        
        @Override
        public String toString() {
            return String.format("VoxyNodeRequest{lod=%d, pos=(%d,%d,%d)%s}", 
                lodLevel, worldX, worldY, worldZ,
                isPartialFill ? ", PARTIAL_FILL" : "");
        }
    }
    
    /**
     * Decode a single 8-byte uvec2 request from buffer.
     * 
     * @param bufferAddr Base address of buffer
     * @param offsetBytes Offset in bytes from buffer start
     * @return Decoded request
     */
    public static VoxyNodeRequest decode(long bufferAddr, int offsetBytes) {
        // Read two uints (little-endian)
        int packedX = readIntLE(bufferAddr, offsetBytes);
        int packedY = readIntLE(bufferAddr, offsetBytes + 4);
        
        VoxyNodeRequest req = new VoxyNodeRequest();
        
        // Extract LOD level (bits 31:28 of packedX)
        req.lodLevel = (packedX >>> 28) & 0xF;
        
        // Extract Y coordinate (bits 27:20 of packedX, signed)
        // GLSL: ((int(packedPos.x)<<4)>>24)
        req.worldY = (packedX << 4) >> 24;
        
        // Extract X coordinate (bits 27:4 of packedY, 24-bit value)
        // GLSL: (int(packedPos.y)<<4)>>8
        req.worldX = (packedY << 4) >> 8;
        
        // Extract Z coordinate (bits 19:0 of packedX + bits 31:28 of packedY)
        // GLSL: z = int((packedPos.x&((1u<<20)-1))<<4) | int(packedPos.y>>28)
        //       z = (z<<8)>>8  [sign-extend]
        int zPart1 = (packedX & 0xFFFFF) << 4;      // Bits 19:0 of X
        int zPart2 = (packedY >>> 28) & 0xF;        // Bits 31:28 of Y
        int z = zPart1 | zPart2;
        req.worldZ = (z << 8) >> 8;  // Sign-extend (arithmetic shift)
        
        return req;
    }
    
    /**
     * Decode all requests from a download buffer.
     * Format: [count(4 bytes) | padding(4) | request[0](8) | request[1](8) | ...]
     * 
     * @param bufferAddr Base address
     * @param bufferSizeBytes Total size of buffer
     * @return Array of decoded requests
     */
    public static VoxyNodeRequest[] decodeAll(long bufferAddr, long bufferSizeBytes) {
        if (bufferSizeBytes < 8) {
            return new VoxyNodeRequest[0];
        }
        
        // Read count (first 4 bytes, little-endian)
        int count = readIntLE(bufferAddr, 0);
        
        // Validate
        long expectedSize = 8L + (long) count * 8L;
        if (expectedSize > bufferSizeBytes) {
            count = (int) ((bufferSizeBytes - 8) / 8);
        }
        
        VoxyNodeRequest[] requests = new VoxyNodeRequest[count];
        for (int i = 0; i < count; i++) {
            requests[i] = decode(bufferAddr, 8 + i * 8);
        }
        return requests;
    }
    
    /**
     * Read a 4-byte little-endian integer from native memory.
     */
    private static int readIntLE(long baseAddr, int offsetBytes) {
        long addr = baseAddr + offsetBytes;
        // JNI-style memory read (would use Unsafe or MemoryUtil in actual code)
        return (int) readNativeInt32(addr);
    }
    
    /**
     * Read 4-byte little-endian signed integer directly from native memory.
     */
    private static long readNativeInt32(long addr) {
        return MemoryUtil.memGetInt(addr);
    }
    
    /**
     * Reference implementation using ByteBuffer (for testing without native mem access).
     */
    public static VoxyNodeRequest decodeFromByteBuffer(byte[] buffer, int offsetBytes) {
        if (buffer.length < offsetBytes + 8) {
            throw new IndexOutOfBoundsException(
                "Buffer too small: need " + (offsetBytes + 8) + ", have " + buffer.length
            );
        }
        
        // Read two little-endian uints
        int packedX = readIntLEFromBytes(buffer, offsetBytes);
        int packedY = readIntLEFromBytes(buffer, offsetBytes + 4);
        
        VoxyNodeRequest req = new VoxyNodeRequest();
        req.lodLevel = (packedX >>> 28) & 0xF;
        req.worldY = (packedX << 4) >> 24;
        req.worldX = (packedY << 4) >> 8;
        
        int zPart1 = (packedX & 0xFFFFF) << 4;
        int zPart2 = (packedY >>> 28) & 0xF;
        int z = zPart1 | zPart2;
        req.worldZ = (z << 8) >> 8;
        
        return req;
    }
    
    private static int readIntLEFromBytes(byte[] buffer, int offset) {
        return (buffer[offset] & 0xFF) |
                ((buffer[offset + 1] & 0xFF) << 8) |
                ((buffer[offset + 2] & 0xFF) << 16) |
                ((buffer[offset + 3] & 0xFF) << 24);
    }
}
