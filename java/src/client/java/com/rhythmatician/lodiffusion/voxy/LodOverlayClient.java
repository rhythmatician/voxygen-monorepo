package com.rhythmatician.lodiffusion.voxy;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandManager;
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayConnectionEvents;
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;

/** Installs the independent LOD overlay command and persistent legend. */
public final class LodOverlayClient implements ClientModInitializer {
    private static final Identifier HUD_ID = Identifier.of("lodiffusion", "lod_overlay_legend");

    @Override
    public void onInitializeClient() {
        ClientCommandRegistrationCallback.EVENT.register((dispatcher, registryAccess) -> dispatcher.register(
                ClientCommandManager.literal("voxygen")
                        .then(ClientCommandManager.literal("lod-overlay")
                                .executes(context -> {
                                    boolean enabled = LodOverlayState.toggle();
                                    context.getSource().sendFeedback(statusMessage(enabled));
                                    return 1;
                                }))));

        ClientPlayConnectionEvents.JOIN.register((handler, sender, client) -> {
            if (LodOverlayState.isEnabled() && client.player != null) {
                client.player.sendMessage(statusMessage(true), false);
            }
        });

        HudElementRegistry.addLast(HUD_ID, LodOverlayClient::renderLegend);
    }

    private static Text statusMessage(boolean enabled) {
        return Text.literal("Voxygen LOD overlay " + (enabled ? "ON" : "OFF")
                + ". " + LodOverlayState.legend());
    }

    private static void renderLegend(DrawContext context, RenderTickCounter tickCounter) {
        if (!LodOverlayState.isEnabled()) {
            return;
        }

        MinecraftClient client = MinecraftClient.getInstance();
        int x = 5;
        int y = 5;
        int width = 164;
        int lineHeight = 10;
        int height = 15 + LodOverlayState.palette().size() * lineHeight;
        context.fill(x, y, x + width, y + height, 0xB0000000);
        context.drawTextWithShadow(client.textRenderer, "VOXY ACTUAL LOD", x + 5, y + 4, 0xFFFFFFFF);

        int rowY = y + 15;
        for (LodOverlayState.PaletteEntry entry : LodOverlayState.palette()) {
            context.fill(x + 5, rowY + 1, x + 12, rowY + 8, entry.argb());
            context.drawTextWithShadow(client.textRenderer,
                    "L" + entry.lod() + "  " + entry.name(), x + 17, rowY, entry.argb());
            rowY += lineHeight;
        }
    }
}
