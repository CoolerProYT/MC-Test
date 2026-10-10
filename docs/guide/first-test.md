# Your first test

With the client running and the MCP server registered, describe the test in plain language:

> Start the NeoForge client in the background, wait until it's ready, create a flat creative test world, give me a
> `mymod:grinder`, place it, put 8 iron ore in it, wait 200 ticks, and check that it output iron dust. Screenshot the GUI.

The agent turns that into tool calls like these:

```
mc_wait_ready → mc_world_create {name:"grinder-test", flat:true}
→ mc_command "give @s mymod:grinder" → mc_player (find the slot) → mc_select_slot
→ mc_use_on {x,y,z, face:"up"} (place) → mc_use_on (open GUI) → mc_wait_for "screen:GrinderScreen"
→ mc_snapshot → mc_click_slot … → mc_wait_for "ticks:200" → mc_block (block-entity NBT) / mc_snapshot
→ mc_screenshot → mc_logs {level:"WARN"} (anything go wrong?)
```

## The usual loop

1. **Set up.** `mc_world_create` makes a new world and waits until you are in it. It defaults to creative with cheats
   on, and `flat: true` gives a clean test area. `mc_command` runs any command and returns its chat output.
2. **Act.** Use the GUI tools (`mc_click_widget`, `mc_click_slot`, `mc_key`) or the gameplay tools (`mc_use_on`,
   `mc_break_block`, `mc_use_item`, `mc_keybind`). They behave exactly like a player's input.
3. **Wait.** Many effects land a tick or more later. `mc_wait_for` checks a condition every client tick:
   `screen:<Class>`, `slot:<i>=<item>`, `block:<x,y,z>=<id>`, `ticks:<n>`, `log:<text>`, `chat:<text>`,
   `entity:<type>`.
4. **Verify.** `mc_block` reads block state and block-entity NBT from the integrated server, so it is authoritative.
   `mc_player`, `mc_entities`, `mc_snapshot` and `mc_tooltip` cover the rest. `mc_screenshot` when visuals matter.
5. **Check the log.** New ERROR lines are appended to every tool result automatically. `mc_logs` has the full stacks.

## After changing your mod

Rebuilding needs a restart: `mc_quit` closes the game cleanly, then start `runClient` again (or `mc_launch`) and call
`mc_wait_ready`.

When the test is finished, `mc_close_all` quits every client and dedicated server (worlds are saved first) and kills
any that hang. Games started with `mc_launch` / `mc_server_launch` are also closed when the MCP session ends.
Asset-only changes don't need one: `mc_reload_resources` is F3+T.

If the game crashes, the next call fails with a hint to use `mc_crash_report`, which reads the newest crash report or
JVM `hs_err` log even after the game has died.
