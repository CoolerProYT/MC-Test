package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.tick.TickControl;
import net.minecraft.world.entity.Mob;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Frozen mobs do not despawn. See {@link TickControl}. */
@Mixin(Mob.class)
public abstract class MobDespawnMixin {

    @Inject(method = "checkDespawn()V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeDespawn(CallbackInfo ci) {
        if (!((Mob) (Object) this).level().isClientSide && TickControl.get().isEntityFrozen((Mob) (Object) this)) ci.cancel();
    }
}
