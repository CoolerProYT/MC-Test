// Starts Minecraft clients / dedicated servers through gradle and makes sure every game this MCP server opened is
// closed again: on mc_close_all, and when the MCP server itself shuts down.
//
// The game JVM is usually a child of the gradle *daemon*, not of the gradlew process we spawn, so killing our child's
// process tree is not enough. Each game reports its own pid in status; we kill that pid, and fall back to finding the
// project's game JVMs by command line if a game never answered.
import { spawn, spawnSync, type ChildProcess } from 'node:child_process';
import { createWriteStream, existsSync, mkdirSync, readFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import type { MCTestClient } from './client.js';

const isWindows = process.platform === 'win32';
const sleep = (ms: number) => new Promise((r) => setTimeout(r, ms));

export type Side = 'client' | 'server';

export interface Launched {
  side: Side;
  /** Control port of the game's MCTest API. */
  port: number;
  /** Player name requested for a client (mc_launch name). */
  name?: string;
  loader: string;
  task: string;
  projectDir: string;
  log: string;
  startedAt: number;
  child: ChildProcess;
  exited: boolean;
  exitCode: number | null;
  /** Pid of the game JVM, once it has answered status. */
  gamePid?: number;
}

export function isAlive(pid: number | undefined): boolean {
  if (!pid) return false;
  try {
    process.kill(pid, 0);
    return true;
  } catch (e) {
    return (e as NodeJS.ErrnoException).code === 'EPERM';
  }
}

/** Kills a process and everything under it, synchronously (safe to use in an 'exit' handler). */
function killTree(pid: number | undefined) {
  if (!isAlive(pid)) return;
  try {
    if (isWindows) spawnSync('taskkill', ['/PID', String(pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
    else {
      try {
        process.kill(-pid!, 'SIGKILL'); // our gradlew children lead their own process group
      } catch {
        process.kill(pid!, 'SIGKILL');
      }
    }
  } catch {
    // already gone
  }
}

async function waitDead(pid: number | undefined, ms: number): Promise<boolean> {
  const deadline = Date.now() + ms;
  while (isAlive(pid)) {
    if (Date.now() > deadline) return false;
    await sleep(250);
  }
  return true;
}

/**
 * Game JVMs (not gradle's own daemon/wrapper) whose command line mentions the project directory and that started
 * after `since`. Only used for launched games that never reported their pid.
 */
function findGameJvms(projectDir: string, since: number): number[] {
  const needles = [projectDir, projectDir.replace(/\\/g, '/')].map((s) => s.toLowerCase());
  const gradleOwn = /GradleDaemon|GradleWrapperMain|GradleWorkerMain|KotlinCompileDaemon|gradle-launcher|org\.gradle\./i;
  const rows: { pid: number; cmd: string; started: number }[] = [];
  try {
    if (isWindows) {
      const ps = spawnSync(
        'powershell.exe',
        [
          '-NoProfile',
          '-NonInteractive',
          '-Command',
          "Get-CimInstance Win32_Process -Filter \"Name='java.exe' OR Name='javaw.exe'\" | " +
            'ForEach-Object { [pscustomobject]@{ pid = $_.ProcessId; cmd = $_.CommandLine; started = ([DateTimeOffset]$_.CreationDate).ToUnixTimeMilliseconds() } } | ConvertTo-Json -Compress',
        ],
        { encoding: 'utf8', windowsHide: true, timeout: 20_000 },
      );
      const parsed = ps.stdout.trim() ? JSON.parse(ps.stdout) : [];
      for (const r of Array.isArray(parsed) ? parsed : [parsed]) rows.push({ pid: r.pid, cmd: r.cmd ?? '', started: r.started ?? 0 });
    } else {
      const ps = spawnSync('ps', ['-eo', 'pid=,etimes=,args='], { encoding: 'utf8', timeout: 20_000 });
      for (const line of ps.stdout.split('\n')) {
        const m = /^\s*(\d+)\s+(\d+)\s+(.*)$/.exec(line);
        if (m && /\bjava\b/.test(m[3])) rows.push({ pid: Number(m[1]), cmd: m[3], started: Date.now() - Number(m[2]) * 1000 });
      }
    }
  } catch {
    return [];
  }
  return rows
    .filter((r) => r.pid !== process.pid && r.started >= since - 5_000 && !gradleOwn.test(r.cmd))
    .filter((r) => needles.some((n) => r.cmd.toLowerCase().includes(n)))
    .map((r) => r.pid);
}

/** Ports probed for MCTest clients that were not launched by this session (mc_clients, mc_close_all). */
export const CLIENT_PORT_SCAN = { from: 25599, to: 25619 };

export interface LaunchOptions {
  /** Control port; default: the side's default port. */
  port?: number;
  /** Player name for a client (sets mctest.username). */
  name?: string;
}

export class Launcher {
  readonly launched: Launched[] = [];

  /**
   * @param clientFor the API client of a side's game on a port
   * @param defaultPort the port each side uses when none is given
   */
  constructor(
    private readonly clientFor: (side: Side, port: number) => MCTestClient,
    private readonly defaultPort: Record<Side, number>,
  ) {}

  /** Live games this session launched. */
  running(side?: Side): Launched[] {
    return this.launched.filter((l) => (!side || l.side === side) && (!l.exited || isAlive(l.gamePid)));
  }

  /** Starts `gradlew :<loader>:runClient|runServer` (or a custom task) in the project directory. */
  async launch(side: Side, loader: string, projectDir: string | undefined, task: string | undefined, args: string[], opts: LaunchOptions = {}): Promise<Launched> {
    const dir = resolve(projectDir ?? process.env.MCTEST_PROJECT_DIR ?? process.cwd());
    const gradlew = join(dir, isWindows ? 'gradlew.bat' : 'gradlew');
    if (!existsSync(gradlew)) {
      throw new Error(`No ${isWindows ? 'gradlew.bat' : 'gradlew'} in ${dir}. Pass projectDir (the mod's gradle project) or set MCTEST_PROJECT_DIR.`);
    }
    const name = opts.name?.trim() || undefined;
    if (name && !/^[A-Za-z0-9_]{1,16}$/.test(name)) throw new Error(`Invalid player name "${name}": 1-16 letters, digits or _`);
    if (name && side === 'server') throw new Error('name only applies to clients');
    if (name && this.running('client').some((l) => l.name?.toLowerCase() === name.toLowerCase())) {
      throw new Error(`A client named ${name} launched by this session is still running.`);
    }
    // A second client needs its own control port: pick a free one when only a name was given.
    // Ports of games launched here count as taken even before those games have opened them.
    const defaultTaken = () => this.running(side).some((l) => l.port === this.defaultPort[side]) || this.portInUse(side, this.defaultPort[side]);
    const port = opts.port ?? (name && (await defaultTaken()) ? await this.freePort(side) : this.defaultPort[side]);
    const running = this.running(side).find((l) => l.port === port);
    if (running) {
      throw new Error(
        `A ${side} launched by this session is still running on port ${port} (${running.task}). Close it first (mc_close_all, mc_quit or mc_server_stop)` +
          (side === 'client' ? ', or launch another client with a name (and/or port).' : '.'),
      );
    }
    if (await this.portInUse(side, port)) {
      throw new Error(
        `A Minecraft ${side} with MCTest is already running on port ${port}. Use it, or close it (mc_close_all) before launching another` +
          (side === 'client' ? ', or pass name (e.g. "Bob") to start an additional client on a free port.' : '.'),
      );
    }

    const fullTask = task ?? `:${loader}:${side === 'client' ? 'runClient' : 'runServer'}`;
    const logDir = join(dir, 'build', 'mctest-logs');
    mkdirSync(logDir, { recursive: true });
    const log = join(logDir, `${loader}-${side}${name ? `-${name}` : port !== this.defaultPort[side] ? `-${port}` : ''}.log`);
    const out = createWriteStream(log);
    const gradleArgs = ['--console=plain', fullTask, ...args];
    // The game reads its port and player name from system properties; JAVA_TOOL_OPTIONS reaches the game JVM that
    // gradle forks (and works with every MCTest version), the MCTEST_* variables are read by newer ones.
    const env = { ...process.env };
    const props: string[] = [];
    if (port !== this.defaultPort[side]) {
      env[side === 'client' ? 'MCTEST_PORT' : 'MCTEST_SERVER_PORT'] = String(port);
      props.push(`-Dmctest.${side === 'client' ? 'port' : 'serverPort'}=${port}`);
    }
    if (name) {
      env.MCTEST_USERNAME = name;
      props.push(`-Dmctest.username=${name}`);
    }
    if (props.length) env.JAVA_TOOL_OPTIONS = [env.JAVA_TOOL_OPTIONS, ...props].filter(Boolean).join(' ');
    const child = isWindows
      ? spawn('cmd.exe', ['/d', '/s', '/c', gradlew, ...gradleArgs], { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
      : spawn(gradlew, gradleArgs, { cwd: dir, env, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    child.stdout!.pipe(out);
    child.stderr!.pipe(out);

    const entry: Launched = { side, port, name, loader, task: fullTask, projectDir: dir, log, startedAt: Date.now(), child, exited: false, exitCode: null };
    child.on('exit', (code) => {
      entry.exited = true;
      entry.exitCode = code;
    });
    this.launched.push(entry);
    void this.watchPid(entry);
    return entry;
  }

  /** Learns the game pid as soon as the game's API answers, so the game can be killed even if it hangs later. */
  private async watchPid(entry: Launched) {
    while (!entry.exited && !entry.gamePid) {
      try {
        const s = await this.clientFor(entry.side, entry.port).call('status', {}, 5_000, 0);
        if (typeof s.pid === 'number') entry.gamePid = s.pid;
        else if (s?.ready) return; // an older MCTest without pid: closeAll falls back to findGameJvms
      } catch {
        // not up yet
      }
      await sleep(2_000);
    }
  }

  /** Records the pid reported by any status call (lets external games be killed too). */
  notePid(side: Side, port: number, pid: unknown) {
    if (typeof pid !== 'number') return;
    const entry = this.launched.find((l) => l.side === side && l.port === port && !l.exited);
    if (entry && !entry.gamePid) entry.gamePid = pid;
  }

  /** The launched process for a side and port, if it died before becoming ready. */
  failedStart(side: Side, port = this.defaultPort[side]): string | undefined {
    const entry = [...this.launched].reverse().find((l) => l.side === side && l.port === port);
    if (!entry?.exited) return undefined;
    return `The ${side} launched with ${entry.task} exited (code ${entry.exitCode}). Last lines of ${entry.log}:\n${tail(entry.log, 30)}`;
  }

  /**
   * Closes every game: first politely (quit / stop through MCTest, so worlds are saved), then by killing the game JVM
   * and the gradle run that started it. `all` also closes MCTest games this session did not launch.
   */
  async closeAll(all: boolean, graceMs = 60_000): Promise<string[]> {
    const targets: [Side, number][] = [];
    for (const side of ['client', 'server'] as const) {
      const ports = new Set([this.defaultPort[side], ...this.running(side).map((l) => l.port)]);
      if (all && side === 'client') for (const p of await this.scanClients()) ports.add(p);
      for (const p of ports) targets.push([side, p]);
    }
    // All games at once: each may take a while to save its world.
    const reports = await Promise.all(targets.map(([side, port]) => this.close(side, port, all, graceMs)));
    return reports.filter((r): r is string => !!r);
  }

  /** Control ports in the scan range where an MCTest client answers. */
  async scanClients(): Promise<number[]> {
    const ports: number[] = [];
    for (let p = CLIENT_PORT_SCAN.from; p <= CLIENT_PORT_SCAN.to; p++) if (p !== this.defaultPort.server) ports.push(p);
    const up = await Promise.all(ports.map((p) => this.portInUse('client', p)));
    return ports.filter((_, i) => up[i]);
  }

  async portInUse(side: Side, port: number): Promise<boolean> {
    try {
      await this.clientFor(side, port).call('status', {}, 3_000, 0);
      return true;
    } catch {
      return false;
    }
  }

  private async freePort(side: Side): Promise<number> {
    const taken = new Set(this.running(side).map((l) => l.port));
    for (let p = this.defaultPort[side] + 1; p < this.defaultPort[side] + 100; p++) {
      if (p === this.defaultPort.server || p === this.defaultPort.client || taken.has(p)) continue;
      if (!(await this.portInUse(side, p))) return p;
    }
    throw new Error('No free MCTest control port found');
  }

  private async close(side: Side, port: number, all: boolean, graceMs: number): Promise<string | undefined> {
    {
      const client = this.clientFor(side, port);
      const mine = this.launched.filter((l) => l.side === side && l.port === port && (!l.exited || isAlive(l.gamePid)));
      let pid = mine.find((l) => l.gamePid)?.gamePid;
      let reachable = false;
      let username: string | undefined;
      try {
        const s = await client.call('status', {}, 3_000, 0);
        reachable = true;
        if (typeof s.pid === 'number') pid = s.pid;
        if (typeof s.username === 'string') username = s.username;
      } catch {
        // not running, or not answering
      }
      if (!mine.length && !(all && reachable)) return undefined;

      let how = 'already exited';
      if (reachable) {
        try {
          await client.call(side === 'client' ? 'quit' : 'stop', {}, 5_000, 0);
          how = side === 'client' ? 'quit' : 'stopped';
        } catch {
          how = 'not answering';
        }
      }
      if (pid && !(await waitDead(pid, reachable ? graceMs : 0))) {
        killTree(pid);
        how += ', then killed (did not exit in time)';
      }
      for (const l of mine) {
        if (!l.gamePid && !pid) {
          const strays = findGameJvms(l.projectDir, l.startedAt);
          if (strays.length) {
            strays.forEach(killTree);
            how += `, killed game JVM ${strays.join(', ')}`;
          }
        }
        if (!(await waitForExit(l, 15_000))) killTree(l.child.pid);
      }
      const who = username ?? mine.find((l) => l.name)?.name;
      return `${side}${who ? ` ${who}` : ''} on port ${port}${pid ? ` (pid ${pid})` : ''}${mine.length ? ` launched by ${mine.map((l) => l.task).join(', ')}` : ' (not launched by this session)'}: ${how}`;
    }
  }

  /** Last-resort synchronous cleanup when the MCP process exits: kills whatever launched game is still alive. */
  killAllSync() {
    for (const l of this.launched) {
      if (l.gamePid) killTree(l.gamePid);
      else if (!l.exited) findGameJvms(l.projectDir, l.startedAt).forEach(killTree);
      if (!l.exited) killTree(l.child.pid);
    }
  }

  hasLiveGames(): boolean {
    return this.launched.some((l) => !l.exited || isAlive(l.gamePid));
  }
}

async function waitForExit(l: Launched, ms: number): Promise<boolean> {
  const deadline = Date.now() + ms;
  while (!l.exited && Date.now() < deadline) await sleep(250);
  return l.exited;
}

export function tail(file: string, lines: number): string {
  try {
    return readFileSync(file, 'utf8').split(/\r?\n/).slice(-lines).join('\n');
  } catch {
    return '(no log)';
  }
}
