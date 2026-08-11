package com.rhythmatician.lodiffusion.gpu;

import com.rhythmatician.lodiffusion.voxy.BiomeMapping;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.lang.reflect.Method;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Reflects over a runtime {@code MultiNoiseBiomeSource} instance to extract the
 * full overworld biome parameter table and serialise it into a GPU-ready {@link FloatBuffer}.
 *
 * <h3>Palette layout (stride = {@value #ENTRY_STRIDE} floats per entry)</h3>
 * <pre>
 *   [0..1]   temperature   (min, max) — de-quantized (÷ 10 000)
 *   [2..3]   humidity      (min, max)
 *   [4..5]   continentalness (min, max)
 *   [6..7]   erosion       (min, max)
 *   [8..9]   depth         (min, max)
 *   [10..11] weirdness     (min, max)
 *   [12]     offset        (float, 7th dimension — query is always 0 in the shader)
 *   [13]     biome_id      (canonical 0–53 / 255, bit-cast to float via
 *                           {@code Float.intBitsToFloat(id)} so the shader can recover it
 *                           with {@code floatBitsToInt()})
 *   [14..15] pad
 * </pre>
 *
 * <p>All min/max values are de-quantized from vanilla's long representation
 * ({@code longVal / 10_000.0f}).
 */
public final class BiomePaletteSerializer {

    private static final Logger LOGGER = LogManager.getLogger();

    /** Floats per palette entry (stride). */
    public static final int ENTRY_STRIDE = 16;

    /** Vanilla quantisation factor: {@code float * 10000 → long}. */
    private static final float DEQUANT = 1.0f / 10_000.0f;

    private BiomePaletteSerializer() {}

    // ===========================================================================================
    // Public API
    // ===========================================================================================

    /**
     * Extracts and serialises the biome parameter table from a live
     * {@code MultiNoiseBiomeSource} instance.
     *
     * <p>Uses reflection so the code compiles and runs independently of the exact
     * Yarn/Mojmap class name in use.  Returns an empty buffer (entry count 0) if
     * reflection fails — the shader will skip GPU biome classification gracefully.
     *
     * @param biomeSource a runtime instance of {@code MultiNoiseBiomeSource}
     *                    (or any biome source exposing a {@code parameters()} accessor)
     * @return serialised palette; position = 0, limit = entryCount × {@value #ENTRY_STRIDE}
     */
    public static FloatBuffer buildPalette(Object biomeSource) {
        if (biomeSource == null) {
            LOGGER.warn("BiomePaletteSerializer: biomeSource is null — returning empty palette");
            return emptyPalette();
        }

        try {
            List<float[]> entries = extractEntries(biomeSource);
            return serialise(entries);
        } catch (Exception e) {
            LOGGER.error("BiomePaletteSerializer: extraction failed — using empty palette", e);
            return emptyPalette();
        }
    }

    // ===========================================================================================
    // Reflection-driven extraction
    // ===========================================================================================

    /**
     * Extracts raw biome parameter entries via reflection.
     *
     * @return list of float[16] arrays (one per entry, in palette layout)
     */
    private static List<float[]> extractEntries(Object biomeSource) throws Exception {
        // Step 1: parameters() → ParameterList (or MultiNoiseUtil.ParameterList)
        Method parametersMethod = findMethod(biomeSource.getClass(), "parameters");
        if (parametersMethod == null) {
            LOGGER.warn("BiomePaletteSerializer: cannot find parameters() on {}",
                    biomeSource.getClass().getName());
            return List.of();
        }
        Object paramList = parametersMethod.invoke(biomeSource);
        if (paramList == null) {
            LOGGER.warn("BiomePaletteSerializer: parameters() returned null");
            return List.of();
        }

        // Step 2: Get the underlying List<Pair<ParameterPoint, Holder>>
        // Try common accessor names: entries(), getEntries(), values()
        List<?> rawEntries = invokeListGetter(paramList, "entries", "getEntries", "values");
        if (rawEntries == null || rawEntries.isEmpty()) {
            // Fall back: try to access it as a field named "entries" or "parameters"
            rawEntries = getListField(paramList, "entries", "parameters");
        }
        if (rawEntries == null) {
            LOGGER.warn("BiomePaletteSerializer: cannot access entries from {}", paramList.getClass().getName());
            return List.of();
        }

        LOGGER.info("BiomePaletteSerializer: found {} biome parameter entries", rawEntries.size());

        List<float[]> entries = new ArrayList<>(rawEntries.size());
        for (Object pairObj : rawEntries) {
            float[] entry = extractEntry(pairObj);
            if (entry != null) entries.add(entry);
        }

        LOGGER.info("BiomePaletteSerializer: serialised {} valid entries", entries.size());
        return entries;
    }

    /**
     * Extracts a single float[16] palette entry from a {@code Pair<ParameterPoint, RegistryEntry>}.
     */
    private static float[] extractEntry(Object pairObj) {
        try {
            // Pair.getFirst() → ParameterPoint (NoiseHypercube in Yarn)
            Object paramPoint = invokeGetter(pairObj, "getFirst", "first", "left", "key");
            // Pair.getSecond() → Holder / RegistryEntry
            Object biomeHolder = invokeGetter(pairObj, "getSecond", "second", "right", "value");
            if (paramPoint == null || biomeHolder == null) return null;

            // --- Extract the 6 Parameter fields + offset from the ParameterPoint ---
            // In Mojmap: temperature(), humidity(), continentalness(), erosion(), depth(), weirdness()
            // In Yarn: same names (these are record accessors)
            // Each returns a Parameter / ParameterRange with min() and max() returning long.
            float[] tRange = extractRange(paramPoint, "temperature");
            float[] hRange = extractRange(paramPoint, "humidity");
            float[] cRange = extractRange(paramPoint, "continentalness");
            float[] eRange = extractRange(paramPoint, "erosion");
            float[] dRange = extractRange(paramPoint, "depth");
            float[] wRange = extractRange(paramPoint, "weirdness");
            float   offset = extractOffset(paramPoint);

            if (tRange == null || hRange == null || cRange == null ||
                eRange == null || dRange == null || wRange == null) {
                return null;
            }

            // --- Resolve biome canonical ID ---
            int biomeId = resolveBiomeId(biomeHolder);

            float[] entry = new float[ENTRY_STRIDE];
            entry[0]  = tRange[0];
            entry[1]  = tRange[1];
            entry[2]  = hRange[0];
            entry[3]  = hRange[1];
            entry[4]  = cRange[0];
            entry[5]  = cRange[1];
            entry[6]  = eRange[0];
            entry[7]  = eRange[1];
            entry[8]  = dRange[0];
            entry[9]  = dRange[1];
            entry[10] = wRange[0];
            entry[11] = wRange[1];
            entry[12] = offset;
            entry[13] = Float.intBitsToFloat(biomeId); // bitcast — recovered in GLSL with floatBitsToInt()
            // [14..15] = 0.0 (pad)
            return entry;

        } catch (Exception e) {
            LOGGER.debug("BiomePaletteSerializer: failed to extract entry from {}", pairObj, e);
            return null;
        }
    }

    /**
     * Extracts [min, max] from a Parameter / ParameterRange accessor on the ParameterPoint.
     * Handles both long (quantized) and float (pre-quantized) representations.
     */
    private static float[] extractRange(Object paramPoint, String fieldName) throws Exception {
        Object param = invokeGetter(paramPoint, fieldName);
        if (param == null) return null;

        // Try min()/max() returning long (Mojmap / older Yarn)
        Long minLong = invokeLongGetter(param, "min", "minInclusive", "getMin");
        Long maxLong = invokeLongGetter(param, "max", "maxInclusive", "getMax");
        if (minLong != null && maxLong != null) {
            return new float[]{ minLong * DEQUANT, maxLong * DEQUANT };
        }

        // Try min()/max() returning float (newer Yarn that pre-normalises)
        Float minF = invokeFloatGetter(param, "min", "minInclusive", "getMin");
        Float maxF = invokeFloatGetter(param, "max", "maxInclusive", "getMax");
        if (minF != null && maxF != null) {
            return new float[]{ minF, maxF };
        }

        return null;
    }

    /**
     * Extracts the offset field from a ParameterPoint.
     * Returns 0.0 if the field cannot be found.
     */
    private static float extractOffset(Object paramPoint) {
        try {
            Long offsetLong = invokeLongGetter(paramPoint, "offset", "getOffset");
            if (offsetLong != null) return offsetLong * DEQUANT;

            Float offsetFloat = invokeFloatGetter(paramPoint, "offset", "getOffset");
            if (offsetFloat != null) return offsetFloat;
        } catch (Exception ignored) {}
        return 0.0f;
    }

    /**
     * Resolves a canonical biome ID from a Holder / RegistryEntry instance.
     */
    private static int resolveBiomeId(Object biomeHolder) {
        try {
            // Try getKey() → Optional<ResourceKey> → ResourceKey.location() → toString()
            Object keyOpt = invokeGetter(biomeHolder, "getKey", "key");
            Object key    = unwrapOptional(keyOpt);
            if (key == null) key = keyOpt; // might not be Optional

            if (key != null) {
                Object location = invokeGetter(key, "location", "getValue", "getLocation");
                if (location != null) {
                    String name = location.toString(); // e.g. "minecraft:plains"
                    return BiomeMapping.toCanonicalId(name);
                }
            }

            // Fallback: try registryKey().toString() directly
            String str = biomeHolder.toString();
            if (str.contains("minecraft:")) {
                int start = str.indexOf("minecraft:");
                int end   = Math.min(str.indexOf(']', start), str.indexOf(')', start));
                if (end < 0) end = str.length();
                return BiomeMapping.toCanonicalId(str.substring(start, end).trim());
            }
        } catch (Exception e) {
            LOGGER.debug("BiomePaletteSerializer: biome ID resolution failed for {}", biomeHolder, e);
        }
        return BiomeMapping.UNKNOWN_BIOME_ID;
    }

    // ===========================================================================================
    // Serialisation
    // ===========================================================================================

    private static FloatBuffer serialise(List<float[]> entries) {
        if (entries.isEmpty()) return emptyPalette();

        int totalFloats = entries.size() * ENTRY_STRIDE;
        FloatBuffer buf = ByteBuffer.allocateDirect(totalFloats * Float.BYTES)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        for (float[] entry : entries) {
            buf.put(entry);
        }
        buf.flip();
        return buf;
    }

    private static FloatBuffer emptyPalette() {
        return ByteBuffer.allocateDirect(0).asFloatBuffer();
    }

    // ===========================================================================================
    // Reflection helpers
    // ===========================================================================================

    /** Tries to invoke each name in order, returns first non-null result. */
    private static Object invokeGetter(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m != null) {
                    Object result = m.invoke(obj);
                    if (result != null) return result;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Like invokeGetter but casts to Long. */
    private static Long invokeLongGetter(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m != null && (m.getReturnType() == long.class || m.getReturnType() == Long.class)) {
                    return ((Number) m.invoke(obj)).longValue();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Like invokeGetter but casts to Float. */
    private static Float invokeFloatGetter(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m != null && (m.getReturnType() == float.class || m.getReturnType() == Float.class)) {
                    return ((Number) m.invoke(obj)).floatValue();
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Tries named getter methods returning List. */
    private static List<?> invokeListGetter(Object obj, String... names) {
        for (String name : names) {
            try {
                Method m = findMethod(obj.getClass(), name);
                if (m != null) {
                    Object result = m.invoke(obj);
                    if (result instanceof List<?> list && !list.isEmpty()) return list;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Tries named fields of type List. */
    private static List<?> getListField(Object obj, String... names) {
        for (String name : names) {
            try {
                java.lang.reflect.Field f = findField(obj.getClass(), name);
                if (f != null) {
                    f.setAccessible(true);
                    Object v = f.get(obj);
                    if (v instanceof List<?> list) return list;
                }
            } catch (Exception ignored) {}
        }
        return null;
    }

    /** Unwraps an Optional (if present), returning the value or null. */
    private static Object unwrapOptional(Object obj) {
        if (obj == null) return null;
        try {
            if (obj.getClass().getName().contains("Optional")) {
                Method m = obj.getClass().getMethod("orElse", Object.class);
                return m.invoke(obj, (Object) null);
            }
        } catch (Exception ignored) {}
        return obj;
    }

    /** Finds a public no-arg method by name on the class or any supertype. */
    private static Method findMethod(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            for (Method m : c.getMethods()) {
                if (m.getName().equals(name) && m.getParameterCount() == 0) {
                    return m;
                }
            }
        }
        return null;
    }

    /** Finds a field by name on the class or any supertype. */
    private static java.lang.reflect.Field findField(Class<?> clazz, String name) {
        for (Class<?> c = clazz; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {}
        }
        return null;
    }
}
