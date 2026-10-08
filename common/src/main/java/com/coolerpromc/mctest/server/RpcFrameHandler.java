package com.coolerpromc.mctest.server;

import com.coolerpromc.mctest.Constants;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.codec.http.websocketx.TextWebSocketFrame;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

/**
 * Handles {@code {id, method, params}} frames and replies with {@code {id, result}} or {@code {id, error}}.
 * Requests are handled concurrently; responses are matched by id, not by order.
 */
final class RpcFrameHandler extends SimpleChannelInboundHandler<TextWebSocketFrame> {

    private static final Gson GSON = new GsonBuilder().serializeNulls().disableHtmlEscaping().create();

    @Override
    protected void channelRead0(ChannelHandlerContext ctx, TextWebSocketFrame frame) {
        JsonElement id = JsonNull.INSTANCE;
        String method;
        JsonObject params;
        try {
            JsonObject request = JsonParser.parseString(frame.text()).getAsJsonObject();
            if (request.has("id")) {
                id = request.get("id");
            }
            method = request.get("method").getAsString();
            params = request.has("params") && request.get("params").isJsonObject() ? request.getAsJsonObject("params") : new JsonObject();
        } catch (Exception e) {
            send(ctx, error(id, new RpcException("Malformed request: " + e.getMessage())));
            return;
        }

        final JsonElement requestId = id;
        RpcRegistry.dispatch(method, params).whenComplete((result, throwable) -> {
            if (throwable != null) {
                send(ctx, error(requestId, throwable));
            } else {
                JsonObject response = new JsonObject();
                response.add("id", requestId);
                response.add("result", result == null ? JsonNull.INSTANCE : result);
                send(ctx, response);
            }
        });
    }

    private static void send(ChannelHandlerContext ctx, JsonObject response) {
        if (ctx.channel().isActive()) {
            ctx.writeAndFlush(new TextWebSocketFrame(GSON.toJson(response)));
        }
    }

    private static JsonObject error(JsonElement id, Throwable t) {
        while ((t instanceof CompletionException || t instanceof ExecutionException) && t.getCause() != null) {
            t = t.getCause();
        }
        if (!(t instanceof RpcException)) {
            Constants.LOG.warn("MCTest request failed", t);
        }
        StringWriter stack = new StringWriter();
        t.printStackTrace(new PrintWriter(stack));

        JsonObject err = new JsonObject();
        err.addProperty("message", t.getMessage() != null ? t.getMessage() : t.getClass().getName());
        err.addProperty("type", t.getClass().getName());
        err.addProperty("stack", stack.toString());
        JsonObject response = new JsonObject();
        response.add("id", id);
        response.add("error", err);
        return response;
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Constants.LOG.debug("MCTest connection error", cause);
        ctx.close();
    }
}
