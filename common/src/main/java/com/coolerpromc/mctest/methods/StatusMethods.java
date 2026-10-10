package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.platform.Services;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.util.Describe;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.client.gui.screens.LevelLoadingScreen;
import net.minecraft.client.gui.screens.ProgressScreen;
import net.minecraft.client.gui.screens.Screen;
import org.jspecify.annotations.Nullable;

import java.util.concurrent.CompletableFuture;

public final class StatusMethods {

    private StatusMethods() {
    }

    public static void register() {
        RpcRegistry.register("status", params -> {
            // On NeoForge the server starts during mod construction, before Minecraft exists.
            if (Minecraft.getInstance() == null) {
                JsonObject out = new JsonObject();
                out.addProperty("state", "starting");
                out.addProperty("pid", ProcessHandle.current().pid());
                out.addProperty("ready", false);
                return CompletableFuture.completedFuture(out);
            }
            return ClientThread.call(StatusMethods::status);
        });
        RpcRegistry.register("quit", params -> ClientThread.call(() -> {
            // Answer first; the game then saves, stops the integrated server and exits like "Quit Game".
            Minecraft mc = ClientThread.mc();
            mc.execute(mc::stop);
            JsonObject out = new JsonObject();
            out.addProperty("quitting", true);
            return out;
        }));
    }

    private static JsonElement status() {
        Minecraft mc = ClientThread.mc();
        Screen screen = mc.gui.screen();
        String state = state(mc);

        JsonObject out = new JsonObject();
        out.addProperty("state", state);
        // "ready" = the client will accept input; what mc_wait_ready polls for.
        out.addProperty("ready", !state.equals("loading") && !state.equals("joining"));
        out.add("screen", describeScreen(screen));
        out.addProperty("overlay", mc.gui.overlay() == null ? null : mc.gui.overlay().getClass().getName());
        out.addProperty("singleplayer", mc.isLocalServer());
        out.addProperty("windowFocused", mc.getWindow().isFocused());
        out.addProperty("mouseGrabbed", mc.mouseHandler.isMouseGrabbed());
        out.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        out.addProperty("loader", Services.PLATFORM.getPlatformName());
        out.addProperty("loaderVersion", Services.PLATFORM.getLoaderVersion());
        out.addProperty("gameDir", mc.gameDirectory.toPath().toAbsolutePath().normalize().toString());
        out.addProperty("pid", ProcessHandle.current().pid());

        out.add("mods", Describe.mods());
        return out;
    }

    /** One of loading, title, joining, in-world. */
    public static String state(Minecraft mc) {
        Screen screen = mc.gui.screen();
        if (!mc.isGameLoadFinished() || mc.gui.overlay() != null) {
            return "loading";
        }
        if (mc.level != null && mc.player != null) {
            return "in-world";
        }
        if (screen instanceof LevelLoadingScreen || screen instanceof ConnectScreen
                || screen instanceof ProgressScreen || screen instanceof GenericMessageScreen) {
            return "joining";
        }
        return "title";
    }

    /** Shared screen description: {@code {class, simpleName, title}} or null. */
    public static @Nullable JsonObject describeScreen(@Nullable Screen screen) {
        if (screen == null) {
            return null;
        }
        JsonObject s = new JsonObject();
        s.addProperty("class", screen.getClass().getName());
        s.addProperty("simpleName", screen.getClass().getSimpleName());
        s.addProperty("title", screen.getTitle().getString());
        return s;
    }
}
