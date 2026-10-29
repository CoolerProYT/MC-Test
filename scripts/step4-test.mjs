// Step 4 checks: command, player, block, entities, teleport, lookAt, waitFor, logs.
// Creates (and afterwards deletes) its own flat test world, so it can start from any screen:
//   node scripts/step4-test.mjs
import { connect, harness } from './lib.mjs';

const mc = await connect();
const t = harness();
const { check, assert } = t;

// A fresh flat world keeps line-of-sight checks independent of terrain.
const world = await mc.call('world.create', { name: 'mctest-step4', flat: true, gamemode: 'survival' });
console.log(`world ${world.levelId} loaded in ${world.loadMs}ms`);

await mc.call('closeScreen');
await check('command: gamemode survival', async () => {
  const r = await mc.call('command', { command: '/gamemode survival' });
  return r.output.map((l) => l.text).join(' | ') || '(already survival)';
});
await check('command: unknown command reports the error', async () => {
  const r = await mc.call('command', { command: 'notacommand' });
  assert(r.output.some((l) => /Unknown or incomplete command/.test(l.text)), JSON.stringify(r.output));
});

const me = await mc.call('player');
const { x, y, z } = me.blockPos;
const chest = { x: x + 3, y, z };

await check('command: setblock chest with items', async () => {
  const r = await mc.call('command', {
    command: `setblock ${chest.x} ${chest.y} ${chest.z} minecraft:chest[facing=west]{Items:[{Slot:0b,id:"minecraft:diamond",Count:5b}]}`,
  });
  assert(r.output.some((l) => /Changed the block/.test(l.text)), JSON.stringify(r.output));
});
await check('waitFor block:...=chest', async () => {
  const r = await mc.call('waitFor', { condition: `block:${chest.x},${chest.y},${chest.z}=chest` });
  return `${r.ticks} tick(s)`;
});
await check('block (server) has state + block entity NBT', async () => {
  const r = await mc.call('block', chest);
  assert(r.id === 'minecraft:chest' && r.properties.facing === 'west', JSON.stringify(r));
  assert(r.source === 'server' && /minecraft:diamond/.test(r.blockEntity.nbt), r.blockEntity?.nbt);
  return r.blockEntity.nbt;
});

await check('entities finds a summoned, named pig', async () => {
  await mc.call('command', { command: `summon minecraft:pig ${x} ${y} ${z + 4} {CustomName:'{"text":"Bacon"}',NoAI:1b}` });
  const r = await mc.call('entities', { radius: 8, type: 'pig' });
  assert(r.entities.some((e) => e.name === 'Bacon'), JSON.stringify(r.entities.map((e) => e.name)));
  return r.entities.map((e) => `${e.type} ${e.name} d=${e.distance.toFixed(1)} hp=${e.health}`);
});

await check('teleport', async () => {
  const r = await mc.call('teleport', { x: x + 0.5, y: y + 1, z: z + 0.5 });
  assert(r.arrived, JSON.stringify(r));
  return r.pos;
});
await check('lookAt the chest, player.lookingAt reports it', async () => {
  await mc.call('lookAt', { x: chest.x + 0.5, y: chest.y + 0.5, z: chest.z + 0.5 });
  await mc.call('waitFor', { condition: 'ticks:2' });
  const p = await mc.call('player');
  assert(p.lookingAt?.block === 'minecraft:chest', JSON.stringify(p.lookingAt));
});
await check('player has gamemode, health, attributes', async () => {
  const p = await mc.call('player');
  assert(p.gamemode === 'survival' && p.health > 0 && p.attributes.length > 5, JSON.stringify({ gm: p.gamemode, attrs: p.attributes.length }));
  return `${p.dimension} hp=${p.health} food=${p.food} attrs=${p.attributes.length}`;
});

await check('key e + waitFor screen:InventoryScreen', async () => {
  await mc.call('command', { command: 'clear' });
  await mc.call('command', { command: 'give @s minecraft:golden_apple 3' });
  await mc.call('key', { key: 'e' });
  return `${(await mc.call('waitFor', { condition: 'screen:InventoryScreen', timeoutTicks: 40 })).ticks} tick(s)`;
});
await check('waitFor screen:<superclass>', () => mc.call('waitFor', { condition: 'screen:AbstractContainerScreen' }).then(() => undefined));
await check('hover a slot moves the mouse without clicking', async () => {
  await mc.call('waitFor', { condition: 'slot:36=golden_apple' });
  const r = await mc.call('hover', { slot: 36 });
  const snap = await mc.call('snapshot');
  assert(r.item?.item === 'minecraft:golden_apple' && !snap.container.carried, JSON.stringify({ r, carried: snap.container.carried }));
});
await check('waitFor slot:36=golden_apple, shift-click, slot:36=empty', async () => {
  await mc.call('waitFor', { condition: 'slot:36=golden_apple' });
  await mc.call('clickSlot', { index: 36, clickType: 'QUICK_MOVE' });
  await mc.call('waitFor', { condition: 'slot:36=empty' });
});
await check('waitFor times out with a useful message', async () => {
  try {
    await mc.call('waitFor', { condition: 'slot:36=minecraft:diamond', timeoutTicks: 10 });
  } catch (e) {
    assert(/timed out after 10 ticks.*last observed: empty/.test(e.message), e.message);
    return e.message;
  }
  throw new Error('did not time out');
});
await check('closeScreen + waitFor screen:none', async () => {
  await mc.call('closeScreen');
  await mc.call('waitFor', { condition: 'screen:none', timeoutTicks: 20 });
});
await check('waitFor ticks:20 takes about a second', async () => {
  const start = Date.now();
  await mc.call('waitFor', { condition: 'ticks:20' });
  const ms = Date.now() - start;
  assert(ms > 800 && ms < 2500, `${ms}ms`);
  return `${ms}ms`;
});

await check('waitFor log:<text> sees a new log line', async () => {
  const waiting = mc.call('waitFor', { condition: 'log:mctest-wait-marker', timeoutTicks: 100 });
  await mc.call('command', { command: 'say mctest-wait-marker' });
  const r = await waiting;
  return r.observed;
});
await check('waitFor entity:<type>@radius', async () => {
  const waiting = mc.call('waitFor', { condition: 'entity:sheep@10', timeoutTicks: 100 });
  await mc.call('command', { command: 'summon minecraft:sheep ~2 ~ ~2 {NoAI:1b}' });
  return (await waiting).observed;
});
await check('logs: since/next returns only new lines', async () => {
  const { next } = await mc.call('logs', { limit: 1 });
  await mc.call('command', { command: 'say mctest-log-marker' });
  const r = await mc.call('logs', { since: next });
  assert(r.lines.every((l) => l.seq > next), 'old line returned');
  assert(r.lines.some((l) => /mctest-log-marker/.test(l.message)), JSON.stringify(r.lines.map((l) => l.message)));
  assert(!r.lines.some((l) => /handshake/.test(l.message)), 'websocket handshake noise in logs');
  return `${r.lines.length} new line(s)`;
});
await check('logs: level filter + exceptions carry stacks', async () => {
  const r = await mc.call('logs', { level: 'WARN', limit: 500 });
  assert(r.lines.every((l) => ['WARN', 'ERROR', 'FATAL'].includes(l.level)), 'level filter leaked');
  const withStack = r.lines.find((l) => l.stack);
  return withStack ? `${withStack.exception.slice(0, 80)}...` : '(no exceptions logged this session)';
});

await check('key press holds shift for hasShiftDown() polling until release', async () => {
  const pressed = await mc.call('key', { key: 'left_shift', action: 'press' });
  assert(pressed.modifiersDown.shift && pressed.heldKeys.includes('key.keyboard.left.shift'), JSON.stringify(pressed));
  const ctrl = await mc.call('key', { key: 'a', modifiers: ['ctrl'] });
  assert(ctrl.modifiersDown.shift && !ctrl.modifiersDown.ctrl, `tap modifiers must not stick: ${JSON.stringify(ctrl)}`);
  const released = await mc.call('key', { key: 'left_shift', action: 'release' });
  assert(!released.modifiersDown.shift && released.heldKeys.length === 0, JSON.stringify(released));
});
await check('tooltip accepts modifiers', async () => {
  const r = await mc.call('tooltip', { slot: 0, modifiers: ['shift'] });
  assert(Array.isArray(r.lines), JSON.stringify(r));
});

await mc.call('world.leave');
await mc.call('world.delete', { name: world.levelId });
mc.close();
t.done();
