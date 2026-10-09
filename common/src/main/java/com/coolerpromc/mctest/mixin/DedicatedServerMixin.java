package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import net.minecraft.server.dedicated.DedicatedServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Dev clients have no profile keys: without this a dev server rejects their chat and signed commands. */
@Mixin(DedicatedServer.class)
public abstract class DedicatedServerMixin {

    @Inject(method = "enforceSecureProfile", at = @At("HEAD"), cancellable = true)
    private void mctest$allowUnsignedChat(CallbackInfoReturnable<Boolean> cir) {
        if (MCTestServer.offlineMode()) {
            cir.setReturnValue(false);
        }
    }
}
