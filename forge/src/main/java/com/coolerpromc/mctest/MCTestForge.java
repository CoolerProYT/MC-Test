package com.coolerpromc.mctest;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import net.minecraft.client.Minecraft;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.TickEvent;
import net.minecraftforge.event.server.ServerStartedEvent;
import net.minecraftforge.event.server.ServerStartingEvent;
import net.minecraftforge.event.server.ServerStoppingEvent;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.loading.FMLEnvironment;

/** Forge 1.20.1 has one {@code @Mod} class for both sides; client and dedicated-server setup are split here. */
@Mod(Constants.MOD_ID)
public class MCTestForge {

    public MCTestForge() {
        if (FMLEnvironment.dist == Dist.CLIENT) {
            Client.init();
        } else {
            initServer();
        }
    }

    private static void initServer() {
        MCTestServer.init();
        MinecraftForge.EVENT_BUS.addListener((ServerStartingEvent e) -> MCTestServer.onServerStarting(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStartedEvent e) -> MCTestServer.onServerStarted(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((ServerStoppingEvent e) -> MCTestServer.onServerStopping(e.getServer()));
        MinecraftForge.EVENT_BUS.addListener((TickEvent.ServerTickEvent e) -> {
            if (e.phase == TickEvent.Phase.END) MCTestServer.onServerTick(e.getServer());
        });
    }

    /** Kept in its own class so the dedicated server never loads client classes. */
    private static final class Client {
        static void init() {
            MCTestClient.init();
            MinecraftForge.EVENT_BUS.addListener((TickEvent.ClientTickEvent e) -> {
                if (e.phase == TickEvent.Phase.END) MCTestClient.onClientTick(Minecraft.getInstance());
            });
        }
    }
}
