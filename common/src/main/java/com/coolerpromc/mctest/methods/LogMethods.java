package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.capture.LogCapture;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import org.apache.logging.log4j.Level;

import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/** {@code logs}: captured log lines. Shared by the client and the dedicated server. */
public final class LogMethods {

    private LogMethods() {
    }

    public static void register() {
        RpcRegistry.register("logs", params -> {
            long since = (long) Params.number(params, "since", 0);
            String level = Params.string(params, "level", null);
            Level minLevel = level == null ? null : Level.toLevel(level.toUpperCase(Locale.ROOT), null);
            if (level != null && minLevel == null) {
                throw new RpcException("Unknown level '" + level + "'. Use TRACE, DEBUG, INFO, WARN, ERROR or FATAL");
            }
            String contains = Params.string(params, "contains", null);
            int limit = Math.max(1, Params.integer(params, "limit", 200));
            return CompletableFuture.completedFuture(LogCapture.query(since, minLevel, contains, limit));
        });
    }
}
