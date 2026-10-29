package com.coolerpromc.mctest.tick;

/**
 * Freezing and stepping the game, which vanilla only has from 1.20.3 ({@code /tick}). Same idea and method names as
 * vanilla's {@code ServerTickRateManager}: while frozen, levels keep loading chunks and players keep moving, but time,
 * weather, block and fluid ticks, block events, block entities and every non-player entity stand still.
 * {@code ServerLevelTickMixin} applies it. One server runs per JVM (integrated or dedicated), so this is global.
 */
public final class TickControl {

    private static final TickControl INSTANCE = new TickControl();

    private volatile boolean frozen;
    private volatile int frozenTicksToRun;

    private TickControl() {
    }

    public static TickControl get() {
        return INSTANCE;
    }

    public boolean isFrozen() {
        return frozen;
    }

    public void setFrozen(boolean frozen) {
        this.frozen = frozen;
        if (!frozen) frozenTicksToRun = 0;
    }

    /** Runs {@code ticks} more ticks while frozen. False when not frozen. */
    public boolean stepGameIfPaused(int ticks) {
        if (!frozen) return false;
        frozenTicksToRun = ticks;
        return true;
    }

    public boolean isSteppingForward() {
        return frozenTicksToRun > 0;
    }

    /** Whether this tick runs the world (not frozen, or a step is in progress). */
    public boolean runsNormally() {
        return !frozen || frozenTicksToRun > 0;
    }

    /** Whether the world is standing still this tick. */
    public boolean isWorldFrozen() {
        return !runsNormally();
    }

    /** Players, and whatever a player rides, keep moving while frozen (as in vanilla). */
    public boolean isEntityFrozen(net.minecraft.world.entity.Entity entity) {
        return isWorldFrozen() && !(entity instanceof net.minecraft.world.entity.player.Player)
                && !(entity.getControllingPassenger() instanceof net.minecraft.world.entity.player.Player);
    }

    public float tickrate() {
        return 20f;
    }

    /** Called at the end of every server tick. */
    public void endTick() {
        if (frozenTicksToRun > 0) frozenTicksToRun--;
    }
}
