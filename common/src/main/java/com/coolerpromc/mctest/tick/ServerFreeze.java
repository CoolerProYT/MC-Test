package com.coolerpromc.mctest.tick;

import com.coolerpromc.mctest.dedicated.MCTestServer;
import com.coolerpromc.mctest.mixin.ServerCommonPacketListenerInvoker;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.concurrent.CompletableFuture;

/**
 * Full freeze: skips the whole server tick ({@code MinecraftServer.tickServer}), not just the worlds like
 * {@code /tick freeze}. Nothing ticks: levels, players, block entities, GameTests, and every mod's server tick
 * events (so mod timers and countdowns stop too). Packets are still processed between ticks and connections are
 * kept alive, so clients stay connected. {@link #step} lets an exact number of whole ticks run.
 *
 * <p>Server thread only: callers go through {@code ServerThread.call} or {@code server.submit}.
 */
public final class ServerFreeze {

    /** The server that is frozen; a different (e.g. new singleplayer) server is never affected. */
    private static volatile MinecraftServer frozen;
    private static volatile int stepsLeft;
    private static CompletableFuture<Integer> stepDone;
    private static int stepStartTick;

    private ServerFreeze() {
    }

    public static boolean isFrozen(MinecraftServer server) {
        return frozen == server && server != null;
    }

    public static boolean isStepping(MinecraftServer server) {
        return isFrozen(server) && stepsLeft > 0;
    }

    public static void set(MinecraftServer server, boolean on) {
        if (on) {
            frozen = server;
        } else if (frozen == server) {
            frozen = null;
            stepsLeft = 0;
            finishStep(server);
        }
    }

    /** Lets {@code ticks} whole server ticks run; completes with the number that ran once they are done. */
    public static CompletableFuture<Integer> step(MinecraftServer server, int ticks) {
        if (!isFrozen(server)) {
            throw new IllegalStateException("not fully frozen");
        }
        finishStep(server);
        stepsLeft = ticks;
        stepStartTick = server.getTickCount();
        stepDone = new CompletableFuture<>();
        return stepDone;
    }

    /** From the tick loop, instead of each {@code tickServer} call: true if the tick should run. */
    public static boolean shouldTick(MinecraftServer server) {
        if (frozen != server) {
            return true;
        }
        if (stepsLeft > 0) {
            stepsLeft--;
            return true;
        }
        finishStep(server);
        return false;
    }

    /** What still happens on a skipped tick: keep-alives, and MCTest's own scheduler on a dedicated server. */
    public static void whileFrozen(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            if (player.connection != null) {
                ((ServerCommonPacketListenerInvoker) player.connection).mctest$keepConnectionAlive();
            }
        }
        if (server.isDedicatedServer()) {
            // MCTest's waits and auto-op normally run from the server tick event, which a skipped tick never fires.
            MCTestServer.onServerTick(server);
        }
    }

    private static void finishStep(MinecraftServer server) {
        if (stepDone != null) {
            CompletableFuture<Integer> done = stepDone;
            stepDone = null;
            done.complete(server.getTickCount() - stepStartTick);
        }
    }
}
