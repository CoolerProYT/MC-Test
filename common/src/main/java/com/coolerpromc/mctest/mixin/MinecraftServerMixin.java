package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dev clients have no Mojang session, so a dev server must not try to authenticate them (online-mode). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerMixin {

    @Inject(method = "usesAuthentication", at = @At("HEAD"), cancellable = true)
    private void mctest$offline(CallbackInfoReturnable<Boolean> cir) {
        if (MCTestServer.offlineMode()) {
            cir.setReturnValue(false);
        }
    }
}
