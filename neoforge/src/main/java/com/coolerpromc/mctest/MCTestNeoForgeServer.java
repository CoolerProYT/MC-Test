package com.coolerpromc.mctest;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStartingEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.neoforge.event.tick.ServerTickEvent;

@Mod(value = Constants.MOD_ID, dist = Dist.DEDICATED_SERVER)
public class MCTestNeoForgeServer {

    public MCTestNeoForgeServer(IEventBus modBus) {
        MCTestServer.init();
        NeoForge.EVENT_BUS.addListener(ServerStartingEvent.class, event -> MCTestServer.onServerStarting(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStartedEvent.class, event -> MCTestServer.onServerStarted(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerStoppingEvent.class, event -> MCTestServer.onServerStopping(event.getServer()));
        NeoForge.EVENT_BUS.addListener(ServerTickEvent.Post.class, event -> MCTestServer.onServerTick(event.getServer()));
    }
}
