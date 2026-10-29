package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.nav.Navigator;
import com.coolerpromc.mctest.nav.Pathfinder;
import com.coolerpromc.mctest.nav.PlayerControl;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.gui.screens.inventory.InventoryScreen;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerTickRateManager;
import net.minecraft.tags.ItemTags;
import net.minecraft.tags.TagKey;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.item.ItemEntity;
import net.minecraft.world.entity.monster.Enemy;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.entity.player.StackedContents;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ClickType;
import net.minecraft.world.inventory.RecipeBookMenu;
import net.minecraft.world.inventory.CraftingMenu;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.crafting.CraftingRecipe;
import net.minecraft.world.item.crafting.RecipeHolder;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Higher-level, player-like skills built from the primitives: time control (freeze/step/lockstep), walking with
 * pathfinding, choosing tools, gathering, fighting with proper attack timing, crafting via the recipe book, and
 * scanning the surroundings.
 */
public final class SkillMethods {

    /** Step floors of staircases dug this session; collect leaves them alone so the way back stays climbable. */
    private static final Set<BlockPos> STAIR_STEPS = new HashSet<>();
    private static Level stairLevel;

    private static boolean lockstep;
    private static Boolean lockstepFrozen;
    private static MinecraftServer lockstepServer;

    private SkillMethods() {
    }

    public static void register() {
        // ---- time ----
        RpcRegistry.register("time.status", params -> ClientThread.call(SkillMethods::timeStatus));
        RpcRegistry.register("time.freeze", params -> ClientThread.callAsync(() -> setFrozen(true)));
        RpcRegistry.register("time.unfreeze", params -> ClientThread.callAsync(() -> setFrozen(false)));
        RpcRegistry.register("time.step", params -> {
            int ticks = Math.max(1, Params.integer(params, "ticks", 1));
            return ClientThread.callAsync(() -> step(ticks));
        });
        RpcRegistry.register("time.lockstep", params -> {
            boolean enabled = Params.bool(params, "enabled", true);
            return ClientThread.call(() -> {
                server();
                lockstep = enabled;
                lockstepFrozen = null;
                if (!enabled) setFrozenNow(false);
                return timeStatus();
            });
        });

        // ---- movement ----
        RpcRegistry.register("goto", params -> {
            Integer entityId = Params.has(params, "entity") ? Params.integer(params, "entity") : null;
            boolean sprint = Params.bool(params, "sprint", true);
            int timeout = Params.integer(params, "timeoutTicks", 2400);
            Double range = Params.has(params, "range") ? Params.number(params, "range") : null;
            Vec3 point = entityId == null ? new Vec3(Params.number(params, "x"), Params.number(params, "y"), Params.number(params, "z")) : null;
            return ClientThread.callAsync(() -> {
                Minecraft mc = ClientThread.mc();
                WorldMethods.requirePlayer();
                Vec3 target = point;
                double r = range != null ? range : 1.0;
                if (entityId != null) {
                    Entity e = mc.level.getEntity(entityId);
                    if (e == null) throw new RpcException("No entity with id " + entityId);
                    target = e.position();
                    if (range == null) r = 2.0;
                }
                return Navigator.go(mc, new Navigator.Request(Navigator.near(target, Math.max(0.5, r)), BlockPos.containing(target), sprint, timeout))
                        .thenApply(o -> (JsonElement) o);
            });
        });

        // ---- inventory ----
        RpcRegistry.register("selectItem", params -> {
            String item = normalizeId(Params.string(params, "item"));
            return ClientThread.call(() -> {
                LocalPlayer p = WorldMethods.requirePlayer();
                int slot = selectItem(ClientThread.mc(), p, s -> itemId(s).equals(item));
                if (slot < 0) throw new RpcException("No " + item + " in the inventory");
                JsonObject out = new JsonObject();
                out.addProperty("selectedSlot", slot);
                out.add("item", ScreenInspector.stack(p.getInventory().getItem(slot)));
                return out;
            });
        });

        // ---- perception ----
        RpcRegistry.register("scanBlocks", params -> {
            int radius = Math.min(48, Params.integer(params, "radius", 16));
            List<String> filter = new ArrayList<>();
            if (params.has("blocks") && params.get("blocks").isJsonArray()) {
                params.getAsJsonArray("blocks").forEach(e -> filter.add(e.getAsString()));
            }
            int limit = Params.integer(params, "limit", 40);
            return ClientThread.call(() -> scanBlocks(radius, filter, limit));
        });

        // ---- skills ----
        RpcRegistry.register("collect", params -> {
            String block = Params.string(params, "block");
            int count = Math.max(1, Params.integer(params, "count", 1));
            int radius = Math.min(48, Params.integer(params, "radius", 24));
            int timeout = Params.integer(params, "timeoutTicks", 6000);
            return ClientThread.callAsync(() -> new Collect(ClientThread.mc(), blockMatcher(block), block, count, radius, timeout).run());
        });
        RpcRegistry.register("fight", params -> {
            Integer id = Params.has(params, "id") ? Params.integer(params, "id") : null;
            String type = Params.string(params, "type", null);
            double radius = Params.number(params, "radius", 16);
            int timeout = Params.integer(params, "timeoutTicks", 600);
            float fleeHealth = (float) Params.number(params, "fleeHealth", 0);
            boolean weapon = Params.bool(params, "autoWeapon", true);
            return ClientThread.callAsync(() -> fight(id, type, radius, timeout, fleeHealth, weapon));
        });
        RpcRegistry.register("dig", params -> {
            String mode = Params.string(params, "mode", "stairs");
            if (!mode.equals("stairs") && !mode.equals("tunnel") && !mode.equals("up")) {
                throw new RpcException("mode must be stairs (down), up, or tunnel");
            }
            int length = Math.max(1, Math.min(64, Params.integer(params, "length", 8)));
            String dir = Params.string(params, "direction", null);
            String until = Params.has(params, "until") ? Params.string(params, "until") : null;
            return ClientThread.callAsync(() -> dig(mode, length, dir, until));
        });
        RpcRegistry.register("place", params -> {
            String item = normalizeId(Params.string(params, "item"));
            BlockPos at = Params.has(params, "x")
                    ? new BlockPos(Params.integer(params, "x"), Params.integer(params, "y"), Params.integer(params, "z")) : null;
            return ClientThread.callAsync(() -> place(item, at));
        });
        RpcRegistry.register("craft", params -> {
            String item = normalizeId(Params.string(params, "item"));
            int times = Math.max(1, Params.integer(params, "times", 1));
            boolean keepOpen = Params.bool(params, "keepOpen", false);
            return ClientThread.callAsync(() -> craft(item, times, keepOpen));
        });
    }

    // =====================================================================================================  time

    private static MinecraftServer server() {
        MinecraftServer server = ClientThread.mc().getSingleplayerServer();
        if (server == null) throw new RpcException("Time control needs a singleplayer world");
        return server;
    }

    private static void setFrozenNow(boolean frozen) {
        MinecraftServer server = server();
        server.execute(() -> server.tickRateManager().setFrozen(frozen));
    }

    /** Completes once the server has applied the change and the client has had a tick to receive it. */
    private static CompletableFuture<JsonElement> setFrozen(boolean frozen) {
        lockstep = false;
        MinecraftServer server = server();
        return server.submit(() -> {
                    server.tickRateManager().setFrozen(frozen);
                    return true;
                })
                .thenCompose(v -> ClientThread.callAsync(() -> waitTicks(2)))
                .thenCompose(v -> ClientThread.call(SkillMethods::timeStatus));
    }

    private static JsonElement timeStatus() {
        MinecraftServer server = server();
        ServerTickRateManager trm = server.tickRateManager();
        JsonObject out = new JsonObject();
        out.addProperty("frozen", trm.isFrozen());
        out.addProperty("lockstep", lockstep);
        out.addProperty("stepping", trm.isSteppingForward());
        out.addProperty("tickRate", trm.tickrate());
        out.addProperty("gameTime", server.overworld().getGameTime());
        return out;
    }

    private static CompletableFuture<JsonElement> step(int ticks) {
        MinecraftServer server = server();
        Minecraft mc = ClientThread.mc();
        long start = server.overworld().getGameTime();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        if (lockstep) {
            // In lockstep the world runs while any action is pending; this wait is that action.
            int[] left = {ticks};
            TickScheduler.add(() -> {
                if (--left[0] > 0) return false;
                future.complete(stepResult(server, start));
                return true;
            });
            return future;
        }
        if (!server.tickRateManager().isFrozen()) {
            throw new RpcException("The world is not frozen; call time.freeze first (or use waitFor ticks:n)");
        }
        server.execute(() -> server.tickRateManager().stepGameIfPaused(ticks));
        int[] waited = {0};
        TickScheduler.add(() -> {
            waited[0]++;
            boolean done = waited[0] > 2 && !server.tickRateManager().isSteppingForward();
            if (!done && waited[0] < ticks * 3 + 40) return false;
            future.complete(stepResult(server, start));
            return true;
        });
        return future;
    }

    private static JsonObject stepResult(MinecraftServer server, long start) {
        long now = server.overworld().getGameTime();
        JsonObject out = new JsonObject();
        out.addProperty("steppedTicks", now - start);
        out.addProperty("gameTime", now);
        return out;
    }

    /** Called after every client tick: in lockstep the world only runs while MCTest has work in flight. */
    public static void updateLockstep(Minecraft mc) {
        MinecraftServer server = mc.getSingleplayerServer();
        if (!lockstep || server == null || mc.level == null) {
            if (server == null) lockstep = false;
            return;
        }
        if (server != lockstepServer) {
            lockstepServer = server;
            lockstepFrozen = null;
        }
        boolean wantFrozen = !TickScheduler.hasTasks();
        if (lockstepFrozen == null || lockstepFrozen != wantFrozen) {
            lockstepFrozen = wantFrozen;
            server.execute(() -> server.tickRateManager().setFrozen(wantFrozen));
        }
    }

    // =====================================================================================================  items

    static String normalizeId(String id) {
        String s = id.trim().toLowerCase();
        return s.startsWith("#") || s.contains(":") ? s : "minecraft:" + s;
    }

    static String itemId(ItemStack s) {
        return BuiltInRegistries.ITEM.getKey(s.getItem()).toString();
    }

    /**
     * Puts a matching item in the hand: selects it if it's on the hotbar, otherwise swaps it from the main inventory
     * into an empty hotbar slot (or the selected one). Returns the hotbar slot, or -1.
     */
    static int selectItem(Minecraft mc, LocalPlayer p, Predicate<ItemStack> match) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.getSelectionSize(); i++) {
            if (!inv.getItem(i).isEmpty() && match.test(inv.getItem(i))) {
                inv.selected = i;
                return i;
            }
        }
        for (int i = Inventory.getSelectionSize(); i < Inventory.INVENTORY_SIZE; i++) {
            if (!inv.getItem(i).isEmpty() && match.test(inv.getItem(i))) {
                int target = inv.selected;
                for (int h = 0; h < Inventory.getSelectionSize(); h++) {
                    if (inv.getItem(h).isEmpty()) {
                        target = h;
                        break;
                    }
                }
                swapIntoHotbar(mc, p, i, target);
                inv.selected = target;
                return target;
            }
        }
        return -1;
    }

    /** Main-inventory slot (9-35) to hotbar slot via the player's inventory menu, like pressing a number key over it. */
    private static void swapIntoHotbar(Minecraft mc, LocalPlayer p, int inventorySlot, int hotbarSlot) {
        AbstractContainerMenu menu = p.inventoryMenu;
        if (p.containerMenu != menu) {
            throw new RpcException("Close the open container first (" + p.containerMenu.getClass().getSimpleName() + ")");
        }
        // InventoryMenu indices 9-35 are the same main-inventory slots.
        mc.gameMode.handleInventoryMouseClick(menu.containerId, inventorySlot, hotbarSlot, ClickType.SWAP, p);
    }

    /** Best tool for a block: correct-for-drops first, then dig speed. Swaps one in from the main inventory if better. */
    static void selectBestTool(Minecraft mc, LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        int best = -1;
        double bestScore = score(inv.getItem(inv.selected), state);
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            double s = score(inv.getItem(i), state);
            if (s > bestScore + 1e-6) {
                bestScore = s;
                best = i;
            }
        }
        if (best < 0) return;
        if (best < Inventory.getSelectionSize()) {
            inv.selected = best;
        } else if (p.containerMenu == p.inventoryMenu) {
            int target = inv.selected;
            swapIntoHotbar(mc, p, best, target);
        }
    }

    static boolean canHarvest(LocalPlayer p, BlockState state) {
        Inventory inv = p.getInventory();
        for (int i = 0; i < Inventory.INVENTORY_SIZE; i++) {
            if (inv.getItem(i).isCorrectToolForDrops(state)) return true;
        }
        return false;
    }

    private static double score(ItemStack stack, BlockState state) {
        double speed = stack.getDestroySpeed(state);
        boolean drops = !state.requiresCorrectToolForDrops() || stack.isCorrectToolForDrops(state);
        return (drops ? 1000 : 0) + speed;
    }

    // =====================================================================================================  scan

    static Predicate<BlockState> blockMatcher(String spec) {
        String s = normalizeId(spec);
        if (s.startsWith("#")) {
            TagKey<Block> tag = TagKey.create(Registries.BLOCK, ResourceLocation.parse(s.substring(1)));
            return state -> state.is(tag);
        }
        return state -> BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString().equals(s);
    }

    private static JsonElement scanBlocks(int radius, List<String> filter, int limit) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        List<Predicate<BlockState>> matchers = filter.stream().map(SkillMethods::blockMatcher).toList();
        BlockPos origin = p.blockPosition();
        Map<String, int[]> counts = new HashMap<>();
        Map<String, BlockPos> nearest = new HashMap<>();
        Map<String, Double> nearestDist = new HashMap<>();
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!mc.level.isLoaded(m)) continue;
                    BlockState state = mc.level.getBlockState(m);
                    if (state.isAir()) continue;
                    if (!matchers.isEmpty() && matchers.stream().noneMatch(f -> f.test(state))) continue;
                    String id = BuiltInRegistries.BLOCK.getKey(state.getBlock()).toString();
                    counts.computeIfAbsent(id, k -> new int[1])[0]++;
                    double d = dx * dx + dy * dy + dz * dz;
                    if (d < nearestDist.getOrDefault(id, Double.MAX_VALUE)) {
                        nearestDist.put(id, d);
                        nearest.put(id, m.immutable());
                    }
                }
            }
        }
        JsonArray blocks = new JsonArray();
        counts.keySet().stream()
                .sorted(Comparator.comparingDouble(nearestDist::get))
                .limit(limit)
                .forEach(id -> {
                    JsonObject o = new JsonObject();
                    o.addProperty("block", id);
                    o.addProperty("count", counts.get(id)[0]);
                    o.add("nearest", WorldMethods.pos(nearest.get(id)));
                    o.addProperty("distance", Math.round(Math.sqrt(nearestDist.get(id)) * 10) / 10.0);
                    blocks.add(o);
                });
        JsonObject out = new JsonObject();
        out.add("center", WorldMethods.pos(origin));
        out.addProperty("radius", radius);
        out.addProperty("distinctBlocks", counts.size());
        out.add("blocks", blocks);
        return out;
    }

    private static BlockPos findNearest(Level level, BlockPos origin, Predicate<BlockState> match, int radius, Set<BlockPos> exclude) {
        return findNearest(level, origin, (pos, state) -> match.test(state), radius, exclude);
    }

    /** A block a player could see: at least one neighbour has no collision (air, water, plants...). */
    private static java.util.function.BiPredicate<BlockPos, BlockState> exposedAt(Level level) {
        return (pos, state) -> {
            for (Direction d : Direction.values()) {
                BlockPos n = pos.relative(d);
                if (level.getBlockState(n).getCollisionShape(level, n).isEmpty()) return true;
            }
            return false;
        };
    }

    private static BlockPos findNearest(Level level, BlockPos origin, java.util.function.BiPredicate<BlockPos, BlockState> match, int radius, Set<BlockPos> exclude) {
        BlockPos best = null;
        double bestD = Double.MAX_VALUE;
        BlockPos.MutableBlockPos m = new BlockPos.MutableBlockPos();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    // Prefer blocks at or below eye level: they're mined from the ground, and a trunk taken
                    // bottom-up drops each log straight down the gap instead of into the canopy.
                    double d = dx * dx + dz * dz + (dy > 1 ? 1 + 9.0 * (dy - 1) * (dy - 1) : dy * dy);
                    if (d >= bestD) continue;
                    m.set(origin.getX() + dx, origin.getY() + dy, origin.getZ() + dz);
                    if (!level.isLoaded(m) || exclude.contains(m)) continue;
                    if (match.test(m, level.getBlockState(m))) {
                        bestD = d;
                        best = m.immutable();
                    }
                }
            }
        }
        return best;
    }

    private static Map<String, Integer> inventoryCounts(LocalPlayer p) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        Inventory inv = p.getInventory();
        for (int i = 0; i < inv.getContainerSize(); i++) {
            ItemStack s = inv.getItem(i);
            if (!s.isEmpty()) counts.merge(itemId(s), s.getCount(), Integer::sum);
        }
        return counts;
    }

    private static JsonObject inventoryDelta(Map<String, Integer> before, Map<String, Integer> after) {
        JsonObject out = new JsonObject();
        Set<String> keys = new HashSet<>(before.keySet());
        keys.addAll(after.keySet());
        keys.stream().sorted().forEach(k -> {
            int d = after.getOrDefault(k, 0) - before.getOrDefault(k, 0);
            if (d != 0) out.addProperty(k, d);
        });
        return out;
    }

    private static CompletableFuture<Void> waitTicks(int ticks) {
        CompletableFuture<Void> f = new CompletableFuture<>();
        int[] left = {ticks};
        TickScheduler.add(() -> {
            if (--left[0] > 0) return false;
            f.complete(null);
            return true;
        });
        return f;
    }

    private static Direction faceToward(LocalPlayer p, BlockPos block) {
        Vec3 d = p.getEyePosition().subtract(Vec3.atCenterOf(block));
        double ax = Math.abs(d.x), ay = Math.abs(d.y), az = Math.abs(d.z);
        if (ay >= ax && ay >= az) return d.y > 0 ? Direction.UP : Direction.DOWN;
        if (ax >= az) return d.x > 0 ? Direction.EAST : Direction.WEST;
        return d.z > 0 ? Direction.SOUTH : Direction.NORTH;
    }

    // =====================================================================================================  collect

    /** Walk to the nearest matching block, mine it with the best tool, pick up the drop; repeat. */
    private static final class Collect {
        private final Minecraft mc;
        private final Predicate<BlockState> match;
        private final String spec;
        private final int count;
        private final int radius;
        private final int timeout;
        private final long startTime = System.currentTimeMillis();
        private final Set<BlockPos> failed = new HashSet<>();
        private final JsonArray log = new JsonArray();
        private Map<String, Integer> before;
        private int mined;
        private int collected;
        private int lostDrops;
        private int consecutiveFailures;
        private boolean finalSweepDone;
        private final CompletableFuture<JsonElement> result = new CompletableFuture<>();

        Collect(Minecraft mc, Predicate<BlockState> match, String spec, int count, int radius, int timeout) {
            this.mc = mc;
            this.match = match;
            this.spec = spec;
            this.count = count;
            this.radius = radius;
            this.timeout = timeout;
        }

        CompletableFuture<JsonElement> run() {
            before = inventoryCounts(WorldMethods.requirePlayer());
            next();
            return result;
        }

        private void next() {
            LocalPlayer p = mc.player;
            if (p == null) {
                done("left the world");
                return;
            }
            if (collected >= count) {
                done(null);
                return;
            }
            if (System.currentTimeMillis() - startTime > timeout * 50L * 2) {
                done("timed out");
                return;
            }
            var exposed = exposedAt(mc.level);
            // Never dig out the floor you're standing on.
            var floor = p.getBoundingBox().move(0, -0.5, 0);
            Set<BlockPos> steps = stairLevel == mc.level ? STAIR_STEPS : Set.of();
            java.util.function.BiPredicate<BlockPos, BlockState> candidate =
                    (pos, st) -> match.test(st) && exposed.test(pos, st) && !steps.contains(pos)
                            && !floor.intersects(new net.minecraft.world.phys.AABB(pos));
            // Take from walls before floors: digging out the ground you walk on turns steps into ledges you can't
            // climb back up (the way a player mines stone out of a staircase's sides).
            int feetY = p.blockPosition().getY();
            BlockPos wall = findNearest(mc.level, p.blockPosition(), (pos, st) -> pos.getY() >= feetY && candidate.test(pos, st), radius, failed);
            BlockPos target = wall != null ? wall : findNearest(mc.level, p.blockPosition(), candidate, radius, failed);
            if (target == null) {
                done("no more reachable " + spec + " within " + radius + " blocks");
                return;
            }
            BlockState wanted = mc.level.getBlockState(target);
            if (wanted.requiresCorrectToolForDrops() && !canHarvest(p, wanted)) {
                done("no tool in the inventory can harvest " + BuiltInRegistries.BLOCK.getKey(wanted.getBlock())
                        + " (mining it would drop nothing); craft or pick up a suitable tool first");
                return;
            }
            double reach = p.blockInteractionRange() - 0.4;
            Navigator.go(mc, new Navigator.Request(Navigator.withinReach(mc.level, target, reach), target, true, 1200, 20_000))
                    .thenCompose(walk -> {
                        if (!walk.get("arrived").getAsBoolean()) {
                            failed.add(target);
                            consecutiveFailures++;
                            log("could not reach " + target.toShortString() + ": " + (walk.has("reason") ? walk.get("reason").getAsString() : "?"));
                            return CompletableFuture.completedFuture(null);
                        }
                        BlockState state = mc.level.getBlockState(target);
                        if (!match.test(state)) {
                            return CompletableFuture.completedFuture(null); // someone else got it
                        }
                        selectBestTool(mc, mc.player, state);
                        Map<String, Integer> beforeBlock = inventoryCounts(mc.player);
                        return ActionMethods.breakBlock(target, faceToward(mc.player, target), 600)
                                .handle((r, err) -> {
                                    if (err != null) {
                                        failed.add(target);
                                        consecutiveFailures++;
                                        log("could not mine " + target.toShortString() + ": " + rootMessage(err));
                                        return false;
                                    }
                                    mined++;
                                    consecutiveFailures = 0;
                                    return true;
                                })
                                .thenCompose(ok -> ok ? pickUp(target).thenRun(() -> {
                                    // Only count it if something actually ended up in the inventory.
                                    LocalPlayer me = mc.player;
                                    boolean gained = me != null && inventoryCounts(me).entrySet().stream()
                                            .anyMatch(e -> e.getValue() > beforeBlock.getOrDefault(e.getKey(), 0));
                                    if (gained) collected++;
                                    else lostDrops++;
                                }) : CompletableFuture.completedFuture(null));
                    })
                    .whenComplete((v, err) -> {
                        if (err != null) {
                            done("error: " + rootMessage(err));
                        } else if (consecutiveFailures >= 8) {
                            done("gave up: the last 8 candidate blocks were unreachable or unmineable");
                        } else {
                            // Next target on a later tick: keeps each frame cheap and the call stack flat.
                            waitTicks(1).thenRun(this::next);
                        }
                    });
        }

        private final Set<Integer> unreachableDrops = new HashSet<>();

        /**
         * Let drops land (they fall out of trees, bounce, roll), then walk over each one nearby: players pick items up
         * within about a block. Drops that can't be reached (e.g. resting on leaves) are skipped.
         */
        private CompletableFuture<Void> pickUp(BlockPos at) {
            return waitTicks(15).thenCompose(v -> sweep(at, 0));
        }

        private CompletableFuture<Void> sweep(BlockPos at, int round) {
            LocalPlayer p = mc.player;
            if (p == null || round >= 8) return CompletableFuture.completedFuture(null);
            List<ItemEntity> drops = mc.level.getEntitiesOfClass(ItemEntity.class, new net.minecraft.world.phys.AABB(at).inflate(6),
                    e -> e.isAlive() && !unreachableDrops.contains(e.getId()));
            if (drops.isEmpty()) return CompletableFuture.completedFuture(null);
            ItemEntity drop = drops.stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).get();
            if (p.getBoundingBox().inflate(1.0, 0.5, 1.0).intersects(drop.getBoundingBox())) {
                return waitTicks(3).thenCompose(w -> {
                    if (drop.isAlive()) unreachableDrops.add(drop.getId()); // pickup delay / full inventory
                    return sweep(at, round + 1);
                });
            }
            return Navigator.go(mc, new Navigator.Request(Navigator.near(drop.position(), 0.7), drop.blockPosition(), false, 200, 8_000))
                    .thenCompose(r -> waitTicks(3))
                    .thenCompose(w -> {
                        if (drop.isAlive() && mc.player != null && mc.player.distanceTo(drop) > 1.5) {
                            unreachableDrops.add(drop.getId());
                        }
                        return sweep(at, round + 1);
                    });
        }

        private void log(String s) {
            log.add(s);
        }

        private void done(String reason) {
            LocalPlayer p = mc.player;
            if (p != null && !finalSweepDone && mined > 0) {
                // One last pass for drops that bounced away or fell late.
                finalSweepDone = true;
                sweep(p.blockPosition(), 0).whenComplete((v, e) -> done(reason));
                return;
            }
            JsonObject out = new JsonObject();
            out.addProperty("block", spec);
            out.addProperty("requested", count);
            out.addProperty("mined", mined);
            out.addProperty("collected", collected);
            if (lostDrops > 0) out.addProperty("dropsNotPickedUp", lostDrops);
            out.addProperty("complete", collected >= count);
            if (reason != null) out.addProperty("reason", reason);
            if (p != null) out.add("inventoryChange", inventoryDelta(before, inventoryCounts(p)));
            if (!log.isEmpty()) out.add("notes", log);
            result.complete(out);
        }
    }

    static String rootMessage(Throwable t) {
        while (t.getCause() != null && t != t.getCause()) t = t.getCause();
        return t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName();
    }

    // =====================================================================================================  fight

    private static CompletableFuture<JsonElement> fight(Integer id, String type, double radius, int timeoutTicks, float fleeHealth, boolean autoWeapon) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        Entity target;
        if (id != null) {
            target = mc.level.getEntity(id);
            if (target == null) throw new RpcException("No entity with id " + id);
        } else {
            String wanted = type == null ? null : normalizeId(type);
            target = mc.level.getEntities(p, p.getBoundingBox().inflate(radius), e -> e.isAlive()
                            && (wanted == null ? e instanceof Enemy : BuiltInRegistries.ENTITY_TYPE.getKey(e.getType()).toString().equals(wanted)))
                    .stream().min(Comparator.comparingDouble(e -> e.distanceToSqr(p))).orElse(null);
            if (target == null) {
                throw new RpcException("No " + (wanted == null ? "hostile mob" : wanted) + " within " + radius + " blocks");
            }
        }
        if (autoWeapon) {
            if (selectItem(mc, p, s -> s.is(ItemTags.SWORDS)) < 0) {
                selectItem(mc, p, s -> s.is(ItemTags.AXES));
            }
        }

        Entity victim = target;
        String victimType = BuiltInRegistries.ENTITY_TYPE.getKey(victim.getType()).toString();
        float startHealth = p.getHealth();
        CompletableFuture<JsonElement> future = new CompletableFuture<>();
        int[] ticks = {0};
        int[] hits = {0};
        int[] stuck = {0};
        double[] lastDist = {Double.MAX_VALUE};
        TickScheduler.add(() -> {
            ticks[0]++;
            LocalPlayer me = mc.player;
            String outcome = null;
            if (me == null || !me.isAlive()) outcome = "player died";
            else if (!victim.isAlive() || victim.isRemoved()) outcome = "killed";
            else if (fleeHealth > 0 && me.getHealth() <= fleeHealth) outcome = "health low, stopped";
            else if (ticks[0] > timeoutTicks) outcome = "timed out";
            if (outcome != null) {
                PlayerControl.release(mc);
                JsonObject out = new JsonObject();
                out.addProperty("target", victimType);
                out.addProperty("outcome", outcome);
                out.addProperty("hits", hits[0]);
                out.addProperty("ticks", ticks[0]);
                if (me != null) {
                    out.addProperty("health", me.getHealth());
                    out.addProperty("damageTaken", startHealth - me.getHealth());
                }
                future.complete(out);
                return true;
            }
            if (mc.screen != null) return false;

            Vec3 aim = victim.getBoundingBox().getCenter();
            Vec3 eye = me.getEyePosition();
            Vec3 closest = new Vec3(
                    clamp(eye.x, victim.getBoundingBox().minX, victim.getBoundingBox().maxX),
                    clamp(eye.y, victim.getBoundingBox().minY, victim.getBoundingBox().maxY),
                    clamp(eye.z, victim.getBoundingBox().minZ, victim.getBoundingBox().maxZ));
            double reachDist = eye.distanceTo(closest);
            boolean inReach = reachDist <= me.entityInteractionRange() - 0.25;
            float yawError = PlayerControl.turnToward(me, aim, true);

            if (!inReach) {
                // Close in: walk straight at it, jumping over bumps.
                boolean jump = me.horizontalCollision || (victim.getY() > me.getY() + 0.6 && reachDist < 3);
                PlayerControl.move(mc, yawError < 60, jump, reachDist > 5 && yawError < 20);
                if (reachDist >= lastDist[0] - 0.02) stuck[0]++;
                else stuck[0] = 0;
                lastDist[0] = reachDist;
                if (stuck[0] > 60) {
                    PlayerControl.release(mc);
                    JsonObject out = new JsonObject();
                    out.addProperty("target", victimType);
                    out.addProperty("outcome", "cannot reach target (use goto first)");
                    out.addProperty("hits", hits[0]);
                    future.complete(out);
                    return true;
                }
                return false;
            }
            stuck[0] = 0;
            // In reach: hold position (step in slightly if at the edge) and swing when the cooldown is full.
            PlayerControl.move(mc, reachDist > 2.5 && yawError < 30, false, false);
            if (yawError < 20 && me.getAttackStrengthScale(0.5f) >= 1.0f) {
                mc.gameMode.attack(me, victim);
                ActionMethods.attackSwing(me);
                hits[0]++;
            }
            return false;
        });
        return future;
    }

    private static double clamp(double v, double lo, double hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    // =====================================================================================================  dig

    /**
     * Dig like a player: a staircase going down one block per step, or a 1x2 tunnel forward. Each step clears the
     * blocks ahead with the best tool and walks into the gap. Stops at lava/water, unbreakable blocks, or once the
     * block type in {@code until} appears in the walls/floor.
     */
    private static CompletableFuture<JsonElement> dig(String mode, int length, String dirName, String until) {
        boolean stairs = mode.equals("stairs");
        boolean up = mode.equals("up");
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        Direction dir = dirName != null ? Direction.byName(dirName.toLowerCase()) : p.getDirection();
        if (dir == null || dir.getAxis().isVertical()) throw new RpcException("direction must be north, south, east or west");
        Predicate<BlockState> stopAt = until == null ? null : blockMatcher(until);
        Map<String, Integer> before = inventoryCounts(p);
        CompletableFuture<JsonElement> result = new CompletableFuture<>();
        int[] steps = {0};
        Runnable[] next = new Runnable[1];
        java.util.function.Consumer<String> finish = reason -> {
            PlayerControl.release(mc);
            JsonObject out = new JsonObject();
            out.addProperty("mode", mode);
            out.addProperty("direction", dir.getName());
            out.addProperty("steps", steps[0]);
            out.addProperty("stoppedBecause", reason);
            if (mc.player != null) {
                out.add("pos", WorldMethods.pos(mc.player.blockPosition()));
                out.add("inventoryChange", inventoryDelta(before, inventoryCounts(mc.player)));
            }
            result.complete(out);
        };
        next[0] = () -> {
            LocalPlayer me = mc.player;
            if (me == null || !me.isAlive()) {
                finish.accept("player died or left");
                return;
            }
            if (steps[0] >= length) {
                finish.accept("done");
                return;
            }
            BlockPos feet = new Pathfinder.Terrain(mc.level).feet(me.getX(), me.getY(), me.getZ());
            BlockPos front = feet.relative(dir);
            BlockPos nextFeet = stairs ? front.below() : up ? front.above() : front;
            List<BlockPos> clear;
            if (up) {
                // Head room to jump, then the space on top of the step in front.
                clear = new ArrayList<>(List.of(feet.above(2), front.above(2), front.above()));
            } else {
                clear = new ArrayList<>(List.of(front.above(), front));
                if (stairs) clear.add(nextFeet);
            }
            // Look before you dig: any liquid around the new space would flood in.
            for (BlockPos c : clear) {
                for (Direction d : Direction.values()) {
                    var fluid = mc.level.getFluidState(c.relative(d));
                    if (!fluid.isEmpty()) {
                        finish.accept((fluid.is(net.minecraft.tags.FluidTags.LAVA) ? "lava" : "water") + " next to " + c.relative(d).toShortString());
                        return;
                    }
                }
            }
            if (stopAt != null) {
                BlockPos found = clear.stream().filter(c -> stopAt.test(mc.level.getBlockState(c))).findFirst().orElse(null);
                if (found != null) {
                    // Clear what's in front of it (but not the find itself) so it's exposed and in view, like a
                    // player would before switching to mining it.
                    CompletableFuture<Void> expose = CompletableFuture.completedFuture(null);
                    for (BlockPos c : clear) {
                        if (c.getY() <= found.getY() || stopAt.test(mc.level.getBlockState(c))) continue;
                        expose = expose.thenCompose(v -> {
                            BlockState st = mc.level.getBlockState(c);
                            if (st.getCollisionShape(mc.level, c).isEmpty() || st.getDestroySpeed(mc.level, c) < 0) {
                                return CompletableFuture.completedFuture(null);
                            }
                            selectBestTool(mc, mc.player, st);
                            return ActionMethods.breakBlock(c, faceToward(mc.player, c), 600).thenApply(r -> null);
                        });
                    }
                    expose.whenComplete((v, err) -> finish.accept("found " + until + " at " + found.toShortString()));
                    return;
                }
            }
            for (BlockPos c : clear) {
                // Never dig through your own stuff: tables, chests, furnaces, machines.
                if (mc.level.getBlockEntity(c) != null || mc.level.getBlockState(c).getMenuProvider(mc.level, c) != null) {
                    finish.accept("would break " + BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(c).getBlock())
                            + " at " + c.toShortString() + "; dig in another direction");
                    return;
                }
            }
            if (up && mc.level.getBlockState(front).getCollisionShape(mc.level, front).isEmpty()) {
                finish.accept("no block in front to step up onto (open air ahead)");
                return;
            }
            if (stairs && mc.level.getBlockState(nextFeet.below()).getCollisionShape(mc.level, nextFeet.below()).isEmpty()) {
                finish.accept("no floor below the next step (cave or drop ahead)");
                return;
            }
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            for (BlockPos c : clear) {
                chain = chain.thenCompose(v -> {
                    BlockState st = mc.level.getBlockState(c);
                    if (st.getCollisionShape(mc.level, c).isEmpty() && st.getFluidState().isEmpty()) {
                        return CompletableFuture.completedFuture(null);
                    }
                    if (st.getDestroySpeed(mc.level, c) < 0) {
                        throw new RpcException("unbreakable " + BuiltInRegistries.BLOCK.getKey(st.getBlock()) + " at " + c.toShortString());
                    }
                    selectBestTool(mc, mc.player, st);
                    return ActionMethods.breakBlock(c, faceToward(mc.player, c), 600).thenApply(r -> null);
                });
            }
            Navigator.Request stepIn = new Navigator.Request(Navigator.near(Vec3.atBottomCenterOf(nextFeet), 0.4), nextFeet, false, 200, 2_000);
            chain.thenCompose(v -> Navigator.go(mc, stepIn))
                    // One retry: a first attempt can fail if we started off-centre or a drop got in the way.
                    .thenCompose(walk -> walk.get("arrived").getAsBoolean() ? CompletableFuture.completedFuture(walk) : Navigator.go(mc, stepIn))
                    .thenCompose(walk -> waitTicks(4).thenApply(w -> walk)) // let drops reach us
                    .whenComplete((walk, err) -> {
                        LocalPlayer now = mc.player;
                        boolean inStep = now != null
                                && Math.abs(now.getY() - nextFeet.getY()) < 0.6
                                && Math.hypot(now.getX() - (nextFeet.getX() + 0.5), now.getZ() - (nextFeet.getZ() + 0.5)) < 0.8;
                        if (err != null) {
                            finish.accept("blocked: " + rootMessage(err));
                        } else if (!inStep) {
                            // Never keep digging from the wrong spot: that turns a staircase into a shaft.
                            finish.accept("could not step into the dug space at " + nextFeet.toShortString()
                                    + (walk.has("reason") ? " (" + walk.get("reason").getAsString() + ")" : ""));
                        } else {
                            if (stairLevel != mc.level) {
                                STAIR_STEPS.clear();
                                stairLevel = mc.level;
                            }
                            STAIR_STEPS.add(nextFeet.below());
                            steps[0]++;
                            next[0].run();
                        }
                    });
        };
        next[0].run();
        return result;
    }

    // =====================================================================================================  place

    /**
     * Place an item as a block like a player: hold it, then right-click a face of a solid neighbour of the target
     * spot (any side). Without a position, picks the nearest free spot around the player that isn't where they stand.
     */
    private static CompletableFuture<JsonElement> place(String item, BlockPos at) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        if (selectItem(mc, p, s -> itemId(s).equals(item)) < 0) {
            throw new RpcException("No " + item + " in the inventory");
        }
        double reach = p.blockInteractionRange() - 0.2;
        List<BlockPos> spots = new ArrayList<>();
        if (at != null) {
            spots.add(at);
        } else {
            BlockPos feet = p.blockPosition();
            for (int dx = -3; dx <= 3; dx++) {
                for (int dy = -1; dy <= 1; dy++) {
                    for (int dz = -3; dz <= 3; dz++) {
                        BlockPos c = feet.offset(dx, dy, dz);
                        if (c.equals(feet) || c.equals(feet.above())) continue;
                        // Don't box yourself in: skip the space right above your head.
                        if (dx == 0 && dz == 0) continue;
                        spots.add(c);
                    }
                }
            }
            Vec3 eye = p.getEyePosition();
            // Prefer where a player would put it: at feet level, standing on solid ground (not leaves), so it stays
            // reachable on foot later.
            spots.sort(Comparator.comparingDouble(c -> {
                BlockPos below = c.below();
                BlockState under = mc.level.getBlockState(below);
                boolean grounded = under.isFaceSturdy(mc.level, below, Direction.UP)
                        && !under.is(net.minecraft.tags.BlockTags.LEAVES);
                return Vec3.atCenterOf(c).distanceToSqr(eye)
                        + (c.getY() != feet.getY() ? 6 : 0)
                        + (grounded ? 0 : 25);
            }));
        }
        for (BlockPos spot : spots) {
            if (!mc.level.getBlockState(spot).canBeReplaced()) continue;
            if (!mc.level.getEntities((Entity) null, new net.minecraft.world.phys.AABB(spot)).isEmpty()) continue;
            if (p.getBoundingBox().intersects(new net.minecraft.world.phys.AABB(spot))) continue;
            for (Direction dir : new Direction[]{Direction.DOWN, Direction.NORTH, Direction.SOUTH, Direction.EAST, Direction.WEST, Direction.UP}) {
                BlockPos support = spot.relative(dir);
                BlockState s = mc.level.getBlockState(support);
                if (s.getCollisionShape(mc.level, support).isEmpty()) continue;
                // Right-clicking a chest/table/furnace would open it instead of placing against it.
                if (s.getMenuProvider(mc.level, support) != null || mc.level.getBlockEntity(support) != null) continue;
                Direction face = dir.getOpposite();
                Vec3 hit = Vec3.atCenterOf(support).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
                if (p.getEyePosition().distanceTo(hit) > reach) continue;
                p.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
                var result = mc.gameMode.useItemOn(p, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, support, false));
                if (!result.consumesAction()) continue;
                p.swing(InteractionHand.MAIN_HAND);
                CompletableFuture<JsonElement> out = new CompletableFuture<>();
                waitTicks(2).thenRun(() -> {
                    JsonObject o = new JsonObject();
                    String placed = BuiltInRegistries.BLOCK.getKey(mc.level.getBlockState(spot).getBlock()).toString();
                    o.addProperty("placed", !mc.level.getBlockState(spot).canBeReplaced());
                    o.addProperty("block", placed);
                    o.add("pos", WorldMethods.pos(spot));
                    o.addProperty("against", BuiltInRegistries.BLOCK.getKey(s.getBlock()) + " (" + face.getName() + " face)");
                    out.complete(o);
                });
                return out;
            }
        }
        throw new RpcException(at != null
                ? "Cannot place at " + at.toShortString() + ": occupied, out of reach, or nothing solid next to it"
                : "No free spot within reach to place " + item);
    }

    // =====================================================================================================  craft

    private static CompletableFuture<JsonElement> craft(String item, int times, boolean keepOpen) {
        Minecraft mc = ClientThread.mc();
        LocalPlayer p = WorldMethods.requirePlayer();
        List<RecipeHolder<?>> recipes = p.getRecipeBook().getCollections().stream()
                .flatMap(c -> c.getRecipes().stream())
                .filter(e -> e.value() instanceof CraftingRecipe)
                .filter(e -> itemId(e.value().getResultItem(mc.level.registryAccess())).equals(item))
                .distinct()
                .toList();
        if (recipes.isEmpty()) {
            throw new RpcException("No known crafting recipe for " + item + ". Recipes unlock when you first pick up an ingredient"
                    + " (or use /recipe give @s *).");
        }
        StackedContents contents = new StackedContents();
        p.getInventory().fillStackedContents(contents);
        RecipeHolder<?> recipe = recipes.stream().filter(e -> contents.canCraft(e.value(), null)).findFirst().orElse(null);
        if (recipe == null) {
            throw new RpcException("Missing ingredients for " + item + " (" + recipes.size() + " recipe(s) known)");
        }
        boolean big = !recipe.value().canCraftInDimensions(2, 2);
        Map<String, Integer> before = inventoryCounts(p);

        return ensureCraftingMenu(mc, p, big).thenCompose(opened -> {
            CompletableFuture<Void> chain = CompletableFuture.completedFuture(null);
            int[] made = {0};
            for (int i = 0; i < times; i++) {
                chain = chain.thenCompose(v -> craftOnce(mc, recipe, item).thenAccept(ok -> {
                    if (ok) made[0]++;
                }));
            }
            return chain.thenApply(v -> {
                LocalPlayer me = mc.player;
                if (opened && !keepOpen && mc.screen != null) {
                    mc.screen.onClose();
                }
                JsonObject out = new JsonObject();
                out.addProperty("item", item);
                out.addProperty("crafts", made[0]);
                out.addProperty("requested", times);
                out.addProperty("station", big ? "crafting_table" : "inventory");
                if (me != null) out.add("inventoryChange", inventoryDelta(before, inventoryCounts(me)));
                return (JsonElement) out;
            });
        });
    }

    /** Opens the inventory (2x2) or walks to and opens a crafting table (3x3). Completes with "we opened it". */
    private static CompletableFuture<Boolean> ensureCraftingMenu(Minecraft mc, LocalPlayer p, boolean big) {
        if (mc.screen instanceof AbstractContainerScreen<?> s && s.getMenu() instanceof RecipeBookMenu<?, ?> m
                && m.getGridWidth() >= (big ? 3 : 2)) {
            return CompletableFuture.completedFuture(false);
        }
        if (mc.screen != null) {
            mc.screen.onClose();
        }
        if (!big) {
            mc.setScreen(new InventoryScreen(p));
            return CompletableFuture.completedFuture(true);
        }
        BlockPos table = findNearest(mc.level, p.blockPosition(), s -> s.is(Blocks.CRAFTING_TABLE), 24, Set.of());
        if (table == null) {
            throw new RpcException("This recipe needs a crafting table: craft one and place it (useOn) first");
        }
        double reach = p.blockInteractionRange() - 0.4;
        CompletableFuture<JsonObject> walk = p.getEyePosition().distanceTo(Vec3.atCenterOf(table)) <= reach
                ? CompletableFuture.completedFuture(null)
                : Navigator.go(mc, new Navigator.Request(Navigator.withinReach(mc.level, table, reach), table, true, 1200));
        return walk.thenCompose(w -> {
            if (w != null && !w.get("arrived").getAsBoolean()) {
                throw new RpcException("Could not reach the crafting table at " + table.toShortString());
            }
            LocalPlayer me = mc.player;
            Direction face = faceToward(me, table);
            Vec3 hit = Vec3.atCenterOf(table).add(face.getStepX() * 0.5, face.getStepY() * 0.5, face.getStepZ() * 0.5);
            me.lookAt(net.minecraft.commands.arguments.EntityAnchorArgument.Anchor.EYES, hit);
            mc.gameMode.useItemOn(me, InteractionHand.MAIN_HAND, new BlockHitResult(hit, face, table, false));
            CompletableFuture<Boolean> opened = new CompletableFuture<>();
            int[] waited = {0};
            TickScheduler.add(() -> {
                if (mc.player != null && mc.player.containerMenu instanceof CraftingMenu) {
                    opened.complete(true);
                    return true;
                }
                if (++waited[0] > 40) {
                    opened.completeExceptionally(new RpcException("The crafting table did not open"));
                    return true;
                }
                return false;
            });
            return opened;
        });
    }

    /** Click the recipe in the book (server fills the grid), then shift-click the result. */
    private static CompletableFuture<Boolean> craftOnce(Minecraft mc, RecipeHolder<?> recipe, String item) {
        LocalPlayer p = mc.player;
        if (p == null || !(p.containerMenu instanceof RecipeBookMenu<?, ?> menu)) {
            return CompletableFuture.completedFuture(false);
        }
        mc.gameMode.handlePlaceRecipe(menu.containerId, recipe, false);
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        int[] waited = {0};
        TickScheduler.add(() -> {
            LocalPlayer me = mc.player;
            if (me == null || me.containerMenu != menu) {
                done.complete(false);
                return true;
            }
            ItemStack result = menu.getSlot(menu.getResultSlotIndex()).getItem();
            if (!result.isEmpty() && itemId(result).equals(item)) {
                mc.gameMode.handleInventoryMouseClick(menu.containerId, menu.getResultSlotIndex(), 0, ClickType.QUICK_MOVE, me);
                waitTicks(2).thenRun(() -> done.complete(true));
                return true;
            }
            if (++waited[0] > 40) {
                done.complete(false);
                return true;
            }
            return false;
        });
        return done;
    }
}
