package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.mixin.BackupConfirmScreenAccessor;
import com.coolerpromc.mctest.mixin.DisconnectedScreenAccessor;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;
import net.minecraft.client.multiplayer.ServerData;
import net.minecraft.client.multiplayer.resolver.ServerAddress;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.RegistryAccess;
import net.minecraft.client.gui.screens.GenericMessageScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.GameRules;
import net.minecraft.core.registries.Registries;
import net.minecraft.FileUtil;
import net.minecraft.world.Difficulty;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.LevelSettings;
import net.minecraft.world.level.WorldDataConfiguration;
import net.minecraft.world.level.levelgen.WorldDimensions;
import net.minecraft.world.level.levelgen.WorldOptions;
import net.minecraft.world.level.levelgen.presets.WorldPreset;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.LevelStorageSource;
import net.minecraft.world.level.storage.LevelSummary;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.OptionalLong;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;

/**
 * World lifecycle: singleplayer worlds, and joining a multiplayer server. Vanilla's world load spins its own loop on the render thread (as when you press
 * "Create New World"); other requests keep being served during it, but client ticks pause, so completion is detected
 * with a wall-clock deadline rather than a tick count.
 */
public final class WorldLifecycleMethods {

    private static final long DEFAULT_TIMEOUT_MS = 120_000;

    private WorldLifecycleMethods() {
    }

    public static void register() {
        RpcRegistry.register("world.list", params -> listWorlds());
        RpcRegistry.register("world.create", params -> {
            CreateSpec spec = CreateSpec.parse(params);
            WaitSpec wait = WaitSpec.parse(params);
            return ClientThread.callAsync(() -> create(spec, wait));
        });
        RpcRegistry.register("world.join", params -> {
            String name = Params.string(params, "name");
            WaitSpec wait = WaitSpec.parse(params);
            return resolveLevelId(name).thenCompose(id -> ClientThread.callAsync(() -> join(id, wait)));
        });
        RpcRegistry.register("connect", params -> {
            String address = Params.string(params, "address", "localhost");
            if (!ServerAddress.isValidAddress(address)) throw new RpcException("Not a valid server address: " + address);
            WaitSpec wait = WaitSpec.parse(params);
            return ClientThread.callAsync(() -> connect(address, wait));
        });
        RpcRegistry.register("world.leave", params -> {
            long timeout = (long) Params.number(params, "timeoutMs", DEFAULT_TIMEOUT_MS);
            return ClientThread.callAsync(() -> leave(timeout));
        });
        RpcRegistry.register("world.delete", params -> {
            String name = Params.string(params, "name");
            return resolveLevelId(name).thenCompose(id -> ClientThread.call(() -> delete(id)));
        });
    }

    // ---- create ----

    private record CreateSpec(String name, OptionalLong seed, boolean flat, GameType gameType, Difficulty difficulty,
                              boolean hardcore, boolean cheats, boolean structures, boolean bonusChest) {

        static CreateSpec parse(com.google.gson.JsonObject params) {
            String name = Params.string(params, "name");
            if (name.isBlank()) throw new RpcException("name must not be blank");
            OptionalLong seed = OptionalLong.empty();
            if (Params.has(params, "seed")) {
                String s = Params.string(params, "seed").trim();
                if (!s.isEmpty()) {
                    // Same rule as the Create World screen: numbers are used as-is, anything else is hashed.
                    long value;
                    try {
                        value = Long.parseLong(s);
                    } catch (NumberFormatException e) {
                        value = s.hashCode();
                    }
                    seed = OptionalLong.of(value);
                }
            }
            String mode = Params.string(params, "gamemode", "creative").toLowerCase(Locale.ROOT);
            boolean hardcore = mode.equals("hardcore") || Params.bool(params, "hardcore", false);
            GameType gameType = hardcore ? GameType.SURVIVAL : GameType.byName(mode, null);
            if (gameType == null) throw new RpcException("gamemode must be survival, creative, adventure, spectator or hardcore");
            Difficulty difficulty = Difficulty.byName(Params.string(params, "difficulty", hardcore ? "hard" : "normal").toLowerCase(Locale.ROOT));
            if (difficulty == null) throw new RpcException("difficulty must be peaceful, easy, normal or hard");
            // Cheats default on: a test world without commands is rarely what an agent wants.
            boolean cheats = Params.bool(params, "cheats", !hardcore);
            return new CreateSpec(name, seed, Params.bool(params, "flat", false), gameType, difficulty, hardcore, cheats,
                    Params.bool(params, "structures", true), Params.bool(params, "bonusChest", false));
        }
    }

    private static CompletableFuture<JsonElement> create(CreateSpec spec, WaitSpec wait) {
        Minecraft mc = ClientThread.mc();
        String folder;
        try {
            folder = FileUtil.findAvailableName(mc.getLevelSource().getBaseDir(), spec.name(), "");
        } catch (Exception e) {
            throw new RpcException("Could not pick a folder for '" + spec.name() + "': " + e.getMessage());
        }
        LevelSettings settings = new LevelSettings(spec.name(), spec.gameType(), spec.hardcore(), spec.difficulty(), spec.cheats(),
                new GameRules(), WorldDataConfiguration.DEFAULT);
        WorldOptions options = new WorldOptions(spec.seed().orElse(WorldOptions.randomSeed()), spec.structures(), spec.bonusChest());
        ResourceKey<WorldPreset> preset = spec.flat() ? WorldPresets.FLAT : WorldPresets.NORMAL;
        Function<RegistryAccess, WorldDimensions> dimensions =
                registries -> registries.registryOrThrow(Registries.WORLD_PRESET).getHolderOrThrow(preset).value().createWorldDimensions();

        // Run outside this task so the request is acknowledged before the (blocking) load starts.
        mc.execute(() -> {
            leaveIfInWorld(mc);
            mc.createWorldOpenFlows().createFreshLevel(folder, settings, options, dimensions, new TitleScreen());
        });

        JsonObject out = new JsonObject();
        out.addProperty("levelId", folder);
        out.addProperty("name", spec.name());
        // As a string: 64-bit seeds lose precision as JSON numbers in JavaScript clients.
        out.addProperty("seed", Long.toString(options.seed()));
        return wait.block() ? waitForWorld(out, wait) : CompletableFuture.completedFuture(out);
    }

    // ---- join ----

    private static CompletableFuture<JsonElement> join(String levelId, WaitSpec wait) {
        Minecraft mc = ClientThread.mc();
        mc.execute(() -> {
            leaveIfInWorld(mc);
            mc.createWorldOpenFlows().openWorld(levelId, () -> mc.setScreen(new TitleScreen()));
        });
        JsonObject out = new JsonObject();
        out.addProperty("levelId", levelId);
        return wait.block() ? waitForWorld(out, wait) : CompletableFuture.completedFuture(out);
    }

    // ---- connect ----

    /** Same as Multiplayer > Direct Connection. Leaves any open world first. */
    private static CompletableFuture<JsonElement> connect(String address, WaitSpec wait) {
        Minecraft mc = ClientThread.mc();
        ServerAddress parsed = ServerAddress.parseString(address);
        mc.execute(() -> {
            leaveIfInWorld(mc);
            ConnectScreen.startConnecting(new JoinMultiplayerScreen(new TitleScreen()), mc, parsed,
                    new ServerData("MCTest", address, ServerData.Type.OTHER), false, null);
        });
        JsonObject out = new JsonObject();
        out.addProperty("address", parsed.getHost() + ":" + parsed.getPort());
        return wait.block() ? waitForWorld(out, wait) : CompletableFuture.completedFuture(out);
    }

    /**
     * @param block          wait until in the world (else return as soon as loading starts)
     * @param timeoutMs      give up after this long
     * @param confirmPrompts answer "experimental settings" / "make a backup?" prompts with "I know what I'm doing"
     *                       (a test world rarely needs a backup); otherwise fail on them
     */
    private record WaitSpec(boolean block, long timeoutMs, boolean confirmPrompts) {
        static WaitSpec parse(JsonObject params) {
            return new WaitSpec(Params.bool(params, "wait", true), (long) Params.number(params, "timeoutMs", DEFAULT_TIMEOUT_MS),
                    Params.bool(params, "confirmPrompts", true));
        }
    }

    /** A menu that is not part of loading: if the client sits on one this long without loading, it is stuck. */
    private static final long STUCK_MS = 10_000;

    /**
     * Completes once the player is in the world and the loading screen has gone. Fails with the screen's title and
     * buttons if the client ends up on (or stays on) a menu instead, e.g. a load error or a prompt.
     */
    private static CompletableFuture<JsonElement> waitForWorld(JsonObject out, WaitSpec spec) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        long start = System.currentTimeMillis();
        long deadline = start + spec.timeoutMs();
        boolean[] sawLoading = {false};
        long[] menuSince = {-1};
        JsonArray confirmed = new JsonArray();
        TickScheduler.add(() -> {
            Minecraft mc = ClientThread.mc();
            String state = StatusMethods.state(mc);
            Screen screen = mc.screen;
            if (state.equals("in-world") && screen == null) {
                out.addProperty("state", state);
                out.addProperty("loadMs", System.currentTimeMillis() - start);
                if (!confirmed.isEmpty()) out.add("confirmedPrompts", confirmed);
                future.complete(out);
                return true;
            }
            if (screen instanceof BackupConfirmScreenAccessor prompt) {
                if (!spec.confirmPrompts()) {
                    future.completeExceptionally(new RpcException("World load needs confirmation: " + describe(screen)
                            + ". Pass confirmPrompts:true (the default) to proceed without a backup"));
                    return true;
                }
                confirmed.add(screen.getTitle().getString());
                Constants.LOG.info("MCTest: answered '{}' with \"I know what I'm doing\"", screen.getTitle().getString());
                prompt.mctest$getOnProceed().proceed(false, false);
                return false;
            }
            if (!state.equals("title")) {
                sawLoading[0] = true;
                menuSince[0] = -1;
            } else if (sawLoading[0]) {
                // Went loading -> back to a menu without entering the world.
                if (screen instanceof DisconnectedScreenAccessor disconnected) {
                    future.completeExceptionally(new RpcException("Disconnected: " + screen.getTitle().getString()
                            + ": " + disconnected.mctest$getDetails().reason().getString()));
                    return true;
                }
                future.completeExceptionally(new RpcException("World did not load; client is on " + describe(screen)
                        + ". Check mc_logs for the cause"));
                return true;
            } else {
                long now = System.currentTimeMillis();
                if (menuSince[0] < 0) menuSince[0] = now;
                if (now - menuSince[0] > STUCK_MS) {
                    future.completeExceptionally(new RpcException("World never started loading; client is stuck on " + describe(screen)
                            + ". Answer it with mc_click_widget, or check mc_logs"));
                    return true;
                }
            }
            if (System.currentTimeMillis() > deadline) {
                future.completeExceptionally(new RpcException("Timed out after " + spec.timeoutMs() + "ms waiting for the world (state: "
                        + state + ", screen: " + describe(screen) + ")"));
                return true;
            }
            return false;
        });
        return future;
    }

    /** {@code Class "title" [button, button]}, for error messages. */
    private static String describe(Screen screen) {
        if (screen == null) return "no screen";
        List<String> buttons = new ArrayList<>();
        for (var child : screen.children()) {
            if (child instanceof AbstractWidget w && !w.getMessage().getString().isBlank()) buttons.add(w.getMessage().getString());
        }
        return screen.getClass().getSimpleName() + " \"" + screen.getTitle().getString() + "\"" + (buttons.isEmpty() ? "" : " " + buttons);
    }

    // ---- leave ----

    private static CompletableFuture<JsonElement> leave(long timeoutMs) {
        Minecraft mc = ClientThread.mc();
        if (mc.level == null) {
            JsonObject out = new JsonObject();
            out.addProperty("left", false);
            out.addProperty("state", StatusMethods.state(mc));
            return CompletableFuture.completedFuture(out);
        }
        mc.execute(() -> leaveIfInWorld(mc));
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        long deadline = System.currentTimeMillis() + timeoutMs;
        TickScheduler.add(() -> {
            if (mc.level == null && mc.getSingleplayerServer() == null) {
                JsonObject out = new JsonObject();
                out.addProperty("left", true);
                out.addProperty("state", StatusMethods.state(mc));
                future.complete(out);
                return true;
            }
            if (System.currentTimeMillis() > deadline) {
                future.completeExceptionally(new RpcException("Timed out leaving the world"));
                return true;
            }
            return false;
        });
        return future;
    }

    /** Same as the pause menu's "Save and Quit to Title" (saves and stops the integrated server) or "Disconnect". */
    private static void leaveIfInWorld(Minecraft mc) {
        if (mc.level != null) {
            // What the pause menu's button does in 1.21.1.
            boolean local = mc.isLocalServer();
            mc.level.disconnect();
            if (local) {
                mc.disconnect(new GenericMessageScreen(Component.translatable("menu.savingLevel")));
            } else {
                mc.disconnect();
            }
            mc.setScreen(new TitleScreen());
        }
    }

    // ---- list / delete ----

    /** The open singleplayer world, which the save scan can miss while it is locked. Render thread only. */
    private record OpenWorld(String levelId, String name) {
    }

    private static OpenWorld openWorld() {
        var server = ClientThread.mc().getSingleplayerServer();
        if (server == null) {
            return null;
        }
        java.nio.file.Path root = server.getWorldPath(LevelResource.ROOT).toAbsolutePath().normalize();
        return new OpenWorld(root.getFileName().toString(), server.getWorldData().getLevelName());
    }

    /** Summaries of every world except the open one (its folder is locked; reading it only logs an exception). */
    private static CompletableFuture<List<LevelSummary>> summaries() {
        return ClientThread.call(WorldLifecycleMethods::openWorld).thenCompose(open -> {
            LevelStorageSource source = ClientThread.mc().getLevelSource();
            try {
                List<LevelStorageSource.LevelDirectory> dirs = source.findLevelCandidates().levels().stream()
                        .filter(d -> open == null || !d.directoryName().equals(open.levelId()))
                        .toList();
                return source.loadLevelSummaries(new LevelStorageSource.LevelCandidates(dirs));
            } catch (Exception e) {
                return CompletableFuture.failedFuture(new RpcException("Could not list worlds: " + e.getMessage()));
            }
        });
    }

    private static CompletableFuture<JsonElement> listWorlds() {
        return ClientThread.call(WorldLifecycleMethods::openWorld).thenCombine(summaries(), (open, list) -> {
            JsonArray worlds = new JsonArray();
            if (open != null && list.stream().noneMatch(s -> s.getLevelId().equals(open.levelId()))) {
                JsonObject w = new JsonObject();
                w.addProperty("levelId", open.levelId());
                w.addProperty("name", open.name());
                w.addProperty("open", true);
                worlds.add(w);
            }
            list.stream().sorted(Comparator.comparingLong(LevelSummary::getLastPlayed).reversed()).forEach(s -> {
                JsonObject w = new JsonObject();
                w.addProperty("open", open != null && open.levelId().equals(s.getLevelId()));
                w.addProperty("levelId", s.getLevelId());
                w.addProperty("name", s.getLevelName());
                w.addProperty("gamemode", s.isHardcore() ? "hardcore" : s.getGameMode().getName());
                w.addProperty("cheats", s.hasCommands());
                w.addProperty("lastPlayed", s.getLastPlayed());
                w.addProperty("locked", s.isLocked());
                worlds.add(w);
            });
            JsonObject out = new JsonObject();
            out.addProperty("savesDir", ClientThread.mc().getLevelSource().getBaseDir().toAbsolutePath().normalize().toString());
            out.add("worlds", worlds);
            return out;
        });
    }

    /** Accepts a folder id, or a display name if it matches exactly one world. */
    private static CompletableFuture<String> resolveLevelId(String name) {
        return ClientThread.call(WorldLifecycleMethods::openWorld).thenCombine(summaries(), (open, list) -> {
            if (list.stream().anyMatch(s -> s.getLevelId().equals(name))) {
                return name;
            }
            if (open != null && (open.levelId().equals(name) || open.name().equals(name))) {
                return open.levelId();
            }
            List<LevelSummary> byName = list.stream().filter(s -> s.getLevelName().equals(name)).toList();
            if (byName.size() == 1) {
                return byName.getFirst().getLevelId();
            }
            if (byName.size() > 1) {
                throw new RpcException("Several worlds are named '" + name + "'; use the levelId: "
                        + byName.stream().map(LevelSummary::getLevelId).toList());
            }
            throw new RpcException("No world '" + name + "'. Known: " + list.stream().map(LevelSummary::getLevelId).toList());
        });
    }

    private static JsonElement delete(String levelId) {
        Minecraft mc = ClientThread.mc();
        OpenWorld open = openWorld();
        if (open != null && open.levelId().equals(levelId)) {
            throw new RpcException("Cannot delete the world that is currently open; call world.leave first");
        }
        try (LevelStorageSource.LevelStorageAccess access = mc.getLevelSource().createAccess(levelId)) {
            access.deleteLevel();
        } catch (Exception e) {
            throw new RpcException("Could not delete '" + levelId + "': " + e.getMessage());
        }
        Constants.LOG.info("MCTest deleted world {}", levelId);
        JsonObject out = new JsonObject();
        out.addProperty("deleted", levelId);
        return out;
    }
}
