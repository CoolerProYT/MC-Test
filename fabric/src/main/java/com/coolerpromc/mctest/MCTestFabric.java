package com.coolerpromc.mctest;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;

public class MCTestFabric implements ClientModInitializer {

    @Override
    public void onInitializeClient() {
        MCTestClient.init();
        ClientTickEvents.END_CLIENT_TICK.register(MCTestClient::onClientTick);
    }
}
