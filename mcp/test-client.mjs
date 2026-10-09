// End-to-end check of the MCP server over stdio, using the SDK's own client.
//   npm run build && node test-client.mjs      (with a Minecraft client running MCTest)
import { mkdirSync, writeFileSync } from 'node:fs';
import { join } from 'node:path';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const client = new Client({ name: 'mctest-smoke', version: '0.0.0' });
await client.connect(new StdioClientTransport({ command: process.execPath, args: ['dist/index.js'], env: { ...process.env } }));

let failures = 0;
async function call(name, args = {}) {
  const r = await client.callTool({ name, arguments: args });
  const text = r.content.find((c) => c.type === 'text')?.text ?? '';
  return { r, text, json: (() => { try { return JSON.parse(text); } catch { return undefined; } })() };
}
async function check(label, fn) {
  try {
    console.log(`PASS ${label}${(await fn()) ?? ''}`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${label}: ${e.message}`);
  }
}
const assert = (c, m) => { if (!c) throw new Error(m); };

const { instructions } = client.getInstructions ? { instructions: client.getInstructions() } : {};
await check('server instructions present', async () => assert(instructions?.includes('mc_wait_ready'), 'no instructions'));

const tools = (await client.listTools()).tools;
await check(`lists tools`, async () => {
  assert(tools.length >= 35 && tools.every((t) => t.name.startsWith('mc_') && t.description), `${tools.length} tools`);
  return ` -> ${tools.length} tools`;
});

await check('mc_wait_ready', async () => {
  const { r, json } = await call('mc_wait_ready', { timeoutSeconds: 120 });
  assert(!r.isError && json.ready, JSON.stringify(r.content));
  return ` -> ${json.state} on ${json.loader}`;
});

let levelId;
await check('mc_world_create', async () => {
  const { r, json } = await call('mc_world_create', { name: 'mctest-mcp', flat: true });
  assert(!r.isError && json.state === 'in-world', JSON.stringify(r.content));
  levelId = json.levelId;
});

await check('mc_command returns chat output', async () => {
  const { json } = await call('mc_command', { command: 'give @s minecraft:diamond 3' });
  assert(json.output.some((l) => /Gave 3/.test(l.text)), JSON.stringify(json));
});

await check('mc_key e + mc_wait_for + mc_snapshot (empty slots omitted)', async () => {
  await call('mc_key', { key: 'e' });
  const w = await call('mc_wait_for', { condition: 'screen:AbstractContainerScreen', timeoutTicks: 40 });
  assert(!w.r.isError, w.text);
  const { json } = await call('mc_snapshot');
  assert(json.container && json.container.slots.every((s) => s.item) && json.container.emptySlots > 0, JSON.stringify(json.container).slice(0, 300));
  await call('mc_close_screen');
  return ` -> ${json.screen.simpleName}, ${json.container.slots.length} filled + ${json.container.emptySlots} empty slots`;
});

await check('mc_screenshot returns a complete JPEG', async () => {
  const { r } = await call('mc_screenshot', { name: 'mcp-smoke' });
  const img = r.content.find((c) => c.type === 'image');
  const bytes = Buffer.from(img?.data ?? '', 'base64');
  // Downscaled JPEG inline (full PNG stays on disk): starts with FF D8, ends with FF D9 (not truncated).
  assert(img && img.mimeType === 'image/jpeg' && bytes[0] === 0xff && bytes[1] === 0xd8 && bytes.at(-2) === 0xff && bytes.at(-1) === 0xd9, 'no complete jpeg');
  return ` -> ${Math.round(img.data.length * 0.75 / 1024)} KiB`;
});

await check('validation errors come back as tool errors', async () => {
  const { r, text } = await call('mc_click_widget', { id: 'nope' });
  assert(r.isError && /No (widget|screen)/.test(text), text);
});

await check('mc_call escape hatch', async () => {
  const { json } = await call('mc_call', { method: 'player' });
  assert(json.gamemode === 'creative', JSON.stringify(json).slice(0, 200));
});

await check('mc_wait_for chat:', async () => {
  const waiting = call('mc_wait_for', { condition: 'chat:mctest-hello', timeoutTicks: 100 });
  await call('mc_command', { command: 'say mctest-hello' });
  const { r, json } = await waiting;
  assert(!r.isError && json.passed, JSON.stringify(r.content));
  return ` -> ${json.observed}`;
});

await check('mc_gametest_run (vanilla always_pass)', async () => {
  const { r, json } = await call('mc_gametest_run', { tests: 'minecraft:always_pass' });
  assert(!r.isError && json.success && json.passed === 1, JSON.stringify(r.content));
});

// A datapack with one passing and one failing test; datapack registry content makes the world "experimental", so
// rejoining it also exercises the automatic prompt confirmation.
await check('mc_world_join confirms the experimental-settings prompt', async () => {
  const gameDir = (await call('mc_status')).json.gameDir;
  const dir = join(gameDir, 'saves', levelId, 'datapacks', 'mctest-selftest');
  mkdirSync(join(dir, 'data', 'mctest_selftest', 'test_instance'), { recursive: true });
  writeFileSync(join(dir, 'pack.mcmeta'), JSON.stringify({ pack: { description: 'MCTest self-test', min_format: 90, max_format: 1000 } }));
  const t = (o) => JSON.stringify({ environment: 'minecraft:default', structure: 'minecraft:empty', max_ticks: 20, ...o });
  writeFileSync(join(dir, 'data', 'mctest_selftest', 'test_instance', 'passes.json'), t({ type: 'minecraft:function', function: 'minecraft:always_pass' }));
  writeFileSync(join(dir, 'data', 'mctest_selftest', 'test_instance', 'fails.json'), t({ type: 'minecraft:block_based' }));
  await call('mc_world_leave');
  const { r, json } = await call('mc_world_join', { name: levelId });
  assert(!r.isError && json.confirmedPrompts?.length, JSON.stringify(r.content));
  return ` -> ${json.confirmedPrompts[0]}`;
});

await check('mc_gametest_run reports a failure + the ERROR is appended', async () => {
  const list = (await call('mc_gametest_list', { filter: 'mctest_selftest' })).json;
  assert(list.count === 2, JSON.stringify(list));
  const { r, json } = await call('mc_gametest_run', { tests: 'mctest_selftest:*' });
  assert(!r.isError && json.success === false && json.passed === 1 && json.failed === 1, JSON.stringify(r.content).slice(0, 400));
  const failed = json.results.find((x) => !x.passed);
  // The failure is logged at ERROR by vanilla's reporter; it shows up on this or the next tool result.
  let errors = r.content.find((c) => c.type === 'text' && c.text.startsWith('New ERROR lines'));
  if (!errors) errors = (await call('mc_player')).r.content.find((c) => c.type === 'text' && c.text.startsWith('New ERROR lines'));
  assert(errors && /mctest_selftest:fails/.test(errors.text), 'no appended ERROR lines');
  return ` -> ${failed.id}: ${failed.error}`;
});

await check('mc_crash_report (none expected)', async () => {
  const { r, json } = await call('mc_crash_report');
  assert(!r.isError && json && ('path' in json || json.crashReport === null), JSON.stringify(r.content));
  return json.path ? ` -> newest ${json.path}` : ' -> no crash reports';
});

await check('mc_world_leave + mc_world_delete', async () => {
  await call('mc_world_leave');
  const { r, text } = await call('mc_world_delete', { name: levelId });
  assert(!r.isError, text);
});

// Dedicated-server tools, only when a server running MCTest is up (MCTEST_TEST_SERVER=1 to require it).
const { r: srvStatus, json: srvJson } = await call('mc_server_status');
if (!srvStatus.isError || process.env.MCTEST_TEST_SERVER) {
  await check('mc_server_status', async () => {
    assert(!srvStatus.isError && srvJson.side === 'server', JSON.stringify(srvStatus.content));
    return ` -> ${srvJson.state}, ${srvJson.players.length} online`;
  });
  await check('mc_connect + mc_server_players + mc_server_command', async () => {
    const { r, text } = await call('mc_connect', { address: 'localhost' });
    assert(!r.isError, text);
    const me = (await call('mc_player')).json.name;
    const { json } = await call('mc_server_players');
    assert(json.players.some((p) => p.name === me), JSON.stringify(json).slice(0, 200));
    const cmd = (await call('mc_server_command', { command: `give ${me} minecraft:emerald 2` })).json;
    assert(cmd.success, JSON.stringify(cmd));
    await call('mc_world_leave');
    return ` -> ${me} on the server`;
  });
} else {
  console.log('SKIP dedicated-server tools (no server running MCTest)');
}

await client.close();
console.log(failures ? `\n${failures} failed` : '\nall checks passed');
process.exitCode = failures ? 1 : 0;
