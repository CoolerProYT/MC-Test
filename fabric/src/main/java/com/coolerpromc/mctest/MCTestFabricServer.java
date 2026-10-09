package com.coolerpromc.mctest;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;

public class MCTestFabricServer implements DedicatedServerModInitializer {

    @Override
    public void onInitializeServer() {
        MCTestServer.init();
        ServerLifecycleEvents.SERVER_STARTING.register(MCTestServer::onServerStarting);
        ServerLifecycleEvents.SERVER_STARTED.register(MCTestServer::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(MCTestServer::onServerStopping);
        ServerTickEvents.END_SERVER_TICK.register(MCTestServer::onServerTick);
    }
}
