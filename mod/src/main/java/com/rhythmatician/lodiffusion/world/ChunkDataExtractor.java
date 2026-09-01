package com.rhythmatician.lodiffusion.world;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.jglrxavpok.hephaistos.collections.ImmutableLongArray;
import org.jglrxavpok.hephaistos.mca.AnvilException;
import org.jglrxavpok.hephaistos.mca.ChunkColumn;
import org.jglrxavpok.hephaistos.mca.RegionFile;
import org.jglrxavpok.hephaistos.nbt.NBTCompound;
import org.jglrxavpok.hephaistos.nbt.NBTLongArray;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.rhythmatician.voxygen.semantic.Level;

/**
 * Utility class for extracting chunk data from Minecraft world files.
 * This class provides methods to read real world data for testing and training.
 *
 * Supports NBT parsing to extract heightmaps and biome data from .mca region files.
 * Compatible with both pre-1.18 and 1.18+ Minecraft world formats.
 *
 * Performance optimizations:
 * - Caches RegionFile instances to avoid reopening files
 * - Direct long array access without copying
 * - Cached region coordinate parsing
 * - Optional profiling for performance measurement
 */
public class ChunkDataExtractor {    private static final Logger LOGGER = LoggerFactory.getLogger(ChunkDataExtractor.class);

    // Cache for RegionFile instances to avoid reopening files
    private static final ConcurrentMap<String, RegionFileCache> regionFileCache = new ConcurrentHashMap<>();

    // Cache for parsed region coordinates to avoid repeated parsing
    private static final ConcurrentMap<String, int[]> regionCoordCache = new ConcurrentHashMap<>();

    // Performance tracking
    private static volatile boolean profilingEnabled = false;
    private static final ThreadLocal<Long> operationStartTime = new ThreadLocal<>();
      /**
     * Internal cache for RegionFile instances with proper resource management
     */
    private static class RegionFileCache implements AutoCloseable {
        private final RegionFile regionFile;
        private final RandomAccessFile file;
        private volatile boolean closed = false;
        RegionFileCache(File regionFileHandle, int regionX, int regionZ) throws IOException {
            this.file = new RandomAccessFile(regionFileHandle, "r");
            try {
                this.regionFile = new RegionFile(file, regionX, regionZ);
            } catch (AnvilException e) {
                // Close file if RegionFile creation fails
                try {
                    file.close();
                } catch (IOException closeException) {
                    e.addSuppressed(closeException);
                }
                throw new IOException("Failed to create RegionFile for " + regionFileHandle.getName(), e);
            }
        }

        RegionFile getRegionFile() {
            if (closed) {
                throw new IllegalStateException("RegionFile cache has been closed");
            }
            return regionFile;
        }

        @Override
        public void close() throws IOException {
            if (!closed) {
                closed = true;
                file.close();
            }
        }
    }

    /**
     * Enable or disable performance profiling for chunk extraction operations.
     * When enabled, operations will log timing information.
     */
    public static void setProfilingEnabled(boolean enabled) {
        profilingEnabled = enabled;
    }

    /**
     * Start timing an operation (used internally for profiling)
     */
    private static void startTiming() {
        if (profilingEnabled) {
            operationStartTime.set(System.nanoTime());
        }
    }

    /**
     * End timing and log the operation duration
     */    private static void endTiming(String operation) {
        if (profilingEnabled && operationStartTime.get() != null) {
            long elapsed = (System.nanoTime() - operationStartTime.get()) / 1_000_000; // Convert to ms
            LOGGER.debug("PERF: {} took {}ms", operation, elapsed);
            operationStartTime.remove();
        }
    }
      /**
     * Clear all cached region files and coordinates. Call this to free memory.
     */
    public static void clearCache() {
        // Close all cached region files
        for (RegionFileCache cache : regionFileCache.values()) {
            try {
                cache.close();
            } catch (IOException e) {
                LOGGER.warn("Failed to close cached region file", e);
            }
        }
        regionFileCache.clear();
        regionCoordCache.clear();
    }
    /**
     * Parse region coordinates from filename (e.g. "r.0.1.mca" -> [0, 1]).
     * Results are cached to avoid repeated parsing.
     * @param regionFile Region file
     * @return Array containing [regionX, regionZ] coordinates
     * @throws IllegalArgumentException if filename format is invalid
     */
    public static int[] parseRegionCoordinates(File regionFile) {
        String filePath = regionFile.getAbsolutePath();
        String filename = regionFile.getName();

        // Check cache first using absolute path to prevent collisions
        int[] cached = regionCoordCache.get(filePath);
        if (cached != null) {
            return cached.clone(); // Return copy to prevent modification
        }

        if (!filename.matches("r\\.-?\\d+\\.-?\\d+\\.mca")) {
            throw new IllegalArgumentException("Invalid region file format: " + filename);
        }

        // Remove "r." prefix and ".mca" suffix
        String coords = filename.substring(2, filename.length() - 4);
        String[] parts = coords.split("\\.");

        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid coordinate format: " + filename);
        }        int[] result = new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) };

        // Cache the result using absolute path as key
        regionCoordCache.put(filePath, result.clone());
        return result;
    }
      /**
     * Get a cached RegionFile instance, creating one if it doesn't exist.
     * @param regionFile The region file to get/create cache for
     * @return Cached RegionFile instance
     * @throws IOException if file cannot be opened
     */
    private static RegionFileCache getOrCreateRegionFileCache(File regionFile) throws IOException {
        String cacheKey = regionFile.getAbsolutePath();

        RegionFileCache cached = regionFileCache.get(cacheKey);
        if (cached != null && !cached.closed) {
            return cached;
        }

        // Parse region coordinates for RegionFile constructor
        int[] regionCoords = parseRegionCoordinates(regionFile);
        LOGGER.debug("Creating RegionFile cache for {} with coordinates [{}, {}]",
            regionFile.getName(), regionCoords[0], regionCoords[1]);

        // Create new cache entry
        RegionFileCache newCache = new RegionFileCache(regionFile, regionCoords[0], regionCoords[1]);
        regionFileCache.put(cacheKey, newCache);

        LOGGER.debug("Successfully created RegionFile cache for {}", regionFile.getName());
        return newCache;
    }

    /**
     * Calculate chunk coordinates within a region.
     * Each region contains 32x32 chunks.
     * @param regionX Region X coordinate
     * @param regionZ Region Z coordinate
     * @param localChunkX Local chunk X within region (0-31)
     * @param localChunkZ Local chunk Z within region (0-31)
     * @return Array containing [worldChunkX, worldChunkZ]
     */
    public static int[] getWorldChunkCoordinates(int regionX, int regionZ, int localChunkX, int localChunkZ) {
        if (localChunkX < 0 || localChunkX >= 32 || localChunkZ < 0 || localChunkZ >= 32) {
            throw new IllegalArgumentException("Local chunk coordinates must be 0-31");
        }

        int worldChunkX = regionX * 32 + localChunkX;
        int worldChunkZ = regionZ * 32 + localChunkZ;

        return new int[] { worldChunkX, worldChunkZ };
    }    /**
     * Extract heightmap data from a specific chunk in a region file.
     * Uses cached RegionFile instances for better performance.
     * @param regionFile Region file to parse
     * @param chunkX Chunk X coordinate within region (0-31)
     * @param chunkZ Chunk Z coordinate within region (0-31)
     * @return 16x16 heightmap data array, or null if chunk not found
     * @throws IOException if file cannot be read
     */
    public static int[][] extractHeightmapFromChunk(File regionFile, int chunkX, int chunkZ) throws IOException {
        if (chunkX < 0 || chunkX >= 32 || chunkZ < 0 || chunkZ >= 32) {
            throw new IllegalArgumentException("Chunk coordinates must be 0-31");
        }        startTiming();

        try {            // Use cached RegionFile instance
            RegionFileCache regionCache = getOrCreateRegionFileCache(regionFile);
            RegionFile regionFileHandle = regionCache.getRegionFile();

            // Get chunk data from the region file - handle AnvilException for missing chunks
            ChunkColumn chunk;
            NBTCompound chunkTag;
            try {
                chunk = regionFileHandle.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    return null;
                }
                // Convert chunk to NBT compound
                chunkTag = chunk.toNBT();
                if (chunkTag == null) {
                    return null;
                }
            } catch (AnvilException e) {
                // Hephaistos failed - likely 1.18+ format incompatibility with "Missing field named 'Level'"
                if (e.getMessage() != null && e.getMessage().contains("Missing field named 'Level'")) {
                    LOGGER.debug("Hephaistos format incompatibility for chunk [{}, {}] in region {} - attempting raw NBT fallback: {}",
                        chunkX, chunkZ, regionFile.getName(), e.getMessage());
                    
                    // Attempt raw NBT reading for 1.18+ format
                    chunkTag = readChunkNBTDirectly(regionCache.file, chunkX, chunkZ);
                    if (chunkTag == null) {
                        LOGGER.debug("Raw NBT fallback also failed for chunk [{}, {}] in region {}",
                            chunkX, chunkZ, regionFile.getName());
                        return null;
                    }
                } else {
                    // Chunk doesn't exist in this region - this is normal
                    LOGGER.debug("Chunk [{}, {}] not found in region {} - {}",
                        chunkX, chunkZ, regionFile.getName(), e.getMessage());
                    return null;
                }
            }

            // Navigate to heightmaps section - 1.18+ format (no Level tag)
            NBTCompound heightmapsTag = null;
            if (chunkTag.containsKey("Heightmaps")) {
                heightmapsTag = chunkTag.getCompound("Heightmaps");
            } else if (chunkTag.containsKey("Level")) {
                // Fallback for pre-1.18 format with Level tag
                NBTCompound levelTag = chunkTag.getCompound("Level");
                if (levelTag != null && levelTag.containsKey("Heightmaps")) {
                    heightmapsTag = levelTag.getCompound("Heightmaps");
                }
            }

            if (heightmapsTag == null) {
                return null;
            }

            // Extract MOTION_BLOCKING heightmap (most useful for terrain generation)
            if (!heightmapsTag.containsKey("MOTION_BLOCKING")) {
                return null;
            }            NBTLongArray motionBlockingTag = (NBTLongArray) heightmapsTag.get("MOTION_BLOCKING");            if (motionBlockingTag == null) {
                return null;
            }
            // OPTIMIZATION: Use optimized decoding with cached RegionFile
            // Convert ImmutableLongArray to regular long array
            var immutableArray = motionBlockingTag.getValue();
            long[] heightData = immutableArray.copyArray();
            return decodeHeightmapFromLongArray(Arrays.copyOf(heightData, heightData.length));

        } catch (Exception e) {
            throw new IOException("Failed to parse region file: " + regionFile.getName(), e);
        } finally {
            endTiming("extractHeightmapFromChunk");
        }
    }    /**
     * Decode a packed long array from Minecraft heightmap format into a 16x16 int array.
     * Minecraft stores heightmaps as packed data in LongArrayTag format.
     * Each height value is 9 bits (max height 512), packed into 64-bit longs.
     *
     * @param packedLongs The packed long array from NBT heightmap data
     * @return 16x16 array of height values
     */
    /**
     * Encode a 16x16 heightmap into the packed long array format used by Minecraft
     * NBT MOTION_BLOCKING. Inverse of {@link #decodeHeightmapFromLongArray(long[])}.
     * Uses 9 bits per value, 7 values per long (1 bit padding per long), matching
     * the project's decoder. Returns 37 longs (ceil(256/7)) to hold 256 values;
     * callers that need exactly 36 may truncate; decoder tolerates either length
     * and defaults missing values to 64.
     *
     * @param heightmap 16x16 array
     * @return packed long array (length 37)
     */
    public static long[] encodeHeightmapToLongArray(int[][] heightmap) {
        if (heightmap == null || heightmap.length != 16) {
            throw new IllegalArgumentException("heightmap must be 16x16");
        }
        int bitsPerValue = 9;
        int valuesPerLong = 64 / bitsPerValue;
        int longsNeeded = (256 + valuesPerLong - 1) / valuesPerLong;
        long[] packed = new long[longsNeeded];
        long mask = (1L << bitsPerValue) - 1;
        for (int index = 0; index < 256; index++) {
            int x = index & 15;
            int z = index >> 4;
            if (heightmap[x] == null || heightmap[x].length != 16) {
                throw new IllegalArgumentException("heightmap must be 16x16");
            }
            int height = heightmap[x][z] & (int) mask;
            int longIndex = index / valuesPerLong;
            int bitOffset = (index % valuesPerLong) * bitsPerValue;
            packed[longIndex] |= ((long) height & mask) << bitOffset;
        }
        return packed;
    }

    /**
     * Pure-NBT seam: extract heightmap from an already-parsed chunk NBT compound
     * without touching the filesystem. Handles both 1.18+ (top-level Heightmaps) and
     * pre-1.18 (Level.Heightmaps) layouts.
     *
     * @param chunkTag NBT compound for a single chunk
     * @return 16x16 heightmap or null if no MOTION_BLOCKING tag present
     */
    public static int[][] extractHeightmapFromChunkTag(org.jglrxavpok.hephaistos.nbt.NBTCompound chunkTag) {
        if (chunkTag == null) {
            return null;
        }
        org.jglrxavpok.hephaistos.nbt.NBTCompound heightmapsTag = null;
        if (chunkTag.containsKey("Heightmaps")) {
            heightmapsTag = chunkTag.getCompound("Heightmaps");
        } else if (chunkTag.containsKey("Level")) {
            org.jglrxavpok.hephaistos.nbt.NBTCompound levelTag = chunkTag.getCompound("Level");
            if (levelTag != null && levelTag.containsKey("Heightmaps")) {
                heightmapsTag = levelTag.getCompound("Heightmaps");
            }
        }
        if (heightmapsTag == null || !heightmapsTag.containsKey("MOTION_BLOCKING")) {
            return null;
        }
        org.jglrxavpok.hephaistos.nbt.NBTLongArray motionBlockingTag =
                (org.jglrxavpok.hephaistos.nbt.NBTLongArray) heightmapsTag.get("MOTION_BLOCKING");
        if (motionBlockingTag == null) {
            return null;
        }
        long[] heightData = motionBlockingTag.getValue().copyArray();
        return decodeHeightmapFromLongArray(java.util.Arrays.copyOf(heightData, heightData.length));
    }

    public static int[][] decodeHeightmapFromLongArray(long[] packedLongs) {
        int[][] heightmap = new int[16][16];
        int bitsPerValue = 9; // Minecraft uses 9 bits per height value (max height 512)
        int valuesPerLong = 64 / bitsPerValue; // How many height values fit in one long

        for (int index = 0; index < 256; index++) { // 16x16 = 256 positions
            int x = index & 15; // index % 16
            int z = index >> 4; // index / 16

            // Calculate which long contains this value and the bit offset
            int longIndex = index / valuesPerLong;
            int bitOffset = (index % valuesPerLong) * bitsPerValue;

            if (longIndex < packedLongs.length) {
                // Extract the 9-bit height value from the packed long
                long mask = (1L << bitsPerValue) - 1; // Create 9-bit mask (0x1FF)
                int height = (int) ((packedLongs[longIndex] >>> bitOffset) & mask);
                heightmap[x][z] = height;
            } else {
                // Default height if data is missing/corrupt
                heightmap[x][z] = 64; // Sea level
            }
        }

        return heightmap;
    }/**
     * Extract biome data from a specific chunk in a region file.
     * Uses cached RegionFile instances for better performance.
     * Handles both pre-1.18 (2D biomes) and 1.18+ (3D palette-indexed biomes).
     * @param regionFile Region file to parse
     * @param chunkX Chunk X coordinate within region (0-31)
     * @param chunkZ Chunk Z coordinate within region (0-31)
     * @return Biome identifier array for surface level (16x16), or null if not found
     * @throws IOException if file cannot be read
     */    public static String[] extractBiomesFromChunk(File regionFile, int chunkX, int chunkZ) throws IOException {
        if (chunkX < 0 || chunkX >= 32 || chunkZ < 0 || chunkZ >= 32) {
            throw new IllegalArgumentException("Chunk coordinates must be 0-31");
        }

        startTiming();

        try {
            // Use cached RegionFile instance
            RegionFileCache regionCache = getOrCreateRegionFileCache(regionFile);
            RegionFile regionFileHandle = regionCache.getRegionFile();            // Get chunk data from the region file - handle AnvilException for missing chunks
            ChunkColumn chunk;
            NBTCompound chunkTag;
            try {
                chunk = regionFileHandle.getChunk(chunkX, chunkZ);
                if (chunk == null) {
                    return null;
                }
                // Convert chunk to NBT compound
                chunkTag = chunk.toNBT();
                if (chunkTag == null) {
                    return null;
                }            } catch (AnvilException e) {
                // Hephaistos failed - try raw NBT fallback for any AnvilException
                LOGGER.debug("Hephaistos failed for chunk [{}, {}] in region {} - attempting raw NBT fallback: {}",
                    chunkX, chunkZ, regionFile.getName(), e.getMessage());
                
                // Attempt raw NBT reading for any Hephaistos failure
                chunkTag = readChunkNBTDirectly(regionCache.file, chunkX, chunkZ);
                if (chunkTag == null) {
                    LOGGER.debug("Raw NBT fallback also failed for chunk [{}, {}] in region {}",
                        chunkX, chunkZ, regionFile.getName());
                    return null;
                }
            }// Try to extract biomes based on format (1.18+ vs pre-1.18)
            String[] result = extractBiomesFromChunkTag(chunkTag);
            return result;

        } catch (IOException e) {
            throw new IOException("I/O error while extracting biomes from chunk [" + chunkX + ", " + chunkZ +
                                  "] in region " + regionFile.getName(), e);
        } catch (RuntimeException e) { // Catch unexpected runtime exceptions, including NBT parsing issues
            throw new IOException("Failed to parse NBT data for chunk [" + chunkX + ", " + chunkZ +
                                  "] in region " + regionFile.getName(), e);
        } finally {
            endTiming("extractBiomesFromChunk");
        }
    }    /**
     * Find a valid chunk in the region file by scanning available chunks.
     * @param regionFile Region file to scan
     * @return Array containing [chunkX, chunkZ] coordinates of first valid chunk, or null if none found
     * @throws IOException if file cannot be read
     */    public static int[] findValidChunk(File regionFile) throws IOException {        LOGGER.debug("Scanning for valid chunks in region file: {}", regionFile.getAbsolutePath());
        LOGGER.debug("Region file exists: {}, size: {} bytes", regionFile.exists(), regionFile.length());

        try {
            RegionFileCache regionCache = getOrCreateRegionFileCache(regionFile);
            RegionFile regionFileHandle = regionCache.getRegionFile();

            // Parse region coordinates from filename to understand expected coordinate range
            int[] regionCoords = parseRegionCoordinates(regionFile);
            int regionX = regionCoords[0];
            int regionZ = regionCoords[1];

            LOGGER.debug("Region coordinates: [{}, {}]", regionX, regionZ);            // First try relative coordinates (0-31) - this is the standard approach
            int totalScanned = 0;
            for (int chunkX = 0; chunkX < 32; chunkX++) {
                for (int chunkZ = 0; chunkZ < 32; chunkZ++) {
                    totalScanned++;
                    try {
                        ChunkColumn chunk = regionFileHandle.getChunk(chunkX, chunkZ);
                        if (chunk != null) {
                            LOGGER.debug("Found valid chunk at relative coords [{}, {}] in region {} after scanning {} positions",
                                chunkX, chunkZ, regionFile.getName(), totalScanned);
                            return new int[]{chunkX, chunkZ};
                        }
                    } catch (AnvilException e) {
                        // Check if this is a format incompatibility issue (1.18+ chunk with Hephaistos expecting pre-1.18)
                        if (e.getMessage() != null && e.getMessage().contains("Missing field named 'Level'")) {
                            LOGGER.debug("Hephaistos format incompatibility for chunk [{}, {}] - attempting raw NBT check: {}",
                                chunkX, chunkZ, e.getMessage());
                            
                            // Try raw NBT reading to see if chunk actually exists in 1.18+ format
                            NBTCompound chunkTag = readChunkNBTDirectly(regionCache.file, chunkX, chunkZ);
                            if (chunkTag != null) {
                                LOGGER.debug("Found valid 1.18+ format chunk at relative coords [{}, {}] in region {} after scanning {}",
                                    chunkX, chunkZ, regionFile.getName(), totalScanned);
                                return new int[]{chunkX, chunkZ};
                            }
                        }
                        // Chunk doesn't exist, continue scanning
                        LOGGER.trace("Chunk [{}, {}] doesn't exist: {}", chunkX, chunkZ, e.getMessage());
                    }
                }
            }

            // If no chunks found with relative coordinates, try absolute world coordinates
            // For region r.-1.-1.mca, world coordinates should be from (-32, -32) to (-1, -1)
            LOGGER.debug("No chunks found with relative coordinates, trying world coordinates...");
            int worldStartX = regionX * 32;
            int worldStartZ = regionZ * 32;
            int worldEndX = worldStartX + 32;
            int worldEndZ = worldStartZ + 32;

            LOGGER.debug("Trying world coordinates from [{}, {}] to [{}, {}]",
                worldStartX, worldStartZ, worldEndX - 1, worldEndZ - 1);            for (int worldX = worldStartX; worldX < worldEndX; worldX++) {
                for (int worldZ = worldStartZ; worldZ < worldEndZ; worldZ++) {
                    totalScanned++;
                    try {
                        ChunkColumn chunk = regionFileHandle.getChunk(worldX, worldZ);
                        if (chunk != null) {
                            LOGGER.debug("Found valid chunk at world coords [{}, {}] in region {} after scanning {} positions",
                                worldX, worldZ, regionFile.getName(), totalScanned);
                            // Return the relative coordinates for consistency with existing API
                            return new int[]{worldX - worldStartX, worldZ - worldStartZ};
                        }
                    } catch (AnvilException e) {
                        // Check if this is a format incompatibility issue (1.18+ chunk with Hephaistos expecting pre-1.18)
                        if (e.getMessage() != null && e.getMessage().contains("Missing field named 'Level'")) {
                            LOGGER.debug("Hephaistos format incompatibility for world chunk [{}, {}] - attempting raw NBT check: {}",
                                worldX, worldZ, e.getMessage());
                            
                            // Convert world coordinates to relative coordinates for raw NBT reading
                            int relativeX = worldX - worldStartX;
                            int relativeZ = worldZ - worldStartZ;
                            
                            // Try raw NBT reading to see if chunk actually exists in 1.18+ format
                            NBTCompound chunkTag = readChunkNBTDirectly(regionCache.file, relativeX, relativeZ);
                            if (chunkTag != null) {
                                LOGGER.debug("Found valid 1.18+ format chunk at world coords [{}, {}] in region {} after scanning {}",
                                    worldX, worldZ, regionFile.getName(), totalScanned);
                                // Return the relative coordinates for consistency with existing API
                                return new int[]{relativeX, relativeZ};
                            }
                        }
                        // Chunk doesn't exist, continue scanning
                        LOGGER.trace("World chunk [{}, {}] doesn't exist: {}", worldX, worldZ, e.getMessage());
                    }
                }
            }

            LOGGER.warn("No valid chunks found in region {} after scanning {} positions with both coordinate systems",
                regionFile.getName(), totalScanned);
            return null;
        } catch (Exception e) {
            LOGGER.error("Error while scanning region file {}: {}", regionFile.getName(), e.getMessage(), e);
            throw new IOException("Failed to scan region file: " + regionFile.getName(), e);
        }
    }

    /**
     * Extract biome data from chunk NBT tag, handling version differences.
     * @param chunkTag The chunk's NBT data
     * @return Array of biome identifiers for 16x16 surface positions
     */    public static String[] extractBiomesFromChunkTag(NBTCompound chunkTag) {
        // Debug logging to understand chunk structure
        LOGGER.debug("Chunk NBT keys: {}", chunkTag.getKeys());        // Try 1.18+ format first - sections is a ListTag, not a CompoundTag
        if (chunkTag.containsKey("sections")) {
            LOGGER.debug("Found 1.18+ format with sections");
            try {
                // In 1.18+, sections is a ListTag containing section compounds
                @SuppressWarnings("unchecked")
                org.jglrxavpok.hephaistos.nbt.NBTList<org.jglrxavpok.hephaistos.nbt.NBT> sectionsTag = 
                    (org.jglrxavpok.hephaistos.nbt.NBTList<org.jglrxavpok.hephaistos.nbt.NBT>) chunkTag.get("sections");
                if (sectionsTag != null && sectionsTag.getSize() > 0) {
                    return extractBiomes1_18Plus(sectionsTag);
                } else {
                    // sections exists but is empty or null
                }
            } catch (ClassCastException e) {
                // Try as compound (alternative format)
                NBTCompound sectionsCompound = chunkTag.getCompound("sections");
                if (sectionsCompound != null) {
                    return extractBiomes1_18PlusCompound(sectionsCompound);
                }
            }
        }

        // Try pre-1.18 format (Level tag with 2D biomes)
        if (chunkTag.containsKey("Level")) {
            LOGGER.debug("Found pre-1.18 format with Level tag");
            return extractBiomesPre1_18(chunkTag.getCompound("Level"));
        }

        LOGGER.debug("Unable to extract biomes - no recognized format");
        return null; // Unable to extract biomes
    }/**
     * Extract biomes from 1.18+ format with ListTag sections.
     * @param sectionsTag The sections list tag containing section compounds
     * @return Array of surface biome identifiers
     */
    private static String[] extractBiomes1_18Plus(org.jglrxavpok.hephaistos.nbt.NBTList<org.jglrxavpok.hephaistos.nbt.NBT> sectionsTag) {
        // For now, return a placeholder indicating 1.18+ format detected
        // TODO: Implement proper biome extraction from section palette data
        String[] biomes = new String[256]; // 16x16 positions
        for (int i = 0; i < 256; i++) {
            biomes[i] = "minecraft:plains"; // Default placeholder
        }
        return biomes;
    }
      /**
     * Extract biomes from 1.18+ format with CompoundTag sections (alternative format).
     * @param sectionsTag The sections compound tag
     * @return Array of surface biome identifiers
     */
    private static String[] extractBiomes1_18PlusCompound(NBTCompound sectionsTag) {
        // For now, return a placeholder indicating 1.18+ format detected
        // TODO: Implement proper biome extraction from section palette data
        String[] biomes = new String[256]; // 16x16 positions
        for (int i = 0; i < 256; i++) {
            biomes[i] = "minecraft:plains"; // Default placeholder
        }
        return biomes;
    }

    /**
     * Extract biomes from pre-1.18 format (2D biome array in Level tag).
     * @param levelTag The Level compound tag
     * @return Array of surface biome identifiers
     */
    private static String[] extractBiomesPre1_18(NBTCompound levelTag) {
        // In pre-1.18, biomes were stored as a simple array in the Level tag
        if (levelTag.containsKey("Biomes")) {
            // TODO: Implement proper pre-1.18 biome extraction
            String[] biomes = new String[256]; // 16x16 positions
            for (int i = 0; i < 256; i++) {
                biomes[i] = "minecraft:plains"; // Default placeholder
            }
            return biomes;
        }
        
        return null;
    }

    /**
     * Extract heightmaps from multiple chunks in a region file efficiently.
     * Reuses the same RegionFile instance for all chunks in the region.
     * @param regionFile Region file to process
     * @param chunks Array of [chunkX, chunkZ] coordinates to extract
     * @return Array of heightmaps corresponding to input chunks (null entries for missing chunks)
     * @throws IOException if file cannot be read
     */
    public static int[][][] extractHeightmapsFromRegion(File regionFile, int[][] chunks) throws IOException {
        if (chunks == null || chunks.length == 0) {
            return new int[0][][];
        }

        startTiming();

        try {
            RegionFileCache regionCache = getOrCreateRegionFileCache(regionFile);
            int[][][] results = new int[chunks.length][][];

            for (int i = 0; i < chunks.length; i++) {
                if (chunks[i] == null || chunks[i].length != 2) {
                    results[i] = null;
                    continue;
                }

                int chunkX = chunks[i][0];
                int chunkZ = chunks[i][1];

                if (chunkX < 0 || chunkX >= 32 || chunkZ < 0 || chunkZ >= 32) {
                    results[i] = null;
                    continue;
                }

                try {
                    ChunkColumn chunk = regionCache.getRegionFile().getChunk(chunkX, chunkZ);
                    if (chunk != null) {
                        results[i] = extractHeightmapFromChunkColumn(chunk);
                    } else {
                        results[i] = null;
                    }
                } catch (AnvilException e) {
                    // Chunk doesn't exist - normal situation
                    results[i] = null;
                }
            }

            return results;
        } finally {
            endTiming("extractHeightmapsFromRegion[" + chunks.length + " chunks]");
        }
    }

    /**
     * Helper method to extract heightmap from an already-loaded ChunkColumn.
     * @param chunk The loaded chunk
     * @return 16x16 heightmap or null if extraction fails
     */
    private static int[][] extractHeightmapFromChunkColumn(ChunkColumn chunk) {
        try {
            NBTCompound chunkTag = chunk.toNBT();
            if (chunkTag == null) {
                return null;
            }

            // Navigate to heightmaps section
            NBTCompound heightmapsTag = null;
            if (chunkTag.containsKey("Heightmaps")) {
                heightmapsTag = chunkTag.getCompound("Heightmaps");
            } else if (chunkTag.containsKey("Level")) {
                NBTCompound levelTag = chunkTag.getCompound("Level");
                if (levelTag != null && levelTag.containsKey("Heightmaps")) {
                    heightmapsTag = levelTag.getCompound("Heightmaps");
                }
            }

            if (heightmapsTag == null || !heightmapsTag.containsKey("MOTION_BLOCKING")) {
                return null;
            }            NBTLongArray motionBlockingTag = (NBTLongArray) heightmapsTag.get("MOTION_BLOCKING");
            if (motionBlockingTag == null) {
                return null;
            }            // Convert ImmutableLongArray to regular long array
            ImmutableLongArray immutableArray = motionBlockingTag.getValue();
            long[] heightData = immutableArray.copyArray();
            return decodeHeightmapFromLongArray(Arrays.copyOf(heightData, heightData.length));
        } catch (Exception e) {
            return null;
        }
    }    /**
     * Get performance statistics for the last operation.
     * Only available when profiling is enabled.
     * @return Performance summary or null if profiling disabled
     */
    public static String getPerformanceStats() {
        if (!profilingEnabled) {
            return "Profiling not enabled. Call setProfilingEnabled(true) first.";
        }

        return "Cache stats: " + regionFileCache.size() + " region files cached, " +
               regionCoordCache.size() + " coordinate entries cached";
    }

    /**
     * Raw NBT reader for 1.18+ chunk format when Hephaistos fails.
     * This method reads chunk data directly from the region file and parses it as NBT
     * without using Hephaistos ChunkColumn wrapper.
     * 
     * @param regionFile Open RandomAccessFile for the region
     * @param chunkX Local chunk X coordinate (0-31)
     * @param chunkZ Local chunk Z coordinate (0-31)
     * @return NBTCompound containing chunk data, or null if chunk doesn't exist or parsing fails
     */    private static NBTCompound readChunkNBTDirectly(RandomAccessFile regionFile, int chunkX, int chunkZ) {
        try {
            // Calculate chunk index in region file header
            int chunkIndex = (chunkZ % 32) * 32 + (chunkX % 32);
            System.out.printf("Reading chunk [%d, %d] at index %d from region file%n", chunkX, chunkZ, chunkIndex);
            
            // Read chunk location from header (4 bytes per chunk, first 4KB)
            regionFile.seek(chunkIndex * 4);
            int locationData = regionFile.readInt();
            
            System.out.printf("Location data for chunk [%d, %d]: 0x%s%n", chunkX, chunkZ, Integer.toHexString(locationData));
            
            if (locationData == 0) {
                // Chunk doesn't exist
                System.out.printf("Chunk [%d, %d] doesn't exist (location data is 0)%n", chunkX, chunkZ);
                return null;
            }
              // Extract offset and size from location data
            int offset = (locationData >>> 8) * 4096; // Offset in 4KB sectors
            int sectorCount = locationData & 0xFF;
            
            LOGGER.debug("Chunk [{}, {}] offset: {}, sectors: {}", chunkX, chunkZ, offset, sectorCount);
            
            if (offset == 0 || sectorCount == 0) {
                LOGGER.debug("Chunk [{}, {}] has invalid offset ({}) or sector count ({})", 
                    chunkX, chunkZ, offset, sectorCount);
                return null;
            }
            
            // Seek to chunk data
            regionFile.seek(offset);
            
            // Read chunk header (length + compression type)
            int chunkLength = regionFile.readInt();
            int compressionType = regionFile.readByte();
            
            LOGGER.debug("Chunk [{}, {}] length: {}, compression type: {}", 
                chunkX, chunkZ, chunkLength, compressionType);
            
            if (chunkLength <= 0 || chunkLength > 1024 * 1024) { // Sanity check
                LOGGER.warn("Chunk [{}, {}] has invalid length: {}", chunkX, chunkZ, chunkLength);
                return null;
            }
              // Read compressed chunk data
            byte[] compressedData = new byte[chunkLength - 1]; // -1 for compression type byte
            regionFile.readFully(compressedData);
            
            LOGGER.debug("Chunk [{}, {}] read {} bytes of compressed data", chunkX, chunkZ, compressedData.length);
            
            // Decompress and parse NBT
            NBTCompound result = null;
            switch (compressionType) {
                case 1: // GZip
                    LOGGER.debug("Chunk [{}, {}] using GZip compression", chunkX, chunkZ);
                    result = parseCompressedNBT(compressedData, true);
                    break;
                case 2: // Zlib
                    LOGGER.debug("Chunk [{}, {}] using Zlib compression", chunkX, chunkZ);
                    result = parseCompressedNBT(compressedData, false);
                    break;
                default:
                    LOGGER.warn("Unknown compression type {} for chunk [{}, {}]", compressionType, chunkX, chunkZ);
                    return null;
            }
            
            if (result != null) {
                LOGGER.debug("Successfully parsed NBT for chunk [{}, {}]", chunkX, chunkZ);
            } else {
                LOGGER.debug("Failed to parse NBT for chunk [{}, {}]", chunkX, chunkZ);
            }
            
            return result;
            
        } catch (Exception e) {
            LOGGER.debug("Failed to read chunk [{}, {}] directly: {}", chunkX, chunkZ, e.getMessage());
            return null;
        }
    }    /**
     * Parse compressed NBT data using Hephaistos NBT parser.
     * 
     * @param compressedData The compressed chunk data
     * @param isGzip True for GZip compression, false for Zlib
     * @return Parsed NBTCompound or null if parsing fails
     */    private static NBTCompound parseCompressedNBT(byte[] compressedData, boolean isGzip) {
        LOGGER.debug("Parsing {} bytes of {} compressed NBT data", 
            compressedData.length, isGzip ? "GZip" : "Zlib");
            
        try (java.io.ByteArrayInputStream byteStream = new java.io.ByteArrayInputStream(compressedData)) {
            
            try (java.io.InputStream inputStream = isGzip ? 
                new java.util.zip.GZIPInputStream(byteStream) : 
                new java.util.zip.InflaterInputStream(byteStream)) {
                
                LOGGER.debug("Successfully created {} decompression stream", isGzip ? "GZip" : "Zlib");
                
                // Use Hephaistos NBT reader directly
                try (org.jglrxavpok.hephaistos.nbt.NBTReader reader = new org.jglrxavpok.hephaistos.nbt.NBTReader(inputStream)) {
                    org.jglrxavpok.hephaistos.nbt.NBT rootNBT = reader.read();
                    
                    LOGGER.debug("Successfully read NBT, type: {}", rootNBT.getClass().getSimpleName());
                    
                    if (rootNBT instanceof NBTCompound) {
                        NBTCompound compound = (NBTCompound) rootNBT;
                        LOGGER.debug("NBT compound has {} keys: {}", 
                            compound.getSize(), compound.getKeys());
                        return compound;
                    } else {
                        LOGGER.debug("Root NBT is not a compound tag: {}", rootNBT.getClass().getSimpleName());
                        return null;
                    }
                }
            }
            
        } catch (Exception e) {
            LOGGER.debug("Failed to parse compressed NBT: {} - {}", e.getClass().getSimpleName(), e.getMessage());
            return null;
        }
    }
}
