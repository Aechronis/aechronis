import { defineConfig } from 'vitepress'
import { existsSync, readdirSync, readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { execFileSync } from 'node:child_process'

const iterationsDir = fileURLToPath(new URL('../src/iterations/', import.meta.url))
const iterations = readdirSync(iterationsDir, { withFileTypes: true })
  .filter(entry => entry.isDirectory() && !entry.name.startsWith('.')
    && existsSync(join(iterationsDir, entry.name, 'buildings.md')))
  .sort((a, b) => a.name.localeCompare(b.name))
  .map(entry => {
    const { title } = JSON.parse(readFileSync(join(iterationsDir, entry.name, 'iteration.json'), 'utf8'))
    if (typeof title !== 'string' || !title.trim()) {
      throw new Error(`Missing iteration title: ${entry.name}/iteration.json`)
    }
    return {
      text: title,
      collapsed: false,
      items: [{ text: 'Buildings', link: `/iterations/${entry.name}/buildings` }],
    }
  })

export default defineConfig({
  title: 'Aechronis',
  description: 'Guides and rules for the Aechronis Minecraft server.',
  lang: 'en',
  srcDir: 'src',
  // Render the date, author, and commit together in the custom footer.
  lastUpdated: false,
  transformPageData(page) {
    if (page.frontmatter.lastUpdated === false) return
    const source = page.relativePath === 'index.md' ? 'info.md' : page.relativePath
    const sourceDir = fileURLToPath(new URL('../src/', import.meta.url))
    const commit = execFileSync('git', [
      'log', '-1', '--format=%H%n%cs%n%aN', '--', source,
    ], { cwd: sourceDir, encoding: 'utf8' }).trim()
    // Uncommitted pages have no published revision yet.
    if (!commit) return
    const [hash, date, author] = commit.split('\n')
    page.gitRevision = { hash, date, author }
  },
  // Keep mdBook's .html URLs working on GitHub Pages.
  cleanUrls: false,
  head: [['link', { rel: 'icon', type: 'image/png', href: '/favicon.png' }]],
  themeConfig: {
    footer: {
      message: 'The Aechronis server is in no way affiliated with Mojang Studios, nor should it be considered a company endorsed by Mojang Studios. Any contributions or purchases made on this store goes to the Aechronis team.',
      copyright: 'For support or a purchase history, please send us a ticket in <a href="https://discord.aechronis.net/">our discord</a>.',
    },
    editLink: {
      pattern: ({ relativePath }) => {
        // The home page includes info.md; edit the actual content source.
        const path = relativePath === 'index.md' ? 'info.md' : relativePath
        return `https://github.com/Aechronis/aechronis/edit/master/guides/src/${path}`
      },
      text: 'Edit page',
    },
    nav: [
      { text: 'Guide', link: '/guide' },
      { text: 'Rules', link: '/rules' },
      { text: 'Store', link: '/store' },
      { text: 'Map', link: 'https://map.aechronis.net/' },
    ],
    sidebar: [
      { text: 'Info', link: '/info' },
      { text: 'Guide', link: '/guide' },
      { text: 'Colonization', link: '/colonization' },
      { text: 'Rules', link: '/rules' },
      { text: 'Territory Tiers', link: '/territory-tiers' },
      { text: 'Buildings', link: '/buildings' },
      { text: 'Iterations', items: iterations },
    ],
    search: { provider: 'local' },
    outline: [2, 3],
    socialLinks: [{ icon: 'github', link: 'https://github.com/aechronis/' }],
  },
})
