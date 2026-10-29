package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.methods.GameTestMethods;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Notes when something (vanilla in IDE mode, or the loader) already ticked the GameTests this server tick. */
@Mixin(GameTestTicker.class)
public abstract class GameTestTickerMixin {

    @Inject(method = "tick()V", at = @At("HEAD"))
    private void mctest$noteTick(CallbackInfo ci) {
        MinecraftServer server = GameTestMethods.currentServer();
        GameTestMethods.lastTickerTick = server == null ? -1 : server.getTickCount();
    }
}
