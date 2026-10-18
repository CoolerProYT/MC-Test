---
layout: home

hero:
  name: MCTest
  text: Playwright for Minecraft
  tagline: Let Claude Code, or any MCP client, drive a running Minecraft client and test your mod. Dev-only, for Minecraft 26.2 on Fabric and NeoForge.
  image:
    src: /logo.svg
    alt: MCTest
  actions:
    - theme: brand
      text: Get started
      link: /guide/getting-started
    - theme: alt
      text: Browse the tools
      link: /reference/tools

features:
  - title: Real input, real code paths
    details: Clicks and keys go through Minecraft's own mouse and keyboard handlers, and interactions through MultiPlayerGameMode, so the server validates them as for a real player.
  - title: See what the player sees
    details: Screenshots, widget and container snapshots, tooltips (including hold-shift ones), block-entity NBT, entities, inventories and the captured log.
  - title: Plays like a player
    details: Pathfinding walks, mining with real dig time, recipe-book crafting, melee with attack cooldowns, and lockstep time so the world waits while the agent thinks.
  - title: GameTests in the loop
    details: Run your mod's GameTests in the open world and get pass/fail per test, so an agent can write a test, rebuild and check it.
  - title: Dedicated servers too
    details: A second API on the server shows the authoritative side, for bugs that only appear in multiplayer.
  - title: Never shipped
    details: Added as a dev-runtime dependency, loopback-only, and never in your jar or published POM.
---
