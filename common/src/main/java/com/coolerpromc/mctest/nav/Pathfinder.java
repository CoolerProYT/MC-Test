package com.coolerpromc.mctest.nav;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.tags.FluidTags;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.VoxelShape;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.function.Predicate;

/**
 * A* over the client's loaded blocks, for positions a player's feet can occupy. Moves: walk (incl. diagonals that
 * don't cut corners), jump up one block, drop up to three (no fall damage), swim. Avoids lava, fire, magma, cactus,
 * berry bushes, powder snow and cobwebs. Unloaded chunks are walls.
 */
public final class Pathfinder {

    private static final int MAX_DROP = 3;

    private Pathfinder() {
    }

    /** A path of feet positions from start (exclusive) to the goal; {@code complete} is false for a best-effort partial path. */
    public record Path(List<BlockPos> points, boolean complete, int expanded) {
    }

    private record Node(BlockPos pos, double g, double f, Node parent) {
    }

    public static Path find(Level level, BlockPos start, Predicate<BlockPos> goal, BlockPos target, int maxNodes) {
        Terrain t = new Terrain(level);
        PriorityQueue<Node> open = new PriorityQueue<>((a, b) -> Double.compare(a.f, b.f));
        Map<Long, Double> best = new HashMap<>();
        Node first = new Node(start, 0, h(start, target), null);
        open.add(first);
        best.put(start.asLong(), 0.0);
        Node closest = first;
        double closestH = h(start, target);
        int expanded = 0;

        while (!open.isEmpty() && expanded < maxNodes) {
            Node cur = open.poll();
            if (cur.g > best.getOrDefault(cur.pos.asLong(), Double.MAX_VALUE)) {
                continue; // stale entry
            }
            expanded++;
            if (goal.test(cur.pos)) {
                return new Path(reconstruct(cur), true, expanded);
            }
            double ch = h(cur.pos, target);
            if (ch < closestH) {
                closestH = ch;
                closest = cur;
            }
            for (Step s : t.neighbours(cur.pos)) {
                double g = cur.g + s.cost;
                long key = s.pos.asLong();
                if (g < best.getOrDefault(key, Double.MAX_VALUE)) {
                    best.put(key, g);
                    open.add(new Node(s.pos, g, g + h(s.pos, target), cur));
                }
            }
        }
        return new Path(reconstruct(closest), false, expanded);
    }

    private static double h(BlockPos a, BlockPos b) {
        double dx = Math.abs(a.getX() - b.getX());
        double dz = Math.abs(a.getZ() - b.getZ());
        double dy = Math.abs(a.getY() - b.getY());
        // Octile distance plus vertical; slightly inflated for speed (paths stay near-optimal).
        return 1.05 * (Math.max(dx, dz) + 0.414 * Math.min(dx, dz) + dy);
    }

    private static List<BlockPos> reconstruct(Node end) {
        List<BlockPos> out = new ArrayList<>();
        for (Node n = end; n != null && n.parent != null; n = n.parent) {
            out.add(n.pos);
        }
        Collections.reverse(out);
        return out;
    }

    private record Step(BlockPos pos, double cost) {
    }

    /** Block queries with a small cache; one instance per search. */
    public static final class Terrain {
        private final Level level;
        private final Map<Long, Boolean> passableCache = new HashMap<>();

        public Terrain(Level level) {
            this.level = level;
        }

        /** The feet position for an entity standing at y (handles standing on slabs/carpets). */
        public BlockPos feet(double x, double y, double z) {
            BlockPos p = BlockPos.containing(x, y + 0.01, z);
            return passable(p) ? p : p.above();
        }

        public boolean passable(BlockPos p) {
            return passableCache.computeIfAbsent(p.asLong(), k -> computePassable(p));
        }

        private boolean computePassable(BlockPos p) {
            if (!level.isLoaded(p)) return false;
            BlockState s = level.getBlockState(p);
            if (dangerous(s) || s.is(Blocks.COBWEB)) return false;
            return s.getCollisionShape(level, p).isEmpty();
        }

        public boolean water(BlockPos p) {
            return level.getFluidState(p).is(FluidTags.WATER);
        }

        /** Can a player stand with feet in p (or float in water at p)? */
        public boolean standable(BlockPos p) {
            if (!passable(p) || !passable(p.above())) return false;
            if (water(p)) return true;
            BlockPos below = p.below();
            if (!level.isLoaded(below)) return false;
            BlockState s = level.getBlockState(below);
            if (dangerous(s)) return false;
            VoxelShape shape = s.getCollisionShape(level, below);
            // Something to stand on, but not a fence/wall (1.5 high: can't be stepped or jumped onto).
            return !shape.isEmpty() && shape.max(Direction.Axis.Y) <= 1.0;
        }

        private static boolean dangerous(BlockState s) {
            return s.getFluidState().is(FluidTags.LAVA)
                    || s.is(Blocks.LAVA) || s.is(Blocks.FIRE) || s.is(Blocks.SOUL_FIRE) || s.is(Blocks.MAGMA_BLOCK)
                    || s.is(Blocks.CACTUS) || s.is(Blocks.SWEET_BERRY_BUSH) || s.is(Blocks.POWDER_SNOW)
                    || s.is(Blocks.WITHER_ROSE) || s.is(Blocks.CAMPFIRE) || s.is(Blocks.SOUL_CAMPFIRE);
        }

        List<Step> neighbours(BlockPos p) {
            List<Step> out = new ArrayList<>(12);
            boolean inWater = water(p);
            double waterMul = inWater ? 2.5 : 1.0;
            for (int dx = -1; dx <= 1; dx++) {
                for (int dz = -1; dz <= 1; dz++) {
                    if (dx == 0 && dz == 0) continue;
                    boolean diagonal = dx != 0 && dz != 0;
                    BlockPos n = p.offset(dx, 0, dz);
                    if (diagonal && !(passable(p.offset(dx, 0, 0)) && passable(p.offset(dx, 1, 0))
                            && passable(p.offset(0, 0, dz)) && passable(p.offset(0, 1, dz)))) {
                        continue; // would clip a corner
                    }
                    double base = diagonal ? 1.414 : 1.0;
                    if (standable(n)) {
                        out.add(new Step(n, base * (water(n) ? 2.5 : waterMul)));
                    } else if (!diagonal && standable(n.above()) && passable(p.above(2))) {
                        out.add(new Step(n.above(), base + 1.0)); // jump up
                    } else if (passable(n) && passable(n.above())) {
                        for (int d = 1; d <= MAX_DROP; d++) {
                            BlockPos m = n.below(d);
                            if (standable(m)) {
                                out.add(new Step(m, base + 0.5 * d));
                                break;
                            }
                            if (!passable(m)) break;
                        }
                    }
                }
            }
            if (inWater && standable(p.above())) {
                out.add(new Step(p.above(), 1.5)); // swim up
            }
            return out;
        }
    }
}
