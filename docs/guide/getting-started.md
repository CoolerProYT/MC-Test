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
`com.coolerpromc.mctest:mctest-<loader>-<minecraft version>:<version>`, for Minecraft 1.20.1, 1.21.1, 26.1.2, 26.2 and 26.3. 1.20.1 is for Fabric and Forge.

Latest releases, updated automatically from the Maven repository. Pick the row for your Minecraft version:

<!-- mctest-versions:start -->
| Minecraft | Fabric | NeoForge |
|---|---|---|
| 26.3 | `mctest-fabric-26.3:26.3.0.3` | `mctest-neoforge-26.3:26.3.0.3` |
| 26.2 | `mctest-fabric-26.2:26.2.0.1` | `mctest-neoforge-26.2:26.2.0.1` |
| 26.1.2 | `mctest-fabric-26.1.2:26.1.2.1` | `mctest-neoforge-26.1.2:26.1.2.1` |
<!-- mctest-versions:end -->

The snippets below are for 26.3. For another Minecraft version, replace the artifact and version with the
ones from its row (for example `mctest-fabric-26.1.2:26.1.2.1`).

On **1.21.1** Fabric, depend on it with `modLocalRuntime` instead of `localRuntime`: 1.21.1 is obfuscated, so
Loom has to remap MCTest to your mappings. NeoForge uses `localRuntime` on every version. For 1.20.1 on Forge, see
[below](#minecraft-1-20-1-on-forge).

::: code-group

```groovy [Fabric (fabric/build.gradle)]
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

dependencies {
    // Dev-only: on the runClient classpath, never in your jar or POM.
    localRuntime("com.coolerpromc.mctest:mctest-fabric-26.3:26.3.0.3") { transitive = false }
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
    localRuntime("com.coolerpromc.mctest:mctest-neoforge-26.3:26.3.0.3") { transitive = false }
}
```

:::

### Minecraft 1.20.1 on Forge

1.20.1 is published for **Fabric and Forge** (no NeoForge), as `mctest-fabric-1.20.1` and `mctest-forge-1.20.1`. On
Fabric use `modLocalRuntime` as for 1.21.1. Forge 1.20.1 jars are SRG-obfuscated, so MCTest is deobfuscated for your
dev runs. With **ForgeGradle 6** (the 1.20.1 MDK):

```groovy
repositories {
    maven { url = 'https://maven.coolerpromc.com/releases/' }
}

dependencies {
    // Dev-only: deobfuscated for runClient/runServer, never in your jar or POM.
    runtimeOnly(fg.deobf("com.coolerpromc.mctest:mctest-forge-1.20.1:1.20.1.1")) { transitive = false }
}

minecraft {
    runs {
        configureEach {
            // Needed for mixins from obfuscated mod jars (MCTest's). The 1.20.1 MDK leaves these out; without them
            // the game stops at startup with "Mixin apply failed".
            property 'mixin.env.remapRefMap', 'true'
            property 'mixin.env.refMapRemappingFile', "${projectDir}/build/createSrgToMcp/output.srg"
        }
    }
}
```

With **ModDevGradle** (`net.neoforged.moddev.legacyforge`), which sets up the mixin remapping itself:

```groovy
dependencies {
    modRuntimeOnly("com.coolerpromc.mctest:mctest-forge-1.20.1:1.20.1.1") { transitive = false }
}
```

Use the version from the 1.20.1 row of the table above once it is published.

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
