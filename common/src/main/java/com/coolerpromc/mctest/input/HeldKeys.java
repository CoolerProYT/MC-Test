package com.coolerpromc.mctest.input;

import com.mojang.blaze3d.platform.InputConstants;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Keys MCTest is holding down. Polling code ({@code Minecraft.hasShiftDown()}, {@code InputConstants.isKeyDown}) reads
 * SDL's real keyboard state, which synthetic key events never touch; a mixin on {@code isKeyDown} consults this set so
 * "hold shift for details" tooltips and similar mod checks see MCTest's keys as pressed.
 */
public final class HeldKeys {

    private static final int LEFT_CTRL = 224, LEFT_SHIFT = 225, LEFT_ALT = 226, LEFT_GUI = 227;

    private static final Set<Integer> HELD = ConcurrentHashMap.newKeySet();

    private HeldKeys() {
    }

    public static boolean isHeld(int scancode) {
        return HELD.contains(scancode);
    }

    public static void press(int scancode) {
        HELD.add(scancode);
    }

    public static void release(int scancode) {
        HELD.remove(scancode);
    }

    public static Set<Integer> held() {
        return Set.copyOf(HELD);
    }

    /** Runs an action with the left-hand modifier keys for SDL {@code KMOD_*} bits held (keys already held stay held). */
    public static <T> T withModifiers(int mods, Supplier<T> action) {
        int[] added = new int[4];
        int n = 0;
        if ((mods & InputConstants.MOD_CONTROL) != 0 && HELD.add(LEFT_CTRL)) added[n++] = LEFT_CTRL;
        if ((mods & InputConstants.MOD_SHIFT) != 0 && HELD.add(LEFT_SHIFT)) added[n++] = LEFT_SHIFT;
        if ((mods & InputConstants.MOD_ALT) != 0 && HELD.add(LEFT_ALT)) added[n++] = LEFT_ALT;
        if ((mods & InputConstants.MOD_SUPER) != 0 && HELD.add(LEFT_GUI)) added[n++] = LEFT_GUI;
        try {
            return action.get();
        } finally {
            for (int i = 0; i < n; i++) HELD.remove(added[i]);
        }
    }
}
