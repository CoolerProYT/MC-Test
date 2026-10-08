package com.coolerpromc.mctest.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;

public final class RpcRegistry {

    private static final Map<String, RpcMethod> METHODS = Collections.synchronizedMap(new TreeMap<>());

    private RpcRegistry() {
    }

    public static void register(String name, RpcMethod method) {
        METHODS.put(name, method);
    }

    public static Set<String> names() {
        return METHODS.keySet();
    }

    public static CompletableFuture<JsonElement> dispatch(String name, JsonObject params) {
        RpcMethod method = METHODS.get(name);
        if (method == null) {
            return CompletableFuture.failedFuture(new RpcException("Unknown method '" + name + "'. Available: " + names()));
        }
        try {
            return method.invoke(params);
        } catch (Throwable t) {
            return CompletableFuture.failedFuture(t);
        }
    }
}
