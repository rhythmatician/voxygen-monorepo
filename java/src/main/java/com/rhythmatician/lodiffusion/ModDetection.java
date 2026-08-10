package com.rhythmatician.lodiffusion;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Utility class for detecting companion mods at runtime.
 */
public class ModDetection {

    private static final String VOXY_MOD_ID = "voxy";

    /**
     * Checks if Voxy mod is loaded using Fabric's safe mod detection.
     *
     * @return true if Voxy is available, false otherwise
     */
    public static boolean isVoxyAvailable() {
        return FabricLoader.getInstance().isModLoaded(VOXY_MOD_ID);
    }

    /**
     * Gets information about the available LOD strategy.
     *
     * @return String describing the LOD strategy being used
     */
    public static String getLODStrategyInfo() {
        if (isVoxyAvailable()) {
            return "Voxy detected — AI-powered LOD injection available";
        } else {
            return "Voxy not detected — LOD generation disabled (install Voxy for AI-powered LODs)";
        }
    }
}
