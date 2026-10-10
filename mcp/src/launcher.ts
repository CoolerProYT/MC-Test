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

export class Launcher {
  readonly launched: Launched[] = [];

  constructor(private readonly clients: Record<Side, MCTestClient>) {}

  /** Starts `gradlew :<loader>:runClient|runServer` (or a custom task) in the project directory. */
  async launch(side: Side, loader: string, projectDir: string | undefined, task: string | undefined, args: string[]): Promise<Launched> {
    const dir = resolve(projectDir ?? process.env.MCTEST_PROJECT_DIR ?? process.cwd());
    const gradlew = join(dir, isWindows ? 'gradlew.bat' : 'gradlew');
    if (!existsSync(gradlew)) {
      throw new Error(`No ${isWindows ? 'gradlew.bat' : 'gradlew'} in ${dir}. Pass projectDir (the mod's gradle project) or set MCTEST_PROJECT_DIR.`);
    }
    const running = this.launched.find((l) => l.side === side && !l.exited);
    if (running) throw new Error(`A ${side} launched by this session is still running (${running.task}). Close it first (mc_close_all, mc_quit or mc_server_stop).`);
    try {
      await this.clients[side].call('status', {}, 3_000, 0);
      throw new Error('already-running');
    } catch (e) {
      if ((e as Error).message === 'already-running') {
        throw new Error(`A Minecraft ${side} with MCTest is already running on port ${this.clients[side].port}. Use it, or close it (mc_close_all) before launching another.`);
      }
    }

    const fullTask = task ?? `:${loader}:${side === 'client' ? 'runClient' : 'runServer'}`;
    const logDir = join(dir, 'build', 'mctest-logs');
    mkdirSync(logDir, { recursive: true });
    const log = join(logDir, `${loader}-${side}.log`);
    const out = createWriteStream(log);
    const gradleArgs = ['--console=plain', fullTask, ...args];
    const child = isWindows
      ? spawn('cmd.exe', ['/d', '/s', '/c', gradlew, ...gradleArgs], { cwd: dir, stdio: ['ignore', 'pipe', 'pipe'], windowsHide: true })
      : spawn(gradlew, gradleArgs, { cwd: dir, stdio: ['ignore', 'pipe', 'pipe'], detached: true });
    child.stdout!.pipe(out);
    child.stderr!.pipe(out);

    const entry: Launched = { side, loader, task: fullTask, projectDir: dir, log, startedAt: Date.now(), child, exited: false, exitCode: null };
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
        const s = await this.clients[entry.side].call('status', {}, 5_000, 0);
        if (typeof s.pid === 'number') entry.gamePid = s.pid;
        else if (s?.ready) return; // an older MCTest without pid: closeAll falls back to findGameJvms
      } catch {
        // not up yet
      }
      await sleep(2_000);
    }
  }

  /** Records the pid reported by any status call (lets external games be killed too). */
  notePid(side: Side, pid: unknown) {
    if (typeof pid !== 'number') return;
    const entry = this.launched.find((l) => l.side === side && !l.exited);
    if (entry && !entry.gamePid) entry.gamePid = pid;
  }

  /** The launched process for a side, if it died before becoming ready. */
  failedStart(side: Side): string | undefined {
    const entry = [...this.launched].reverse().find((l) => l.side === side);
    if (!entry?.exited) return undefined;
    return `The ${side} launched with ${entry.task} exited (code ${entry.exitCode}). Last lines of ${entry.log}:\n${tail(entry.log, 30)}`;
  }

  /**
   * Closes every game: first politely (quit / stop through MCTest, so worlds are saved), then by killing the game JVM
   * and the gradle run that started it. `all` also closes MCTest games this session did not launch.
   */
  async closeAll(all: boolean, graceMs = 60_000): Promise<string[]> {
    const report: string[] = [];
    for (const side of ['client', 'server'] as const) {
      const client = this.clients[side];
      const mine = this.launched.filter((l) => l.side === side && (!l.exited || isAlive(l.gamePid)));
      let pid = mine.find((l) => l.gamePid)?.gamePid;
      let reachable = false;
      try {
        const s = await client.call('status', {}, 3_000, 0);
        reachable = true;
        if (typeof s.pid === 'number') pid = s.pid;
      } catch {
        // not running, or not answering
      }
      if (!mine.length && !(all && reachable)) continue;

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
      report.push(`${side}${pid ? ` (pid ${pid})` : ''}${mine.length ? ` launched by ${mine.map((l) => l.task).join(', ')}` : ' (not launched by this session)'}: ${how}`);
    }
    return report;
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
