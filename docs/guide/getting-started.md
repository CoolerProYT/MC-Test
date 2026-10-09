# Getting started

MCTest has two parts:

- **The MCTest mod**, a dev-only client mod for Fabric and NeoForge. It opens a WebSocket JSON-RPC API on
  `127.0.0.1:25599` inside your dev client.
- **The MCP server** [`mctest-mcp`](https://www.npmjs.com/package/mctest-mcp), which turns that API into `mc_*` tools
  for Claude Code or any other MCP client.

```
Claude Code ──stdio/MCP──▶ mctest-mcp (Node) ──WebSocket JSON-RPC──▶ MCTest mod in your dev client (127.0.0.1:25599)
```

## 1. Add the mod to your mod's dev runtime

MCTest is published to `https://maven.coolerpromc.com/releases/` as
`com.coolerpromc.mctest:mctest-fabric-26.3:<version>` and `com.coolerpromc.mctest:mctest-neoforge-26.3:<version>`.

::: code-group

```groovy [Fabric (fabric/build.gradle)]
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

dependencies {
    // Dev-only: on the runClient classpath, never in your jar or POM.
    localRuntime("com.coolerpromc.mctest:mctest-fabric-${minecraft_version}:26.3.0.2") { transitive = false }
}
```

```groovy [NeoForge (neoforge/build.gradle)]
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

// ModDevGradle has no built-in localRuntime, so declare one. Runtime-only in dev, never published.
configurations {
    localRuntime
    runtimeClasspath.extendsFrom localRuntime
}

dependencies {
    localRuntime("com.coolerpromc.mctest:mctest-neoforge-${minecraft_version}:26.3.0.2") { transitive = false }
}
```

:::

`transitive = false` stops MCTest's own Fabric API / loader runtime dependencies from overriding your versions. Both
setups were verified with a MultiLoader-template mod: MCTest loads in `runClient` on both loaders and does not appear
in the built jar or the generated POM.

::: tip Several clients at once
The default port is `25599`. Set `-Dmctest.port=25600` on the client run to use another one, and give the MCP server
the same port with `MCTEST_PORT`. See [Configuration](/reference/configuration).
:::

## 2. Register the MCP server

Requires Node.js 22 or newer. Nothing to clone or build:

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

The server connects lazily and reconnects after the game restarts, so register it once and leave it.

## 3. Start the game

```bash
./gradlew :fabric:runClient     # or :neoforge:runClient
```

Then ask your agent to test something. It starts with `mc_wait_ready`. Continue with [Your first test](/guide/first-test).
