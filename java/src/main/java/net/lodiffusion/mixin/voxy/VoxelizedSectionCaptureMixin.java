package net.lodiffusion.mixin.voxy;

import com.rhythmatician.lodiffusion.voxy.VoxyProcessingAPI;
import com.rhythmatician.lodiffusion.voxy.VoxelizedSectionSnapshot;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Fabric Mixin for VoxelizedSection data capture.
 *
 * <p>Intercepts {@code WorldUpdater.insertUpdate(WorldEngine, VoxelizedSection)} to capture
 * VoxelizedSection data after mipping but before insertion into Voxy's world engine.
 *
 * <p>This allows the LODiffusion dataset generation system to collect training data without
 * requiring a player to be nearby, by hooking into Voxy's data processing pipeline directly.
 *
 * <p>The captured snapshot includes:
 * <ul>
 *   <li>The voxel data array (long[] with all 5 LOD levels)</li>
 *   <li>Chunk coordinates (cx, cy, cz)</li>
 *   <li>World identifier</li>
 *   <li>Capture timestamp</li>
 * </ul>
 */
@Mixin(targets = "me.cortex.voxy.common.world.WorldUpdater")
public class VoxelizedSectionCaptureMixin {

    /**
     * Intercept insertUpdate to capture VoxelizedSection data.
     *
     * <p>This injection runs at the HEAD of {@code WorldUpdater.insertUpdate()}, which means it
     * executes before any modifications to the section or world state. This is the ideal point
     * to capture data, since:
     * <ul>
     *   <li>The VoxelizedSection has been fully mipped (all LOD levels present)</li>
     *   <li>Data has not yet been compressed or serialized</li>
     *   <li>The raw voxel encoding (block ID + biome ID + light) is still accessible</li>
     * </ul>
     *
     * @param worldEngine The target WorldEngine being updated
     * @param section The VoxelizedSection being inserted (contains mipped LOD data)
     * @param ci Callback info for injection
     */
    @Inject(
        method = "insertUpdate",
        at = @At("HEAD"),
        cancellable = false
    )
    private static void captureSection(WorldEngine worldEngine, VoxelizedSection section, CallbackInfo ci) {
        try {
            // Only capture if there are active listeners
            if (VoxyProcessingAPI.getListenerCount() == 0) {
                return;
            }

            // Extract coordinates and data from VoxelizedSection
            VoxelizedSectionSnapshot snapshot = extractSnapshot(section, worldEngine);
            if (snapshot != null) {
                VoxyProcessingAPI.fireCaptureSectionCallbacks(snapshot);
            }
        } catch (Exception e) {
            // Log but never throw — we don't want to disrupt Voxy's normal operation
            org.slf4j.LoggerFactory.getLogger(VoxelizedSectionCaptureMixin.class)
                    .warn("Error capturing VoxelizedSection", e);
        }
    }

    /**
     * Extract snapshot data from VoxelizedSection and WorldEngine objects using reflection.
     *
     * <p>We use reflection because:
     * <ul>
     *   <li>Voxy is a compile-time dependency, not a decompiled client-mapped dependency</li>
     *   <li>Field names use obfuscated names at runtime</li>
     *   <li>This keeps the dependency optional and graceful if Voxy is absent</li>
     * </ul>
     *
     * @param section The VoxelizedSection object
     * @param worldEngine The WorldEngine object (for world ID mapping)
     * @return A VoxelizedSectionSnapshot, or null if extraction fails
     */
    private static VoxelizedSectionSnapshot extractSnapshot(VoxelizedSection section, WorldEngine worldEngine) {
        try {
            // Get VoxelizedSection fields: x, y, z, section (long[])
            int cx = (int) getField(section, "x");
            int cy = (int) getField(section, "y");
            int cz = (int) getField(section, "z");
            long[] sectionData = (long[]) getField(section, "section");

            // Get world identifier from WorldEngine
            // WorldEngine has a reference to the world, which we can use to derive a world ID
            String worldId = deriveWorldId(worldEngine);

            return new VoxelizedSectionSnapshot(cx, cy, cz, sectionData, worldId);
        } catch (Exception e) {
            org.slf4j.LoggerFactory.getLogger(VoxelizedSectionCaptureMixin.class)
                    .debug("Failed to extract VoxelizedSectionSnapshot", e);
            return null;
        }
    }

    /**
     * Derive a world identifier string from the WorldEngine.
     *
     * <p>Attempts to extract meaningful world identification info. If this fails, returns a
     * generic ID.
     *
     * @param worldEngine The WorldEngine object
     * @return A world identifier string (e.g., "minecraft:overworld" or "unknown")
     */
    private static String deriveWorldId(WorldEngine worldEngine) {
        try {
            // Try to get the worldId field if it exists in WorldEngine
            Object worldIdObj = getField(worldEngine, "worldId");
            if (worldIdObj != null) {
                // WorldIdentifier has toString() that should give us a useful ID
                return worldIdObj.toString();
            }
        } catch (Exception e) {
            // Fall through to default
        }
        return "unknown";
    }

    /**
     * Utility to get a field value via reflection, trying common field name patterns.
     *
     * @param obj The object to read from
     * @param fieldName The field name to attempt to read
     * @return The field value, or null if not found
     * @throws IllegalAccessException if the field exists but cannot be accessed
     */
    private static Object getField(Object obj, String fieldName) throws IllegalAccessException {
        if (obj == null) {
            return null;
        }

        Class<?> cls = obj.getClass();
        // Try the field name directly first
        try {
            java.lang.reflect.Field field = cls.getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(obj);
        } catch (NoSuchFieldException e) {
            // Try with underscore prefix (common in Java obfuscation)
            try {
                java.lang.reflect.Field field = cls.getDeclaredField("_" + fieldName);
                field.setAccessible(true);
                return field.get(obj);
            } catch (NoSuchFieldException e2) {
                // Try with "m" prefix (another common pattern)
                try {
                    java.lang.reflect.Field field = cls.getDeclaredField("m" + fieldName);
                    field.setAccessible(true);
                    return field.get(obj);
                } catch (NoSuchFieldException e3) {
                    // Field not found with any pattern
                    return null;
                }
            }
        }
    }
}
