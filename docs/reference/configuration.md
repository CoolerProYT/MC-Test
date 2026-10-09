# Configuration

## Game system properties

Set these as JVM arguments on the client or server run, for example:

```groovy
// Fabric:   loom { runs { client { vmArg('-Dmctest.port=25600') } } }
// NeoForge: neoForge { runs { client { systemProperty('mctest.port', '25600') } } }
```

| Property | Default | Side | Meaning |
|---|---|---|---|
| `mctest.port` | `25599` | client | Port of the client control API |
| `mctest.serverPort` | `25598` | server | Port of the dedicated-server control API |
| `mctest.logBuffer` | `10000` | both | Log lines kept for `mc_logs` |
| `mctest.pauseOnLostFocus` | `false` | client | `true` keeps vanilla's pause menu when the window loses focus |
| `mctest.mouseWarp` | `false` | client | `true` lets the game capture and warp the OS cursor as in vanilla |
| `mctest.tutorial` | `false` | client | `true` keeps the tutorial hint toasts |
| `mctest.onlineMode` | `false` | server | `true` keeps authentication and signed chat as `server.properties` says |
| `mctest.whitelist` | `false` | server | `true` keeps the whitelist on at startup |
| `mctest.autoOp` | `true` | server | `false` stops opping players when they join |

The control APIs always bind to `127.0.0.1`.

## MCP server environment

| Variable | Default | Meaning |
|---|---|---|
| `MCTEST_PORT` | `25599` | Port of the client control API |
| `MCTEST_SERVER_PORT` | `25598` | Port of the dedicated-server control API |

```bash
claude mcp add mctest --scope user -e MCTEST_PORT=25600 -- npx -y mctest-mcp
```
