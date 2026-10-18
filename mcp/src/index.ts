#!/usr/bin/env node
// MCP server for MCTest: one mc_* tool per method of the MCTest mod's WebSocket API.
import { readdir, readFile, stat } from 'node:fs/promises';
import { join } from 'node:path';
import { createRequire } from 'node:module';
import { McpServer } from '@modelcontextprotocol/sdk/server/mcp.js';
import { StdioServerTransport } from '@modelcontextprotocol/sdk/server/stdio.js';
import { z } from 'zod';
import { MCTestClient, RemoteError } from './client.js';
import { Launcher, type Side } from './launcher.js';

const port = Number(process.env.MCTEST_PORT ?? 25599);
const mc = new MCTestClient(port);
const serverPort = Number(process.env.MCTEST_SERVER_PORT ?? 25598);
const srv = new MCTestClient(
  serverPort,
  'Minecraft dedicated server not reachable at {url} ({error}). Start it with ./gradlew :<loader>:runServer ' +
    '(fabric, neoforge or forge; with MCTest on the runtime classpath), then call mc_server_wait_ready.',
);
const launcher = new Launcher({ client: mc, server: srv });

const INSTRUCTIONS = `Drive a running Minecraft client (Fabric, NeoForge or Forge, with the MCTest mod) like Playwright drives a browser.
Typical flow: mc_launch {loader} (or use an already running client) -> mc_wait_ready -> mc_world_create (flat:true for a clean test world) -> act -> verify -> mc_screenshot when visuals matter.
- GUI: mc_snapshot lists widgets (stable id "index:Class:text") and container slots; prefer mc_click_widget over raw mc_click. Coordinates are GUI-scaled.
- Keybind-driven actions (opening the inventory with key "e", chat with "t") take effect on the next client tick: follow with mc_wait_for (e.g. "screen:InventoryScreen").
- Gameplay: mc_keybind (hold forward/jump/sneak for N ticks), mc_use_on / mc_break_block / mc_use_item / mc_attack_entity / mc_interact_entity act exactly like a player (server-validated reach and permissions).
- Verify with mc_player, mc_block (reads the integrated server in singleplayer, so block-entity NBT is authoritative), mc_entities, mc_wait_for, mc_logs (exceptions include stacks).
- mc_command runs any command (worlds made by mc_world_create have cheats on) and returns its chat output.
- Player skills: mc_goto (pathfinding walk), mc_collect (walk + mine visible blocks with the best tool + pick up drops), mc_dig (staircase down / tunnel), mc_place, mc_fight (melee with proper attack cooldowns), mc_craft (recipe book; opens the inventory or walks to a crafting table), mc_select_item, mc_scan_blocks.
- For survival play, enable mc_lockstep: the world freezes while you think and only runs while an action is in progress, so mobs can't act between your calls.
- Dedicated server (multiplayer) tests: start the server (mc_server_launch) and the client (mc_launch), mc_server_wait_ready + mc_wait_ready, then mc_connect. The mc_server_* tools talk to the server itself: mc_server_command runs console commands (full permissions), mc_server_players / mc_server_block / mc_server_entities show the authoritative server state, mc_server_wait_for waits on server-side conditions, mc_server_logs reads the server log. Client tools keep driving the player. Compare client and server views to catch sync bugs.
- GameTests: mc_gametest_run {tests:"mymod:*"} runs your mod's GameTests and reports each result (mc_server_gametest_run on a dedicated server).
- Errors logged by the game are appended to tool results automatically. If the game died, mc_crash_report shows why; mc_quit closes it cleanly.
- Start games with mc_launch / mc_server_launch rather than running gradle yourself, so they are tracked.
- ALWAYS call mc_close_all when you have finished testing (also after failures): it quits every Minecraft client and dedicated server (saving worlds) and kills any that hang. Games launched here are also closed when this MCP server shuts down.
- 20 ticks = 1 second.`;

const { version } = createRequire(import.meta.url)('../package.json') as { version: string };
const server = new McpServer({ name: 'mctest', version }, { instructions: INSTRUCTIONS });

type Content = { type: 'text'; text: string } | { type: 'image'; data: string; mimeType: string };

function text(value: unknown): { content: Content[] } {
  return { content: [{ type: 'text', text: typeof value === 'string' ? value : JSON.stringify(value) }] };
}

function errorResult(e: unknown) {
  let message = e instanceof Error ? e.message : String(e);
  // Unexpected exceptions (not MCTest's own validation errors) are usually what a mod developer needs to see.
  if (e instanceof RemoteError && e.type && !e.type.endsWith('RpcException') && e.remoteStack) {
    message += `\n${e.remoteStack.split('\n').slice(0, 25).join('\n')}`;
  }
  return { content: [{ type: 'text', text: `Error: ${message}` }] as Content[], isError: true };
}

/** Game directory per side, remembered from status calls so crash reports can be found after the game is gone. */
const gameDirs = new Map<MCTestClient, string>();
/** Newest log sequence number already reported per side. */
const errorCursor = new Map<MCTestClient, number>();

function remember(client: MCTestClient, result: any) {
  if (result && typeof result.gameDir === 'string') gameDirs.set(client, result.gameDir);
  if (result && 'pid' in result) launcher.notePid(client === srv ? 'server' : 'client', result.pid);
}

/**
 * ERROR lines logged since the previous tool call, so a mod's exceptions surface right after the action that caused
 * them instead of only when someone thinks to read the log.
 */
async function newErrors(client: MCTestClient): Promise<Content[]> {
  try {
    const since = errorCursor.get(client) ?? 0;
    const r = await client.call('logs', { since, level: 'ERROR', limit: 20 }, 3_000, 0);
    if (r.next < since) {
      // The game restarted and its sequence numbers began again.
      errorCursor.set(client, 0);
      return [];
    }
    errorCursor.set(client, r.next);
    if (!r.lines.length) return [];
    const side = client === srv ? 'server' : 'client';
    const shown = r.lines.slice(-5).map((l: any) => `[${l.level}] ${l.logger}: ${l.message}${l.exception ? ` -- ${l.exception}` : ''}`);
    const more = r.lines.length > 5 ? ` (newest 5 of ${r.lines.length}${r.lines.length === 20 ? '+' : ''})` : '';
    return [
      {
        type: 'text',
        text: `New ERROR lines in the ${side} log since the previous call${more}; ${side === 'server' ? 'mc_server_logs' : 'mc_logs'} {level:"ERROR"} has the stacks:\n${shown.join('\n')}`,
      },
    ];
  } catch {
    return [];
  }
}

async function withErrors(client: MCTestClient, result: { content: Content[]; isError?: boolean }) {
  result.content.push(...(await newErrors(client)));
  return result;
}

/** Drops undefined values so the mod applies its own defaults. */
function clean(args: Record<string, unknown>): Record<string, unknown> {
  return Object.fromEntries(Object.entries(args).filter(([, v]) => v !== undefined));
}

/**
 * Registers a tool that forwards its arguments to a mod method. `timeout` derives a client-side timeout from the
 * arguments (waits measured in ticks can stretch while the game is loading).
 */
function tool<S extends z.ZodRawShape>(
  name: string,
  method: string,
  description: string,
  shape: S,
  options: {
    timeout?: (args: z.infer<z.ZodObject<S>>) => number;
    transform?: (result: any, args: z.infer<z.ZodObject<S>>) => unknown;
    readOnly?: boolean;
    client?: MCTestClient;
  } = {},
) {
  server.registerTool(
    name,
    { description, inputSchema: shape, annotations: { readOnlyHint: options.readOnly ?? false, openWorldHint: false } },
    (async (args: z.infer<z.ZodObject<S>>) => {
      const client = options.client ?? mc;
      let out: { content: Content[]; isError?: boolean };
      try {
        const result = await client.call(method, clean(args as Record<string, unknown>), options.timeout?.(args) ?? 60_000);
        remember(client, result);
        out = text(options.transform ? options.transform(result, args) : result);
      } catch (e) {
        out = errorResult(e);
      }
      return method === 'logs' ? out : withErrors(client, out);
    }) as any,
  );
}

const ticksTimeout = (ticks: number | undefined, def: number) => (ticks ?? def) * 50 * 3 + 15_000;
const xyz = {
  x: z.number().describe('Block/world X'),
  y: z.number().describe('Block/world Y'),
  z: z.number().describe('Block/world Z'),
};
const intXyz = {
  x: z.number().int().describe('Block X'),
  y: z.number().int().describe('Block Y'),
  z: z.number().int().describe('Block Z'),
};
const face = z.enum(['up', 'down', 'north', 'south', 'east', 'west']).optional().describe('Block face to act on (default up)');
const hand = z.enum(['main', 'off']).optional().describe('Hand (default main)');
const modifiers = z
  .array(z.enum(['shift', 'ctrl', 'alt', 'super']))
  .optional()
  .describe('Modifier keys held during the input');

// ---------------------------------------------------------------- status / lifecycle

tool('mc_status', 'status', 'Client state (loading | title | joining | in-world), whether it is ready for input, the open screen, Minecraft/loader versions and the mod list.', {}, {
  readOnly: true,
});

server.registerTool(
  'mc_wait_ready',
  {
    description:
      'Wait until the Minecraft client is running and ready for input (polls mc_status; tolerates the game not having started yet). Call this first after launching the game.',
    inputSchema: { timeoutSeconds: z.number().positive().optional().describe('Give up after this long (default 300)') },
    annotations: { readOnlyHint: true },
  },
  async ({ timeoutSeconds }) => {
    const deadline = Date.now() + (timeoutSeconds ?? 300) * 1000;
    let last = 'not connected';
    while (Date.now() < deadline) {
      const died = launcher.failedStart('client');
      if (died) return errorResult(new Error(died));
      try {
        const s = await mc.call('status', {}, 10_000, 1_000);
        remember(mc, s);
        last = `state=${s.state}`;
        if (s.ready) {
          return text({ ready: true, state: s.state, screen: s.screen?.simpleName ?? null, minecraft: s.minecraftVersion, loader: `${s.loader} ${s.loaderVersion}` });
        }
      } catch (e) {
        last = (e as Error).message;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
    return errorResult(new Error(`Client not ready after ${timeoutSeconds ?? 300}s (last: ${last})`));
  },
);

tool('mc_world_list', 'world.list', 'List singleplayer worlds (levelId folder, display name, gamemode, cheats, which one is open).', {}, { readOnly: true });

tool(
  'mc_world_create',
  'world.create',
  'Create a new singleplayer world and wait until the player is in it. Leaves (and saves) any open world first. Defaults: creative, cheats on, normal terrain. Use flat:true for a predictable test world (flat grass surface; the player stands on top of it).',
  {
    name: z.string().describe('World display name (folder name is derived, made unique)'),
    seed: z.string().optional().describe('Seed; numeric strings are used as-is, other text is hashed like the Create World screen'),
    flat: z.boolean().optional().describe('Superflat world (default false)'),
    gamemode: z.enum(['survival', 'creative', 'adventure', 'spectator', 'hardcore']).optional(),
    difficulty: z.enum(['peaceful', 'easy', 'normal', 'hard']).optional(),
    cheats: z.boolean().optional().describe('Allow commands (default true, false for hardcore)'),
    structures: z.boolean().optional(),
    bonusChest: z.boolean().optional(),
    timeoutMs: z.number().int().positive().optional().describe('Max load time (default 120000)'),
  },
  { timeout: (a) => (a.timeoutMs ?? 120_000) + 15_000 },
);

tool(
  'mc_world_join',
  'world.join',
  'Open an existing singleplayer world (by levelId or exact display name) and wait until in-world. Leaves any open world first.',
  {
    name: z.string().describe('levelId (folder) or display name'),
    timeoutMs: z.number().int().positive().optional(),
    confirmPrompts: z
      .boolean()
      .optional()
      .describe('Answer "experimental settings / make a backup?" prompts with "I know what I\'m doing" (default true; a world with datapack registry content always asks)'),
  },
  { timeout: (a) => (a.timeoutMs ?? 120_000) + 15_000 },
);

tool('mc_world_leave', 'world.leave', 'Save and quit to the title screen (like the pause menu), or disconnect from a multiplayer server. No-op if not in a world.', {}, { timeout: () => 135_000 });

tool('mc_quit', 'quit', 'Quit the game like "Quit Game" (saves the world first). Use it to restart after rebuilding your mod; the gradle run task then finishes.', {});

server.registerTool(
  'mc_crash_report',
  {
    description:
      'Read the newest crash report (crash-reports/*.txt, or a JVM hs_err_pid*.log) of the client or the dedicated server: the cause and stack trace when the game died. Works after the game has exited.',
    inputSchema: {
      side: z.enum(['client', 'server']).optional().describe('Default client'),
      gameDir: z.string().optional().describe('Game directory (e.g. <mod>/fabric/runs/client); only needed if MCTest never reported it in this session'),
      maxLines: z.number().int().positive().optional().describe('Default 150'),
    },
    annotations: { readOnlyHint: true },
  },
  async ({ side, gameDir, maxLines }) => {
    try {
      const client = side === 'server' ? srv : mc;
      let dir = gameDir ?? gameDirs.get(client);
      if (!dir) {
        const s = await client.call('status', {}, 5_000, 0);
        remember(client, s);
        dir = s.gameDir as string;
      }
      const candidates: { path: string; mtime: number }[] = [];
      for (const [folder, match] of [
        [join(dir, 'crash-reports'), /\.txt$/],
        [dir, /^hs_err_pid\d+\.log$/],
      ] as const) {
        const names = await readdir(folder).catch(() => [] as string[]);
        for (const n of names.filter((n) => match.test(n))) {
          const path = join(folder, n);
          candidates.push({ path, mtime: (await stat(path)).mtimeMs });
        }
      }
      if (!candidates.length) return text({ gameDir: dir, crashReport: null, note: 'No crash reports in this game directory' });
      candidates.sort((a, b) => b.mtime - a.mtime);
      const newest = candidates[0];
      const lines = (await readFile(newest.path, 'utf8')).split(/\r?\n/);
      const limit = maxLines ?? 150;
      return text({
        path: newest.path,
        written: new Date(newest.mtime).toISOString(),
        totalLines: lines.length,
        report: lines.slice(0, limit).join('\n') + (lines.length > limit ? `\n... (${lines.length - limit} more lines in the file)` : ''),
      });
    } catch (e) {
      return errorResult(e);
    }
  },
);

tool(
  'mc_gametest_list',
  'gametest.list',
  'List the GameTests registered in the singleplayer world (vanilla, your mod\'s via the loader GameTest API, and datapack test instances).',
  { filter: z.string().optional().describe('Substring of the test id, e.g. your mod id') },
  { readOnly: true },
);

const gametestRun = {
  tests: z.string().optional().describe('Test selector, e.g. "mymod:*" or "mymod:grinder_outputs_dust" (default "*:*")'),
  timeoutSeconds: z.number().positive().optional().describe('Default 300'),
};
tool(
  'mc_gametest_run',
  'gametest.run',
  'Run GameTests in the singleplayer world (like /test run) and wait for them: pass/fail per test with the failure message and position. success = every required test passed. Run it in a flat test world; tests are built near the world spawn.',
  gametestRun,
  { timeout: (a) => (a.timeoutSeconds ?? 300) * 1000 + 15_000 },
);

tool(
  'mc_connect',
  'connect',
  'Join a multiplayer server (like Multiplayer > Direct Connection) and wait until the player is in the world. Leaves any open world first. A dev server running MCTest accepts dev clients (offline mode) and ops them on join. Fails with the disconnect reason if the server refuses the client.',
  {
    address: z.string().optional().describe('host[:port] (default "localhost", port 25565)'),
    timeoutMs: z.number().int().positive().optional().describe('Default 120000'),
  },
  { timeout: (a) => (a.timeoutMs ?? 120_000) + 15_000 },
);

tool('mc_world_delete', 'world.delete', 'Permanently delete a singleplayer world from the game\'s saves folder (not the one currently open).', {
  name: z.string().describe('levelId (folder) or exact display name'),
});

// ---------------------------------------------------------------- observation

server.registerTool(
  'mc_screenshot',
  {
    description:
      'Capture the game window. Returns the image (scaled down to maxSize, as JPEG, so large windows arrive intact) and the path of the full-resolution PNG (saved under <gameDir>/mctest/screenshots/).',
    inputSchema: {
      name: z.string().optional().describe('File name (letters, digits, . _ -); default is a timestamp'),
      maxSize: z.number().int().min(64).max(4096).optional().describe('Longest side of the returned image in pixels (default 1280; the PNG on disk is always full size)'),
    },
    annotations: { readOnlyHint: true },
  },
  async ({ name, maxSize }) => {
    try {
      const r = await mc.call('screenshot', clean({ name, maxSize, inline: true }));
      const { inline, ...info } = r;
      // Older mods without inline support: fall back to the PNG file.
      const image = inline
        ? { type: 'image' as const, data: inline.data as string, mimeType: inline.mimeType as string }
        : { type: 'image' as const, data: (await readFile(r.path)).toString('base64'), mimeType: 'image/png' };
      if (inline) Object.assign(info, { sent: `${inline.width}x${inline.height} ${inline.mimeType}` });
      return withErrors(mc, { content: [image, { type: 'text' as const, text: JSON.stringify(info) }] });
    } catch (e) {
      return errorResult(e);
    }
  },
);

tool(
  'mc_window_resize',
  'window.resize',
  'Resize the game window (leaves fullscreen), e.g. to test a GUI at different sizes and GUI scales. Returns the new size and GUI scale.',
  {
    width: z.number().int().min(320).describe('Window width in screen pixels'),
    height: z.number().int().min(240).describe('Window height in screen pixels'),
  },
);

tool(
  'mc_snapshot',
  'snapshot',
  'Inspect the open screen: class/title, every widget (id "index:Class:text", type, text, x/y/w/h in GUI coordinates, active, focused, visible, checkbox/cycle values) and, for container screens, the slots (menu index, item, count, components, x/y) and carried stack. Empty slots are omitted unless includeEmptySlots.',
  {
    includeEmptySlots: z.boolean().optional().describe('Include empty container slots (default false)'),
    includeHidden: z.boolean().optional().describe('Include hidden / scrolled-out widgets (default false)'),
  },
  {
    readOnly: true,
    transform: (r, a) => {
      if (!a.includeHidden) r.widgets = r.widgets.filter((w: any) => w.visible);
      if (r.container && !a.includeEmptySlots) {
        r.container.emptySlots = r.container.slots.filter((s: any) => !s.item).length;
        r.container.slots = r.container.slots.filter((s: any) => s.item);
      }
      return r;
    },
  },
);

tool(
  'mc_player',
  'player',
  'Local player: position, rotation, velocity, dimension, gamemode, health, food, xp, what the crosshair targets, inventory (slot numbers: 0-8 hotbar, 9-35 main, 36+ armor/offhand), effects and attributes.',
  {},
  { readOnly: true },
);

tool(
  'mc_block',
  'block',
  'Block id, state properties and block-entity NBT at a position. In singleplayer reads the integrated server (authoritative, includes e.g. chest contents); source:"client" reads what the client has.',
  { ...intXyz, source: z.enum(['auto', 'server', 'client']).optional() },
  { readOnly: true },
);

tool(
  'mc_entities',
  'entities',
  'Entities near the player, nearest first: network id (use with attack/interact), uuid, type, name, position, distance, health, item for dropped items.',
  {
    radius: z.number().positive().max(256).optional().describe('Search radius in blocks (default 16)'),
    type: z.string().optional().describe('Entity type id, e.g. "pig" or "mymod:robot"'),
    limit: z.number().int().positive().optional().describe('Max results (default 50)'),
  },
  { readOnly: true },
);

tool(
  'mc_logs',
  'logs',
  'Game log lines captured since startup (each has seq, level, logger, thread, message; exceptions include the stack). Pass the returned "next" as since to get only new lines.',
  {
    since: z.number().int().optional().describe('Only lines with seq > since'),
    level: z.enum(['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']).optional().describe('Minimum level'),
    contains: z.string().optional().describe('Case-insensitive filter on logger/message/exception'),
    limit: z.number().int().positive().optional().describe('Max lines, newest kept (default 200)'),
  },
  { readOnly: true },
);

tool('mc_tooltip', 'tooltip', 'Tooltip lines of an item. slot = menu slot index (as in mc_snapshot) if a container screen is open, otherwise a player inventory index. Pass modifiers (e.g. ["shift"]) for mods that show extra lines while shift/ctrl/alt is held.', {
  slot: z.number().int(),
  advanced: z.boolean().optional().describe('Advanced tooltips (item id, durability, component count)'),
  modifiers: z
    .array(z.enum(['shift', 'ctrl', 'alt', 'super']))
    .optional()
    .describe('Modifier keys held while the tooltip is built (Minecraft.hasShiftDown() etc. report them as down)'),
}, { readOnly: true });

tool('mc_keybinds', 'keybinds', 'All key mappings, including other mods\' (name like "key.forward", category, bound key, whether down).', {}, { readOnly: true });

// ---------------------------------------------------------------- waiting

tool(
  'mc_wait_for',
  'waitFor',
  'Wait until a condition holds, checked every client tick. Conditions: "screen:<Class>" (simple/full/superclass name, or "screen:none"), "slot:<menuIndex>=<item|empty>", "block:<x>,<y>,<z>=<blockId>", "ticks:<n>", "state:<loading|title|joining|in-world>", "log:<text>" (a new log line containing text), "chat:<text>" (a new chat/system message containing text), "entity:<type>[@radius]". Fails with the last observed value on timeout.',
  {
    condition: z.string(),
    timeoutTicks: z.number().int().positive().optional().describe('Default 100 (5 s)'),
  },
  {
    readOnly: true,
    timeout: (a) => {
      const m = /^ticks:(\d+)/.exec(a.condition.trim());
      return ticksTimeout(Math.max(a.timeoutTicks ?? 100, m ? Number(m[1]) : 0), 100);
    },
  },
);

// ---------------------------------------------------------------- GUI input

tool('mc_click', 'click', 'Click at GUI-scaled coordinates (as reported by mc_snapshot) through the real mouse handler.', {
  x: z.number(),
  y: z.number(),
  button: z.enum(['left', 'right', 'middle']).optional(),
  modifiers,
});

tool('mc_click_widget', 'clickWidget', 'Click the centre of a widget from mc_snapshot. An id from an older snapshot still works if its "Class:text" part is unique.', {
  id: z.string(),
  button: z.enum(['left', 'right', 'middle']).optional(),
  modifiers,
});

tool(
  'mc_click_slot',
  'clickSlot',
  'Click a slot of the open container screen by menu slot index (from mc_snapshot; -999 = outside the window). button 0 = left, 1 = right (for SWAP: hotbar 0-8, 40 = offhand). Returns the predicted slot and cursor stacks.',
  {
    index: z.number().int(),
    button: z.number().int().optional(),
    clickType: z.enum(['PICKUP', 'QUICK_MOVE', 'SWAP', 'CLONE', 'THROW', 'QUICK_CRAFT', 'PICKUP_ALL']).optional().describe('Default PICKUP; QUICK_MOVE = shift-click'),
  },
);

tool(
  'mc_key',
  'key',
  'Press a keyboard key through the real keyboard handler (screens and keybinds react as to a real key). Names: a-z, 0-9, escape, enter, backspace, tab, space, up/down/left/right, f1-f12, left_shift, ... In-world keybinds (e.g. "e" opens the inventory) apply on the next tick. action "press" keeps the key held (also for hasShiftDown()-style polling, e.g. hold-shift tooltips in screenshots) until action "release".',
  {
    key: z.string(),
    modifiers,
    action: z.enum(['tap', 'press', 'release']).optional().describe('Default tap (press + release); press holds until release'),
  },
);

tool('mc_type', 'type', 'Type text into the focused widget of the open screen (e.g. an edit box or chat).', { text: z.string() });

tool('mc_scroll', 'scroll', 'Scroll the mouse wheel (positive = up), optionally at GUI coordinates. In-world it changes the hotbar slot.', {
  amount: z.number(),
  x: z.number().optional(),
  y: z.number().optional(),
});

tool(
  'mc_hover',
  'hover',
  'Move the mouse without clicking, e.g. so mc_screenshot shows a slot tooltip. Give a container slot (menu index, as in mc_snapshot) or GUI-scaled x/y. For hold-shift tooltips, mc_key left_shift action "press" first, and "release" after.',
  {
    slot: z.number().int().optional(),
    x: z.number().optional(),
    y: z.number().optional(),
  },
);

tool('mc_close_screen', 'closeScreen', 'Close the open screen (like pressing Escape).', {});

// ---------------------------------------------------------------- gameplay

tool(
  'mc_command',
  'command',
  'Run a command as the player (leading "/" optional) and return the chat lines received over the next few ticks.',
  { command: z.string(), ticks: z.number().int().positive().optional().describe('How long to collect output (default 5)') },
  { timeout: (a) => ticksTimeout(a.ticks, 5) },
);

tool('mc_chat', 'chat', 'Send a chat message.', { message: z.string() });

tool(
  'mc_keybind',
  'keybind',
  'Drive a key mapping by name, independent of what key it is bound to; works for other mods\' keybinds too. Names: forward, back, left, right, jump, sneak, sprint, inventory, drop, use, attack, or any "key.*" from mc_keybinds. Actions: tap, press, release, hold (for `ticks`). Walking: hold forward for 20 ticks = about 4.3 blocks.',
  {
    name: z.string(),
    action: z.enum(['tap', 'press', 'release', 'hold']).optional(),
    ticks: z.number().int().positive().optional().describe('Duration for hold'),
  },
  { timeout: (a) => ticksTimeout(a.ticks, 1) },
);

tool('mc_select_slot', 'selectSlot', 'Select a hotbar slot (0-8).', { slot: z.number().int().min(0).max(8) });

tool('mc_look', 'look', 'Set the player\'s view: yaw (0 = south/+Z, 90 = west, 180 = north, -90 = east) and pitch (-90 up .. 90 down).', {
  yaw: z.number(),
  pitch: z.number(),
});

tool('mc_look_at', 'lookAt', 'Turn the player to look at a point (use block centre: x+0.5, y+0.5, z+0.5).', xyz);

tool('mc_teleport', 'teleport', 'Teleport the player (runs /tp, needs cheats) and wait until the client arrives.', {
  ...xyz,
  yaw: z.number().optional(),
  pitch: z.number().optional(),
});

tool(
  'mc_use_on',
  'useOn',
  'Right-click a block face with the held item, like a player: places blocks (onto the given face), opens GUIs (chests, machines), uses tools on blocks. The server checks reach (~4.5 blocks).',
  { ...intXyz, face, hand },
);

tool(
  'mc_use_item',
  'useItem',
  'Right-click with the held item without targeting a block (throw, eat, drink, draw a bow). With ticks > 0 keeps using for that long (eating takes 32 ticks), then releases.',
  { hand, ticks: z.number().int().min(0).optional() },
  { timeout: (a) => ticksTimeout(a.ticks, 0) },
);

tool(
  'mc_break_block',
  'breakBlock',
  'Mine a block like holding left-click: instant in creative, real dig time in survival (depends on tool). Fails if not broken within timeoutTicks (out of reach, unbreakable, protected).',
  { ...intXyz, face, timeoutTicks: z.number().int().positive().optional().describe('Default 600') },
  { timeout: (a) => ticksTimeout(a.timeoutTicks, 600) },
);

tool('mc_attack_entity', 'attackEntity', 'Left-click (attack) an entity by its id from mc_entities. Must be within reach.', { id: z.number().int() });

tool(
  'mc_interact_entity',
  'interactEntity',
  'Right-click an entity by id from mc_entities (trade with villagers, milk cows, ride, use mod entities).',
  { id: z.number().int(), hand },
);

tool('mc_reload_resources', 'reloadResources', 'Reload resource packs (like F3+T); picks up changed textures, models, lang files and so on.', {}, {
  timeout: () => 180_000,
});

// ---------------------------------------------------------------- time control (singleplayer)

tool('mc_time_status', 'time.status', 'Whether the world is frozen / in lockstep, and the current game time.', {}, { readOnly: true });
tool('mc_time_freeze', 'time.freeze', 'Freeze the world (like /tick freeze, but no cheats needed): mobs, blocks, crops, fluids stop; the player can still act.', {});
tool('mc_time_unfreeze', 'time.unfreeze', 'Unfreeze the world (also turns lockstep off).', {});
tool(
  'mc_time_step',
  'time.step',
  'Advance a frozen world by exactly N ticks, then wait for it to finish.',
  { ticks: z.number().int().positive() },
  { timeout: (a) => ticksTimeout(a.ticks, 1) },
);
tool(
  'mc_lockstep',
  'time.lockstep',
  'Lockstep mode: the world stays frozen except while an MCTest action is running (walking, mining, fighting, waiting), so nothing happens while the agent is thinking. Recommended for survival play.',
  { enabled: z.boolean().optional().describe('Default true') },
);

// ---------------------------------------------------------------- player skills

tool(
  'mc_goto',
  'goto',
  'Walk to a position (or an entity) like a player: A* pathfinding over loaded terrain, turning, sprinting, jumping up blocks, dropping up to 3 blocks, swimming; replans when stuck. Never teleports. Returns arrived:false with a reason when it cannot get there.',
  {
    x: z.number().optional(),
    y: z.number().optional(),
    z: z.number().optional(),
    entity: z.number().int().optional().describe('Entity id to walk to instead of x/y/z'),
    range: z.number().positive().optional().describe('Arrival distance (default 1, or 2 for an entity)'),
    sprint: z.boolean().optional().describe('Sprint on straight stretches (default true)'),
    timeoutTicks: z.number().int().positive().optional().describe('Default 2400 (2 minutes)'),
  },
  { timeout: (a) => ticksTimeout(a.timeoutTicks, 2400) },
);

tool('mc_select_item', 'selectItem', 'Hold an item: selects it on the hotbar, or swaps it in from the main inventory.', {
  item: z.string().describe('Item id, e.g. "bread" or "mymod:wrench"'),
});

tool(
  'mc_scan_blocks',
  'scanBlocks',
  'Survey the blocks around the player: per block type the count and nearest position, nearest first. Filter with ids or tags ("#minecraft:logs").',
  {
    radius: z.number().int().positive().max(48).optional().describe('Default 16'),
    blocks: z.array(z.string()).optional().describe('Only these block ids / #tags'),
    limit: z.number().int().positive().optional().describe('Max block types (default 40)'),
  },
  { readOnly: true },
);

tool(
  'mc_collect',
  'collect',
  'Gather blocks like a player: walk to the nearest matching block, pick the best tool from the inventory, mine it with real dig time, walk over the drop; repeat. Keeps going until `count` blocks have actually been picked up (drops that get away do not count). Returns blocks mined, collected, and the inventory change.',
  {
    block: z.string().describe('Block id or #tag, e.g. "#minecraft:logs", "stone", "iron_ore"'),
    count: z.number().int().positive().optional().describe('Default 1'),
    radius: z.number().int().positive().max(48).optional().describe('Search radius (default 24)'),
    timeoutTicks: z.number().int().positive().optional().describe('Default 6000'),
  },
  { timeout: (a) => ticksTimeout(a.timeoutTicks, 6000) * 2 },
);

tool(
  'mc_fight',
  'fight',
  'Melee an entity like a player: equips a sword/axe, closes in, aims, and attacks only when the attack cooldown is full. Target by id, by type, or (neither) the nearest hostile mob.',
  {
    id: z.number().int().optional(),
    type: z.string().optional().describe('Entity type, e.g. "zombie"'),
    radius: z.number().positive().optional().describe('Search radius when picking by type (default 16)'),
    fleeHealth: z.number().optional().describe('Stop when your health drops to this (default 0 = never)'),
    autoWeapon: z.boolean().optional().describe('Equip the best sword/axe first (default true)'),
    timeoutTicks: z.number().int().positive().optional().describe('Default 600'),
  },
  { timeout: (a) => ticksTimeout(a.timeoutTicks, 600) },
);

tool(
  'mc_place',
  'place',
  'Place a block from the inventory like a player: holds it and right-clicks a face of a solid neighbour (any side). Without a position it picks the nearest free spot around you that is not where you stand.',
  {
    item: z.string().describe('Item id of the block to place, e.g. "crafting_table", "torch", "mymod:machine"'),
    x: z.number().int().optional(),
    y: z.number().int().optional(),
    z: z.number().int().optional(),
  },
);

tool(
  'mc_dig',
  'dig',
  'Dig like a player: "stairs" goes one block down per step (to reach stone/ores), "up" digs a staircase upward (e.g. out of a hole or a mine), "tunnel" digs a 1x2 corridor forward. Uses the best tool, walks into the gap each step, picks up drops. Stops at lava/water, unbreakable blocks, open drops, or when the `until` block (id or #tag) shows up.',
  {
    mode: z.enum(['stairs', 'up', 'tunnel']).optional().describe('Default stairs (down)'),
    length: z.number().int().positive().max(64).optional().describe('Steps (default 8)'),
    direction: z.enum(['north', 'south', 'east', 'west']).optional().describe('Default: the way you are facing'),
    until: z.string().optional().describe('Stop when this block appears, e.g. "stone" or "#minecraft:iron_ores"'),
  },
  { timeout: (a) => (a.length ?? 8) * 30_000 + 15_000 },
);

tool(
  'mc_craft',
  'craft',
  'Craft through the recipe book like a player: 2x2 recipes in the inventory, 3x3 at a crafting table (walks to one within 24 blocks and opens it). Only recipes the player has unlocked (they unlock when you obtain an ingredient).',
  {
    item: z.string().describe('Result item id, e.g. "oak_planks", "crafting_table", "wooden_pickaxe"'),
    times: z.number().int().positive().optional().describe('How many times to craft (default 1)'),
    keepOpen: z.boolean().optional().describe('Leave the crafting screen open afterwards'),
  },
  { timeout: () => 180_000 },
);

// ---------------------------------------------------------------- dedicated server

const serverOpts = { client: srv };

server.registerTool(
  'mc_server_wait_ready',
  {
    description:
      'Wait until the dedicated server (running MCTest) has finished starting and is accepting players (polls its status; tolerates the server not having started yet). Call this after launching ./gradlew :<loader>:runServer.',
    inputSchema: { timeoutSeconds: z.number().positive().optional().describe('Give up after this long (default 300)') },
    annotations: { readOnlyHint: true },
  },
  async ({ timeoutSeconds }) => {
    const deadline = Date.now() + (timeoutSeconds ?? 300) * 1000;
    let last = 'not connected';
    while (Date.now() < deadline) {
      const died = launcher.failedStart('server');
      if (died) return errorResult(new Error(died));
      try {
        const s = await srv.call('status', {}, 10_000, 1_000);
        remember(srv, s);
        last = `state=${s.state}`;
        if (s.ready) {
          return text({ ready: true, port: s.port, onlineMode: s.onlineMode, minecraft: s.minecraftVersion, loader: `${s.loader} ${s.loaderVersion}`, players: s.players });
        }
      } catch (e) {
        last = (e as Error).message;
      }
      await new Promise((r) => setTimeout(r, 1000));
    }
    return errorResult(new Error(`Server not ready after ${timeoutSeconds ?? 300}s (last: ${last})`));
  },
);

tool('mc_server_status', 'status', 'Dedicated server state (starting | running | stopping), game port, online mode, players online, tick count, ms per tick, frozen, dimensions, versions and mod list.', {}, {
  ...serverOpts,
  readOnly: true,
});

tool(
  'mc_server_command',
  'command',
  'Run a command on the dedicated server as the console (full permissions, no player needed) and return its output, success flag and result value. Use it to set up tests (op, gamerule, setblock, give <player>, summon, execute as <player> run ...).',
  {
    command: z.string().describe('Command, with or without the leading slash'),
    ticks: z.number().int().min(0).optional().describe('Also collect output sent over this many following ticks (default 0)'),
  },
  { ...serverOpts, timeout: (a) => ticksTimeout(a.ticks, 0) },
);

tool('mc_server_players', 'players', 'Players on the dedicated server as the server sees them: name, uuid, op, ping, position, dimension, gamemode, health, food, xp, inventory, open menu class, effects.', {}, {
  ...serverOpts,
  readOnly: true,
});

tool(
  'mc_server_block',
  'block',
  'Read a block on the dedicated server (authoritative): id, state properties and full block-entity NBT. Compare with mc_block {source:"client"} to check client sync.',
  { ...intXyz, dimension: z.string().optional().describe('e.g. "the_nether" (default overworld)') },
  { ...serverOpts, readOnly: true },
);

tool(
  'mc_server_entities',
  'entities',
  'Entities on the dedicated server: around a player (by name), around a point (x,y,z), or every loaded entity in a dimension. Sorted by distance when there is a centre.',
  {
    player: z.string().optional().describe('Centre on this player (uses their dimension)'),
    x: z.number().optional(),
    y: z.number().optional(),
    z: z.number().optional(),
    dimension: z.string().optional().describe('Default overworld'),
    radius: z.number().positive().optional().describe('Default 16 (ignored without a centre)'),
    type: z.string().optional().describe('Entity type id, e.g. "zombie"'),
    limit: z.number().int().positive().optional().describe('Default 50'),
  },
  { ...serverOpts, readOnly: true },
);

tool(
  'mc_server_wait_for',
  'waitFor',
  'Wait on the dedicated server until a condition holds (checked every server tick). Conditions: "ticks:<n>", "log:<text>", "player:<name>" (online), "noplayer:<name>" (gone), "players:<n>" (at least n online), "block:<x>,<y>,<z>=<id>[@dimension]", "entity:<type>[@dimension]" (loaded anywhere).',
  {
    condition: z.string(),
    timeoutTicks: z.number().int().positive().optional().describe('Default 100 (5 s)'),
  },
  { ...serverOpts, timeout: (a) => ticksTimeout(a.timeoutTicks, 100), readOnly: true },
);

tool(
  'mc_server_logs',
  'logs',
  'Captured dedicated-server log lines (with exception stacks), newest last. Pass the returned "next" as since to get only newer lines.',
  {
    since: z.number().int().optional(),
    level: z.enum(['TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR', 'FATAL']).optional().describe('Minimum level'),
    contains: z.string().optional(),
    limit: z.number().int().positive().optional().describe('Default 200'),
  },
  { ...serverOpts, readOnly: true },
);

tool('mc_server_time_status', 'time.status', 'Whether the dedicated server is frozen or stepping, its tick rate and game time.', {}, { ...serverOpts, readOnly: true });
tool('mc_server_time_freeze', 'time.freeze', "Freeze the dedicated server's worlds (like /tick freeze): mobs, blocks, crops, fluids stop; players can still move.", {}, serverOpts);
tool('mc_server_time_unfreeze', 'time.unfreeze', "Unfreeze the dedicated server's worlds.", {}, serverOpts);
tool(
  'mc_server_time_step',
  'time.step',
  'Advance a frozen dedicated server by exactly N ticks and wait until done.',
  { ticks: z.number().int().positive().describe('Ticks to advance') },
  { ...serverOpts, timeout: (a) => ticksTimeout(a.ticks, 1) },
);

tool(
  'mc_server_gametest_list',
  'gametest.list',
  'List the GameTests registered on the dedicated server.',
  { filter: z.string().optional() },
  { ...serverOpts, readOnly: true },
);
tool(
  'mc_server_gametest_run',
  'gametest.run',
  'Run GameTests on the dedicated server (like /test run) and wait for them: pass/fail per test with the failure message and position.',
  gametestRun,
  { ...serverOpts, timeout: (a) => (a.timeoutSeconds ?? 300) * 1000 + 15_000 },
);

tool('mc_server_stop', 'stop', 'Save and stop the dedicated server (like /stop). Connected clients are disconnected.', {}, serverOpts);

server.registerTool(
  'mc_server_call',
  {
    description: "Call any method of the dedicated server's MCTest API directly with raw params.",
    inputSchema: { method: z.string(), params: z.record(z.string(), z.unknown()).optional(), timeoutMs: z.number().int().positive().optional() },
  },
  async ({ method, params, timeoutMs }) => {
    try {
      return withErrors(srv, text(await srv.call(method, params ?? {}, timeoutMs ?? 60_000)));
    } catch (e) {
      return errorResult(e);
    }
  },
);

// ---------------------------------------------------------------- launching / closing games

const launchShape = {
  loader: z
    .enum(['fabric', 'neoforge', 'forge'])
    .describe('Which loader subproject to run (:<loader>:runClient / :<loader>:runServer). For a single-loader project pass task, e.g. "runClient"'),
  projectDir: z
    .string()
    .optional()
    .describe('Gradle project of the mod (default MCTEST_PROJECT_DIR, else the directory this MCP server runs in)'),
  task: z.string().optional().describe('Gradle task to run instead of :<loader>:runClient / :<loader>:runServer'),
  args: z.array(z.string()).optional().describe('Extra gradle arguments'),
};

function registerLaunch(name: string, side: Side, waitTool: string) {
  server.registerTool(
    name,
    {
      description:
        `Start a Minecraft ${side === 'client' ? 'client' : 'dedicated server'} with gradle (./gradlew :<loader>:${side === 'client' ? 'runClient' : 'runServer'}) and track it, so mc_close_all ` +
        `(and the end of this MCP session) closes it again. Returns right away; then call ${waitTool}. Gradle output goes to <projectDir>/build/mctest-logs/.`,
      inputSchema: launchShape,
      annotations: { readOnlyHint: false, openWorldHint: false },
    },
    async ({ loader, projectDir, task, args }) => {
      try {
        const l = await launcher.launch(side, loader, projectDir, task, args ?? []);
        return text({ launched: l.task, side, projectDir: l.projectDir, log: l.log, next: `call ${waitTool}`, reminder: 'call mc_close_all when testing is done' });
      } catch (e) {
        return errorResult(e);
      }
    },
  );
}
registerLaunch('mc_launch', 'client', 'mc_wait_ready');
registerLaunch('mc_server_launch', 'server', 'mc_server_wait_ready');

server.registerTool(
  'mc_close_all',
  {
    description:
      'Close every Minecraft client and dedicated server: quits them through MCTest (worlds are saved), waits for them to exit and kills any that hang, ' +
      'plus the gradle runs that started them. Call this whenever you have finished testing, also after a failed test.',
    inputSchema: {
      all: z.boolean().optional().describe('Also close MCTest games this session did not launch itself (default true)'),
      graceSeconds: z.number().positive().optional().describe('How long to wait for a clean exit before killing (default 60)'),
    },
    annotations: { readOnlyHint: false, destructiveHint: true, openWorldHint: false },
  },
  async ({ all, graceSeconds }) => {
    try {
      const report = await launcher.closeAll(all ?? true, (graceSeconds ?? 60) * 1000);
      return text({ closed: report.length ? report : ['nothing was running'] });
    } catch (e) {
      return errorResult(e);
    }
  },
);

// ---------------------------------------------------------------- escape hatch

server.registerTool(
  'mc_call',
  {
    description: 'Call any MCTest method directly with raw params (for methods without a dedicated tool).',
    inputSchema: { method: z.string(), params: z.record(z.string(), z.unknown()).optional(), timeoutMs: z.number().int().positive().optional() },
  },
  async ({ method, params, timeoutMs }) => {
    try {
      return withErrors(mc, text(await mc.call(method, params ?? {}, timeoutMs ?? 60_000)));
    } catch (e) {
      return errorResult(e);
    }
  },
);

// Close the games this session launched when the MCP client goes away (stdin closes or we are signalled).
let shuttingDown = false;
async function shutdown() {
  if (shuttingDown) return;
  shuttingDown = true;
  if (launcher.hasLiveGames()) {
    // Quick clean quit, then kill; MCP clients do not wait long for a server to exit.
    await Promise.race([launcher.closeAll(false, 8_000).catch(() => undefined), new Promise((r) => setTimeout(r, 15_000))]);
  }
  process.exit(0);
}
process.on('exit', () => launcher.killAllSync());
process.on('SIGINT', shutdown);
process.on('SIGTERM', shutdown);
process.on('SIGHUP', shutdown);
process.stdin.on('end', shutdown);
process.stdin.on('close', shutdown);

const transport = new StdioServerTransport();
transport.onclose = () => void shutdown();
await server.connect(transport);
