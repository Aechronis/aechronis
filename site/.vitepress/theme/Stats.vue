<script setup>
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'

// Public HTTPS endpoint of the server's stats module (GET /stats?sort=&player=&type=&scope=&week=).
const api = import.meta.env.DEV ? 'http://localhost:8090/stats' : 'https://stats.aechronis.net/stats'

const COLUMNS = {
  kills: 'Kills',
  deaths: 'Deaths',
  kd: 'K/D',
  capsBroken: 'Caps broken',
  capsCompleted: 'Caps completed',
  playtime: 'Playtime',
}
const PLAYER_KEYS = ['kills', 'deaths', 'kd', 'capsBroken', 'capsCompleted', 'playtime']
const NATION_KEYS = ['kills', 'deaths', 'kd', 'playtime']
// War snapshots leave out playtime; they track the rally graph instead.
const keysFor = (type, war) => (type === 'nation' ? NATION_KEYS : PLAYER_KEYS).filter(k => !war || k !== 'playtime')

function show(key, value) {
  if (value == null) return '–'
  if (key !== 'playtime') return value
  return `${Math.floor(value / 3600)}h ${Math.floor((value % 3600) / 60)}m`
}
function weekLabel(week) {
  if (!week) return ''
  return new Date(`${week}T12:00:00`).toLocaleDateString(undefined, { weekday: 'short', month: 'short', day: 'numeric' })
}
function clock(time) {
  return new Date(time * 1000).toLocaleTimeString([], { hour: 'numeric', minute: '2-digit', timeZone: 'America/New_York' })
}

async function request(params) {
  const response = await fetch(`${api}?${new URLSearchParams(params)}`)
  if (!response.ok) throw new Error()
  return response.json()
}

const error = ref('')
const failed = () => { error.value = 'Stats are unavailable right now. Please try again.' }

// ---- Period & leaderboard -------------------------------------------------

const scope = ref('all') // 'all' | 'war'
const week = ref('')
const weeks = ref([])
const mode = ref('player') // 'player' | 'nation'
const sort = ref('kills')
const board = ref([])
const total = ref(0)
const rally = ref([])
const loading = ref(false)
const columns = computed(() => keysFor(mode.value, scope.value === 'war'))

// Every name seen so far feeds the search suggestions.
const known = ref(new Map()) // lowercase name -> { name, type }
function remember(names, type) {
  const next = new Map(known.value)
  for (const name of names) if (name && !next.has(`${type}:${name.toLowerCase()}`)) next.set(`${type}:${name.toLowerCase()}`, { name, type })
  known.value = next
}

let latest = 0
async function load() {
  const id = ++latest
  loading.value = true
  try {
    const params = { sort: sort.value }
    if (mode.value === 'nation') params.type = 'nation'
    if (scope.value === 'war') {
      params.scope = 'war'
      if (week.value) params.week = week.value
    }
    const data = await request(params)
    if (id !== latest) return
    error.value = ''
    board.value = data.players
    total.value = data.total
    remember(data.players.map(p => p.name), mode.value)
    if (scope.value === 'war') {
      weeks.value = data.weeks
      week.value = data.week ?? ''
      rally.value = data.rally ?? []
      remember(rally.value.flatMap(s => Object.keys(s.nations)), 'nation')
    }
    if (!selected.value && board.value.length) select({ name: board.value[0].name, type: mode.value }, false)
  } catch {
    if (id === latest) failed()
  } finally {
    if (id === latest) loading.value = false
  }
}

function setScope(next) {
  if (scope.value === next) return
  scope.value = next
  week.value = ''
  if (!columns.value.includes(sort.value)) sort.value = 'kills'
  board.value = []
  load().then(syncProfile)
}
function setWeek() {
  board.value = []
  load().then(syncProfile)
}
// The profile follows the page's period: all-time, or the war being viewed.
function syncProfile() {
  if (!selected.value) return
  period.value = scope.value === 'war' && week.value ? week.value : 'all'
  loadProfile(true)
}
function setMode(next) {
  if (mode.value === next) return
  mode.value = next
  if (!columns.value.includes(sort.value)) sort.value = 'kills'
  board.value = []
  load()
}
function setSort(key) {
  if (sort.value === key) return
  sort.value = key
  load()
}

// ---- Search ---------------------------------------------------------------

const query = ref('')
const searchOpen = ref(false)
const active = ref(0)
const searchBusy = ref(false)
const notFound = ref('')
const searchInput = ref(null)

const suggestions = computed(() => {
  const q = query.value.trim().toLowerCase()
  if (!q) return []
  const all = [...known.value.values()]
  const starts = all.filter(e => e.name.toLowerCase().startsWith(q))
  const contains = all.filter(e => !e.name.toLowerCase().startsWith(q) && e.name.toLowerCase().includes(q))
  return [...starts, ...contains].slice(0, 7)
})
// The last option always looks the exact name up, so anyone with stats can be found.
const options = computed(() => {
  const q = query.value.trim()
  if (!q) return []
  const exact = suggestions.value.some(s => s.name.toLowerCase() === q.toLowerCase())
  return exact ? suggestions.value : [...suggestions.value, { name: q, type: 'lookup' }]
})
watch(query, () => { active.value = 0; notFound.value = ''; searchOpen.value = true })

async function choose(option) {
  if (!option) return
  searchOpen.value = false
  if (option.type !== 'lookup') {
    query.value = ''
    select(option)
    return
  }
  // Exact lookup: try players, then nations.
  searchBusy.value = true
  try {
    const [p, n] = await Promise.all([
      request({ sort: 'kills', player: option.name }),
      request({ sort: 'kills', player: option.name, type: 'nation' }),
    ])
    const hit = p.player ? { name: p.player.name, type: 'player' } : n.player ? { name: n.player.name, type: 'nation' } : null
    if (!hit) { notFound.value = option.name; searchOpen.value = true; return }
    remember([hit.name], hit.type)
    query.value = ''
    select(hit)
  } catch { failed() } finally { searchBusy.value = false }
}

function searchKey(event) {
  const count = options.value.length
  if (event.key === 'ArrowDown' && count) { active.value = (active.value + 1) % count; searchOpen.value = true; event.preventDefault() }
  else if (event.key === 'ArrowUp' && count) { active.value = (active.value - 1 + count) % count; event.preventDefault() }
  else if (event.key === 'Enter') { choose(options.value[active.value]); event.preventDefault() }
  else if (event.key === 'Escape') { searchOpen.value = false }
}
function slashFocus(event) {
  if (event.key !== '/' || ['INPUT', 'SELECT', 'TEXTAREA'].includes(document.activeElement?.tagName)) return
  event.preventDefault()
  searchInput.value?.focus()
}

// ---- Profile --------------------------------------------------------------

const selected = ref(null) // { name, type }
const period = ref('all') // 'all' | a war week
const profile = ref(null) // { stats, total } | { absent: true }
const history = ref([])
const profileBusy = ref(false)
const profileKeys = computed(() => selected.value ? keysFor(selected.value.type, period.value !== 'all') : [])

function select(entry, followScope = true) {
  selected.value = entry
  period.value = followScope && scope.value === 'war' && week.value ? week.value : 'all'
  if (entry.type === 'nation') focus.value = entry.name
  loadProfile(true)
}

let profileLatest = 0
async function loadProfile(withHistory = false) {
  const entry = selected.value
  if (!entry) return
  const id = ++profileLatest
  profileBusy.value = true
  try {
    const base = { sort: 'kills', player: entry.name }
    if (entry.type === 'nation') base.type = 'nation'
    const params = period.value === 'all' ? base : { ...base, scope: 'war', week: period.value }
    const data = await request(params)
    if (id !== profileLatest) return
    profile.value = data.player ? { stats: data.player, total: data.total } : { absent: true }
    if (data.player) selected.value = { ...entry, name: data.player.name }
    if (withHistory) {
      history.value = []
      const recent = weeks.value.slice(0, 6)
      const rows = await Promise.all(recent.map(w => request({ ...base, scope: 'war', week: w }).then(d => ({ week: w, stats: d.player, total: d.total })).catch(() => ({ week: w, stats: null }))))
      if (id !== profileLatest) return
      history.value = rows
    }
  } catch {
    if (id === profileLatest) failed()
  } finally {
    if (id === profileLatest) profileBusy.value = false
  }
}
const historyMax = computed(() => Math.max(1, ...history.value.map(h => h.stats?.kills ?? 0)))
const playedWars = computed(() => history.value.filter(h => h.stats).length)

// ---- Rally graph ----------------------------------------------------------
// Players online per nation, one sample every ten minutes during war. The six biggest
// nations get colours and end labels; the rest stay grey until highlighted.

const PALETTE = ['#7c8cff', '#f0903e', '#2fb39c', '#e2628f', '#d4ac1c', '#4ea3f0']
const focus = ref(null)
const cursor = ref(0)
const dragging = ref(false)
const plot = ref(null)
const size = ref({ w: 640, h: 300 })
const PAD = { l: 34, r: 96, t: 12, b: 26 }
let observer

function niceStep(max) {
  const rough = max / 4
  const power = 10 ** Math.floor(Math.log10(rough))
  const unit = rough / power
  return Math.max(1, (unit <= 1 ? 1 : unit <= 2 ? 2 : unit <= 5 ? 5 : 10) * power)
}

const series = computed(() => {
  const peaks = {}
  for (const sample of rally.value) for (const [nation, count] of Object.entries(sample.nations)) peaks[nation] = Math.max(peaks[nation] ?? 0, count)
  return Object.keys(peaks)
    .sort((a, b) => peaks[b] - peaks[a] || a.localeCompare(b))
    .map((name, i) => ({ name, peak: peaks[name], color: PALETTE[i] ?? null }))
})
const colorOf = s => s.color ?? (focus.value === s.name ? 'var(--vp-c-text-1)' : 'var(--vp-c-text-3)')

// The war runs 1-4pm ET and the server records one sample per 10 minute slot, so the x axis is
// laid out in whole slots across the full window; a slot with no sample is left as a gap.
const SLOT = 600
const WAR_SLOTS = 18

const chart = computed(() => {
  const samples = rally.value
  if (!samples.length) return null
  const { w, h } = size.value
  const max = Math.max(1, ...samples.flatMap(s => Object.values(s.nations)))
  const step = niceStep(max)
  const ceiling = Math.ceil(max / step) * step
  const slotOf = time => Math.floor(time / SLOT)
  const first = slotOf(samples[0].time)
  const slots = Math.max(WAR_SLOTS, slotOf(samples[samples.length - 1].time) - first)
  const x = time => PAD.l + ((slotOf(time) - first) / slots) * (w - PAD.l - PAD.r)
  const y = v => h - PAD.b - (v / ceiling) * (h - PAD.t - PAD.b)
  const grid = []
  for (let v = 0; v <= ceiling; v += step) grid.push({ y: y(v), label: v })
  // Ticks on the half hour (every third slot), thinned further if the chart is narrow.
  const tickEvery = 3 * Math.max(1, Math.ceil((slots / 3) / Math.max(2, Math.floor((w - PAD.l - PAD.r) / 80))))
  const ticks = []
  for (let i = 0; i <= slots; i += tickEvery) ticks.push({ i, x: PAD.l + (i / slots) * (w - PAD.l - PAD.r), label: clock((first + i) * SLOT) })
  // Break a line wherever slots were skipped instead of drawing across the gap.
  const segmentsFor = name => {
    const parts = []
    let current = []
    samples.forEach((sample, i) => {
      if (i && slotOf(sample.time) - slotOf(samples[i - 1].time) > 1) { parts.push(current); current = [] }
      current.push(`${x(sample.time)},${y(sample.nations[name] ?? 0)}`)
    })
    parts.push(current)
    return parts.map(p => p.join(' '))
  }
  const lines = series.value.map(s => {
    const on = focus.value === s.name
    const dim = focus.value && !on
    return {
      ...s,
      stroke: colorOf(s),
      width: on ? 3.5 : s.color ? 2.25 : 1.25,
      opacity: dim ? 0.15 : s.color || on ? 1 : 0.7,
      segments: segmentsFor(s.name),
      at: samples.map(sample => y(sample.nations[s.name] ?? 0)),
      labelled: Boolean(s.color) || on,
    }
  })
  // Draw grey first and the highlighted line last so it sits on top.
  const order = [...lines].sort((a, b) => (a.name === focus.value) - (b.name === focus.value) || Boolean(a.color) - Boolean(b.color))
  // End labels, nudged apart so they never overlap.
  const last = samples.length - 1
  const labels = lines.filter(l => l.labelled).map(l => ({ name: l.name, stroke: l.stroke, opacity: l.opacity, y: l.at[last] + 4 })).sort((a, b) => a.y - b.y)
  for (let i = 1; i < labels.length; i++) labels[i].y = Math.max(labels[i].y, labels[i - 1].y + 14)
  const totals = samples.map(s => Object.values(s.nations).reduce((a, b) => a + b, 0))
  const peakAt = totals.indexOf(Math.max(...totals))
  return {
    w, h, ceiling,
    xs: samples.map(s => x(s.time)),
    grid,
    ticks,
    order,
    dots: lines.filter(l => l.labelled),
    labels,
    labelX: x(samples[last].time) + 10,
    peak: { count: totals[peakAt], time: clock(samples[peakAt].time) },
    totals,
  }
})

const readout = computed(() => {
  const sample = rally.value[cursor.value]
  if (!sample) return null
  const rows = series.value.map(s => ({ ...s, stroke: colorOf(s), count: sample.nations[s.name] ?? 0 })).sort((a, b) => b.count - a.count || a.name.localeCompare(b.name))
  const top = Math.max(1, rows[0]?.count ?? 1)
  return { time: clock(sample.time), sum: chart.value?.totals[cursor.value] ?? 0, rows: rows.map(r => ({ ...r, pct: (r.count / top) * 100 })) }
})

watch(rally, (samples, old) => { if (!old?.length || cursor.value >= samples.length) cursor.value = Math.max(0, samples.length - 1) })

function toggleFocus(name) { focus.value = focus.value === name ? null : name }
function moveTo(event) {
  if (!chart.value || !plot.value) return
  const box = plot.value.getBoundingClientRect()
  const px = event.clientX - box.left
  let best = 0
  chart.value.xs.forEach((v, i) => { if (Math.abs(v - px) < Math.abs(chart.value.xs[best] - px)) best = i })
  cursor.value = best
}
function down(event) {
  dragging.value = true
  try { event.currentTarget.setPointerCapture(event.pointerId) } catch { /* keep dragging without capture */ }
  moveTo(event)
}
function move(event) { if (dragging.value || event.pointerType === 'mouse') moveTo(event) }
function chartKey(event) {
  const last = rally.value.length - 1
  const steps = { ArrowLeft: -1, ArrowDown: -1, ArrowRight: 1, ArrowUp: 1 }
  if (event.key === 'Home') cursor.value = 0
  else if (event.key === 'End') cursor.value = last
  else if (steps[event.key]) cursor.value = Math.min(last, Math.max(0, cursor.value + steps[event.key]))
  else return
  event.preventDefault()
}
function watchPlot() {
  observer?.disconnect()
  if (!plot.value) return
  observer = new ResizeObserver(([entry]) => {
    const { width, height } = entry.contentRect
    if (width > 0 && height > 0) size.value = { w: Math.round(width), h: Math.round(height) }
  })
  observer.observe(plot.value)
}
watch(() => scope.value === 'war' && Boolean(chart.value), on => { if (on) nextTick(watchPlot) })

// ---- Lifecycle ------------------------------------------------------------

let timer
onMounted(async () => {
  window.addEventListener('keydown', slashFocus)
  load()
  try {
    const [nations, war] = await Promise.all([request({ sort: 'kills', type: 'nation' }), request({ sort: 'kills', scope: 'war' })])
    remember(nations.players.map(p => p.name), 'nation')
    weeks.value = war.weeks
    if (selected.value && !history.value.length) loadProfile(true)
  } catch { /* the leaderboard load reports errors */ }
  timer = setInterval(() => { if (!document.hidden) load() }, 30000)
})
onUnmounted(() => {
  clearInterval(timer)
  observer?.disconnect()
  window.removeEventListener('keydown', slashFocus)
})
</script>

<template>
  <div class="st" :class="{ 'st-war': scope === 'war' }">
    <header class="st-bar">
      <h1 class="st-title">Stats</h1>

      <div class="st-search" @focusout="e => { if (!e.currentTarget.contains(e.relatedTarget)) searchOpen = false }">
        <svg aria-hidden="true" viewBox="0 0 24 24" class="st-search-icon"><circle cx="11" cy="11" r="7" /><path d="M20 20l-3.5-3.5" /></svg>
        <label for="st-q" class="st-sr">Search a player or nation</label>
        <input
          id="st-q"
          ref="searchInput"
          v-model="query"
          type="search"
          role="combobox"
          autocomplete="off"
          autocapitalize="none"
          :spellcheck="false"
          maxlength="32"
          placeholder="Search a player or nation…"
          aria-controls="st-options"
          :aria-expanded="searchOpen && Boolean(query.trim())"
          :aria-activedescendant="searchOpen && options.length ? `st-opt-${active}` : undefined"
          @focus="searchOpen = true"
          @keydown="searchKey"
        />
        <kbd class="st-kbd" aria-hidden="true">/</kbd>
        <ul v-if="searchOpen && query.trim()" id="st-options" role="listbox" class="st-options">
          <li
            v-for="(option, i) in options"
            :id="`st-opt-${i}`"
            :key="option.type + option.name"
            role="option"
            :aria-selected="i === active"
            :class="{ active: i === active }"
            @mousedown.prevent="choose(option)"
            @mousemove="active = i"
          >
            <template v-if="option.type === 'lookup'">
              <span class="st-opt-name">{{ searchBusy ? 'Looking up' : 'Look up' }} “{{ option.name }}”</span>
              <span class="st-opt-meta">Enter</span>
            </template>
            <template v-else>
              <span class="st-avatar" :class="option.type">{{ option.name[0] }}</span>
              <span class="st-opt-name">{{ option.name }}</span>
              <span class="st-opt-meta">{{ option.type === 'nation' ? 'Nation' : 'Player' }}</span>
            </template>
          </li>
          <li v-if="notFound" class="st-opt-empty" role="presentation">No player or nation named “{{ notFound }}” has recorded stats.</li>
        </ul>
      </div>

      <div class="st-controls">
        <div class="st-seg" role="group" aria-label="Period">
          <button type="button" :aria-pressed="scope === 'all'" @click="setScope('all')">All-time</button>
          <button type="button" :aria-pressed="scope === 'war'" @click="setScope('war')">War</button>
        </div>
        <select v-if="scope === 'war' && weeks.length" v-model="week" class="st-select" aria-label="War week" @change="setWeek">
          <option v-for="w in weeks" :key="w" :value="w">{{ weekLabel(w) }}</option>
        </select>
      </div>
    </header>

    <p v-if="error" role="alert" class="st-error">{{ error }}</p>

    <div class="st-body">
      <!-- Leaderboard -->
      <section class="st-card st-board" aria-labelledby="st-board-title">
        <div class="st-card-head">
          <div class="st-head-text">
            <h2 id="st-board-title">Leaderboard</h2>
            <span class="st-muted">{{ scope === 'war' ? `War of ${weekLabel(week)}` : 'All-time' }} · top {{ board.length }} of {{ total }}</span>
          </div>
          <div class="st-seg st-seg-sm" role="group" aria-label="Leaderboard type">
            <button type="button" :aria-pressed="mode === 'player'" @click="setMode('player')">Players</button>
            <button type="button" :aria-pressed="mode === 'nation'" @click="setMode('nation')">Nations</button>
          </div>
        </div>
        <div class="st-scroll">
          <table class="st-table">
            <thead>
              <tr>
                <th scope="col" class="st-rank-col">#</th>
                <th scope="col" class="st-name-col">{{ mode === 'nation' ? 'Nation' : 'Player' }}</th>
                <th v-for="key in columns" :key="key" scope="col" :aria-sort="sort === key ? 'descending' : 'none'">
                  <button type="button" class="st-sort" :class="{ on: sort === key }" @click="setSort(key)">{{ COLUMNS[key] }}<span aria-hidden="true">{{ sort === key ? ' ↓' : '' }}</span></button>
                </th>
              </tr>
            </thead>
            <tbody>
              <tr v-if="loading && !board.length"><td :colspan="columns.length + 2" class="st-empty">Loading stats…</td></tr>
              <tr v-else-if="!board.length"><td :colspan="columns.length + 2" class="st-empty">{{ scope === 'war' ? 'No war stats have been recorded yet.' : 'No stats have been recorded yet.' }}</td></tr>
              <tr
                v-for="row in board"
                v-else
                :key="row.name"
                :class="{ picked: selected && selected.type === mode && selected.name === row.name }"
                tabindex="0"
                @click="select({ name: row.name, type: mode })"
                @keydown.enter="select({ name: row.name, type: mode })"
              >
                <td class="st-rank-col"><span :class="{ top: row.rank <= 3 }">{{ row.rank }}</span></td>
                <td class="st-name-col">{{ row.name }}</td>
                <td v-for="key in columns" :key="key" :class="{ on: sort === key }">{{ show(key, row[key]) }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </section>

      <!-- Rally graph -->
      <section v-if="scope === 'war'" class="st-card st-rally" aria-labelledby="st-rally-title">
        <div class="st-card-head">
          <div class="st-head-text">
            <h2 id="st-rally-title">Rally</h2>
            <span v-if="chart" class="st-muted">Peak {{ chart.peak.count }} online at {{ chart.peak.time }} ET · {{ series.length }} nations</span>
          </div>
          <button v-if="focus" type="button" class="st-ghost" @click="focus = null">Clear highlight</button>
        </div>
        <p v-if="!chart" class="st-empty">{{ loading ? 'Loading…' : 'No rally data was recorded for this war.' }}</p>
        <div v-else class="st-rally-body">
          <div
            ref="plot"
            class="st-plot"
            role="slider"
            tabindex="0"
            :aria-valuemin="0"
            :aria-valuemax="rally.length - 1"
            :aria-valuenow="cursor"
            :aria-valuetext="readout ? `${readout.time}: ${readout.sum} players online` : ''"
            aria-label="Players online per nation during the war. Drag or use the arrow keys to move through time."
            @pointerdown="down"
            @pointermove="move"
            @pointerup="dragging = false"
            @pointercancel="dragging = false"
            @keydown="chartKey"
          >
            <svg :width="chart.w" :height="chart.h" aria-hidden="true">
              <g v-for="g in chart.grid" :key="g.label">
                <line :x1="PAD.l" :x2="chart.w - PAD.r" :y1="g.y" :y2="g.y" class="st-grid" />
                <text :x="PAD.l - 8" :y="g.y + 4" text-anchor="end" class="st-axis">{{ g.label }}</text>
              </g>
              <text v-for="t in chart.ticks" :key="t.i" :x="t.x" :y="chart.h - 6" text-anchor="middle" class="st-axis">{{ t.label }}</text>
              <template v-for="l in chart.order" :key="l.name">
              <polyline
                v-for="(points, i) in l.segments"
                :key="i"
                :points="points"
                fill="none"
                stroke-linejoin="round"
                stroke-linecap="round"
                :style="{ stroke: l.stroke, strokeWidth: l.width, opacity: l.opacity }"
              />
              </template>
              <line :x1="chart.xs[cursor]" :x2="chart.xs[cursor]" :y1="PAD.t" :y2="chart.h - PAD.b" class="st-cursor" />
              <circle v-for="l in chart.dots" :key="l.name" :cx="chart.xs[cursor]" :cy="l.at[cursor]" r="4.5" class="st-dot" :style="{ fill: l.stroke, opacity: l.opacity }" />
              <text v-for="b in chart.labels" :key="b.name" :x="chart.labelX" :y="b.y" class="st-end" :style="{ fill: b.stroke, opacity: b.opacity }">{{ b.name }}</text>
            </svg>
          </div>
          <aside v-if="readout" class="st-readout" aria-label="Online at the selected time">
            <div class="st-readout-head"><strong>{{ readout.time }} ET</strong><span class="st-muted">{{ readout.sum }} online</span></div>
            <ul>
              <li v-for="r in readout.rows" :key="r.name">
                <button type="button" :aria-pressed="focus === r.name" :class="{ on: focus === r.name, dim: focus && focus !== r.name }" @click="toggleFocus(r.name)">
                  <span class="st-readout-line">
                    <span class="st-swatch" :style="{ background: r.stroke }" />
                    <span class="st-readout-name">{{ r.name }}</span>
                    <span class="st-num">{{ r.count }}</span>
                  </span>
                  <span class="st-meter"><span :style="{ width: `${r.pct}%`, background: r.stroke }" /></span>
                </button>
              </li>
            </ul>
          </aside>
        </div>
      </section>

      <!-- Profile -->
      <section class="st-card st-profile" aria-live="polite" aria-labelledby="st-profile-title">
        <template v-if="selected">
          <div class="st-profile-head">
            <span class="st-avatar st-avatar-lg" :class="selected.type">{{ selected.name[0] }}</span>
            <div class="st-head-text">
              <h2 id="st-profile-title">{{ selected.name }}</h2>
              <span class="st-muted">{{ selected.type === 'nation' ? 'Nation' : 'Player' }}<template v-if="history.length"> · played {{ playedWars }} of the last {{ history.length }} wars</template></span>
            </div>
            <select v-model="period" class="st-select st-select-sm" aria-label="Profile period" @change="loadProfile()">
              <option value="all">All-time</option>
              <option v-for="w in weeks" :key="w" :value="w">War · {{ weekLabel(w) }}</option>
            </select>
          </div>
          <div class="st-profile-scroll">
            <p v-if="profile && profile.absent" class="st-empty">{{ selected.name }} has no stats for the war of {{ weekLabel(period) }}.</p>
            <dl v-else-if="profile" class="st-tiles" :class="{ busy: profileBusy }">
              <div v-for="key in profileKeys" :key="key" class="st-tile">
                <dt>{{ COLUMNS[key] }}</dt>
                <dd>
                  <span class="st-tile-value">{{ show(key, profile.stats[key]) }}</span>
                  <span class="st-badge" :class="{ top: profile.stats.ranks[key] <= 3 }">#{{ profile.stats.ranks[key] }}</span>
                </dd>
              </div>
            </dl>
            <p v-if="profile && !profile.absent" class="st-muted st-small">Ranks among {{ profile.total }} {{ selected.type === 'nation' ? 'nations' : 'players' }}{{ period === 'all' ? '' : ' that war' }}.</p>

            <div v-if="history.length" class="st-history">
              <h3>Recent wars</h3>
              <table class="st-table st-table-sm">
                <thead><tr><th scope="col" class="st-name-col">War</th><th scope="col">Kills</th><th scope="col">Deaths</th><th scope="col">K/D</th><th scope="col" class="st-hist-bar-col"><span class="st-sr">Kills relative</span></th></tr></thead>
                <tbody>
                  <tr v-for="h in history" :key="h.week" tabindex="0" :class="{ picked: period === h.week }" @click="period = h.week; loadProfile()" @keydown.enter="period = h.week; loadProfile()">
                    <td class="st-name-col">{{ weekLabel(h.week) }}</td>
                    <template v-if="h.stats">
                      <td>{{ h.stats.kills }}</td><td>{{ h.stats.deaths }}</td><td>{{ h.stats.kd }}</td>
                      <td class="st-hist-bar-col"><span class="st-meter"><span :style="{ width: `${(h.stats.kills / historyMax) * 100}%` }" /></span></td>
                    </template>
                    <td v-else colspan="4" class="st-muted st-absent">Didn't play</td>
                  </tr>
                </tbody>
              </table>
            </div>
          </div>
        </template>
        <div v-else class="st-empty">
          <h2 id="st-profile-title" class="st-sr">Profile</h2>
          Search a player or nation, or click a row to see their stats.
        </div>
      </section>
    </div>
  </div>
</template>

<style scoped>
.st {
  --st-gap: 16px;
  --st-card: var(--vp-c-bg-soft);
  --st-line: var(--vp-c-divider);
  --st-gold: var(--vp-c-yellow-1);
  max-width: 1600px;
  margin: 0 auto;
  padding: 20px 24px 24px;
  display: flex;
  flex-direction: column;
  gap: var(--st-gap);
  font-size: 14px;
}
.st-sr { position: absolute; width: 1px; height: 1px; overflow: hidden; clip: rect(0 0 0 0); white-space: nowrap; }
.st-muted { color: var(--vp-c-text-2); font-size: 13px; }
.st-small { font-size: 12px; margin: 10px 0 0; }
.st-num, .st-table td, .st-tile-value, .st-badge { font-variant-numeric: tabular-nums; }
.st button, .st select, .st input { font: inherit; color: inherit; }
.st button:focus-visible, .st select:focus-visible, .st input:focus-visible, .st [tabindex]:focus-visible { outline: 2px solid var(--vp-c-brand-1); outline-offset: 2px; }

/* Top bar */
.st-bar { display: flex; flex-wrap: wrap; align-items: center; gap: 12px 20px; }
.st-title { margin: 0; font-size: 26px; font-weight: 700; letter-spacing: -0.02em; line-height: 1; }
.st-controls { display: flex; align-items: center; gap: 10px; margin-left: auto; }
.st-search { position: relative; flex: 1 1 360px; max-width: 640px; display: flex; align-items: center; gap: 10px; height: 44px; padding: 0 12px; background: var(--st-card); border: 1px solid var(--st-line); border-radius: 10px; }
.st-search:focus-within { border-color: var(--vp-c-brand-1); }
.st-search-icon { width: 18px; height: 18px; flex: none; fill: none; stroke: var(--vp-c-text-2); stroke-width: 2; stroke-linecap: round; }
.st-search input { flex: 1; min-width: 0; height: 100%; border: 0; background: transparent; font-size: 15px; }
.st-search input:focus-visible { outline: none; }
.st-search input::-webkit-search-cancel-button { display: none; }
.st-kbd { font-family: var(--vp-font-family-mono); font-size: 12px; color: var(--vp-c-text-2); border: 1px solid var(--st-line); border-radius: 5px; padding: 1px 7px; }
.st-options { position: absolute; z-index: 20; top: calc(100% + 6px); left: 0; right: 0; margin: 0; padding: 6px; list-style: none; background: var(--vp-c-bg-elv); border: 1px solid var(--st-line); border-radius: 10px; box-shadow: var(--vp-shadow-3); }
.st-options li { display: flex; align-items: center; gap: 10px; padding: 8px 10px; border-radius: 7px; cursor: pointer; }
.st-options li.active { background: var(--vp-c-default-soft); }
.st-opt-name { flex: 1; min-width: 0; font-weight: 600; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.st-opt-meta { font-size: 12px; color: var(--vp-c-text-2); }
.st-options .st-opt-empty { cursor: default; color: var(--vp-c-text-2); }
.st-avatar { flex: none; width: 26px; height: 26px; display: grid; place-items: center; border-radius: 6px; font-weight: 700; font-size: 13px; text-transform: uppercase; background: var(--vp-c-default-soft); color: var(--vp-c-brand-1); }
.st-avatar.nation { border-radius: 50%; color: #f0903e; }
.st-avatar-lg { width: 44px; height: 44px; font-size: 20px; border-radius: 10px; }

.st-seg { display: flex; gap: 3px; padding: 3px; background: var(--st-card); border: 1px solid var(--st-line); border-radius: 9px; }
.st-seg button { border: 0; background: transparent; padding: 7px 14px; border-radius: 6px; font-size: 13px; font-weight: 600; color: var(--vp-c-text-2); cursor: pointer; }
.st-seg button:hover { color: var(--vp-c-text-1); }
.st-seg button[aria-pressed='true'] { background: var(--vp-c-brand-1); color: var(--vp-c-white); }
.st-seg-sm button { padding: 5px 12px; }
.st-select { height: 38px; padding: 0 10px; background: var(--st-card); border: 1px solid var(--st-line); border-radius: 9px; font-size: 13px; font-weight: 600; cursor: pointer; }
.st-select-sm { height: 32px; margin-left: auto; }
.st-ghost { border: 1px solid var(--st-line); background: transparent; border-radius: 7px; padding: 5px 10px; font-size: 12px; cursor: pointer; }
.st-ghost:hover { border-color: var(--vp-c-brand-1); }
.st-error { margin: 0; padding: 10px 14px; border: 1px solid var(--vp-c-danger-1); border-radius: 8px; background: var(--vp-c-danger-soft); }

/* Cards */
.st-body { display: grid; gap: var(--st-gap); grid-template-columns: minmax(0, 1.6fr) minmax(340px, 1fr); grid-template-areas: 'board profile'; }
.st-war .st-body { grid-template-columns: minmax(0, 1fr) minmax(0, 1.55fr); grid-template-rows: minmax(0, 1.25fr) minmax(0, 1fr); grid-template-areas: 'board rally' 'board profile'; }
.st-board { grid-area: board; }
.st-rally { grid-area: rally; }
.st-profile { grid-area: profile; }
.st-card { min-width: 0; min-height: 0; display: flex; flex-direction: column; background: var(--st-card); border: 1px solid var(--st-line); border-radius: 12px; overflow: hidden; }
.st-card-head { flex: none; display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 10px; padding: 12px 16px; border-bottom: 1px solid var(--st-line); }
.st-head-text { display: flex; flex-direction: column; gap: 2px; min-width: 0; }
.st-card h2 { margin: 0; padding: 0; border: 0; font-size: 16px; font-weight: 700; line-height: 1.3; letter-spacing: 0; }
.st-scroll { flex: 1; min-height: 0; overflow: auto; }
.st-empty { margin: 0; padding: 24px 16px; color: var(--vp-c-text-2); text-align: center; }

/* Tables */
.st-table { width: 100%; border-collapse: collapse; display: table; margin: 0; font-size: 14px; }
.st-table tr { border: 0; background: transparent; }
.st-table th, .st-table td { border: 0; padding: 0 12px; height: 38px; text-align: right; white-space: nowrap; }
.st-table thead th { position: sticky; top: 0; z-index: 1; height: 34px; background: var(--vp-c-bg-alt); border-bottom: 1px solid var(--st-line); font-size: 12px; font-weight: 600; color: var(--vp-c-text-2); }
.st-table tbody tr { border-bottom: 1px solid var(--st-line); cursor: pointer; }
.st-table tbody tr:hover { background: var(--vp-c-default-soft); }
.st-table tbody tr.picked { background: var(--vp-c-brand-soft); }
.st-table td { color: var(--vp-c-text-2); }
.st-table td.on { color: var(--vp-c-text-1); font-weight: 600; }
.st-table .st-rank-col { width: 44px; text-align: left; padding-left: 16px; }
.st-table .st-name-col { text-align: left; color: var(--vp-c-text-1); font-weight: 600; width: 100%; }
.st-rank-col .top { color: var(--st-gold); font-weight: 700; }
.st-sort { border: 0; background: none; padding: 0; font-size: 12px; font-weight: 600; color: var(--vp-c-text-2); cursor: pointer; }
.st-sort:hover, .st-sort.on { color: var(--vp-c-brand-1); }
.st-table-sm th, .st-table-sm td { height: 32px; padding: 0 8px; font-size: 13px; }
.st-table-sm thead th { position: static; background: transparent; }

/* Profile */
.st-profile-head { flex: none; display: flex; align-items: center; gap: 12px; padding: 12px 16px; border-bottom: 1px solid var(--st-line); }
.st-profile-scroll { flex: 1; min-height: 0; overflow: auto; padding: 14px 16px 16px; }
.st-tiles { margin: 0; display: grid; grid-template-columns: repeat(auto-fill, minmax(150px, 1fr)); gap: 10px; transition: opacity .15s; }
.st-tiles.busy { opacity: .5; }
.st-tile { padding: 10px 12px; border: 1px solid var(--st-line); border-radius: 9px; background: var(--vp-c-bg); }
.st-tile dt { white-space: nowrap; overflow: hidden; text-overflow: ellipsis; font-size: 11px; font-weight: 600; letter-spacing: .05em; text-transform: uppercase; color: var(--vp-c-text-2); }
.st-tile dd { margin: 4px 0 0; display: flex; align-items: baseline; justify-content: space-between; gap: 6px; }
.st-tile-value { font-size: 20px; font-weight: 700; white-space: nowrap; }
.st-badge { font-size: 11px; font-weight: 600; padding: 1px 7px; border-radius: 999px; background: var(--vp-c-default-soft); color: var(--vp-c-text-2); }
.st-badge.top { background: var(--vp-c-yellow-soft); color: var(--st-gold); }
.st-history { margin-top: 16px; }
.st-history h3 { margin: 0 0 6px; font-size: 13px; font-weight: 700; color: var(--vp-c-text-2); text-transform: uppercase; letter-spacing: .05em; }
.st-table .st-hist-bar-col { width: 110px; min-width: 110px; }
.st-absent { text-align: left !important; font-style: italic; }
.st-meter { display: block; width: 100%; min-width: 70px; height: 4px; border-radius: 2px; background: var(--vp-c-default-soft); overflow: hidden; }
.st-meter > span { display: block; height: 100%; border-radius: 2px; background: var(--vp-c-brand-1); }

/* Rally */
.st-rally-body { flex: 1; min-height: 0; display: flex; }
.st-plot { flex: 1; min-width: 0; min-height: 0; position: relative; margin: 8px 0 4px 4px; cursor: crosshair; touch-action: none; user-select: none; }
.st-plot svg { position: absolute; inset: 0; display: block; }
.st-grid { stroke: var(--st-line); stroke-width: 1; }
.st-axis { fill: var(--vp-c-text-2); font-size: 11px; }
.st-cursor { stroke: var(--vp-c-text-2); stroke-width: 1; stroke-dasharray: 3 4; }
.st-dot { stroke: var(--st-card); stroke-width: 2; }
.st-end { font-size: 12px; font-weight: 600; }
.st-readout { flex: none; width: 220px; display: flex; flex-direction: column; min-height: 0; border-left: 1px solid var(--st-line); }
.st-readout-head { flex: none; display: flex; align-items: baseline; justify-content: space-between; gap: 8px; padding: 10px 12px 6px; }
.st-readout ul { flex: 1; min-height: 0; overflow: auto; margin: 0; padding: 0 6px 8px; list-style: none; }
.st-readout button { width: 100%; display: flex; flex-direction: column; gap: 4px; padding: 5px 6px; border: 0; border-radius: 6px; background: transparent; text-align: left; cursor: pointer; font-size: 13px; }
.st-readout button:hover, .st-readout button.on { background: var(--vp-c-default-soft); }
.st-readout button.dim { opacity: .45; }
.st-readout-line { display: flex; align-items: center; gap: 8px; }
.st-readout-name { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.st-swatch { flex: none; width: 9px; height: 9px; border-radius: 2px; }
.st-num { font-size: 12px; font-weight: 600; }
.st-readout .st-meter { height: 3px; min-width: 0; }

/* One screen on a desktop: the page itself doesn't scroll, only the boxes inside it. */
@media (min-width: 1100px) and (min-height: 620px) {
  .st { height: calc(100vh - var(--vp-nav-height)); }
  .st-body { flex: 1; min-height: 0; }
}
@media (max-width: 1099px), (max-height: 619px) {
  .st-body, .st-war .st-body { grid-template-columns: minmax(0, 1fr); grid-template-rows: none; grid-template-areas: 'rally' 'profile' 'board'; }
  .st-body { grid-template-areas: 'profile' 'board'; }
  .st-board .st-scroll { max-height: 70vh; }
  .st-rally-body { flex-direction: column; }
  .st-plot { height: 300px; flex: none; }
  .st-readout { width: auto; border-left: 0; border-top: 1px solid var(--st-line); max-height: 260px; }
}
@media (max-width: 640px) {
  .st { padding: 16px; }
  .st-controls { margin-left: 0; }
}
</style>
