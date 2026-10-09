# Dedicated servers

Multiplayer bugs (missing packets, client/server desync, code that only works in singleplayer because both sides share a
JVM) only show up against a real dedicated server. MCTest runs on the server too: the same `localRuntime` dependency
puts it on `runServer`, where it opens a second control API on `ws://127.0.0.1:25598/` that the `mc_server_*` tools
talk to.

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

Comparing `mc_server_block` with `mc_block {source: "client"}` is the quickest way to find a block entity that never
syncs its data to the client.

## Dev-server conveniences

In a development environment MCTest makes a dev server usable by dev clients:

- **Offline mode.** Dev clients have no Mojang session, so the server skips authentication and does not require signed
  chat, whatever `server.properties` says. Opt out with `-Dmctest.onlineMode=true`.
- **No whitelist.** New 26.x servers whitelist by default. MCTest turns it off at startup (as `/whitelist off` would).
  `/whitelist on` still works afterwards. Keep it with `-Dmctest.whitelist=true`.
- **Auto-op.** Players are opped when they join, so `mc_command` works from the client. A test that deops a player is
  respected until they rejoin. Turn off with `-Dmctest.autoOp=false` for permission tests.

The server API is not started in the integrated server: in singleplayer the client API already reads the integrated
server directly (`mc_block`, `mc_time_*`).

See [Server tools](/reference/server-tools) for the full list.
