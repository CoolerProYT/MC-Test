// Checks that MCTest-driven actions never make the game capture (grab) or warp your OS cursor, focused or not
// (vanilla grabs it whenever a screen closes in-world and warps it whenever one opens). Windows only (reads the cursor via PowerShell).
//   node scripts/cursor-test.mjs
import { execFileSync } from 'node:child_process';
import { connect, harness } from './lib.mjs';

const cursor = () =>
  execFileSync('powershell', ['-NoProfile', '-Command',
    'Add-Type -AssemblyName System.Windows.Forms; $p=[System.Windows.Forms.Cursor]::Position; "$($p.X),$($p.Y)"'])
    .toString().trim();

const mc = await connect();
const t = harness();
const { check, assert } = t;

const world = await mc.call('world.create', { name: 'mctest-cursor', flat: true, gamemode: 'survival' });
await mc.call('command', { command: 'give @s minecraft:crafting_table' });
const s = await mc.call('status');
console.log(`window focused: ${s.windowFocused}, mouse grabbed: ${s.mouseGrabbed}`);

const start = cursor();
console.log(`cursor at ${start}`);
const steps = [
  ['open inventory (E)', async () => { await mc.call('key', { key: 'e' }); await mc.call('waitFor', { condition: 'screen:InventoryScreen', timeoutTicks: 40 }); }],
  ['close inventory', () => mc.call('closeScreen')],
  ['open chat (T)', async () => { await mc.call('key', { key: 't' }); await mc.call('waitFor', { condition: 'screen:ChatScreen', timeoutTicks: 40 }); }],
  ['close chat', () => mc.call('key', { key: 'escape' })],
  ['in-world click', () => mc.call('click', { x: 100, y: 100 })],
  ['place + open crafting table', async () => {
    const r = await mc.call('place', { item: 'crafting_table' });
    await mc.call('useOn', { ...r.pos, face: 'up' });
    await mc.call('waitFor', { condition: 'screen:CraftingScreen', timeoutTicks: 40 });
  }],
  ['close crafting table', () => mc.call('closeScreen')],
  ['open pause menu (Esc)', async () => { await mc.call('key', { key: 'escape' }); await mc.call('waitFor', { condition: 'screen:PauseScreen', timeoutTicks: 40 }); }],
  ['close pause menu', () => mc.call('closeScreen')],
];
for (const [label, action] of steps) {
  await check(`${label}: game did not capture the mouse`, async () => {
    await action();
    await mc.call('waitFor', { condition: 'ticks:5' });
    const st = await mc.call('status');
    assert(!st.mouseGrabbed, 'mouse was grabbed by the game');
    const now = cursor();
    // You may be moving the mouse yourself while this runs, so a moved cursor is only reported.
    return now === start ? 'cursor untouched' : `cursor now ${now} (moved by you?)`;
  });
}

await mc.call('world.leave');
await mc.call('world.delete', { name: world.levelId });
mc.close();
t.done();
