package io.github.lodiffusion.worldgen;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.IdentityHashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.ArrayList;
import java.util.Set;

/**
 * {@code ShadowRouterExtractor} — Java-side initialization stage of the
 * <em>shadow router</em> pipeline.
 *
 * <h3>What is the shadow router?</h3>
 * Vanilla Minecraft's terrain generation is controlled by a {@code NoiseRouter} —
 * a directed acyclic graph of 15+ {@code DensityFunction} nodes that, together,
 * compute the final density value for every block position.  The shadow router
 * is our GPU-side re-implementation of that same graph: a set of GLSL compute
 * shaders ({@code improved_noise.glsl}, {@code perlin_noise.glsl},
 * {@code normal_noise.glsl}, {@code terrain_compute.comp}) that reproduce vanilla
 * terrain at LOD speed without loading any chunks.
 *
 * <h3>Role of this class</h3>
 * At world load, this extractor <em>mirrors</em> the vanilla {@code NoiseRouter}
 * onto the GPU by:
 * <ol>
 *   <li>Walking the {@code DensityFunction} expression tree via the
 *       {@code DensityFunction.Visitor} pattern (reflection-based, no compile-time
 *       Minecraft dependency).</li>
 *   <li>Discovering every {@code NormalNoise}, {@code PerlinNoise}, and
 *       {@code ImprovedNoise} instance and assigning each a contiguous GPU index.</li>
 *   <li>Serializing permutation tables, octave configs, origin offsets, and blend
 *       scalars into {@link ShadowRouterData} — flat NIO buffers ready for SSBO
 *       upload via {@link ShaderSSBOManager}.</li>
 * </ol>
 * Once extraction is complete, the GPU shaders can evaluate any noise value in
 * parallel using only the SSBO data — no CPU involvement per block.
 *
 * <p>This class is intentionally written without any direct references to Minecraft
 * classes (net.minecraft.*) so that it compiles even if the Minecraft dependency
 * is not present at compile time.
 */
@SuppressWarnings("unused")
public class ShadowRouterExtractor {
    private static final Logger LOGGER = LogManager.getLogger();

    // ============================================================================
    // SSBO Layout Constants (must match GLSL shader definitions)
    // ============================================================================

    private static final int IMPROVED_ORIGINS_BINDING = 0;
    private static final int IMPROVED_PERMS_BINDING = 1;
    private static final int IMPROVED_PERMS_STRIDE = 256;

    private static final int PERLIN_INT_BINDING = 2;
    private static final int PERLIN_FLOAT_BINDING = 3;
    private static final int MAX_OCTAVES = 16;

    private static final int NORMAL_NOISE_INT_BINDING = 4;
    private static final int NORMAL_NOISE_FLOAT_BINDING = 5;

    private static final int SPLINE_DATA_BINDING = 6;
    private static final int DENSITY_OUTPUT_BINDING = 7;

    // ============================================================================
    // Reflection type caches (loaded lazily)
    // ============================================================================

    private final Class<?> densityFunctionClass;
    private final Class<?> densityFunctionVisitorClass;
    private final Class<?> densityFunctionsNoiseClass;
    private final Class<?> densityFunctionsSplineClass;
    private final Class<?> densityFunctionsMarkerClass;
    private final Class<?> densityFunctionNoiseHolderClass;

    private final Class<?> normalNoiseClass;
    private final Class<?> perlinNoiseClass;
    private final Class<?> improvedNoiseClass;
    private final Class<?> cubicSplineClass;

    // Cached reflection methods (for performance)
    private final Method mapAllMethod;
    private final Method noiseHolderNoiseMethod;
    private final Method cubicSplineControlPointsMethod;

    // ============================================================================
    // Extraction state
    // ============================================================================

    /** NormalNoise instance → GPU index */
    private final Map<Object, Integer> noiseIndexMap = new IdentityHashMap<>();

    /** ImprovedNoise instance → GPU index */
    private final Map<Object, Integer> improvedNoiseIndexMap = new IdentityHashMap<>();

    /** PerlinNoise instance → GPU index */
    private final Map<Object, Integer> perlinNoiseIndexMap = new IdentityHashMap<>();

    /** Discovered ImprovedNoise instances (retains insertion order) */
    private final List<Object> improvedNoises = new ArrayList<>();

    /** Discovered PerlinNoise instances */
    private final List<Object> perlinNoises = new ArrayList<>();

    /** Discovered NormalNoise instances */
    private final List<Object> normalNoises = new ArrayList<>();

    /**
     * Scale parameters recorded when a NormalNoise is encountered inside a
     * {@code DensityFunctions.Noise} wrapper.  Keyed by the NormalNoise instance;
     * value is {@code [xzScale, yScale]} as declared on the wrapper.
     *
     * Used to identify Noises.JAGGED (xzScale ≈ 1500, yScale = 0) without needing
     * to compare resource keys at runtime.
     */
    private final Map<Object, double[]> normalNoiseScaleMap = new IdentityHashMap<>();

    /** Spline control point buffer and offsets */
    private final List<Float> splineDataFloats = new ArrayList<>();
    private final Map<Object, Integer> splineOffsets = new IdentityHashMap<>();

    // ----------------------------------------------------------------------------------------------------------------
    // Constructor (builds reflection metadata)
    // ----------------------------------------------------------------------------------------------------------------

    public ShadowRouterExtractor() {
        // Yarn-mapped names (MC 1.21.11 Fabric).  Mojang-mapped fallbacks are
        // tried second so the extractor also works in a Mojmap dev environment.
        this.densityFunctionClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunction",
                "net.minecraft.world.level.levelgen.DensityFunction");
        this.densityFunctionVisitorClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunction$DensityFunctionVisitor",
                "net.minecraft.world.level.levelgen.DensityFunction$Visitor");
        this.densityFunctionsNoiseClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$Noise",
                "net.minecraft.world.level.levelgen.DensityFunctions$Noise");
        this.densityFunctionsSplineClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$Spline",
                "net.minecraft.world.level.levelgen.DensityFunctions$Spline");
        this.densityFunctionsMarkerClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunctionTypes$Wrapping",
                "net.minecraft.world.level.levelgen.DensityFunctions$Marker");
        this.densityFunctionNoiseHolderClass = loadClassWithFallback(
                "net.minecraft.world.gen.densityfunction.DensityFunction$Noise",
                "net.minecraft.world.level.levelgen.DensityFunction$NoiseHolder");

        this.normalNoiseClass = loadClassWithFallback(
                "net.minecraft.util.math.noise.DoublePerlinNoiseSampler",
                "net.minecraft.world.level.levelgen.synth.NormalNoise");
        this.perlinNoiseClass = loadClassWithFallback(
                "net.minecraft.util.math.noise.OctavePerlinNoiseSampler",
                "net.minecraft.world.level.levelgen.synth.PerlinNoise");
        this.improvedNoiseClass = loadClassWithFallback(
                "net.minecraft.util.math.noise.PerlinNoiseSampler",
                "net.minecraft.world.level.levelgen.synth.ImprovedNoise");
        this.cubicSplineClass = loadClassWithFallback(
                "net.minecraft.util.math.Spline",
                "net.minecraft.util.CubicSpline");

        // Yarn: apply(DensityFunctionVisitor)  Mojang: mapAll(Visitor)
        this.mapAllMethod = findMethodByNameFallback(
                densityFunctionClass, densityFunctionVisitorClass,
                "apply", "mapAll");
        this.noiseHolderNoiseMethod = findMethodOrNull(densityFunctionNoiseHolderClass, "noise");
        // Yarn: Spline is an interface; concrete Spline$Implementation exposes
        // locations()/values()/derivatives() — getControlPoints() no longer exists.
        this.cubicSplineControlPointsMethod = findMethodOrNull(cubicSplineClass, "getControlPoints");
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Public API
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Extracts SSBO-compatible buffers from a runtime NoiseRouter instance.
     *
     * @param noiseRouter A runtime instance of net.minecraft.world.level.levelgen.NoiseRouter
     */
    public ShadowRouterData extract(Object noiseRouter) {
        LOGGER.info("Starting shadow router extraction...");
        if (noiseRouter == null) {
            throw new IllegalArgumentException("noiseRouter must not be null");
        }
        if (densityFunctionVisitorClass == null) {
            throw new IllegalStateException("Unable to locate DensityFunction visitor interface at runtime");
        }

        Object visitor = createVisitorProxy();
        try {
            int traversed = traverseNoiseRouterDensityFunctions(noiseRouter, visitor);
            if (traversed == 0) {
                throw new IllegalStateException("NoiseRouter traversal found no DensityFunction accessors");
            }
            LOGGER.info("Traversed {} NoiseRouter density functions via mapAll/apply", traversed);
        } catch (Exception e) {
            throw new RuntimeException("Failed to invoke mapAll() on NoiseRouter", e);
        }

        LOGGER.info("Discovered {} ImprovedNoise instances", improvedNoises.size());
        LOGGER.info("Discovered {} PerlinNoise instances", perlinNoises.size());
        LOGGER.info("Discovered {} NormalNoise instances", normalNoises.size());

        ShadowRouterData data = new ShadowRouterData();
        data.improvedOrigins = extractImprovedOrigins();
        data.improvedPerms = extractImprovedPerms();
        data.perlinInts = extractPerlinInts();
        data.perlinFloats = extractPerlinFloats();
        data.normalNoiseInts = extractNormalNoiseInts();
        data.normalNoiseFloats = extractNormalNoiseFloats();

        float[] splineArray = new float[splineDataFloats.size()];
        for (int i = 0; i < splineDataFloats.size(); i++) {
            splineArray[i] = splineDataFloats.get(i);
        }
        data.splineData = FloatBuffer.wrap(splineArray);

        // Second pass: resolve named noise indices from specific NoiseRouter fields
        wireNamedIndices(noiseRouter, data);

        LOGGER.info("Shadow router extraction complete. Spline data size: {} floats", data.splineData.capacity());
        LOGGER.info("Named indices: continents={} erosion={} ridges={} shift={}",
                data.nnContinents, data.nnErosion, data.nnRidges, data.shiftNoiseIndex);
        return data;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Visitor Proxy
    // ----------------------------------------------------------------------------------------------------------------

    private Object createVisitorProxy() {
        InvocationHandler handler = (proxy, method, args) -> {
            if (args != null && args.length == 1) {
                Object function = args[0];
                unwrapAndProcess(function);
                return function; // match DensityFunction.Visitor.apply()
            }
            // Fallback: return null for any other method
            return null;
        };

        return Proxy.newProxyInstance(
            getClass().getClassLoader(),
            new Class<?>[]{densityFunctionVisitorClass},
            handler
        );
    }

    /**
     * Visits every zero-arg NoiseRouter accessor that returns a DensityFunction
     * and invokes DensityFunction.mapAll/apply with our visitor.
     */
    private int traverseNoiseRouterDensityFunctions(Object noiseRouter, Object visitor) throws Exception {
        Class<?> routerClass = noiseRouter.getClass();
        Set<Object> seenFunctions = java.util.Collections.newSetFromMap(new IdentityHashMap<>());
        Set<String> attemptedAccessors = new HashSet<>();
        int traversed = 0;

        for (Method accessor : routerClass.getMethods()) {
            if (accessor.getParameterCount() != 0) continue;
            if (!densityFunctionClass.isAssignableFrom(accessor.getReturnType())) continue;
            if (accessor.getDeclaringClass() == Object.class) continue;

            String name = accessor.getName();
            if (!attemptedAccessors.add(name)) continue;

            Object densityFn;
            try {
                densityFn = accessor.invoke(noiseRouter);
            } catch (Exception e) {
                LOGGER.debug("Skipping NoiseRouter accessor {} due to invocation failure", name, e);
                continue;
            }
            if (densityFn == null || !seenFunctions.add(densityFn)) {
                continue;
            }

            if (!invokeMapAllCompatible(densityFn, visitor)) {
                // Last resort: process the node directly so extraction still proceeds.
                unwrapAndProcess(densityFn);
                LOGGER.debug("Visited {} via direct fallback (mapAll/apply unavailable)", densityFn.getClass().getName());
            }
            traversed++;
        }

        return traversed;
    }

    /**
     * Invoke mapAll/apply on a concrete DensityFunction instance, tolerating
     * declaring-class mismatches caused by mapping/classloader differences.
     */
    private boolean invokeMapAllCompatible(Object densityFn, Object visitor) {
        // Fast path: method discovered from the interface cache.
        if (mapAllMethod != null && mapAllMethod.getDeclaringClass().isInstance(densityFn)) {
            try {
                mapAllMethod.invoke(densityFn, visitor);
                return true;
            } catch (Exception e) {
                LOGGER.debug("Cached mapAll/apply invocation failed on {}", densityFn.getClass().getName(), e);
            }
        }

        // Fallback: resolve on concrete runtime class.
        Method concrete = findMethodByNameFallback(densityFn.getClass(), densityFunctionVisitorClass, "apply", "mapAll");
        if (concrete == null) {
            return false;
        }
        try {
            concrete.invoke(densityFn, visitor);
            return true;
        } catch (Exception e) {
            LOGGER.debug("Concrete mapAll/apply invocation failed on {}", densityFn.getClass().getName(), e);
            return false;
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Graph traversal and node processing
    // ----------------------------------------------------------------------------------------------------------------

    private void unwrapAndProcess(Object function) {
        if (function == null) return;

        if (densityFunctionsNoiseClass != null && densityFunctionsNoiseClass.isInstance(function)) {
            processNoise(function);
            return;
        }

        if (densityFunctionsSplineClass != null && densityFunctionsSplineClass.isInstance(function)) {
            processSpline(function);
            return;
        }

        if (densityFunctionsMarkerClass != null && densityFunctionsMarkerClass.isInstance(function)) {
            Object underlying = getMarkerArgument(function);
            if (underlying != null) {
                unwrapAndProcess(underlying);
            }
            return;
        }

        // Handle NoiseHolder directly — visited via visitNoise() from ShiftedNoise/ShiftA/ShiftB.
        // This registers the NormalNoise for continents, erosion, ridges, and the SHIFT noise.
        if (densityFunctionNoiseHolderClass != null && densityFunctionNoiseHolderClass.isInstance(function)) {
            processNoiseHolder(function);
            return;
        }

        // Fallback: if this object has a field named "wrapped" or "argument", try to unwrap
        Object fallback = tryUnwrapByName(function, "wrapped");
        if (fallback == null) fallback = tryUnwrapByName(function, "argument");
        if (fallback != null) {
            unwrapAndProcess(fallback);
        }
    }

    private void processNoiseHolder(Object holder) {
        try {
            if (noiseHolderNoiseMethod == null) return;
            Object noiseObj = noiseHolderNoiseMethod.invoke(holder);
            if (noiseObj == null || normalNoiseClass == null || !normalNoiseClass.isInstance(noiseObj)) return;

            if (!noiseIndexMap.containsKey(noiseObj)) {
                int index = normalNoises.size();
                noiseIndexMap.put(noiseObj, index);
                normalNoises.add(noiseObj);

                Object first  = getFieldValue(noiseObj, "firstSampler");
                Object second = getFieldValue(noiseObj, "secondSampler");
                registerPerlinNoise(first);
                registerPerlinNoise(second);
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process NoiseHolder node", e);
        }
    }

    private void processNoise(Object noiseFunc) {
        try {
            Object holder = getNoiseHolder(noiseFunc);
            if (holder == null) return;

            Object noiseObj = noiseHolderNoiseMethod != null ? noiseHolderNoiseMethod.invoke(holder) : null;
            if (noiseObj == null || normalNoiseClass == null || !normalNoiseClass.isInstance(noiseObj)) {
                return;
            }

            if (!noiseIndexMap.containsKey(noiseObj)) {
                int index = normalNoises.size();
                noiseIndexMap.put(noiseObj, index);
                normalNoises.add(noiseObj);

                Object first = getFieldValue(noiseObj, "firstSampler");
                Object second = getFieldValue(noiseObj, "secondSampler");

                registerPerlinNoise(first);
                registerPerlinNoise(second);
            }

            // Record the xzScale / yScale from the DensityFunctions.Noise wrapper so that
            // wireNamedIndices() can identify noises by their distinctive scale signature
            // (e.g. Noises.JAGGED at xzScale = 1500, yScale = 0).
            try {
                double xzScale = getDoubleField(noiseFunc, "xzScale");
                double yScale  = getDoubleField(noiseFunc, "yScale");
                normalNoiseScaleMap.put(noiseObj, new double[]{xzScale, yScale});
            } catch (Exception ignored) {
                // Field may be absent on some mod-injected wrappers; safe to skip.
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process Noise node", e);
        }
    }

    private void processSpline(Object splineFunc) {
        if (cubicSplineClass == null) return;

        try {
            Object spline = getFieldValue(splineFunc, "spline");
            if (spline != null && !splineOffsets.containsKey(spline)) {
                int offset = splineDataFloats.size();
                splineOffsets.put(spline, offset);

                // TODO: Flatten spline control points.
                // For now, we reserve the offset so shaders can bind a predictable index.
            }
        } catch (Exception e) {
            LOGGER.warn("Failed to process Spline node", e);
        }
    }

    private void registerPerlinNoise(Object pn) {
        if (pn == null || perlinNoiseClass == null || !perlinNoiseClass.isInstance(pn)) return;
        if (perlinNoiseIndexMap.containsKey(pn)) return;

        int index = perlinNoises.size();
        perlinNoiseIndexMap.put(pn, index);
        perlinNoises.add(pn);

        Object[] octaves = getImprovedOctaves(pn);
        if (octaves == null) return;

        for (Object improved : octaves) {
            if (improved != null && !improvedNoiseIndexMap.containsKey(improved)) {
                int improvedIdx = improvedNoises.size();
                improvedNoiseIndexMap.put(improved, improvedIdx);
                improvedNoises.add(improved);
            }
        }
    }

    // ----------------------------------------------------------------------------------------------------------------
    // SSBO extraction helpers
    // ----------------------------------------------------------------------------------------------------------------

    private FloatBuffer extractImprovedOrigins() {
        FloatBuffer buffer = FloatBuffer.allocate(improvedNoises.size() * 3);
        for (Object improved : improvedNoises) {
            try {
                double xo = getDoubleField(improved, "originX");
                double yo = getDoubleField(improved, "originY");
                double zo = getDoubleField(improved, "originZ");
                buffer.put((float) xo);
                buffer.put((float) yo);
                buffer.put((float) zo);
            } catch (Exception e) {
                LOGGER.warn("Failed to extract origin from ImprovedNoise", e);
                buffer.put(0).put(0).put(0);
            }
        }
        buffer.flip();
        return buffer;
    }

    private IntBuffer extractImprovedPerms() {
        IntBuffer buffer = IntBuffer.allocate(improvedNoises.size() * IMPROVED_PERMS_STRIDE);
        for (Object improved : improvedNoises) {
            try {
                byte[] perms = (byte[]) getFieldValue(improved, "permutation");
                for (byte perm : perms) {
                    buffer.put(perm & 0xFF);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to extract permutations from ImprovedNoise", e);
                for (int i = 0; i < IMPROVED_PERMS_STRIDE; i++) {
                    buffer.put(i);
                }
            }
        }
        buffer.flip();
        return buffer;
    }

    private IntBuffer extractPerlinInts() {
        IntBuffer buffer = IntBuffer.allocate(perlinNoises.size() * (1 + MAX_OCTAVES));
        for (Object pn : perlinNoises) {
            try {
                int firstOctave = getIntField(pn, "firstOctave");
                buffer.put(firstOctave);

                Object[] octaves = getImprovedOctaves(pn);
                for (int i = 0; i < MAX_OCTAVES; i++) {
                    if (octaves != null && i < octaves.length && octaves[i] != null) {
                        Integer idx = improvedNoiseIndexMap.get(octaves[i]);
                        buffer.put(idx != null ? idx : -1);
                    } else {
                        buffer.put(-1);
                    }
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to extract PerlinNoise int data", e);
                buffer.put(0);
                for (int i = 0; i < MAX_OCTAVES; i++) {
                    buffer.put(-1);
                }
            }
        }
        buffer.flip();
        return buffer;
    }

    private FloatBuffer extractPerlinFloats() {
        FloatBuffer buffer = FloatBuffer.allocate(perlinNoises.size() * (2 + MAX_OCTAVES));
        for (Object pn : perlinNoises) {
            try {
                double lowestFreqFactor = getDoubleField(pn, "lacunarity");
                double lowestValFactor = getDoubleField(pn, "persistence");
                buffer.put((float) lowestFreqFactor);
                buffer.put((float) lowestValFactor);

                Object amplitudes = getFieldValue(pn, "amplitudes");
                int amplitudeCount = 0;
                double[] amplitudeArray = new double[MAX_OCTAVES];

                if (amplitudes != null) {
                    try {
                        Method sizeMethod = amplitudes.getClass().getMethod("size");
                        int size = (int) sizeMethod.invoke(amplitudes);
                        amplitudeCount = Math.min(size, MAX_OCTAVES);
                        Method getDoubleMethod = amplitudes.getClass().getMethod("getDouble", int.class);
                        for (int i = 0; i < amplitudeCount; i++) {
                            amplitudeArray[i] = (double) getDoubleMethod.invoke(amplitudes, i);
                        }
                    } catch (Exception ex) {
                        LOGGER.warn("Failed to extract amplitudes from DoubleList", ex);
                    }
                }
                for (int i = 0; i < MAX_OCTAVES; i++) {
                    buffer.put(i < amplitudeCount ? (float) amplitudeArray[i] : 0.0f);
                }
            } catch (Exception e) {
                LOGGER.warn("Failed to extract PerlinNoise float data", e);
                buffer.put(1.0f).put(1.0f);
                for (int i = 0; i < MAX_OCTAVES; i++) {
                    buffer.put(0.0f);
                }
            }
        }
        buffer.flip();
        return buffer;
    }

    private IntBuffer extractNormalNoiseInts() {
        IntBuffer buffer = IntBuffer.allocate(normalNoises.size() * 2);
        for (Object nn : normalNoises) {
            try {
                Object first = getFieldValue(nn, "firstSampler");
                Object second = getFieldValue(nn, "secondSampler");
                Integer firstIdx = perlinNoiseIndexMap.get(first);
                Integer secondIdx = perlinNoiseIndexMap.get(second);
                buffer.put(firstIdx != null ? firstIdx : -1);
                buffer.put(secondIdx != null ? secondIdx : -1);
            } catch (Exception e) {
                LOGGER.warn("Failed to extract NormalNoise int data", e);
                buffer.put(-1).put(-1);
            }
        }
        buffer.flip();
        return buffer;
    }

    private FloatBuffer extractNormalNoiseFloats() {
        FloatBuffer buffer = FloatBuffer.allocate(normalNoises.size());
        for (Object nn : normalNoises) {
            try {
                double valueFactor = getDoubleField(nn, "amplitude");
                buffer.put((float) valueFactor);
            } catch (Exception e) {
                LOGGER.warn("Failed to extract NormalNoise float data", e);
                buffer.put(1.0f);
            }
        }
        buffer.flip();
        return buffer;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Reflection helpers
    // ----------------------------------------------------------------------------------------------------------------

    private Class<?> loadClassOrNull(String className) {
        try {
            return Class.forName(className);
        } catch (ClassNotFoundException e) {
            return null;
        }
    }

    /** Try the primary (Yarn) name first, then the fallback (Mojang) name. */
    private Class<?> loadClassWithFallback(String primary, String fallback) {
        Class<?> cls = loadClassOrNull(primary);
        return cls != null ? cls : loadClassOrNull(fallback);
    }

    private Method findMethodOrNull(String methodName, Class<?>... paramTypes) {
        if (densityFunctionClass == null) return null;
        try {
            return densityFunctionClass.getMethod(methodName, paramTypes);
        } catch (Exception ignored) {
            return null;
        }
    }

    /**
     * Finds a method on {@code clazz} accepting a single parameter of {@code paramType},
     * trying the primary (Yarn) method name first, then the fallback (Mojang) name.
     */
    private Method findMethodByNameFallback(Class<?> clazz, Class<?> paramType,
                                            String primary, String fallback) {
        if (clazz == null || paramType == null) return null;
        try {
            return clazz.getMethod(primary, paramType);
        } catch (Exception ignored) { /* try fallback */ }
        try {
            return clazz.getMethod(fallback, paramType);
        } catch (Exception ignored) { return null; }
    }

    private Method findMethodOrNull(Class<?> clazz, String methodName, Class<?>... paramTypes) {
        if (clazz == null) return null;
        try {
            return clazz.getMethod(methodName, paramTypes);
        } catch (Exception ignored) {
            return null;
        }
    }

    private Object getFieldValue(Object obj, String fieldName) {
        if (obj == null) return null;
        try {
            Field field = obj.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (Exception ignored) {
            // Ignore missing fields; we'll attempt other strategies
            return null;
        }
    }

    private double getDoubleField(Object obj, String fieldName) throws Exception {
        Object value = getFieldValue(obj, fieldName);
        if (value instanceof Number) {
            return ((Number) value).doubleValue();
        }
        throw new IllegalStateException("Field " + fieldName + " is not numeric");
    }

    private int getIntField(Object obj, String fieldName) throws Exception {
        Object value = getFieldValue(obj, fieldName);
        if (value instanceof Number) {
            return ((Number) value).intValue();
        }
        throw new IllegalStateException("Field " + fieldName + " is not numeric");
    }

    private Object getNoiseHolder(Object noiseFunc) {
        return getFieldValue(noiseFunc, "noise");
    }

    private Object getMarkerArgument(Object marker) {
        return getFieldValue(marker, "wrapped");
    }

    private Object[] getImprovedOctaves(Object perlinNoise) {
        Object value = getFieldValue(perlinNoise, "octaveSamplers");
        if (value == null) return null;
        if (value.getClass().isArray()) {
            int len = Array.getLength(value);
            Object[] result = new Object[len];
            for (int i = 0; i < len; i++) {
                result[i] = Array.get(value, i);
            }
            return result;
        }
        return null;
    }

    private Object tryUnwrapByName(Object obj, String fieldName) {
        Object candidate = getFieldValue(obj, fieldName);
        if (candidate != null) return candidate;
        return null;
    }

    // ----------------------------------------------------------------------------------------------------------------
    // Named index wiring (second pass after mapAll() traversal)
    // ----------------------------------------------------------------------------------------------------------------

    /**
     * Populates named noise indices in {@code data} by walking specific fields of the NoiseRouter.
     * Requires that {@code mapAll()} has already been run so {@code noiseIndexMap} is populated.
     */
    private void wireNamedIndices(Object noiseRouter, ShadowRouterData data) {
        data.nnContinents    = indexForShiftedNoise(noiseRouter, "continents");
        data.nnErosion       = indexForShiftedNoise(noiseRouter, "erosion");
        data.nnRidges        = indexForShiftedNoise(noiseRouter, "ridges");
        data.nnTemperature   = indexForShiftedNoise(noiseRouter, "temperature");
        data.nnVegetation    = indexForShiftedNoise(noiseRouter, "vegetation");
        data.shiftNoiseIndex = indexForShiftNoise(noiseRouter, "continents");
        data.nnJagged        = indexForJaggedNoise();
        // nnDepthNoise (BASE_3D_NOISE_OVERWORLD) is a BlendedNoise, not a NormalNoise.
        // mc_normal_noise() cannot evaluate it; tracked as WS-1.2-BlendedNoise.
        // data.nnDepthNoise remains -1 until a mc_blended_noise() GLSL function is added.

        // Aquifer noise fields (4 fields; each is a simple DensityFunctions.Noise wrapper)
        data.nnBarrier      = indexForDirectNoise(noiseRouter, "barrierNoise");
        data.nnFloodedness  = indexForDirectNoise(noiseRouter, "fluidLevelFloodednessNoise");
        data.nnSpread       = indexForDirectNoise(noiseRouter, "fluidLevelSpreadNoise");
        data.nnLava         = indexForDirectNoise(noiseRouter, "lavaNoise");

        // Ore vein noise fields (3 fields; each is a simple DensityFunctions.Noise wrapper)
        data.nnVeinToggle   = indexForDirectNoise(noiseRouter, "veinToggle");
        data.nnVeinRidged   = indexForDirectNoise(noiseRouter, "veinRidged");
        data.nnVeinGap      = indexForDirectNoise(noiseRouter, "veinGap");

        LOGGER.info("Named noise indices: continents={}, erosion={}, ridges={}, temp={}, veg={}, shift={}, jagged={}",
                data.nnContinents, data.nnErosion, data.nnRidges,
                data.nnTemperature, data.nnVegetation, data.shiftNoiseIndex, data.nnJagged);
        LOGGER.info("Aquifer/ore noise indices: barrier={}, floodedness={}, spread={}, lava={}, veinToggle={}, veinRidged={}, veinGap={}",
                data.nnBarrier, data.nnFloodedness, data.nnSpread, data.nnLava,
                data.nnVeinToggle, data.nnVeinRidged, data.nnVeinGap);
    }

    /**
     * Returns the NormalNoise SSBO index for a router field backed by a
     * {@code DensityFunctions.Noise} wrapper (non-shifted).  This covers the
     * aquifer fields (barrier, floodedness, spread, lava) and ore vein fields
     * (veinToggle, veinRidged, veinGap) where the DensityFunction graph is:
     *   noiseRouter.{fieldName}() → DensityFunctions.Noise → .noiseData (NoiseHolder) → NormalNoise
     *
     * <p>Falls back to unwrapping intermediate cache/marker wrappers if needed.
     */
    private int indexForDirectNoise(Object noiseRouter, String fieldName) {
        try {
            Object densityFunction = invokeAccessor(noiseRouter, fieldName);
            if (densityFunction == null) return -1;

            // Walk through potential wrappers (cache, marker) to reach the Noise node
            return resolveNoiseIndex(densityFunction, fieldName);
        } catch (Exception e) {
            LOGGER.warn("Failed to extract direct noise index for '{}'", fieldName, e);
            return -1;
        }
    }

    /**
     * Recursively resolves a NormalNoise SSBO index from a DensityFunction, handling
     * common wrappers: DensityFunctions.Noise, cache layers, markers.
     */
    private int resolveNoiseIndex(Object densityFunction, String debugName) {
        if (densityFunction == null) return -1;

        // Direct: DensityFunctions.Noise → noiseData (NoiseHolder) → NormalNoise
        if (densityFunctionsNoiseClass != null && densityFunctionsNoiseClass.isInstance(densityFunction)) {
            Object noiseHolder = getFieldValue(densityFunction, "noise");
            if (noiseHolder == null) noiseHolder = getNoiseHolder(densityFunction);
            if (noiseHolder != null) {
                try {
                    Object normalNoise = noiseHolderNoiseMethod != null
                            ? noiseHolderNoiseMethod.invoke(noiseHolder) : null;
                    if (normalNoise != null) {
                        Integer idx = noiseIndexMap.get(normalNoise);
                        if (idx != null) return idx;
                    }
                } catch (Exception e) {
                    LOGGER.debug("NoiseHolder invoke failed for '{}'", debugName);
                }
            }
        }

        // Unwrap: cache/marker layers ("wrapped", "argument")
        Object inner = getFieldValue(densityFunction, "wrapped");
        if (inner == null) inner = getFieldValue(densityFunction, "argument");
        if (inner == null) inner = tryUnwrapByName(densityFunction, "delegate");
        if (inner != null && inner != densityFunction) {
            return resolveNoiseIndex(inner, debugName);
        }

        LOGGER.debug("Could not resolve NormalNoise from '{}' (type: {})",
                debugName, densityFunction.getClass().getSimpleName());
        return -1;
    }

    /**
     * Returns the NormalNoise SSBO index for the noise wrapped inside a ShiftedNoise2d field.
     * noiseRouter.{fieldName}() → ShiftedNoise → .noise (NoiseHolder) → NormalNoise → index
     */
    private int indexForShiftedNoise(Object noiseRouter, String fieldName) {
        try {
            Object shiftedNoise = invokeAccessor(noiseRouter, fieldName);
            if (shiftedNoise == null) return -1;

            // ShiftedNoise.noise is a DensityFunction$NoiseHolder
            Object noiseHolder = getFieldValue(shiftedNoise, "noise");
            if (noiseHolder == null) return -1;

            Object normalNoise = noiseHolderNoiseMethod != null ? noiseHolderNoiseMethod.invoke(noiseHolder) : null;
            if (normalNoise == null) return -1;

            Integer idx = noiseIndexMap.get(normalNoise);
            return idx != null ? idx : -1;
        } catch (Exception e) {
            LOGGER.warn("Failed to determine index for ShiftedNoise '{}'", fieldName, e);
            return -1;
        }
    }

    /**
     * Returns the SHIFT NormalNoise SSBO index by walking:
     *   noiseRouter.{fieldName}() (ShiftedNoise) → .shiftX (ShiftA) → .offsetNoise (NoiseHolder) → NormalNoise
     *
     * Both ShiftA and ShiftB use the same underlying Noises.SHIFT NormalNoise.
     * The coord permutation (bx,0,bz vs bz,bx,0) is applied at call time in the shader,
     * so a single SSBO index covers both shift_x and shift_z.
     */
    private int indexForShiftNoise(Object noiseRouter, String fieldName) {
        try {
            Object shiftedNoise = invokeAccessor(noiseRouter, fieldName);
            if (shiftedNoise == null) return -1;

            // ShiftedNoise.shiftX is a ShiftA instance
            Object shiftA = getFieldValue(shiftedNoise, "shiftX");
            if (shiftA == null) return -1;

            // ShiftA.offsetNoise is a DensityFunction$NoiseHolder
            Object offsetNoiseHolder = getFieldValue(shiftA, "offsetNoise");
            if (offsetNoiseHolder == null) return -1;

            Object normalNoise = noiseHolderNoiseMethod != null ? noiseHolderNoiseMethod.invoke(offsetNoiseHolder) : null;
            if (normalNoise == null) return -1;

            Integer idx = noiseIndexMap.get(normalNoise);
            return idx != null ? idx : -1;
        } catch (Exception e) {
            LOGGER.warn("Failed to determine shift noise index from '{}'", fieldName, e);
            return -1;
        }
    }

    /**
     * Finds the NormalNoise SSBO index for {@code Noises.JAGGED} by matching its
     * distinctive {@code xzScale ≈ 1500, yScale = 0} signature recorded during the
     * {@link #processNoise} pass.
     *
     * <p>Rationale: {@code NoiseRouterData.java} wraps Noises.JAGGED as
     * {@code DensityFunctions.noise(noises.getOrThrow(Noises.JAGGED), 1500.0, 0.0)}.
     * No other terrain noise uses an xzScale near 1500, so scale matching is robust.
     */
    private int indexForJaggedNoise() {
        for (Map.Entry<Object, double[]> entry : normalNoiseScaleMap.entrySet()) {
            double xzScale = entry.getValue()[0];
            double yScale  = entry.getValue()[1];
            if (xzScale >= 1450.0 && xzScale <= 1550.0 && Math.abs(yScale) < 0.01) {
                Integer idx = noiseIndexMap.get(entry.getKey());
                if (idx != null) {
                    LOGGER.debug("Resolved Noises.JAGGED at NormalNoise index {} (xzScale={})",
                            idx, xzScale);
                    return idx;
                }
            }
        }
        LOGGER.warn("Noises.JAGGED not found in normalNoiseScaleMap — nnJagged remains -1. "
                + "({} NormalNoise/scale entries available)", normalNoiseScaleMap.size());
        return -1;
    }

    /** Invokes a no-arg accessor method by name on the given object. */
    private Object invokeAccessor(Object obj, String methodName) {
        try {
            java.lang.reflect.Method m = obj.getClass().getMethod(methodName);
            return m.invoke(obj);
        } catch (Exception e) {
            return null;
        }
    }

    // ============================================================================
    // Output Data Container
    // ============================================================================

    /**
     * Serialized shadow router parameters — flat NIO buffers ready for SSBO
     * upload via {@link ShaderSSBOManager}.  One instance is produced per world
     * load and reused for the lifetime of that world's noise configuration.
     */
    public static class ShadowRouterData {
        public FloatBuffer improvedOrigins;
        public IntBuffer improvedPerms;
        public IntBuffer perlinInts;
        public FloatBuffer perlinFloats;
        public IntBuffer normalNoiseInts;
        public FloatBuffer normalNoiseFloats;
        public FloatBuffer splineData;

        // Named NormalNoise indices within the flat SSBO arrays (binding 4/5).
        // -1 means not found — shader will use its fallback path.
        public int nnContinents    = -1;
        public int nnErosion       = -1;
        public int nnRidges        = -1;
        public int nnTemperature   = -1;  // NoiseRouter.temperature (biome climate signal)
        public int nnVegetation    = -1;  // NoiseRouter.vegetation  (= humidity in biome terms)
        /**
         * BASE_3D_NOISE_OVERWORLD = BlendedNoise(xzScale=0.25, yScale=0.125, xzFactor=80,
         * yFactor=160, smearScale=8).  BlendedNoise is composed of three PerlinNoise
         * instances blended by smooth interpolation and CANNOT be evaluated by
         * mc_normal_noise().  This index remains -1 until a dedicated mc_blended_noise()
         * GLSL function is added (tracked: WS-1.2-BlendedNoise).
         */
        public int nnDepthNoise    = -1;
        /** Noises.JAGGED with xzScale=1500, yScale=0.  Resolved by scale-matching in
         *  {@link ShadowRouterExtractor#indexForJaggedNoise()}. */
        public int nnJagged        = -1;
        public int shiftNoiseIndex = -1;  // Noises.SHIFT — same index for both ShiftA and ShiftB

        // Aquifer noise indices (RouterField ordinals 8–11)
        public int nnBarrier       = -1;  // NoiseRouter.barrierNoise()
        public int nnFloodedness   = -1;  // NoiseRouter.fluidLevelFloodednessNoise()
        public int nnSpread        = -1;  // NoiseRouter.fluidLevelSpreadNoise()
        public int nnLava          = -1;  // NoiseRouter.lavaNoise()

        // Ore vein noise indices (RouterField ordinals 12–14)
        public int nnVeinToggle    = -1;  // NoiseRouter.veinToggle()
        public int nnVeinRidged    = -1;  // NoiseRouter.veinRidged()
        public int nnVeinGap       = -1;  // NoiseRouter.veinGap()

        public void uploadToGPU() {
            LOGGER.info("SSBO upload requested (not yet implemented)");
        }
    }
}
