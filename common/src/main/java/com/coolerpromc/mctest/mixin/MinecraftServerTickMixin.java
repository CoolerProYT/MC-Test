package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.tick.ServerFreeze;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import java.util.function.BooleanSupplier;

/**
 * Full freeze ({@link ServerFreeze}): the call to {@code tickServer} is skipped as a whole, so loader tick events and
 * other mods' injections into {@code tickServer} do not run either.
 */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {

    @Shadow
    protected abstract void tickServer(BooleanSupplier haveTime);

    @Redirect(method = "processPacketsAndTick",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/MinecraftServer;tickServer(Ljava/util/function/BooleanSupplier;)V"))
    private void mctest$tickServer(MinecraftServer self, BooleanSupplier haveTime) {
        if (ServerFreeze.shouldTick(self)) {
            this.tickServer(haveTime);
        } else {
            ServerFreeze.whileFrozen(self);
        }
    }
}
