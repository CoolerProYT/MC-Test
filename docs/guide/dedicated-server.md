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

## Several players

`mc_launch {loader, name: "Bob"}` starts another client that plays as Bob, on its own control port (the next free one
after 25599). Every client tool takes `client: "Bob"` (or `port`) to drive that client; without it, tools drive the
main client. `mc_clients` lists the running clients, and `mc_close_all` closes all of them.

```
mc_server_launch → mc_launch {loader:"fabric"} → mc_launch {loader:"fabric", name:"Bob"}
→ mc_server_wait_ready → mc_wait_ready → mc_wait_ready {client:"Bob"}
→ mc_connect → mc_connect {client:"Bob"} → mc_use_item {client:"Bob"} → mc_server_players
```

The name comes from `-Dmctest.username` (or the `MCTEST_USERNAME` environment variable) and the port from
`-Dmctest.port` (or `MCTEST_PORT`), so clients started some other way can be set up the same way.

## Freezing the whole server

`mc_server_time_freeze` works like `/tick freeze`: the worlds stop, but the server still ticks, so code on server tick
events (a mod's countdown, say) keeps running. `mc_server_time_freeze {full: true}` pauses the whole server tick
instead. Nothing ticks, players and every mod's tick events included. Packets are still handled and keep-alives are
still sent, so clients stay connected. `mc_server_time_step {ticks: n}` then runs exactly `n` whole server ticks, and
`mc_server_time_unfreeze` resumes. In singleplayer, `mc_time_freeze {full: true}` does the same to the integrated server.

The server API is not started in the integrated server: in singleplayer the client API already reads the integrated
server directly (`mc_block`, `mc_time_*`).

See [Server tools](/reference/server-tools) for the full list.
