package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.tick.TickControl;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Block entities (furnaces, hoppers, ...) stand still on a frozen server. See {@link TickControl}. */
@Mixin(Level.class)
public abstract class LevelBlockEntityTickMixin {

    @Inject(method = "tickBlockEntities()V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeBlockEntities(CallbackInfo ci) {
        if ((Object) this instanceof ServerLevel && TickControl.get().isWorldFrozen()) ci.cancel();
    }
}
