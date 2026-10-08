package com.coolerpromc.mctest;

import net.minecraft.client.Minecraft;
import net.neoforged.api.distmarker.Dist;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.neoforge.client.event.ClientTickEvent;
import net.neoforged.neoforge.common.NeoForge;

@Mod(value = Constants.MOD_ID, dist = Dist.CLIENT)
public class MCTestNeoForge {

    public MCTestNeoForge(IEventBus modBus) {
        MCTestClient.init();
        NeoForge.EVENT_BUS.addListener(ClientTickEvent.Post.class, event -> MCTestClient.onClientTick(Minecraft.getInstance()));
    }
}
