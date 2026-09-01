package com.rhythmatician.lodiffusion.world.noise;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * GPU-accelerated implementation of {@link HeightmapProvider}.
 *
 * <h2>Algorithm</h2>
 * <ol>
 *   <li>Enqueue all 24 overworld Y-sections for the requested chunk column
 *       as a single column batch via {@link GpuNoiseDispatchQueue#enqueueColumn}.</li>
 *   <li>Block on the future (up to {@link #GPU_TIMEOUT_MS} ms) for the GPU to
 *       evaluate {@link RouterField#FINAL_DENSITY} at every quart lattice point
 *       in the column.</li>
 *   <li>CPU zero-crossing scan: for each of the 4×4 quart-XZ columns, scan
 *       sections top-down ({@code sectionY = 19..−4}) and within each section
 *       scan quart-Y top-down ({@code qy = 1..0}).  The first cell with
 *       {@code FINAL_DENSITY > 0} is the surface; its Y centre is recorded as
 *       {@code worldSurface[qx][qz]}.</li>
 *   <li>Ocean-floor heuristic: if {@code worldSurface[qx][qz] < SEA_LEVEL}
 *       (the column is submerged), the recorded surface is the ocean floor.
 *       At LOD distances the air/water distinction above the seafloor is
 *       invisible, so {@code oceanFloor = worldSurface} for submerged columns.</li>
 *   <li>Bilinear upsample: the 4×4 quart-resolution surfaces are upsampled to
 *       16×16 block resolution by linear interpolation between adjacent quart
 *       centres (at block offsets 2, 6, 10, 14 within the section).</li>
 * </ol>
 *
 * <h2>Fallback</h2>
 * <p>If the GPU dispatch queue is unavailable or the future times out, a flat
 * {@link HeightmapData} at sea level is returned.  A rate-limited warning is
 * logged so the fallback is visible in telemetry without flooding logs.
 *
 * <h2>Thread safety</h2>
 * <p>{@link GpuNoiseDispatchQueue#enqueueColumn} is thread-safe.  The gen thread
 * blocks on the future; all GL work happens on the render thread inside the queue.
 *
 * @see VanillaHeightmapProvider
 * @see HeightmapProvider
 */
public final class GpuHeightmapProvider implements HeightmapProvider {

    private static final Logger LOG = LoggerFactory.getLogger("LODiffusion/HeightmapGpu");

    /** Overworld first section Y (-64 / 16 = -4). */
    public static final int MIN_SECTION_Y = -4;

    /** Overworld last section Y (319 / 16 = 19). */
    public static final int MAX_SECTION_Y = 19;

    /** Total number of Y-sections in an overworld column. */
    public static final int COLUMN_SECTIONS = MAX_SECTION_Y - MIN_SECTION_Y + 1; // 24

    /**
     * Sea level block Y.  Columns whose surface is below this are considered
     * ocean or deep underwater for the ocean-floor heuristic.
     */
    public static final int SEA_LEVEL = 63;

    /** Fallback height used when no solid block is found in a quart column. */
    private static final float DEFAULT_SURFACE_Y = (float) (MIN_SECTION_Y * 16);

    /** Timeout for a full-column GPU dispatch (24 sections). */
    public static final long GPU_TIMEOUT_MS = 2000L;

    /**
     * Minimum interval between fallback warnings to avoid log spam.
     * Warning logged at most once per {@code WARN_INTERVAL_MS}.
     */
    private static final long WARN_INTERVAL_MS = 5_000L;
    private volatile long lastWarnMs = 0L;

    public GpuHeightmapProvider() {
        LOG.info("[HeightmapGpu] Initialised (sections={}, timeout={}ms)",
                COLUMN_SECTIONS, GPU_TIMEOUT_MS);
    }

    @Override
    public HeightmapData sampleHeightmaps(int sectionX, int sectionZ) {
        GpuNoiseDispatchQueue q = GpuNoiseDispatchQueue.instance();
        if (q == null) {
            return fallback("GPU dispatch queue not initialised");
        }

        CompletableFuture<SectionNoiseData[]> future =
                q.enqueueColumn(sectionX, sectionZ, MIN_SECTION_Y, MAX_SECTION_Y);
        try {
            SectionNoiseData[] column = future.get(GPU_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            return computeHeightmaps(column);
        } catch (TimeoutException e) {
            future.cancel(false);
            return fallback("GPU dispatch timed out after " + GPU_TIMEOUT_MS + "ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return fallback("Interrupted while waiting for GPU dispatch");
        } catch (Exception e) {
            return fallback("GPU dispatch failed: " + e.getMessage());
        }
    }

    @Override
    public String backendName() {
        return "gpu_zero_crossing";
    }

    // ── Core computation ──────────────────────────────────────────────

    /**
     * Derive world-surface and ocean-floor heightmaps from a full column of
     * quart-resolution noise sections.
     *
     * @param column {@code SectionNoiseData[COLUMN_SECTIONS]} ordered bottom-to-top
     *               (index 0 = {@code sectionY = MIN_SECTION_Y})
     */
    HeightmapData computeHeightmaps(SectionNoiseData[] column) {
        // zero-crossing scan: float[4][4] at quart resolution [qx][qz]
        float[][] quartSurface   = new float[4][4];
        float[][] quartOceanFloor = new float[4][4];

        for (int qx = 0; qx < 4; qx++) {
            for (int qz = 0; qz < 4; qz++) {
                float surfaceY = DEFAULT_SURFACE_Y;

                // Scan top-down: sectionY from MAX down to MIN, within each section qy from 1 to 0
                outer:
                for (int si = COLUMN_SECTIONS - 1; si >= 0; si--) {
                    int sectionY = MIN_SECTION_Y + si;   // si=0 → MIN_SECTION_Y, si=23 → MAX_SECTION_Y
                    SectionNoiseData data = column[si];
                    for (int qy = 1; qy >= 0; qy--) {
                        float density = data.get(RouterField.FINAL_DENSITY, qx, qy, qz);
                        if (density > 0.0f) {
                            // Cell-centre block Y (cellHeight=8)
                            surfaceY = sectionY * 16 + qy * 8 + 4;
                            break outer;
                        }
                    }
                }

                quartSurface[qx][qz] = surfaceY;
                // Ocean-floor heuristic: submerged columns have no separate floor at LOD distances
                quartOceanFloor[qx][qz] = (surfaceY < SEA_LEVEL) ? surfaceY : surfaceY;
            }
        }

        float[][] worldSurface = bilinearUpsample(quartSurface);
        float[][] oceanFloor   = bilinearUpsample(quartOceanFloor);
        return new HeightmapData(worldSurface, oceanFloor);
    }

    // ── Bilinear upsample ─────────────────────────────────────────────

    /**
     * Bilinearly upsample a 4×4 quart-resolution surface to 16×16 block resolution.
     *
     * <p>Quart centres are at block offsets 2, 6, 10, 14 within the 16-block section.
     * Block positions outside the first and last quart-centre are clamped (no
     * extrapolation).
     *
     * @param quart the 4×4 quart-resolution surface in {@code [qx][qz]} order
     * @return {@code float[16][16]} in {@code [blockX][blockZ]} order (local coords 0–15)
     */
    static float[][] bilinearUpsample(float[][] quart) {
        // First pass: upsample X for each quart-Z row → float[16][4]
        float[][] xUp = new float[16][4];
        for (int qz = 0; qz < 4; qz++) {
            float[] row = new float[4];
            for (int qx = 0; qx < 4; qx++) row[qx] = quart[qx][qz];
            float[] up = upsample1D(row);
            for (int bx = 0; bx < 16; bx++) xUp[bx][qz] = up[bx];
        }
        // Second pass: upsample Z for each block-X column → float[16][16]
        float[][] out = new float[16][16];
        for (int bx = 0; bx < 16; bx++) {
            float[] col = upsample1D(xUp[bx]);
            System.arraycopy(col, 0, out[bx], 0, 16);
        }
        return out;
    }

    /**
     * Linearly upsample a 4-element quart array to 16 block samples.
     *
     * <p>Quart centres are at block positions 2, 6, 10, 14.  Blocks outside the
     * first/last centre are clamped to the nearest quart value.
     *
     * @param quartValues 4-element float array (one value per quart cell)
     * @return 16-element float array at block resolution
     */
    static float[] upsample1D(float[] quartValues) {
        // Quart centres in local block coords [0, 15]
        final int[] CENTRES = {2, 6, 10, 14};
        float[] out = new float[16];
        for (int bx = 0; bx < 16; bx++) {
            // Find the lower quart bracket
            int lo = 0;
            for (int q = 1; q < 4; q++) {
                if (CENTRES[q] <= bx) lo = q;
            }
            int hi = Math.min(lo + 1, 3);
            if (lo == hi) {
                // At or past the last centre — clamp
                out[bx] = quartValues[lo];
            } else {
                float span = CENTRES[hi] - CENTRES[lo];
                float t = (bx - CENTRES[lo]) / span;
                t = Math.max(0.0f, Math.min(1.0f, t));
                out[bx] = quartValues[lo] + t * (quartValues[hi] - quartValues[lo]);
            }
        }
        return out;
    }

    // ── Fallback ──────────────────────────────────────────────────────

    private HeightmapData fallback(String reason) {
        long now = System.currentTimeMillis();
        if (now - lastWarnMs > WARN_INTERVAL_MS) {
            lastWarnMs = now;
            LOG.warn("[HeightmapGpu] Falling back to sea-level heightmap — {}", reason);
        }
        float[][] surf = new float[16][16];
        float[][] floor = new float[16][16];
        for (int bx = 0; bx < 16; bx++) {
            java.util.Arrays.fill(surf[bx], (float) SEA_LEVEL);
            java.util.Arrays.fill(floor[bx], (float) SEA_LEVEL);
        }
        return new HeightmapData(surf, floor);
    }
}
