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

- Register tests through the Fabric or Forge GameTest API (`@GameTest` methods; 1.20.1 has no data-driven test
  instances). On Forge, Forge only registers your tests with these run properties:
  `property 'forge.enableGameTest', 'true'` and `property 'forge.enabledGameTestNamespaces', '<your mod id>'`.
- Selectors match the test name (`myclass.mytest`), the structure id (`mymod:myclass.mytest`), or
  `<structure namespace>:<test name>`; `*` is a wildcard. `mymod:*` runs the tests whose structures live in `mymod`.
- Use a **flat** test world: tests are placed next to the player (or the world spawn on a server with nobody on).
- Vanilla 1.20.1 only ticks GameTests in its IDE mode; MCTest ticks the tests it starts itself.
- The same tools exist on a dedicated server as `mc_server_gametest_list` and `mc_server_gametest_run`. See
  [Dedicated servers](/guide/dedicated-server).
