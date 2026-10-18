package com.coolerpromc.mctest.mixin;

import net.minecraft.client.KeyboardHandler;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The GLFW callbacks MCTest feeds synthetic input into (private in 26.2). */
@Mixin(KeyboardHandler.class)
public interface KeyboardHandlerInvoker {

    @Invoker("keyPress")
    void mctest$keyPress(long handle, int action, KeyEvent event);

    @Invoker("charTyped")
    void mctest$charTyped(long handle, CharacterEvent event);
}
