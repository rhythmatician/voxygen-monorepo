package com.voxeltree.harvester;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Handles automatic connection and reconnection to the target server.
 */
public class AutoConnectHandler {

    private static final Logger LOGGER = LoggerFactory.getLogger("dataharvester");

    private final HarvesterConfig config;
    private boolean firstConnectTriggered = false;
    private int titleScreenTickCount = 0;
    private boolean connecting = false;
    private int reconnectTickCounter = 0;
    private boolean hasConnectedOnce = false;

    public AutoConnectHandler(HarvesterConfig config) {
        this.config = config;
    }

    public void onClientTick(Minecraft client) {
        if (client.screen == null) {
            if (!hasConnectedOnce) {
                hasConnectedOnce = true;
                LOGGER.info("[DataHarvester] Successfully connected to {}!", config.serverAddress);
            }
            connecting = false;
            reconnectTickCounter = 0;
            return;
        }

        if (!firstConnectTriggered && client.screen instanceof TitleScreen) {
            titleScreenTickCount++;
            int delayTicks = config.autoConnectDelaySec * 20;
            if (titleScreenTickCount >= delayTicks) {
                LOGGER.info("[DataHarvester] Title screen ready. Connecting to {}...",
                        config.serverAddress);
                connect(client);
                firstConnectTriggered = true;
            }
            return;
        }

        if (config.reconnectOnDisconnect
                && firstConnectTriggered
                && !connecting
                && client.screen instanceof DisconnectedScreen) {

            reconnectTickCounter++;
            int delayTicks = config.reconnectDelaySec * 20;
            if (reconnectTickCounter >= delayTicks) {
                LOGGER.info("[DataHarvester] Disconnected. Reconnecting to {}...",
                        config.serverAddress);
                reconnectTickCounter = 0;
                // FIX: Do not call client.disconnect() when already on DisconnectedScreen;
                // it tries to return to in-game GUI and throws IllegalStateException.
                // Directly start a new connection from the DisconnectedScreen.
                connect(client);
            } else if (reconnectTickCounter == 1) {
                LOGGER.info("[DataHarvester] Disconnected. Will reconnect in {}s.",
                        config.reconnectDelaySec);
            }
        }
    }

    private void connect(Minecraft client) {
        connecting = true;
        ServerAddress address = ServerAddress.parseString(config.serverAddress);
        ServerData serverData = new ServerData(
                "DataHarvester Target",
                config.serverAddress,
                ServerData.Type.OTHER
        );
        ConnectScreen.startConnecting(
                new TitleScreen(),
                client,
                address,
                serverData,
                false,
                null
        );
    }
}
