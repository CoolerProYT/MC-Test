package com.coolerpromc.mctest.mixin;

import net.minecraft.gametest.framework.GameTestInfo;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** {@code GameTestInfo.getTick()} is package-private in 26.1. */
@Mixin(GameTestInfo.class)
public interface GameTestInfoAccessor {

    @Invoker("getTick")
    int mctest$getTick();
}
