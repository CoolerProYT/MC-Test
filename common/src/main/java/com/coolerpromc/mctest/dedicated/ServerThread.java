package com.coolerpromc.mctest.dedicated;

import com.coolerpromc.mctest.rpc.RpcException;
import net.minecraft.server.MinecraftServer;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Schedules work onto the dedicated server thread without ever blocking it.
 */
public final class ServerThread {

    private ServerThread() {
    }

    public static MinecraftServer server() {
        MinecraftServer server = MCTestServer.serverOrNull();
        if (server == null) {
            throw new RpcException("The server is still starting; call status (or mc_server_wait_ready) until ready");
        }
        return server;
    }

    /** Runs {@code task} on the server thread and completes with its result. */
    public static <T> CompletableFuture<T> call(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        server().execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Runs {@code task} on the server thread; the task completes the returned future itself (possibly later). */
    public static <T> CompletableFuture<T> callAsync(Supplier<CompletableFuture<T>> task) {
        return call(task).thenCompose(f -> f);
    }
}
