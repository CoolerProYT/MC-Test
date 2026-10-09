# Playing like a player

Besides the primitives (`mc_keybind`, `mc_use_on`, `mc_break_block`, …) MCTest has skill tools that behave the way a
person plays. They make realistic survival-style tests of your mod possible, and they are not shortcuts.

| Tool | What it does |
|---|---|
| `mc_goto {x,y,z \| entity, range?, sprint?}` | Walk there: A* pathfinding, gradual turning, sprinting, jumping, drops, swimming, replanning |
| `mc_collect {block, count, radius?}` | Walk to visible matching blocks, use the best tool, mine with real dig time, pick up the drops |
| `mc_dig {mode, length, direction?, until?}` | Staircase down (`stairs`), staircase up (`up`), or a 1×2 `tunnel` |
| `mc_place {item, x?,y?,z?}` | Place a block against any solid face; picks a sensible spot if no position is given |
| `mc_fight {id \| type, fleeHealth?}` | Equip a weapon, close in, aim, attack only at full cooldown |
| `mc_craft {item, times?}` | Recipe-book crafting: 2×2 in the inventory, 3×3 by walking to a nearby crafting table |
| `mc_select_item {item}` | Hold an item, swapping it into the hotbar if needed |
| `mc_scan_blocks {radius?, blocks?}` | Block types around you with counts and nearest positions (ids or `#tags`) |

## How they stay honest

- **Movement.** `mc_goto` never teleports. It plans a path (walk, diagonal, 1-block jumps, drops of up to 3 blocks,
  swimming; it avoids lava, fire, magma, cactus and berry bushes), then steers with the real movement keybinds. The
  camera turns at most 40° per tick, it sprints on straight stretches, and it replans when stuck.
- **Mining.** `mc_collect` only targets blocks a player could see (an exposed face, in line of sight), prefers walls
  over floors, never digs out the block you stand on or the steps of a staircase you dug, and refuses up front if
  nothing in your inventory can harvest the block. Only drops that reach the inventory are counted.
- **Digging.** `mc_dig` won't dig through tables, chests, furnaces or machines, and looks for lava and water before
  opening a space.
- **Fighting.** `mc_fight` respects the attack cooldown, so damage is what a real player would deal.
- **Crafting.** `mc_craft` uses the recipe book, so recipes must be unlocked, exactly as in survival.

## Time control

An agent thinks for seconds between calls, which is long enough for a zombie to kill the player. Time control fixes
that:

| Tool | What it does |
|---|---|
| `mc_lockstep {enabled}` | The world only runs while an MCTest action is in progress, so nothing happens while the agent thinks |
| `mc_time_freeze` / `mc_time_unfreeze` | Freeze the world (like `/tick freeze`, no cheats needed); the player can still act |
| `mc_time_step {ticks}` | Advance a frozen world by exactly N ticks |
| `mc_time_status` | Frozen / lockstep state and game time |

Turn on `mc_lockstep` for any survival play. `mc_time_step` is useful for machines: freeze, insert items, step exactly
200 ticks, check the output.

## A full survival run

[`scripts/survival-test.mjs`](https://github.com/CoolerProYT/MC-Test/blob/26.3/scripts/survival-test.mjs) uses only
these player actions, in a world with cheats off, to go from bare hands to a stone pickaxe: find trees, chop logs,
craft planks, a table, sticks and a wooden pickaxe, place the table, dig a staircase down to stone, mine cobblestone,
craft a stone pickaxe, and hunt an animal.

On random terrain it succeeded on 4 of 5 seeds tried. The failures were terrain dead ends (for example being stuck at
the bottom of a hole with no exposed stone), which pathfinding that can place blocks (pillaring, bridging) would solve.
