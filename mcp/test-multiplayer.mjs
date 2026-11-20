// Multi-client + full-freeze test through the MCP server: a dedicated server and two clients (the main one and "Bob"),
// client:"Bob" targeting, extra player fields, whole-server freeze/step (connections must survive keep-alive timeouts),
// singleplayer full freeze, and mc_close_all closing all three games.
//   npm run build && node test-multiplayer.mjs [fabric|neoforge] [serverGamePort]      (no game running beforehand)
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const here = dirname(fileURLToPath(import.meta.url));
const projectDir = join(here, '..');
const loader = process.argv[2] ?? 'fabric';
const gamePort = process.argv[3] ?? '25566';

let failures = 0;
const assert = (c, m) => {
  if (!c) throw new Error(m);
};
async function check(label, fn) {
  try {
    console.log(`PASS ${label}${(await fn()) ?? ''}`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${label}: ${e.message}`);
  }
}
const alive = (pid) => {
  try {
    process.kill(pid, 0);
    return true;
  } catch {
    return false;
  }
};
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const client = new Client({ name: 'mctest-multiplayer-test', version: '0.0.0' });
await client.connect(new StdioClientTransport({ command: process.execPath, args: [join(here, 'dist/index.js')], env: { ...process.env } }));
const call = async (name, args = {}) => {
  const r = await client.callTool({ name, arguments: args }, undefined, { timeout: 900_000 });
  const text = r.content.find((c) => c.type === 'text')?.text ?? '';
  let json;
  try {
    json = JSON.parse(text);
  } catch {}
  return { r, text, json };
};
const ok = async (name, args) => {
  const res = await call(name, args);
  assert(!res.r.isError, `${name}: ${res.text}`);
  return res.json ?? res.text;
};

const pids = [];
try {
  await check('launch server + two clients', async () => {
    await ok('mc_server_launch', { loader, projectDir });
    await ok('mc_launch', { loader, projectDir });
    const bob = await ok('mc_launch', { loader, projectDir, name: 'Bob' });
    assert(bob.port !== 25599, `Bob got port ${bob.port}`);
    await ok('mc_server_wait_ready', { timeoutSeconds: 900 });
    const main = await ok('mc_wait_ready', { timeoutSeconds: 900 });
    const b = await ok('mc_wait_ready', { timeoutSeconds: 900, client: 'Bob' });
    assert(b.player === 'Bob', `Bob's client plays as ${b.player}`);
    assert(main.player !== 'Bob', `main client also Bob`);
    return ` -> main ${main.player} :${main.port}, Bob :${b.port}`;
  });

  await check('mc_clients lists both', async () => {
    const r = await ok('mc_clients');
    const names = r.clients.map((c) => `${c.username}:${c.port}`);
    assert(r.clients.length >= 2 && r.clients.some((c) => c.username === 'Bob'), names.join(', '));
    for (const c of r.clients) pids.push(c.pid);
    pids.push((await ok('mc_server_status')).pid);
    return ` -> ${names.join(', ')}`;
  });

  await check('both clients join the server', async () => {
    await ok('mc_connect', { address: `localhost:${gamePort}` });
    await ok('mc_connect', { address: `localhost:${gamePort}`, client: 'Bob' });
    const players = (await ok('mc_server_players')).players.map((p) => p.name);
    assert(players.includes('Bob') && players.length === 2, players.join(', '));
    return ` -> ${players.join(', ')}`;
  });

  await check('client:"Bob" drives Bob (command + player)', async () => {
    const p = await ok('mc_player', { client: 'Bob' });
    assert(p.name === 'Bob', `mc_player client:Bob is ${p.name}`);
    const byPort = await ok('mc_player', { port: (await ok('mc_clients')).clients.find((c) => c.username === 'Bob').port });
    assert(byPort.name === 'Bob', 'port selection');
    const main = await ok('mc_player');
    assert(main.name !== 'Bob', 'default client');
  });

  await check('player fields: vehicle, frozenTicks, air, fire, gliding', async () => {
    await ok('mc_server_command', { command: 'gamemode survival Bob' });
    await ok('mc_server_command', { command: 'execute at Bob run summon pig ~ ~ ~ {Tags:["mt_ride"],NoAI:1b}' });
    await ok('mc_server_command', { command: 'ride Bob mount @e[tag=mt_ride,limit=1]' });
    await ok('mc_server_wait_for', { condition: 'ticks:5' });
    let first = (await ok('mc_server_players')).players.find((p) => p.name === 'Bob');
    for (const k of ['gliding', 'vehicle', 'fireTicks', 'onFire', 'air', 'maxAir', 'frozenTicks', 'fullyFrozen', 'passengers', 'sleeping', 'swimming']) {
      assert(k in first, `server players lacks ${k}`);
    }
    assert(first.vehicle?.type === 'minecraft:pig', `server vehicle ${JSON.stringify(first.vehicle)}`);
    const cp = await ok('mc_player', { client: 'Bob' });
    assert(cp.vehicle?.type === 'minecraft:pig', `client vehicle ${JSON.stringify(cp.vehicle)}`);
    assert('fireTicks' in cp && 'air' in cp && 'frozenTicks' in cp && 'gliding' in cp, 'client fields');
    await ok('mc_server_command', { command: 'ride Bob dismount' });
    await ok('mc_server_command', { command: 'kill @e[tag=mt_ride]' });
    // In a glass pit high above spawn: water uses air, powder snow freezes, lava sets on fire.
    const bob = async () => (await ok('mc_server_players')).players.find((p) => p.name === 'Bob');
    const pit = async (block) => {
      await ok('mc_server_command', { command: 'fill 50 100 50 52 104 52 glass' });
      await ok('mc_server_command', { command: `fill 51 101 51 51 103 51 ${block}` });
      await ok('mc_server_command', { command: 'tp Bob 51.5 101 51.5' });
    };
    await pit('water');
    await ok('mc_server_wait_for', { condition: 'ticks:60' });
    let sp = await bob();
    const air = sp.air;
    const underWater = sp.underWater;
    await pit('powder_snow');
    await ok('mc_server_wait_for', { condition: 'ticks:40' });
    const frozen = (await bob()).frozenTicks;
    await pit('air');
    await ok('mc_server_command', { command: 'setblock 51 101 51 lava' });
    await ok('mc_server_wait_for', { condition: 'ticks:10' });
    sp = await bob();
    const fire = sp.fireTicks;
    const onFire = sp.onFire;
    await ok('mc_server_command', { command: 'fill 50 100 50 52 104 52 air' });
    await ok('mc_server_command', { command: 'effect give Bob fire_resistance 30 0 true' });
    await ok('mc_server_command', { command: 'gamemode creative Bob' });
    assert(underWater && air < sp.maxAir, `air ${air} underWater ${underWater}`);
    assert(onFire, 'onFire');
    assert(frozen > 0, `frozenTicks ${frozen}`);
    assert(air < sp.maxAir, `air ${air}`);
    assert(fire > 0, `fireTicks ${fire}`);
    return ` -> frozenTicks ${frozen}, air ${air}/${sp.maxAir}, fireTicks ${fire}`;
  });

  await check('server full freeze: no ticks, connections survive 40 s, exact steps', async () => {
    const f = await ok('mc_server_time_freeze', { full: true });
    assert(f.fullFreeze === true, JSON.stringify(f));
    const t0 = (await ok('mc_server_time_status')).serverTick;
    await sleep(40_000); // past the 15 s keep-alive and 30 s client read timeout
    const s1 = await ok('mc_server_time_status');
    assert(s1.serverTick === t0, `server ticked while frozen: ${t0} -> ${s1.serverTick}`);
    const players = (await ok('mc_server_players')).players.map((p) => p.name);
    assert(players.length === 2, `players after 40 s frozen: ${players}`);
    const st = await ok('mc_server_time_step', { ticks: 10 });
    assert(st.steppedTicks === 10 && st.serverTick === t0 + 10, JSON.stringify(st));
    await sleep(1000);
    assert((await ok('mc_server_time_status')).serverTick === t0 + 10, 'ticked after step');
    const main = await ok('mc_status');
    assert(main.state === 'in-world', `main client state ${main.state}`);
    await ok('mc_server_time_unfreeze');
    await sleep(1000);
    const s2 = await ok('mc_server_time_status');
    assert(s2.serverTick > t0 + 10 && !s2.fullFreeze, JSON.stringify(s2));
    return ` -> step ${JSON.stringify(st)}`;
  });

  await check('singleplayer full freeze on the main client', async () => {
    await ok('mc_world_create', { name: 'mt-freeze', flat: true });
    await ok('mc_time_freeze', { full: true });
    const a = await ok('mc_time_status');
    await sleep(2000);
    const b = await ok('mc_time_status');
    assert(a.fullFreeze && a.serverTick === b.serverTick && a.gameTime === b.gameTime, `${JSON.stringify(a)} ${JSON.stringify(b)}`);
    const st = await ok('mc_time_step', { ticks: 5 });
    assert(st.steppedTicks === 5, JSON.stringify(st));
    await ok('mc_time_unfreeze');
    await sleep(1000);
    const c = await ok('mc_time_status');
    assert(!c.fullFreeze && c.serverTick > b.serverTick + 5, JSON.stringify(c));
    return ` -> ${JSON.stringify(st)}`;
  });
} finally {
  await check('mc_close_all closes all three games', async () => {
    const r = await ok('mc_close_all', { graceSeconds: 90 });
    console.log('   ', r.closed.join('\n    '));
    await sleep(3000);
    const live = pids.filter((p) => p && alive(p));
    assert(r.closed.length >= 3, `closed ${r.closed.length}`);
    assert(!live.length, `still alive: ${live}`);
  });
  await client.close();
}
console.log(failures ? `${failures} FAILED` : 'ALL PASSED');
process.exit(failures ? 1 : 0);
