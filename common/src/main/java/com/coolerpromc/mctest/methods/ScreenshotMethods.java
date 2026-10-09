package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.NativeImage;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.util.Util;

import javax.imageio.IIOImage;
import javax.imageio.ImageIO;
import javax.imageio.ImageWriteParam;
import javax.imageio.ImageWriter;
import javax.imageio.stream.MemoryCacheImageOutputStream;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.Base64;
import java.util.concurrent.CompletableFuture;

public final class ScreenshotMethods {

    /** Longest side of the inline image. Vision models downscale anything larger anyway (~1568 px). */
    private static final int DEFAULT_INLINE_MAX = 1280;

    private ScreenshotMethods() {
    }

    public static void register() {
        RpcRegistry.register("screenshot", params -> {
            String name = Params.string(params, "name", null);
            // inline: also return a downscaled JPEG as base64. A full-size PNG of a large window runs to several MB,
            // which agent clients compress or truncate (the bottom of the image comes out garbled).
            boolean inline = Params.bool(params, "inline", false);
            int maxSize = Math.max(64, Params.integer(params, "maxSize", DEFAULT_INLINE_MAX));
            return ClientThread.callAsync(() -> take(name, inline, maxSize));
        });
        RpcRegistry.register("window.resize", params -> {
            int width = Params.integer(params, "width");
            int height = Params.integer(params, "height");
            return ClientThread.callAsync(() -> resize(width, height));
        });
    }

    private static CompletableFuture<JsonElement> take(String requestedName, boolean inline, int maxSize) {
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
                if (inline) {
                    out.add("inline", inlineJpeg(img, maxSize));
                }
                future.complete(out);
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        }));
        return future;
    }

    /** {@code {mimeType, data (base64), width, height}}: the screenshot scaled to fit {@code maxSize}, as JPEG. */
    private static JsonObject inlineJpeg(NativeImage img, int maxSize) throws Exception {
        int w = img.getWidth();
        int h = img.getHeight();
        BufferedImage full = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        full.setRGB(0, 0, w, h, img.getPixels(), 0, w);

        double scale = Math.min(1.0, (double) maxSize / Math.max(w, h));
        int sw = Math.max(1, (int) Math.round(w * scale));
        int sh = Math.max(1, (int) Math.round(h * scale));
        BufferedImage scaled = full;
        if (sw != w || sh != h) {
            scaled = new BufferedImage(sw, sh, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = scaled.createGraphics();
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
            g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
            g.drawImage(full, 0, 0, sw, sh, null);
            g.dispose();
        }

        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageWriter writer = ImageIO.getImageWritersByFormatName("jpeg").next();
        try (MemoryCacheImageOutputStream stream = new MemoryCacheImageOutputStream(bytes)) {
            ImageWriteParam param = writer.getDefaultWriteParam();
            param.setCompressionMode(ImageWriteParam.MODE_EXPLICIT);
            param.setCompressionQuality(0.9f);
            writer.setOutput(stream);
            writer.write(null, new IIOImage(scaled, null, null), param);
        } finally {
            writer.dispose();
        }

        JsonObject o = new JsonObject();
        o.addProperty("mimeType", "image/jpeg");
        o.addProperty("width", sw);
        o.addProperty("height", sh);
        o.addProperty("data", Base64.getEncoder().encodeToString(bytes.toByteArray()));
        return o;
    }

    /** Resizes the game window (leaving fullscreen) and waits until it has the new size. */
    private static CompletableFuture<JsonElement> resize(int width, int height) {
        Minecraft mc = ClientThread.mc();
        Window window = mc.getWindow();
        // Also leaves fullscreen.
        window.setWindowed(width, height);
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] waited = {0};
        TickScheduler.add(() -> {
            boolean done = window.getScreenWidth() == width && window.getScreenHeight() == height;
            if (!done && ++waited[0] < 40) return false;
            JsonObject out = new JsonObject();
            out.addProperty("width", window.getScreenWidth());
            out.addProperty("height", window.getScreenHeight());
            // Differs from width/height under OS display scaling.
            out.addProperty("framebufferWidth", window.getWidth());
            out.addProperty("framebufferHeight", window.getHeight());
            out.addProperty("guiScaledWidth", window.getGuiScaledWidth());
            out.addProperty("guiScaledHeight", window.getGuiScaledHeight());
            out.addProperty("guiScale", window.getGuiScale());
            future.complete(out);
            return true;
        });
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
