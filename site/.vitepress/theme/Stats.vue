<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'

// Public HTTPS endpoint of the server's stats module (GET /stats?sort=&player=).
const api = import.meta.env.DEV ? 'http://localhost:8090/stats' : 'https://stats.aechronis.net/stats'
const columns = [
  { key: 'kills', label: 'Kills' },
  { key: 'deaths', label: 'Deaths' },
  { key: 'kd', label: 'K/D' },
  { key: 'capsBroken', label: 'Caps broken' },
  { key: 'capsCompleted', label: 'Caps completed' },
  { key: 'playtime', label: 'Playtime' },
]
// Playtime arrives in seconds.
function show(key, value) {
  if (key !== 'playtime') return value
  const hours = Math.floor(value / 3600)
  return `${hours}h ${Math.floor((value % 3600) / 60)}m`
}

const nationKeys = ['kills', 'deaths', 'kd', 'playtime']
const mode = ref('players')
const scope = ref('all') // 'all' = all-time, 'war' = Saturday 1-4pm ET snapshots
const week = ref('')
const weeks = ref([])
// War snapshots track a rally graph instead of playtime.
const statColumns = computed(() => {
  const base = mode.value === 'nations' ? columns.filter(c => nationKeys.includes(c.key)) : columns
  return scope.value === 'war' ? base.filter(c => c.key !== 'playtime') : base
})
const tabs = computed(() => scope.value === 'war' ? [...statColumns.value, { key: 'rally', label: 'Rally' }] : statColumns.value)
const rally = ref([])
const tab = ref('kills')
const board = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')
const username = ref('')
const me = ref(null)
const notFound = ref('')

let latest = 0

async function load(player = me.value?.name ?? '') {
  const request = ++latest
  loading.value = true
  error.value = ''
  try {
    const query = new URLSearchParams({ sort: tab.value === 'rally' ? 'kills' : tab.value })
    if (scope.value === 'war') {
      query.set('scope', 'war')
      if (week.value) query.set('week', week.value)
    }
    if (mode.value === 'nations') query.set('type', 'nation')
    else if (player) query.set('player', player)
    const response = await fetch(`${api}?${query}`)
    if (!response.ok) throw new Error()
    const data = await response.json()
    if (request !== latest) return // a newer tab click superseded this response
    board.value = data.players
    total.value = data.total
    if (scope.value === 'war') {
      weeks.value = data.weeks
      rally.value = data.rally ?? []
      week.value = data.week ?? ''
    }
    if (player && mode.value === 'players') {
      me.value = data.player
      notFound.value = data.player ? '' : player
    }
  } catch {
    if (request !== latest) return
    error.value = 'Stats are unavailable right now. Please try again.'
  } finally { if (request === latest) loading.value = false }
}

function lookup() {
  const name = username.value.trim()
  if (!name) return
  mode.value = 'players' // player cards only exist on the player boards
  load(name)
}

function setScope(next) {
  scope.value = next
  if (!tabs.value.some(c => c.key === tab.value)) tab.value = 'kills'
  week.value = ''
  board.value = []
  load()
}

function setWeek() {
  board.value = []
  load()
}

function setMode(next) {
  mode.value = next
  if (!tabs.value.some(c => c.key === tab.value)) tab.value = 'kills'
  board.value = []
  load()
}

function select(key) {
  tab.value = key
  board.value = []
  load()
}

// Rally graph: players online per nation, sampled every ten minutes during war.
const palette = ['#e4572e', '#2e86ab', '#76b041', '#f2a900', '#7b2d8e', '#17bebb', '#d1495b', '#8d6a9f']
const chart = computed(() => {
  const samples = rally.value
  if (!samples.length) return null
  const peaks = {}
  for (const sample of samples) for (const [nation, count] of Object.entries(sample.nations)) peaks[nation] = Math.max(peaks[nation] ?? 0, count)
  const names = Object.keys(peaks).sort((a, b) => peaks[b] - peaks[a]).slice(0, palette.length)
  const max = Math.max(1, ...names.map(name => peaks[name]))
  const t0 = samples[0].time
  const span = Math.max(1, samples[samples.length - 1].time - t0)
  const x = time => 40 + ((time - t0) / span) * 540
  const y = count => 260 - (count / max) * 240
  const clock = time => new Date(time * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' })
  return {
    max,
    lines: names.map((name, i) => ({
      name,
      color: palette[i],
      points: samples.map(sample => `${x(sample.time)},${y(sample.nations[name] ?? 0)}`).join(' '),
      dots: samples.map(sample => ({ cx: x(sample.time), cy: y(sample.nations[name] ?? 0), label: `${name}: ${sample.nations[name] ?? 0} online at ${clock(sample.time)} ET` })),
    })),
    ticks: samples.filter((_, i) => i % 3 === 0).map(sample => ({ x: x(sample.time), label: clock(sample.time) })),
    grid: [0, max / 2, max].map(count => ({ y: y(count), label: Math.round(count) })),
  }
})

let timer
onMounted(() => {
  load()
  timer = setInterval(() => { if (!document.hidden) load() }, 30000)
})
onUnmounted(() => clearInterval(timer))
</script>

<template>
  <div class="stats">
    <form class="stats-lookup" @submit.prevent="lookup">
      <label for="stats-username">Look up a player</label>
      <div class="stats-lookup-row">
        <input id="stats-username" v-model="username" autocomplete="off" autocapitalize="none" :spellcheck="false" pattern="[A-Za-z0-9_]{3,16}" maxlength="16" placeholder="Minecraft username" required />
        <button type="submit" class="stats-primary" :disabled="loading">Look up</button>
      </div>
    </form>

    <p v-if="error" role="alert" class="stats-message stats-error">{{ error }}</p>
    <p v-if="notFound" role="status" class="stats-message">No stats recorded yet for {{ notFound }}.</p>

    <section v-if="me" class="stats-card" aria-labelledby="stats-me">
      <h2 id="stats-me">{{ me.name }}</h2>
      <dl>
        <div v-for="column in statColumns" :key="column.key">
          <dt>{{ column.label }}</dt>
          <dd>{{ show(column.key, me[column.key]) }} <span class="stats-rank">#{{ me.ranks[column.key] }}</span></dd>
        </div>
      </dl>
      <p class="stats-note">Rank among {{ total }} players with recorded stats.</p>
    </section>

    <h2>Leaderboards</h2>
    <div class="stats-modes" role="group" aria-label="Stats period">
      <button type="button" :aria-pressed="scope === 'all'" :class="{ active: scope === 'all' }" @click="setScope('all')">All-time</button>
      <button type="button" :aria-pressed="scope === 'war'" :class="{ active: scope === 'war' }" @click="setScope('war')">War</button>
      <select v-if="scope === 'war' && weeks.length" v-model="week" aria-label="War week" @change="setWeek">
        <option v-for="w in weeks" :key="w" :value="w">Week of {{ w }}</option>
      </select>
    </div>
    <div class="stats-modes" role="group" aria-label="Leaderboard type">
      <button type="button" :aria-pressed="mode === 'players'" :class="{ active: mode === 'players' }" @click="setMode('players')">Players</button>
      <button type="button" :aria-pressed="mode === 'nations'" :class="{ active: mode === 'nations' }" @click="setMode('nations')">Nations</button>
    </div>
    <div class="stats-layout">
      <div class="stats-tabs" role="tablist" aria-orientation="vertical">
        <button v-for="column in tabs" :key="column.key" type="button" role="tab" :aria-selected="tab === column.key" :class="{ active: tab === column.key }" @click="select(column.key)">{{ column.label }}</button>
      </div>
      <div class="stats-board" role="tabpanel">
        <template v-if="tab === 'rally'">
          <p v-if="!chart && !loading" role="status">No rally data has been recorded yet.</p>
          <figure v-else-if="chart" class="stats-chart">
            <svg viewBox="0 0 600 300" role="img" aria-label="Players online per nation during war, every ten minutes">
              <g v-for="line in chart.grid" :key="line.y">
                <line x1="40" x2="580" :y1="line.y" :y2="line.y" class="stats-grid" />
                <text x="34" :y="line.y + 4" text-anchor="end" class="stats-axis">{{ line.label }}</text>
              </g>
              <text v-for="tick in chart.ticks" :key="tick.x" :x="tick.x" y="282" text-anchor="middle" class="stats-axis">{{ tick.label }}</text>
              <g v-for="line in chart.lines" :key="line.name" :style="{ color: line.color }">
                <polyline :points="line.points" fill="none" stroke="currentColor" stroke-width="2" stroke-linejoin="round" />
                <circle v-for="dot in line.dots" :key="dot.cx" :cx="dot.cx" :cy="dot.cy" r="3" fill="currentColor"><title>{{ dot.label }}</title></circle>
              </g>
            </svg>
            <figcaption class="stats-legend">
              <span v-for="line in chart.lines" :key="line.name"><i :style="{ background: line.color }"></i>{{ line.name }}</span>
            </figcaption>
          </figure>
        </template>
        <p v-else-if="loading && !board.length" role="status">Loading stats…</p>
        <p v-else-if="!board.length && !error">{{ scope === 'war' ? 'No war stats have been recorded yet.' : 'No stats have been recorded yet.' }}</p>
        <ol v-else>
          <li v-for="row in board" :key="row.name" :class="{ 'stats-me': me && row.name === me.name }">
            <span class="stats-pos">{{ row.rank }}</span>
            <span class="stats-name">{{ row.name }}</span>
            <span class="stats-value">{{ show(tab, row[tab]) }}</span>
          </li>
        </ol>
      </div>
    </div>
    <p v-if="tab === 'rally'" class="stats-note">Players online per nation, sampled every 10 minutes during war (Saturdays 1pm-4pm Eastern).</p>
    <p v-else class="stats-note">Top 20 {{ mode }}{{ scope === 'war' ? ' (war)' : '' }} by {{ columns.find(column => column.key === tab).label.toLowerCase() }}. Refreshes every 30 seconds.{{ scope === 'war' ? ' War stats are recorded Saturdays 1pm-4pm Eastern, one snapshot per week.' : '' }}</p>
  </div>
</template>

<style scoped>
.stats { margin-top: 1.5rem; }
.stats-lookup label { display: block; font-weight: 600; font-size: .875rem; }
.stats-lookup-row { display: flex; gap: .5rem; margin-top: .5rem; max-width: 420px; }
.stats input { flex: 1; min-width: 0; border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .6rem; background: var(--vp-c-bg); font: inherit; }
.stats button { border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .5rem .8rem; font: inherit; font-size: .875rem; cursor: pointer; background: var(--vp-c-bg); }
.stats button:hover:not(:disabled) { border-color: var(--vp-c-brand-1); }
.stats button:disabled { opacity: .55; cursor: not-allowed; }
.stats .stats-primary { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: var(--vp-c-white); }
.stats button:focus-visible, .stats input:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 3px; }
.stats-card { margin-top: 1.5rem; padding: 1.25rem; border: 1px solid var(--vp-c-divider); border-radius: 12px; background: var(--vp-c-bg-soft); }
.stats-card h2 { margin: 0 0 .75rem; padding: 0; border: 0; }
.stats-card dl { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: .5rem 1.5rem; }
.stats-card dt { font-size: .875rem; color: var(--vp-c-text-2); }
.stats-card dd { margin: 0; font-size: 1.2rem; font-weight: 600; }
.stats-rank { font-size: .875rem; font-weight: 400; color: var(--vp-c-text-2); }
.stats-note { font-size: .875rem; line-height: 1.5; color: var(--vp-c-text-2); }
.stats-message { border-radius: 8px; padding: 1rem; background: var(--vp-c-bg-soft); }
.stats-error { border: 1px solid var(--vp-c-danger-1); }
.stats-modes { display: flex; flex-wrap: wrap; gap: .5rem; margin-bottom: 1rem; }
.stats select { border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .5rem .8rem; font: inherit; font-size: .875rem; background: var(--vp-c-bg); }
.stats .stats-modes button.active { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: var(--vp-c-white); }
.stats-layout { display: grid; grid-template-columns: 200px minmax(0, 1fr); gap: 1.5rem; align-items: start; }
.stats-tabs { display: flex; flex-direction: column; gap: .5rem; }
.stats .stats-tabs button { text-align: left; }
.stats .stats-tabs button.active { background: var(--vp-c-brand-1); border-color: var(--vp-c-brand-1); color: var(--vp-c-white); }
.stats-board { padding: 1rem 1.25rem; border: 1px solid var(--vp-c-divider); border-radius: 12px; }
.stats-board ol { margin: 0; padding: 0; list-style: none; }
.stats-board li { display: flex; gap: .5rem; padding: .25rem .5rem; border-radius: 4px; }
.stats-pos { width: 1.75rem; color: var(--vp-c-text-2); }
.stats-name { flex: 1; min-width: 0; overflow-wrap: anywhere; }
.stats-value { font-weight: 600; }
.stats .stats-me { background: var(--vp-c-brand-soft); }
@media (max-width: 640px) { .stats-layout { grid-template-columns: 1fr; } .stats-tabs { flex-direction: row; flex-wrap: wrap; } }
.stats-chart { margin: 0; }
.stats-chart svg { width: 100%; height: auto; }
.stats-grid { stroke: var(--vp-c-divider); stroke-width: 1; }
.stats-axis { fill: var(--vp-c-text-2); font-size: 11px; }
.stats-legend { display: flex; flex-wrap: wrap; gap: .25rem 1rem; margin-top: .75rem; font-size: .875rem; }
.stats-legend i { display: inline-block; width: .75rem; height: .75rem; border-radius: 2px; margin-right: .35rem; }
</style>
