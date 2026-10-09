# mctest-mcp

An MCP server that lets Claude Code, or any other MCP client, drive a running Minecraft (26.x) client the way Playwright
drives a browser. Use it to test your mods: create worlds, click GUIs, press keys, walk, mine, craft, fight, run
commands, inspect blocks, entities and inventories, read logs and take screenshots.

It talks to the **MCTest** mod, a dev-only client mod (Fabric and NeoForge) that you add to your mod's dev runtime.
Full documentation: https://mctest.coolerpromc.com/

## 1. Add the MCTest mod to your mod's dev client

**Fabric** (`fabric/build.gradle`):

```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

dependencies {
    localRuntime("com.coolerpromc.mctest:mctest-fabric-26.3:26.3.0.2") { transitive = false }
}
```

**NeoForge** (`neoforge/build.gradle`):

```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    localRuntime("com.coolerpromc.mctest:mctest-neoforge-26.3:26.3.0.2") { transitive = false }
}
```

Both are dev-runtime only: MCTest is never bundled into your jar or published POM. It binds a WebSocket server to
`127.0.0.1:25599` when the client starts. To use another port, add `-Dmctest.port=<port>` to the client run.

## 2. Register the MCP server

Requires Node.js 22 or newer.

```bash
claude mcp add mctest --scope user -- npx -y mctest-mcp
```

For a client on another port:

```bash
claude mcp add mctest --scope user -e MCTEST_PORT=25600 -- npx -y mctest-mcp
```

To share the setup with everyone working on a mod, commit a `.mcp.json` in its repository:

```json
{
  "mcpServers": {
    "mctest": { "command": "npx", "args": ["-y", "mctest-mcp"] }
  }
}
```

Other MCP clients: run `npx -y mctest-mcp` as a stdio server.

## 3. Use it

Start your client (`./gradlew :fabric:runClient` or `:neoforge:runClient`) and ask your agent to test something:

> Wait for the game, create a flat test world, give me a `mymod:grinder`, place it, put 8 iron ore in, wait 10 seconds
> and check it produced iron dust. Screenshot the GUI.

The server connects lazily and reconnects when the game restarts, so you can leave it registered. Start with
`mc_wait_ready`.

## Tools

| Area | Tools |
|---|---|
| Lifecycle | `mc_status`, `mc_wait_ready`, `mc_world_create`, `mc_world_join`, `mc_world_leave`, `mc_world_list`, `mc_world_delete` |
| Observe | `mc_screenshot` (returns the image, downscaled JPEG; full PNG on disk), `mc_window_resize`, `mc_snapshot` (widgets + container slots), `mc_player`, `mc_block`, `mc_entities`, `mc_scan_blocks`, `mc_tooltip`, `mc_logs`, `mc_keybinds` |
| GUI input | `mc_click`, `mc_click_widget`, `mc_click_slot`, `mc_hover`, `mc_key`, `mc_type`, `mc_scroll`, `mc_close_screen` |
| Gameplay | `mc_command`, `mc_chat`, `mc_keybind`, `mc_select_slot`, `mc_select_item`, `mc_look`, `mc_look_at`, `mc_teleport`, `mc_use_on`, `mc_use_item`, `mc_break_block`, `mc_attack_entity`, `mc_interact_entity`, `mc_reload_resources` |
| Player skills | `mc_goto` (pathfinding walk), `mc_collect`, `mc_dig`, `mc_place`, `mc_fight`, `mc_craft` |
| Time | `mc_time_freeze`, `mc_time_unfreeze`, `mc_time_step`, `mc_time_status`, `mc_lockstep` |
| Waiting | `mc_wait_for` (`screen:`, `slot:`, `block:`, `ticks:`, `state:`, `log:`, `chat:`, `entity:`) |
| Multiplayer | `mc_connect` (join a server), `mc_world_leave` (disconnect) |
| GameTests | `mc_gametest_list`, `mc_gametest_run` (per-test pass/fail with failure messages) |
| Crashes | `mc_crash_report` (newest crash report, also after the game died), `mc_quit` |
| Dedicated server | `mc_server_wait_ready`, `mc_server_status`, `mc_server_command`, `mc_server_players`, `mc_server_block`, `mc_server_entities`, `mc_server_wait_for`, `mc_server_logs`, `mc_server_time_freeze` / `_unfreeze` / `_step` / `_status`, `mc_server_stop`, `mc_server_call` |
| Anything else | `mc_call` (raw method call) |

Notes:

- Coordinates in GUI tools are GUI-scaled, as reported by `mc_snapshot`.
- 20 ticks = 1 second.
- Keybind-driven actions (`mc_key` "e") take effect on the next tick, so follow them with `mc_wait_for`.
- `mc_lockstep` freezes the world while the agent is thinking, which helps a lot in survival play.
- New ERROR lines in the game log are appended to every tool result, so mod exceptions surface immediately.

## Environment

| Variable | Default | Meaning |
|---|---|---|
| `MCTEST_PORT` | `25599` | Port of the MCTest mod's control server in the client |
| `MCTEST_SERVER_PORT` | `25598` | Port of the MCTest control server in a dedicated server (`-Dmctest.serverPort`) |

## License

MIT
