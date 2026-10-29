package com.coolerpromc.mctest.server;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * A minimal WebSocket server codec (RFC 6455) on plain Netty. Minecraft 1.21.1 ships Netty 4.1 without the HTTP codec,
 * and a nested copy of it would not reach a Fabric dev run (Loom strips nested jars from dependencies), so MCTest
 * speaks the protocol itself: the upgrade handshake, then text frames in both directions. Decoded text messages are
 * passed on as {@link String}s; send replies with {@link #sendText}.
 */
final class WebSocketCodec extends ByteToMessageDecoder {

    private static final String GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
    private static final int MAX_HANDSHAKE = 64 * 1024;
    private static final int MAX_MESSAGE = 16 * 1024 * 1024;

    private boolean upgraded;
    /** Payload of a fragmented text message so far, or null. */
    private ByteBuf fragments;

    static void sendText(ChannelHandlerContext ctx, String text) {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8);
        ctx.writeAndFlush(frame(0x1, payload));
    }

    @Override
    protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) throws Exception {
        if (!upgraded) {
            handshake(ctx, in);
            return;
        }
        while (in.readableBytes() >= 2) {
            in.markReaderIndex();
            int b0 = in.readUnsignedByte();
            int b1 = in.readUnsignedByte();
            boolean fin = (b0 & 0x80) != 0;
            int opcode = b0 & 0x0F;
            boolean masked = (b1 & 0x80) != 0;
            long len = b1 & 0x7F;
            if (len == 126) {
                if (in.readableBytes() < 2) { in.resetReaderIndex(); return; }
                len = in.readUnsignedShort();
            } else if (len == 127) {
                if (in.readableBytes() < 8) { in.resetReaderIndex(); return; }
                len = in.readLong();
            }
            if (len < 0 || len > MAX_MESSAGE) {
                close(ctx, 1009);
                return;
            }
            if (in.readableBytes() < (masked ? 4 : 0) + len) {
                in.resetReaderIndex();
                return;
            }
            byte[] mask = new byte[4];
            if (masked) in.readBytes(mask);
            byte[] payload = new byte[(int) len];
            in.readBytes(payload);
            if (masked) {
                for (int i = 0; i < payload.length; i++) payload[i] ^= mask[i & 3];
            }
            switch (opcode) {
                case 0x1, 0x0 -> { // text, continuation
                    if (opcode == 0x1 && fin && fragments == null) {
                        out.add(new String(payload, StandardCharsets.UTF_8));
                        break;
                    }
                    if (fragments == null) fragments = Unpooled.buffer();
                    fragments.writeBytes(payload);
                    if (fragments.readableBytes() > MAX_MESSAGE) {
                        close(ctx, 1009);
                        return;
                    }
                    if (fin) {
                        out.add(fragments.toString(StandardCharsets.UTF_8));
                        fragments.release();
                        fragments = null;
                    }
                }
                case 0x8 -> { // close
                    close(ctx, 1000);
                    return;
                }
                case 0x9 -> ctx.writeAndFlush(frame(0xA, payload)); // ping -> pong
                case 0xA -> { } // pong
                default -> { // binary or unknown: unsupported
                    close(ctx, 1003);
                    return;
                }
            }
        }
    }

    private void handshake(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        int end = indexOf(in, "\r\n\r\n");
        if (end < 0) {
            if (in.readableBytes() > MAX_HANDSHAKE) ctx.close();
            return;
        }
        String request = in.readCharSequence(end - in.readerIndex() + 4, StandardCharsets.ISO_8859_1).toString();
        String key = null;
        boolean upgrade = false;
        for (String line : request.split("\r\n")) {
            int colon = line.indexOf(':');
            if (colon < 0) continue;
            String name = line.substring(0, colon).trim().toLowerCase(Locale.ROOT);
            String value = line.substring(colon + 1).trim();
            if (name.equals("sec-websocket-key")) key = value;
            if (name.equals("upgrade") && value.equalsIgnoreCase("websocket")) upgrade = true;
        }
        if (!request.startsWith("GET ") || !upgrade || key == null) {
            String body = "MCTest control server: connect with a WebSocket client.\n";
            ctx.writeAndFlush(Unpooled.copiedBuffer("HTTP/1.1 400 Bad Request\r\nContent-Type: text/plain\r\nContent-Length: "
                    + body.length() + "\r\nConnection: close\r\n\r\n" + body, StandardCharsets.ISO_8859_1))
                    .addListener(ChannelFutureListener.CLOSE);
            return;
        }
        String accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1").digest((key + GUID).getBytes(StandardCharsets.ISO_8859_1)));
        ctx.writeAndFlush(Unpooled.copiedBuffer("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n"
                + "Sec-WebSocket-Accept: " + accept + "\r\n\r\n", StandardCharsets.ISO_8859_1));
        upgraded = true;
    }

    private static int indexOf(ByteBuf buf, String needle) {
        byte[] n = needle.getBytes(StandardCharsets.ISO_8859_1);
        outer:
        for (int i = buf.readerIndex(); i <= buf.writerIndex() - n.length; i++) {
            for (int j = 0; j < n.length; j++) {
                if (buf.getByte(i + j) != n[j]) continue outer;
            }
            return i;
        }
        return -1;
    }

    private static ByteBuf frame(int opcode, byte[] payload) {
        ByteBuf buf = Unpooled.buffer(payload.length + 10);
        buf.writeByte(0x80 | opcode);
        if (payload.length < 126) {
            buf.writeByte(payload.length);
        } else if (payload.length <= 0xFFFF) {
            buf.writeByte(126);
            buf.writeShort(payload.length);
        } else {
            buf.writeByte(127);
            buf.writeLong(payload.length);
        }
        return buf.writeBytes(payload);
    }

    private void close(ChannelHandlerContext ctx, int code) {
        ctx.writeAndFlush(frame(0x8, new byte[]{(byte) (code >> 8), (byte) code})).addListener(ChannelFutureListener.CLOSE);
    }

    @Override
    protected void handlerRemoved0(ChannelHandlerContext ctx) {
        if (fragments != null) {
            fragments.release();
            fragments = null;
        }
    }
}
