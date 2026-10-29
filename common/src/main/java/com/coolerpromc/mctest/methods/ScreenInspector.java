package com.coolerpromc.mctest.methods;

import com.coolerpromc.mctest.mixin.AbstractContainerScreenAccessor;
import com.coolerpromc.mctest.util.Describe;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import net.minecraft.client.gui.components.AbstractSelectionList;
import net.minecraft.client.gui.components.AbstractSliderButton;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Checkbox;
import net.minecraft.client.gui.components.CycleButton;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.components.ObjectSelectionList;
import net.minecraft.client.gui.components.events.ContainerEventHandler;
import net.minecraft.client.gui.components.events.GuiEventListener;
import net.minecraft.client.gui.layouts.LayoutElement;
import net.minecraft.client.gui.navigation.ScreenRectangle;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.Slot;
import net.minecraft.world.item.ItemStack;
import org.jspecify.annotations.Nullable;

import java.util.ArrayList;
import java.util.List;

/**
 * Walks the current screen's widget tree and container slots. Must run on the render thread.
 */
public final class ScreenInspector {

    private static final int ID_TEXT_LIMIT = 40;

    private ScreenInspector() {
    }

    /** One interactive element: a widget or a selection-list entry. Coordinates are GUI-scaled. */
    public record Element(String id, GuiEventListener listener, String type, String className, String text,
                          int x, int y, int w, int h, boolean active, boolean focused, boolean visible) {

        public double centerX() {
            return x + w / 2.0;
        }

        public double centerY() {
            return y + h / 2.0;
        }
    }

    public static List<Element> elements(Screen screen) {
        List<Element> out = new ArrayList<>();
        walk(screen, screen.getRectangle(), out);
        return out;
    }

    private static void walk(ContainerEventHandler parent, ScreenRectangle clip, List<Element> out) {
        boolean listEntries = parent instanceof AbstractSelectionList<?>;
        for (GuiEventListener child : parent.children()) {
            if (child instanceof LayoutElement layout) {
                out.add(element(out.size(), child, layout, clip, listEntries));
            }
            if (child instanceof ContainerEventHandler container) {
                // Lists scroll their entries; anything outside the list's viewport is not clickable.
                ScreenRectangle childClip = child instanceof AbstractSelectionList<?> ? child.getRectangle() : clip;
                walk(container, childClip, out);
            }
        }
    }

    private static Element element(int index, GuiEventListener listener, LayoutElement layout, ScreenRectangle clip, boolean listEntry) {
        String className = displayClassName(listener.getClass());
        String text = text(listener);
        boolean active = !(listener instanceof AbstractWidget w) || w.isActive();
        boolean visible = (!(listener instanceof AbstractWidget w) || w.visible)
                && layout.getWidth() > 0 && layout.getHeight() > 0
                && contains(clip, layout.getX() + layout.getWidth() / 2, layout.getY() + layout.getHeight() / 2);
        String id = index + ":" + className + ":" + idText(text);
        return new Element(id, listener, listEntry ? "entry" : type(listener), listener.getClass().getName(), text,
                layout.getX(), layout.getY(), layout.getWidth(), layout.getHeight(), active, listener.isFocused(), visible);
    }

    private static boolean contains(ScreenRectangle r, int x, int y) {
        return x >= r.left() && x < r.right() && y >= r.top() && y < r.bottom();
    }

    /** Anonymous/lambda classes have no simple name; use the nearest named superclass. */
    private static String displayClassName(Class<?> cls) {
        while (cls.isAnonymousClass() || cls.getSimpleName().isEmpty()) {
            cls = cls.getSuperclass();
        }
        return cls.getSimpleName();
    }

    private static String type(GuiEventListener l) {
        if (l instanceof EditBox) return "textbox";
        if (l instanceof Checkbox) return "checkbox";
        if (l instanceof CycleButton<?>) return "cycle";
        if (l instanceof AbstractSliderButton) return "slider";
        if (l instanceof AbstractSelectionList<?>) return "list";
        if (l instanceof net.minecraft.client.gui.components.Button) return "button";
        if (l instanceof ContainerEventHandler) return "container";
        return "widget";
    }

    private static String text(GuiEventListener l) {
        if (l instanceof EditBox box) {
            return box.getValue();
        }
        if (l instanceof AbstractWidget w) {
            return w.getMessage().getString();
        }
        if (l instanceof ObjectSelectionList.Entry<?> entry) {
            return entry.getNarration().getString();
        }
        return "";
    }

    private static String idText(String text) {
        String t = text.replaceAll("\\s+", " ").trim();
        return t.length() > ID_TEXT_LIMIT ? t.substring(0, ID_TEXT_LIMIT) : t;
    }

    /**
     * Finds an element by exact id. If the index has shifted (the screen rebuilt its widgets), falls back to a unique
     * match on the {@code Class:text} part so callers can reuse ids from an earlier snapshot.
     */
    public static @Nullable Element find(Screen screen, String id) {
        List<Element> all = elements(screen);
        for (Element e : all) {
            if (e.id().equals(id)) {
                return e;
            }
        }
        String wanted = withoutIndex(id);
        List<Element> matches = all.stream().filter(e -> withoutIndex(e.id()).equals(wanted)).toList();
        return matches.size() == 1 ? matches.get(0) : null;
    }

    private static String withoutIndex(String id) {
        int sep = id.indexOf(':');
        return sep >= 0 && id.substring(0, sep).chars().allMatch(Character::isDigit) ? id.substring(sep + 1) : id;
    }

    public static JsonObject toJson(Element e) {
        JsonObject o = new JsonObject();
        o.addProperty("id", e.id());
        o.addProperty("type", e.type());
        o.addProperty("class", e.className());
        o.addProperty("text", e.text());
        o.addProperty("x", e.x());
        o.addProperty("y", e.y());
        o.addProperty("w", e.w());
        o.addProperty("h", e.h());
        o.addProperty("active", e.active());
        o.addProperty("focused", e.focused());
        o.addProperty("visible", e.visible());
        if (e.listener() instanceof Checkbox box) {
            o.addProperty("checked", box.selected());
        } else if (e.listener() instanceof CycleButton<?> cycle) {
            o.addProperty("value", String.valueOf(cycle.getValue()));
        } else if (e.listener() instanceof AbstractSelectionList<?> list) {
            o.addProperty("selected", list.getSelected() == null ? null : text((GuiEventListener) list.getSelected()));
        }
        return o;
    }

    // ---- containers ----

    public static @Nullable JsonObject container(Screen screen) {
        if (!(screen instanceof AbstractContainerScreen<?> containerScreen)) {
            return null;
        }
        AbstractContainerMenu menu = containerScreen.getMenu();
        AbstractContainerScreenAccessor accessor = (AbstractContainerScreenAccessor) containerScreen;
        int left = accessor.mctest$getLeftPos();
        int top = accessor.mctest$getTopPos();

        JsonObject out = new JsonObject();
        out.addProperty("menuClass", menu.getClass().getName());
        out.addProperty("containerId", menu.containerId);
        out.add("carried", stack(menu.getCarried()));
        JsonArray slots = new JsonArray();
        for (Slot slot : menu.slots) {
            JsonObject s = new JsonObject();
            s.addProperty("index", slot.index);
            s.addProperty("containerSlot", slot.getContainerSlot());
            s.addProperty("container", displayClassName(slot.container.getClass()));
            s.addProperty("x", left + slot.x);
            s.addProperty("y", top + slot.y);
            s.addProperty("active", slot.isActive());
            ItemStack item = slot.getItem();
            if (item.isEmpty()) {
                s.addProperty("item", (String) null);
                s.addProperty("count", 0);
            } else {
                s.addProperty("item", BuiltInRegistries.ITEM.getKey(item.getItem()).toString());
                s.addProperty("count", item.getCount());
                s.add("stack", stack(item));
            }
            slots.add(s);
        }
        out.add("slots", slots);
        return out;
    }

    /** Item id, count, display name, damage and the components that differ from the item's defaults. */
    public static @Nullable JsonObject stack(ItemStack item) {
        return Describe.stack(item);
    }
}
