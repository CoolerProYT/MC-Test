package com.coolerpromc.mctest.mixin;

import com.coolerpromc.mctest.capture.ChatCapture;
import net.minecraft.client.multiplayer.chat.GuiMessageTag;
import net.minecraft.client.gui.components.ChatComponent;
import net.minecraft.client.multiplayer.chat.GuiMessageSource;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MessageSignature;
import org.jspecify.annotations.Nullable;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** Every chat/system line funnels through this private method; used to capture command output. */
@Mixin(ChatComponent.class)
public abstract class ChatComponentMixin {

    @Inject(method = "addMessage(Lnet/minecraft/network/chat/Component;Lnet/minecraft/network/chat/MessageSignature;Lnet/minecraft/client/multiplayer/chat/GuiMessageSource;Lnet/minecraft/client/multiplayer/chat/GuiMessageTag;)V", at = @At("HEAD"))
    private void mctest$captureMessage(Component contents, @Nullable MessageSignature signature, GuiMessageSource source, @Nullable GuiMessageTag tag, CallbackInfo ci) {
        ChatCapture.onMessage(contents, source);
    }
}
