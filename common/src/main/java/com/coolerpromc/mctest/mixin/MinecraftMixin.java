package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.methods.ActionMethods;
import net.minecraft.client.Minecraft;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(Minecraft.class)
public abstract class MinecraftMixin {

    /**
     * While MCTest holds the use key to keep an item in use (eating, drawing a bow), vanilla would otherwise fire a
     * fresh right-click at whatever the crosshair is on as soon as the use ends.
     */
    @Inject(method = "startUseItem()V", at = @At("HEAD"), cancellable = true)
    private void mctest$suppressHeldUse(CallbackInfo ci) {
        if (ActionMethods.isHoldingUse()) {
            ci.cancel();
        }
    }
}
