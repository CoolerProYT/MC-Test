// One command for the whole suite: builds and launches the game (and a dedicated server), waits until ready, runs the
// test scripts, then quits the game cleanly. Exit code 1 if anything failed.
//
//   node scripts/run-tests.mjs                     # fabric + forge, default suites, including the server suite
//   node scripts/run-tests.mjs fabric              # one loader
//   node scripts/run-tests.mjs forge --no-server
//   node scripts/run-tests.mjs fabric --suites step4,mcp
//
// Suites: ws, step4, step5, play, skills, mcp, server. Not run by default: survival (terrain-dependent, slow) and
// cursor (moves your real mouse). Name them with --suites to run them. Game output goes to build/test-logs/.
import { spawn } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';
import { connect } from './lib.mjs';

const root = join(dirname(fileURLToPath(import.meta.url)), '..');
const isWindows = process.platform === 'win32';
const gradlew = join(root, isWindows ? 'gradlew.bat' : 'gradlew');

/** spawn without a shell; .bat/.cmd files on Windows go through cmd.exe explicitly. */
function run(cmd, cmdArgs, options) {
  return isWindows && /\.(bat|cmd)$|^npm$/.test(cmd)
    ? spawn('cmd.exe', ['/d', '/s', '/c', cmd, ...cmdArgs], options)
    : spawn(cmd, cmdArgs, options);
}

const args = process.argv.slice(2);
const loaders = args.filter((a) => a === 'fabric' || a === 'forge');
const suitesArg = args.includes('--suites') ? args[args.indexOf('--suites') + 1].split(',') : null;
const ALL = ['ws', 'step4', 'step5', 'play', 'skills', 'mcp', 'server'];
const suites = (suitesArg ?? ALL).filter((s) => !(args.includes('--no-server') && s === 'server'));
const unknown = suites.filter((s) => ![...ALL, 'survival', 'cursor'].includes(s));
if (unknown.length) {
  console.error(`Unknown suite(s): ${unknown.join(', ')}`);
  process.exit(2);
}

const SUITE_CMD = {
  ws: ['node', ['scripts/ws-test.mjs']],
  step4: ['node', ['scripts/step4-test.mjs']],
  step5: ['node', ['scripts/step5-test.mjs']],
  play: ['node', ['scripts/play-test.mjs']],
  skills: ['node', ['scripts/skills-test.mjs']],
  survival: ['node', ['scripts/survival-test.mjs']],
  cursor: ['node', ['scripts/cursor-test.mjs']],
  server: ['node', ['scripts/server-test.mjs']],
  mcp: ['npm', ['test'], 'mcp'],
};

// The client's control port. MCTEST_PORT moves it, e.g. when another MCTest client is running; pass the same port to
// the game: JAVA_TOOL_OPTIONS=-Dmctest.port=<port>.
const CLIENT_PORT = Number(process.env.MCTEST_PORT ?? 25599);
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const logDir = join(root, 'build', 'test-logs');
mkdirSync(logDir, { recursive: true });

/** Starts a gradle run task; its output goes to build/test-logs/<name>.log. */
function launch(name, task) {
  const log = createWriteStream(join(logDir, `${name}.log`));
  const child = run(gradlew, [task], { cwd: root, stdio: ['ignore', 'pipe', 'pipe'] });
  child.stdout.pipe(log);
  child.stderr.pipe(log);
  child.exited = new Promise((r) => child.on('exit', r));
  return child;
}

/** Kills a launched gradle run and the game JVM under it. */
async function kill(child) {
  if (child.exitCode !== null) return;
  if (isWindows) spawn('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' });
  else child.kill('SIGKILL');
  await Promise.race([child.exited, sleep(10_000)]);
}

/** Waits for a control socket to answer status with ready:true. */
async function waitReady(port, what, child, timeoutMs = 600_000) {
  const deadline = Date.now() + timeoutMs;
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`${what} exited during startup (see build/test-logs)`);
    try {
      const c = await connect(port);
      const s = await c.call('status');
      c.close();
      if (s.ready) return s;
    } catch {
      // not up yet
    }
    await sleep(2000);
  }
  throw new Error(`${what} not ready after ${timeoutMs / 1000}s`);
}

/** Asks the game to exit through MCTest, falling back to killing it. */
async function shutdown(child, port, method) {
  try {
    const c = await connect(port);
    await c.call(method);
    c.close();
    await Promise.race([child.exited, sleep(60_000)]);
  } catch {
    // already gone or not answering
  }
  await kill(child);
}

/** The dev server's game port (another server on 25565 is common, so the run config may use a different one). */
function serverAddress(loader) {
  const props = join(root, loader, 'runs', 'server', 'server.properties');
  const port = existsSync(props) ? /^server-port=(\d+)/m.exec(readFileSync(props, 'utf8'))?.[1] : null;
  return port && port !== '25565' ? `localhost:${port}` : 'localhost';
}

function runSuite(name, loader) {
  const [cmd, baseArgs, dir] = SUITE_CMD[name];
  const cmdArgs = name === 'server' ? [...baseArgs, serverAddress(loader)] : baseArgs;
  const env = { ...process.env, MCTEST_TEST_ADDRESS: serverAddress(loader) };
  return new Promise((resolve) => {
    const started = Date.now();
    const child = run(cmd === 'node' ? process.execPath : cmd, cmdArgs, { cwd: join(root, dir ?? ''), env, stdio: ['ignore', 'pipe', 'pipe'] });
    let out = '';
    child.stdout.on('data', (d) => (out += d));
    child.stderr.on('data', (d) => (out += d));
    child.on('exit', (code) => {
      const failures = out.split('\n').filter((l) => l.startsWith('FAIL'));
      resolve({ name, ok: code === 0, seconds: Math.round((Date.now() - started) / 1000), failures, out });
    });
  });
}

const results = [];
for (const loader of loaders.length ? loaders : ['fabric', 'forge']) {
  console.log(`\n== ${loader} ==`);
  const needServer = suites.includes('server');
  const server = needServer ? launch(`${loader}-server`, `:${loader}:runServer`) : null;
  const client = launch(`${loader}-client`, `:${loader}:runClient`);
  try {
    if (server) {
      const s = await waitReady(25598, `${loader} server`, server);
      console.log(`server ready (${s.loader} ${s.minecraftVersion})`);
    }
    const s = await waitReady(CLIENT_PORT, `${loader} client`, client);
    console.log(`client ready (${s.loader} ${s.loaderVersion}, ${s.mods.length} mods)`);
    for (const suite of suites) {
      if (suite === 'server' && !server) continue;
      process.stdout.write(`  ${suite.padEnd(9)} `);
      const r = await runSuite(suite, loader);
      results.push({ loader, ...r });
      console.log(`${r.ok ? 'PASS' : 'FAIL'} (${r.seconds}s)`);
      for (const f of r.failures) console.log(`      ${f}`);
      if (!r.ok && !r.failures.length) console.log(r.out.split('\n').slice(-15).map((l) => `      ${l}`).join('\n'));
    }
  } catch (e) {
    console.log(`  ERROR ${e.message}`);
    results.push({ loader, name: 'startup', ok: false, failures: [e.message] });
  } finally {
    await shutdown(client, CLIENT_PORT, 'quit');
    if (server) await shutdown(server, 25598, 'stop');
  }
}

const failed = results.filter((r) => !r.ok);
console.log(`\n${results.length - failed.length}/${results.length} suite runs passed${failed.length ? `; failed: ${failed.map((r) => `${r.loader}/${r.name}`).join(', ')}` : ''}`);
process.exitCode = failed.length ? 1 : 0;
