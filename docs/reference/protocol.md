# Raw protocol

The MCP server is a thin layer over a WebSocket JSON-RPC API, so you can also drive the game from your own scripts or
CI without an agent.

- Client: `ws://127.0.0.1:25599/`. Dedicated server: `ws://127.0.0.1:25598/`.
- Send `{id, method, params}`. Receive `{id, result}` or `{id, error: {message, type, stack}}`.
- Requests run concurrently and responses are matched by `id`.
- Method names and params are the **Method** column of the [client](/reference/tools) and
  [server](/reference/server-tools) tool tables.

```js
const ws = new WebSocket('ws://127.0.0.1:25599/');
ws.onopen = () => ws.send(JSON.stringify({ id: 1, method: 'status', params: {} }));
ws.onmessage = (e) => console.log(JSON.parse(e.data));
```

[`scripts/lib.mjs`](https://github.com/CoolerProYT/MC-Test/blob/26.3/scripts/lib.mjs) is a tiny promise client for
Node 22+ with no dependencies:

```js
import { connect } from './lib.mjs';

const mc = await connect();
await mc.call('world.create', { name: 'ci-test', flat: true });
const r = await mc.call('command', { command: 'give @s minecraft:diamond' });
console.log(r.output);
mc.close();
```

Every game-state access hops to the render thread with `Minecraft.execute()`; nothing blocks the render thread, and
waits are checked once per client tick.
