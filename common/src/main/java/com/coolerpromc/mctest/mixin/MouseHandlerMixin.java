package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.input.InputGuard;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.lwjgl.sdl.SDLMouse;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * Vanilla captures the OS cursor whenever a screen closes in-world, and warps it to the window centre whenever a screen
 * opens. When MCTest is the one opening/closing screens (or entering worlds) while you work in another app, that traps
 * or yanks your mouse. Here the game only does that within a moment of your own input in its window; otherwise the
 * cursor is left alone. A real click in the game window still captures it as usual. Vanilla behaviour:
 * -Dmctest.mouseWarp=true.
 */
@Mixin(MouseHandler.class)
public abstract class MouseHandlerMixin {

    @Inject(method = "onButton(JLnet/minecraft/client/input/MouseButtonInfo;I)V", at = @At("HEAD"))
    private void mctest$noteRealClick(long handle, MouseButtonInfo info, int action, CallbackInfo ci) {
        InputGuard.noteInput();
    }

    @Inject(method = "grabMouse()V", at = @At("HEAD"), cancellable = true)
    private void mctest$onlyGrabForTheUser(CallbackInfo ci) {
        if (!InputGuard.userIsActive()) {
            // Stay un-grabbed (so your next real click in the window still captures it, as in vanilla).
            ci.cancel();
        }
    }

    @Redirect(method = "releaseMouse()V", at = @At(value = "INVOKE",
            target = "Lcom/mojang/blaze3d/platform/InputConstants;releaseMouse(Lcom/mojang/blaze3d/platform/Window;DD)V"))
    private void mctest$releaseWithoutWarp(Window window, double x, double y) {
        if (InputGuard.userIsActive() && window.isFocused()) {
            InputConstants.releaseMouse(window, x, y);
        } else {
            // Same as vanilla minus moving your cursor: just leave relative (captured) mode.
            SDLMouse.SDL_SetWindowRelativeMouseMode(window.handle(), false);
        }
    }
}
