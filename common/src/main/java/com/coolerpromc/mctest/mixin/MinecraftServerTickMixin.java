package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.methods.GameTestMethods;
import net.minecraft.server.MinecraftServer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/** Drives MCTest-started GameTests on integrated and dedicated servers (see {@link GameTestMethods#tickIfNeeded}). */
@Mixin(MinecraftServer.class)
public abstract class MinecraftServerTickMixin {

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("HEAD"))
    private void mctest$trackServer(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        GameTestMethods.setCurrentServer((MinecraftServer) (Object) this);
    }

    @Inject(method = "tickChildren(Ljava/util/function/BooleanSupplier;)V", at = @At("TAIL"))
    private void mctest$tickGameTests(BooleanSupplier hasTimeLeft, CallbackInfo ci) {
        GameTestMethods.tickIfNeeded((MinecraftServer) (Object) this);
    }
}
