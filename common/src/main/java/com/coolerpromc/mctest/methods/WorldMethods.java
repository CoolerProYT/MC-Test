package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.capture.ChatCapture;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.ai.attributes.AttributeInstance;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

public final class WorldMethods {

    private WorldMethods() {
    }

    public static void register() {
        RpcRegistry.register("command", params -> {
            String command = stripSlash(Params.string(params, "command"));
            int ticks = Math.max(1, Params.integer(params, "ticks", 5));
            return ClientThread.callAsync(() -> command(command, ticks));
        });
        RpcRegistry.register("player", params -> ClientThread.call(WorldMethods::player));
        RpcRegistry.register("block", params -> {
            BlockPos pos = BlockPos.containing(Params.number(params, "x"), Params.number(params, "y"), Params.number(params, "z"));
            String source = Params.string(params, "source", "auto").toLowerCase(Locale.ROOT);
            return ClientThread.callAsync(() -> block(pos, source));
        });
        RpcRegistry.register("entities", params -> {
            double radius = Math.min(256, Params.number(params, "radius", 16));
            String type = Params.string(params, "type", null);
            int limit = Params.integer(params, "limit", 50);
            return ClientThread.call(() -> entities(radius, type, limit));
        });
        RpcRegistry.register("teleport", params -> {
            double x = Params.number(params, "x");
            double y = Params.number(params, "y");
            double z = Params.number(params, "z");
            String rot = Params.has(params, "yaw") || Params.has(params, "pitch")
                    ? String.format(Locale.ROOT, " %s %s", num(Params.number(params, "yaw", 0)), num(Params.number(params, "pitch", 0)))
                    : "";
            String command = String.format(Locale.ROOT, "tp @s %s %s %s%s", num(x), num(y), num(z), rot);
            return ClientThread.callAsync(() -> teleport(command, new Vec3(x, y, z)));
        });
        RpcRegistry.register("lookAt", params -> {
            Vec3 target = new Vec3(Params.number(params, "x"), Params.number(params, "y"), Params.number(params, "z"));
            return ClientThread.call(() -> {
                LocalPlayer player = requirePlayer();
                player.lookAt(EntityAnchorArgument.Anchor.EYES, target);
                JsonObject out = new JsonObject();
                out.addProperty("yaw", player.getYRot());
                out.addProperty("pitch", player.getXRot());
                return out;
            });
        });
    }

    // ---- command ----

    private static CompletableFuture<JsonElement> command(String command, int ticks) {
        requirePlayer();
        ClientPacketListener connection = ClientThread.mc().getConnection();
        if (connection == null) {
            throw new RpcException("Not connected to a world");
        }
        ChatCapture.Session capture = ChatCapture.open();
        connection.sendCommand(command);

        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] remaining = {ticks};
        TickScheduler.add(() -> {
            if (--remaining[0] > 0) {
                return false;
            }
            JsonObject out = new JsonObject();
            out.addProperty("command", command);
            out.add("output", capture.close());
            future.complete(out);
            return true;
        });
        return future;
    }

    private static CompletableFuture<JsonElement> teleport(String command, Vec3 target) {
        LocalPlayer player = requirePlayer();
        ClientPacketListener connection = ClientThread.mc().getConnection();
        ChatCapture.Session capture = ChatCapture.open();
        connection.sendCommand(command);

        // Done once the server's position update reaches the client (within a block: collision may nudge us off
        // the exact target), or after 40 ticks (e.g. no permission).
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] waited = {0};
        TickScheduler.add(() -> {
            boolean arrived = player.position().distanceToSqr(target) < 1.0;
            if (!arrived && ++waited[0] < 40) {
                return false;
            }
            JsonObject out = new JsonObject();
            out.addProperty("arrived", arrived);
            out.add("pos", vec(player.position()));
            out.add("output", capture.close());
            future.complete(out);
            return true;
        });
        return future;
    }

    // ---- player ----

    private static JsonElement player() {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = requirePlayer();
        JsonObject out = new JsonObject();
        out.addProperty("name", p.getName().getString());
        out.addProperty("uuid", p.getUUID().toString());
        out.add("pos", vec(p.position()));
        out.add("blockPos", pos(p.blockPosition()));
        out.addProperty("yaw", p.getYRot());
        out.addProperty("pitch", p.getXRot());
        out.add("velocity", vec(p.getDeltaMovement()));
        out.addProperty("dimension", p.level().dimension().identifier().toString());
        out.addProperty("gamemode", mc.gameMode == null ? null : mc.gameMode.getPlayerMode().getName());
        out.addProperty("health", p.getHealth());
        out.addProperty("maxHealth", p.getMaxHealth());
        out.addProperty("absorption", p.getAbsorptionAmount());
        out.addProperty("armor", p.getArmorValue());
        out.addProperty("food", p.getFoodData().getFoodLevel());
        out.addProperty("saturation", p.getFoodData().getSaturationLevel());
        JsonObject xp = new JsonObject();
        xp.addProperty("level", p.experienceLevel);
        xp.addProperty("progress", p.experienceProgress);
        xp.addProperty("total", p.totalExperience);
        out.add("xp", xp);
        out.addProperty("onGround", p.onGround());
        out.addProperty("sprinting", p.isSprinting());
        out.addProperty("crouching", p.isCrouching());
        out.add("lookingAt", hitResult(mc.hitResult));

        Inventory inv = p.getInventory();
        out.addProperty("selectedSlot", inv.getSelectedSlot());
        JsonArray items = new JsonArray();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack stack = inv.getItem(i);
            if (stack.isEmpty()) continue;
            JsonObject s = ScreenInspector.stack(stack);
            s.addProperty("slot", i);
            EquipmentSlot equipment = Inventory.EQUIPMENT_SLOT_MAPPING.get(i);
            s.addProperty("section", i < Inventory.getSelectionSize() ? "hotbar" : i < Inventory.INVENTORY_SIZE ? "main" : equipment != null ? equipment.getName() : "other");
            items.add(s);
        }
        out.add("inventory", items);

        JsonArray effects = new JsonArray();
        for (MobEffectInstance e : p.getActiveEffects()) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getEffect().getRegisteredName());
            o.addProperty("amplifier", e.getAmplifier());
            o.addProperty("duration", e.isInfiniteDuration() ? -1 : e.getDuration());
            effects.add(o);
        }
        out.add("effects", effects);

        JsonArray attributes = new JsonArray();
        // Every registered attribute (vanilla and modded) the player actually has.
        BuiltInRegistries.ATTRIBUTE.listElements()
                .map(holder -> p.getAttributes().getInstance(holder))
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(a -> a.getAttribute().getRegisteredName()))
                .forEach((AttributeInstance a) -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("id", a.getAttribute().getRegisteredName());
                    o.addProperty("value", a.getValue());
                    o.addProperty("base", a.getBaseValue());
                    attributes.add(o);
                });
        out.add("attributes", attributes);
        return out;
    }

    private static JsonElement hitResult(HitResult hit) {
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            return null;
        }
        JsonObject o = new JsonObject();
        if (hit instanceof BlockHitResult b) {
            o.addProperty("type", "block");
            o.add("pos", pos(b.getBlockPos()));
            o.addProperty("face", b.getDirection().getName());
            Level level = ClientThread.mc().level;
            if (level != null) {
                o.addProperty("block", BuiltInRegistries.BLOCK.getKey(level.getBlockState(b.getBlockPos()).getBlock()).toString());
            }
        } else if (hit instanceof EntityHitResult e) {
            o.addProperty("type", "entity");
            o.addProperty("entityId", e.getEntity().getId());
            o.addProperty("entityType", BuiltInRegistries.ENTITY_TYPE.getKey(e.getEntity().getType()).toString());
        }
        return o;
    }

    // ---- block ----

    /**
     * In singleplayer the integrated server's view is authoritative (block entities often sync only partially to the
     * client), so read it on the server thread. Otherwise, or with {@code source: "client"}, read the client level.
     */
    private static CompletableFuture<JsonElement> block(BlockPos pos, String source) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer player = requirePlayer();
        MinecraftServer server = mc.getSingleplayerServer();
        boolean useServer = switch (source) {
            case "auto" -> server != null;
            case "server" -> {
                if (server == null) throw new RpcException("source=server needs a singleplayer world");
                yield true;
            }
            case "client" -> false;
            default -> throw new RpcException("source must be auto, server or client");
        };
        if (useServer) {
            var dimension = player.level().dimension();
            return server.submit(() -> {
                ServerLevel level = server.getLevel(dimension);
                if (level == null) throw new RpcException("Server has no level " + dimension.identifier());
                return describeBlock(level, pos, "server");
            });
        }
        return CompletableFuture.completedFuture(describeBlock(mc.level, pos, "client"));
    }

    private static JsonElement describeBlock(Level level, BlockPos pos, String source) {
        JsonObject out = new JsonObject();
        out.add("pos", pos(pos));
        out.addProperty("source", source);
        out.addProperty("loaded", level.isLoaded(pos));
        BlockState state = level.getBlockState(pos);
        out.addProperty("id", BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getValues().forEach(v -> properties.addProperty(v.property().getName(), v.valueName()));
        out.add("properties", properties);
        BlockEntity be = level.getBlockEntity(pos);
        if (be != null) {
            JsonObject b = new JsonObject();
            b.addProperty("type", String.valueOf(BuiltInRegistries.BLOCK_ENTITY_TYPE.getKey(be.getType())));
            b.addProperty("nbt", be.saveWithFullMetadata(level.registryAccess()).toString());
            out.add("blockEntity", b);
        } else {
            out.add("blockEntity", null);
        }
        return out;
    }

    // ---- entities ----

    private static JsonElement entities(double radius, String type, int limit) {
        LocalPlayer player = requirePlayer();
        String wantedType = type == null ? null : (type.contains(":") ? type : "minecraft:" + type);
        Vec3 origin = player.position();
        List<Entity> found = player.level().getEntities(player, player.getBoundingBox().inflate(radius), e ->
                e.position().distanceTo(origin) <= radius
                        && (wantedType == null || BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(wantedType)));
        found.sort(Comparator.comparingDouble(e -> e.position().distanceTo(origin)));

        JsonArray list = new JsonArray();
        for (Entity e : found.subList(0, Math.min(limit, found.size()))) {
            JsonObject o = new JsonObject();
            o.addProperty("id", e.getId());
            o.addProperty("uuid", e.getUUID().toString());
            o.addProperty("type", BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString());
            o.addProperty("name", e.getName().getString());
            o.addProperty("customName", e.getCustomName() == null ? null : e.getCustomName().getString());
            o.add("pos", vec(e.position()));
            o.addProperty("distance", e.position().distanceTo(origin));
            o.addProperty("yaw", e.getYRot());
            o.addProperty("pitch", e.getXRot());
            if (e instanceof LivingEntity living) {
                o.addProperty("health", living.getHealth());
                o.addProperty("maxHealth", living.getMaxHealth());
            }
            if (e instanceof ItemEntity item) {
                o.add("item", ScreenInspector.stack(item.getItem()));
            }
            list.add(o);
        }
        JsonObject out = new JsonObject();
        out.addProperty("total", found.size());
        out.add("entities", list);
        return out;
    }

    // ---- helpers ----

    static LocalPlayer requirePlayer() {
        Minecraft mc = ClientThread.mc();
        if (mc.player == null || mc.level == null) {
            throw new RpcException("Not in a world (state: " + StatusMethods.state(mc) + ")");
        }
        return mc.player;
    }

    private static String stripSlash(String command) {
        String c = command.trim();
        return c.startsWith("/") ? c.substring(1) : c;
    }

    private static String num(double d) {
        return d == Math.rint(d) && Math.abs(d) < 1e9 ? Long.toString((long) d) + ".0" : Double.toString(d);
    }

    static JsonObject vec(Vec3 v) {
        JsonObject o = new JsonObject();
        o.addProperty("x", v.x);
        o.addProperty("y", v.y);
        o.addProperty("z", v.z);
        return o;
    }

    static JsonObject pos(BlockPos p) {
        JsonObject o = new JsonObject();
        o.addProperty("x", p.getX());
        o.addProperty("y", p.getY());
        o.addProperty("z", p.getZ());
        return o;
    }
}
