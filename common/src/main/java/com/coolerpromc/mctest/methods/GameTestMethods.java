package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.mixin.GameTestInfoAccessor;
import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.util.Describe;
import com.coolerpromc.mctest.tick.TickControl;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.core.BlockPos;
import net.minecraft.gametest.framework.GameTestAssertPosException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GameTestRegistry;
import net.minecraft.gametest.framework.GameTestRunner;
import net.minecraft.gametest.framework.GameTestTicker;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.TestFunction;
import net.minecraft.gametest.framework.TestReporter;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.Util;
import org.apache.commons.lang3.exception.ExceptionUtils;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * Runs GameTests (the tested mod's, registered through the loader's GameTest API into {@link GameTestRegistry}) the way
 * {@code /test run} does, and reports each test's result. Shared by the client (integrated server) and the dedicated
 * server. 1.21.1 only ticks GameTests in vanilla's IDE mode, so {@link #tickIfNeeded} drives them otherwise.
 */
public final class GameTestMethods {

    private static final List<Session> SESSIONS = new CopyOnWriteArrayList<>();
    private static boolean reporterInstalled;
    /** Server tick on which {@link GameTestTicker#SINGLETON} last ticked (set by GameTestTickerMixin). */
    public static volatile int lastTickerTick = -1;
    private static volatile MinecraftServer currentServer;

    public static MinecraftServer currentServer() {
        return currentServer;
    }

    public static void setCurrentServer(MinecraftServer server) {
        if (server != currentServer) {
            // A new world or server starts unfrozen.
            com.coolerpromc.mctest.tick.TickControl.get().setFrozen(false);
        }
        currentServer = server;
    }

    private GameTestMethods() {
    }

    /** @param server the server to run on; must throw a readable {@link RpcException} when there is none */
    public static void register(Supplier<MinecraftServer> server) {
        installReporter();
        SelfTests.register();
        RpcRegistry.register("gametest.list", params -> {
            String filter = Params.string(params, "filter", null);
            MinecraftServer s = server.get();
            return s.submit(() -> list(s, filter));
        });
        RpcRegistry.register("gametest.run", params -> {
            String tests = Params.string(params, "tests", "*:*").trim();
            if (tests.isEmpty() || tests.contains(" ")) throw new RpcException("tests must be one selector like mymod:* or mymod:my_test");
            long timeoutMs = (long) (Params.number(params, "timeoutSeconds", 300) * 1000);
            MinecraftServer s = server.get();
            return s.submit(() -> run(s, tests, timeoutMs)).thenCompose(f -> f);
        });
    }

    /** Wraps vanilla's log reporter so every finished test is also handed to the open sessions. */
    private static synchronized void installReporter() {
        if (reporterInstalled) return;
        reporterInstalled = true;
        TestReporter log = new LogTestReporter();
        GlobalTestReporter.replaceWith(new TestReporter() {
            @Override
            public void onTestFailed(GameTestInfo info) {
                log.onTestFailed(info);
                SESSIONS.forEach(s -> s.record(info, false));
            }

            @Override
            public void onTestSuccess(GameTestInfo info) {
                log.onTestSuccess(info);
                SESSIONS.forEach(s -> s.record(info, true));
            }

            @Override
            public void finish() {
                log.finish();
            }
        });
    }

    // ---- list ----

    private static JsonElement list(MinecraftServer server, String filter) {
        String f = filter == null ? null : filter.toLowerCase(Locale.ROOT);
        JsonArray tests = new JsonArray();
        GameTestRegistry.getAllTestFunctions().stream()
                .sorted(java.util.Comparator.comparing(TestFunction::getTestName))
                .forEach(fn -> {
                    if (f != null && !fn.getTestName().toLowerCase(Locale.ROOT).contains(f)
                            && !fn.getStructureName().toLowerCase(Locale.ROOT).contains(f)) return;
                    JsonObject t = new JsonObject();
                    t.addProperty("id", fn.getTestName());
                    t.addProperty("batch", fn.getBatchName());
                    t.addProperty("structure", fn.getStructureName());
                    t.addProperty("maxTicks", fn.getMaxTicks());
                    t.addProperty("required", fn.isRequired());
                    tests.add(t);
                });
        JsonObject out = new JsonObject();
        out.addProperty("count", tests.size());
        out.add("tests", tests);
        return out;
    }

    /**
     * Selector: {@code *} (every test), a test name ({@code mytests.place_block}), or a glob over the test name, the
     * structure id ({@code mymod:mytests.place_block}) or {@code <structure namespace>:<test name>}. So {@code mymod:*}
     * picks the tests whose structures live in {@code mymod}.
     */
    private static boolean matches(TestFunction fn, String selector) {
        String sel = selector.toLowerCase(Locale.ROOT);
        String name = fn.getTestName().toLowerCase(Locale.ROOT);
        String structure = fn.getStructureName().toLowerCase(Locale.ROOT);
        ResourceLocation id = ResourceLocation.tryParse(structure);
        String namespaced = (id == null ? "minecraft" : id.getNamespace()) + ":" + name;
        if (!sel.contains("*")) {
            return sel.equals(name) || sel.equals(structure) || sel.equals(namespaced);
        }
        if (sel.equals("*") || sel.equals("*:*")) return true;
        Pattern glob = Pattern.compile(("\\Q" + sel + "\\E").replace("*", "\\E.*\\Q"));
        return glob.matcher(name).matches() || glob.matcher(structure).matches() || glob.matcher(namespaced).matches();
    }

    /** Ticks the running GameTests when the game itself does not (anywhere but vanilla's IDE mode). Server thread. */
    public static void tickIfNeeded(MinecraftServer server) {
        if (SESSIONS.isEmpty() || lastTickerTick == server.getTickCount() || !TickControl.get().runsNormally()) return;
        GameTestTicker.SINGLETON.tick();
    }

    // ---- run ----

    private static CompletableFuture<JsonElement> run(MinecraftServer server, String tests, long timeoutMs) {
        List<TestFunction> functions = GameTestRegistry.getAllTestFunctions().stream().filter(fn -> matches(fn, tests)).toList();
        if (functions.isEmpty()) {
            throw new RpcException("No tests match '" + tests + "' (gametest.list shows the registered tests)");
        }
        SelfTests.ensureStructure(server);
        // Next to the first player (like /test run), else at the overworld spawn.
        ServerPlayer player = server.getPlayerList().getPlayers().isEmpty() ? null : server.getPlayerList().getPlayers().get(0);
        ServerLevel level = player != null ? player.serverLevel() : server.overworld();
        BlockPos around = player != null ? player.blockPosition() : level.getSharedSpawnPos();
        BlockPos start = new BlockPos(around.getX(), level.getHeightmapPos(Heightmap.Types.WORLD_SURFACE, around).getY() + 1, around.getZ() + 3);

        List<String> missing = new ArrayList<>();
        List<TestFunction> infos = new ArrayList<>();
        for (TestFunction fn : functions) {
            ResourceLocation structure = ResourceLocation.tryParse(fn.getStructureName());
            if (structure == null || level.getStructureManager().get(structure).isEmpty()) {
                missing.add(fn.getTestName() + " (structure " + fn.getStructureName() + ")");
            } else {
                infos.add(fn);
            }
        }
        if (infos.isEmpty()) {
            throw new RpcException("No tests started for '" + tests + "': test structures not found: " + String.join(", ", missing));
        }

        Session session = new Session(tests);
        session.expected = infos.size();
        missing.forEach(session.skipped::add);
        SESSIONS.add(session);
        Constants.LOG.info("MCTest: running {} GameTest(s) for {}", infos.size(), tests);
        GameTestTicker.SINGLETON.clear(); // what /test does first: stops a previous run
        GameTestRunner.clearMarkers(level);
        GameTestRegistry.forgetFailedTests();
        GameTestRunner.runTests(infos, start, net.minecraft.world.level.block.Rotation.NONE, level, GameTestTicker.SINGLETON, 8);

        session.checkDone();
        return session.future
                .completeOnTimeout(null, timeoutMs, TimeUnit.MILLISECONDS)
                .thenApply(v -> {
                    SESSIONS.remove(session);
                    return session.result(v == null && !session.isDone());
                });
    }

    private static final class Session {
        final String selector;
        final CompletableFuture<Boolean> future = new CompletableFuture<>();
        /** Last result per test id (retries replace earlier attempts). */
        final Map<String, JsonObject> results = new LinkedHashMap<>();
        /** Matching tests that could not start (missing structure). */
        final JsonArray skipped = new JsonArray();
        volatile int expected = -1;

        Session(String selector) {
            this.selector = selector;
        }

        synchronized void record(GameTestInfo info, boolean passed) {
            JsonObject r = new JsonObject();
            r.addProperty("id", info.getTestName());
            r.addProperty("passed", passed);
            r.addProperty("required", info.isRequired());
            r.addProperty("ms", info.getRunTime());
            r.addProperty("ticks", ((GameTestInfoAccessor) info).mctest$getTick());
            BlockPos testPos = info.getStructureBlockPos();
            r.add("testPos", testPos == null ? null : Describe.pos(testPos));
            Throwable error = info.getError();
            if (!passed && error != null) {
                r.addProperty("error", error.getMessage() != null ? error.getMessage() : Util.describeError(error));
                Throwable root = ExceptionUtils.getRootCause(error);
                if (root instanceof GameTestAssertPosException pos && pos.getAbsolutePos() != null) {
                    r.add("errorPos", Describe.pos(pos.getAbsolutePos()));
                    r.add("errorRelativePos", pos.getRelativePos() == null ? null : Describe.pos(pos.getRelativePos()));
                }
                if (root != null && root != error && !(root instanceof GameTestAssertPosException)) {
                    r.addProperty("cause", Util.describeError(root));
                }
            }
            results.put(info.getTestName(), r);
            checkDone();
        }

        synchronized boolean isDone() {
            return expected > 0 && results.size() >= expected;
        }

        void checkDone() {
            if (isDone()) future.complete(true);
        }

        synchronized JsonObject result(boolean timedOut) {
            int passed = 0, failedRequired = 0, failedOptional = 0;
            JsonArray list = new JsonArray();
            for (JsonObject r : results.values()) {
                list.add(r);
                if (r.get("passed").getAsBoolean()) passed++;
                else if (r.get("required").getAsBoolean()) failedRequired++;
                else failedOptional++;
            }
            JsonObject out = new JsonObject();
            out.addProperty("tests", selector);
            out.addProperty("total", expected);
            out.addProperty("finished", results.size());
            out.addProperty("passed", passed);
            out.addProperty("failed", failedRequired);
            out.addProperty("failedOptional", failedOptional);
            out.addProperty("timedOut", timedOut);
            // The verdict a CI step would use: every required test finished and passed.
            out.addProperty("success", !timedOut && failedRequired == 0 && results.size() >= expected);
            out.add("results", list);
            if (!skipped.isEmpty()) out.add("skipped", skipped);
            return out;
        }
    }
}
