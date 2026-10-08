package com.coolerpromc.mctest.rpc;

import net.minecraft.client.Minecraft;

import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

/**
 * Schedules work onto the render thread without ever blocking it.
 */
public final class ClientThread {

    private ClientThread() {
    }

    public static Minecraft mc() {
        Minecraft mc = Minecraft.getInstance();
        if (mc == null) {
            throw new RpcException("Minecraft is not initialised yet");
        }
        return mc;
    }

    /** Runs {@code task} on the render thread and completes with its result. */
    public static <T> CompletableFuture<T> call(Supplier<T> task) {
        CompletableFuture<T> future = new CompletableFuture<>();
        mc().execute(() -> {
            try {
                future.complete(task.get());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /** Runs {@code task} on the render thread; the task completes the returned future itself (possibly later). */
    public static <T> CompletableFuture<T> callAsync(Supplier<CompletableFuture<T>> task) {
        return call(task).thenCompose(f -> f);
    }
}
