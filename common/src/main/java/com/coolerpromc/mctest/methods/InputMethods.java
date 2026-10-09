package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.input.HeldKeys;
import com.coolerpromc.mctest.input.InputGuard;
import com.coolerpromc.mctest.mixin.AbstractContainerScreenAccessor;
import com.coolerpromc.mctest.rpc.ClientThread;
import com.coolerpromc.mctest.rpc.Params;
import com.coolerpromc.mctest.rpc.RpcException;
import com.coolerpromc.mctest.rpc.RpcRegistry;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.mojang.blaze3d.platform.InputConstants;
import com.mojang.blaze3d.platform.Window;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonInfo;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ContainerInput;
import org.lwjgl.sdl.SDLKeyboard;

import java.util.Locale;
import java.util.Map;

/**
 * Screen inspection and synthetic input. Mouse and keyboard events are fed through vanilla's own
 * {@code MouseHandler}/{@code KeyboardHandler}, so screens, keybindings and mouse grabbing behave as with real input.
 * 26.x uses SDL3: key values are SDL scancodes and modifiers are SDL {@code KMOD_*} bits.
 */
public final class InputMethods {

    private static final Map<String, String> KEY_ALIASES = Map.ofEntries(
            Map.entry("esc", "escape"),
            Map.entry("return", "enter"),
            Map.entry("shift", "left.shift"),
            Map.entry("ctrl", "left.control"),
            Map.entry("control", "left.control"),
            Map.entry("alt", "left.alt"),
            Map.entry("super", "left.win"),
            Map.entry("meta", "left.win"),
            Map.entry("cmd", "left.win"),
            Map.entry("arrowup", "up"),
            Map.entry("arrowdown", "down"),
            Map.entry("arrowleft", "left"),
            Map.entry("arrowright", "right"),
            Map.entry("pageup", "page.up"),
            Map.entry("pagedown", "page.down"),
            Map.entry("`", "grave.accent"),
            Map.entry("grave", "grave.accent"),
            Map.entry("/", "slash"),
            Map.entry(" ", "space"));

    private InputMethods() {
    }

    public static void register() {
        RpcRegistry.register("snapshot", params -> ClientThread.call(InputMethods::snapshot));
        RpcRegistry.register("click", params -> {
            double x = Params.number(params, "x");
            double y = Params.number(params, "y");
            int button = mouseButton(params);
            int mods = modifiers(params);
            return ClientThread.call(() -> InputGuard.synthetic(() -> HeldKeys.withModifiers(mods, () -> click(x, y, button, mods))));
        });
        RpcRegistry.register("clickWidget", params -> {
            String id = Params.string(params, "id");
            int button = mouseButton(params);
            int mods = modifiers(params);
            return ClientThread.call(() -> InputGuard.synthetic(() -> HeldKeys.withModifiers(mods, () -> clickWidget(id, button, mods))));
        });
        RpcRegistry.register("hover", params -> {
            Integer slot = Params.has(params, "slot") ? Params.integer(params, "slot") : null;
            Double x = Params.has(params, "x") ? Params.number(params, "x") : null;
            Double y = Params.has(params, "y") ? Params.number(params, "y") : null;
            if (slot == null && (x == null || y == null)) {
                throw new RpcException("Give x and y, or a container slot index");
            }
            return ClientThread.call(() -> InputGuard.synthetic(() -> hover(slot, x, y)));
        });
        RpcRegistry.register("clickSlot", params -> {
            int index = Params.integer(params, "index");
            int button = Params.integer(params, "button", 0);
            ContainerInput input = containerInput(Params.string(params, "clickType", "PICKUP"));
            return ClientThread.call(() -> clickSlot(index, button, input));
        });
        RpcRegistry.register("key", params -> {
            int scancode = scancode(params.get("key"));
            int mods = modifiers(params);
            String action = Params.string(params, "action", "tap").toLowerCase(Locale.ROOT);
            if (!action.equals("tap") && !action.equals("press") && !action.equals("release")) {
                throw new RpcException("action must be tap, press or release");
            }
            return ClientThread.call(() -> InputGuard.synthetic(() -> key(scancode, mods, action)));
        });
        RpcRegistry.register("type", params -> {
            String text = Params.string(params, "text");
            return ClientThread.call(() -> InputGuard.synthetic(() -> type(text)));
        });
        RpcRegistry.register("closeScreen", params -> ClientThread.call(InputMethods::closeScreen));
    }

    // ---- snapshot ----

    private static JsonElement snapshot() {
        Minecraft mc = ClientThread.mc();
        Screen screen = mc.gui.screen();
        JsonObject out = new JsonObject();
        out.add("screen", StatusMethods.describeScreen(screen));
        Window window = mc.getWindow();
        out.addProperty("guiWidth", window.getGuiScaledWidth());
        out.addProperty("guiHeight", window.getGuiScaledHeight());
        out.addProperty("guiScale", window.getGuiScale());
        JsonArray widgets = new JsonArray();
        if (screen != null) {
            ScreenInspector.elements(screen).forEach(e -> widgets.add(ScreenInspector.toJson(e)));
        }
        out.add("widgets", widgets);
        out.add("container", screen == null ? null : ScreenInspector.container(screen));
        return out;
    }

    // ---- mouse ----

    private static JsonElement click(double guiX, double guiY, int sdlButton, int mods) {
        Minecraft mc = ClientThread.mc();
        Screen before = mc.gui.screen();
        Window window = mc.getWindow();
        // MouseHandler stores window coordinates and scales them down to GUI coordinates itself.
        double rawX = guiX * window.getScreenWidth() / window.getGuiScaledWidth();
        double rawY = guiY * window.getScreenHeight() / window.getGuiScaledHeight();
        mc.mouseHandler.onMove(window.handle(), rawX, rawY, 0, 0);
        MouseButtonInfo info = new MouseButtonInfo(sdlButton, mods);
        mc.mouseHandler.onButton(window.handle(), info, InputConstants.PRESS);
        mc.mouseHandler.onButton(window.handle(), info, InputConstants.RELEASE);
        return screenChange(before, mc.gui.screen());
    }

    /** Moves the mouse without clicking, e.g. so a screenshot shows a slot's tooltip. */
    private static JsonElement hover(Integer slot, Double guiX, Double guiY) {
        Minecraft mc = ClientThread.mc();
        double x, y;
        JsonObject out = new JsonObject();
        if (slot != null) {
            if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
                throw new RpcException("No container screen open (current: " + describe(mc.gui.screen()) + ")");
            }
            AbstractContainerMenu menu = screen.getMenu();
            if (slot < 0 || slot >= menu.slots.size()) {
                throw new RpcException("Slot index " + slot + " out of range 0.." + (menu.slots.size() - 1));
            }
            AbstractContainerScreenAccessor acc = (AbstractContainerScreenAccessor) screen;
            // Slot positions are the top-left of the 16x16 item area.
            x = acc.mctest$getLeftPos() + menu.slots.get(slot).x + 8;
            y = acc.mctest$getTopPos() + menu.slots.get(slot).y + 8;
            out.add("item", ScreenInspector.stack(menu.slots.get(slot).getItem()));
        } else {
            x = guiX;
            y = guiY;
        }
        Window window = mc.getWindow();
        mc.mouseHandler.onMove(window.handle(), x * window.getScreenWidth() / window.getGuiScaledWidth(),
                y * window.getScreenHeight() / window.getGuiScaledHeight(), 0, 0);
        out.addProperty("x", x);
        out.addProperty("y", y);
        return out;
    }

    private static JsonElement clickWidget(String id, int sdlButton, int mods) {
        Screen screen = requireScreen();
        ScreenInspector.Element e = ScreenInspector.find(screen, id);
        if (e == null) {
            throw new RpcException("No widget '" + id + "' on " + screen.getClass().getSimpleName() + ". Take a fresh snapshot.");
        }
        if (!e.visible()) {
            throw new RpcException("Widget '" + e.id() + "' is not visible (hidden or scrolled out of view)");
        }
        if (!e.active()) {
            throw new RpcException("Widget '" + e.id() + "' is not active");
        }
        JsonObject out = click(e.centerX(), e.centerY(), sdlButton, mods).getAsJsonObject();
        out.addProperty("clicked", e.id());
        return out;
    }

    private static JsonObject screenChange(Screen before, Screen after) {
        JsonObject out = new JsonObject();
        out.add("screenBefore", StatusMethods.describeScreen(before));
        out.add("screenAfter", StatusMethods.describeScreen(after));
        out.addProperty("screenChanged", before != after);
        return out;
    }

    // ---- container ----

    private static JsonElement clickSlot(int index, int button, ContainerInput input) {
        Minecraft mc = ClientThread.mc();
        if (!(mc.gui.screen() instanceof AbstractContainerScreen<?> screen)) {
            throw new RpcException("No container screen open (current: " + describe(mc.gui.screen()) + ")");
        }
        AbstractContainerMenu menu = screen.getMenu();
        if (index != AbstractContainerMenu.SLOT_CLICKED_OUTSIDE && (index < 0 || index >= menu.slots.size())) {
            throw new RpcException("Slot index " + index + " out of range 0.." + (menu.slots.size() - 1) + " (or -999 for outside)");
        }
        mc.gameMode.handleContainerInput(menu.containerId, index, button, input, mc.player);

        // The client predicts the result immediately; the server confirms a tick or two later.
        JsonObject out = new JsonObject();
        if (index >= 0) {
            out.add("slot", ScreenInspector.stack(menu.slots.get(index).getItem()));
        }
        out.add("carried", ScreenInspector.stack(menu.getCarried()));
        return out;
    }

    private static ContainerInput containerInput(String name) {
        String normalized = name.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        try {
            return ContainerInput.valueOf(normalized);
        } catch (IllegalArgumentException e) {
            throw new RpcException("Unknown clickType '" + name + "'. Use one of PICKUP, QUICK_MOVE, SWAP, CLONE, THROW, QUICK_CRAFT, PICKUP_ALL");
        }
    }

    // ---- keyboard ----

    private static JsonElement key(int scancode, int mods, String action) {
        Minecraft mc = ClientThread.mc();
        Screen before = mc.gui.screen();
        long handle = mc.getWindow().handle();
        int keycode = SDLKeyboard.SDL_GetKeyFromScancode(scancode, (short) mods, false);
        KeyEvent event = new KeyEvent(scancode, keycode, mods);
        // "press" keeps the key down (for hasShiftDown()-style polling) until a matching "release".
        HeldKeys.withModifiers(mods, () -> {
            if (!action.equals("release")) {
                HeldKeys.press(scancode);
                mc.keyboardHandler.keyPress(handle, InputConstants.PRESS, event);
            }
            if (!action.equals("press")) {
                HeldKeys.release(scancode);
                mc.keyboardHandler.keyPress(handle, InputConstants.RELEASE, event);
            }
            return null;
        });
        JsonObject out = screenChange(before, mc.gui.screen());
        out.addProperty("key", InputConstants.Type.KEYBOARD.getOrCreate(scancode).getName());
        out.add("heldKeys", heldKeys());
        // What polling code (e.g. "hold shift for details" tooltips) sees now.
        JsonObject down = new JsonObject();
        down.addProperty("shift", mc.hasShiftDown());
        down.addProperty("ctrl", mc.hasControlDown());
        down.addProperty("alt", mc.hasAltDown());
        out.add("modifiersDown", down);
        return out;
    }

    /** Names of the keys MCTest is currently holding down. */
    public static JsonArray heldKeys() {
        JsonArray arr = new JsonArray();
        HeldKeys.held().stream().sorted().forEach(k -> arr.add(InputConstants.Type.KEYBOARD.getOrCreate(k).getName()));
        return arr;
    }

    private static JsonElement type(String text) {
        Minecraft mc = ClientThread.mc();
        requireScreen();
        mc.keyboardHandler.textInput(mc.getWindow().handle(), text);
        JsonObject out = new JsonObject();
        out.addProperty("typed", text.codePointCount(0, text.length()));
        return out;
    }

    private static JsonElement closeScreen() {
        Minecraft mc = ClientThread.mc();
        Screen before = mc.gui.screen();
        if (before != null) {
            before.onClose();
        }
        return screenChange(before, mc.gui.screen());
    }

    // ---- param parsing ----

    /** Accepts names (left/right/middle) or web-style numbers (0 = left, 1 = right, 2 = middle); returns SDL buttons. */
    private static int mouseButton(JsonObject params) {
        if (!Params.has(params, "button")) {
            return InputConstants.MOUSE_BUTTON_LEFT;
        }
        String b = Params.string(params, "button").toLowerCase(Locale.ROOT);
        return switch (b) {
            case "left", "0" -> InputConstants.MOUSE_BUTTON_LEFT;
            case "right", "1" -> InputConstants.MOUSE_BUTTON_RIGHT;
            case "middle", "2" -> InputConstants.MOUSE_BUTTON_MIDDLE;
            default -> throw new RpcException("button must be left, right, middle (or 0, 1, 2)");
        };
    }

    /** Accepts an array/comma list of shift/ctrl/alt/super, or a raw SDL modifier bitmask. */
    public static int modifiers(JsonObject params) {
        JsonElement m = params.get("modifiers");
        if (m == null || m.isJsonNull()) {
            return 0;
        }
        if (m.isJsonPrimitive() && m.getAsJsonPrimitive().isNumber()) {
            return m.getAsInt();
        }
        Iterable<JsonElement> names;
        if (m.isJsonArray()) {
            names = m.getAsJsonArray();
        } else {
            JsonArray split = new JsonArray();
            for (String s : m.getAsString().split("[,+ ]+")) {
                if (!s.isEmpty()) split.add(s);
            }
            names = split;
        }
        int mods = 0;
        for (JsonElement n : names) {
            mods |= switch (n.getAsString().toLowerCase(Locale.ROOT)) {
                case "shift" -> InputConstants.MOD_SHIFT;
                case "ctrl", "control" -> InputConstants.MOD_CONTROL;
                case "alt" -> InputConstants.MOD_ALT;
                case "super", "meta", "cmd", "win" -> InputConstants.MOD_SUPER;
                default -> throw new RpcException("Unknown modifier '" + n.getAsString() + "'. Use shift, ctrl, alt, super");
            };
        }
        return mods;
    }

    /** Resolves a key name ("e", "escape", "left_shift", "key.keyboard.f3") or a raw SDL scancode number. */
    private static int scancode(JsonElement key) {
        if (key == null || key.isJsonNull()) {
            throw new RpcException("Missing required param 'key'");
        }
        if (key.isJsonPrimitive() && key.getAsJsonPrimitive().isNumber()) {
            return key.getAsInt();
        }
        String raw = key.getAsString();
        String name = raw.length() == 1 ? raw.toLowerCase(Locale.ROOT) : raw.trim().toLowerCase(Locale.ROOT);
        name = KEY_ALIASES.getOrDefault(name, name);
        if (!name.startsWith("key.keyboard.")) {
            name = "key.keyboard." + (name.length() == 1 ? name : name.replace('_', '.').replace(' ', '.'));
        }
        InputConstants.Key k;
        try {
            k = InputConstants.getKey(name);
        } catch (RuntimeException e) {
            k = InputConstants.UNKNOWN;
        }
        if (k == InputConstants.UNKNOWN || k.getType() != InputConstants.Type.KEYBOARD) {
            throw new RpcException("Unknown key '" + raw + "'. Use names like e, escape, enter, left_shift, f3, up, or an SDL scancode");
        }
        return k.getValue();
    }

    private static Screen requireScreen() {
        Screen screen = ClientThread.mc().gui.screen();
        if (screen == null) {
            throw new RpcException("No screen is open");
        }
        return screen;
    }

    private static String describe(Screen screen) {
        return screen == null ? "none" : screen.getClass().getSimpleName();
    }
}
