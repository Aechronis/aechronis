<script setup>
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'

// Public HTTPS endpoint of the server's stats module (GET /stats?sort=&player=&scope=&week=).
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

async function request(params) {
  const response = await fetch(`${api}?${new URLSearchParams(params)}`)
  if (!response.ok) throw new Error()
  return response.json()
}

// ---- Leaderboards -------------------------------------------------------

const nationKeys = ['kills', 'deaths', 'kd', 'playtime']
const mode = ref('players')
const scope = ref('all') // 'all' = all-time, 'war' = Saturday 1-4pm ET snapshots
const week = ref('')
const weeks = ref([])
const rally = ref([])
// War snapshots track a rally graph instead of playtime.
const statColumns = computed(() => {
  const base = mode.value === 'nations' ? columns.filter(c => nationKeys.includes(c.key)) : columns
  return scope.value === 'war' ? base.filter(c => c.key !== 'playtime') : base
})
const tabs = computed(() => scope.value === 'war' ? [...statColumns.value, { key: 'rally', label: 'Rally' }] : statColumns.value)
const tab = ref('kills')
const board = ref([])
const total = ref(0)
const loading = ref(false)
const error = ref('')

let latest = 0

async function load() {
  const requestId = ++latest
  loading.value = true
  error.value = ''
  try {
    const params = { sort: tab.value === 'rally' ? 'kills' : tab.value }
    if (scope.value === 'war') {
      params.scope = 'war'
      if (week.value) params.week = week.value
    }
    if (mode.value === 'nations') params.type = 'nation'
    const data = await request(params)
    if (requestId !== latest) return // a newer click superseded this response
    board.value = data.players
    total.value = data.total
    if (scope.value === 'war') {
      weeks.value = data.weeks
      week.value = data.week ?? ''
      rally.value = data.rally ?? []
    }
  } catch {
    if (requestId !== latest) return
    error.value = 'Stats are unavailable right now. Please try again.'
  } finally { if (requestId === latest) loading.value = false }
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

// ---- Player lookup ------------------------------------------------------

const username = ref('')
const lookedUp = ref('') // canonical name of the player on the card
const period = ref('all') // 'all' or a war week
const card = ref(null) // { kind: 'ok' | 'unknown' | 'absent', ... }
const cardBusy = ref(false)
const cardColumns = computed(() => period.value === 'all' ? columns : columns.filter(c => c.key !== 'playtime'))

let cardLatest = 0

async function refreshCard() {
  const requestId = ++cardLatest
  cardBusy.value = true
  try {
    const params = { sort: 'kills', player: lookedUp.value }
    if (period.value !== 'all') Object.assign(params, { scope: 'war', week: period.value })
    const data = await request(params)
    if (requestId !== cardLatest) return
    card.value = data.player
      ? { kind: 'ok', stats: data.player, total: data.total }
      : { kind: 'absent', name: lookedUp.value, week: period.value }
  } catch {
    if (requestId === cardLatest) error.value = 'Stats are unavailable right now. Please try again.'
  } finally { if (requestId === cardLatest) cardBusy.value = false }
}

async function lookup() {
  const name = username.value.trim()
  if (!name) return
  const requestId = ++cardLatest
  cardBusy.value = true
  error.value = ''
  try {
    const data = await request({ sort: 'kills', player: name })
    if (requestId !== cardLatest) return
    if (!data.player) {
      lookedUp.value = ''
      card.value = { kind: 'unknown', name }
      return
    }
    lookedUp.value = data.player.name
    period.value = 'all'
    card.value = { kind: 'ok', stats: data.player, total: data.total }
  } catch {
    if (requestId === cardLatest) error.value = 'Stats are unavailable right now. Please try again.'
  } finally { if (requestId === cardLatest) cardBusy.value = false }
}

// ---- Rally graph --------------------------------------------------------
// Players online per nation, one sample every ten minutes during war. Drag (or hover, or use
// the arrow keys) along the graph to read any moment.

const W = 720
const H = 340
const left = 48
const right = 16
const top = 16
const bottom = 36
const hidden = ref([])
const cursor = ref(0)
const dragging = ref(false)
const svg = ref(null)

function clock(time) {
  return new Date(time * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' })
}

// Round the axis step to 1/2/5 x 10^n so ticks stay readable from a handful of players to hundreds.
function niceStep(max) {
  const rough = max / 5
  const power = 10 ** Math.floor(Math.log10(rough))
  const unit = rough / power
  return (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * power
}

const series = computed(() => {
  const peaks = {}
  for (const sample of rally.value) {
    for (const [nation, count] of Object.entries(sample.nations)) peaks[nation] = Math.max(peaks[nation] ?? 0, count)
  }
  return Object.keys(peaks)
    .sort((a, b) => peaks[b] - peaks[a])
    .map((name, i) => ({ name, color: `hsl(${(i * 137.5) % 360} 65% 48%)` }))
})

const chart = computed(() => {
  const samples = rally.value
  if (!samples.length) return null
  const shown = series.value.filter(s => !hidden.value.includes(s.name))
  const max = Math.max(1, ...samples.flatMap(sample => shown.map(s => sample.nations[s.name] ?? 0)))
  const step = niceStep(max)
  const ceiling = Math.ceil(max / step) * step
  const t0 = samples[0].time
  const span = Math.max(1, samples[samples.length - 1].time - t0)
  const x = time => left + ((time - t0) / span) * (W - left - right)
  const y = count => H - bottom - (count / ceiling) * (H - top - bottom)
  const grid = []
  for (let value = 0; value <= ceiling; value += step) grid.push({ y: y(value), label: value })
  const every = Math.max(1, Math.ceil(samples.length / 7))
  return {
    xs: samples.map(sample => x(sample.time)),
    grid,
    ticks: samples.map((sample, i) => ({ x: x(sample.time), label: clock(sample.time), i })).filter(tick => tick.i % every === 0),
    lines: shown.map(s => ({
      ...s,
      points: samples.map(sample => `${x(sample.time)},${y(sample.nations[s.name] ?? 0)}`).join(' '),
      at: samples.map(sample => y(sample.nations[s.name] ?? 0)),
    })),
  }
})

// Everything at the cursor: nations sorted by how many were online.
const readout = computed(() => {
  const sample = rally.value[cursor.value]
  if (!sample) return null
  const rows = series.value
    .map(s => ({ ...s, count: sample.nations[s.name] ?? 0 }))
    .sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
  return { time: clock(sample.time), rows, sum: rows.reduce((acc, row) => acc + row.count, 0) }
})

watch(rally, samples => { cursor.value = Math.max(0, samples.length - 1) })

function toggle(name) {
  hidden.value = hidden.value.includes(name) ? hidden.value.filter(n => n !== name) : [...hidden.value, name]
}

function moveTo(event) {
  if (!chart.value || !svg.value) return
  const box = svg.value.getBoundingClientRect()
  const x = ((event.clientX - box.left) / box.width) * W
  let best = 0
  chart.value.xs.forEach((value, i) => { if (Math.abs(value - x) < Math.abs(chart.value.xs[best] - x)) best = i })
  cursor.value = best
}

function down(event) {
  dragging.value = true
  try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* keep dragging without capture */ }
  moveTo(event)
}

function move(event) {
  if (dragging.value || event.pointerType === 'mouse') moveTo(event)
}

function key(event) {
  const last = rally.value.length - 1
  const steps = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1 }
  if (event.key === 'Home') cursor.value = 0
  else if (event.key === 'End') cursor.value = last
  else if (steps[event.key]) cursor.value = Math.min(last, Math.max(0, cursor.value + steps[event.key]))
  else return
  event.preventDefault()
}

let timer
onMounted(async () => {
  load()
  // The war weeks power the lookup card's period picker.
  try { weeks.value = (await request({ scope: 'war' })).weeks } catch { /* the leaderboard load reports errors */ }
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
        <button type="submit" class="stats-primary" :disabled="cardBusy">Look up</button>
      </div>
    </form>

    <p v-if="error" role="alert" class="stats-message stats-error">{{ error }}</p>

    <section v-if="card" class="stats-card" aria-live="polite">
      <template v-if="card.kind === 'unknown'">
        <h2>Player not found</h2>
        <p class="stats-empty">No player named <strong>{{ card.name }}</strong> has any recorded stats. Check the spelling, or they may not have played since stats tracking started.</p>
      </template>
      <template v-else>
        <div class="stats-card-head">
          <h2>{{ lookedUp }}</h2>
          <label class="stats-period">
            <span>Period</span>
            <select v-model="period" @change="refreshCard">
              <option value="all">All-time</option>
              <option v-for="w in weeks" :key="w" :value="w">War of {{ w }}</option>
            </select>
          </label>
        </div>
        <template v-if="card.kind === 'absent'">
          <p class="stats-empty"><strong>{{ lookedUp }}</strong> didn't play in the war of {{ card.week }}. No stats were recorded for them that day.</p>
        </template>
        <template v-else>
          <dl>
            <div v-for="column in cardColumns" :key="column.key">
              <dt>{{ column.label }}</dt>
              <dd>{{ show(column.key, card.stats[column.key]) }} <span class="stats-rank">#{{ card.stats.ranks[column.key] }}</span></dd>
            </div>
          </dl>
          <p class="stats-note">Rank among {{ card.total }} players with recorded stats{{ period === 'all' ? '' : ` in the war of ${period}` }}.</p>
        </template>
      </template>
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
          <div v-else-if="chart" class="stats-chart">
            <svg
              ref="svg"
              :viewBox="`0 0 ${W} ${H}`"
              role="slider"
              tabindex="0"
              :aria-valuemin="0"
              :aria-valuemax="rally.length - 1"
              :aria-valuenow="cursor"
              :aria-valuetext="readout ? `${readout.time}: ${readout.sum} players online` : ''"
              aria-label="Players online per nation during war. Drag, or use the arrow keys, to read any moment."
              @pointerdown="down"
              @pointermove="move"
              @pointerup="dragging = false"
              @pointercancel="dragging = false"
              @keydown="key"
            >
              <g v-for="line in chart.grid" :key="line.label">
                <line :x1="left" :x2="W - right" :y1="line.y" :y2="line.y" class="stats-grid" />
                <text :x="left - 8" :y="line.y + 4" text-anchor="end" class="stats-axis">{{ line.label }}</text>
              </g>
              <text v-for="tick in chart.ticks" :key="tick.i" :x="tick.x" :y="H - 12" text-anchor="middle" class="stats-axis">{{ tick.label }}</text>
              <polyline v-for="line in chart.lines" :key="line.name" :points="line.points" fill="none" :stroke="line.color" stroke-width="2" stroke-linejoin="round" />
              <line :x1="chart.xs[cursor]" :x2="chart.xs[cursor]" :y1="top" :y2="H - bottom" class="stats-cursor" />
              <circle v-for="line in chart.lines" :key="line.name" :cx="chart.xs[cursor]" :cy="line.at[cursor]" r="4" :fill="line.color" class="stats-dot" />
            </svg>
            <div v-if="readout" class="stats-readout" aria-live="off">
              <p class="stats-readout-head"><strong>{{ readout.time }} ET</strong> <span>{{ readout.sum }} online across {{ readout.rows.length }} nations</span></p>
              <ul>
                <li v-for="row in readout.rows" :key="row.name" :class="{ off: hidden.includes(row.name) }">
                  <button type="button" :aria-pressed="!hidden.includes(row.name)" @click="toggle(row.name)">
                    <i :style="{ background: row.color }"></i>
                    <span class="stats-name">{{ row.name }}</span>
                    <span class="stats-value">{{ row.count }}</span>
                  </button>
                </li>
              </ul>
            </div>
          </div>
        </template>
        <p v-else-if="loading && !board.length" role="status">Loading stats…</p>
        <p v-else-if="!board.length && !error">{{ scope === 'war' ? 'No war stats have been recorded yet.' : 'No stats have been recorded yet.' }}</p>
        <ol v-else>
          <li v-for="row in board" :key="row.name" :class="{ 'stats-me': row.name === lookedUp }">
            <span class="stats-pos">{{ row.rank }}</span>
            <span class="stats-name">{{ row.name }}</span>
            <span class="stats-value">{{ show(tab, row[tab]) }}</span>
          </li>
        </ol>
      </div>
    </div>
    <p v-if="tab === 'rally'" class="stats-note">Players online per nation, sampled every 10 minutes during war (Saturdays 1pm-4pm Eastern). Click a nation to hide or show it.</p>
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
.stats button:focus-visible, .stats input:focus-visible, .stats select:focus-visible, .stats svg:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 3px; }
.stats select { border: 1px solid var(--vp-c-divider); border-radius: 6px; padding: .5rem .8rem; font: inherit; font-size: .875rem; background: var(--vp-c-bg); }
.stats-card { margin-top: 1.5rem; padding: 1.25rem; border: 1px solid var(--vp-c-divider); border-radius: 12px; background: var(--vp-c-bg-soft); }
.stats-card h2 { margin: 0; padding: 0; border: 0; }
.stats-card-head { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: .75rem; margin-bottom: .75rem; }
.stats-period { display: flex; align-items: center; gap: .5rem; font-size: .875rem; color: var(--vp-c-text-2); }
.stats-empty { margin: .75rem 0 0; line-height: 1.6; color: var(--vp-c-text-2); }
.stats-card dl { margin: 0; display: grid; grid-template-columns: repeat(auto-fit, minmax(150px, 1fr)); gap: .5rem 1.5rem; }
.stats-card dt { font-size: .875rem; color: var(--vp-c-text-2); }
.stats-card dd { margin: 0; font-size: 1.2rem; font-weight: 600; }
.stats-rank { font-size: .875rem; font-weight: 400; color: var(--vp-c-text-2); }
.stats-note { font-size: .875rem; line-height: 1.5; color: var(--vp-c-text-2); }
.stats-message { border-radius: 8px; padding: 1rem; background: var(--vp-c-bg-soft); }
.stats-error { border: 1px solid var(--vp-c-danger-1); }
.stats-modes { display: flex; flex-wrap: wrap; gap: .5rem; margin-bottom: 1rem; }
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
.stats-chart svg { width: 100%; height: auto; cursor: ew-resize; touch-action: none; user-select: none; }
.stats-grid { stroke: var(--vp-c-divider); stroke-width: 1; }
.stats-axis { fill: var(--vp-c-text-2); font-size: 11px; }
.stats-cursor { stroke: var(--vp-c-text-2); stroke-width: 1; stroke-dasharray: 4 3; }
.stats-dot { stroke: var(--vp-c-bg); stroke-width: 2; }
.stats-readout { margin-top: .75rem; border-top: 1px solid var(--vp-c-divider); padding-top: .75rem; }
.stats-readout-head { margin: 0 0 .5rem; display: flex; flex-wrap: wrap; justify-content: space-between; gap: .5rem; }
.stats-readout-head span { color: var(--vp-c-text-2); font-size: .875rem; }
.stats-readout ul { margin: 0; padding: 0; list-style: none; display: grid; grid-template-columns: repeat(auto-fill, minmax(190px, 1fr)); gap: .25rem .75rem; max-height: 220px; overflow-y: auto; }
.stats .stats-readout button { display: flex; align-items: center; gap: .5rem; width: 100%; border: 0; padding: .2rem .25rem; background: transparent; text-align: left; }
.stats-readout i { flex: none; width: .75rem; height: .75rem; border-radius: 2px; }
.stats-readout li.off { opacity: .4; }
@media (max-width: 640px) { .stats-layout { grid-template-columns: 1fr; } .stats-tabs { flex-direction: row; flex-wrap: wrap; } }
</style>
