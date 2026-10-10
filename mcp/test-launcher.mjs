// Checks that the MCP server closes every game it opens: mc_launch + mc_server_launch, mc_close_all, then a client
// that is left running when the MCP session ends.
//   npm run build && node test-launcher.mjs [fabric|neoforge]      (no game running beforehand)
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { Client } from '@modelcontextprotocol/sdk/client/index.js';
import { StdioClientTransport } from '@modelcontextprotocol/sdk/client/stdio.js';

const here = dirname(fileURLToPath(import.meta.url));
const projectDir = join(here, '..');
const loader = process.argv[2] ?? 'fabric';

let failures = 0;
const assert = (c, m) => { if (!c) throw new Error(m); };
async function check(label, fn) {
  try {
    console.log(`PASS ${label}${(await fn()) ?? ''}`);
  } catch (e) {
    failures++;
    console.log(`FAIL ${label}: ${e.message}`);
  }
}
const alive = (pid) => { try { process.kill(pid, 0); return true; } catch { return false; } };
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

async function session() {
  const client = new Client({ name: 'mctest-launcher-test', version: '0.0.0' });
  const transport = new StdioClientTransport({ command: process.execPath, args: [join(here, 'dist/index.js')], env: { ...process.env } });
  await client.connect(transport);
  const call = async (name, args = {}) => {
    const r = await client.callTool({ name, arguments: args }, undefined, { timeout: 900_000 });
    const text = r.content.find((c) => c.type === 'text')?.text ?? '';
    let json;
    try { json = JSON.parse(text); } catch {}
    return { r, text, json };
  };
  return { client, transport, call };
}

// 1. launch both, close with mc_close_all
{
  const { client, call } = await session();
  let clientPid, serverPid;
  await check('mc_server_launch + mc_server_wait_ready', async () => {
    const l = await call('mc_server_launch', { loader, projectDir });
    assert(!l.r.isError, l.text);
    const w = await call('mc_server_wait_ready', { timeoutSeconds: 600 });
    assert(!w.r.isError, w.text);
    serverPid = (await call('mc_server_status')).json.pid;
    assert(alive(serverPid), `server pid ${serverPid}`);
    return ` -> pid ${serverPid}`;
  });
  await check('mc_launch + mc_wait_ready', async () => {
    const l = await call('mc_launch', { loader, projectDir });
    assert(!l.r.isError, l.text);
    const w = await call('mc_wait_ready', { timeoutSeconds: 600 });
    assert(!w.r.isError, w.text);
    clientPid = (await call('mc_status')).json.pid;
    assert(alive(clientPid), `client pid ${clientPid}`);
    return ` -> pid ${clientPid}`;
  });
  await check('second mc_launch is refused while one runs', async () => {
    const l = await call('mc_launch', { loader, projectDir });
    assert(l.r.isError, l.text);
  });
  await check('mc_close_all closes both', async () => {
    const c = await call('mc_close_all');
    assert(!c.r.isError, c.text);
    await sleep(1000);
    assert(!alive(clientPid) && !alive(serverPid), `still alive: client ${alive(clientPid)}, server ${alive(serverPid)}`);
    return ` -> ${c.json.closed.join(' | ')}`;
  });
  await check('mc_close_all with nothing running', async () => {
    const c = await call('mc_close_all');
    assert(!c.r.isError && c.json.closed[0] === 'nothing was running', c.text);
  });
  await client.close();
}

// 2. leave a client running and end the MCP session
{
  const { client, call } = await session();
  let clientPid;
  await check('launch a client, then end the MCP session', async () => {
    assert(!(await call('mc_launch', { loader, projectDir })).r.isError, 'launch failed');
    assert(!(await call('mc_wait_ready', { timeoutSeconds: 600 })).r.isError, 'not ready');
    clientPid = (await call('mc_status')).json.pid;
    await client.close();
    const deadline = Date.now() + 30_000;
    while (alive(clientPid) && Date.now() < deadline) await sleep(500);
    assert(!alive(clientPid), `client ${clientPid} still running after the session ended`);
    return ` -> pid ${clientPid} gone`;
  });
}

console.log(failures ? `\n${failures} check(s) failed` : '\nall checks passed');
process.exitCode = failures ? 1 : 0;
