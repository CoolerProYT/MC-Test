// Dedicated-server checks: server control API, client connect, client/server views agree, chat and commands from a
// dev client, server-side waits and time control. Start a server and a client first:
//   ./gradlew :fabric:runServer   (or :neoforge:runServer)
//   ./gradlew :fabric:runClient   (or :neoforge:runClient)
//   node scripts/server-test.mjs [host:port]
import { connect, harness } from './lib.mjs';

const address = process.argv[2] ?? 'localhost';
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
// Either side may still be launching.
async function connectRetry(port) {
  for (let i = 0; ; i++) {
    try {
      return await connect(port);
    } catch (e) {
      if (i >= 300) throw e;
      await sleep(1000);
    }
  }
}
const srv = await connectRetry(process.env.MCTEST_SERVER_PORT ?? 25598);
const mc = await connectRetry(process.env.MCTEST_PORT ?? 25599);
const t = harness();
const { check, assert } = t;
async function until(what, fn, timeoutMs = 300_000) {
  const deadline = Date.now() + timeoutMs;
  for (;;) {
    try {
      if (await fn()) return;
    } catch {
      // not up yet
    }
    if (Date.now() > deadline) throw new Error(`timed out waiting for ${what}`);
    await sleep(1000);
  }
}

await until('server ready', async () => (await srv.call('status')).ready);
await until('client ready', async () => (await mc.call('status')).ready);

await check('server status', async () => {
  const s = await srv.call('status');
  assert(s.side === 'server' && s.state === 'running', JSON.stringify(s));
  assert(s.onlineMode === false, 'dev server should be in offline mode');
  return `${s.loader} ${s.minecraftVersion}, port ${s.port}, ${s.players.length} online`;
});

await check('server command: console output', async () => {
  const r = await srv.call('command', { command: '/list' });
  assert(r.success === true && r.output.some((l) => /players online/.test(l)), JSON.stringify(r));
  return r.output.join(' | ');
});
await check('server command: failure is reported', async () => {
  const r = await srv.call('command', { command: 'notacommand' });
  assert(r.success !== true && r.output.some((l) => /Unknown or incomplete command/.test(l)), JSON.stringify(r));
});

const join = srv.call('waitFor', { condition: 'players:1', timeoutTicks: 2400 });
await check('client connect', async () => {
  const r = await mc.call('connect', { address });
  return `${r.address} in ${r.loadMs}ms`;
});
await check('server sees the player join', async () => (await join).observed);

const me = await mc.call('player');
const name = me.name;

await check('server players: same player, auto-opped', async () => {
  // Auto-op runs every 10 ticks.
  await srv.call('waitFor', { condition: 'ticks:20' });
  const r = await srv.call('players');
  const p = r.players.find((x) => x.name === name);
  assert(p, `player ${name} not in ${JSON.stringify(r.players.map((x) => x.name))}`);
  assert(p.op === true, 'player should be op');
  assert(Math.abs(p.pos.x - me.pos.x) < 1 && Math.abs(p.pos.z - me.pos.z) < 1, `server ${JSON.stringify(p.pos)} vs client ${JSON.stringify(me.pos)}`);
  return `${p.name} op=${p.op} ping=${p.ping}`;
});

await check('client command works on the server (op)', async () => {
  const r = await mc.call('command', { command: 'gamemode creative' });
  const p = (await srv.call('players')).players.find((x) => x.name === name);
  assert(p.gamemode === 'creative', `${p.gamemode}; output ${JSON.stringify(r.output)}`);
  return r.output.map((l) => l.text).join(' | ');
});

await check('client chat reaches the server (unsigned chat allowed)', async () => {
  const marker = `mctest-chat-${Date.now()}`;
  const wait = srv.call('waitFor', { condition: `log:${marker}`, timeoutTicks: 200 });
  await mc.call('chat', { message: marker });
  return (await wait).observed;
});

const { x, y, z } = me.blockPos;
const chest = { x: x + 2, y, z };
await check('server setblock -> client sees it', async () => {
  const r = await srv.call('command', {
    command: `setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest{Items:[{Slot:0b,id:"minecraft:diamond",count:3}]}`,
  });
  assert(r.success, JSON.stringify(r.output));
  const w = await mc.call('waitFor', { condition: `block:${chest.x},${chest.y},${chest.z}=chest` });
  return `client saw it after ${w.ticks} tick(s)`;
});
await check('server block has full NBT', async () => {
  const b = await srv.call('block', chest);
  assert(b.id === 'minecraft:chest' && b.source === 'server' && /diamond/.test(b.blockEntity?.nbt), JSON.stringify(b));
});
await check('client block in multiplayer reads the client level', async () => {
  const b = await mc.call('block', chest);
  assert(b.id === 'minecraft:chest' && b.source === 'client', JSON.stringify(b));
});

await check('client breaks a block -> server sees it', async () => {
  await mc.call('breakBlock', chest);
  const w = await srv.call('waitFor', { condition: `block:${chest.x},${chest.y},${chest.z}=air`, timeoutTicks: 100 });
  return `${w.ticks} tick(s)`;
});

await check('server entities around the player', async () => {
  await srv.call('command', { command: `summon minecraft:pig ${x + 3} ${y} ${z} {NoAI:1b,Tags:["mctest"]}` });
  const r = await srv.call('entities', { player: name, radius: 8, type: 'pig' });
  assert(r.total >= 1, JSON.stringify(r));
  await srv.call('waitFor', { condition: 'entity:pig' });
  const c = await mc.call('waitFor', { condition: 'entity:pig@8' });
  return `server ${r.total}, client: ${c.observed}`;
});
await srv.call('command', { command: 'kill @e[tag=mctest]' });

await check('server time freeze / step / unfreeze', async () => {
  const f = await srv.call('time.freeze');
  assert(f.frozen, JSON.stringify(f));
  const s = await srv.call('time.step', { ticks: 10 });
  assert(s.steppedTicks === 10, JSON.stringify(s));
  const u = await srv.call('time.unfreeze');
  assert(!u.frozen, JSON.stringify(u));
  return `stepped ${s.steppedTicks}`;
});

await check('server logs', async () => {
  const r = await srv.call('logs', { contains: 'MCTest', limit: 5 });
  assert(r.lines.length > 0, 'no MCTest lines');
  return `${r.lines.length} line(s), next=${r.next}`;
});

await check('unknown dimension is a clear error', async () => {
  try {
    await srv.call('block', { x: 0, y: 0, z: 0, dimension: 'nowhere' });
    throw new Error('expected an error');
  } catch (e) {
    assert(/Unknown dimension/.test(e.message), e.message);
  }
});

const leave = srv.call('waitFor', { condition: `noplayer:${name}`, timeoutTicks: 600 });
await check('client disconnects', async () => {
  const r = await mc.call('world.leave');
  assert(r.left, JSON.stringify(r));
  return (await leave).observed;
});

srv.close();
mc.close();
t.done();
