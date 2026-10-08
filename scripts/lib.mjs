// Tiny promise-based client for the MCTest control server (Node 22+, no dependencies).

export async function connect(port = process.env.MCTEST_PORT ?? 25599) {
  const ws = new WebSocket(`ws://127.0.0.1:${port}/`);
  await new Promise((resolve, reject) => {
    ws.addEventListener('open', resolve, { once: true });
    ws.addEventListener('error', () => reject(new Error(`Could not connect to ws://127.0.0.1:${port}/`)), { once: true });
  });
  let nextId = 1;
  const pending = new Map();
  ws.addEventListener('message', (ev) => {
    const msg = JSON.parse(ev.data);
    const p = pending.get(msg.id);
    if (!p) return;
    pending.delete(msg.id);
    msg.error ? p.reject(Object.assign(new Error(msg.error.message), { remote: msg.error })) : p.resolve(msg.result);
  });
  const call = (method, params = {}) => new Promise((resolve, reject) => {
    const id = nextId++;
    pending.set(id, { resolve, reject });
    ws.send(JSON.stringify({ id, method, params }));
  });
  return { call, close: () => ws.close() };
}

/** Minimal test harness: check(name, fn) runs fn, prints PASS/FAIL, and remembers failures. */
export function harness() {
  let failures = 0;
  return {
    async check(name, fn) {
      try {
        const detail = await fn();
        console.log(`PASS ${name}${detail !== undefined ? ` -> ${typeof detail === 'string' ? detail : JSON.stringify(detail)}` : ''}`);
      } catch (e) {
        failures++;
        console.log(`FAIL ${name}: ${e.message}`);
      }
    },
    assert(cond, msg) {
      if (!cond) throw new Error(msg);
    },
    done() {
      console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
      process.exitCode = failures ? 1 : 0;
    },
  };
}
