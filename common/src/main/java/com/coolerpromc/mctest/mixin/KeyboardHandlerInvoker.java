package com.coolerpromc.mctest.mixin;

import net.minecraft.client.KeyboardHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The GLFW callback MCTest feeds synthetic typing into (private in 1.21.1; keyPress is already public). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {

    @Invoker("charTyped")
    void mctest$charTyped(long handle, int codePoint, int modifiers);
}
