// Player-skill checks in a flat test world: pathfinding, time control, crafting, gathering, fighting.
//   node scripts/skills-test.mjs
import { connect, harness } from './lib.mjs';

const mc = await connect();
const t = harness();
const { check, assert } = t;
const cmd = (command) => mc.call('command', { command });
const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

const world = await mc.call('world.create', { name: 'mctest-skills', flat: true, gamemode: 'survival' });
console.log(`world ${world.levelId} loaded in ${world.loadMs}ms`);
for (const c of ['gamerule doDaylightCycle false', 'gamerule doMobSpawning false', 'time set noon', 'difficulty easy']) await cmd(c);
await mc.call('waitFor', { condition: 'ticks:10' });

const start = (await mc.call('player')).blockPos;
const { x: X, y: Y, z: Z } = start; // feet position on the flat surface

await check('goto walks around a 3-high wall', async () => {
  await cmd(`fill ${X + 3} ${Y} ${Z - 6} ${X + 3} ${Y + 2} ${Z + 6} minecraft:stone`);
  const r = await mc.call('goto', { x: X + 6.5, y: Y, z: Z + 0.5 });
  assert(r.arrived && r.pathLength > 8, JSON.stringify(r));
  return `${r.pathLength} steps, ${r.ticks} ticks, ${r.replans} replans`;
});

await check('goto jumps onto a block', async () => {
  const p = (await mc.call('player')).blockPos;
  await cmd(`setblock ${p.x + 2} ${Y} ${p.z} minecraft:oak_planks`);
  const r = await mc.call('goto', { x: p.x + 2.5, y: Y + 1, z: p.z + 0.5, range: 0.6 });
  const me = await mc.call('player');
  assert(r.arrived && me.blockPos.y === Y + 1, `${JSON.stringify(r)} y=${me.pos.y}`);
  return `${r.ticks} ticks`;
});

await check('goto reports an enclosed target as unreachable', async () => {
  await cmd(`fill ${X - 12} ${Y} ${Z - 2} ${X - 8} ${Y + 3} ${Z + 2} minecraft:stone hollow`);
  const r = await mc.call('goto', { x: X - 9.5, y: Y + 1, z: Z + 0.5, timeoutTicks: 600 });
  assert(!r.arrived && r.reason, JSON.stringify(r));
  return r.reason;
});

await check('time.freeze stops the world; time.step advances exactly', async () => {
  await mc.call('time.freeze');
  const t0 = (await mc.call('time.status')).gameTime;
  await sleep(1000);
  const t1 = (await mc.call('time.status')).gameTime;
  const s = await mc.call('time.step', { ticks: 10 });
  await mc.call('time.unfreeze');
  assert(t1 === t0 && s.steppedTicks >= 9 && s.steppedTicks <= 11, `idle +${t1 - t0}, stepped ${s.steppedTicks}`);
  return `idle +${t1 - t0}, stepped ${s.steppedTicks}`;
});

// 1.20.1 has no vanilla tick freeze; MCTest's own must also stop mobs (here: a pig falling from the sky).
await check('time.freeze also stops mobs (pig stays in mid-air until stepped)', async () => {
  await cmd(`summon minecraft:pig ${X + 0.5} ${Y + 15} ${Z - 4.5} {Tags:["mctest_fall"]}`);
  await mc.call('time.freeze');
  const yOf = async () => (await mc.call('entities', { radius: 32, type: 'pig' })).entities.find((e) => e.pos.y > Y + 1)?.pos.y;
  const y0 = await yOf();
  await sleep(1000);
  const y1 = await yOf();
  await mc.call('time.step', { ticks: 10 });
  await mc.call('waitFor', { condition: 'ticks:2' }).catch(() => {});
  const y2 = await yOf();
  await mc.call('time.unfreeze');
  await cmd('kill @e[tag=mctest_fall]');
  assert(y0 !== undefined && y0 === y1 && (y2 === undefined || y2 < y1), `y ${y0} -> ${y1} frozen -> ${y2} after step`);
  return `y ${y0?.toFixed(2)} frozen, ${y2?.toFixed(2)} after 10 steps`;
});

await check('lockstep: frozen while idle, runs during actions', async () => {
  await mc.call('time.lockstep', { enabled: true });
  await sleep(300);
  const t0 = (await mc.call('time.status')).gameTime;
  await sleep(1000);
  const t1 = (await mc.call('time.status')).gameTime;
  await mc.call('waitFor', { condition: 'ticks:20' });
  const t2 = (await mc.call('time.status')).gameTime;
  await mc.call('time.lockstep', { enabled: false });
  assert(t1 - t0 <= 1 && t2 - t1 >= 18, `idle +${t1 - t0}, during wait +${t2 - t1}`);
  return `idle +${t1 - t0}, during wait +${t2 - t1}`;
});

await check('craft planks (2x2 in the inventory), crafting table and sticks', async () => {
  await cmd('clear');
  await cmd('give @s minecraft:oak_log 3');
  await mc.call('waitFor', { condition: 'ticks:5' });
  const a = await mc.call('craft', { item: 'oak_planks', times: 3 });
  const b = await mc.call('craft', { item: 'crafting_table' });
  const c = await mc.call('craft', { item: 'stick' });
  assert(a.inventoryChange['minecraft:oak_planks'] === 12 || a.crafts === 3, JSON.stringify(a));
  assert(b.inventoryChange['minecraft:crafting_table'] === 1, JSON.stringify(b));
  assert(c.inventoryChange['minecraft:stick'] === 4, JSON.stringify(c));
  const s = await mc.call('status');
  assert(!s.screen, `screen left open: ${JSON.stringify(s.screen)}`);
  return `planks x${a.crafts}, table, sticks`;
});

await check('place the table, then craft a pickaxe (3x3: opens the table itself)', async () => {
  const p = (await mc.call('player')).blockPos;
  await mc.call('selectItem', { item: 'crafting_table' });
  await mc.call('useOn', { x: p.x + 1, y: Y - 1, z: p.z - 2, face: 'up' });
  await mc.call('waitFor', { condition: `block:${p.x + 1},${Y},${p.z - 2}=crafting_table` });
  const r = await mc.call('craft', { item: 'wooden_pickaxe' });
  assert(r.inventoryChange['minecraft:wooden_pickaxe'] === 1 && r.station === 'crafting_table', JSON.stringify(r));
  return JSON.stringify(r.inventoryChange);
});

await check('craft explains missing ingredients', async () => {
  try {
    await mc.call('craft', { item: 'diamond_pickaxe' });
  } catch (e) {
    assert(/No known crafting recipe|Missing ingredients/.test(e.message), e.message);
    return e.message.slice(0, 80);
  }
  throw new Error('crafted a diamond pickaxe from nothing?');
});

await check('collect walks to scattered logs, mines them with the best tool and picks them up', async () => {
  const p = (await mc.call('player')).blockPos;
  for (const [dx, dz] of [[6, 4], [-5, 7], [8, -6]]) await cmd(`setblock ${p.x + dx} ${Y} ${p.z + dz} minecraft:birch_log`);
  await cmd('give @s minecraft:stone_axe');
  const scan = await mc.call('scanBlocks', { radius: 12, blocks: ['minecraft:birch_log'] });
  assert(scan.blocks[0]?.count === 3, JSON.stringify(scan));
  const r = await mc.call('collect', { block: 'minecraft:birch_log', count: 3, radius: 16 });
  assert(r.mined === 3 && r.inventoryChange['minecraft:birch_log'] === 3, JSON.stringify(r));
  return `mined ${r.mined}, inventory ${JSON.stringify(r.inventoryChange)}`;
});

await check('fight kills a husk with proper attack cooldowns', async () => {
  await cmd('give @s minecraft:stone_sword');
  const p = (await mc.call('player')).blockPos;
  await cmd(`summon minecraft:husk ${p.x + 5} ${Y} ${p.z}`);
  await mc.call('waitFor', { condition: 'entity:husk@10' });
  const r = await mc.call('fight', { type: 'husk', timeoutTicks: 600 });
  assert(r.outcome === 'killed', JSON.stringify(r));
  return `${r.hits} hits in ${r.ticks} ticks, took ${r.damageTaken} damage`;
});

await check('selectItem pulls an item from the main inventory into the hotbar', async () => {
  await cmd('clear');
  for (let i = 0; i < 9; i++) await cmd(`give @s minecraft:dirt 64`);
  await cmd('give @s minecraft:bread');
  const r = await mc.call('selectItem', { item: 'bread' });
  await mc.call('waitFor', { condition: 'ticks:3' });
  const me = await mc.call('player');
  assert(me.inventory.find((i) => i.slot === me.selectedSlot)?.item === 'minecraft:bread', JSON.stringify(r));
  return `slot ${r.selectedSlot}`;
});

await check('dig stairs down 3 into stone, then dig up out of the hole', async () => {
  await cmd('give @s minecraft:stone_pickaxe');
  const p = (await mc.call('player')).blockPos;
  // A stone hill to the east (west of here is the hollow box from the previous check).
  await cmd(`fill ${p.x + 1} ${Y - 1} ${p.z - 2} ${p.x + 12} ${Y + 4} ${p.z + 2} minecraft:stone`);
  await cmd(`fill ${p.x + 1} ${Y - 1} ${p.z - 2} ${p.x + 12} ${Y - 1} ${p.z + 2} minecraft:stone`);
  await mc.call('look', { yaw: -90, pitch: 0 });
  const down = await mc.call('dig', { mode: 'tunnel', length: 2, direction: 'east' });
  const deep = await mc.call('dig', { mode: 'stairs', length: 3, direction: 'east' });
  const bottom = (await mc.call('player')).blockPos.y;
  const upward = await mc.call('dig', { mode: 'up', length: 3, direction: 'east' });
  const top = (await mc.call('player')).blockPos.y;
  assert(deep.steps === 3 && upward.steps === 3 && bottom === p.y - 3 && top === p.y,
    JSON.stringify({ down, deep, upward, start: p.y, bottom, top }));
  return `tunnel ${down.steps}, down to y=${bottom}, back up to y=${top}`;
});

await mc.call('world.leave');
await mc.call('world.delete', { name: world.levelId });
mc.close();
t.done();
