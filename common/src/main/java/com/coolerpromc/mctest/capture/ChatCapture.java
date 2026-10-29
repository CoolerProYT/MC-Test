package com.coolerpromc.mctest.capture;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.GuiMessageTag;
import net.minecraft.network.chat.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * Collects chat lines while a capture is open. Fed by {@code ChatComponentMixin}; render-thread only.
 */
public final class ChatCapture {

    private static final List<Session> SESSIONS = new ArrayList<>();

    private ChatCapture() {
    }

    public static Session open() {
        Session s = new Session();
        SESSIONS.add(s);
        return s;
    }

    /** {@code source} mirrors the 26.x GuiMessageSource names: SYSTEM_SERVER for system lines, PLAYER otherwise. */
    public static void onMessage(Component content, GuiMessageTag tag) {
        if (SESSIONS.isEmpty()) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("text", content.getString());
        line.addProperty("source", tag != null && "System".equals(tag.logTag()) ? "SYSTEM_SERVER" : "PLAYER");
        for (Session s : SESSIONS) {
            s.lines.add(line);
        }
    }

    public static final class Session {
        private final JsonArray lines = new JsonArray();

        /** Lines so far, without closing. */
        public JsonArray lines() {
            return lines;
        }

        public JsonArray close() {
            SESSIONS.remove(this);
            return lines;
        }
    }
}
