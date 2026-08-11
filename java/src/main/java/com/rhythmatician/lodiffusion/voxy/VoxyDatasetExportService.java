package com.rhythmatician.lodiffusion.voxy;

import java.io.BufferedOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Service for exporting VoxelizedSection data to disk for training dataset generation.
 *
 * <p>This service:
 * <ul>
 *   <li>Listens for VoxelizedSectionSnapshot objects via VoxyProcessingAPI</li>
 *   <li>Exports them as binary files to a configurable output directory</li>
 *   <li>Tracks export statistics and performance metrics</li>
 *   <li>Can be started/stopped to control dataset collection</li>
 * </ul>
 *
 * <p><b>Usage:</b>
 * <pre>
 * VoxyDatasetExportService service = new VoxyDatasetExportService(
 *     Paths.get("dataset/"),
 *     VoxyDatasetExportService.Format.BINARY
 * );
 * service.start();
 * VoxyProcessingAPI.processChunk(100, 200);
 * // ... chunks are processed and exported to dataset/ directory
 * service.stop();
 * </pre>
 *
 * <p><b>Output Format:</b>
 * Each section is exported as a binary file: {@code section_cx_cy_cz.bin}
 * <pre>
 * File format:
 *   [4 bytes] magic number (0xDEADBEEF)
 *   [4 bytes] version (1)
 *   [4 bytes] cx (chunk X)
 *   [4 bytes] cy (chunk Y / section index)
 *   [4 bytes] cz (chunk Z)
 *   [4 bytes] world ID length
 *   [variable] world ID string (UTF-8)
 *   [8 bytes] capture timestamp
 *   [4 bytes] section array length
 *   [8*length bytes] section data (long array)
 * </pre>
 */
public class VoxyDatasetExportService {
    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyDatasetExportService.class);

    // Magic number for binary format validation
    private static final int MAGIC_NUMBER = 0xDEADBEEF;
    private static final int FILE_VERSION = 1;

    /** Export format options */
    public enum Format {
        /** Binary format with metadata header */
        BINARY,
        /** Parquet columnar format (future extension) */
        PARQUET,
    }

    private final Path outputDirectory;
    private final Format format;
    private volatile boolean isActive;

    // Statistics tracking
    private final AtomicLong sectionsExported = new AtomicLong(0);
    private final AtomicLong bytesExported = new AtomicLong(0);
    private final AtomicLong exportErrors = new AtomicLong(0);
    private final ConcurrentHashMap<String, AtomicLong> worldExportCounts = new ConcurrentHashMap<>();

    /**
     * Create an export service with the given output directory and format.
     *
     * @param outputDirectory Path where sections will be exported
     * @param format Export format (BINARY or PARQUET)
     */
    public VoxyDatasetExportService(Path outputDirectory, Format format) {
        this.outputDirectory = outputDirectory;
        this.format = format;
        this.isActive = false;
    }

    /**
     * Start the export service and register the listener with VoxyProcessingAPI.
     *
     * <p>Creates the output directory if it doesn't exist.
     *
     * @return true if startup succeeded, false otherwise
     */
    public boolean start() {
        if (isActive) {
            LOGGER.warn("Export service is already active");
            return false;
        }

        // Create output directory
        try {
            Files.createDirectories(outputDirectory);
            LOGGER.info("Created output directory: {}", outputDirectory.toAbsolutePath());
        } catch (Exception e) {
            LOGGER.error("Failed to create output directory", e);
            return false;
        }

        // Register listener
        try {
            VoxyProcessingAPI.registerSectionCaptureListener(this::exportSection);
            isActive = true;
            LOGGER.info("VoxyDatasetExportService started. Exporting to: {}", outputDirectory.toAbsolutePath());
            return true;
        } catch (Exception e) {
            LOGGER.error("Failed to start export service", e);
            return false;
        }
    }

    /**
     * Stop the export service and unregister the listener.
     */
    public void stop() {
        if (!isActive) {
            return;
        }

        VoxyProcessingAPI.unregisterSectionCaptureListener(this::exportSection);
        isActive = false;
        LOGGER.info("VoxyDatasetExportService stopped. Exported {} sections ({} bytes, {} errors)",
                sectionsExported.get(), bytesExported.get(), exportErrors.get());
    }

    /**
     * Export a single VoxelizedSectionSnapshot to disk.
     *
     * <p>This method is called by the mixin callback when a section is captured. It handles:
     * <ul>
     *   <li>Directory structure creation per-world</li>
     *   <li>File writing with metadata</li>
     *   <li>Error handling and recovery</li>
     * </ul>
     *
     * @param snapshot The section snapshot to export
     */
    private void exportSection(VoxelizedSectionSnapshot snapshot) {
        if (!isActive) {
            return;
        }

        try {
            switch (format) {
                case BINARY:
                    exportBinary(snapshot);
                    break;
                case PARQUET:
                    // TODO: Implement Parquet export
                    LOGGER.warn("Parquet format not yet implemented");
                    break;
                default:
                    LOGGER.warn("Unknown export format: {}", format);
                    return;
            }

            // Update statistics
            sectionsExported.incrementAndGet();
            bytesExported.addAndGet(snapshot.getSectionSizeBytes() + 100); // +100 for header
            worldExportCounts.computeIfAbsent(snapshot.worldId, k -> new AtomicLong(0))
                    .incrementAndGet();

        } catch (Exception e) {
            exportErrors.incrementAndGet();
            LOGGER.warn("Failed to export section ({}, {}, {})", snapshot.cx, snapshot.cy, snapshot.cz, e);
        }
    }

    /**
     * Export a section in binary format.
     *
     * @param snapshot The section snapshot to export
     * @throws Exception if export fails
     */
    private void exportBinary(VoxelizedSectionSnapshot snapshot) throws Exception {
        // Create world-specific subdirectory
        Path worldDir = outputDirectory.resolve(sanitizeWorldId(snapshot.worldId));
        Files.createDirectories(worldDir);

        // Create filename: section_cx_cy_cz.bin
        String filename = String.format("section_%d_%d_%d.bin", snapshot.cx, snapshot.cy, snapshot.cz);
        Path outputFile = worldDir.resolve(filename);

        // Write binary data
        try (DataOutputStream dos = new DataOutputStream(
                new BufferedOutputStream(new FileOutputStream(outputFile.toFile())))) {

            // Header
            dos.writeInt(MAGIC_NUMBER);
            dos.writeInt(FILE_VERSION);
            dos.writeInt(snapshot.cx);
            dos.writeInt(snapshot.cy);
            dos.writeInt(snapshot.cz);

            // World ID
            byte[] worldIdBytes = snapshot.worldId.getBytes(java.nio.charset.StandardCharsets.UTF_8);
            dos.writeInt(worldIdBytes.length);
            dos.write(worldIdBytes);

            // Timestamp
            dos.writeLong(snapshot.captureTimestamp);

            // Section data
            dos.writeInt(snapshot.section.length);
            for (long value : snapshot.section) {
                dos.writeLong(value);
            }

            dos.flush();
        }

        LOGGER.debug("Exported section ({}, {}, {}) to {}", snapshot.cx, snapshot.cy, snapshot.cz, outputFile);
    }

    /**
     * Sanitize a world ID for use as a directory name.
     *
     * @param worldId The world identifier
     * @return A filesystem-safe directory name
     */
    private static String sanitizeWorldId(String worldId) {
        // Replace unsafe characters with underscores
        return worldId.replaceAll("[^a-zA-Z0-9._-]", "_");
    }

    // ========== Statistics API ==========

    /**
     * Get the number of sections exported so far.
     *
     * @return Export count
     */
    public long getExportedSectionCount() {
        return sectionsExported.get();
    }

    /**
     * Get the total bytes exported so far.
     *
     * @return Byte count
     */
    public long getExportedByteCount() {
        return bytesExported.get();
    }

    /**
     * Get the number of export errors encountered.
     *
     * @return Error count
     */
    public long getErrorCount() {
        return exportErrors.get();
    }

    /**
     * Get export count for a specific world.
     *
     * @param worldId The world identifier
     * @return Count of sections from that world, or 0 if none
     */
    public long getExportCountForWorld(String worldId) {
        AtomicLong count = worldExportCounts.get(worldId);
        return count != null ? count.get() : 0;
    }

    /**
     * Check if the service is currently active.
     *
     * @return true if actively listening for sections
     */
    public boolean isActive() {
        return isActive;
    }

    /**
     * Get the output directory path.
     *
     * @return The configured output directory
     */
    public Path getOutputDirectory() {
        return outputDirectory;
    }

    /**
     * Get the export format.
     *
     * @return The configured format
     */
    public Format getFormat() {
        return format;
    }

    @Override
    public String toString() {
        return String.format(
                "VoxyDatasetExportService{outputDir=%s, format=%s, active=%s, sectionsExported=%d, "
                        + "bytesExported=%d, errors=%d}",
                outputDirectory, format, isActive, sectionsExported.get(), bytesExported.get(),
                exportErrors.get());
    }
}
