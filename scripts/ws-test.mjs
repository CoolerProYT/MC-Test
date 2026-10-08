// Minimal smoke test for the MCTest control server. Needs Node 22+ (global WebSocket), no dependencies.
//
//   node scripts/ws-test.mjs                       -> status + screenshot
//   node scripts/ws-test.mjs <method> '<json params>'   -> call one method
//
// Port: MCTEST_PORT env var, default 25599.

const port = process.env.MCTEST_PORT ?? 25599;
const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
let nextId = 1;
const pending = new Map();

ws.addEventListener('message', (ev) => {
  const msg = JSON.parse(ev.data);
  const p = pending.get(msg.id);
  if (!p) return;
  pending.delete(msg.id);
  msg.error ? p.reject(Object.assign(new Error(msg.error.message), { remote: msg.error })) : p.resolve(msg.result);
});

function call(method, params = {}) {
  const id = nextId++;
  return new Promise((resolve, reject) => {
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
}

ws.addEventListener('error', () => {
  console.error(`Could not connect to ws://127.0.0.1:${port}/ - is the client running?`);
  process.exit(1);
});

ws.addEventListener('open', async () => {
  try {
    const [, , method, rawParams] = process.argv;
    if (method) {
      console.log(JSON.stringify(await call(method, rawParams ? JSON.parse(rawParams) : {}), null, 2));
    } else {
      const status = await call('status');
      console.log('status:', JSON.stringify({ ...status, mods: `${status.mods.length} mods` }, null, 2));
      console.log('screenshot:', await call('screenshot', { name: 'ws-test' }));
      try {
        await call('noSuchMethod');
      } catch (e) {
        console.log('error path ok:', e.message);
      }
    }
  } catch (e) {
    console.error('FAILED:', e.message, e.remote?.stack ?? '');
    process.exitCode = 1;
  } finally {
    ws.close();
  }
});
