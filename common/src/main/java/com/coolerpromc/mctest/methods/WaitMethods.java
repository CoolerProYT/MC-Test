package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.capture.LogCapture;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import org.apache.logging.log4j.Level;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * {@code waitFor}: a condition checked at the end of every client tick until it holds or the timeout expires.
 * Also hosts {@code logs}.
 */
public final class WaitMethods {

    private WaitMethods() {
    }

    /** What a condition saw this tick. */
    private record Check(boolean passed, String observed) {
    }

    public static void register() {
        RpcRegistry.register("waitFor", params -> {
            String condition = Params.string(params, "condition").trim();
            int timeout = Params.integer(params, "timeoutTicks", 100);
            Supplier<Check> check = parse(condition);
            if (condition.startsWith("ticks:")) {
                timeout = Math.max(timeout, Integer.parseInt(condition.substring(6).trim()) + 1);
            }
            int finalTimeout = timeout;
            return ClientThread.callAsync(() -> waitFor(condition, check, finalTimeout));
        });
        RpcRegistry.register("logs", params -> {
            long since = (long) Params.number(params, "since", 0);
            String level = Params.string(params, "level", null);
            Level minLevel = level == null ? null : Level.toLevel(level.toUpperCase(Locale.ROOT), null);
            if (level != null && minLevel == null) {
                throw new RpcException("Unknown level '" + level + "'. Use TRACE, DEBUG, INFO, WARN, ERROR or FATAL");
            }
            String contains = Params.string(params, "contains", null);
            int limit = Math.max(1, Params.integer(params, "limit", 200));
            return CompletableFuture.completedFuture(LogCapture.query(since, minLevel, contains, limit));
        });
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

    // ---- conditions ----

    private static Supplier<Check> parse(String condition) {
        int colon = condition.indexOf(':');
        if (colon < 0) {
            throw new RpcException("Condition must look like kind:value. Kinds: screen, slot, block, ticks, state, log, entity");
        }
        String kind = condition.substring(0, colon).trim().toLowerCase(Locale.ROOT);
        String arg = condition.substring(colon + 1).trim();
        return switch (kind) {
            case "screen" -> screen(arg);
            case "slot" -> slot(arg);
            case "block" -> block(arg);
            case "ticks" -> ticks(arg);
            case "log" -> log(arg);
            case "entity" -> entity(arg);
            case "state" -> () -> {
                String state = StatusMethods.state(ClientThread.mc());
                return new Check(state.equalsIgnoreCase(arg), state);
            };
            default -> throw new RpcException("Unknown condition kind '" + kind + "'. Kinds: screen, slot, block, ticks, state, log, entity");
        };
    }

    /** {@code screen:TitleScreen}, {@code screen:net.minecraft...InventoryScreen}, or {@code screen:none}. */
    private static Supplier<Check> screen(String wanted) {
        return () -> {
            Screen screen = ClientThread.mc().gui.screen();
            if (screen == null) {
                return new Check(wanted.equalsIgnoreCase("none") || wanted.equalsIgnoreCase("null"), "none");
            }
            Class<?> cls = screen.getClass();
            boolean match = false;
            // Also accept a superclass name, e.g. screen:AbstractContainerScreen.
            for (Class<?> c = cls; c != null && c != Object.class; c = c.getSuperclass()) {
                if (c.getName().equals(wanted) || c.getSimpleName().equals(wanted)) {
                    match = true;
                    break;
                }
            }
            return new Check(match, cls.getName());
        };
    }

    /** {@code slot:36=minecraft:diamond} or {@code slot:36=empty}, against the open menu (the inventory if none). */
    private static Supplier<Check> slot(String arg) {
        String[] parts = arg.split("=", 2);
        if (parts.length != 2) {
            throw new RpcException("Use slot:<index>=<item id|empty>");
        }
        int index;
        try {
            index = Integer.parseInt(parts[0].trim());
        } catch (NumberFormatException e) {
            throw new RpcException("Slot index must be a number: " + parts[0]);
        }
        String wanted = normalizeId(parts[1].trim());
        return () -> {
            AbstractContainerMenu menu = WorldMethods.requirePlayer().containerMenu;
            if (index < 0 || index >= menu.slots.size()) {
                return new Check(false, "slot " + index + " not in " + menu.getClass().getSimpleName() + " (" + menu.slots.size() + " slots)");
            }
            ItemStack stack = menu.slots.get(index).getItem();
            String actual = stack.isEmpty() ? "empty" : BuiltInRegistries.ITEM.getKey(stack.getItem()).toString();
            boolean match = wanted.equals("empty") || wanted.equals("minecraft:air") ? stack.isEmpty() : actual.equals(wanted);
            return new Check(match, stack.isEmpty() ? "empty" : actual + " x" + stack.getCount());
        };
    }

    /** {@code block:10,64,-3=minecraft:chest}. */
    private static Supplier<Check> block(String arg) {
        String[] parts = arg.split("=", 2);
        String[] xyz = parts[0].split(",");
        if (parts.length != 2 || xyz.length != 3) {
            throw new RpcException("Use block:<x>,<y>,<z>=<block id>");
        }
        BlockPos pos;
        try {
            pos = new BlockPos(Integer.parseInt(xyz[0].trim()), Integer.parseInt(xyz[1].trim()), Integer.parseInt(xyz[2].trim()));
        } catch (NumberFormatException e) {
            throw new RpcException("Block coordinates must be integers: " + parts[0]);
        }
        String wanted = normalizeId(parts[1].trim());
        return () -> {
            Minecraft mc = ClientThread.mc();
            WorldMethods.requirePlayer();
            String actual = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
            return new Check(actual.equals(wanted), actual);
        };
    }

    /** {@code ticks:20} simply waits that many client ticks. */
    private static Supplier<Check> ticks(String arg) {
        int n;
        try {
            n = Integer.parseInt(arg);
        } catch (NumberFormatException e) {
            throw new RpcException("Use ticks:<n>");
        }
        long[] start = {-1};
        return () -> {
            if (start[0] < 0) {
                start[0] = TickScheduler.tickCount() - 1;
            }
            long waited = TickScheduler.tickCount() - start[0];
            return new Check(waited >= n, waited + " ticks");
        };
    }

    /** {@code log:some text}: a log line containing the text appears after the wait starts. */
    private static Supplier<Check> log(String needle) {
        if (needle.isEmpty()) throw new RpcException("Use log:<text>");
        long start = LogCapture.lastSeq();
        return () -> {
            JsonObject line = LogCapture.findSince(start, needle);
            return line == null ? new Check(false, "no matching line yet")
                    : new Check(true, "[" + line.get("level").getAsString() + "] " + line.get("message").getAsString());
        };
    }

    /** {@code entity:minecraft:zombie} or {@code entity:zombie@8}: such an entity within the radius (default 32). */
    private static Supplier<Check> entity(String arg) {
        String[] parts = arg.split("@", 2);
        String type = normalizeId(parts[0].trim());
        double radius;
        try {
            radius = parts.length > 1 ? Double.parseDouble(parts[1].trim()) : 32;
        } catch (NumberFormatException e) {
            throw new RpcException("Use entity:<type>[@radius]");
        }
        return () -> {
            var player = WorldMethods.requirePlayer();
            var found = player.level().getEntities(player, player.getBoundingBox().inflate(radius),
                    e -> BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(type) && e.distanceTo(player) <= radius);
            return new Check(!found.isEmpty(), found.size() + " " + type + " within " + radius);
        };
    }

    private static String normalizeId(String id) {
        String lower = id.toLowerCase(Locale.ROOT);
        return lower.equals("empty") || lower.contains(":") ? lower : "minecraft:" + lower;
    }
}
