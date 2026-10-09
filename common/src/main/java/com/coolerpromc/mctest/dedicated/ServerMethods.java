package com.coolerpromc.mctest.dedicated;

import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.capture.LogCapture;
import com.coolerpromc.mctest.platform.Services;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.server.ControlServer;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.coolerpromc.mctest.util.Describe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.SharedConstants;
import net.minecraft.commands.CommandSource;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * The dedicated server's control API: the authoritative side of a multiplayer test. Run console commands, inspect
 * players, blocks and entities as the server sees them, wait on server-side conditions, and control the tick rate.
 */
public final class ServerMethods {

    private ServerMethods() {
    }

    public static void register() {
        RpcRegistry.register("status", params -> {
            // Before SERVER_STARTED the server thread is busy loading; answer without it.
            if (MCTestServer.serverOrNull() == null || !MCTestServer.state().equals("running")) {
                return CompletableFuture.completedFuture(status(null));
            }
            return ServerThread.call(() -> status(ServerThread.server()));
        });
        RpcRegistry.register("command", params -> {
            String command = stripSlash(Params.string(params, "command"));
            int ticks = Math.max(0, Params.integer(params, "ticks", 0));
            return ServerThread.callAsync(() -> command(command, ticks));
        });
        RpcRegistry.register("players", params -> ServerThread.call(ServerMethods::players));
        RpcRegistry.register("block", params -> {
            BlockPos pos = BlockPos.containing(Params.number(params, "x"), Params.number(params, "y"), Params.number(params, "z"));
            String dimension = Params.string(params, "dimension", null);
            return ServerThread.call(() -> Describe.block(level(dimension), pos, "server"));
        });
        RpcRegistry.register("entities", params -> {
            String dimension = Params.string(params, "dimension", null);
            String player = Params.string(params, "player", null);
            Vec3 center = Params.has(params, "x") ? new Vec3(Params.number(params, "x"), Params.number(params, "y"), Params.number(params, "z")) : null;
            double radius = Params.number(params, "radius", 16);
            String type = Params.string(params, "type", null);
            int limit = Params.integer(params, "limit", 50);
            return ServerThread.call(() -> entities(dimension, player, center, radius, type, limit));
        });
        RpcRegistry.register("waitFor", params -> {
            String condition = Params.string(params, "condition").trim();
            int timeout = Params.integer(params, "timeoutTicks", 100);
            if (condition.startsWith("ticks:")) {
                try {
                    timeout = Math.max(timeout, Integer.parseInt(condition.substring(6).trim()) + 1);
                } catch (NumberFormatException e) {
                    throw new RpcException("Use ticks:<n>");
                }
            }
            Supplier<Check> check = parse(condition);
            int finalTimeout = timeout;
            return ServerThread.callAsync(() -> waitFor(condition, check, finalTimeout));
        });
        RpcRegistry.register("time.status", params -> ServerThread.call(ServerMethods::timeStatus));
        RpcRegistry.register("time.freeze", params -> ServerThread.call(() -> {
            ServerThread.server().tickRateManager().setFrozen(true);
            return timeStatus();
        }));
        RpcRegistry.register("time.unfreeze", params -> ServerThread.call(() -> {
            ServerThread.server().tickRateManager().setFrozen(false);
            return timeStatus();
        }));
        RpcRegistry.register("time.step", params -> {
            int ticks = Math.max(1, Params.integer(params, "ticks", 1));
            return ServerThread.callAsync(() -> step(ticks));
        });
        RpcRegistry.register("stop", params -> ServerThread.call(() -> {
            MinecraftServer server = ServerThread.server();
            Constants.LOG.info("MCTest: stopping the server on request");
            // Only flags the loop; the server then saves and exits after this tick, like /stop.
            server.halt(false);
            JsonObject out = new JsonObject();
            out.addProperty("stopping", true);
            return out;
        }));
    }

    // ---- status ----

    private static JsonObject status(MinecraftServer server) {
        String state = MCTestServer.state();
        JsonObject out = new JsonObject();
        out.addProperty("side", "server");
        out.addProperty("state", state);
        out.addProperty("ready", state.equals("running"));
        out.addProperty("minecraftVersion", SharedConstants.getCurrentVersion().name());
        out.addProperty("loader", Services.PLATFORM.getPlatformName());
        out.addProperty("loaderVersion", Services.PLATFORM.getLoaderVersion());
        out.addProperty("gameDir", Services.PLATFORM.getGameDir().toAbsolutePath().normalize().toString());
        out.addProperty("controlPort", ControlServer.configuredServerPort());
        if (server != null) {
            out.addProperty("port", server.getPort());
            out.addProperty("motd", server.getMotd());
            out.addProperty("onlineMode", server.usesAuthentication());
            out.addProperty("tickCount", server.getTickCount());
            out.addProperty("msPerTick", server.getCurrentSmoothedTickTime());
            out.addProperty("frozen", server.tickRateManager().isFrozen());
            out.addProperty("maxPlayers", server.getPlayerList().getMaxPlayers());
            JsonArray players = new JsonArray();
            server.getPlayerList().getPlayers().forEach(p -> players.add(p.getGameProfile().name()));
            out.add("players", players);
            JsonArray levels = new JsonArray();
            server.levelKeys().forEach(k -> levels.add(k.identifier().toString()));
            out.add("dimensions", levels);
        }
        out.add("mods", Describe.mods());
        return out;
    }

    // ---- command ----

    /**
     * Runs a command as the server console (full permissions) and returns what it printed. Output sent over the
     * following {@code ticks} ticks is included too (for commands that report later).
     */
    private static CompletableFuture<JsonElement> command(String command, int ticks) {
        MinecraftServer server = ServerThread.server();
        List<String> lines = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                lines.add(message.getString());
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return true;
            }
        };
        Boolean[] success = {null};
        int[] result = {0};
        CommandSourceStack source = server.createCommandSourceStack()
                .withSource(sink)
                .withCallback((ok, value) -> {
                    success[0] = ok;
                    result[0] = value;
                });
        Constants.LOG.info("MCTest: /{}", command);
        server.getCommands().performPrefixedCommand(source, command);

        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        Runnable finish = () -> {
            JsonObject out = new JsonObject();
            out.addProperty("command", command);
            out.addProperty("success", success[0]);
            out.addProperty("result", result[0]);
            JsonArray output = new JsonArray();
            lines.forEach(output::add);
            out.add("output", output);
            future.complete(out);
        };
        if (ticks == 0) {
            finish.run();
        } else {
            int[] remaining = {ticks};
            TickScheduler.add(() -> {
                if (--remaining[0] > 0) return false;
                finish.run();
                return true;
            });
        }
        return future;
    }

    // ---- players ----

    private static JsonElement players() {
        MinecraftServer server = ServerThread.server();
        JsonArray list = new JsonArray();
        for (ServerPlayer p : server.getPlayerList().getPlayers()) {
            list.add(player(server, p));
        }
        JsonObject out = new JsonObject();
        out.addProperty("count", list.size());
        out.add("players", list);
        return out;
    }

    private static JsonObject player(MinecraftServer server, ServerPlayer p) {
        JsonObject out = new JsonObject();
        out.addProperty("name", p.getGameProfile().name());
        out.addProperty("uuid", p.getUUID().toString());
        out.addProperty("entityId", p.getId());
        out.addProperty("op", server.getPlayerList().isOp(p.nameAndId()));
        out.addProperty("ping", p.connection == null ? null : p.connection.latency());
        out.add("pos", Describe.vec(p.position()));
        out.add("blockPos", Describe.pos(p.blockPosition()));
        out.addProperty("yaw", p.getYRot());
        out.addProperty("pitch", p.getXRot());
        out.addProperty("dimension", p.level().dimension().identifier().toString());
        out.addProperty("gamemode", p.gameMode.getGameModeForPlayer().getName());
        out.addProperty("health", p.getHealth());
        out.addProperty("maxHealth", p.getMaxHealth());
        out.addProperty("food", p.getFoodData().getFoodLevel());
        out.addProperty("saturation", p.getFoodData().getSaturationLevel());
        out.addProperty("xpLevel", p.experienceLevel);
        out.addProperty("onGround", p.onGround());

        Inventory inv = p.getInventory();
        out.addProperty("selectedSlot", inv.getSelectedSlot());
        JsonArray items = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject s = Describe.stack(stack);
            s.addProperty("slot", i);
            EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(i);
            s.addProperty("section", i < Inventory.getSelectionSize() ? "hotbar" : i < Inventory.INVENTORY_SIZE ? "main" : equipment != null ? equipment.getName() : "other");
            items.add(s);
        }
        out.add("inventory", items);
        out.addProperty("openMenu", p.containerMenu == p.inventoryMenu ? null : p.containerMenu.getClass().getName());

        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getEffect().getRegisteredName());
            o.addProperty("amplifier", e.getAmplifier());
            o.addProperty("duration", e.isInfiniteDuration() ? -1 : e.getDuration());
            effects.add(o);
        }
        out.add("effects", effects);
        return out;
    }

    // ---- entities ----

    private static JsonElement entities(String dimension, String playerName, Vec3 center, double radius, String type, int limit) {
        MinecraftServer server = ServerThread.server();
        ServerLevel level;
        Vec3 origin = center;
        Entity exclude = null;
        if (playerName != null) {
            ServerPlayer p = requirePlayer(server, playerName);
            level = p.level();
            origin = p.position();
            exclude = p;
        } else {
            level = level(dimension);
        }
        String wantedType = type == null ? null : normalizeId(type);
        List<Entity> found = new ArrayList<>();
        for (Entity e : level.getAllEntities()) {
            if (e == exclude) continue;
            if (origin != null && e.position().distanceTo(origin) > radius) continue;
            if (wantedType != null && !BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(wantedType)) continue;
            found.add(e);
        }
        if (origin != null) {
            Vec3 o = origin;
            found.sort(Comparator.comparingDouble(e -> e.position().distanceTo(o)));
        }
        JsonArray list = new JsonArray();
        for (Entity e : found.subList(0, Math.min(limit, found.size()))) {
            list.add(Describe.entity(e, origin));
        }
        JsonObject out = new JsonObject();
        out.addProperty("dimension", level.dimension().identifier().toString());
        out.addProperty("total", found.size());
        out.add("entities", list);
        return out;
    }

    // ---- waitFor ----

    private record Check(boolean passed, String observed) {
    }

    private static CompletableFuture<JsonElement> waitFor(String condition, Supplier<Check> check, int timeoutTicks) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] elapsed = {0};
        TickScheduler.add(() -> {
            elapsed[0]++;
            Check result;
            try {
                result = check.get();
            } catch (RpcException e) {
                result = new Check(false, e.getMessage());
            }
            if (result.passed()) {
                JsonObject out = new JsonObject();
                out.addProperty("condition", condition);
                out.addProperty("passed", true);
                out.addProperty("ticks", elapsed[0]);
                out.addProperty("observed", result.observed());
                future.complete(out);
                return true;
            }
            if (elapsed[0] >= timeoutTicks) {
                future.completeExceptionally(new RpcException("waitFor timed out after " + timeoutTicks + " ticks: "
                        + condition + " (last observed: " + result.observed() + ")"));
                return true;
            }
            return false;
        });
        return future;
    }

    private static final String KINDS = "ticks, log, player, noplayer, players, block, entity";

    private static Supplier<Check> parse(String condition) {
        int colon = condition.indexOf(':');
        if (colon < 0) {
            throw new RpcException("Condition must look like kind:value. Kinds: " + KINDS);
        }
        String kind = condition.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String arg = condition.substring(colon + 1).trim();
        return switch (kind) {
            case "ticks" -> {
                int n = number(arg, "ticks:<n>");
                long[] start = {-1};
                yield () -> {
                    if (start[0] < 0) start[0] = TickScheduler.tickCount() - 1;
                    long waited = TickScheduler.tickCount() - start[0];
                    return new Check(waited >= n, waited + " ticks");
                };
            }
            case "log" -> {
                if (arg.isEmpty()) throw new RpcException("Use log:<text>");
                long start = LogCapture.lastSeq();
                yield () -> {
                    JsonObject line = LogCapture.findSince(start, arg);
                    return line == null ? new Check(false, "no matching line yet")
                            : new Check(true, "[" + line.get("level").getAsString() + "] " + line.get("message").getAsString());
                };
            }
            case "player" -> () -> {
                boolean online = ServerThread.server().getPlayerList().getPlayer(arg) != null;
                return new Check(online, online ? arg + " online" : onlineNames());
            };
            case "noplayer" -> () -> {
                boolean online = ServerThread.server().getPlayerList().getPlayer(arg) != null;
                return new Check(!online, online ? arg + " online" : onlineNames());
            };
            case "players" -> {
                int n = number(arg, "players:<n>");
                yield () -> {
                    int count = ServerThread.server().getPlayerCount();
                    return new Check(count >= n, count + " online");
                };
            }
            case "block" -> block(arg);
            case "entity" -> entity(arg);
            default -> throw new RpcException("Unknown condition kind '" + kind + "'. Kinds: " + KINDS);
        };
    }

    /** {@code block:10,64,-3=minecraft:chest}, optionally {@code @minecraft:the_nether}. */
    private static Supplier<Check> block(String arg) {
        String[] dimSplit = arg.split("@", 2);
        String[] parts = dimSplit[0].split("=", 2);
        String[] xyz = parts[0].split(",");
        if (parts.length != 2 || xyz.length != 3) {
            throw new RpcException("Use block:<x>,<y>,<z>=<block id>[@dimension]");
        }
        BlockPos pos;
        try {
            pos = new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (NumberFormatException e) {
            throw new RpcException("Block coordinates must be integers: " + parts[0]);
        }
        String wanted = normalizeId(parts[1].trim());
        String dimension = dimSplit.length > 1 ? dimSplit[1].trim() : null;
        return () -> {
            String actual = BuiltInRegistries.BLOCK.getKey(level(dimension).getBlockState(pos).getBlock()).toString();
            return new Check(actual.equals(wanted), actual);
        };
    }

    /** {@code entity:minecraft:zombie} (anywhere in the overworld) or {@code entity:zombie@minecraft:the_nether}. */
    private static Supplier<Check> entity(String arg) {
        String[] parts = arg.split("@", 2);
        String type = normalizeId(parts[0].trim());
        String dimension = parts.length > 1 ? parts[1].trim() : null;
        return () -> {
            int count = 0;
            for (Entity e : level(dimension).getAllEntities()) {
                if (BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type)) count++;
            }
            return new Check(count > 0, count + " " + type + " loaded");
        };
    }

    private static int number(String arg, String usage) {
        try {
            return Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new RpcException("Use " + usage);
        }
    }

    private static String onlineNames() {
        List<String> names = ServerThread.server().getPlayerList().getPlayers().stream().map(p -> p.getGameProfile().name()).toList();
        return "online: " + names;
    }

    // ---- time ----

    private static JsonObject timeStatus() {
        MinecraftServer server = ServerThread.server();
        ServerTickRateManager trm = server.tickRateManager();
        JsonObject out = new JsonObject();
        out.addProperty("frozen", trm.isFrozen());
        out.addProperty("stepping", trm.isSteppingForward());
        out.addProperty("tickRate", trm.tickrate());
        out.addProperty("gameTime", server.overworld().getGameTime());
        return out;
    }

    private static CompletableFuture<JsonElement> step(int ticks) {
        MinecraftServer server = ServerThread.server();
        ServerTickRateManager trm = server.tickRateManager();
        if (!trm.isFrozen()) {
            throw new RpcException("The server is not frozen; call time.freeze first (or use waitFor ticks:n)");
        }
        long start = server.overworld().getGameTime();
        trm.stepGameIfPaused(ticks);
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] waited = {0};
        TickScheduler.add(() -> {
            waited[0]++;
            if (trm.isSteppingForward() && waited[0] < ticks * 3 + 40) return false;
            long now = server.overworld().getGameTime();
            JsonObject out = new JsonObject();
            out.addProperty("steppedTicks", now - start);
            out.addProperty("gameTime", now);
            future.complete(out);
            return true;
        });
        return future;
    }

    // ---- helpers ----

    /** A dimension by id ({@code the_nether} or {@code minecraft:the_nether}); the overworld when null. */
    private static ServerLevel level(String dimension) {
        MinecraftServer server = ServerThread.server();
        if (dimension == null || dimension.isBlank()) {
            return server.overworld();
        }
        Identifier id = Identifier.tryParse(normalizeId(dimension));
        ServerLevel level = id == null ? null : server.getLevel(ResourceKey.create(Registries.DIMENSION, id));
        if (level == null) {
            throw new RpcException("Unknown dimension '" + dimension + "'. Known: "
                    + server.levelKeys().stream().map(k -> k.identifier().toString()).toList());
        }
        return level;
    }

    private static ServerPlayer requirePlayer(MinecraftServer server, String name) {
        ServerPlayer p = server.getPlayerList().getPlayer(name);
        if (p == null) {
            throw new RpcException("Player '" + name + "' is not online (" + onlineNames() + ")");
        }
        return p;
    }

    private static String normalizeId(String id) {
        String lower = id.trim().toLowerCase(Locale.ROOT);
        return lower.contains(":") ? lower : "minecraft:" + lower;
    }

    private static String stripSlash(String command) {
        String c = command.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }
}
