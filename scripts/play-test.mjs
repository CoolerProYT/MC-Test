// Gameplay checks: plays like a person in a fresh survival flat world.
//   node scripts/play-test.mjs
import { connect, harness } from './lib.mjs';

const mc = await connect();
const t = harness();
const { check, assert } = t;
const cmd = (command) => mc.call('command', { command });
const ticks = (n) => mc.call('waitFor', { condition: `ticks:${n}` });

const world = await mc.call('world.create', { name: 'mctest-play', flat: true, gamemode: 'survival', cheats: true });
console.log(`world ${world.levelId} loaded in ${world.loadMs}ms`);
await cmd('gamerule doDaylightCycle false');
await cmd('gamerule doMobSpawning false');
await cmd('time set day');
await cmd('clear');
for (const item of ['oak_planks 16', 'bread 4', 'crafting_table', 'bucket', 'stone_pickaxe']) {
  await cmd(`give @s minecraft:${item}`);
}
await ticks(5);

let me = await mc.call('player');
const base = me.blockPos; // standing on the flat surface
const ground = base.y - 1;

await check('keybinds lists vanilla bindings', async () => {
  const r = await mc.call('keybinds');
  assert(r.keybinds.some((k) => k.name === 'key.forward'), 'no key.forward');
  return `${r.keybinds.length} keybinds`;
});

await check('walk: hold forward for 20 ticks moves the player', async () => {
  await mc.call('look', { yaw: 0, pitch: 0 }); // facing +Z (south)
  const before = (await mc.call('player')).pos;
  await mc.call('keybind', { name: 'forward', action: 'hold', ticks: 20 });
  await ticks(5);
  const after = (await mc.call('player')).pos;
  const moved = Math.hypot(after.x - before.x, after.z - before.z);
  assert(moved > 2 && after.z > before.z, `moved ${moved.toFixed(2)} (dz=${(after.z - before.z).toFixed(2)})`);
  return `${moved.toFixed(2)} blocks south`;
});

await check('jump: tapping jump leaves the ground', async () => {
  await mc.call('keybind', { name: 'jump', action: 'tap' });
  await ticks(3);
  const p = await mc.call('player');
  assert(!p.onGround && p.pos.y > ground + 1.1, `onGround=${p.onGround} y=${p.pos.y}`);
  await ticks(15);
  return `y=${p.pos.y.toFixed(2)}`;
});

me = await mc.call('player');
const here = me.blockPos;
const slotOf = (id) => me.inventory.find((i) => i.item === `minecraft:${id}` && i.slot < 9)?.slot;

await check('selectSlot + scroll change the held item', async () => {
  const s = await mc.call('selectSlot', { slot: slotOf('oak_planks') });
  assert(s.item.item === 'minecraft:oak_planks', JSON.stringify(s));
  const r = await mc.call('scroll', { amount: -1 });
  assert(r.selectedSlot !== s.selectedSlot, JSON.stringify(r));
  await mc.call('selectSlot', { slot: slotOf('oak_planks') });
});

const place = { x: here.x + 2, y: ground, z: here.z };
await check('useOn places a block on the ground', async () => {
  const r = await mc.call('useOn', { ...place, face: 'up' });
  await ticks(2);
  const b = await mc.call('block', { x: place.x, y: place.y + 1, z: place.z });
  assert(b.id === 'minecraft:oak_planks', `${JSON.stringify(r)} -> ${b.id}`);
  return r.result;
});

await check('breakBlock by hand takes real survival dig time and drops the item', async () => {
  const r = await mc.call('breakBlock', { x: place.x, y: place.y + 1, z: place.z, face: 'up' });
  assert(r.broken && r.ticks > 20, JSON.stringify(r));
  await ticks(10);
  // The drop is either still on the ground or already picked up (the player is standing next to it).
  const drops = await mc.call('entities', { radius: 6, type: 'item' });
  const onGround = drops.entities.filter((e) => e.item?.item === 'minecraft:oak_planks').reduce((n, e) => n + e.item.count, 0);
  const inInv = (await mc.call('player')).inventory.filter((i) => i.item === 'minecraft:oak_planks').reduce((n, i) => n + i.count, 0);
  assert(onGround + inInv === 16, `planks: ${inInv} in inventory + ${onGround} on ground != 16`);
  return `${r.ticks} ticks, drop ${onGround ? 'on ground' : 'picked up'}`;
});

await check('place a crafting table and open it with useOn', async () => {
  await mc.call('selectSlot', { slot: slotOf('crafting_table') });
  await mc.call('useOn', { x: here.x - 2, y: ground, z: here.z, face: 'up' });
  await ticks(2);
  const r = await mc.call('useOn', { x: here.x - 2, y: ground + 1, z: here.z, face: 'up' });
  const w = await mc.call('waitFor', { condition: 'screen:CraftingScreen', timeoutTicks: 20 });
  const snap = await mc.call('snapshot');
  assert(snap.container?.slots.length === 46, `slots=${snap.container?.slots.length}`);
  return `${r.result}, opened in ${w.ticks} tick(s)`;
});

await check('craft sticks: planks into the grid, shift-click the result', async () => {
  const snap = await mc.call('snapshot');
  const planks = snap.container.slots.find((s) => s.item === 'minecraft:oak_planks' && s.index >= 10);
  await mc.call('clickSlot', { index: planks.index }); // pick up stack
  await mc.call('clickSlot', { index: 1, button: 1 }); // drop one into grid (0,0)
  await mc.call('clickSlot', { index: 4, button: 1 }); // and one below it
  await mc.call('clickSlot', { index: planks.index }); // put the rest back
  await mc.call('waitFor', { condition: 'slot:0=stick', timeoutTicks: 20 });
  await mc.call('clickSlot', { index: 0, clickType: 'QUICK_MOVE' });
  await ticks(3);
  await mc.call('closeScreen');
  await ticks(3);
  const p = await mc.call('player');
  const sticks = p.inventory.find((i) => i.item === 'minecraft:stick');
  assert(sticks?.count === 4, JSON.stringify(p.inventory.map((i) => `${i.item}x${i.count}`)));
  return `${sticks.count} sticks`;
});

await check('eat bread: useItem held until finished restores food', async () => {
  await cmd('effect give @s minecraft:hunger 3 255');
  await ticks(70);
  const before = (await mc.call('player')).food;
  await mc.call('selectSlot', { slot: slotOf('bread') });
  const r = await mc.call('useItem', { ticks: 40 });
  await ticks(3);
  const after = (await mc.call('player')).food;
  assert(after > before, `food ${before} -> ${after} (${JSON.stringify(r)})`);
  return `food ${before} -> ${after}`;
});

await check('attackEntity damages a pig', async () => {
  await cmd(`summon minecraft:pig ${here.x} ${ground + 1} ${here.z + 2} {NoAI:1b}`);
  await ticks(2);
  const pig = (await mc.call('entities', { radius: 4, type: 'pig' })).entities[0];
  await mc.call('attackEntity', { id: pig.id });
  await ticks(5);
  const after = (await mc.call('entities', { radius: 4, type: 'pig' })).entities.find((e) => e.id === pig.id);
  assert(!after || after.health < pig.health, `hp ${pig.health} -> ${after?.health}`);
  return `hp ${pig.health} -> ${after ? after.health : 'dead'}`;
});

await check('interactEntity: milk a cow with a bucket', async () => {
  await cmd(`summon minecraft:cow ${here.x + 2} ${ground + 1} ${here.z + 2} {NoAI:1b}`);
  await ticks(2);
  const cow = (await mc.call('entities', { radius: 5, type: 'cow' })).entities[0];
  await mc.call('selectSlot', { slot: slotOf('bucket') });
  const r = await mc.call('interactEntity', { id: cow.id });
  await ticks(3);
  const p = await mc.call('player');
  assert(p.inventory.some((i) => i.item === 'minecraft:milk_bucket'), JSON.stringify(r));
  return r.result;
});

await check('chat message reaches the server log', async () => {
  const { next } = await mc.call('logs', { limit: 1 });
  await mc.call('chat', { message: 'hello from an agent' });
  await ticks(3);
  const r = await mc.call('logs', { since: next, contains: 'hello from an agent' });
  assert(r.lines.length > 0, 'not logged');
});

await check('tooltip of an inventory slot', async () => {
  const p = await mc.call('player');
  const pick = p.inventory.find((i) => i.item === 'minecraft:stone_pickaxe');
  const r = await mc.call('tooltip', { slot: pick.slot, advanced: true });
  assert(r.lines[0] === 'Stone Pickaxe' && r.lines.some((l) => /minecraft:stone_pickaxe/.test(l)),
    `${r.source} slot ${pick.slot}: ${JSON.stringify(r.lines)}; screen=${JSON.stringify((await mc.call('status')).screen)}`);
  return r.lines.join(' / ');
});

await check('reloadResources', async () => `${(await mc.call('reloadResources')).reloadMs}ms`);

await mc.call('world.leave');
await mc.call('world.delete', { name: world.levelId });
mc.close();
t.done();
