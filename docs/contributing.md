# Developing MCTest

## Layout

```
common/    all logic (vanilla code only): server/, rpc/, methods/, capture/, tick/, mixin/, nav/, dedicated/
fabric/    entrypoints + tick hook (ClientTickEvents) + platform service
neoforge/  entrypoint + tick hook (ClientTickEvent.Post) + platform service
mcp/       MCP server (TypeScript), published to npm as mctest-mcp
scripts/   end-to-end test scripts
docs/      this site (VitePress)
```

Code loaded on a dedicated server must not touch client classes. Shared helpers live in `util/Describe` and
`methods/LogMethods`.

## Running the tests

One command builds and launches the client and a dedicated server for each loader, runs the suites, and quits the game
afterwards (game output in `build/test-logs/`):

```bash
node scripts/run-tests.mjs                          # both loaders, all default suites
node scripts/run-tests.mjs fabric --suites step4,mcp
node scripts/run-tests.mjs neoforge --no-server
```

Or run a single suite against a client you started yourself (`./gradlew :fabric:runClient` or `:neoforge:runClient`):

```bash
node scripts/ws-test.mjs              # status + screenshot smoke test
node scripts/step4-test.mjs           # commands, player/block/entities, waitFor, logs, held keys
node scripts/step5-test.mjs           # world create/join/leave/list/delete
node scripts/play-test.mjs            # survival gameplay: walk, jump, place, mine, craft, eat, fight, milk
node scripts/skills-test.mjs          # pathfinding, time freeze/step/lockstep, craft, collect, fight, dig
node scripts/survival-test.mjs [seed] # no-cheats run on real terrain: punch trees -> stone pickaxe
node scripts/cursor-test.mjs          # screens open/close without the game capturing your mouse (Windows)
node scripts/server-test.mjs          # dedicated server: also start ./gradlew :<loader>:runServer first
cd mcp && npm test                    # MCP server end to end over stdio
```

`survival` and `cursor` are not in the default set: the first depends on terrain, the second moves your real mouse.

## Publishing

**The mod.** `./gradlew publish` uploads the common, Fabric and NeoForge artifacts to the repository given by the
`maven_url`, `maven_username` and `maven_password` Gradle properties. Keep them in your user-level
`~/.gradle/gradle.properties`, never in the repository. Bump `version` in `gradle.properties` first; release
repositories refuse to overwrite an existing version. For local testing, `./gradlew publishToMavenLocal` and
`mavenLocal()` in the consuming mod.

**The MCP server.** From `mcp/`: `npm version patch && npm publish`. To run it from a checkout instead, `npm install`
there and register `node <path-to-checkout>/mcp/dist/index.js`.

**This site.** Pushes to the default branch rebuild and deploy it through `.github/workflows/docs.yml`. Locally:

```bash
cd docs
npm install
npm run dev       # http://localhost:5173
npm run build     # to .vitepress/dist
```

The client and server tool tables in `reference/` mirror the tables in the repository README: update both when a tool
changes.
