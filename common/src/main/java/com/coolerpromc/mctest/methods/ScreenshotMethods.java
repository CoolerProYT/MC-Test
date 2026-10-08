package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public final class ScreenshotMethods {

    private ScreenshotMethods() {
    }

    public static void register() {
        RpcRegistry.register("screenshot", params -> {
            String name = Params.string(params, "name", null);
            return ClientThread.callAsync(() -> take(name));
        });
    }

    private static CompletableFuture<JsonElement> take(String requestedName) {
        Minecraft mc = ClientThread.mc();
        File dir = mc.gameDirectory.toPath().toAbsolutePath().normalize().resolve("mctest/screenshots").toFile();
        File file = new File(dir, fileName(requestedName));

        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        // Copies the framebuffer into a GPU buffer; the callback fires once the readback finishes (a frame or so later).
        Screenshot.takeScreenshot(mc.gameRenderer.mainRenderTarget(), image -> Util.ioPool().execute(() -> {
            try (NativeImage img = image) {
                dir.mkdirs();
                img.writeToFile(file);
                JsonObject out = new JsonObject();
                out.addProperty("path", file.getAbsolutePath());
                out.addProperty("width", img.getWidth());
                out.addProperty("height", img.getHeight());
                future.complete(out);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }));
        return future;
    }

    private static String fileName(String requested) {
        if (requested == null || requested.isBlank()) {
            return Util.getFilenameFormattedDateTime() + ".png";
        }
        String name = requested.endsWith(".png") ? requested.substring(0, requested.length() - 4) : requested;
        if (!name.matches("[A-Za-z0-9._-]+") || name.startsWith(".")) {
            throw new RpcException("Screenshot name may only contain letters, digits, '.', '_' and '-'");
        }
        return name + ".png";
    }
}
