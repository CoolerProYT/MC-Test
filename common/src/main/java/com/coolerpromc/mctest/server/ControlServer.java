package com.coolerpromc.mctest.server;

import com.coolerpromc.mctest.Constants;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.SocketChannel;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.http.HttpObjectAggregator;
import io.netty.handler.codec.http.HttpServerCodec;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolConfig;
import io.netty.handler.codec.http.websocketx.WebSocketServerProtocolHandler;
import io.netty.util.concurrent.DefaultThreadFactory;

import java.net.InetSocketAddress;

/**
 * Loopback-only WebSocket server. Uses the Netty that Minecraft already ships, on daemon threads so it can never keep
 * the JVM alive after the game exits.
 */
public final class ControlServer {

    public static final String HOST = "127.0.0.1";
    public static final int DEFAULT_PORT = 25599;
    /** Default for the dedicated server's control API, so a client and a server can run side by side. */
    public static final int DEFAULT_SERVER_PORT = 25598;

    private static EventLoopGroup group;
    private static Channel channel;

    private ControlServer() {
    }

    public static int configuredPort() {
        return Integer.getInteger("mctest.port", DEFAULT_PORT);
    }

    public static int configuredServerPort() {
        return Integer.getInteger("mctest.serverPort", DEFAULT_SERVER_PORT);
    }

    public static synchronized void start(int port) {
        if (channel != null) {
            return;
        }
        group = new MultiThreadIoEventLoopGroup(1, new DefaultThreadFactory("mctest-ws", true), NioIoHandler.newFactory());
        WebSocketServerProtocolConfig wsConfig = WebSocketServerProtocolConfig.newBuilder()
                .websocketPath("/")
                .checkStartsWith(true)
                .maxFramePayloadLength(16 * 1024 * 1024)
                .build();
        try {
            channel = new ServerBootstrap()
                    .group(group)
                    .channel(NioServerSocketChannel.class)
                    .childHandler(new ChannelInitializer<SocketChannel>() {
                        @Override
                        protected void initChannel(SocketChannel ch) {
                            ch.pipeline()
                                    .addLast(new HttpServerCodec())
                                    .addLast(new HttpObjectAggregator(64 * 1024))
                                    .addLast(new WebSocketServerProtocolHandler(wsConfig))
                                    .addLast(new RpcFrameHandler());
                        }
                    })
                    .bind(new InetSocketAddress(HOST, port))
                    .syncUninterruptibly()
                    .channel();
            Constants.LOG.info("MCTest control server listening on ws://{}:{}/", HOST, port);
        } catch (Exception e) {
            // Never take the game down because the port is busy (e.g. two clients running).
            Constants.LOG.error("MCTest control server failed to bind {}:{}", HOST, port, e);
            group.shutdownGracefully();
            group = null;
            channel = null;
        }
    }

    public static synchronized void stop() {
        if (channel != null) {
            channel.close();
            channel = null;
        }
        if (group != null) {
            group.shutdownGracefully();
            group = null;
        }
    }
}
