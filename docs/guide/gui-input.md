# GUIs, keys and tooltips

All input goes through vanilla's own `MouseHandler` and `KeyboardHandler`, so screens, widgets and keybinds (yours and
other mods') react as they do to a real player.

## Reading a screen

`mc_snapshot` lists the open screen's class and title, its widgets and, for container screens, every slot:

- **Widgets** have a stable id like `3:Button:Done` (index, class, text), plus type, text, position and size, and
  whether they are active, focused and visible.
- **Slots** have their *menu* index, item, count, components and position, and the snapshot includes the carried stack.

Coordinates are **GUI-scaled**, the same space the screen's own code uses.

## Clicking

- `mc_click_widget {id}` clicks a widget's centre. Prefer it over raw coordinates: it fails clearly if the widget is
  missing, hidden or inactive.
- `mc_click {x, y, button?, modifiers?}` clicks anywhere.
- `mc_click_slot {index, button?, clickType?}` is a container click by menu slot index. `clickType` is one of
  `PICKUP` (default), `QUICK_MOVE` (shift-click), `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`.
- `mc_hover {slot}` or `mc_hover {x, y}` moves the mouse without clicking, so a screenshot shows the hovered slot's
  tooltip or a widget's hover state.
- `mc_scroll {amount, x?, y?}` scrolls lists, or the hotbar in-world.

## Keys and text

- `mc_key {key}` taps a key: `e`, `escape`, `enter`, `tab`, `f3`, `left_shift`, `up`, … Add `modifiers` for
  shortcuts, such as `{key: "a", modifiers: ["ctrl"]}`.
- `mc_type {text}` types into the focused text field.
- `mc_keybind {name, action, ticks?}` drives any key mapping by name, including other mods' keybinds, and can hold it
  for a number of ticks. Walking is holding `forward`. `mc_keybinds` lists them all.

::: warning Keybind actions apply on the next tick
`mc_key {key: "e"}` returns before the inventory opens. Follow it with
`mc_wait_for {condition: "screen:InventoryScreen"}`.
:::

## Holding Shift, Ctrl or Alt

Many mods show extra lines only while a modifier is held ("Hold Shift for details"), or change behaviour while it is.
They check with `Minecraft.hasShiftDown()` / `hasControlDown()` / `hasAltDown()`, which read the real keyboard state.
MCTest makes the keys it holds count as down for those checks too.

**Just the tooltip text:**

```json
mc_tooltip {"slot": 0, "modifiers": ["shift"]}
```

Shift is held only while the tooltip is built. `slot` is the menu slot index when a container screen is open, otherwise
a player inventory index. `advanced: true` adds the F3+H lines.

**The rendered tooltip in a screenshot**, or any behaviour that checks the key over time:

```
mc_key {key:"left_shift", action:"press"}     → held until released
mc_hover {slot: 36}                           → the mouse is over the slot, no click
mc_screenshot
mc_key {key:"left_shift", action:"release"}
```

The `mc_key` result lists `heldKeys` and what `hasShiftDown()`, `hasControlDown()` and `hasAltDown()` currently return.
Keys stay held until you release them. Modifiers passed to `mc_click`, `mc_click_widget` and `mc_key` are held only for
that one action.
