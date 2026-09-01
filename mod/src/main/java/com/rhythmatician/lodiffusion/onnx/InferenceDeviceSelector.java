package com.rhythmatician.lodiffusion.onnx;

import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.rhythmatician.lodiffusion.Config;

/**
 * Selects the best available ONNX execution provider using a tiered
 * fallback strategy designed for broad hardware compatibility ("potato" PCs).
 *
 * <p>Priority order:
 * <ol>
 *   <li><b>DirectML</b> — DirectX 12-based; works on any modern Windows
 *       GPU (AMD, Intel, NVIDIA).  Requires no extra driver install.</li>
 *   <li><b>OpenVINO</b> — Intel-optimised; can be selected explicitly via
 *       config for Intel iGPU / CPU users.</li>
 *   <li><b>CPU</b> — always available; uses ONNX Runtime's optimised CPU
 *       kernels (AVX2/AVX512 where supported).</li>
 * </ol>
 *
 * <p>The provider is resolved once and cached.  Set {@code inferenceDevice}
 * in {@code config/lodiffusion/runtime.json} to override auto-detection:
 * <pre>
 *   { "inferenceDevice": "directml" }   // force DirectML
 *   { "inferenceDevice": "openvino" }   // force OpenVINO
 *   { "inferenceDevice": "cpu" }        // force CPU
 *   { "inferenceDevice": "auto" }       // auto-detect (default)
 * </pre>
 *
 * @see SparseOctreeModelRunner
 */
public final class InferenceDeviceSelector {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InferenceDeviceSelector.class);

    /** DX12 first shipped in Windows 10 (version string starts with "10."). */
    private static final int MIN_WINDOWS_MAJOR_FOR_DIRECTML = 10;

    /** Volatile so a second thread never sees a partially-constructed value. */
    private static volatile Provider cachedProvider;

    private InferenceDeviceSelector() {}

    // ------------------------------------------------------------------
    // Provider enum
    // ------------------------------------------------------------------

    /**
     * Supported ONNX execution providers, in priority order.
     *
     * <p>The {@link #djlOptionValue()} string is passed to
     * {@code Criteria.optOption("ortDevice", value)} when configuring the
     * DJL/ONNX Runtime session.  An empty string means "no special option"
     * (plain CPU provider).
     */
    public enum Provider {
        /** DirectML — DirectX 12 GPU acceleration (Windows 10+). */
        DIRECTML("DirectML"),
        /** OpenVINO — Intel CPU/iGPU acceleration (all platforms). */
        OPENVINO("OpenVINO"),
        /** CPU — ONNX Runtime default; always available. */
        CPU("");

        private final String djlOptionValue;

        Provider(String djlOptionValue) {
            this.djlOptionValue = djlOptionValue;
        }

        /**
         * Value to pass to {@code Criteria.optOption("ortDevice", ...)} for
         * this provider.  Empty string means no option should be added (CPU).
         */
        public String djlOptionValue() {
            return djlOptionValue;
        }

        /**
         * Parse a provider from a config string, case-insensitively.
         *
         * @param name provider name (e.g. {@code "directml"}, {@code "cpu"})
         * @return matching provider, or {@code null} if not recognised
         */
        public static Provider fromString(String name) {
            if (name == null) return null;
            for (Provider p : values()) {
                if (p.name().equalsIgnoreCase(name)
                        || p.djlOptionValue().equalsIgnoreCase(name)) {
                    return p;
                }
            }
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Returns the best execution provider available on this machine.
     *
     * <p>Result is computed once and cached for the lifetime of the JVM.
     * Call {@link #resetForTesting()} to clear the cache in unit tests.
     *
     * @return selected provider (never {@code null})
     */
    public static Provider selectProvider() {
        Provider cached = cachedProvider;
        if (cached != null) return cached;

        Provider selected = resolveProvider();
        cachedProvider = selected;
        return selected;
    }

    /**
     * Clears the cached provider selection.
     *
     * <p>Intended for unit tests that need to exercise auto-detection
     * with different system properties.
     */
    static void resetForTesting() {
        cachedProvider = null;
    }

    // ------------------------------------------------------------------
    // Detection helpers (package-private for testability)
    // ------------------------------------------------------------------

    private static Provider resolveProvider() {
        // 1. Honour explicit config override
        String pref = Config.inferenceDevice();
        if (!"auto".equalsIgnoreCase(pref)) {
            Provider explicit = Provider.fromString(pref);
            if (explicit != null) {
                LOGGER.info("[InferenceDeviceSelector] Using configured provider: {}", explicit);
                return explicit;
            }
            LOGGER.warn("[InferenceDeviceSelector] Unknown inferenceDevice '{}' — "
                    + "falling back to auto-detection", pref);
        }

        // 2. Auto-detect
        if (isDirectMLSupportedOnCurrentPlatform()) {
            LOGGER.info("[InferenceDeviceSelector] Auto-selected DirectML "
                    + "(Windows 10+ detected)");
            return Provider.DIRECTML;
        }
        // OpenVINO is not auto-selected; must be opted in via config.
        // This avoids loading native OpenVINO DLLs/SOs unexpectedly.
        LOGGER.info("[InferenceDeviceSelector] Auto-selected CPU provider "
                + "(non-Windows or pre-DX12 hardware)");
        return Provider.CPU;
    }

    /**
     * Returns {@code true} if the current platform can reasonably use
     * DirectML (Windows 10 or later, which ships with DirectX 12).
     */
    static boolean isDirectMLSupportedOnCurrentPlatform() {
        String osName = System.getProperty("os.name", "");
        if (!osName.toLowerCase(Locale.ROOT).contains("windows")) {
            return false;
        }
        // DirectX 12 requires Windows 10 (os.version starts with "10.") or later.
        String osVersion = System.getProperty("os.version", "");
        try {
            String majorStr = osVersion.split("\\.")[0];
            int major = Integer.parseInt(majorStr);
            return major >= MIN_WINDOWS_MAJOR_FOR_DIRECTML;
        } catch (NumberFormatException | ArrayIndexOutOfBoundsException e) {
            // Cannot determine — conservatively skip DirectML
            return false;
        }
    }
}
