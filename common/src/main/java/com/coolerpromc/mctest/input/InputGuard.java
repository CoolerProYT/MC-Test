package com.coolerpromc.mctest.input;

import java.util.function.Supplier;

/**
 * Tells real user input apart from MCTest's synthetic input, so the game only captures or moves the OS cursor when a
 * person is actually using the game window. Render thread only (except reads of the timestamp).
 */
public final class InputGuard {

    /** How long after a real click/key press the game may still capture the cursor (e.g. closing a screen with E). */
    private static final long USER_ACTIVE_MS = 2_000;

    private static int syntheticDepth;
    private static volatile long lastRealInput;

    private InputGuard() {
    }

    /** Runs MCTest-generated input; anything it triggers is not treated as the user's. */
    public static <T> T synthetic(Supplier<T> action) {
        syntheticDepth++;
        try {
            return action.get();
        } finally {
            syntheticDepth--;
        }
    }

    /** Called from the real mouse/keyboard handlers. */
    public static void noteInput() {
        if (syntheticDepth == 0) {
            lastRealInput = System.currentTimeMillis();
        }
    }

    /** May the game capture (grab) or warp the OS cursor right now? */
    public static boolean userIsActive() {
        return Boolean.getBoolean("mctest.mouseWarp") || System.currentTimeMillis() - lastRealInput < USER_ACTIVE_MS;
    }
}
