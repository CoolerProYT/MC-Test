package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.input.HeldKeys;
import com.mojang.blaze3d.platform.InputConstants;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** Reports keys held by MCTest as down, so {@code hasShiftDown()}-style polling sees synthetic input. */
@Mixin(InputConstants.class)
public abstract class InputConstantsMixin {

    @Inject(method = "isKeyDown(JI)Z", at = @At("HEAD"), cancellable = true)
    private static void mctest$heldKeys(long window, int key, CallbackInfoReturnable<Boolean> cir) {
        if (HeldKeys.isHeld(key)) {
            cir.setReturnValue(true);
        }
    }
}
