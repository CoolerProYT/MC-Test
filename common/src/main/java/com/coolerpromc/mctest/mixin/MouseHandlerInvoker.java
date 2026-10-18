package com.coolerpromc.mctest.mixin;

import net.minecraft.client.MouseHandler;
import net.minecraft.client.input.MouseButtonInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The GLFW callbacks MCTest feeds synthetic input into (private in 26.2). */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {

    @Invoker("onButton")
    void mctest$onButton(long handle, MouseButtonInfo info, int action);

    @Invoker("onMove")
    void mctest$onMove(long handle, double x, double y);

    @Invoker("onScroll")
    void mctest$onScroll(long handle, double xoffset, double yoffset);
}
