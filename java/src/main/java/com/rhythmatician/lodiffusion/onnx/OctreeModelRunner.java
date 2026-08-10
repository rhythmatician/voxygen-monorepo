package com.rhythmatician.lodiffusion.onnx;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

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

import com.rhythmatician.lodiffusion.voxy.OctreeColumnContext;
import com.rhythmatician.lodiffusion.voxy.OctreeTask;

/**
 * Loads and runs the three octree ONNX models for breadth-first LOD
 * generation.
 *
 * <h3>Contract: {@code lodiffusion.v5.octree}</h3>
 *
 * <p>Three models, each producing 32³ block logits:
 * <pre>
 *   octree_init.onnx   — L4 roots (no parent context)
 *     Inputs:  heightmap  float32[N,5,32,32]
 *              biome      int64[N,32,32]
 *              y_position int64[N]
 *     Outputs: block_logits float32[N,num_classes,32,32,32]
 *              occ_logits   float32[N,8]
 *
 *   octree_refine.onnx — L3-L1 (shared, level ← input)
 *     Inputs:  parent_blocks  int64[N,32,32,32]
 *              heightmap      float32[N,5,32,32]
 *              biome          int64[N,32,32]
 *              y_position     int64[N]
 *              level          int64[N]
 *     Outputs: block_logits   float32[N,num_classes,32,32,32]
 *              occ_logits     float32[N,8]
 *
 *   octree_leaf.onnx   — L0 leaves (no occupancy output)
 *     Inputs:  parent_blocks  int64[N,32,32,32]
 *              heightmap      float32[N,5,32,32]
 *              biome          int64[N,32,32]
 *              y_position     int64[N]
 *     Outputs: block_logits   float32[N,num_classes,32,32,32]
 * </pre>
 *
 * <p>All models exported with dynamic batch axes (N ≥ 1).
 *
 * <p>Thread safety: the DJL {@link NDManager} is thread-safe for sub-manager
 * creation.  Each inference call creates its own sub-manager scope and uses
 * thread-local reusable buffers for GC pressure reduction.
 *
 * @see com.rhythmatician.lodiffusion.voxy.OctreeQueue
 * @see com.rhythmatician.lodiffusion.voxy.OctreeTask
 */
public final class OctreeModelRunner implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger(OctreeModelRunner.class);

    /** ONNX file stems (no extension). */
    private static final String STEM_INIT   = "octree_init";
    private static final String STEM_REFINE = "octree_refine";
    private static final String STEM_LEAF   = "octree_leaf";

    /** Config sidecar filenames. */
    private static final String CONFIG_INIT   = "octree_init_config.json";
    private static final String CONFIG_REFINE = "octree_refine_config.json";
    private static final String CONFIG_LEAF   = "octree_leaf_config.json";

    /** Spatial size of the model output (32³ for octree). */
    private static final int SPATIAL = 32;

    /**
     * Occupancy logits threshold (sigmoid > this → child is occupied).
     *
     * <p>RocNet insight: in recursive octrees, false negatives are
     * disproportionately expensive — they erase entire subtrees.  A lower
     * threshold (e.g. 0.3) biases toward recall at the cost of some extra
     * inference calls, which is the safer trade-off.
     *
     * <p>Configurable at runtime via {@code lodiffusion.json} key
     * {@code "occThreshold"} (0.0–1.0, default 0.3).
     */
    private static float occThreshold() {
        return (float) com.rhythmatician.lodiffusion.Config.getDouble(
                "occThreshold", 0.3);
    }

    /**
     * Result of a single octree inference call.
     *
     * @param blockArgmax  argmax block IDs {@code int[32][32][32]} in Y,Z,X order.
     *                     Computed directly from the flat ONNX logits in a single
     *                     pass — no intermediate 4D array is allocated.
     * @param occMask      predicted 8-bit occupancy mask (sigmoid > 0.5).
     *                     Always 0 for L0 (leaf) tasks.
     * @param vocabSize    number of block classes in the logits
     * @param elapsedMs    wall time for this inference call
     */
    public record OctreeOutput(
        int[][][] blockArgmax,        // [32][32][32] argmax class indices
        byte occMask,
        int vocabSize,
        long elapsedMs
    ) {}

    private final NDManager manager;
    private final ZooModel<NDList, NDList> initModel;
    private final ZooModel<NDList, NDList> refineModel;
    private final ZooModel<NDList, NDList> leafModel;
    private final ModelConfig initConfig;
    private final ModelConfig refineConfig;
    private final ModelConfig leafConfig;
    private final BlockVocabulary vocabulary;

    /**
     * Thread-local reusable buffers for inference.  Eliminates the major
     * GC pressure sources: conditioning tensor scratch arrays (~20 KB each).
     */
    private final ThreadLocal<OctreeInferenceBuffers> threadBuffers =
            new ThreadLocal<>();

    private OctreeModelRunner(NDManager manager,
                              ZooModel<NDList, NDList> initModel,
                              ZooModel<NDList, NDList> refineModel,
                              ZooModel<NDList, NDList> leafModel,
                              ModelConfig initConfig,
                              ModelConfig refineConfig,
                              ModelConfig leafConfig,
                              BlockVocabulary vocabulary) {
        this.manager      = manager;
        this.initModel    = initModel;
        this.refineModel  = refineModel;
        this.leafModel    = leafModel;
        this.initConfig   = initConfig;
        this.refineConfig = refineConfig;
        this.leafConfig   = leafConfig;
        this.vocabulary   = vocabulary;
    }

    // ------------------------------------------------------------------
    // Factory
    // ------------------------------------------------------------------

    /**
     * Load all 3 octree ONNX models from {@code modelDir}.
     *
     * <p>Validates required files from the pipeline manifest, then loads
     * each model with its sidecar config.
     *
     * @param modelDir directory containing the {@code .onnx} files and
     *                 {@code _config.json} sidecars
     * @throws IOException if any model or config cannot be loaded
     */
    public static OctreeModelRunner loadAll(Path modelDir) throws IOException {
        // Validate required files
        validateRequiredFiles(modelDir);

        // Select the best execution provider for this machine (DirectML → CPU).
        InferenceDeviceSelector.Provider provider =
                InferenceDeviceSelector.selectProvider();
        LOGGER.info("[OctreeModelRunner] Execution provider: {}", provider);

        // DJL uses ServiceLoader for both EngineProvider (NDManager.newBaseManager)
        // and ZooProvider (Criteria.loadModel).  In Fabric (Knot), those service
        // implementations are loaded by the mod classloader, not the system/bootstrap
        // classloader that ForkJoinPool worker threads inherit as their context CL.
        // Hold the swap for the entire DJL initialization block so that every
        // internal ServiceLoader call sees the correct classloader.
        ClassLoader prevCl = Thread.currentThread().getContextClassLoader();
        Thread.currentThread().setContextClassLoader(
                OctreeModelRunner.class.getClassLoader());
        try {
            NDManager manager = NDManager.newBaseManager();
            try {
                // Load configs
                ModelConfig initCfg   = ConfigLoader.load(modelDir.resolve(CONFIG_INIT));
                ModelConfig refineCfg = ConfigLoader.load(modelDir.resolve(CONFIG_REFINE));
                ModelConfig leafCfg   = ConfigLoader.load(modelDir.resolve(CONFIG_LEAF));

                // Load models (each calls Criteria.loadModel → ZooProvider ServiceLoader)
                ZooModel<NDList, NDList> init   = loadModel(modelDir, STEM_INIT,   provider);
                ZooModel<NDList, NDList> refine = loadModel(modelDir, STEM_REFINE, provider);
                ZooModel<NDList, NDList> leaf   = loadModel(modelDir, STEM_LEAF,   provider);

                // Use the leaf model's config for vocabulary (finest resolution)
                BlockVocabulary vocab = BlockVocabulary.fromConfig(leafCfg);

                LOGGER.info("[OctreeModelRunner] All 3 octree models loaded — "
                        + "vocab=" + vocab.size() + "  dir=" + modelDir
                        + "  provider=" + provider);

                return new OctreeModelRunner(manager, init, refine, leaf,
                        initCfg, refineCfg, leafCfg, vocab);

            } catch (Exception e) {
                manager.close();
                if (e instanceof IOException) throw (IOException) e;
                throw new IOException("Failed to load octree models from " + modelDir, e);
            }
        } finally {
            Thread.currentThread().setContextClassLoader(prevCl);
        }
    }

    /**
     * Load a single ONNX model, attempting the given execution provider first
     * and transparently falling back to CPU if the provider is unavailable.
     *
     * @param dir      directory containing the {@code .onnx} file
     * @param stem     model file stem (without extension)
     * @param provider requested execution provider
     * @return loaded model
     * @throws IOException if the model cannot be loaded even with CPU fallback
     */
    private static ZooModel<NDList, NDList> loadModel(Path dir, String stem,
                                                       InferenceDeviceSelector.Provider provider)
            throws IOException {
        // Try the requested provider first (if it requires an option)
        if (!provider.djlOptionValue().isEmpty()) {
            try {
                ZooModel<NDList, NDList> model = buildAndLoad(dir, stem,
                        provider.djlOptionValue());
                LOGGER.info("[OctreeModelRunner] Loaded {} with provider {}",
                        stem, provider);
                return model;
            } catch (Exception ex) {
                LOGGER.warn("[OctreeModelRunner] Provider {} unavailable for {} "
                        + "({}); falling back to CPU",
                        provider, stem, ex.getMessage());
            }
        }
        // CPU fallback (or initial attempt when provider == CPU)
        try {
            ZooModel<NDList, NDList> model = buildAndLoad(dir, stem, null);
            if (!provider.djlOptionValue().isEmpty()) {
                LOGGER.info("[OctreeModelRunner] Loaded {} with CPU fallback", stem);
            } else {
                LOGGER.info("[OctreeModelRunner] Loaded {} (CPU)", stem);
            }
            return model;
        } catch (Exception e) {
            throw new IOException("Failed to load " + stem + " from " + dir, e);
        }
    }

    /**
     * Build a {@link Criteria} and load a model, optionally setting the
     * {@code ortDevice} session option for GPU acceleration.
     *
     * @param dir         model directory
     * @param stem        model stem (no extension)
     * @param ortDevice   value for {@code ortDevice} option, or {@code null}
     *                    to use the default (CPU) provider
     * @return loaded model
     * @throws Exception if loading fails
     */
    private static ZooModel<NDList, NDList> buildAndLoad(Path dir, String stem,
                                                          String ortDevice)
            throws Exception {
        Criteria.Builder<NDList, NDList> builder = Criteria.builder()
                .setTypes(NDList.class, NDList.class)
                .optModelPath(dir)
                .optModelName(stem)
                .optTranslator(new NoopTranslator());
        if (ortDevice != null && !ortDevice.isEmpty()) {
            builder.optOption("ortDevice", ortDevice);
        }
        // Thread control: we run single-batch inference on dedicated worker
        // threads, so inter-op parallelism just wastes threads.  Intra-op
        // parallelism helps with large Conv3d ops on the CPU fallback path.
        builder.optOption("interOpNumThreads", "1");
        builder.optOption("intraOpNumThreads", "4");
        return builder.build().loadModel();
    }

    /**
     * Validate that all octree model files exist in the directory.
     */
    private static void validateRequiredFiles(Path modelDir) throws IOException {
        String[] required = {
            STEM_INIT + ".onnx", STEM_REFINE + ".onnx", STEM_LEAF + ".onnx",
            CONFIG_INIT, CONFIG_REFINE, CONFIG_LEAF
        };

        // Also check manifest for additional required files
        Path manifestPath = modelDir.resolve("pipeline_manifest.json");
        List<String> allRequired = new ArrayList<>();

        if (Files.exists(manifestPath)) {
            List<String> fromManifest = parseRequiredFiles(
                    Files.readString(manifestPath));
            if (!fromManifest.isEmpty()) {
                allRequired.addAll(fromManifest);
            }
        }

        // Ensure our minimum set is always checked
        for (String r : required) {
            if (!allRequired.contains(r)) allRequired.add(r);
        }

        List<String> missing = new ArrayList<>();
        for (String name : allRequired) {
            if (!Files.exists(modelDir.resolve(name))) {
                missing.add(name);
            }
        }

        if (!missing.isEmpty()) {
            StringBuilder sb = new StringBuilder();
            sb.append("Incomplete octree model deployment in ").append(modelDir).append("!\n");
            sb.append("Missing ").append(missing.size()).append(" required file(s):\n");
            for (String m : missing) {
                sb.append("  - ").append(m).append('\n');
            }
            sb.append("Copy ALL files from the export directory into ").append(modelDir);
            throw new IOException(sb.toString());
        }

        LOGGER.info("[OctreeModelRunner] Deployment validated — "
                + allRequired.size() + " required files present in " + modelDir);
    }

    /** Parse required_files from pipeline_manifest.json (same as ProgressiveModelRunner). */
    private static List<String> parseRequiredFiles(String json) {
        List<String> result = new ArrayList<>();
        int idx = json.indexOf("\"required_files\"");
        if (idx < 0) return result;
        int arrStart = json.indexOf('[', idx);
        if (arrStart < 0) return result;
        int arrEnd = json.indexOf(']', arrStart);
        if (arrEnd < 0) return result;
        String arrContent = json.substring(arrStart + 1, arrEnd);
        int pos = 0;
        while (pos < arrContent.length()) {
            int q1 = arrContent.indexOf('"', pos);
            if (q1 < 0) break;
            int q2 = arrContent.indexOf('"', q1 + 1);
            if (q2 < 0) break;
            result.add(arrContent.substring(q1 + 1, q2));
            pos = q2 + 1;
        }
        return result;
    }

    // ------------------------------------------------------------------
    // Single-sample inference
    // ------------------------------------------------------------------

    /**
     * Run {@code octree_init.onnx} for a single L4 root section.
     *
     * @param ctx    column context with 32×32 heightmap and biome data
     * @param yPos   Y position index for this section
     * @return inference output with block logits and occupancy mask
     */
    public OctreeOutput runInit(OctreeColumnContext ctx, int yPos)
            throws TranslateException {

        long t0 = System.currentTimeMillis();
        OctreeInferenceBuffers buf = getOrCreateBuffers();
        ctx.flattenHeightmapInto(buf.hpFlat);
        ctx.flattenBiomeInto(buf.bioFlat);

        try (NDManager sub = manager.newSubManager()) {
            NDArray xHp    = sub.create(buf.hpFlat, new Shape(1, 5, 32, 32));
            NDArray xBiome = sub.create(buf.bioFlat, new Shape(1, 32, 32));
            NDArray xY     = sub.create(new long[]{yPos}, new Shape(1));

            NDList inputs  = new NDList(xHp, xBiome, xY);
            NDList outputs = predict(initModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return parseOutput(outputs, true, elapsed);
        }
    }

    /**
     * Run {@code octree_refine.onnx} for a single L3-L1 section.
     *
     * @param parentBlocksFlat parent's argmax block IDs, flat
     *                          {@code long[32768]} (32³)
     * @param ctx               column context with 32×32 heightmap and biome
     * @param yPos              Y position index
     * @param level             current level (1-3)
     * @return inference output with block logits and occupancy mask
     */
    public OctreeOutput runRefine(long[] parentBlocksFlat,
                                   OctreeColumnContext ctx,
                                   int yPos, int level)
            throws TranslateException {

        long t0 = System.currentTimeMillis();
        OctreeInferenceBuffers buf = getOrCreateBuffers();
        ctx.flattenHeightmapInto(buf.hpFlat);
        ctx.flattenBiomeInto(buf.bioFlat);

        try (NDManager sub = manager.newSubManager()) {
            NDArray xParent = sub.create(parentBlocksFlat,
                    new Shape(1, SPATIAL, SPATIAL, SPATIAL));
            NDArray xHp     = sub.create(buf.hpFlat, new Shape(1, 5, 32, 32));
            NDArray xBiome  = sub.create(buf.bioFlat, new Shape(1, 32, 32));
            NDArray xY      = sub.create(new long[]{yPos}, new Shape(1));
            NDArray xLevel  = sub.create(new long[]{level}, new Shape(1));

            NDList inputs  = new NDList(xParent, xHp, xBiome, xY, xLevel);
            NDList outputs = predict(refineModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return parseOutput(outputs, true, elapsed);
        }
    }

    /**
     * Run {@code octree_leaf.onnx} for a single L0 leaf section.
     *
     * @param parentBlocksFlat parent's argmax block IDs, flat long[32768]
     * @param ctx               column context
     * @param yPos              Y position index
     * @return inference output with block logits (occMask always 0)
     */
    public OctreeOutput runLeaf(long[] parentBlocksFlat,
                                 OctreeColumnContext ctx, int yPos)
            throws TranslateException {

        long t0 = System.currentTimeMillis();
        OctreeInferenceBuffers buf = getOrCreateBuffers();
        ctx.flattenHeightmapInto(buf.hpFlat);
        ctx.flattenBiomeInto(buf.bioFlat);

        try (NDManager sub = manager.newSubManager()) {
            NDArray xParent = sub.create(parentBlocksFlat,
                    new Shape(1, SPATIAL, SPATIAL, SPATIAL));
            NDArray xHp     = sub.create(buf.hpFlat, new Shape(1, 5, 32, 32));
            NDArray xBiome  = sub.create(buf.bioFlat, new Shape(1, 32, 32));
            NDArray xY      = sub.create(new long[]{yPos}, new Shape(1));

            NDList inputs  = new NDList(xParent, xHp, xBiome, xY);
            NDList outputs = predict(leafModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return parseOutput(outputs, false, elapsed);
        }
    }

    // ------------------------------------------------------------------
    // Batched inference
    // ------------------------------------------------------------------

    /**
     * Run batched {@code octree_init.onnx} for multiple L4 root tasks.
     *
     * @param tasks list of L4 tasks with column context already set
     * @return per-task inference outputs, same order as input
     */
    public List<OctreeOutput> runInitBatch(List<OctreeTask> tasks)
            throws TranslateException {

        int n = tasks.size();
        if (n == 1) return List.of(runInit(tasks.get(0).columnContext,
                tasks.get(0).wsY));

        long t0 = System.currentTimeMillis();

        try (NDManager sub = manager.newSubManager()) {
            // Stack conditioning tensors
            float[] hpBatch   = new float[n * 5 * 32 * 32];
            long[]  bioBatch  = new long[n * 32 * 32];
            long[]  yBatch    = new long[n];

            for (int b = 0; b < n; b++) {
                OctreeTask task = tasks.get(b);
                OctreeColumnContext ctx = task.columnContext;
                float[] hpFlat = ctx.flattenHeightmap();
                System.arraycopy(hpFlat, 0, hpBatch, b * 5 * 1024, 5 * 1024);
                long[] bioFlat = ctx.flattenBiome();
                System.arraycopy(bioFlat, 0, bioBatch, b * 1024, 1024);
                yBatch[b] = task.wsY;
            }

            NDArray xHp    = sub.create(hpBatch, new Shape(n, 5, 32, 32));
            NDArray xBiome = sub.create(bioBatch, new Shape(n, 32, 32));
            NDArray xY     = sub.create(yBatch, new Shape(n));

            NDList inputs  = new NDList(xHp, xBiome, xY);
            NDList outputs = predict(initModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return splitBatchOutput(outputs, n, true, elapsed);
        }
    }

    /**
     * Run batched {@code octree_refine.onnx} for multiple tasks at the
     * same level (L3-L1).
     *
     * @param level LOD level (1-3) — must be the same for all tasks
     * @param tasks tasks with column context and parent block IDs set
     * @return per-task inference outputs
     */
    public List<OctreeOutput> runRefineBatch(int level, List<OctreeTask> tasks)
            throws TranslateException {

        int n = tasks.size();
        if (n == 1) return List.of(runRefine(tasks.get(0).parentContextFlat,
                tasks.get(0).columnContext, tasks.get(0).wsY, level));

        long t0 = System.currentTimeMillis();
        int parentSize = SPATIAL * SPATIAL * SPATIAL;  // 32768

        try (NDManager sub = manager.newSubManager()) {
            long[]  parentBatch = new long[n * parentSize];
            float[] hpBatch     = new float[n * 5 * 32 * 32];
            long[]  bioBatch    = new long[n * 32 * 32];
            long[]  yBatch      = new long[n];
            long[]  lvlBatch    = new long[n];

            for (int b = 0; b < n; b++) {
                OctreeTask task = tasks.get(b);
                System.arraycopy(task.parentContextFlat, 0,
                        parentBatch, b * parentSize, parentSize);
                float[] hpFlat = task.columnContext.flattenHeightmap();
                System.arraycopy(hpFlat, 0, hpBatch, b * 5 * 1024, 5 * 1024);
                long[] bioFlat = task.columnContext.flattenBiome();
                System.arraycopy(bioFlat, 0, bioBatch, b * 1024, 1024);
                yBatch[b]  = task.wsY;
                lvlBatch[b] = level;
            }

            NDArray xParent = sub.create(parentBatch,
                    new Shape(n, SPATIAL, SPATIAL, SPATIAL));
            NDArray xHp     = sub.create(hpBatch, new Shape(n, 5, 32, 32));
            NDArray xBiome  = sub.create(bioBatch, new Shape(n, 32, 32));
            NDArray xY      = sub.create(yBatch, new Shape(n));
            NDArray xLevel  = sub.create(lvlBatch, new Shape(n));

            NDList inputs  = new NDList(xParent, xHp, xBiome, xY, xLevel);
            NDList outputs = predict(refineModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return splitBatchOutput(outputs, n, true, elapsed);
        }
    }

    /**
     * Run batched {@code octree_leaf.onnx} for multiple L0 leaf tasks.
     *
     * @param tasks tasks with column context and parent block IDs set
     * @return per-task inference outputs (occMask always 0)
     */
    public List<OctreeOutput> runLeafBatch(List<OctreeTask> tasks)
            throws TranslateException {

        int n = tasks.size();
        if (n == 1) return List.of(runLeaf(tasks.get(0).parentContextFlat,
                tasks.get(0).columnContext, tasks.get(0).wsY));

        long t0 = System.currentTimeMillis();
        int parentSize = SPATIAL * SPATIAL * SPATIAL;

        try (NDManager sub = manager.newSubManager()) {
            long[]  parentBatch = new long[n * parentSize];
            float[] hpBatch     = new float[n * 5 * 32 * 32];
            long[]  bioBatch    = new long[n * 32 * 32];
            long[]  yBatch      = new long[n];

            for (int b = 0; b < n; b++) {
                OctreeTask task = tasks.get(b);
                System.arraycopy(task.parentContextFlat, 0,
                        parentBatch, b * parentSize, parentSize);
                float[] hpFlat = task.columnContext.flattenHeightmap();
                System.arraycopy(hpFlat, 0, hpBatch, b * 5 * 1024, 5 * 1024);
                long[] bioFlat = task.columnContext.flattenBiome();
                System.arraycopy(bioFlat, 0, bioBatch, b * 1024, 1024);
                yBatch[b] = task.wsY;
            }

            NDArray xParent = sub.create(parentBatch,
                    new Shape(n, SPATIAL, SPATIAL, SPATIAL));
            NDArray xHp     = sub.create(hpBatch, new Shape(n, 5, 32, 32));
            NDArray xBiome  = sub.create(bioBatch, new Shape(n, 32, 32));
            NDArray xY      = sub.create(yBatch, new Shape(n));

            NDList inputs  = new NDList(xParent, xHp, xBiome, xY);
            NDList outputs = predict(leafModel, inputs);

            long elapsed = System.currentTimeMillis() - t0;
            return splitBatchOutput(outputs, n, false, elapsed);
        }
    }

    // ------------------------------------------------------------------
    // Output parsing
    // ------------------------------------------------------------------

    /**
     * Parse ONNX output for a single sample (batch=1).
     *
     * @param outputs       raw ONNX output tensors
     * @param hasOccupancy  true if this model produces occ_logits (init/refine)
     * @param elapsedMs     wall time for the ONNX call
     * @return parsed output
     */
    private OctreeOutput parseOutput(NDList outputs, boolean hasOccupancy,
                                     long elapsedMs) {
        NDArray blockLogitsTensor = findBlockLogits(outputs);
        long[] shape = blockLogitsTensor.getShape().getShape();
        // shape: [1, num_classes, 32, 32, 32]
        int vocabSize = (int) shape[1];

        float[] flat = blockLogitsTensor.toFloatArray();
        int[][][] blockArgmax = computeArgmaxDirect(flat, vocabSize);

        byte occMask = 0;
        if (hasOccupancy) {
            NDArray occTensor = findOccLogits(outputs);
            if (occTensor != null) {
                occMask = sigmoidThreshold(occTensor.toFloatArray());
            }
        }

        return new OctreeOutput(blockArgmax, occMask,
                vocabSize, elapsedMs);
    }

    /**
     * Split batched ONNX output into per-sample results.
     *
     * <p>Uses {@link #computeArgmaxDirect} to compute argmax directly from
     * the flat logits slice, avoiding the expensive 4D reshape allocation.
     */
    private List<OctreeOutput> splitBatchOutput(NDList outputs, int batchSize,
                                                boolean hasOccupancy,
                                                long totalElapsedMs) {
        NDArray blockLogitsTensor = findBlockLogits(outputs);
        long[] shape = blockLogitsTensor.getShape().getShape();
        // shape: [batchSize, num_classes, 32, 32, 32]
        int vocabSize = (int) shape[1];
        int sampleElements = vocabSize * SPATIAL * SPATIAL * SPATIAL;
        float[] allFlat = blockLogitsTensor.toFloatArray();

        float[] allOccFlat = null;
        if (hasOccupancy) {
            NDArray occTensor = findOccLogits(outputs);
            if (occTensor != null) {
                allOccFlat = occTensor.toFloatArray();
            }
        }

        long perSample = batchSize > 0 ? totalElapsedMs / batchSize : totalElapsedMs;
        List<OctreeOutput> results = new ArrayList<>(batchSize);

        for (int b = 0; b < batchSize; b++) {
            // Compute argmax directly from the flat logits slice
            int[][][] blockArgmax = computeArgmaxDirect(
                    allFlat, b * sampleElements, vocabSize);

            byte occMask = 0;
            if (allOccFlat != null) {
                float[] sampleOcc = new float[8];
                System.arraycopy(allOccFlat, b * 8, sampleOcc, 0, 8);
                occMask = sigmoidThreshold(sampleOcc);
            }

            results.add(new OctreeOutput(blockArgmax, occMask,
                    vocabSize, perSample));
        }

        return results;
    }

    // ------------------------------------------------------------------
    // Tensor helpers
    // ------------------------------------------------------------------

    /** Find the block_logits tensor in the output list (rank 5, channel > 1). */
    private static NDArray findBlockLogits(NDList outputs) {
        // If there's only one rank-5 tensor, it's the block logits
        for (NDArray t : outputs) {
            long[] s = t.getShape().getShape();
            if (s.length == 5 && s[1] > 1) return t;
        }
        // Fallback: first tensor
        if (!outputs.isEmpty()) return outputs.get(0);
        throw new IllegalStateException(
                "[OctreeModelRunner] block_logits not found in model output");
    }

    /** Find the occ_logits tensor in the output list (rank 2, dim1 = 8). */
    private static NDArray findOccLogits(NDList outputs) {
        for (NDArray t : outputs) {
            long[] s = t.getShape().getShape();
            if (s.length == 2 && s[1] == 8) return t;
        }
        // Also check rank-1 with size 8 (single sample without batch dim)
        for (NDArray t : outputs) {
            long[] s = t.getShape().getShape();
            if (s.length == 1 && s[0] == 8) return t;
        }
        LOGGER.warn("[OctreeModelRunner] occ_logits not found in output — "
                + "defaulting to no occupancy");
        return null;
    }

    /**
     * Compute argmax block IDs directly from a flat logits array, without
     * materializing the intermediate {@code float[vocabSize][32][32][32]}
     * reshape.  For vocabSize=128 this eliminates a 16 MB allocation per
     * sample.
     *
     * <p>The flat array layout is {@code [C][Y][Z][X]} where C is the
     * class/channel dimension.  The method iterates in channel-major order
     * for sequential access and maintains a running {@code bestVal} array
     * for cache-friendly argmax computation.
     *
     * @param flat      flat logits starting at index 0, length ≥ vocabSize × 32³
     * @param vocabSize number of block classes
     * @return {@code int[32][32][32]} with the argmax class index at each voxel
     */
    static int[][][] computeArgmaxDirect(float[] flat, int vocabSize) {
        return computeArgmaxDirect(flat, 0, vocabSize);
    }

    /**
     * Compute argmax block IDs directly from a slice of a flat logits array.
     *
     * @param flat      flat logits array (may contain multiple batched samples)
     * @param offset    starting index of this sample's logits within {@code flat}
     * @param vocabSize number of block classes
     * @return {@code int[32][32][32]} with the argmax class index at each voxel
     */
    static int[][][] computeArgmaxDirect(float[] flat, int offset, int vocabSize) {
        int[][][] argmax = new int[SPATIAL][SPATIAL][SPATIAL];
        float[][][] bestVal = new float[SPATIAL][SPATIAL][SPATIAL];

        // Initialize with channel 0
        int idx = offset;
        for (int y = 0; y < SPATIAL; y++)
            for (int z = 0; z < SPATIAL; z++)
                for (int x = 0; x < SPATIAL; x++)
                    bestVal[y][z][x] = flat[idx++];

        // Scan remaining channels — sequential access through flat array
        for (int c = 1; c < vocabSize; c++) {
            for (int y = 0; y < SPATIAL; y++)
                for (int z = 0; z < SPATIAL; z++)
                    for (int x = 0; x < SPATIAL; x++) {
                        float v = flat[idx++];
                        if (v > bestVal[y][z][x]) {
                            bestVal[y][z][x] = v;
                            argmax[y][z][x] = c;
                        }
                    }
        }
        return argmax;
    }

    /**
     * Reshape flat logits {@code [vocabSize * 32 * 32 * 32]} into
     * {@code [vocabSize][32][32][32]} in Y,Z,X order.
     *
     * @deprecated Use {@link #computeArgmaxDirect} instead — avoids the
     *             expensive 4D allocation entirely.
     */
    @Deprecated
    private static float[][][][] reshapeLogits(float[] flat, int vocabSize) {
        float[][][][] out = new float[vocabSize][SPATIAL][SPATIAL][SPATIAL];
        int idx = 0;
        for (int c = 0; c < vocabSize; c++)
            for (int y = 0; y < SPATIAL; y++)
                for (int z = 0; z < SPATIAL; z++)
                    for (int x = 0; x < SPATIAL; x++)
                        out[c][y][z][x] = flat[idx++];
        return out;
    }

    /**
     * Compute argmax block IDs from reshaped logits.
     *
     * @param logits  {@code [vocabSize][32][32][32]}
     * @param vocabSize number of classes
     * @return {@code int[32][32][32]} with argmax class index
     * @deprecated Use {@link #computeArgmaxDirect} instead.
     */
    @Deprecated
    static int[][][] computeArgmax(float[][][][] logits, int vocabSize) {
        int[][][] out = new int[SPATIAL][SPATIAL][SPATIAL];
        for (int y = 0; y < SPATIAL; y++) {
            for (int z = 0; z < SPATIAL; z++) {
                for (int x = 0; x < SPATIAL; x++) {
                    int bestIdx = 0;
                    float bestVal = logits[0][y][z][x];
                    for (int c = 1; c < vocabSize; c++) {
                        float v = logits[c][y][z][x];
                        if (v > bestVal) {
                            bestVal = v;
                            bestIdx = c;
                        }
                    }
                    out[y][z][x] = bestIdx;
                }
            }
        }
        return out;
    }

    /**
     * Apply sigmoid to 8 occupancy logits and threshold at 0.5 to produce
     * an 8-bit mask.
     *
     * @param occLogits raw logits {@code float[8]}
     * @return byte mask where bit i is set if sigmoid(occLogits[i]) > {@link #occThreshold()}
     */
    static byte sigmoidThreshold(float[] occLogits) {
        byte mask = 0;
        for (int i = 0; i < 8 && i < occLogits.length; i++) {
            float sigmoid = 1.0f / (1.0f + (float) Math.exp(-occLogits[i]));
            if (sigmoid > occThreshold()) {
                mask |= (byte) (1 << i);
            }
        }
        return mask;
    }

    /** Run prediction on a model. */
    private static NDList predict(ZooModel<NDList, NDList> model, NDList inputs)
            throws TranslateException {
        try (var predictor = model.newPredictor(new NoopTranslator())) {
            return predictor.predict(inputs);
        }
    }

    // ------------------------------------------------------------------
    // Thread-local buffer pool
    // ------------------------------------------------------------------

    /**
     * Pre-allocated scratch arrays for conditioning tensor construction.
     */
    private static final class OctreeInferenceBuffers {
        final float[] hpFlat  = new float[5 * 32 * 32];
        final long[]  bioFlat = new long[32 * 32];
    }

    private OctreeInferenceBuffers getOrCreateBuffers() {
        OctreeInferenceBuffers buf = threadBuffers.get();
        if (buf == null) {
            buf = new OctreeInferenceBuffers();
            threadBuffers.set(buf);
            LOGGER.debug("[OctreeModelRunner] Allocated inference buffers for thread "
                    + Thread.currentThread().getName());
        }
        return buf;
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    /** The block vocabulary from the leaf model's sidecar config. */
    public BlockVocabulary vocabulary() { return vocabulary; }

    /** ModelConfig from the leaf model. */
    public ModelConfig leafConfig() { return leafConfig; }

    /** ModelConfig from the init model. */
    public ModelConfig initConfig() { return initConfig; }

    /** ModelConfig from the refine model. */
    public ModelConfig refineConfig() { return refineConfig; }

    /** Vocabulary size (number of block classes including air at index 0). */
    public int vocabSize() { return leafConfig.effectiveBlockVocabSize(); }

    @Override
    public void close() {
        if (initModel != null) try { initModel.close(); } catch (Exception ignored) {}
        if (refineModel != null) try { refineModel.close(); } catch (Exception ignored) {}
        if (leafModel != null) try { leafModel.close(); } catch (Exception ignored) {}
        try { manager.close(); } catch (Exception ignored) {}
    }
}
