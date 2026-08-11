package com.rhythmatician.lodiffusion.onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import ai.djl.ndarray.NDArray;
import ai.djl.ndarray.NDList;
import ai.djl.ndarray.NDManager;
import ai.djl.ndarray.types.Shape;
import ai.djl.repository.zoo.Criteria;
import ai.djl.repository.zoo.ZooModel;
import ai.djl.translate.NoopTranslator;
import ai.djl.translate.TranslateException;

/**
 * Loads and runs the five per-level Voxy ONNX models ({@code voxy_l0.onnx}
 * through {@code voxy_l4.onnx}) for hierarchical LOD generation.
 *
 * <h3>Contract: {@code lodiffusion.v6.voxy}</h3>
 *
 * <p>Each model is an independent ONNX graph with level-specific inputs:
 *
 * <pre>
 *   L4 (root, no parent):
 *     climate_2d  float32[N, 6, 8, 8]   — 6 feature-selected climate channels (2D)
 *     biome_2d    int64[N, 8, 8]         — biome IDs at subsampled XZ
 *     y_position  int64[N]               — section Y index
 *     →  block_logits[N, 513, 24, 32, 32]  occ_logits[N, 8]
 *
 *   L3 (2D climate + parent):
 *     climate_2d  float32[N, 6, 8, 8]
 *     biome_2d    int64[N, 8, 8]
 *     y_position  int64[N]
 *     parent_blocks int64[N, 32, 32, 32]
 *     →  block_logits[N, 513, 32, 32, 32]  occ_logits[N, 8]
 *
 *   L2 (2D climate + parent, 7ch):
 *     climate_2d  float32[N, 7, 8, 8]
 *     biome_2d    int64[N, 8, 8]
 *     y_position  int64[N]
 *     parent_blocks int64[N, 32, 32, 32]
 *     →  block_logits[N, 513, 32, 32, 32]  occ_logits[N, 8]
 *
 *   L1 (3D noise + parent):
 *     noise_3d    float32[N, 15, 16, 8, 16]
 *     biome_3d    int64[N, 16, 8, 16]
 *     y_position  int64[N]
 *     parent_blocks int64[N, 32, 32, 32]
 *     →  block_logits[N, 513, 32, 32, 32]  occ_logits[N, 8]
 *
 *   L0 (3D noise + parent, finest):
 *     noise_3d    float32[N, 15, 8, 4, 8]
 *     biome_3d    int64[N, 8, 4, 8]
 *     y_position  int64[N]
 *     parent_blocks int64[N, 32, 32, 32]
 *     →  block_logits[N, 513, 32, 32, 32]
 * </pre>
 *
 * <p>Generation proceeds top-down: L4 → L3 → L2 → L1 → L0.  Each level's
 * {@code block_logits} are argmax'd to produce a {@code int[32][32][32]} (or
 * {@code int[24][32][32]} for L4) block grid.  This grid becomes the
 * {@code parent_blocks} input for the next finer level.  Occupancy logits
 * (L1-L4) enable early pruning: if {@code sigmoid(occ_logits[octant]) < threshold},
 * the child octant is skipped entirely.
 *
 * @see com.rhythmatician.lodiffusion.world.noise.NoiseRouterSampler
 */
public final class VoxyModelRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(VoxyModelRunner.class);

    /** Number of LOD levels (L0 through L4). */
    static final int NUM_LEVELS = 5;

    /** Occupancy expansion threshold: sigmoid(logit) &gt; this → expand octant. */
    private static final float DEFAULT_OCC_THRESHOLD = 0.5f;

    /** Edge of each model's output cube, in voxels. */
    static final int OUTPUT_RES = 32;

    // ── Per-level model state ─────────────────────────────────────────

    private final NDManager manager;

    /** ONNX models for levels 0–4 (null if that level wasn't loaded). */
    private final ZooModel<NDList, NDList>[] models;

    /** Parsed sidecar configs for levels 0–4 (null if not loaded). */
    private final ModelConfig[] configs;

    /** Block vocabulary (shared across levels). */
    private final BlockVocabulary vocabulary;

    /** Number of block classes (513 for current vocab). */
    private final int numClasses;

    /** Input tensor names per level, in ONNX graph order. */
    private final List<String>[] inputOrders;

    /** Per-level noise channel indices (RouterField ordinals). */
    private final int[][] noiseChannels;

    /** Whether each level uses 3D noise (true for L0, L1) or 2D climate. */
    private final boolean[] is3dNoise;

    /** Whether each level has a parent_blocks input. */
    private final boolean[] hasParent;

    /** Whether each level produces occupancy logits. */
    private final boolean[] hasOccupancy;

    /** Guards one-shot diagnostic log. */
    private final AtomicBoolean debugOnce = new AtomicBoolean(false);

    @SuppressWarnings("unchecked")
    private VoxyModelRunner(NDManager manager,
                            ZooModel<NDList, NDList>[] models,
                            ModelConfig[] configs,
                            BlockVocabulary vocabulary,
                            int numClasses,
                            List<String>[] inputOrders,
                            int[][] noiseChannels,
                            boolean[] is3dNoise,
                            boolean[] hasParent,
                            boolean[] hasOccupancy) {
        this.manager = manager;
        this.models = models;
        this.configs = configs;
        this.vocabulary = vocabulary;
        this.numClasses = numClasses;
        this.inputOrders = inputOrders;
        this.noiseChannels = noiseChannels;
        this.is3dNoise = is3dNoise;
        this.hasParent = hasParent;
        this.hasOccupancy = hasOccupancy;
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Load all available Voxy level models from {@code modelDir}.
     *
     * <p>Looks for {@code voxy_l0.onnx} through {@code voxy_l4.onnx} and their
     * corresponding {@code voxy_l{N}_config.json} sidecars.  At least one level
     * must be present.
     *
     * @param modelDir directory containing model files
     * @return loaded runner, or {@code null} if no voxy models are found
     * @throws IOException if a model file is present but cannot be loaded
     */
    @SuppressWarnings("unchecked")
    public static VoxyModelRunner tryLoad(Path modelDir) throws IOException {
        // Check if any voxy models exist
        boolean anyFound = false;
        for (int level = 0; level < NUM_LEVELS; level++) {
            if (Files.exists(modelDir.resolve("voxy_l" + level + ".onnx"))) {
                anyFound = true;
                break;
            }
        }
        if (!anyFound) {
            LOGGER.info("[VoxyModel] No voxy_l*.onnx files found in {} — unavailable", modelDir);
            return null;
        }

        InferenceDeviceSelector.Provider provider = InferenceDeviceSelector.selectProvider();
        LOGGER.info("[VoxyModel] Loading models from {} (provider={})", modelDir, provider);

        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(VoxyModelRunner.class.getClassLoader());
        try {
            NDManager manager = NDManager.newBaseManager();
            try {
                ZooModel<NDList, NDList>[] models = new ZooModel[NUM_LEVELS];
                ModelConfig[] configs = new ModelConfig[NUM_LEVELS];
                List<String>[] inputOrders = new List[NUM_LEVELS];
                int[][] noiseChannels = new int[NUM_LEVELS][];
                boolean[] is3dNoise = new boolean[NUM_LEVELS];
                boolean[] hasParent = new boolean[NUM_LEVELS];
                boolean[] hasOccupancy = new boolean[NUM_LEVELS];

                BlockVocabulary vocab = null;
                int numClasses = 513;

                for (int level = 0; level < NUM_LEVELS; level++) {
                    String stem = "voxy_l" + level;
                    Path onnxPath = modelDir.resolve(stem + ".onnx");
                    Path configPath = modelDir.resolve(stem + "_config.json");

                    if (!Files.exists(onnxPath)) {
                        LOGGER.info("[VoxyModel] L{} not found — skipping", level);
                        continue;
                    }

                    models[level] = buildAndLoad(modelDir, stem, provider);
                    LOGGER.info("[VoxyModel] Loaded L{} from {}", level, onnxPath);

                    if (Files.exists(configPath)) {
                        ModelConfig cfg = ConfigLoader.load(configPath);
                        configs[level] = cfg;

                        // Extract noise channel indices
                        noiseChannels[level] = extractNoiseChannels(cfg);

                        // Determine noise encoding type
                        is3dNoise[level] = cfg.hasInput("noise_3d");
                        hasParent[level] = cfg.hasInput("parent_blocks");
                        hasOccupancy[level] = cfg.outputs() != null
                                && cfg.outputs().containsKey("occ_logits");

                        // Resolve input order from config
                        inputOrders[level] = resolveInputOrder(cfg);

                        // Build vocabulary from first config that has block_mapping
                        if (vocab == null && cfg.blockMapping() != null
                                && !cfg.blockMapping().isEmpty()) {
                            try {
                                vocab = BlockVocabulary.fromConfig(cfg);
                                int nc = cfg.effectiveBlockVocabSize();
                                if (nc > 0) numClasses = nc;
                            } catch (ExceptionInInitializerError
                                    | NoClassDefFoundError e) {
                                LOGGER.warn("[VoxyModel] Block registry unavailable "
                                        + "(test env?) — vocab skipped: {}",
                                        e.getClass().getSimpleName());
                            }
                        }

                        LOGGER.info("[VoxyModel] L{}: {} noise, {}parent, {}occ, "
                                + "channels={}", level,
                                is3dNoise[level] ? "3D" : "2D",
                                hasParent[level] ? "" : "no ",
                                hasOccupancy[level] ? "" : "no ",
                                Arrays.toString(noiseChannels[level]));
                    } else {
                        LOGGER.warn("[VoxyModel] No config sidecar for L{} — "
                                + "using defaults", level);
                        inputOrders[level] = List.of();
                    }
                }

                return new VoxyModelRunner(manager, models, configs, vocab,
                        numClasses, inputOrders, noiseChannels, is3dNoise,
                        hasParent, hasOccupancy);

            } catch (Exception e) {
                manager.close();
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("Failed to load Voxy models from " + modelDir, e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }
    }

    // ------------------------------------------------------------------
    // DJL session helpers
    // ------------------------------------------------------------------

    @SuppressWarnings("null")
    private static ZooModel<NDList, NDList> buildAndLoad(
            Path dir, String stem, InferenceDeviceSelector.Provider provider)
            throws Exception {
        if (!provider.djlOptionValue().isEmpty()) {
            ZooModel<NDList, NDList> attempted = null;
            try {
                attempted = buildCriteria(dir, stem, provider.djlOptionValue())
                        .build().loadModel();
                LOGGER.info("[VoxyModel] {} loaded with provider {}", stem, provider);
                return attempted;
            } catch (Exception ex) {
                if (attempted != null) {
                    try { attempted.close(); } catch (Exception ignore) {}
                }
                LOGGER.warn("[VoxyModel] Provider {} unavailable for {} ({}); "
                        + "falling back to CPU", provider, stem, ex.getMessage());
            }
        }
        ZooModel<NDList, NDList> model =
                buildCriteria(dir, stem, null).build().loadModel();
        LOGGER.info("[VoxyModel] {} loaded (CPU)", stem);
        return model;
    }

    private static Criteria.Builder<NDList, NDList> buildCriteria(
            Path dir, String stem, String ortDevice) {
        Criteria.Builder<NDList, NDList> builder = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(dir)
                .optModelName(stem)
                .optEngine("OnnxRuntime")
                .optTranslator(new NoopTranslator())
                .optOption("interOpNumThreads", "1")
                .optOption("intraOpNumThreads", "4");
        if (ortDevice != null && !ortDevice.isEmpty()) {
            builder.optOption("ortDevice", ortDevice);
        }
        return builder;
    }

    // ------------------------------------------------------------------
    // Config helpers
    // ------------------------------------------------------------------

    private static int[] extractNoiseChannels(ModelConfig cfg) {
        if (cfg.assumptions() == null) return null;
        // The sidecar stores noise_channels as a JSON array of ints under the
        // top-level key (not under assumptions).  We access it via the raw map.
        // ConfigLoader parses it into the ModelConfig assumptions.
        // For now, return null — the channel selection is done Python-side
        // during 2D climate preparation.
        return null;
    }

    private static List<String> resolveInputOrder(ModelConfig cfg) {
        List<String> order = new ArrayList<>();
        if (cfg.inputs() != null) {
            order.addAll(cfg.inputs().keySet());
        }
        return order;
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /** Whether a specific level is loaded and available. */
    public boolean hasLevel(int level) {
        return level >= 0 && level < NUM_LEVELS && models[level] != null;
    }

    /** Block vocabulary (may be null in test environments). */
    public BlockVocabulary vocabulary() { return vocabulary; }

    /** Number of block classes the models were trained with. */
    public int numClasses() { return numClasses; }

    // ------------------------------------------------------------------
    // L4 Inference (root — no parent)
    // ------------------------------------------------------------------

    /**
     * Run L4 (root) inference: 2D climate → block grid.
     *
     * @param climate2d  float[6 * 8 * 8] feature-selected 2D climate data,
     *                   channel-outermost: [ch][z][x] flattened
     * @param biome2d    int[8 * 8] biome IDs at subsampled 8×8 XZ, flattened
     * @param yPosition  section Y index (0–23)
     * @return result containing block IDs [24][32][32] in [Y][Z][X] order
     *         and occupancy logits, or null if inference failed
     */
    public LevelResult runL4(float[] climate2d, long[] biome2d, int yPosition) {
        return runClimateLevel(4, climate2d, new long[]{1, 6, 8, 8},
                biome2d, new long[]{1, 8, 8}, yPosition, null);
    }

    // ------------------------------------------------------------------
    // L3 Inference (2D climate + parent)
    // ------------------------------------------------------------------

    /**
     * Run L3 inference: 2D climate + parent → block grid + occupancy.
     *
     * @param climate2d    float[6 * 8 * 8] feature-selected 2D climate
     * @param biome2d      int[8 * 8] biome IDs at 8×8 XZ
     * @param yPosition    section Y index
     * @param parentBlocks int[32 * 32 * 32] parent block IDs from L4, flattened
     * @return result or null
     */
    public LevelResult runL3(float[] climate2d, long[] biome2d,
                             int yPosition, long[] parentBlocks) {
        return runClimateLevel(3, climate2d, new long[]{1, 6, 8, 8},
                biome2d, new long[]{1, 8, 8}, yPosition, parentBlocks);
    }

    // ------------------------------------------------------------------
    // L2 Inference (2D climate + parent, 7 channels)
    // ------------------------------------------------------------------

    /**
     * Run L2 inference: 2D climate (7ch) + parent → block grid + occupancy.
     *
     * @param climate2d    float[7 * 8 * 8] climate data (6 climate + final_density)
     * @param biome2d      int[8 * 8] biome IDs at 8×8 XZ
     * @param yPosition    section Y index
     * @param parentBlocks int[32 * 32 * 32] parent block IDs from L3
     * @return result or null
     */
    public LevelResult runL2(float[] climate2d, long[] biome2d,
                             int yPosition, long[] parentBlocks) {
        return runClimateLevel(2, climate2d, new long[]{1, 7, 8, 8},
                biome2d, new long[]{1, 8, 8}, yPosition, parentBlocks);
    }

    // ------------------------------------------------------------------
    // L1 Inference (3D noise + parent)
    // ------------------------------------------------------------------

    /**
     * Run L1 inference: full 3D noise + parent → block grid + occupancy.
     *
     * @param noise3d      float[15 * 16 * 8 * 16] full 3D noise, channel-outermost
     * @param biome3d      int[16 * 8 * 16] biome IDs at quart resolution
     * @param yPosition    section Y index
     * @param parentBlocks int[32 * 32 * 32] parent block IDs from L2
     * @return result or null
     */
    public LevelResult runL1(float[] noise3d, long[] biome3d,
                             int yPosition, long[] parentBlocks) {
        return runNoiseLevel(1, noise3d, new long[]{1, 15, 16, 8, 16},
                biome3d, new long[]{1, 16, 8, 16}, yPosition, parentBlocks);
    }

    // ------------------------------------------------------------------
    // L0 Inference (3D noise + parent, finest)
    // ------------------------------------------------------------------

    /**
     * Run L0 inference: full 3D noise + parent → block grid (no occupancy).
     *
     * @param noise3d      float[15 * 8 * 4 * 8] full 3D noise
     * @param biome3d      int[8 * 4 * 8] biome IDs at quart resolution
     * @param yPosition    section Y index
     * @param parentBlocks int[32 * 32 * 32] parent block IDs from L1
     * @return result or null
     */
    public LevelResult runL0(float[] noise3d, long[] biome3d,
                             int yPosition, long[] parentBlocks) {
        return runNoiseLevel(0, noise3d, new long[]{1, 15, 8, 4, 8},
                biome3d, new long[]{1, 8, 4, 8}, yPosition, parentBlocks);
    }

    // ------------------------------------------------------------------
    // Generic inference methods
    // ------------------------------------------------------------------

    /**
     * Run a 2D-climate level (L2, L3, L4).
     */
    private LevelResult runClimateLevel(int level,
                                        float[] climate2d, long[] climateShape,
                                        long[] biome2d, long[] biomeShape,
                                        int yPosition,
                                        long[] parentBlocks) {
        if (!hasLevel(level)) return null;

        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try (NDManager sub = manager.newSubManager()) {
            NDList inputs = new NDList();

            // climate_2d: float32
            inputs.add(sub.create(climate2d, new Shape(climateShape)));

            // biome_2d: int64
            inputs.add(sub.create(biome2d, new Shape(biomeShape)));

            // y_position: int64
            inputs.add(sub.create(new long[]{yPosition}, new Shape(1)));

            // parent_blocks: int64 (only for L2, L3)
            if (hasParent[level] && parentBlocks != null) {
                inputs.add(sub.create(parentBlocks, new Shape(1, 32, 32, 32)));
            }

            return runAndDecode(level, inputs, sub);

        } catch (TranslateException e) {
            LOGGER.warn("[VoxyModel] L{} inference failed: {}", level, e.getMessage());
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }
    }

    /**
     * Run a 3D-noise level (L0, L1).
     */
    private LevelResult runNoiseLevel(int level,
                                      float[] noise3d, long[] noiseShape,
                                      long[] biome3d, long[] biomeShape,
                                      int yPosition,
                                      long[] parentBlocks) {
        if (!hasLevel(level)) return null;

        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(getClass().getClassLoader());
        try (NDManager sub = manager.newSubManager()) {
            NDList inputs = new NDList();

            // noise_3d: float32
            inputs.add(sub.create(noise3d, new Shape(noiseShape)));

            // biome_3d: int64
            inputs.add(sub.create(biome3d, new Shape(biomeShape)));

            // y_position: int64
            inputs.add(sub.create(new long[]{yPosition}, new Shape(1)));

            // parent_blocks: int64
            if (hasParent[level] && parentBlocks != null) {
                inputs.add(sub.create(parentBlocks, new Shape(1, 32, 32, 32)));
            }

            return runAndDecode(level, inputs, sub);

        } catch (TranslateException e) {
            LOGGER.warn("[VoxyModel] L{} inference failed: {}", level, e.getMessage());
            return null;
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }
    }

    /**
     * Run ONNX inference and decode block_logits → argmax block IDs.
     */
    private LevelResult runAndDecode(int level, NDList inputs, NDManager sub)
            throws TranslateException {
        long tPredictStart = System.currentTimeMillis();

        NDList outputs;
        try (var predictor = models[level].newPredictor()) {
            outputs = predictor.predict(inputs);
        }

        long predictMs = System.currentTimeMillis() - tPredictStart;

        if (debugOnce.compareAndSet(false, true)) {
            LOGGER.info("[VoxyModel] First predict — L{} completed in {}ms, "
                    + "{} outputs", level, predictMs, outputs.size());
            for (int i = 0; i < outputs.size(); i++) {
                LOGGER.info("[VoxyModel]   output[{}]: shape={}",
                        i, Arrays.toString(outputs.get(i).getShape().getShape()));
            }
        } else {
            LOGGER.debug("[VoxyModel] L{} predict: {}ms", level, predictMs);
        }

        // Decode block_logits: argmax along class dimension
        // block_logits shape: [1, V, Y, Z, X]
        NDArray blockLogits = outputs.get(0);  // first output is always block_logits
        long[] shape = blockLogits.getShape().getShape();
        int classes = (int) shape[1];
        int dimY = (int) shape[2];
        int dimZ = (int) shape[3];
        int dimX = (int) shape[4];

        long tDecodeStart = System.currentTimeMillis();
        int[] blockIds = decodeArgmaxFromLogits(blockLogits, classes, dimY, dimZ, dimX);

        long decodeMs = System.currentTimeMillis() - tDecodeStart;
        long totalMs = predictMs + decodeMs;
        if (debugOnce.get()) {
            LOGGER.info("[VoxyModel] L{} decode={}ms total={}ms", level, decodeMs, totalMs);
        } else {
            LOGGER.debug("[VoxyModel] L{} decode={}ms total={}ms", level, decodeMs, totalMs);
        }

        // Convert to [Y][Z][X] int array
        int[][][] blocks = new int[dimY][dimZ][dimX];
        int idx = 0;
        for (int y = 0; y < dimY; y++) {
            for (int z = 0; z < dimZ; z++) {
                for (int x = 0; x < dimX; x++) {
                    blocks[y][z][x] = blockIds[idx++];
                }
            }
        }

        // Decode occupancy logits if present
        float[] occLogits = null;
        if (hasOccupancy[level] && outputs.size() > 1) {
            NDArray occArray = outputs.get(1);
            occLogits = occArray.toFloatArray();
        }

        return new LevelResult(blocks, occLogits, dimY, dimZ, dimX);
    }

    /**
     * Decode class logits [1, V, Y, Z, X] to class IDs [Y*Z*X] using a manual
     * argmax pass. This avoids NDArray argMax overhead that can be very slow on
     * some ONNX Runtime CPU paths.
     */
    private int[] decodeArgmaxFromLogits(NDArray blockLogits,
                                         int classes,
                                         int dimY,
                                         int dimZ,
                                         int dimX) {
        float[] logits = blockLogits.toFloatArray();
        int voxelCount = dimY * dimZ * dimX;
        int[] out = new int[voxelCount];

        // Flattened layout for [1, V, Y, Z, X] with row-major storage:
        // index = c * voxelCount + v, where v iterates [Y][Z][X] with X fastest.
        for (int v = 0; v < voxelCount; v++) {
            int bestClass = 0;
            float best = logits[v];
            int classBase = voxelCount;

            for (int c = 1; c < classes; c++) {
                float score = logits[classBase + v];
                if (score > best) {
                    best = score;
                    bestClass = c;
                }
                classBase += voxelCount;
            }
            out[v] = bestClass;
        }

        return out;
    }

    // ------------------------------------------------------------------
    // Result record
    // ------------------------------------------------------------------

    /**
     * Result from a single level's inference.
     *
     * @param blocks      block IDs in [Y][Z][X] order
     * @param occLogits   raw occupancy logits for 8 child octants (null for L0)
     * @param dimY        Y dimension of blocks array
     * @param dimZ        Z dimension
     * @param dimX        X dimension
     */
    public record LevelResult(
        int[][][] blocks,
        float[] occLogits,
        int dimY,
        int dimZ,
        int dimX
    ) {
        /**
         * Flatten blocks to {@code long[32*32*32]} for use as parent_blocks
         * input to the next finer level.  Iterates in [Y][Z][X] order
         * matching the ONNX model's expectation.
         */
        public long[] flattenForParent() {
            long[] flat = new long[dimY * dimZ * dimX];
            int i = 0;
            for (int y = 0; y < dimY; y++) {
                for (int z = 0; z < dimZ; z++) {
                    for (int x = 0; x < dimX; x++) {
                        flat[i++] = blocks[y][z][x];
                    }
                }
            }
            return flat;
        }

        /**
         * Check if a specific octant should be expanded based on occupancy.
         *
         * @param octant   octant index (0–7)
         * @param threshold expansion threshold
         * @return true if the octant should be expanded (has non-trivial content)
         */
        public boolean shouldExpand(int octant, float threshold) {
            if (occLogits == null) return true;  // L0 has no occupancy — always expand
            float sigmoid = 1.0f / (1.0f + (float) Math.exp(-occLogits[octant]));
            return sigmoid > threshold;
        }
    }

    // ------------------------------------------------------------------
    // Lifecycle
    // ------------------------------------------------------------------

    @Override
    public void close() {
        for (int i = 0; i < NUM_LEVELS; i++) {
            if (models[i] != null) {
                try {
                    models[i].close();
                } catch (Exception e) {
                    LOGGER.debug("[VoxyModel] Error closing L{}: {}", i, e.getMessage());
                }
            }
        }
        manager.close();
        LOGGER.info("[VoxyModel] Closed all models");
    }
}
