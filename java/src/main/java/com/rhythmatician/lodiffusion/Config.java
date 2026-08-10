package com.rhythmatician.lodiffusion;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Lightweight runtime config loader with overlay semantics.
 * Base: classpath /lodiffusion.defaults.json
 * Overlay: config/lodiffusion/runtime.json (created lazily on first write)
 */
public final class Config {
  private static final Gson GSON = new Gson();
  private static final AtomicReference<JsonObject> CACHED = new AtomicReference<>();
  private static final Path CONFIG_DIR = Paths.get("config", "lodiffusion");
  private static final Path RUNTIME_FILE = CONFIG_DIR.resolve("runtime.json");
  
  /** Classpath resource path to the default configuration JSON file */
  private static final String DEFAULTS_RESOURCE = "/lodiffusion.defaults.json";

  private Config() {}

  private static JsonObject loadDefaults() {
    try (InputStream in = Config.class.getResourceAsStream(DEFAULTS_RESOURCE)) {
      if (in == null) {
        return new JsonObject();
      }
      try (Reader r = new BufferedReader(new java.io.InputStreamReader(in, StandardCharsets.UTF_8))) {
        return JsonParser.parseReader(r).getAsJsonObject();
      }
    } catch (IOException e) {
      return new JsonObject();
    }
  }

  private static JsonObject loadRuntime() {
    if (Files.isRegularFile(RUNTIME_FILE)) {
      try (Reader r = Files.newBufferedReader(RUNTIME_FILE, StandardCharsets.UTF_8)) {
        JsonElement el = JsonParser.parseReader(r);
        if (el.isJsonObject()) return el.getAsJsonObject();
      } catch (IOException ignored) {}
    }
    return new JsonObject();
  }

  private static JsonObject merged() {
    JsonObject cached = CACHED.get();
    if (cached != null) return cached;
    JsonObject base = loadDefaults();
    JsonObject overlay = loadRuntime();
    // shallow merge
    for (String k : overlay.keySet()) {
      base.add(k, overlay.get(k));
    }
    CACHED.compareAndSet(null, base);
    return CACHED.get();
  }

  public static boolean useOnnxTerrain() {
    return getBoolean("useOnnxTerrain", true);
  }

  public static Path modelPath() {
    return Paths.get(getString("modelPath", "config/lodiffusion/model.onnx"));
  }

  /**
   * Directory containing the 4 progressive ONNX models exported by export_lod.py.
   * Defaults to the parent of {@link #modelPath()} (i.e. {@code config/lodiffusion/}).
   */
  public static java.nio.file.Path modelDir() {
    String raw = getString("modelDir", "");
    if (!raw.isBlank()) return Paths.get(raw);
    java.nio.file.Path parent = modelPath().getParent();
    return parent != null ? parent : Paths.get("config", "lodiffusion");
  }

  public static String adapter() {
    return getString("adapter", "unified_v1");
  }

  public static int inferenceThreads() {
    int raw = getInt("inferenceThreads", 2);
    int max = Runtime.getRuntime().availableProcessors();
    if (raw < 1) raw = 1;
    if (raw > max) raw = max;
    return raw;
  }

  public static double threshold() {
    double t = getDouble("threshold", 0.5);
    if (t < 0.0) t = 0.0;
    if (t > 1.0) t = 1.0;
    return t;
  }

  private static JsonObject getDebugObject() {
    JsonObject merged = merged();
    return merged.has("debug") && merged.get("debug").isJsonObject() ? merged.getAsJsonObject("debug") : null;
  }

  public static boolean logTimings() {
    JsonObject debug = getDebugObject();
    if (debug == null) return false;
    return debug.has("logTimings") && debug.get("logTimings").getAsBoolean();
  }

  public static Optional<Path> metricsCsv() {
    JsonObject debug = getDebugObject();
    if (debug == null) return Optional.empty();
    if (!debug.has("dumpCsv")) return Optional.empty();
    String v = debug.get("dumpCsv").getAsString();
    if (v == null || v.isBlank()) return Optional.empty();
    return Optional.of(Paths.get(v));
  }

  // Runtime mutation helpers (update overlay + cache)
  /**
   * ONNX execution provider preference.
   *
   * <p>Supported values: {@code "auto"} (default), {@code "directml"},
   * {@code "openvino"}, {@code "cpu"}.  {@code "auto"} selects the best
   * provider available on the current platform (DirectML on Windows 10+,
   * otherwise CPU).
   */
  public static String inferenceDevice() {
    return getString("inferenceDevice", "auto");
  }

  public static void setUseOnnxTerrain(boolean enabled) { setRuntime("useOnnxTerrain", enabled); }
  public static void setAdapter(String adapterId) { setRuntime("adapter", adapterId); }
  public static void setThreshold(double thr) { setRuntime("threshold", thr); }
  public static void setOccThreshold(double thr) { setRuntime("occThreshold", thr); }
  public static void setInferenceDevice(String device) { setRuntime("inferenceDevice", device); }

  /**
   * Set the debug.dumpCsv path in the runtime overlay (writes nested object).
   * Pass {@code null} or blank string to disable CSV output.
   */
  public static void setDebugDumpCsv(String csvPath) {
    try {
      Files.createDirectories(CONFIG_DIR);
      JsonObject overlay = loadRuntime();
      JsonObject debug = overlay.has("debug") && overlay.get("debug").isJsonObject()
          ? overlay.getAsJsonObject("debug") : new JsonObject();
      if (csvPath == null || csvPath.isBlank()) {
        debug.remove("dumpCsv");
      } else {
        debug.addProperty("dumpCsv", csvPath);
      }
      if (debug.size() > 0) {
        overlay.add("debug", debug);
      } else {
        overlay.remove("debug");
      }
      try (var w = Files.newBufferedWriter(RUNTIME_FILE, StandardCharsets.UTF_8)) {
        GSON.toJson(overlay, w);
      }
      CACHED.set(null);
    } catch (IOException ignored) {}
  }

  private static void setRuntime(String key, Object value) {
    try {
      Files.createDirectories(CONFIG_DIR);
      JsonObject overlay = loadRuntime();
      if (value instanceof Boolean) overlay.addProperty(key, (Boolean) value);
      else if (value instanceof Number) overlay.addProperty(key, (Number) value);
      else if (value instanceof String) overlay.addProperty(key, (String) value);
      try (var w = Files.newBufferedWriter(RUNTIME_FILE, StandardCharsets.UTF_8)) {
        GSON.toJson(overlay, w);
      }
      // Invalidate cache
      CACHED.set(null);
    } catch (IOException ignored) {}
  }

  private static String getString(String key, String def) {
    JsonObject o = merged();
    return o.has(key) ? o.get(key).getAsString() : def;
  }
  private static boolean getBoolean(String key, boolean def) {
    JsonObject o = merged();
    return o.has(key) ? o.get(key).getAsBoolean() : def;
  }
  public static int getInt(String key, int def) {
    JsonObject o = merged();
    return o.has(key) ? o.get(key).getAsInt() : def;
  }
  public static double getDouble(String key, double def) {
    JsonObject o = merged();
    return o.has(key) ? o.get(key).getAsDouble() : def;
  }
}
