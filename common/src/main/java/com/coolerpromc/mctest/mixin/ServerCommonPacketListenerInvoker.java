package com.coolerpromc.mctest.mixin;

import net.minecraft.server.network.ServerCommonPacketListenerImpl;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** Keeps connections alive while {@link com.coolerpromc.mctest.tick.ServerFreeze} skips server ticks. */
@Mixin(ServerCommonPacketListenerImpl.class)
public interface ServerCommonPacketListenerInvoker {

    @Invoker("keepConnectionAlive")
    void mctest$keepConnectionAlive();
}
