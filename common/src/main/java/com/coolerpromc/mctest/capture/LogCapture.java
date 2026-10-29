package com.coolerpromc.mctest.capture;

import com.coolerpromc.mctest.platform.Services;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.LogEvent;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.appender.AbstractAppender;
import org.apache.logging.log4j.core.appender.AbstractOutputStreamAppender;
import org.apache.logging.log4j.core.config.Configuration;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A log4j appender on the root logger that keeps the most recent lines (and their exceptions) in memory, each with a
 * monotonically increasing sequence number so callers can poll with {@code since}.
 */
public final class LogCapture {

    private static final String APPENDER_NAME = "MCTestCapture";
    private static final int CAPACITY = Integer.getInteger("mctest.logBuffer", 10_000);

    private static final Deque<JsonObject> BUFFER = new ArrayDeque<>();
    private static long nextSeq = 1;
    private static Appender appender;

    private LogCapture() {
    }

    /** Attaches to the root logger. Safe to call repeatedly: re-attaches if a loader reconfigured log4j. */
    public static synchronized void ensureInstalled() {
        if (!(LogManager.getContext(false) instanceof LoggerContext ctx)) {
            return;
        }
        Configuration config = ctx.getConfiguration();
        if (config.getRootLogger().getAppenders().containsKey(APPENDER_NAME)) {
            return;
        }
        boolean firstInstall = appender == null;
        if (firstInstall) {
            // Lines logged before we attached (other mods' early init) only exist in the log file so far.
            backfill(config);
            appender = new Appender();
            appender.start();
        }
        config.addAppender(appender);
        config.getRootLogger().addAppender(appender, null, null);
        ctx.updateLoggers();
    }

    // [12:34:56] [Render thread/INFO] (Logger) message            (Fabric)
    // [08Oct2026 12:34:56.789] [main/INFO] [logger/MARKER]: message (NeoForge)
    private static final Pattern FILE_LINE = Pattern.compile("^\\[([^\\]]+)\\] \\[(.+?)/([A-Z]+)\\] [\\[(]([^\\])]*)[\\])]:? (.*)$");

    /** Flushes the game's buffered file appenders, then imports this session's latest.log. */
    private static void backfill(Configuration config) {
        try {
            for (org.apache.logging.log4j.core.Appender a : config.getAppenders().values()) {
                if (a instanceof AbstractOutputStreamAppender<?> stream) {
                    stream.getManager().flush();
                }
            }
            Path file = Services.PLATFORM.getGameDir().resolve("logs/latest.log");
            if (!Files.isRegularFile(file)) {
                return;
            }
            JsonObject current = null;
            StringBuilder stack = null;
            for (String raw : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Matcher m = FILE_LINE.matcher(raw);
                if (m.matches()) {
                    if (current != null) finishBackfilled(current, stack);
                    current = new JsonObject();
                    current.addProperty("seq", nextSeq++);
                    current.addProperty("time", (Number) null);
                    current.addProperty("level", m.group(3));
                    String logger = m.group(4);
                    int slash = logger.indexOf('/');
                    current.addProperty("logger", slash >= 0 ? logger.substring(0, slash) : logger);
                    current.addProperty("thread", m.group(2));
                    current.addProperty("message", m.group(5));
                    current.addProperty("backfilled", true);
                    stack = null;
                } else if (current != null && !raw.isEmpty()) {
                    // Continuation: exception header and stack frames.
                    if (stack == null) stack = new StringBuilder();
                    stack.append(raw).append('\n');
                }
            }
            if (current != null) finishBackfilled(current, stack);
        } catch (Exception e) {
            // Best effort: live capture still works.
        }
    }

    private static void finishBackfilled(JsonObject line, StringBuilder stack) {
        if (stack != null) {
            String text = stack.toString();
            String first = text.lines().findFirst().orElse("").trim();
            if (first.matches("^(Caused by: )?[\\w.$]+(Exception|Error|Throwable)\\b.*") || first.startsWith("at ")) {
                line.addProperty("exception", first);
                line.addProperty("stack", text);
            } else {
                // A multi-line message (e.g. the loader's mod list), not an exception.
                line.addProperty("message", line.get("message").getAsString() + "\n" + text.stripTrailing());
            }
        }
        BUFFER.addLast(line);
        while (BUFFER.size() > CAPACITY) {
            BUFFER.removeFirst();
        }
    }

    private static synchronized void record(LogEvent event) {
        // Our own socket threads log a Netty handshake per connection at DEBUG; that is noise, not the game's output.
        if (event.getThreadName().startsWith("mctest-ws") && !event.getLevel().isMoreSpecificThan(Level.WARN)) {
            return;
        }
        JsonObject line = new JsonObject();
        line.addProperty("seq", nextSeq++);
        line.addProperty("time", event.getTimeMillis());
        line.addProperty("level", event.getLevel().name());
        line.addProperty("logger", event.getLoggerName());
        line.addProperty("thread", event.getThreadName());
        line.addProperty("message", event.getMessage().getFormattedMessage());
        Throwable thrown = event.getThrown();
        if (thrown != null) {
            StringWriter sw = new StringWriter();
            thrown.printStackTrace(new PrintWriter(sw));
            line.addProperty("exception", thrown.toString());
            line.addProperty("stack", sw.toString());
        }
        BUFFER.addLast(line);
        while (BUFFER.size() > CAPACITY) {
            BUFFER.removeFirst();
        }
    }

    /** Sequence number of the newest captured line. */
    public static synchronized long lastSeq() {
        return nextSeq - 1;
    }

    /** Newest line after {@code since} whose message or exception contains {@code needle} (case-insensitive), or null. */
    public static synchronized JsonObject findSince(long since, String needle) {
        String n = needle.toLowerCase();
        JsonObject found = null;
        for (JsonObject line : BUFFER) {
            if (line.get("seq").getAsLong() <= since) continue;
            String hay = (line.get("message").getAsString() + " " + (line.has("exception") ? line.get("exception").getAsString() : "")).toLowerCase();
            if (hay.contains(n)) found = line;
        }
        return found;
    }

    /**
     * @param since    only lines with {@code seq > since}
     * @param minLevel e.g. WARN to get WARN, ERROR and FATAL; null for everything
     * @param contains case-insensitive substring filter on logger + message + exception; null for none
     * @param limit    maximum number of (most recent matching) lines
     */
    public static synchronized JsonObject query(long since, Level minLevel, String contains, int limit) {
        String needle = contains == null ? null : contains.toLowerCase();
        Deque<JsonObject> matched = new ArrayDeque<>();
        for (JsonObject line : BUFFER) {
            if (line.get("seq").getAsLong() <= since) continue;
            if (minLevel != null && !Level.getLevel(line.get("level").getAsString()).isMoreSpecificThan(minLevel)) continue;
            if (needle != null) {
                String hay = (line.get("logger").getAsString() + " " + line.get("message").getAsString() + " "
                        + (line.has("exception") ? line.get("exception").getAsString() : "")).toLowerCase();
                if (!hay.contains(needle)) continue;
            }
            matched.addLast(line);
            if (matched.size() > limit) matched.removeFirst();
        }
        JsonObject out = new JsonObject();
        JsonArray lines = new JsonArray();
        matched.forEach(lines::add);
        out.add("lines", lines);
        // Pass this back as `since` to get only newer lines next time.
        out.addProperty("next", nextSeq - 1);
        out.addProperty("oldestBuffered", BUFFER.isEmpty() ? nextSeq : BUFFER.peekFirst().get("seq").getAsLong());
        return out;
    }

    private static final class Appender extends AbstractAppender {
        Appender() {
            super(APPENDER_NAME, null, null, true); // the 4-argument constructor exists in every log4j a 1.20.1 loader ships
        }

        @Override
        public void append(LogEvent event) {
            record(event);
        }
    }
}
