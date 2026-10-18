package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.mixin.GameTestInfoAccessor;
import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.coolerpromc.mctest.util.Describe;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import net.minecraft.commands.CommandSource;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.gametest.framework.GameTestAssertPosException;
import net.minecraft.gametest.framework.GameTestInfo;
import net.minecraft.gametest.framework.GlobalTestReporter;
import net.minecraft.gametest.framework.LogTestReporter;
import net.minecraft.gametest.framework.TestReporter;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.contents.TranslatableContents;
import net.minecraft.server.MinecraftServer;
import net.minecraft.util.Util;
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

/**
 * Runs GameTests (vanilla's and the tested mod's, registered through the loader's GameTest API) with {@code /test run}
 * and reports each test's result. Shared by the client (integrated server) and the dedicated server.
 */
public final class GameTestMethods {

    private static final List<Session> SESSIONS = new CopyOnWriteArrayList<>();
    private static boolean reporterInstalled;

    private GameTestMethods() {
    }

    /** @param server the server to run on; must throw a readable {@link RpcException} when there is none */
    public static void register(Supplier<MinecraftServer> server) {
        installReporter();
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
        server.registryAccess().lookupOrThrow(Registries.TEST_INSTANCE).listElements().forEach(holder -> {
            String id = holder.key().identifier().toString();
            if (f != null && !id.contains(f)) return;
            JsonObject t = new JsonObject();
            t.addProperty("id", id);
            t.addProperty("structure", holder.value().structure().toString());
            t.addProperty("maxTicks", holder.value().maxTicks());
            t.addProperty("required", holder.value().required());
            t.addProperty("manualOnly", holder.value().manualOnly());
            tests.add(t);
        });
        JsonObject out = new JsonObject();
        out.addProperty("count", tests.size());
        out.add("tests", tests);
        return out;
    }

    // ---- run ----

    private static CompletableFuture<JsonElement> run(MinecraftServer server, String tests, long timeoutMs) {
        Session session = new Session(tests);
        List<String> output = new ArrayList<>();
        CommandSource sink = new CommandSource() {
            @Override
            public void sendSystemMessage(Component message) {
                output.add(message.getString());
                if (message.getContents() instanceof TranslatableContents t && t.getKey().equals("commands.test.run.running")
                        && t.getArgs().length > 0) {
                    try {
                        session.expected = Integer.parseInt(String.valueOf(t.getArgs()[0] instanceof Component c ? c.getString() : t.getArgs()[0]));
                    } catch (NumberFormatException ignored) {
                        // Left at -1: completes on timeout instead.
                    }
                }
            }

            @Override
            public boolean acceptsSuccess() {
                return true;
            }

            @Override
            public boolean acceptsFailure() {
                return true;
            }

            @Override
            public boolean shouldInformAdmins() {
                return false;
            }
        };
        SESSIONS.add(session);
        Constants.LOG.info("MCTest: /test run {}", tests);
        server.getCommands().performPrefixedCommand(server.createCommandSourceStack().withSource(sink), "test run " + tests);

        if (session.expected <= 0) {
            SESSIONS.remove(session);
            // Nothing started: no match, a bad selector, or a structure problem. The command said why.
            throw new RpcException("No tests started for '" + tests + "': " + String.join(" | ", output)
                    + " (gametest.list shows the registered tests)");
        }
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
        volatile int expected = -1;

        Session(String selector) {
            this.selector = selector;
        }

        synchronized void record(GameTestInfo info, boolean passed) {
            JsonObject r = new JsonObject();
            r.addProperty("id", info.id().toString());
            r.addProperty("passed", passed);
            r.addProperty("required", info.isRequired());
            r.addProperty("ms", info.getRunTime());
            r.addProperty("ticks", ((GameTestInfoAccessor) info).mctest$getTick());
            BlockPos testPos = info.getTestBlockPos();
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
            results.put(info.id().toString(), r);
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
            return out;
        }
    }
}
