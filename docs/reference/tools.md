# Client tools

Each MCP tool `mc_*` wraps one method of the mod's WebSocket API (the Method column), so scripts can call the
same thing over the [raw protocol](/reference/protocol). Coordinates in GUI tools are **GUI-scaled**, as
`mc_snapshot` reports them. 20 ticks = 1 second.

<!-- Generated from the tool table in the repository README. -->

## Lifecycle and worlds

| Tool | Method | What it does |
|---|---|---|
| `mc_status` | `status` | State (`starting`/`loading`/`title`/`joining`/`in-world`), `ready`, screen, MC + loader versions, mod list |
| `mc_wait_ready` | (polls `status`) | Wait until the client is up and accepting input; tolerates the game not running yet |
| `mc_world_create` | `world.create {name,seed?,flat?,gamemode?,difficulty?,cheats?,…}` | New world, waits until in-world (creative + cheats by default) |
| `mc_world_join` | `world.join {name,confirmPrompts?}` | Open an existing world by folder id or display name. Answers the "experimental settings / backup" prompt (any world with datapack registry content gets it) unless `confirmPrompts:false` |
| `mc_world_leave` | `world.leave` | Save and quit to title, or disconnect from a server |
| `mc_world_list` | `world.list` | Worlds in the saves folder |
| `mc_world_delete` | `world.delete {name}` | Delete a world (not the open one) |
| `mc_connect` | `connect {address?}` | Join a multiplayer server and wait until in-world; fails with the disconnect reason |
| `mc_quit` | `quit` | Quit the game cleanly (saves first), e.g. to restart after rebuilding your mod |
| `mc_crash_report` | (reads files) | Newest crash report or JVM `hs_err` log of the client or server, also after the game has died |

## Observing

| Tool | Method | What it does |
|---|---|---|
| `mc_screenshot` | `screenshot {name?,maxSize?}` | Saves a full-size PNG to `<gameDir>/mctest/screenshots/`; returns the path and the image scaled to fit `maxSize` (default 1280) as JPEG, so large windows arrive intact |
| `mc_window_resize` | `window.resize {width,height}` | Resize the game window (leaves fullscreen); returns size and GUI scale |
| `mc_snapshot` | `snapshot` | Screen class/title, widgets (`index:Class:text` id, type, text, x/y/w/h, active, focused, visible, values), container slots (menu index, item, count, components, x/y), carried stack |
| `mc_player` | `player` | Position, rotation, dimension, gamemode, health, food, xp, crosshair target, inventory, effects, attributes |
| `mc_block` | `block {x,y,z,source?}` | Block id, state properties, block-entity NBT (from the integrated server in singleplayer) |
| `mc_entities` | `entities {radius?,type?,limit?}` | Nearby entities with ids, types, names, health, item stacks |
| `mc_scan_blocks` | `scanBlocks {radius?, blocks?}` | Block types around you with counts and nearest positions (ids or `#tags`) |
| `mc_tooltip` | `tooltip {slot,advanced?,modifiers?}` | Tooltip lines of an item (menu slot if a container is open, else inventory slot); `modifiers: ["shift"]` for hold-shift/ctrl tooltips |
| `mc_logs` | `logs {since?,level?,contains?,limit?}` | Captured log lines with exception stacks, paged by sequence number |
| `mc_keybinds` | `keybinds` | List all key mappings |

## GUI input

| Tool | Method | What it does |
|---|---|---|
| `mc_click` | `click {x,y,button?,modifiers?}` | Click at GUI coordinates through the real mouse handler |
| `mc_click_widget` | `clickWidget {id}` | Click a widget's centre. Old ids still work if `Class:text` is unique |
| `mc_click_slot` | `clickSlot {index,button?,clickType?}` | Container click by menu slot index (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`) |
| `mc_hover` | `hover {slot \| x,y}` | Move the mouse without clicking (slot tooltips in screenshots) |
| `mc_key` | `key {key,modifiers?,action?}` | Keyboard key through the real handler (`e`, `escape`, `enter`, `f3`, `left_shift`, …); `press` holds it (seen by `hasShiftDown()`) until `release` |
| `mc_type` | `type {text}` | Type into the focused text field |
| `mc_scroll` | `scroll {amount,x?,y?}` | Mouse wheel (lists, or hotbar in-world) |
| `mc_close_screen` | `closeScreen` | Close the current screen |

## Gameplay

| Tool | Method | What it does |
|---|---|---|
| `mc_command` | `command {command,ticks?}` | Run a command, return the chat output from the next few ticks |
| `mc_chat` | `chat {message}` | Send a chat message |
| `mc_teleport` | `teleport {x,y,z,yaw?,pitch?}` | `/tp` and wait until arrived |
| `mc_look_at` / `mc_look` | `lookAt {x,y,z}` / `look {yaw,pitch}` | Aim the player |
| `mc_keybind` | `keybind {name,action,ticks?}` | Drive any key mapping (including other mods' keybinds): tap/press/release/hold. Walking = hold `forward` |
| `mc_select_slot` | `selectSlot {slot}` | Select hotbar slot 0–8 |
| `mc_select_item` | `selectItem {item}` | Hold an item (swaps it into the hotbar if needed) |
| `mc_use_on` | `useOn {x,y,z,face?,hand?}` | Right-click a block face: place blocks, open GUIs, use tools |
| `mc_use_item` | `useItem {hand?,ticks?}` | Right-click the held item; `ticks` keeps using it (eat, drink, draw a bow) |
| `mc_break_block` | `breakBlock {x,y,z,face?,timeoutTicks?}` | Mine with real dig time (instant in creative) |
| `mc_attack_entity` | `attackEntity {id}` | Attack an entity |
| `mc_interact_entity` | `interactEntity {id,hand?}` | Right-click an entity (trade, milk, ride, mod entities) |
| `mc_reload_resources` | `reloadResources` | Reload resource packs (F3+T) after editing assets |

## Player skills

| Tool | Method | What it does |
|---|---|---|
| `mc_goto` | `goto {x,y,z \| entity, range?, sprint?}` | Walk there like a player: A* pathfinding, gradual turning, sprinting, jumping, drops, swimming, replanning |
| `mc_collect` | `collect {block, count, radius?}` | Walk to visible matching blocks, use the best tool, mine with real dig time, pick up the drops; counts only what reached the inventory |
| `mc_dig` | `dig {mode, length, direction?, until?}` | Staircase down (`stairs`), staircase up (`up`), or 1×2 `tunnel`; stops at lava/water, your own blocks, or the `until` block |
| `mc_place` | `place {item, x?,y?,z?}` | Place a block against any solid face; picks a sensible nearby spot if no position is given |
| `mc_fight` | `fight {id \| type, fleeHealth?}` | Equip a weapon, close in, aim, attack only at full cooldown |
| `mc_craft` | `craft {item, times?}` | Recipe-book crafting: 2×2 in the inventory, 3×3 by walking to and opening a nearby crafting table |

## Time

| Tool | Method | What it does |
|---|---|---|
| `mc_time_freeze` / `mc_time_unfreeze` | `time.freeze` / `time.unfreeze` | Freeze the world (like `/tick freeze`, no cheats needed); the player can still act |
| `mc_time_step` | `time.step {ticks}` | Advance a frozen world by exactly N ticks |
| `mc_lockstep` | `time.lockstep {enabled}` | The world only runs while an MCTest action is in progress, so nothing happens while the agent is thinking |
| `mc_time_status` | `time.status` | Frozen / lockstep state and game time |

## Waiting

| Tool | Method | What it does |
|---|---|---|
| `mc_wait_for` | `waitFor {condition,timeoutTicks?}` | `screen:<Class>`/`screen:none`, `slot:<i>=<item\|empty>`, `block:<x,y,z>=<id>`, `ticks:<n>`, `state:<state>`, `log:<text>`, `chat:<text>`, `entity:<type>[@radius]` |

## GameTests

| Tool | Method | What it does |
|---|---|---|
| `mc_gametest_list` | `gametest.list {filter?}` | GameTests registered in the singleplayer world (vanilla, your mod's, datapack test instances) |
| `mc_gametest_run` | `gametest.run {tests?,timeoutSeconds?}` | Run GameTests (`/test run <selector>`) and wait: pass/fail per test with the failure message and position, plus an overall `success` |

## Anything else

| Tool | Method | What it does |
|---|---|---|
| `mc_call` | any | Escape hatch: call a method by name with raw params |
