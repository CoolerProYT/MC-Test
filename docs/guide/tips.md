# Things worth knowing

## Errors come to you

After every tool call the MCP server checks the game's log for new ERROR lines and appends them to the result, so an
exception your mod throws shows up right after the action that caused it. `mc_logs` has the full stacks. If the game
crashes, the next call fails with a hint to use `mc_crash_report`.

## Logs from before MCTest loads

On Fabric MCTest attaches in `preLaunch`, before any mod initializes. On NeoForge, lines logged before MCTest is
constructed are imported from `logs/latest.log` (flagged `backfilled: true`), so your mod's startup errors are visible
on both loaders.

## Screen class names

They are the real, unobfuscated ones (`InventoryScreen`, `CreativeModeInventoryScreen`, `CraftingScreen`, your mod's
screen class). `screen:` in `mc_wait_for` also matches superclasses, for example `screen:AbstractContainerScreen`.

## Two slot numberings

- Container slot numbers are **menu** indices from `mc_snapshot` (crafting table: 0 = result, 1–9 = grid,
  10–45 = inventory). `mc_click_slot`, `mc_hover` and `mc_tooltip` (with a container open) use these.
- `mc_player` inventory slots are **inventory** indices (0–8 hotbar, 9–35 main, 36+ armor/offhand). `mc_tooltip` uses
  these when no container is open.

## Keybind actions apply on the next tick

`mc_key {key: "e"}` returns before the inventory opens. Follow it with `mc_wait_for {condition: "screen:InventoryScreen"}`.

## Input is GLFW in 26.1

Raw key numbers for `mc_key` are GLFW key codes (26.3 and newer use SDL scancodes). Use names (`e`, `escape`, `left_shift`, `f3`).

## Pause on lost focus is off

The game window is usually not focused while an agent drives it, and vanilla would otherwise open the pause menu every
time a screen closes. MCTest turns `pauseOnLostFocus` off in memory. Launch with `-Dmctest.pauseOnLostFocus=true` to
keep vanilla behaviour.

## Your mouse is left alone

Vanilla captures the OS cursor whenever a screen closes in-world and warps it to the window centre whenever one opens,
so an agent opening and closing screens would keep trapping or yanking your mouse while you work in another app. With
MCTest, the game only captures or moves the cursor within 2 seconds of *your own* click or key press in its window;
MCTest's synthetic input never counts. Click into the game to play by hand as usual. `mc_status` reports
`windowFocused` and `mouseGrabbed`. Use `-Dmctest.mouseWarp=true` for vanilla behaviour.

## Tutorial hints are skipped

Toasts like "Look around" would otherwise cover screenshots. Use `-Dmctest.tutorial=true` to keep them.
