# MCTest

A **dev-only** Minecraft client mod (Fabric + NeoForge, Minecraft 1.21.1, 26.1.2, 26.2 and 26.3) plus an **MCP server**, so Claude Code (or any
coding agent, or your own scripts) can drive a running Minecraft client the way Playwright drives a browser: open
worlds, click GUIs, press keys, walk, mine, place, craft, fight, run commands, inspect blocks, entities and inventories,
read logs and take screenshots. The main use is testing your own mods.

**Documentation: https://mctest.coolerpromc.com/**

```
Claude Code ──stdio/MCP──▶ mcp/ (Node) ──WebSocket JSON-RPC──▶ MCTest mod in your dev client (127.0.0.1:25599)
```

- **Loopback only.** The control servers bind to `127.0.0.1`. On the client, MCTest drives the game; on a dedicated
  server (optional, see [Testing on a dedicated server](#testing-on-a-dedicated-server)) it exposes the server side.
- **Never ship it.** Add it to your mod as a dev-runtime dependency (see below). It is not bundled into your jar or
  published POM.
- Everything runs through vanilla code paths: input goes through Minecraft's own mouse/keyboard handlers and key
  mappings, and interactions go through `MultiPlayerGameMode`, so the server validates them as for a real player.

---

## 1. Use MCTest in your mod

### Where to get it

MCTest is published to `https://maven.coolerpromc.com/releases/` as
`com.coolerpromc.mctest:mctest-<loader>-<minecraft version>:<version>`, for Minecraft 1.21.1, 26.1.2, 26.2 and 26.3.
The snippets below use that repository. If you're changing MCTest itself, run `./gradlew publishToMavenLocal` and use
`mavenLocal()` instead to pick up your local build.

Latest releases, updated automatically from the Maven repository. Pick the row for your Minecraft version:

<!-- mctest-versions:start -->
| Minecraft | Fabric | NeoForge |
|---|---|---|
| 26.3 | `mctest-fabric-26.3:26.3.0.3` | `mctest-neoforge-26.3:26.3.0.3` |
| 26.2 | `mctest-fabric-26.2:26.2.0.1` | `mctest-neoforge-26.2:26.2.0.1` |
| 26.1.2 | `mctest-fabric-26.1.2:26.1.2.1` | `mctest-neoforge-26.1.2:26.1.2.1` |
<!-- mctest-versions:end -->

The snippets below are for 26.3. For another Minecraft version, replace the artifact and version with the
ones from its row (for example `mctest-fabric-26.1.2:26.1.2.1`).

### Fabric (Loom), in your mod's `fabric/build.gradle`

```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

dependencies {
    // Dev-only: on the runClient classpath, never in your jar or POM.
    localRuntime("com.coolerpromc.mctest:mctest-fabric-26.3:26.3.0.3") { transitive = false }
}
```

`localRuntime` is Loom's dev-runtime configuration. `transitive = false` stops MCTest's own `fabric-api` /
`fabric-loader` runtime dependencies from overriding your versions. On 1.21.1, depend on it with `modLocalRuntime` instead of `localRuntime`: 1.21.1 is obfuscated, so Loom has to remap MCTest.

### NeoForge (ModDevGradle), in your mod's `neoforge/build.gradle`

ModDevGradle has no built-in `localRuntime`, so declare one:

```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

// Runtime-only in dev, never published.
configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    localRuntime("com.coolerpromc.mctest:mctest-neoforge-26.3:26.3.0.3") { transitive = false }
}
```

Both setups were verified with a MultiLoader-template mod: MCTest loads in `runClient` on both loaders and does not
appear in the built jar or the generated POM.

### Changing the port (optional)

Default is `25599`. To run several clients at once, or avoid a clash, set a system property on the client run:

```groovy
// Fabric: loom { runs { client { vmArg('-Dmctest.port=25600') } } }
// NeoForge: neoForge { runs { client { systemProperty('mctest.port', '25600') } } }
```

Then give the MCP server the same port (`MCTEST_PORT`, below).

---

## 2. Set up the MCP server

Requires Node 22+. The server is published to npm as [`mctest-mcp`](https://www.npmjs.com/package/mctest-mcp), so
there's nothing to clone or build. Register it with Claude Code once, for all projects:

```bash
claude mcp add mctest --scope user -- npx -y mctest-mcp
```

For a client on a different port:

```bash
claude mcp add mctest --scope user -e MCTEST_PORT=25600 -- npx -y mctest-mcp
```

To share it with everyone working on a mod, commit a `.mcp.json` in that mod's repository:

```json
{
  "mcpServers": {
    "mctest": { "command": "npx", "args": ["-y", "mctest-mcp"] }
  }
}
```

The server connects lazily and reconnects after the game restarts, so it can be registered once and left running.
Start your client with `./gradlew :fabric:runClient` (or `:neoforge:runClient`), or let the agent start it with
`mc_launch`; the agent then calls `mc_wait_ready`. When it has finished testing it calls `mc_close_all`, which closes
every client and server; games started with `mc_launch` / `mc_server_launch` are also closed when the MCP session ends.

To run the server from this checkout instead (when changing it): `cd mcp && npm install`, then register
`node <path-to-checkout>/mcp/dist/index.js`. Publish a new version with `npm version patch && npm publish` from `mcp/`.

### Testing on a dedicated server

Multiplayer bugs (missing packets, client/server desync, code that only works in singleplayer because both sides share
a JVM) only show up against a real dedicated server. MCTest runs on the server too: the same `localRuntime` dependency
puts it on `runServer`, where it opens a second control API on `ws://127.0.0.1:25598/` (`-Dmctest.serverPort=...`)
that the `mc_server_*` tools talk to.

```bash
./gradlew :fabric:runServer     # or :neoforge:runServer (first run: accept the EULA in runs/server/eula.txt)
./gradlew :fabric:runClient     # in a second terminal
```

Then the agent calls `mc_server_wait_ready`, `mc_wait_ready` and `mc_connect` (default `localhost`). From there client
tools drive the player as usual, and the server tools show and change the authoritative side:

```
mc_server_command "setblock 10 64 10 mymod:grinder" → mc_connect → mc_use_on (open it) → mc_click_slot …
→ mc_server_block {x:10,y:64,z:10}  (server NBT)  vs  mc_block {…, source:"client"}  (what the client was sent)
→ mc_server_wait_for "log:Grinder finished" → mc_server_logs {level:"WARN"}
```

In a development environment MCTest makes a dev server usable by dev clients:

- **Offline mode.** Dev clients have no Mojang session, so the server skips authentication and does not require signed
  chat, whatever `server.properties` says. Opt out with `-Dmctest.onlineMode=true`.
- **No whitelist.** New 26.x servers whitelist by default; MCTest turns it off at startup (as `/whitelist off` would).
  `/whitelist on` still works afterwards. Keep it with `-Dmctest.whitelist=true`.
- **Auto-op.** Players are opped when they join, so `mc_command` works from the client. A test that deops a player is
  respected until they rejoin. Turn off with `-Dmctest.autoOp=false` (for permission tests).

The server API has no singleplayer equivalent and is not started in the integrated server: in singleplayer the client
API already reads the integrated server directly (`mc_block`, `mc_time_*`).

### A typical agent session

> Start the NeoForge client in the background, wait until it's ready, create a flat creative test world, give me a
> `mymod:grinder`, place it, put 8 iron ore in it, wait 200 ticks, and check that it output iron dust. Screenshot the GUI.

which turns into calls like:

```
mc_wait_ready → mc_world_create {name:"grinder-test", flat:true}
→ mc_command "give @s mymod:grinder" → mc_player (find the slot) → mc_select_slot
→ mc_use_on {x,y,z, face:"up"} (place) → mc_use_on (open GUI) → mc_wait_for "screen:GrinderScreen"
→ mc_snapshot → mc_click_slot … → mc_wait_for "ticks:200" → mc_block (block-entity NBT) / mc_snapshot
→ mc_screenshot → mc_logs {level:"WARN"} (anything go wrong?)
```

---

## 3. Tools / methods

Each MCP tool `mc_*` wraps one method of the mod's WebSocket API (shown in brackets). Coordinates in GUI methods are
**GUI-scaled** (what `mc_snapshot` reports). 20 ticks = 1 second.

| Tool | Method | What it does |
|---|---|---|
| `mc_status` | `status` | State (`starting`/`loading`/`title`/`joining`/`in-world`), `ready`, screen, MC + loader versions, mod list |
| `mc_wait_ready` | (polls `status`) | Wait until the client is up and accepting input; tolerates the game not running yet |
| `mc_screenshot` | `screenshot {name?,maxSize?}` | Saves a full-size PNG to `<gameDir>/mctest/screenshots/`; returns the path and the image scaled to fit `maxSize` (default 1280) as JPEG, so large windows arrive intact |
| `mc_window_resize` | `window.resize {width,height}` | Resize the game window (leaves fullscreen); returns size and GUI scale |
| `mc_snapshot` | `snapshot` | Screen class/title, widgets (`index:Class:text` id, type, text, x/y/w/h, active, focused, visible, values), container slots (menu index, item, count, components, x/y), carried stack |
| `mc_click` | `click {x,y,button?,modifiers?}` | Click at GUI coordinates through the real mouse handler |
| `mc_click_widget` | `clickWidget {id}` | Click a widget's centre. Old ids still work if `Class:text` is unique |
| `mc_hover` | `hover {slot \| x,y}` | Move the mouse without clicking (slot tooltips in screenshots) |
| `mc_click_slot` | `clickSlot {index,button?,clickType?}` | Container click by menu slot index (`PICKUP`, `QUICK_MOVE`, `SWAP`, `CLONE`, `THROW`, `QUICK_CRAFT`, `PICKUP_ALL`) |
| `mc_key` | `key {key,modifiers?,action?}` | Keyboard key through the real handler (`e`, `escape`, `enter`, `f3`, `left_shift`, …); `press` holds it (seen by `hasShiftDown()`) until `release` |
| `mc_type` | `type {text}` | Type into the focused text field |
| `mc_scroll` | `scroll {amount,x?,y?}` | Mouse wheel (lists, or hotbar in-world) |
| `mc_close_screen` | `closeScreen` | Close the current screen |
| `mc_command` | `command {command,ticks?}` | Run a command, return the chat output from the next few ticks |
| `mc_chat` | `chat {message}` | Send a chat message |
| `mc_player` | `player` | Position, rotation, dimension, gamemode, health, food, xp, crosshair target, inventory, effects, attributes |
| `mc_block` | `block {x,y,z,source?}` | Block id, state properties, block-entity NBT (from the integrated server in singleplayer) |
| `mc_entities` | `entities {radius?,type?,limit?}` | Nearby entities with ids, types, names, health, item stacks |
| `mc_teleport` | `teleport {x,y,z,yaw?,pitch?}` | `/tp` and wait until arrived |
| `mc_look_at` / `mc_look` | `lookAt {x,y,z}` / `look {yaw,pitch}` | Aim the player |
| `mc_keybind` | `keybind {name,action,ticks?}` | Drive any key mapping (including other mods' keybinds): tap/press/release/hold. Walking = hold `forward` |
| `mc_keybinds` | `keybinds` | List all key mappings |
| `mc_select_slot` | `selectSlot {slot}` | Select hotbar slot 0–8 |
| `mc_use_on` | `useOn {x,y,z,face?,hand?}` | Right-click a block face: place blocks, open GUIs, use tools |
| `mc_use_item` | `useItem {hand?,ticks?}` | Right-click the held item; `ticks` keeps using it (eat, drink, draw a bow) |
| `mc_break_block` | `breakBlock {x,y,z,face?,timeoutTicks?}` | Mine with real dig time (instant in creative) |
| `mc_attack_entity` | `attackEntity {id}` | Attack an entity |
| `mc_interact_entity` | `interactEntity {id,hand?}` | Right-click an entity (trade, milk, ride, mod entities) |
| `mc_tooltip` | `tooltip {slot,advanced?,modifiers?}` | Tooltip lines of an item (menu slot if a container is open, else inventory slot); `modifiers: ["shift"]` for hold-shift/ctrl tooltips |
| `mc_wait_for` | `waitFor {condition,timeoutTicks?}` | `screen:<Class>`/`screen:none`, `slot:<i>=<item\|empty>`, `block:<x,y,z>=<id>`, `ticks:<n>`, `state:<state>`, `log:<text>`, `chat:<text>`, `entity:<type>[@radius]` |
| `mc_logs` | `logs {since?,level?,contains?,limit?}` | Captured log lines with exception stacks, paged by sequence number |
| `mc_reload_resources` | `reloadResources` | Reload resource packs (F3+T) after editing assets |
| `mc_world_create` | `world.create {name,seed?,flat?,gamemode?,difficulty?,cheats?,…}` | New world, waits until in-world (creative + cheats by default) |
| `mc_world_join` | `world.join {name,confirmPrompts?}` | Open an existing world by folder id or display name. Answers the "experimental settings / backup" prompt (any world with datapack registry content gets it) unless `confirmPrompts:false` |
| `mc_world_leave` | `world.leave` | Save and quit to title, or disconnect from a server |
| `mc_connect` | `connect {address?}` | Join a multiplayer server and wait until in-world; fails with the disconnect reason |
| `mc_quit` | `quit` | Quit the game cleanly (saves first), e.g. to restart after rebuilding your mod |
| `mc_launch` | (runs gradle) | Start `./gradlew :<loader>:runClient` and track it; then `mc_wait_ready` |
| `mc_close_all` | `quit` / `stop`, then kill | Close every client and dedicated server (saves first, kills any that hang). Call it when testing is done; games launched by the MCP server are also closed when its session ends |
| `mc_crash_report` | (reads files) | Newest crash report or JVM `hs_err` log of the client or server, also after the game has died |
| `mc_gametest_list` | `gametest.list {filter?}` | GameTests registered in the singleplayer world (vanilla, your mod's, datapack test instances) |
| `mc_gametest_run` | `gametest.run {tests?,timeoutSeconds?}` | Run GameTests (`/test run <selector>`) and wait: pass/fail per test with the failure message and position, plus an overall `success` |
| `mc_world_list` | `world.list` | Worlds in the saves folder |
| `mc_world_delete` | `world.delete {name}` | Delete a world (not the open one) |
| `mc_time_freeze` / `mc_time_unfreeze` | `time.freeze` / `time.unfreeze` | Freeze the world (like `/tick freeze`, no cheats needed); the player can still act |
| `mc_time_step` | `time.step {ticks}` | Advance a frozen world by exactly N ticks |
| `mc_lockstep` | `time.lockstep {enabled}` | The world only runs while an MCTest action is in progress, so nothing happens while the agent is thinking |
| `mc_time_status` | `time.status` | Frozen / lockstep state and game time |
| `mc_goto` | `goto {x,y,z \| entity, range?, sprint?}` | Walk there like a player: A* pathfinding, gradual turning, sprinting, jumping, drops, swimming, replanning |
| `mc_collect` | `collect {block, count, radius?}` | Walk to visible matching blocks, use the best tool, mine with real dig time, pick up the drops; counts only what reached the inventory |
| `mc_dig` | `dig {mode, length, direction?, until?}` | Staircase down (`stairs`), staircase up (`up`), or 1×2 `tunnel`; stops at lava/water, your own blocks, or the `until` block |
| `mc_place` | `place {item, x?,y?,z?}` | Place a block against any solid face; picks a sensible nearby spot if no position is given |
| `mc_fight` | `fight {id \| type, fleeHealth?}` | Equip a weapon, close in, aim, attack only at full cooldown |
| `mc_craft` | `craft {item, times?}` | Recipe-book crafting: 2×2 in the inventory, 3×3 by walking to and opening a nearby crafting table |
| `mc_select_item` | `selectItem {item}` | Hold an item (swaps it into the hotbar if needed) |
| `mc_scan_blocks` | `scanBlocks {radius?, blocks?}` | Block types around you with counts and nearest positions (ids or `#tags`) |
| `mc_call` | any | Escape hatch: call a method by name with raw params |

Dedicated-server tools (the server's own API, port 25598):

| Tool | Method | What it does |
|---|---|---|
| `mc_server_wait_ready` | (polls `status`) | Wait until the server has started; tolerates it not running yet |
| `mc_server_status` | `status` | State (`starting`/`running`/`stopping`), game port, online mode, players, ms/tick, frozen, dimensions, mods |
| `mc_server_command` | `command {command,ticks?}` | Run a command as the console (full permissions); returns output, success, result |
| `mc_server_players` | `players` | Players as the server sees them: op, ping, position, dimension, gamemode, health, inventory, open menu, effects |
| `mc_server_block` | `block {x,y,z,dimension?}` | Authoritative block state and block-entity NBT |
| `mc_server_entities` | `entities {player? \| x,y,z?, dimension?, radius?, type?, limit?}` | Entities around a player or point, or all loaded in a dimension |
| `mc_server_wait_for` | `waitFor {condition,timeoutTicks?}` | `ticks:<n>`, `log:<text>`, `player:<name>`, `noplayer:<name>`, `players:<n>`, `block:<x,y,z>=<id>[@dim]`, `entity:<type>[@dim]` |
| `mc_server_logs` | `logs {…}` | The server's captured log lines, same format as `mc_logs` |
| `mc_server_time_freeze` / `_unfreeze` / `_step` / `_status` | `time.*` | Tick freeze and exact stepping on the server |
| `mc_server_gametest_list` / `_run` | `gametest.*` | The GameTest tools, on the dedicated server |
| `mc_server_stop` | `stop` | Save and stop the server |
| `mc_server_launch` | (runs gradle) | Start `./gradlew :<loader>:runServer` and track it; then `mc_server_wait_ready`. `mc_close_all` closes it |
| `mc_server_call` | any | Raw call to the server API |

### Playing like a player

The skill tools build on the primitives and behave the way a person plays, which makes them useful for realistic
survival-style tests of your mod. They are not shortcuts:

- **Movement.** `mc_goto` never teleports. It plans a path (walk, diagonal, 1-block jumps, drops of up to 3 blocks,
  swimming; it avoids lava, fire, magma, cactus and berry bushes), then steers with the real movement keybinds. The
  camera turns at most 40° per tick, it sprints on straight stretches, and it replans when stuck.
- **Mining.** `mc_collect` only targets blocks a player could see (an exposed face, in line of sight), prefers walls
  over floors, never digs out the block you stand on or the steps of a staircase you dug, and refuses up front if
  nothing in your inventory can harvest the block. Drops are walked over and picked up.
- **Digging.** `mc_dig` won't dig through tables, chests, furnaces or machines, and looks for lava and water before
  opening a space.
- **Fighting.** `mc_fight` respects the attack cooldown, so damage is what a real player would deal.
- **Crafting.** `mc_craft` uses the recipe book, so recipes must be unlocked, exactly as in survival.
- **Lockstep.** `mc_lockstep` removes the agent's thinking time from the game: the world runs only while an action is
  in progress.

[`scripts/survival-test.mjs`](scripts/survival-test.mjs) uses only these player actions, in a world with cheats off,
to go from bare hands to a stone pickaxe: find trees, chop logs, craft planks, a table, sticks and a wooden pickaxe,
place the table, dig a staircase down to stone, mine cobblestone, craft a stone pickaxe, and hunt an animal. On random
terrain it succeeded on 4 of 5 seeds tried. The failures were terrain dead ends (for example being stuck at the bottom
of a hole with no exposed stone), which is where pathfinding that can place blocks (pillaring, bridging) would help.

### Things worth knowing

- **Errors come to you.** After every tool call the MCP server checks the game's log for new ERROR lines and appends
  them to the result, so an exception your mod throws shows up right after the action that caused it. `mc_logs` has
  the full stacks. If the game crashes, the next call fails with a hint to use `mc_crash_report`.
- **GameTests.** `mc_gametest_run {tests:"mymod:*"}` runs your mod's GameTests (registered through the Fabric or
  NeoForge GameTest API) in the open world and returns each result, so an agent can write a GameTest, rebuild, and
  check it in one loop. Use a flat test world: tests are placed near the world spawn.

- **Keybind actions apply on the next tick.** `mc_key {key:"e"}` returns before the inventory opens. Follow it with
  `mc_wait_for {condition:"screen:InventoryScreen"}`.
- **Screen class names** are the real, unobfuscated ones (`InventoryScreen`, `CreativeModeInventoryScreen`,
  `CraftingScreen`, your mod's screen class). `screen:` also matches superclasses, for example
  `screen:AbstractContainerScreen`.
- **Two slot numberings.** Container slot numbers are *menu* indices from `mc_snapshot` (crafting table: 0 = result,
  1–9 = grid, 10–45 = inventory). `mc_player` inventory slots are *inventory* indices (0–8 hotbar, 9–35 main,
  36+ armor/offhand).
- **Pause on lost focus is turned off.** The game window is usually not focused while an agent drives it, and vanilla
  would otherwise open the pause menu every time a screen closes. MCTest turns `pauseOnLostFocus` off in memory. To
  keep vanilla behaviour, launch with `-Dmctest.pauseOnLostFocus=true`.
- **Logs from before MCTest loads.** On Fabric MCTest attaches in `preLaunch`, before any mod initializes. On NeoForge,
  lines logged before MCTest is constructed are imported from `logs/latest.log` (flagged `backfilled: true`), so your
  mod's startup errors are visible on both loaders.
- **Tutorial hints are skipped.** Toasts like "Look around" would otherwise cover screenshots. Use
  `-Dmctest.tutorial=true` to keep them.
- **Input is SDL3 from 26.3.** Raw key numbers for `mc_key` are SDL scancodes on 26.3 and GLFW key codes on 1.21.1, 26.1.2 and 26.2.
  Use names, which work on every version.
- **Your mouse is left alone.** Vanilla captures the OS cursor whenever a screen closes in-world and warps it to the
  window centre whenever one opens, so an agent opening and closing screens or entering worlds would keep trapping or
  yanking your mouse while you work in another app. With MCTest, the game only captures or moves the cursor within
  2 seconds of *your own* click or key press in its window. MCTest's synthetic input never counts. Click into the
  game to play by hand as usual. `status` reports `windowFocused` and `mouseGrabbed`. Use `-Dmctest.mouseWarp=true`
  for vanilla behaviour.

---

## 4. Raw protocol

WebSocket on `ws://127.0.0.1:<port>/`. Send `{id, method, params}`, receive `{id, result}` or
`{id, error: {message, type, stack}}`. Requests run concurrently and responses are matched by `id`.

```js
const ws = new WebSocket('ws://127.0.0.1:25599/');
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'status', params: {} }));
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

[`scripts/lib.mjs`](scripts/lib.mjs) is a tiny promise client for Node 22+ (no dependencies).

---

## 5. Developing MCTest itself

### Publishing a release

`./gradlew publish` uploads the common, Fabric and NeoForge artifacts to the repository given by the `maven_url`,
`maven_username` and `maven_password` Gradle properties. Keep them in your user-level `~/.gradle/gradle.properties`,
never in this repo:

```properties
maven_url=https://maven.coolerpromc.com/releases/
maven_username=...
maven_password=...
```

Bump `version` in `gradle.properties` first; release repositories usually refuse to overwrite an existing version.


```
common/    all logic (vanilla code only): server/, rpc/, methods/, capture/, tick/, mixin/
fabric/    entrypoints + tick hook (ClientTickEvents) + platform service
neoforge/  entrypoint + tick hook (ClientTickEvent.Post) + platform service
mcp/       MCP server (TypeScript)
scripts/   end-to-end test scripts
```

Every game-state access hops to the render thread with `Minecraft.execute()` and completes a `CompletableFuture`;
nothing blocks the render thread. Waits are checked on each client tick.

One command builds and launches the client and a dedicated server for each loader, runs the suites below, and
quits the game afterwards (game output in `build/test-logs/`):

```bash
node scripts/run-tests.mjs                          # both loaders, all default suites
node scripts/run-tests.mjs fabric --suites step4,mcp
```

Or run a single suite against a client you started yourself (`./gradlew :fabric:runClient` or `:neoforge:runClient`):

```bash
node scripts/ws-test.mjs              # status + screenshot smoke test
node scripts/step4-test.mjs           # commands, player/block/entities, waitFor, logs
node scripts/step5-test.mjs           # world create/join/leave/list/delete
node scripts/play-test.mjs            # survival gameplay: walk, jump, place, mine, craft, eat, fight, milk
node scripts/skills-test.mjs          # pathfinding, time freeze/step/lockstep, craft, collect, fight, dig
node scripts/survival-test.mjs [seed] # no-cheats run on real terrain: punch trees -> stone pickaxe
node scripts/cursor-test.mjs          # screens open/close without the game capturing your mouse (Windows)
node scripts/server-test.mjs          # dedicated server: also start ./gradlew :<loader>:runServer first
cd mcp && npm test                    # MCP server end to end over stdio
```

All of these pass on both Fabric and NeoForge for 26.3 (and for 26.2, 26.1.2 and 1.21.1 on their branches). `survival-test` depends on the terrain around the spawn
point and passes on most seeds (see above).

## License

MIT. See [LICENSE](LICENSE).
