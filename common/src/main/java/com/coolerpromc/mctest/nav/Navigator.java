package com.coolerpromc.mctest.nav;

import com.coolerpromc.mctest.tick.TickScheduler;
import com.google.gson.JsonObject;
import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.shapes.CollisionContext;

import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Walks the local player along an A* path by steering like a person: turn toward the next waypoint, hold forward once
 * roughly facing it, jump for step-ups / collisions / water, sprint on straight stretches. Replans when stuck.
 * Always completes normally; check {@code arrived} in the result.
 */
public final class Navigator {

    private static final int MAX_NODES = 60_000;
    private static final int MAX_REPLANS = 8;
    private static final int STUCK_TICKS = 40;

    private Navigator() {
    }

    /** Options for a walk. {@code goal} decides arrival by feet position; {@code target} guides the search. */
    public record Request(Predicate<BlockPos> goal, BlockPos target, boolean sprint, int timeoutTicks, int maxNodes) {
        public Request(Predicate<BlockPos> goal, BlockPos target, boolean sprint, int timeoutTicks) {
            this(goal, target, sprint, timeoutTicks, MAX_NODES);
        }
    }

    /** Must be called on the render thread. */
    public static CompletableFuture<JsonObject> go(Minecraft mc, Request req) {
        CompletableFuture<JsonObject> future = new CompletableFuture<>();
        new Walk(mc, req, future).start();
        return future;
    }

    /** Goal: feet within {@code range} blocks of the point (3D distance between feet block centre and point). */
    public static Predicate<BlockPos> near(Vec3 point, double range) {
        double r2 = range * range;
        return p -> Vec3.atBottomCenterOf(p).distanceToSqr(point) <= r2;
    }

    /**
     * Goal: from this feet position the block is within reach of the eyes and actually visible (a player can only
     * mine or use what the crosshair can hit).
     */
    public static Predicate<BlockPos> withinReach(Level level, BlockPos block, double reach) {
        Vec3 c = Vec3.atCenterOf(block);
        double r2 = reach * reach;
        return p -> {
            if (p.equals(block) || p.above().equals(block)) return false;
            Vec3 eye = Vec3.atBottomCenterOf(p).add(0, 1.62, 0);
            return eye.distanceToSqr(c) <= r2 && canSee(level, eye, block);
        };
    }

    /** True if the block is visible from the eye: a ray to its centre or to an exposed face's centre hits it first. */
    public static boolean canSee(Level level, Vec3 eye, BlockPos block) {
        if (rayHits(level, eye, Vec3.atCenterOf(block), block)) return true;
        for (net.minecraft.core.Direction d : net.minecraft.core.Direction.values()) {
            BlockPos n = block.relative(d);
            if (!level.getBlockState(n).getCollisionShape(level, n).isEmpty()) continue; // hidden face
            Vec3 face = Vec3.atCenterOf(block).add(d.getStepX() * 0.49, d.getStepY() * 0.49, d.getStepZ() * 0.49);
            if (rayHits(level, eye, face, block)) return true;
        }
        return false;
    }

    private static boolean rayHits(Level level, Vec3 eye, Vec3 to, BlockPos block) {
        BlockHitResult hit = level.clip(new ClipContext(eye, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, net.minecraft.client.Minecraft.getInstance().player));
        return hit.getType() == HitResult.Type.MISS || hit.getBlockPos().equals(block);
    }

    private static final class Walk implements TickScheduler.TickTask {
        private final Minecraft mc;
        private final Request req;
        private final CompletableFuture<JsonObject> future;
        private List<BlockPos> path = List.of();
        private boolean complete;
        private int index;
        private int ticks;
        private int replans;
        private int screenTicks;
        private double bestDist = Double.MAX_VALUE;
        private int lastProgress;
        private int pathLength;
        private long planNanos;

        Walk(Minecraft mc, Request req, CompletableFuture<JsonObject> future) {
            this.mc = mc;
            this.req = req;
            this.future = future;
        }

        void start() {
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null) {
                future.completeExceptionally(new IllegalStateException("Not in a world"));
                return;
            }
            if (req.goal.test(feet(p))) {
                finish(true, "already there");
                return;
            }
            if (!plan()) {
                return;
            }
            TickScheduler.add(this);
        }

        /**
         * The block the player stands in. If that column has no floor (standing on the edge of a hole), use another
         * column under the hitbox that does, as the player is really supported there.
         */
        private BlockPos feet(LocalPlayer p) {
            Pathfinder.Terrain t = new Pathfinder.Terrain(mc.level);
            BlockPos centre = t.feet(p.getX(), p.getY(), p.getZ());
            if (t.standable(centre)) return centre;
            double w = p.getBbWidth() / 2 - 0.01;
            for (double[] o : new double[][]{{w, w}, {w, -w}, {-w, w}, {-w, -w}}) {
                BlockPos c = t.feet(p.getX() + o[0], p.getY(), p.getZ() + o[1]);
                if (t.standable(c)) return c;
            }
            return centre;
        }

        /** Returns false (and finishes) if there is nowhere to go. */
        private boolean plan() {
            LocalPlayer p = mc.player;
            long t0 = System.nanoTime();
            Pathfinder.Path found = Pathfinder.find(mc.level, feet(p), req.goal, req.target, req.maxNodes);
            planNanos += System.nanoTime() - t0;
            path = found.points();
            complete = found.complete();
            pathLength = Math.max(pathLength, path.size());
            index = 0;
            bestDist = Double.MAX_VALUE;
            lastProgress = ticks;
            if (path.isEmpty()) {
                finish(found.complete(), found.complete() ? "already there" : "no path (searched " + found.expanded() + " positions)");
                return false;
            }
            return true;
        }

        @Override
        public boolean tick() {
            if (future.isDone()) {
                return true;
            }
            ticks++;
            LocalPlayer p = mc.player;
            if (p == null || mc.level == null || !p.isAlive()) {
                return finish(false, p != null && !p.isAlive() ? "player died" : "left the world");
            }
            if (mc.screen != null) {
                // Vanilla releases movement keys while a screen is open; wait for it to close.
                if (++screenTicks > 100) return finish(false, "a screen stayed open: " + mc.screen.getClass().getSimpleName());
                return false;
            }
            screenTicks = 0;
            BlockPos feet = feet(p);
            if (req.goal.test(feet) && p.onGround() || req.goal.test(feet) && p.isInWater()) {
                return finish(true, null);
            }
            if (ticks > req.timeoutTicks) {
                return finish(false, "timed out");
            }

            // Skip waypoints we've reached (or overshot).
            while (index < path.size() && reached(p, path.get(index))) {
                index++;
            }
            if (index >= path.size()) {
                if (complete && req.goal.test(feet)) return finish(true, null);
                return replanOrFail(complete ? "path ended short of the goal" : "goal unreachable, got as close as possible");
            }

            BlockPos wp = path.get(index);
            Vec3 aim = Vec3.atBottomCenterOf(wp);
            double hdist = Math.hypot(aim.x - p.getX(), aim.z - p.getZ());
            float yawError = PlayerControl.turnToward(p, new Vec3(aim.x, p.getEyeY(), aim.z), false);
            boolean forward = yawError < 50 || hdist > 2;
            boolean stepUp = wp.getY() > p.getY() + 0.5 && hdist < 1.8;
            boolean jump = stepUp || (p.horizontalCollision && forward) || (p.isInWater() && wp.getY() >= feet.getY());
            boolean straight = index + 2 < path.size() && sameDirection(path, index);
            boolean sprint = req.sprint && forward && yawError < 15 && straight && !p.isInWater() && p.getFoodData().getFoodLevel() > 6;
            PlayerControl.move(mc, forward, jump, sprint);

            // Stuck detection: no progress toward the current waypoint for a while.
            double dist = p.position().distanceTo(aim);
            if (dist < bestDist - 0.05) {
                bestDist = dist;
                lastProgress = ticks;
            } else if (ticks - lastProgress > STUCK_TICKS) {
                return replanOrFail("stuck");
            }
            return false;
        }

        private static boolean reached(LocalPlayer p, BlockPos wp) {
            double h = Math.hypot(wp.getX() + 0.5 - p.getX(), wp.getZ() + 0.5 - p.getZ());
            double dy = p.getY() - wp.getY();
            // A waypoint below us (a drop) only counts once we've dropped: standing on the rim above it can look
            // "close enough" horizontally while the hitbox still rests on the edge.
            return h < 0.45 && dy > -0.6 && dy < 0.5;
        }

        private static boolean sameDirection(List<BlockPos> path, int i) {
            BlockPos a = path.get(i), b = path.get(i + 1), c = path.get(i + 2);
            return b.getX() - a.getX() == c.getX() - b.getX() && b.getZ() - a.getZ() == c.getZ() - b.getZ();
        }

        private boolean replanOrFail(String why) {
            if (++replans > MAX_REPLANS) {
                return finish(false, why + " (gave up after " + MAX_REPLANS + " replans)");
            }
            PlayerControl.release(mc);
            return !plan() || future.isDone();
        }

        private boolean finish(boolean arrived, String reason) {
            PlayerControl.release(mc);
            LocalPlayer p = mc.player;
            JsonObject out = new JsonObject();
            out.addProperty("arrived", arrived);
            if (reason != null) out.addProperty("reason", reason);
            if (p != null) {
                JsonObject pos = new JsonObject();
                pos.addProperty("x", p.getX());
                pos.addProperty("y", p.getY());
                pos.addProperty("z", p.getZ());
                out.add("pos", pos);
                out.addProperty("distanceToTarget", p.position().distanceTo(Vec3.atBottomCenterOf(req.target)));
            }
            out.addProperty("ticks", ticks);
            out.addProperty("replans", replans);
            out.addProperty("pathLength", pathLength);
            out.addProperty("planMs", planNanos / 1_000_000);
            future.complete(out);
            return true;
        }
    }
}
