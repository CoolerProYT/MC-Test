// Step 5 checks: world.list / create / leave / join / delete. Starts from any menu or world.
//   node scripts/step5-test.mjs
import { connect, harness } from './lib.mjs';

const mc = await connect();
const t = harness();
const { check, assert } = t;
const NAME = `mctest-step5-${Date.now() % 100000}`;
let levelId;

await check('world.create flat creative world (auto-leaves any current world)', async () => {
  const r = await mc.call('world.create', { name: NAME, flat: true, seed: 'mctest', gamemode: 'creative' });
  levelId = r.levelId;
  assert(r.state === 'in-world', JSON.stringify(r));
  return `${r.levelId} seed=${r.seed} in ${r.loadMs}ms`;
});
await check('player is creative in the overworld with cheats', async () => {
  const p = await mc.call('player');
  assert(p.gamemode === 'creative' && p.dimension === 'minecraft:overworld', JSON.stringify({ gm: p.gamemode, dim: p.dimension }));
  const r = await mc.call('command', { command: 'time set day' });
  assert(r.output.length > 0 && !r.output.some((l) => /Unknown|permission/i.test(l.text)), JSON.stringify(r.output));
  return r.output[0].text;
});
await check('flat world: block under the player is flat-preset grass', async () => {
  const p = await mc.call('player');
  const b = await mc.call('block', { x: p.blockPos.x, y: p.blockPos.y - 1, z: p.blockPos.z });
  assert(b.id === 'minecraft:grass_block', b.id);
  return `y=${p.blockPos.y - 1} ${b.id}`;
});
await check('world.leave returns to the title screen', async () => {
  const r = await mc.call('world.leave');
  assert(r.left && r.state === 'title', JSON.stringify(r));
});
await check('world.leave when not in a world is a no-op', async () => {
  const r = await mc.call('world.leave');
  assert(r.left === false, JSON.stringify(r));
});
await check('world.list includes the new world', async () => {
  const r = await mc.call('world.list');
  const w = r.worlds.find((x) => x.levelId === levelId);
  assert(w && w.name === NAME && w.cheats === true, JSON.stringify(r.worlds));
  return `${r.worlds.length} world(s) in ${r.savesDir}`;
});
await check('world.join by display name', async () => {
  const r = await mc.call('world.join', { name: NAME });
  assert(r.state === 'in-world' && r.levelId === levelId, JSON.stringify(r));
  return `${r.loadMs}ms`;
});
await check('world.list marks the open world', async () => {
  const r = await mc.call('world.list');
  assert(r.worlds.some((w) => w.levelId === levelId && w.open), JSON.stringify(r.worlds));
});
await check('world.delete refuses the open world', async () => {
  try {
    await mc.call('world.delete', { name: levelId });
  } catch (e) {
    assert(/currently open/.test(e.message), e.message);
    return;
  }
  throw new Error('deleted the open world');
});
await check('world.join of a missing world fails clearly', async () => {
  try {
    await mc.call('world.join', { name: 'definitely-not-a-world' });
  } catch (e) {
    assert(/No world/.test(e.message), e.message);
    return;
  }
  throw new Error('joined a missing world?');
});
await check('world.leave + world.delete', async () => {
  await mc.call('world.leave');
  await mc.call('world.delete', { name: levelId });
  const r = await mc.call('world.list');
  assert(!r.worlds.some((w) => w.levelId === levelId), 'still listed');
});

mc.close();
t.done();
