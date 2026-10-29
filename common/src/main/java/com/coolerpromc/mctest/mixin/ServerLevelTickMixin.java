package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.tick.TickControl;
import net.minecraft.server.level.ServerChunkCache;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.raid.Raids;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.function.BooleanSupplier;

/**
 * Freezes a level while {@link TickControl} says so, the way vanilla's {@code /tick freeze} does from 1.20.3: chunks
 * keep loading and players keep moving; time, weather, scheduled ticks, raids, block events and other entities stop.
 * Block entities and despawning are frozen by {@code LevelBlockEntityTickMixin} and {@code MobDespawnMixin}.
 */
@Mixin(ServerLevel.class)
public abstract class ServerLevelTickMixin {

    @Inject(method = "advanceWeatherCycle()V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeWeather(CallbackInfo ci) {
        if (TickControl.get().isWorldFrozen()) ci.cancel();
    }

    @Inject(method = "tickTime()V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeTime(CallbackInfo ci) {
        if (TickControl.get().isWorldFrozen()) ci.cancel();
    }

    @Inject(method = "runBlockEvents()V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeBlockEvents(CallbackInfo ci) {
        if (TickControl.get().isWorldFrozen()) ci.cancel();
    }

    /** Block and fluid ticks are skipped like in a debug world. */
    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerLevel;isDebug()Z"))
    private boolean mctest$freezeScheduledTicks(ServerLevel level) {
        return level.isDebug() || TickControl.get().isWorldFrozen();
    }

    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/world/entity/raid/Raids;tick()V"))
    private void mctest$freezeRaids(Raids raids) {
        if (!TickControl.get().isWorldFrozen()) raids.tick();
    }

    /** Chunks keep loading and sending; random ticks and mob spawning stop. */
    @Redirect(method = "tick(Ljava/util/function/BooleanSupplier;)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerChunkCache;tick(Ljava/util/function/BooleanSupplier;Z)V"))
    private void mctest$freezeChunkTicks(ServerChunkCache chunks, BooleanSupplier hasTimeLeft, boolean tickChunks) {
        chunks.tick(hasTimeLeft, tickChunks && !TickControl.get().isWorldFrozen());
    }

    @Inject(method = "tickNonPassenger(Lnet/minecraft/world/entity/Entity;)V", at = @At("HEAD"), cancellable = true)
    private void mctest$freezeEntities(Entity entity, CallbackInfo ci) {
        if (TickControl.get().isEntityFrozen(entity)) ci.cancel();
    }
}
