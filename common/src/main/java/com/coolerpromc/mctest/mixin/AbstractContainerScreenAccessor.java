package com.coolerpromc.mctest.mixin;

import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/** Exposes the container screen origin so slot coordinates can be reported in screen space. */
@Mixin(AbstractContainerScreen.class)
public interface AbstractContainerScreenAccessor {

    @Accessor("leftPos")
    int mctest$getLeftPos();

    @Accessor("topPos")
    int mctest$getTopPos();
}
