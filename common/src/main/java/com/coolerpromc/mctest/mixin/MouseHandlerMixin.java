package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.input.InputGuard;
import com.mojang.blaze3d.platform.InputConstants;
import net.minecraft.client.MouseHandler;
import net.minecraft.client.Minecraft;
import org.lwjgl.glfw.GLFW;
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

    @Inject(method = "onPress(JIII)V", at = @At("HEAD"))
    private void mctest$noteRealClick(long handle, int button, int action, int modifiers, CallbackInfo ci) {
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
            target = "Lcom/mojang/blaze3d/platform/InputConstants;grabOrReleaseMouse(JIDD)V"))
    private void mctest$releaseWithoutWarp(long window, int cursorMode, double x, double y) {
        if (InputGuard.userIsActive() && Minecraft.getInstance().isWindowActive()) {
            InputConstants.grabOrReleaseMouse(window, cursorMode, x, y);
        } else {
            // Same as vanilla minus moving your cursor: just show the normal cursor again.
            GLFW.glfwSetInputMode(window, GLFW.GLFW_CURSOR, cursorMode);
        }
    }
}
