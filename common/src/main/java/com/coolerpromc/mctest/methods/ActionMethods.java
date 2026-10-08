package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.input.InputGuard;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.KeyMapping;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.commands.arguments.EntityAnchorArgument;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ServerboundPunchPacket;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.EntityHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Player-level actions: keybinds (including other mods' keybinds), hotbar, block/entity interaction, mining, chat,
 * scrolling, tooltips and resource reloads. Interactions go through {@code MultiPlayerGameMode}, i.e. exactly the
 * packets a real client sends, so the server validates reach, permissions and so on.
 */
public final class ActionMethods {

    private static boolean holdingUse;

    private ActionMethods() {
    }

    /** True while {@code useItem} holds the use key; see {@code MinecraftMixin}. Render thread only. */
    public static boolean isHoldingUse() {
        return holdingUse;
    }

    public static void register() {
        RpcRegistry.register("keybinds", params -> ClientThread.call(ActionMethods::keybinds));
        RpcRegistry.register("keybind", params -> {
            String name = Params.string(params, "name");
            String action = Params.string(params, "action", "tap").toLowerCase(Locale.ROOT);
            int ticks = Params.integer(params, "ticks", 1);
            return ClientThread.callAsync(() -> keybind(name, action, ticks));
        });
        RpcRegistry.register("selectSlot", params -> {
            int slot = Params.integer(params, "slot");
            if (slot < 0 || slot > 8) throw new RpcException("slot must be 0-8 (hotbar)");
            return ClientThread.call(() -> {
                LocalPlayer p = WorldMethods.requirePlayer();
                p.getInventory().setSelectedSlot(slot);
                JsonObject out = new JsonObject();
                out.addProperty("selectedSlot", slot);
                out.add("item", ScreenInspector.stack(p.getInventory().getItem(slot)));
                return out;
            });
        });
        RpcRegistry.register("look", params -> {
            float yaw = (float) Params.number(params, "yaw");
            float pitch = (float) Math.max(-90, Math.min(90, Params.number(params, "pitch")));
            return ClientThread.call(() -> {
                LocalPlayer p = WorldMethods.requirePlayer();
                p.setYRot(yaw);
                p.setXRot(pitch);
                p.yRotO = yaw;
                p.xRotO = pitch;
                JsonObject out = new JsonObject();
                out.addProperty("yaw", p.getYRot());
                out.addProperty("pitch", p.getXRot());
                return out;
            });
        });
        RpcRegistry.register("useOn", params -> {
            BlockPos pos = blockPos(params);
            Direction face = direction(Params.string(params, "face", "up"));
            InteractionHand hand = hand(params);
            return ClientThread.call(() -> useOn(pos, face, hand));
        });
        RpcRegistry.register("useItem", params -> {
            InteractionHand hand = hand(params);
            int ticks = Params.integer(params, "ticks", 0);
            return ClientThread.callAsync(() -> useItem(hand, ticks));
        });
        RpcRegistry.register("breakBlock", params -> {
            BlockPos pos = blockPos(params);
            Direction face = direction(Params.string(params, "face", "up"));
            int timeout = Params.integer(params, "timeoutTicks", 600);
            return ClientThread.callAsync(() -> breakBlock(pos, face, timeout));
        });
        RpcRegistry.register("attackEntity", params -> {
            int id = Params.integer(params, "id");
            return ClientThread.call(() -> {
                LocalPlayer p = WorldMethods.requirePlayer();
                Entity target = entity(id);
                p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
                ClientThread.mc().gameMode.attack(p, target);
                attackSwing(p);
                JsonObject out = new JsonObject();
                out.addProperty("attacked", BuiltInRegistries.ENTITY_TYPE.getKey(target.getType()).toString());
                out.addProperty("distance", p.distanceTo(target));
                return out;
            });
        });
        RpcRegistry.register("interactEntity", params -> {
            int id = Params.integer(params, "id");
            InteractionHand hand = hand(params);
            return ClientThread.call(() -> {
                Minecraft mc = ClientThread.mc();
                LocalPlayer p = WorldMethods.requirePlayer();
                Entity target = entity(id);
                p.lookAt(EntityAnchorArgument.Anchor.EYES, target.getBoundingBox().getCenter());
                Screen before = mc.gui.screen();
                EntityHitResult hit = new EntityHitResult(target, target.getBoundingBox().getCenter());
                InteractionResult result = mc.gameMode.interact(p, target, hit, hand);
                swingIfPredicted(p, hand, result);
                JsonObject out = new JsonObject();
                out.addProperty("result", result.getClass().getSimpleName());
                out.add("screenAfter", StatusMethods.describeScreen(mc.gui.screen()));
                out.addProperty("screenChanged", before != mc.gui.screen());
                return out;
            });
        });
        RpcRegistry.register("chat", params -> {
            String message = Params.string(params, "message");
            return ClientThread.call(() -> {
                WorldMethods.requirePlayer();
                ClientThread.mc().getConnection().sendChat(message);
                JsonObject out = new JsonObject();
                out.addProperty("sent", message);
                return out;
            });
        });
        RpcRegistry.register("scroll", params -> {
            double amount = Params.number(params, "amount");
            Double x = Params.has(params, "x") ? Params.number(params, "x") : null;
            Double y = Params.has(params, "y") ? Params.number(params, "y") : null;
            return ClientThread.call(() -> InputGuard.synthetic(() -> scroll(x, y, amount)));
        });
        RpcRegistry.register("tooltip", params -> {
            int slot = Params.integer(params, "slot");
            boolean advanced = Params.bool(params, "advanced", false);
            return ClientThread.call(() -> tooltip(slot, advanced));
        });
        RpcRegistry.register("reloadResources", params -> ClientThread.callAsync(() -> {
            long start = System.currentTimeMillis();
            return ClientThread.mc().reloadResourcePacks().thenApply(v -> {
                JsonObject out = new JsonObject();
                out.addProperty("reloadMs", System.currentTimeMillis() - start);
                return (JsonElement) out;
            });
        }));
    }

    // ---- keybinds ----

    private static JsonElement keybinds() {
        Minecraft mc = ClientThread.mc();
        JsonArray list = new JsonArray();
        Arrays.stream(mc.options.keyMappings)
                .sorted(Comparator.comparing(KeyMapping::getName))
                .forEach(k -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("name", k.getName());
                    o.addProperty("label", Component.translatable(k.getName()).getString());
                    o.addProperty("category", k.getCategory().id().toString());
                    o.addProperty("key", k.saveString());
                    o.addProperty("down", k.isDown());
                    list.add(o);
                });
        JsonObject out = new JsonObject();
        out.add("keybinds", list);
        return out;
    }

    private static KeyMapping mapping(String name) {
        Minecraft mc = ClientThread.mc();
        String n = name.trim();
        KeyMapping k = KeyMapping.get(n);
        if (k == null) k = KeyMapping.get("key." + n);
        if (k == null) {
            // Friendly aliases for the vanilla names that differ from what players call them.
            k = switch (n.toLowerCase(Locale.ROOT)) {
                case "back", "backward" -> mc.options.keyDown;
                case "left" -> mc.options.keyLeft;
                case "right" -> mc.options.keyRight;
                case "crouch", "shift" -> mc.options.keyShift;
                case "drop_item" -> mc.options.keyDrop;
                case "pick", "pick_block" -> mc.options.keyPickItem;
                default -> null;
            };
        }
        if (k == null) {
            throw new RpcException("Unknown keybind '" + name + "'. Call keybinds for the list (e.g. key.forward, key.attack, key.use)");
        }
        return k;
    }

    /**
     * tap: one click (what a quick key press does); press/release: hold or let go; hold: press for {@code ticks}
     * client ticks, then release. Holding drives the mapping directly, so it works even if the user rebound the key.
     */
    private static CompletableFuture<JsonElement> keybind(String name, String action, int ticks) {
        KeyMapping k = mapping(name);
        InputConstants.Key bound = k.isUnbound() ? null : InputConstants.getKey(k.saveString());
        JsonObject out = new JsonObject();
        out.addProperty("name", k.getName());
        switch (action) {
            case "tap" -> {
                if (bound != null) {
                    KeyMapping.click(bound);
                } else {
                    throw new RpcException(k.getName() + " is unbound; bind it or use press/hold");
                }
                k.setDown(true);
                // Release next tick so "isDown" consumers see one tick of press, like a real tap.
                return releaseAfter(k, 1, out);
            }
            case "press" -> {
                if (bound != null) KeyMapping.click(bound);
                k.setDown(true);
            }
            case "release" -> k.setDown(false);
            case "hold" -> {
                if (ticks < 1) throw new RpcException("hold needs ticks >= 1");
                if (bound != null) KeyMapping.click(bound);
                k.setDown(true);
                return releaseAfter(k, ticks, out);
            }
            default -> throw new RpcException("action must be tap, press, release or hold");
        }
        out.addProperty("down", k.isDown());
        return CompletableFuture.completedFuture(out);
    }

    private static CompletableFuture<JsonElement> releaseAfter(KeyMapping k, int ticks, JsonObject out) {
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] left = {ticks};
        TickScheduler.add(() -> {
            // Opening a screen releases all keys in vanilla; keep re-asserting so a hold survives brief UI.
            if (--left[0] > 0) {
                if (ClientThread.mc().gui.screen() == null) k.setDown(true);
                return false;
            }
            k.setDown(false);
            out.addProperty("heldTicks", ticks);
            LocalPlayer p = ClientThread.mc().player;
            if (p != null) out.add("pos", WorldMethods.vec(p.position()));
            future.complete(out);
            return true;
        });
        return future;
    }

    // ---- interaction ----

    private static JsonElement useOn(BlockPos pos, Direction face, InteractionHand hand) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        Screen before = mc.gui.screen();
        Vec3 hitVec = Vec3.atCenterOf(pos).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
        BlockHitResult hit = new BlockHitResult(hitVec, face, pos, false);
        p.lookAt(EntityAnchorArgument.Anchor.EYES, hitVec);
        String blockBefore = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
        InteractionResult result = mc.gameMode.useItemOn(p, hand, hit);
        swingIfPredicted(p, hand, result);
        JsonObject out = new JsonObject();
        out.addProperty("block", blockBefore);
        out.addProperty("result", result.getClass().getSimpleName());
        out.addProperty("consumesAction", result.consumesAction());
        out.addProperty("distance", Math.sqrt(p.getEyePosition().distanceToSqr(hitVec)));
        out.add("screenAfter", StatusMethods.describeScreen(mc.gui.screen()));
        out.addProperty("screenChanged", before != mc.gui.screen());
        return out;
    }

    private static CompletableFuture<JsonElement> useItem(InteractionHand hand, int ticks) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        ItemStack item = p.getItemInHand(hand).copy();
        InteractionResult result = mc.gameMode.useItem(p, hand);
        swingIfPredicted(p, hand, result);
        JsonObject out = new JsonObject();
        out.add("item", ScreenInspector.stack(item));
        out.addProperty("result", result.getClass().getSimpleName());
        if (ticks <= 0 || !p.isUsingItem()) {
            out.addProperty("usingItem", p.isUsingItem());
            return CompletableFuture.completedFuture(out);
        }
        // Keep using (eat, draw a bow, ...) for `ticks`, then release like letting go of right-click. Vanilla stops
        // using an item on any tick where the use key is not down, so hold the mapping for the duration.
        KeyMapping use = mc.options.keyUse;
        use.setDown(true);
        holdingUse = true;
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] left = {ticks};
        TickScheduler.add(() -> {
            if (--left[0] > 0 && p.isUsingItem()) {
                use.setDown(true);
                return false;
            }
            boolean stillUsing = p.isUsingItem();
            use.setDown(false);
            if (stillUsing) {
                mc.gameMode.releaseUsingItem(p);
            }
            // Keep suppressing vanilla's held-key re-use for one more tick: the server's "finished using" packet is
            // processed between ticks, before the next keybind pass.
            TickScheduler.add(() -> {
                holdingUse = false;
                return true;
            });
            out.addProperty("finishedEarly", !stillUsing);
            out.add("itemAfter", ScreenInspector.stack(p.getItemInHand(hand)));
            future.complete(out);
            return true;
        });
        return future;
    }

    /** Mines like holding left-click: creative breaks instantly, survival takes the real dig time. */
    static CompletableFuture<JsonElement> breakBlock(BlockPos pos, Direction face, int timeoutTicks) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        String block = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString();
        if (mc.level.getBlockState(pos).isAir()) {
            throw new RpcException("No block at " + pos.toShortString());
        }
        p.lookAt(EntityAnchorArgument.Anchor.EYES, Vec3.atCenterOf(pos));
        mc.gameMode.startDestroyBlock(pos, face);
        attackSwing(p);

        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] elapsed = {0};
        TickScheduler.add(() -> {
            elapsed[0]++;
            boolean gone = mc.level == null || !BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString().equals(block);
            if (gone || elapsed[0] >= timeoutTicks || mc.player == null) {
                if (!gone && mc.gameMode != null) mc.gameMode.stopDestroyBlock();
                JsonObject out = new JsonObject();
                out.addProperty("block", block);
                out.addProperty("broken", gone);
                out.addProperty("ticks", elapsed[0]);
                if (mc.level != null) out.addProperty("blockAfter", BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(pos).getBlock()).toString());
                if (gone) {
                    future.complete(out);
                } else {
                    future.completeExceptionally(new RpcException("Block " + block + " at " + pos.toShortString() + " not broken after "
                            + elapsed[0] + " ticks (too far? wrong tool? protected?)"));
                }
                return true;
            }
            if (mc.gameMode.continueDestroyBlock(pos, face)) {
                attackSwing(p);
            }
            return false;
        });
        return future;
    }

    /** What vanilla does after a left-click attack or dig tick. */
    static void attackSwing(LocalPlayer p) {
        p.swing(InteractionHand.MAIN_HAND, p.getMainHandItem().getAttackAnimation(), false);
        p.connection.send(ServerboundPunchPacket.INSTANCE);
    }

    /** What vanilla does after a right-click whose result the client predicted. */
    private static void swingIfPredicted(LocalPlayer p, InteractionHand hand, InteractionResult result) {
        if (result instanceof InteractionResult.Success success && success.swingSource() == InteractionResult.SwingSource.PREDICTED) {
            p.swing(hand, p.getItemInHand(hand).getInteractAnimation(), false);
        }
    }

    // ---- misc ----

    private static JsonElement scroll(Double guiX, Double guiY, double amount) {
        Minecraft mc = ClientThread.mc();
        Window window = mc.getWindow();
        if (guiX != null && guiY != null) {
            mc.mouseHandler.onMove(window.handle(), guiX * window.getScreenWidth() / window.getGuiScaledWidth(),
                    guiY * window.getScreenHeight() / window.getGuiScaledHeight(), 0, 0);
        }
        // Positive = scroll up (wheel away from you), as in vanilla.
        mc.mouseHandler.onScroll(window.handle(), 0, amount);
        JsonObject out = new JsonObject();
        out.addProperty("scrolled", amount);
        if (mc.player != null) out.addProperty("selectedSlot", mc.player.getInventory().getSelectedSlot());
        return out;
    }

    /** Tooltip lines of a slot in the open container, or of a player inventory slot if no container is open. */
    private static JsonElement tooltip(int slot, boolean advanced) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        ItemStack stack;
        String source;
        if (mc.gui.screen() instanceof AbstractContainerScreen<?> screen) {
            source = "menu:" + screen.getMenu().getClass().getSimpleName();
            AbstractContainerMenu menu = screen.getMenu();
            if (slot < 0 || slot >= menu.slots.size()) throw new RpcException("Slot " + slot + " out of range for the open menu");
            stack = menu.slots.get(slot).getItem();
        } else {
            source = "inventory";
            if (slot < 0 || slot >= p.getInventory().getContainerSize()) throw new RpcException("Inventory slot out of range");
            stack = p.getInventory().getItem(slot);
        }
        List<String> lines = new ArrayList<>();
        if (!stack.isEmpty()) {
            TooltipFlag flag = advanced ? TooltipFlag.ADVANCED : TooltipFlag.NORMAL;
            for (Component c : stack.getTooltipLines(net.minecraft.world.item.Item.TooltipContext.of(mc.level), p, flag)) {
                lines.add(c.getString());
            }
        }
        JsonObject out = new JsonObject();
        // Slot numbering differs: menu slot index (as in snapshot) when a container is open, else inventory index.
        out.addProperty("source", source);
        out.add("item", ScreenInspector.stack(stack));
        JsonArray arr = new JsonArray();
        lines.forEach(arr::add);
        out.add("lines", arr);
        return out;
    }

    // ---- params ----

    private static BlockPos blockPos(JsonObject params) {
        return new BlockPos(Params.integer(params, "x"), Params.integer(params, "y"), Params.integer(params, "z"));
    }

    private static Direction direction(String name) {
        Direction d = Direction.byName(name.toLowerCase(Locale.ROOT));
        if (d == null) throw new RpcException("face must be up, down, north, south, east or west");
        return d;
    }

    private static InteractionHand hand(JsonObject params) {
        String h = Params.string(params, "hand", "main").toLowerCase(Locale.ROOT);
        return switch (h) {
            case "main", "main_hand", "mainhand" -> InteractionHand.MAIN_HAND;
            case "off", "off_hand", "offhand" -> InteractionHand.OFF_HAND;
            default -> throw new RpcException("hand must be main or off");
        };
    }

    private static Entity entity(int id) {
        Entity e = ClientThread.mc().level.getEntity(id);
        if (e == null) throw new RpcException("No entity with id " + id + " (use entities to find ids)");
        return e;
    }
}
