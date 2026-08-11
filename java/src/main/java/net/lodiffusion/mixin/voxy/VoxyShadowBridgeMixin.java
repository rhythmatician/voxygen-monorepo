package net.lodiffusion.mixin.voxy;

import com.rhythmatician.lodiffusion.HelloTerrainMod;
import net.lodiffusion.shadow.ShadowRouterJobQueue;
import net.lodiffusion.shadow.VoxyRequestDecoder;
import net.lodiffusion.shadow.VoxyWorldSectionKeyDecoder;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * Bridges Voxy's section-watch demand into LODiffusion generation jobs.
 *
 * In Voxy 0.2.11-alpha, SectionUpdateRouter.watch(JI) is called when NodeManager
 * requests section data (e.g. leaf child requests). Hooking this method gives a
 * direct signal for missing/needed sections without relying on GPU request-buffer
 * callbacks that can stay empty for unseen hierarchy.
 */
@Mixin(targets = "me.cortex.voxy.client.core.rendering.SectionUpdateRouter")
public class VoxyShadowBridgeMixin {

    private static final int WATCH_FLAG_INITIAL_MESH = 0x1;
    private static final long BRIDGE_LOG_INTERVAL_MS = 5000L;

    private static long lastBridgeLogMs;
    private static int bridgeWatchAdds;
    private static int bridgeQueued;

    /**
     * Intercept SectionUpdateRouter.watch(JI) after Voxy has accepted the new watch.
     */
    @Inject(
        method = "watch(JI)Z",
        at = @At("RETURN"),
        cancellable = false
    )
    private void interceptWatch(long worldSectionKey,
                                int watchFlags,
                                CallbackInfoReturnable<Boolean> cir) {
        try {
            if (!Boolean.TRUE.equals(cir.getReturnValue())) {
                return;
            }

            if ((watchFlags & WATCH_FLAG_INITIAL_MESH) == 0) {
                return;
            }

            VoxyWorldSectionKeyDecoder.DecodedSectionKey decoded =
                    VoxyWorldSectionKeyDecoder.decode(worldSectionKey);
            if (decoded == null) {
                return;
            }

            if (decoded.level() < 0 || decoded.level() > 4) {
                return;
            }

            VoxyRequestDecoder.VoxyNodeRequest req = new VoxyRequestDecoder.VoxyNodeRequest();
            req.lodLevel = decoded.level();
            req.worldX = decoded.x();
            req.worldY = decoded.y();
            req.worldZ = decoded.z();

            ShadowRouterJobQueue.enqueue(req);
            logBridgeProgress(1);

        } catch (Exception e) {
            HelloTerrainMod.LOGGER.warn("[LodGen][Bridge] watch hook failed: {}", e.toString());
        }
    }

    private static void logBridgeProgress(int queuedCount) {
        bridgeWatchAdds++;
        bridgeQueued += queuedCount;

        long now = System.currentTimeMillis();
        if (bridgeWatchAdds == 1 || now - lastBridgeLogMs >= BRIDGE_LOG_INTERVAL_MS) {
            HelloTerrainMod.LOGGER.info(
                    "[LodGen][Bridge] watchAdds={} queued={} queuedDepth={} inFlight={}",
                    bridgeWatchAdds,
                    bridgeQueued,
                    ShadowRouterJobQueue.size(),
                    ShadowRouterJobQueue.inFlightSize());
            lastBridgeLogMs = now;
        }
    }
}
