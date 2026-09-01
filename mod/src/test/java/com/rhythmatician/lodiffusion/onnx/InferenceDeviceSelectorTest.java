package com.rhythmatician.lodiffusion.onnx;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import com.rhythmatician.lodiffusion.Config;
import com.rhythmatician.lodiffusion.onnx.InferenceDeviceSelector.Provider;

/**
 * Unit tests for {@link InferenceDeviceSelector}.
 *
 * <p>These tests exercise provider selection logic and config parsing
 * without requiring ONNX models, DJL, or any GPU hardware.
 */
@Tag("ci")
class InferenceDeviceSelectorTest {

    @BeforeEach
    void clearCache() {
        InferenceDeviceSelector.resetForTesting();
    }

    @AfterEach
    void restoreConfig() {
        // Restore default so other tests are not affected
        Config.setInferenceDevice("auto");
        InferenceDeviceSelector.resetForTesting();
    }

    // ── Provider enum ───────────────────────────────────────────────────

    @Test
    void allProvidersHaveNonNullDjlOptionValue() {
        for (Provider p : Provider.values()) {
            assertNotNull(p.djlOptionValue(),
                    "djlOptionValue() must never be null for " + p);
        }
    }

    @Test
    void cpuProviderHasEmptyDjlOptionValue() {
        // CPU provider uses no special ortDevice option
        assertTrue(Provider.CPU.djlOptionValue().isEmpty(),
                "CPU provider should use empty djlOptionValue");
    }

    @Test
    void directmlProviderDjlOptionValue() {
        assertEquals("DirectML", Provider.DIRECTML.djlOptionValue());
    }

    @Test
    void openvinoProviderDjlOptionValue() {
        assertEquals("OpenVINO", Provider.OPENVINO.djlOptionValue());
    }

    // ── Provider.fromString ─────────────────────────────────────────────

    @Test
    void fromString_recognisesLowerCase() {
        assertEquals(Provider.DIRECTML, Provider.fromString("directml"));
        assertEquals(Provider.OPENVINO, Provider.fromString("openvino"));
        assertEquals(Provider.CPU,      Provider.fromString("cpu"));
    }

    @Test
    void fromString_recognisesUpperCase() {
        assertEquals(Provider.DIRECTML, Provider.fromString("DIRECTML"));
        assertEquals(Provider.OPENVINO, Provider.fromString("OPENVINO"));
        assertEquals(Provider.CPU,      Provider.fromString("CPU"));
    }

    @Test
    void fromString_recognisesDjlOptionValue() {
        // Users may type the DJL option string directly in config
        assertEquals(Provider.DIRECTML, Provider.fromString("DirectML"));
        assertEquals(Provider.OPENVINO, Provider.fromString("OpenVINO"));
    }

    @Test
    void fromString_returnsNullForUnknown() {
        // "cuda" is not a recognised value — must return null
        assertNull(Provider.fromString("cuda"),
                "fromString(\"cuda\") should return null");
    }

    @Test
    void fromString_returnsNullForNullInput() {
        // fromString(null) must not throw, and must return null
        assertNull(Provider.fromString(null),
                "fromString(null) should return null");
    }

    // ── selectProvider with config override ────────────────────────────

    @Test
    void selectProvider_configOverride_directml() {
        Config.setInferenceDevice("directml");
        assertEquals(Provider.DIRECTML, InferenceDeviceSelector.selectProvider());
    }

    @Test
    void selectProvider_configOverride_openvino() {
        Config.setInferenceDevice("openvino");
        assertEquals(Provider.OPENVINO, InferenceDeviceSelector.selectProvider());
    }

    @Test
    void selectProvider_configOverride_cpu() {
        Config.setInferenceDevice("cpu");
        assertEquals(Provider.CPU, InferenceDeviceSelector.selectProvider());
    }

    @Test
    void selectProvider_configOverride_caseInsensitive() {
        Config.setInferenceDevice("DirectML");
        assertEquals(Provider.DIRECTML, InferenceDeviceSelector.selectProvider());
    }

    @Test
    void selectProvider_unknownConfigValue_fallsBackToAutoDetect() {
        Config.setInferenceDevice("cuda");
        // Unknown value → auto-detect (should not throw)
        Provider result = InferenceDeviceSelector.selectProvider();
        assertNotNull(result, "selectProvider() should never return null");
        // On CI (Linux) the auto-detect result will be CPU
        assertTrue(result == Provider.CPU || result == Provider.DIRECTML,
                "Auto-detect should return CPU or DirectML, got: " + result);
    }

    // ── selectProvider caching ──────────────────────────────────────────

    @Test
    void selectProvider_returnsSameInstanceOnRepeatedCalls() {
        Config.setInferenceDevice("cpu");
        Provider first  = InferenceDeviceSelector.selectProvider();
        Provider second = InferenceDeviceSelector.selectProvider();
        assertEquals(first, second, "Cached provider should be stable");
    }

    // ── Platform detection helpers ──────────────────────────────────────

    @Test
    void isDirectMLSupported_nonWindowsReturnsFalse() {
        String original = System.getProperty("os.name");
        try {
            System.setProperty("os.name", "Linux");
            assertFalse(InferenceDeviceSelector.isDirectMLSupportedOnCurrentPlatform(),
                    "DirectML should not be supported on Linux");
        } finally {
            System.setProperty("os.name", original);
        }
    }

    @Test
    void isDirectMLSupported_windows10ReturnsTrue() {
        String origName    = System.getProperty("os.name");
        String origVersion = System.getProperty("os.version");
        try {
            System.setProperty("os.name", "Windows 10");
            System.setProperty("os.version", "10.0");
            assertTrue(InferenceDeviceSelector.isDirectMLSupportedOnCurrentPlatform(),
                    "DirectML should be supported on Windows 10");
        } finally {
            System.setProperty("os.name", origName);
            System.setProperty("os.version", origVersion);
        }
    }

    @Test
    void isDirectMLSupported_windows11ReturnsTrue() {
        String origName    = System.getProperty("os.name");
        String origVersion = System.getProperty("os.version");
        try {
            System.setProperty("os.name", "Windows 11");
            System.setProperty("os.version", "10.0");
            assertTrue(InferenceDeviceSelector.isDirectMLSupportedOnCurrentPlatform(),
                    "DirectML should be supported on Windows 11");
        } finally {
            System.setProperty("os.name", origName);
            System.setProperty("os.version", origVersion);
        }
    }

    @Test
    void isDirectMLSupported_windows7ReturnsFalse() {
        String origName    = System.getProperty("os.name");
        String origVersion = System.getProperty("os.version");
        try {
            System.setProperty("os.name", "Windows 7");
            System.setProperty("os.version", "6.1");
            assertFalse(InferenceDeviceSelector.isDirectMLSupportedOnCurrentPlatform(),
                    "DirectML should not be supported on Windows 7 (no DX12)");
        } finally {
            System.setProperty("os.name", origName);
            System.setProperty("os.version", origVersion);
        }
    }

    @Test
    void isDirectMLSupported_macosReturnsFalse() {
        String origName    = System.getProperty("os.name");
        String origVersion = System.getProperty("os.version");
        try {
            System.setProperty("os.name", "Mac OS X");
            System.setProperty("os.version", "14.0");
            assertFalse(InferenceDeviceSelector.isDirectMLSupportedOnCurrentPlatform(),
                    "DirectML should not be supported on macOS");
        } finally {
            System.setProperty("os.name", origName);
            System.setProperty("os.version", origVersion);
        }
    }

    // ── Auto-detect on current CI platform ─────────────────────────────

    @Test
    void selectProvider_autoOnCiDoesNotThrow() {
        Config.setInferenceDevice("auto");
        // This test runs on Linux CI — should select CPU without error
        Provider provider = InferenceDeviceSelector.selectProvider();
        assertNotNull(provider);
    }
}
