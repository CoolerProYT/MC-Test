package com.coolerpromc.mctest.mixin;

import net.minecraft.client.MouseHandler;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The GLFW callbacks MCTest feeds synthetic input into (private in 1.21.1). */
@Mixin(MouseHandler.class)
public interface MouseHandlerInvoker {

    @Invoker("onPress")
    void mctest$onPress(long handle, int button, int action, int modifiers);

    @Invoker("onMove")
    void mctest$onMove(long handle, double x, double y);

    @Invoker("onScroll")
    void mctest$onScroll(long handle, double xoffset, double yoffset);
}
