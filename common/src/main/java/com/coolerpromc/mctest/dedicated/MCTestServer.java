package com.coolerpromc.mctest.dedicated;

import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.capture.LogCapture;
import com.coolerpromc.mctest.methods.GameTestMethods;
import com.coolerpromc.mctest.methods.LogMethods;
import com.coolerpromc.mctest.platform.Services;
import com.coolerpromc.mctest.server.ControlServer;
import com.coolerpromc.mctest.tick.TickScheduler;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.players.PlayerList;
import org.jspecify.annotations.Nullable;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

/**
 * Common dedicated-server entry point. Loader modules call {@link #init()} from their server-only entrypoints and
 * forward the server lifecycle and tick events; nothing here runs on a client (singleplayer is covered by the client
 * API, which reads the integrated server directly).
 */
public final class MCTestServer {

    private static volatile @Nullable MinecraftServer server;
    private static volatile String state = "starting";
    private static final Set<UUID> AUTO_OPPED = new HashSet<>();

    private MCTestServer() {
    }

    public static void init() {
        LogCapture.ensureInstalled();
        Constants.LOG.info("MCTest server init on {} ({})", Services.PLATFORM.getPlatformName(), Services.PLATFORM.getEnvironmentName());
        if (offlineMode()) {
            Constants.LOG.warn("MCTest: dev server runs in offline mode so dev clients can join (opt out with -Dmctest.onlineMode=true)");
        }
        ServerMethods.register();
        LogMethods.register();
        GameTestMethods.register(ServerThread::server);
        ControlServer.start(ControlServer.configuredServerPort());
    }

    /**
     * Dev clients have no Mojang session, so a dev server must not authenticate them or require signed chat.
     * Applied by the server mixins; never outside a development environment.
     */
    public static boolean offlineMode() {
        return Services.PLATFORM.isDevelopmentEnvironment() && !Boolean.getBoolean("mctest.onlineMode");
    }

    /** Op every player when they join, so client-side tests can run commands. Opt out with -Dmctest.autoOp=false. */
    private static boolean autoOp() {
        return Services.PLATFORM.isDevelopmentEnvironment() && Boolean.parseBoolean(System.getProperty("mctest.autoOp", "true"));
    }

    public static void onServerStarting(MinecraftServer s) {
        server = s;
        state = "starting";
    }

    public static void onServerStarted(MinecraftServer s) {
        server = s;
        state = "running";
        // New 26.x servers whitelist by default, which locks dev clients out. Turned off at start (like
        // `/whitelist off`), so `/whitelist on` still works for tests that need it. Keep it with -Dmctest.whitelist=true.
        if (Services.PLATFORM.isDevelopmentEnvironment() && !Boolean.getBoolean("mctest.whitelist") && s.isUsingWhitelist()) {
            s.setUsingWhitelist(false);
            Constants.LOG.warn("MCTest: turned the whitelist off so dev clients can join (keep it with -Dmctest.whitelist=true)");
        }
        Constants.LOG.info("MCTest server ready (control API on ws://{}:{}/)", ControlServer.HOST, ControlServer.configuredServerPort());
    }

    public static void onServerStopping(MinecraftServer s) {
        state = "stopping";
    }

    /** Called by the loader at the end of every server tick, on the server thread. */
    public static void onServerTick(MinecraftServer s) {
        if (TickScheduler.tickCount() % 100 == 0) {
            LogCapture.ensureInstalled();
        }
        if (TickScheduler.tickCount() % 10 == 0 && autoOp()) {
            opNewPlayers(s.getPlayerList());
        }
        TickScheduler.tick();
    }

    private static void opNewPlayers(PlayerList players) {
        Set<UUID> online = new HashSet<>();
        for (ServerPlayer p : players.getPlayers()) {
            online.add(p.getUUID());
            // Once per join, so a test that deops a player is not undone until they rejoin.
            if (AUTO_OPPED.add(p.getUUID()) && !players.isOp(p.nameAndId())) {
                players.op(p.nameAndId());
                Constants.LOG.info("MCTest: opped {} (dev server; disable with -Dmctest.autoOp=false)", p.getGameProfile().name());
            }
        }
        AUTO_OPPED.retainAll(online);
    }

    public static @Nullable MinecraftServer serverOrNull() {
        return server;
    }

    public static String state() {
        return state;
    }
}
