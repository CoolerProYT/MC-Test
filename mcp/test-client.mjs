// End-to-end check of the MCP server over stdio, using the SDK's own client.
//   npm run build && node test-client.mjs      (with a Minecraft client running MCTest)
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

await check('mc_screenshot returns a PNG image', async () => {
  const { r } = await call('mc_screenshot', { name: 'mcp-smoke' });
  const img = r.content.find((c) => c.type === 'image');
  assert(img && img.mimeType === 'image/png' && Buffer.from(img.data, 'base64').subarray(1, 4).toString() === 'PNG', 'no png');
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

await check('mc_world_leave + mc_world_delete', async () => {
  await call('mc_world_leave');
  const { r, text } = await call('mc_world_delete', { name: levelId });
  assert(!r.isError, text);
});

await client.close();
console.log(failures ? `\n${failures} failed` : '\nall checks passed');
process.exitCode = failures ? 1 : 0;
