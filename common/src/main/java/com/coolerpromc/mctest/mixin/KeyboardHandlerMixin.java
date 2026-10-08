package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.input.InputGuard;
import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Records real key presses for {@link InputGuard} (synthetic MCTest keys are excluded). */
@Mixin(KeyboardHandler.class)
public abstract class KeyboardHandlerMixin {

    @Inject(method = "keyPress(JILnet/minecraft/client/input/KeyEvent;)V", at = @At("HEAD"))
    private void mctest$noteRealKey(long handle, int action, KeyEvent event, CallbackInfo ci) {
        InputGuard.noteInput();
    }
}
