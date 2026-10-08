package com.coolerpromc.mctest.rpc;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Typed accessors for request params that fail with a readable {@link RpcException}.
 */
public final class Params {

    private Params() {
    }

    private static JsonElement get(JsonObject params, String key) {
        JsonElement e = params.get(key);
        return e == null || e.isJsonNull() ? null : e;
    }

    public static boolean has(JsonObject params, String key) {
        return get(params, key) != null;
    }

    public static String string(JsonObject params, String key) {
        JsonElement e = get(params, key);
        if (e == null) {
            throw new RpcException("Missing required param '" + key + "'");
        }
        return e.getAsString();
    }

    public static String string(JsonObject params, String key, String def) {
        JsonElement e = get(params, key);
        return e == null ? def : e.getAsString();
    }

    public static int integer(JsonObject params, String key) {
        return (int) Math.floor(number(params, key));
    }

    public static int integer(JsonObject params, String key, int def) {
        return has(params, key) ? integer(params, key) : def;
    }

    public static double number(JsonObject params, String key) {
        JsonElement e = get(params, key);
        if (e == null) {
            throw new RpcException("Missing required param '" + key + "'");
        }
        try {
            return e.getAsDouble();
        } catch (RuntimeException ex) {
            throw new RpcException("Param '" + key + "' must be a number");
        }
    }

    public static double number(JsonObject params, String key, double def) {
        return has(params, key) ? number(params, key) : def;
    }

    public static boolean bool(JsonObject params, String key, boolean def) {
        JsonElement e = get(params, key);
        return e == null ? def : e.getAsBoolean();
    }
}
