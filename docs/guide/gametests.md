# GameTests

MCTest can run Minecraft's GameTests in the open world and report each result, so an agent can write a GameTest,
rebuild, and check it in one loop.

| Tool | What it does |
|---|---|
| `mc_gametest_list {filter?}` | GameTests registered in the singleplayer world: vanilla, your mod's, and datapack test instances |
| `mc_gametest_run {tests?, timeoutSeconds?}` | Run tests (`/test run <selector>`) and wait. Returns pass/fail per test with the failure message and position, plus an overall `success` |

```
mc_world_create {name:"gt", flat:true}
→ mc_gametest_run {tests:"mymod:*"}
```

- Register tests through the Fabric or NeoForge GameTest API, or as `test_instance` JSON in a datapack.
- **On 1.21.1** tests are registered in code only (`@GameTest` methods, no `test_instance` JSON). Selectors match the
  test name (`myclass.mytest`), the structure id, or `<structure namespace>:<test name>`, so `mymod:*` runs the
  tests whose structures live in `mymod`. On NeoForge, enable your namespace with `neoforge.enabledGameTestNamespaces`.
  MCTest ticks the tests it starts itself, since vanilla 1.21.1 only does that in its IDE mode.
- Use a **flat** test world: tests are placed near the world spawn.
- A world whose datapacks add registry content shows the "experimental settings / backup" prompt on load.
  `mc_world_join` answers it for you unless you pass `confirmPrompts: false`.
- The same tools exist on a dedicated server as `mc_server_gametest_list` and `mc_server_gametest_run`. See
  [Dedicated servers](/guide/dedicated-server).
