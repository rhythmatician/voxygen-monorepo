package net.lodiffusion.shadow;

import com.rhythmatician.lodiffusion.HelloTerrainMod;

import java.lang.reflect.Method;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Decodes Voxy's packed WorldSection long key to level/x/y/z coordinates.
 *
 * Uses Voxy's own static WorldEngine decoders via reflection so we do not
 * duplicate bit-pack assumptions across versions.
 */
public final class VoxyWorldSectionKeyDecoder {

    public record DecodedSectionKey(int level, int x, int y, int z) {}

    private static volatile boolean initialized;
    private static Method getLevelMethod;
    private static Method getXMethod;
    private static Method getYMethod;
    private static Method getZMethod;
    private static final AtomicBoolean loggedInitFailure = new AtomicBoolean(false);

    private VoxyWorldSectionKeyDecoder() {
    }

    public static DecodedSectionKey decode(long worldSectionKey) {
        ensureInitialized();
        if (getLevelMethod == null || getXMethod == null || getYMethod == null || getZMethod == null) {
            return null;
        }

        try {
            int level = (Integer) getLevelMethod.invoke(null, worldSectionKey);
            int x = (Integer) getXMethod.invoke(null, worldSectionKey);
            int y = (Integer) getYMethod.invoke(null, worldSectionKey);
            int z = (Integer) getZMethod.invoke(null, worldSectionKey);
            return new DecodedSectionKey(level, x, y, z);
        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[LodGen][Bridge] Failed decoding section key: {}", e.toString());
            return null;
        }
    }

    private static void ensureInitialized() {
        if (initialized) {
            return;
        }

        synchronized (VoxyWorldSectionKeyDecoder.class) {
            if (initialized) {
                return;
            }

            try {
                Class<?> worldEngineClass = Class.forName("me.cortex.voxy.common.world.WorldEngine");
                getLevelMethod = worldEngineClass.getMethod("getLevel", long.class);
                getXMethod = worldEngineClass.getMethod("getX", long.class);
                getYMethod = worldEngineClass.getMethod("getY", long.class);
                getZMethod = worldEngineClass.getMethod("getZ", long.class);
            } catch (Exception e) {
                if (loggedInitFailure.compareAndSet(false, true)) {
                    HelloTerrainMod.LOGGER.warn(
                            "[LodGen][Bridge] WorldEngine key decoder unavailable: {}",
                            e.toString());
                }
            }

            initialized = true;
        }
    }
}
