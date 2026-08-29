package com.voxeltree.harvester.mixin;

import com.voxeltree.harvester.ingest.IngestClientHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * True Voxy completion barrier for oracle ingest: counts WorldUpdater.insertUpdate RETURN,
 * not rawIngest enqueue. Ack only after every expected insert has returned.
 */
@Mixin(targets = "me.cortex.voxy.common.world.WorldUpdater")
public class OracleInsertUpdateTrackerMixin {

    @Inject(method = "insertUpdate", at = @At("RETURN"))
    private static void onInsertUpdateReturn(Object worldEngine, Object section, CallbackInfo ci) {
        try {
            int cx = getIntField(section, "x");
            int cy = getIntField(section, "y");
            int cz = getIntField(section, "z");
            IngestClientHandler.notifyInsertUpdateReturn(cx, cy, cz);
        } catch (Exception ignored) {
        }
    }

    private static int getIntField(Object obj, String name) throws Exception {
        Class<?> c = obj.getClass();
        for (String cand : new String[]{name, "_" + name, "m" + name, "field_" + name}) {
            try {
                java.lang.reflect.Field f = c.getDeclaredField(cand);
                f.setAccessible(true);
                return f.getInt(obj);
            } catch (NoSuchFieldException ignored) {}
        }
        // Try any int field that looks like coordinate
        for (java.lang.reflect.Field f : c.getDeclaredFields()) {
            if (f.getType() == int.class) {
                f.setAccessible(true);
                // Heuristic: first three ints are x,y,z
                // We need to distinguish, so try ordered fields
            }
        }
        throw new NoSuchFieldException(name);
    }
}
