package com.coolerpromc.mctest.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.concurrent.CompletableFuture;

/**
 * A single remotely callable method. Invoked on a Netty I/O thread, so implementations must hop to the render thread
 * (see {@link ClientThread}) before touching game state, and must never block.
 */
@FunctionalInterface
public interface RpcMethod {

    CompletableFuture<JsonElement> invoke(JsonObject params) throws Exception;
}
