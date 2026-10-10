# Server tools

These talk to MCTest's API inside a dedicated server (`ws://127.0.0.1:25598/` by default), started by
`runServer`. See [Dedicated servers](/guide/dedicated-server) for the setup.

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
