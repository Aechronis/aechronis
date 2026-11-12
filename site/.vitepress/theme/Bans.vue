<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

// Read-only API of the server's punishments module (GET /api/recent, /api/blacklist, /api/player?q=).
const api = import.meta.env.DEV ? 'http://localhost:8126/api' : 'https://bans.aechronis.net/api'

const TABS = { recent: 'Recent', blacklist: 'Blacklist' }
const FILTERS = { all: 'All', bans: 'Bans', mutes: 'Mutes', warnings: 'Warnings' }
const BANS = ['ban', 'iteration_ban', 'ip_ban']
const MATCH = { all: () => true, bans: t => BANS.includes(t), mutes: t => t === 'mute', warnings: t => t === 'warn' }
const RANK = ['blacklist', 'iteration_ban', 'ban', 'ip_ban', 'mute', 'warn']
const HISTORY_COLS = ['Action', 'Category', 'Reason', 'Staff', 'Expires', 'When']
const SKELETON = [38, 26, 44, 30, 22, 34]

// ---- Formatting -------------------------------------------------------------

const dayFmt = new Intl.DateTimeFormat('en-US', { month: 'short', day: 'numeric', year: 'numeric' })
const timeFmt = new Intl.DateTimeFormat('en-GB', { hour: '2-digit', minute: '2-digit' })
const rtf = new Intl.RelativeTimeFormat('en', { numeric: 'always' })
const day = ms => dayFmt.format(ms)
const stamp = ms => `${day(ms)} ${timeFmt.format(ms)}`
function ago(ms) {
  const s = (Date.now() - ms) / 1000
  for (const [unit, n] of [['year', 31536000], ['month', 2592000], ['week', 604800], ['day', 86400], ['hour', 3600], ['minute', 60]]) {
    if (s >= n) return rtf.format(-Math.floor(s / n), unit)
  }
  return 'just now'
}
function duration(ms) {
  let out = ''
  for (const [unit, n] of [['d', 864e5], ['h', 36e5], ['m', 6e4], ['s', 1e3]]) {
    const k = Math.floor(ms / n)
    if (k) { out += k + unit; ms -= k * n }
  }
  return out || '0s'
}
const shortCategory = label => (label ? label.replace(/ violation| or modification/, '') : '—')
const staffName = staff => (staff.uuid ? staff.name : 'Console')

function part(r) {
  const d = r.expiresAt == null ? 'perm' : duration(r.expiresAt - r.createdAt)
  return { warn: 'warning', mute: `${d} mute`, ban: `${d} ban`, iteration_ban: 'iteration ban', ip_ban: 'IP ban', blacklist: 'blacklist' }[r.type] ?? r.typeLabel
}
function expiry(r) {
  if (r.lifted) return `Lifted ${stamp(r.lifted.at)} by ${r.lifted.by}`
  if (r.type === 'warn') return '—'
  if (r.type === 'iteration_ban') return 'Next iteration'
  return r.expiresAt == null ? 'Permanent' : stamp(r.expiresAt)
}

/** One offence is one row: records sharing an offence id, most severe first. */
function offences(records) {
  const groups = new Map()
  for (const r of records) {
    if (!groups.has(r.offence)) groups.set(r.offence, [])
    groups.get(r.offence).push(r)
  }
  return [...groups.values()].map(g => {
    g.sort((a, b) => RANK.indexOf(a.type) - RANK.indexOf(b.type))
    const first = g[0]
    const lifted = g.every(r => r.lifted)
    const live = g.find(r => !r.lifted) ?? first
    let expires = expiry(live)
    if (!lifted && live.expiresAt != null && live.type !== 'warn') {
      for (const r of g) if (r !== live && !r.lifted && r.expiresAt == null) expires += ` (${r.type === 'ip_ban' ? 'IP ban' : r.typeLabel.toLowerCase()} permanent)`
    }
    const label = g.map(part).join(' + ')
    return {
      key: first.offence,
      types: g.map(r => r.type),
      player: first.player,
      label: label[0].toUpperCase() + label.slice(1),
      category: shortCategory(first.categoryLabel),
      reason: first.reason || '—',
      staff: staffName(first.staff),
      expires,
      lifted,
      createdAt: first.createdAt,
    }
  })
}

// ---- Data -------------------------------------------------------------------

const tab = ref('recent')
const q = ref('') // the player being shown, from ?q=
const input = ref('')
const searchInput = ref(null)
const state = ref('loading') // 'loading' | 'ok' | 'notfound' | 'rate' | 'error'
const recent = ref({ rows: [], at: 0 })
const filter = ref('all')
const blacklist = ref(null)
const player = ref(null)
const copied = ref('')

const recentRows = computed(() => recent.value.rows.filter(row => row.types.some(MATCH[filter.value])))

async function get(path) {
  const response = await fetch(api + path)
  if (!response.ok) throw Object.assign(new Error(), { status: response.status })
  return response.json()
}

const badges = computed(() => {
  const s = player.value?.status
  if (!s) return []
  const list = [
    s.blacklisted && ['blacklisted', 'Blacklisted'],
    s.banned && !s.blacklisted && ['banned', 'Banned'],
    s.muted && ['muted', 'Muted'],
  ].filter(Boolean)
  return list.length ? list : [['clean', 'Clean']]
})
const discord = computed(() => {
  const d = player.value?.discord
  if (!d) return 'Discord not linked'
  return d.username ? `Discord @${d.username}` : `Discord linked (${d.id})`
})

let seq = 0
async function load(quiet = false) {
  const id = ++seq
  if (!quiet) state.value = 'loading'
  try {
    if (q.value) {
      const p = await get(`/player?q=${encodeURIComponent(q.value)}`)
      if (id !== seq) return
      player.value = { ...p, rows: offences(p.history) }
      document.title = `${p.name} · Bans · Aechronis`
    } else if (tab.value === 'recent') {
      const { records } = await get('/recent?limit=50')
      if (id !== seq) return
      recent.value = { rows: offences(records), at: Date.now() }
    } else {
      const data = await get('/blacklist')
      if (id !== seq) return
      blacklist.value = data
    }
    state.value = 'ok'
  } catch (e) {
    if (id !== seq || quiet) return
    state.value = e.status === 404 && q.value ? 'notfound' : e.status === 429 ? 'rate' : 'error'
  }
}

// ---- Routing: ?q=<player> is the player page, #tab picks the tab ------------

function route() {
  q.value = new URLSearchParams(location.search).get('q')?.trim() ?? ''
  tab.value = q.value ? '' : Object.keys(TABS).find(t => `#${t}` === location.hash) ?? 'recent'
  input.value = q.value
  document.title = 'Bans · Aechronis'
  load()
}
// A null history state keeps VitePress's router out of these in-page navigations.
function go(url) {
  history.pushState(null, '', url)
  route()
}
const playerUrl = name => `${location.pathname}?q=${encodeURIComponent(name)}`
const shareUrl = name => location.origin + playerUrl(name)
const openPlayer = name => go(playerUrl(name))
const openTab = t => go(`${location.pathname}#${t}`)

function search() {
  const value = input.value.trim()
  if (value) openPlayer(value)
}
function copy(text, what) {
  navigator.clipboard.writeText(text).then(() => {
    copied.value = what
    setTimeout(() => { if (copied.value === what) copied.value = '' }, 1500)
  })
}
function slashFocus(event) {
  if (event.key !== '/' || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement?.tagName)) return
  event.preventDefault()
  searchInput.value?.focus()
  searchInput.value?.select()
}

let timer
onMounted(() => {
  window.addEventListener('keydown', slashFocus)
  window.addEventListener('popstate', route)
  timer = setInterval(() => { if (tab.value === 'recent' && !document.hidden) load(true) }, 60000)
  route()
})
onUnmounted(() => {
  clearInterval(timer)
  window.removeEventListener('keydown', slashFocus)
  window.removeEventListener('popstate', route)
})
</script>

<template>
  <div class="bn">
    <header class="bn-bar">
      <h1 class="bn-title">Bans</h1>
      <form class="bn-search" :class="{ on: q }" role="search" @submit.prevent="search">
        <svg aria-hidden="true" viewBox="0 0 24 24" class="bn-search-icon"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <label for="bn-q" class="bn-sr">Player name or UUID</label>
        <input id="bn-q" ref="searchInput" v-model="input" type="search" autocomplete="off" autocapitalize="none" :spellcheck="false" placeholder="Look up a player by name or UUID" />
        <kbd class="bn-kbd" aria-hidden="true">/</kbd>
      </form>
      <nav class="bn-seg bn-tabs" aria-label="Sections">
        <a v-for="(label, t) in TABS" :key="t" :href="`#${t}`" :class="{ on: tab === t }" :aria-current="tab === t ? 'page' : undefined" @click.prevent="openTab(t)">{{ label }}</a>
      </nav>
    </header>

    <section v-if="state === 'loading'" class="bn-card" aria-busy="true">
      <div v-for="w in SKELETON" :key="w" class="bn-sk"><span :style="{ width: `${w}%` }" /></div>
    </section>
    <p v-else-if="state === 'rate'" role="alert" class="bn-notice warn">Too many lookups. Try again in a minute.</p>
    <p v-else-if="state === 'error'" role="alert" class="bn-notice danger">Bans are unavailable right now. Please try again.</p>
    <section v-else-if="state === 'notfound'" class="bn-card">
      <p class="bn-empty">No record for {{ q }}. This player has never been punished or hasn't joined the server.</p>
    </section>

    <!-- Player -->
    <section v-else-if="q && player" class="bn-card">
      <div class="bn-ident">
        <span class="bn-avatar bn-avatar-lg">{{ player.name[0] }}</span>
        <div class="bn-head-text">
          <div class="bn-ident-title">
            <h2>{{ player.name }}</h2>
            <span v-for="[c, t] in badges" :key="c" class="bn-badge" :class="c">{{ t }}</span>
          </div>
          <span class="bn-muted">{{ player.uuid }} · {{ discord }}</span>
        </div>
        <button type="button" class="bn-ghost" @click="copy(player.uuid, 'uuid')">{{ copied === 'uuid' ? 'Copied' : 'Copy UUID' }}</button>
        <button type="button" class="bn-ghost" @click="copy(shareUrl(player.name), 'link')">{{ copied === 'link' ? 'Copied' : 'Copy link' }}</button>
      </div>
      <div v-if="player.rows.length" class="bn-scroll">
        <table class="bn-table">
          <thead><tr><th v-for="c in HISTORY_COLS" :key="c" scope="col">{{ c }}</th></tr></thead>
          <tbody>
            <tr v-for="row in player.rows" :key="row.key" :class="{ lifted: row.lifted }">
              <td><span class="bn-act">{{ row.label }}</span></td>
              <td>{{ row.category }}</td>
              <td class="bn-reason">{{ row.reason }}</td>
              <td>{{ row.staff }}</td>
              <td>{{ row.expires }}</td>
              <td :title="stamp(row.createdAt)">{{ ago(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="bn-empty">No punishments on record.</p>
    </section>

    <!-- Recent -->
    <section v-else-if="tab === 'recent'" class="bn-card">
      <div class="bn-card-head">
        <div class="bn-head-text">
          <h2>Recent actions</h2>
          <span class="bn-muted">Latest 50 · updated {{ ago(recent.at) }}</span>
        </div>
        <div class="bn-seg bn-seg-sm" role="group" aria-label="Filter">
          <button v-for="(label, f) in FILTERS" :key="f" type="button" :class="{ on: filter === f }" :aria-pressed="filter === f" @click="filter = f">{{ label }}</button>
        </div>
      </div>
      <div v-if="recentRows.length" class="bn-scroll">
        <table class="bn-table">
          <thead><tr><th scope="col">Player</th><th v-for="c in HISTORY_COLS" :key="c" scope="col">{{ c }}</th></tr></thead>
          <tbody>
            <tr v-for="row in recentRows" :key="row.key" :class="{ lifted: row.lifted }">
              <td><a class="bn-who" :href="playerUrl(row.player.name)" @click.prevent="openPlayer(row.player.name)"><span class="bn-avatar">{{ row.player.name[0] }}</span><span class="bn-name">{{ row.player.name }}</span></a></td>
              <td><span class="bn-act">{{ row.label }}</span></td>
              <td>{{ row.category }}</td>
              <td class="bn-reason">{{ row.reason }}</td>
              <td>{{ row.staff }}</td>
              <td>{{ row.expires }}</td>
              <td :title="stamp(row.createdAt)">{{ ago(row.createdAt) }}</td>
            </tr>
          </tbody>
        </table>
      </div>
      <p v-else class="bn-empty">Nothing here yet.</p>
    </section>

    <!-- Blacklist -->
    <template v-else-if="tab === 'blacklist' && blacklist">
      <p class="bn-notice danger">{{ blacklist.rule }}</p>
      <div class="bn-cols">
        <section class="bn-card bn-players">
          <div class="bn-card-head"><div class="bn-head-text"><h2>Players</h2><span class="bn-muted">{{ blacklist.players.length }} blacklisted</span></div></div>
          <div v-if="blacklist.players.length" class="bn-scroll">
            <table class="bn-table">
              <thead><tr><th scope="col">Player</th><th scope="col">Category</th><th scope="col">Reason</th><th scope="col">Staff</th><th scope="col">Date</th></tr></thead>
              <tbody>
                <tr v-for="r in blacklist.players" :key="r.id ?? r.player.uuid">
                  <td><a class="bn-who" :href="playerUrl(r.player.name)" @click.prevent="openPlayer(r.player.name)"><span class="bn-avatar">{{ r.player.name[0] }}</span><span class="bn-name">{{ r.player.name }}</span></a></td>
                  <td>{{ shortCategory(r.categoryLabel) }}</td>
                  <td class="bn-reason">{{ r.reason || '—' }}</td>
                  <td>{{ staffName(r.staff) }}</td>
                  <td :title="stamp(r.createdAt)">{{ day(r.createdAt) }}</td>
                </tr>
              </tbody>
            </table>
          </div>
          <p v-else class="bn-empty">No blacklisted players.</p>
        </section>
        <section class="bn-card bn-groups">
          <div class="bn-card-head"><div class="bn-head-text"><h2>Groups</h2><span class="bn-muted">{{ blacklist.groups.length }} blacklisted</span></div></div>
          <div v-for="g in blacklist.groups" :key="g.name" class="bn-group">
            <div class="bn-group-top"><span class="bn-name">{{ g.name }}</span><span class="bn-muted">{{ day(g.createdAt) }}</span></div>
            <span>{{ g.reason || '—' }}</span>
            <span class="bn-muted">Added by {{ g.staffName }}</span>
          </div>
          <p v-if="!blacklist.groups.length" class="bn-empty">No blacklisted groups.</p>
        </section>
      </div>
    </template>
  </div>
</template>

<style scoped>
.bn {
  --bn-card: var(--vp-c-bg-soft);
  --bn-line: var(--vp-c-divider);
  max-width: 1600px;
  margin: 0 auto;
  padding: 20px 24px 24px;
  display: flex;
  flex-direction: column;
  gap: 16px;
  font-size: 14px;
}
.bn-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.bn-muted { color: var(--vp-c-text-2); font-size: 13px; }
.bn button, .bn input { font: inherit; color: inherit; }
.bn button:focus-visible, .bn a:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 2px; }
.bn a { color: inherit; text-decoration: none; }
.bn a:hover { color: var(--vp-c-brand-1); }

/* Top bar */
.bn-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; }
.bn-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; line-height: 1; }
.bn-search { flex: 1 1 360px; max-width: 640px; display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 12px; background: var(--bn-card); border: 1px solid var(--bn-line); border-radius: 10px; }
.bn-search:focus-within, .bn-search.on { border-color: var(--vp-c-brand-1); }
.bn-search-icon { width: 18px; height: 18px; flex: none; fill: none; stroke: var(--vp-c-text-2); stroke-width: 2; stroke-linecap: round; }
.bn-search input { flex: 1; min-width: 0; height: 100%; border: 0; background: transparent; font-size: 15px; }
.bn-search input:focus-visible { outline: none; }
.bn-search input::-webkit-search-cancel-button { display: none; }
.bn-kbd { font-family: var(--vp-font-family-mono); font-size: 12px; color: var(--vp-c-text-2); border: 1px solid var(--bn-line); border-radius: 5px; padding: 1px 7px; }
.bn-tabs { margin-left: auto; }
.bn-seg { display: flex; gap: 3px; padding: 3px; background: var(--bn-card); border: 1px solid var(--bn-line); border-radius: 9px; }
.bn-seg a, .bn-seg button { border: 0; background: transparent; padding: 7px 14px; border-radius: 6px; font-size: 13px; font-weight: 600; color: var(--vp-c-text-2); cursor: pointer; }
.bn-seg a:hover, .bn-seg button:hover { color: var(--vp-c-text-1); }
.bn-seg .on, .bn-seg .on:hover { background: var(--vp-c-brand-1); color: var(--vp-c-white); }
.bn-seg-sm button { padding: 5px 12px; }
.bn-ghost { border: 1px solid var(--bn-line); background: transparent; border-radius: 7px; padding: 5px 10px; font-size: 12px; cursor: pointer; }
.bn-ghost:hover { border-color: var(--vp-c-brand-1); }
.bn-notice { margin: 0; padding: 10px 14px; border: 1px solid; border-radius: 8px; }
.bn-notice.danger { border-color: var(--vp-c-danger-1); background: var(--vp-c-danger-soft); }
.bn-notice.warn { border-color: var(--vp-c-warning-1); background: var(--vp-c-warning-soft); }
.bn-avatar { flex: none; width: 26px; height: 26px; display: grid; place-items: center; border-radius: 6px; font-weight: 700; font-size: 13px; text-transform: uppercase; background: var(--vp-c-default-soft); color: var(--vp-c-brand-1); }
.bn-avatar-lg { width: 44px; height: 44px; font-size: 20px; border-radius: 10px; }

/* Cards */
.bn-card { min-width: 0; display: flex; flex-direction: column; background: var(--bn-card); border: 1px solid var(--bn-line); border-radius: 12px; overflow: hidden; }
.bn-card-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--bn-line); }
.bn-head-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.bn-card h2 { margin: 0; padding: 0; border: 0; font-size: 16px; font-weight: 700; line-height: 1.3; letter-spacing: 0; }
.bn-scroll { overflow-x: auto; }
.bn-empty { margin: 0; padding: 24px 16px; color: var(--vp-c-text-2); text-align: center; }
.bn-sk { height: 38px; display: flex; align-items: center; padding: 0 16px; border-bottom: 1px solid var(--bn-line); }
.bn-sk:last-child { border-bottom: 0; }
.bn-sk span { height: 10px; border-radius: 5px; background: var(--vp-c-default-soft); }

/* Tables */
.bn-table { width: 100%; border-collapse: collapse; display: table; margin: 0; font-size: 14px; }
.bn-table tr { border: 0; background: transparent; }
.bn-table th, .bn-table td { border: 0; padding: 0 12px; height: 38px; text-align: left; white-space: nowrap; }
.bn-table thead th { height: 34px; background: var(--vp-c-bg-alt); border-bottom: 1px solid var(--bn-line); font-size: 12px; font-weight: 600; color: var(--vp-c-text-2); }
.bn-table tbody tr { border-bottom: 1px solid var(--bn-line); }
.bn-table tbody tr:last-child { border-bottom: 0; }
.bn-table tbody tr:hover { background: var(--vp-c-default-soft); }
.bn-table td { color: var(--vp-c-text-2); }
.bn-table th:first-child, .bn-table td:first-child { padding-left: 16px; }
.bn-table th:last-child, .bn-table td:last-child { padding-right: 16px; text-align: right; }
.bn-who { display: flex; align-items: center; gap: 10px; }
.bn-name { color: var(--vp-c-text-1); font-weight: 600; }
.bn-reason { white-space: normal !important; color: var(--vp-c-text-1) !important; min-width: 220px; }
.bn-act { font-weight: 600; color: var(--vp-c-text-1); }
.lifted .bn-act { text-decoration: line-through; color: var(--vp-c-text-2); }
.lifted .bn-reason { color: var(--vp-c-text-2) !important; }

/* Blacklist */
.bn-cols { display: flex; flex-wrap: wrap; gap: 16px; align-items: flex-start; }
.bn-players { flex: 999 1 640px; }
.bn-groups { flex: 1 1 340px; }
.bn-group { display: flex; flex-direction: column; gap: 6px; padding: 14px 16px; border-bottom: 1px solid var(--bn-line); }
.bn-group:last-child { border-bottom: 0; }
.bn-group-top { display: flex; justify-content: space-between; align-items: baseline; gap: 12px; font-size: 15px; }

/* Player */
.bn-ident { display: flex; flex-wrap: wrap; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--bn-line); }
.bn-ident .bn-head-text { flex: 1; }
.bn-ident-title { display: flex; flex-wrap: wrap; align-items: center; gap: 8px; }
.bn-badge { font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 999px; }
.bn-badge.banned { background: var(--vp-c-danger-soft); color: var(--vp-c-danger-1); }
.bn-badge.muted { background: var(--vp-c-warning-soft); color: var(--vp-c-warning-1); }
.bn-badge.blacklisted { background: #000; color: var(--vp-c-text-1); box-shadow: inset 0 0 0 1px var(--vp-c-gray-1); }
.bn-badge.clean { background: var(--vp-c-default-soft); color: var(--vp-c-text-2); }

@media (max-width: 640px) {
  .bn { padding: 16px; }
  .bn-tabs { margin-left: 0; }
}
</style>
