# GameTests

MCTest can run Minecraft's GameTests in the open world and report each result, so an agent can write a GameTest,
rebuild, and check it in one loop.

| Tool | What it does |
|---|---|
| `mc_gametest_list {filter?}` | GameTests registered through the loader's GameTest API, with their batch and structure |
| `mc_gametest_run {tests?, timeoutSeconds?}` | Run tests (like `/test run`) and wait. Returns pass/fail per test with the failure message and position, plus an overall `success` |

```
mc_world_create {name:"gt", flat:true}
→ mc_gametest_run {tests:"mymod:*"}
```

- Register tests through the Fabric or NeoForge GameTest API (`@GameTest` methods; 1.21.1 has no data-driven test
  instances). On NeoForge, enable your namespace with `neoforge.enabledGameTestNamespaces` in your run configs.
- Selectors match the test name (`myclass.mytest`), the structure id (`mymod:myclass.mytest`), or
  `<structure namespace>:<test name>`; `*` is a wildcard. `mymod:*` runs the tests whose structures live in `mymod`.
  Manual-only tests run only when named without a wildcard.
- Use a **flat** test world: tests are placed next to the player (or the world spawn on a server with nobody on).
- Vanilla 1.21.1 only ticks GameTests in its IDE mode; MCTest ticks the tests it starts itself.
- The same tools exist on a dedicated server as `mc_server_gametest_list` and `mc_server_gametest_run`. See
  [Dedicated servers](/guide/dedicated-server).
