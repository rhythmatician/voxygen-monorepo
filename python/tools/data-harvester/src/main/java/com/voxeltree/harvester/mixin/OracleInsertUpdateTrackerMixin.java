package com.voxeltree.harvester.mixin;

import com.voxeltree.harvester.ingest.IngestClientHandler;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * True Voxy completion barrier for oracle ingest: counts WorldUpdater.insertUpdate RETURN,
 * not rawIngest enqueue. Ack only after every expected insert has returned.
 * Uses correct Voxy types with remap=false for Intermediary-free Voxy classes.
 */
@Mixin(targets = "me.cortex.voxy.common.world.WorldUpdater", remap = false)
public class OracleInsertUpdateTrackerMixin {

    @Inject(method = "insertUpdate", at = @At("RETURN"), remap = false)
    private static void onInsertUpdateReturn(WorldEngine worldEngine, VoxelizedSection section, CallbackInfo ci) {
        try {
            IngestClientHandler.notifyInsertUpdateReturn(section.x, section.y, section.z);
        } catch (Exception ignored) {
        }
    }
}
