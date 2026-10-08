// No-cheats survival run on real terrain, using only player actions (no commands):
// trees -> logs -> planks/table/sticks -> wooden pickaxe -> stone -> stone pickaxe -> hunt an animal.
//   node scripts/survival-test.mjs [seed]
import { connect, harness } from './lib.mjs';

const mc = await connect();
const t = harness();
const { check, assert } = t;
const seed = process.argv[2] ?? 'mctest-survival';

const world = await mc.call('world.create', { name: 'mctest-survival', seed, gamemode: 'survival', cheats: false, difficulty: 'easy' });
console.log(`world ${world.levelId} (seed ${world.seed}) loaded in ${world.loadMs}ms`);
await mc.call('time.lockstep', { enabled: true });
await mc.call('waitFor', { condition: 'ticks:40' }); // let chunks around spawn arrive

const say = async (label) => console.log(`     [${label}] ${JSON.stringify((await mc.call('player')).inventory.map((i) => `${i.item.replace('minecraft:', '')}x${i.count}`))}`);

await check('commands are really disabled', async () => {
  const r = await mc.call('command', { command: 'give @s minecraft:diamond' });
  assert(!r.output.some((l) => /Gave/.test(l.text)), JSON.stringify(r.output));
  return r.output.map((l) => l.text).join(' ') || '(no output)';
});

let logType;
await check('find trees (exploring if needed)', async () => {
  for (let attempt = 0; attempt < 6; attempt++) {
    const scan = await mc.call('scanBlocks', { radius: 32, blocks: ['#minecraft:logs'] });
    if (scan.blocks.length) {
      logType = scan.blocks[0].block;
      return `${scan.blocks[0].count}x ${logType} nearest ${scan.blocks[0].distance} blocks away`;
    }
    const p = (await mc.call('player')).pos;
    const angle = attempt * 1.05;
    const r = await mc.call('goto', { x: p.x + Math.cos(angle) * 40, y: p.y, z: p.z + Math.sin(angle) * 40, range: 6, timeoutTicks: 1200 });
    console.log(`     exploring: arrived=${r.arrived} ${r.reason ?? ''}`);
  }
  throw new Error('no trees found');
});

await check('chop 5 logs by hand', async () => {
  const r = await mc.call('collect', { block: logType, count: 5, radius: 32 });
  assert(r.mined >= 4, JSON.stringify(r));
  await say('logs');
  return `mined ${r.mined} ${r.reason ?? ''}`;
});

const planks = () => logType.replace(/_(log|stem|wood)$/, '_planks');
await check('craft planks, crafting table, sticks', async () => {
  const logs = (await mc.call('player')).inventory.filter((i) => i.item === logType).reduce((n, i) => n + i.count, 0);
  assert(logs >= 3, `only ${logs} logs picked up`);
  const p = await mc.call('craft', { item: planks(), times: logs });
  await mc.call('craft', { item: 'crafting_table' });
  await mc.call('craft', { item: 'stick', times: 2 });
  await say('crafted');
  return JSON.stringify(p.inventoryChange);
});

let tablePos;
await check('place the crafting table next to me', async () => {
  const r = await mc.call('place', { item: 'crafting_table' });
  tablePos = r.pos;
  assert(r.placed && r.block === 'minecraft:crafting_table', JSON.stringify(r));
  return `at ${r.pos.x},${r.pos.y},${r.pos.z} against ${r.against}`;
});

await check('craft a wooden pickaxe at the table', async () => {
  const r = await mc.call('craft', { item: 'wooden_pickaxe' });
  assert(r.inventoryChange['minecraft:wooden_pickaxe'] === 1, JSON.stringify(r));
});

const cobble = async () => (await mc.call('player')).inventory.filter((i) => i.item === 'minecraft:cobblestone').reduce((n, i) => n + i.count, 0);

await check('dig a staircase down until stone shows up', async () => {
  // Dig away from the table, like a player would.
  const me = (await mc.call('player')).blockPos;
  const dx = me.x - tablePos.x, dz = me.z - tablePos.z;
  const direction = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 'east' : 'west') : (dz >= 0 ? 'south' : 'north');
  const r = await mc.call('dig', { mode: 'stairs', length: 16, until: 'stone', direction });
  return `${r.steps} steps, stopped: ${r.stoppedBecause}`;
});

await check('mine 3 stone with the wooden pickaxe (cobblestone drops)', async () => {
  // Like a player: take exposed stone, and if there isn't enough, keep digging down through it.
  let r = await mc.call('collect', { block: 'stone', count: 3, radius: 8 });
  for (let i = 0; i < 3 && (await cobble()) < 3; i++) {
    const d = await mc.call('dig', { mode: 'stairs', length: 2 });
    console.log(`     dig through stone: ${d.steps} steps (${d.stoppedBecause})`);
    if ((await cobble()) < 3) r = await mc.call('collect', { block: 'stone', count: 3 - (await cobble()), radius: 6 });
  }
  await say('stone');
  assert((await cobble()) >= 3, JSON.stringify(r));
  return `${await cobble()} cobblestone`;
});

await check('craft a stone pickaxe', async () => {
  let r;
  try {
    r = await mc.call('craft', { item: 'stone_pickaxe' });
  } catch (e) {
    console.log(`     ${e.message}`);
    // First try what a player does in a hole: dig stairs up toward the table.
    const me = (await mc.call('player')).blockPos;
    const dx = tablePos.x - me.x, dz = tablePos.z - me.z;
    const direction = Math.abs(dx) >= Math.abs(dz) ? (dx >= 0 ? 'east' : 'west') : (dz >= 0 ? 'south' : 'north');
    const up = await mc.call('dig', { mode: 'up', length: Math.max(1, tablePos.y - me.y), direction });
    console.log(`     dug up ${up.steps} steps (${up.stoppedBecause})`);
    try {
      r = await mc.call('craft', { item: 'stone_pickaxe' });
    } catch {}
  }
  if (!r) {
    // Still stuck: make another table right here, like a player with spare planks.
    console.log('     -> crafting a new table here');
    if (!(await mc.call('player')).inventory.some((i) => i.item.endsWith('_planks') && i.count >= 4)) throw new Error('cannot reach the table and no planks for a new one');
    await mc.call('craft', { item: 'crafting_table' });
    await mc.call('place', { item: 'crafting_table' });
    r = await mc.call('craft', { item: 'stone_pickaxe' });
  }
  assert(r.inventoryChange['minecraft:stone_pickaxe'] === 1, JSON.stringify(r));
});

await check('hunt an animal for food', async () => {
  const animals = (await mc.call('entities', { radius: 48 })).entities.filter((e) => /pig|cow|sheep|chicken|rabbit/.test(e.type));
  if (!animals.length) return '(no animals nearby, skipped)';
  const target = animals[0];
  const walk = await mc.call('goto', { entity: target.id, range: 2.5, timeoutTicks: 1200 });
  const r = await mc.call('fight', { id: target.id, timeoutTicks: 800 });
  await mc.call('waitFor', { condition: 'ticks:20' });
  await say('hunt');
  assert(r.outcome === 'killed', JSON.stringify({ walk, r }));
  return `${target.type}: ${r.hits} hits`;
});

const shot = await mc.call('screenshot', { name: 'survival-end' });
console.log(`screenshot: ${shot.path}`);
await mc.call('time.lockstep', { enabled: false });
mc.close();
t.done();
