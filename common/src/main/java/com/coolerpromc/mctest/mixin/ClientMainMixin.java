package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.platform.Services;
import net.minecraft.client.User;
import net.minecraft.client.main.Main;
import net.minecraft.core.UUIDUtil;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyArg;

/**
 * Player name for dev clients: {@code -Dmctest.username=Bob} (or the MCTEST_USERNAME environment variable) replaces
 * the dev launcher's name, with the matching offline UUID, so several test clients can join one server.
 */
@Mixin(Main.class)
public abstract class ClientMainMixin {

    @ModifyArg(method = "main",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/main/GameConfig$UserData;<init>(Lnet/minecraft/client/User;Ljava/net/Proxy;)V"),
            index = 0)
    private static User mctest$username(User user) {
        String name = System.getProperty("mctest.username", System.getenv("MCTEST_USERNAME"));
        if (name == null || name.isBlank() || !Services.PLATFORM.isDevelopmentEnvironment()) {
            return user;
        }
        name = name.trim();
        return new User(name, UUIDUtil.createOfflinePlayerUUID(name), user.getAccessToken(), user.getXuid(), user.getClientId());
    }
}
