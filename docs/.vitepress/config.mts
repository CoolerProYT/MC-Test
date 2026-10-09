import { defineConfig } from 'vitepress'

// GitHub Pages serves a project site from /<repository>/. For a custom domain or a user site, build with DOCS_BASE=/.
const base = process.env.DOCS_BASE ?? '/MC-Test/'

export default defineConfig({
  title: 'MCTest',
  description: 'Drive a running Minecraft client from Claude Code or any MCP client, like Playwright drives a browser. Test your mods on Fabric and NeoForge.',
  base,
  cleanUrls: true,
  srcExclude: ['README.md'],
  head: [['link', { rel: 'icon', type: 'image/svg+xml', href: `${base}logo.svg` }]],
  themeConfig: {
    logo: { src: '/logo.svg', alt: '' },
    nav: [
      { text: 'Guide', link: '/guide/getting-started' },
      { text: 'Tools', link: '/reference/tools' },
      { text: 'GitHub', link: 'https://github.com/CoolerProYT/MC-Test' },
    ],
    sidebar: [
      {
        text: 'Guide',
        items: [
          { text: 'Getting started', link: '/guide/getting-started' },
          { text: 'Your first test', link: '/guide/first-test' },
          { text: 'GUIs, keys and tooltips', link: '/guide/gui-input' },
          { text: 'Playing like a player', link: '/guide/player-skills' },
          { text: 'GameTests', link: '/guide/gametests' },
          { text: 'Dedicated servers', link: '/guide/dedicated-server' },
          { text: 'Things worth knowing', link: '/guide/tips' },
        ],
      },
      {
        text: 'Reference',
        items: [
          { text: 'Client tools', link: '/reference/tools' },
          { text: 'Server tools', link: '/reference/server-tools' },
          { text: 'Configuration', link: '/reference/configuration' },
          { text: 'Raw protocol', link: '/reference/protocol' },
        ],
      },
      { text: 'Developing MCTest', link: '/contributing' },
    ],
    socialLinks: [{ icon: 'github', link: 'https://github.com/CoolerProYT/MC-Test' }],
    editLink: {
      pattern: 'https://github.com/CoolerProYT/MC-Test/edit/26.3/docs/:path',
      text: 'Edit this page on GitHub',
    },
    search: { provider: 'local' },
    outline: { level: [2, 3] },
    footer: { message: 'Released under the MIT License.' },
  },
})
