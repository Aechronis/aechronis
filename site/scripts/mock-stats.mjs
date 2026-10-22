// Local stand-in for the server's stats module (modules/stats): node scripts/mock-stats.mjs [port]
// Mirrors Stats.query / Stats.history / Stats.search so the stats page can be developed offline.
import http from 'node:http'

const port = Number(process.argv[2] ?? process.env.STATS_PORT ?? 8090)
const NATIONS = ['Valdria', 'Korrin', 'Ostmark', 'Ashvale', 'Drem', 'Thessaly', 'Norrland', 'Ibrel', 'Quenmar', 'Sarnak', 'Velmoor', 'Hadrin', 'Ysolde', 'Brask']
const NAMES = ['Ironclad', 'Vex', 'Marrow', 'Sable', 'Halcyon', 'Tormund', 'Bracken', 'Nyx', 'Corvus', 'Ember', 'Lark', 'Rook', 'Thane', 'Wisp', 'Gorse', 'Dune', 'Pike', 'Quill', 'Jarl', 'Moth', 'Ash', 'Fen', 'Orrin', 'Sleet', 'Brindle', 'Cinder', 'Tarn', 'Hollis', 'Rime', 'Vesper']
// War Saturdays, newest first (ET dates, like the server's week keys).
const WEEKS = ['2026-10-17', '2026-10-10', '2026-10-03', '2026-09-26', '2026-09-19', '2026-09-12', '2026-09-05']

let seed = 7
const rand = () => ((seed = (seed * 1664525 + 1013904223) >>> 0) / 2 ** 32)
const blank = () => ({ kills: 0, deaths: 0, capsBroken: 0, capsCompleted: 0, playtime: 0 })
const fix = s => ({ ...s, kd: +(s.deaths === 0 ? s.kills : s.kills / s.deaths).toFixed(2) })

// players: name -> { nation (or null), all, war: { week -> stats } }; nations aggregate their members like Stats.credit.
const players = new Map()
const nations = new Map(NATIONS.map(n => [n, { all: blank(), war: {} }]))
const add = (to, s) => { for (const k in s) to[k] = (to[k] ?? 0) + s[k] }
NAMES.forEach((name, i) => {
  const nation = i % 7 === 6 ? null : NATIONS[i % NATIONS.length]
  const p = { nation, all: blank(), war: {} }
  const skill = 1.6 - i * 0.04
  for (const week of WEEKS) {
    if (rand() < 0.3 + i * 0.01) continue // sat this war out
    const kills = Math.round((4 + rand() * 30) * skill)
    const w = { kills, deaths: Math.round(3 + rand() * 18), capsBroken: Math.round(rand() * 4 * skill), capsCompleted: Math.round(rand() * 2 * skill), playtime: 0 }
    p.war[week] = w
    add(p.all, w)
    if (nation) { nations.get(nation).war[week] ??= blank(); add(nations.get(nation).war[week], w); add(nations.get(nation).all, w) }
  }
  const hours = Math.round((60 - i) * 3600 * (0.6 + rand()))
  p.all.playtime = hours
  if (nation) nations.get(nation).all.playtime += hours
  players.set(name, p)
})

const rally = Array.from({ length: 18 }, (_, i) => ({
  time: Math.floor(Date.parse('2026-10-17T17:00:00Z') / 1000) + i * 600, // 1:00 PM EDT, one sample per 10 minute slot
  nations: Object.fromEntries(NATIONS.map((n, j) => [n, Math.max(0, Math.round(15 - j * 0.9 + 4 * Math.sin(i / 3 + j)))])),
}))

const KEYS = { player: ['kills', 'deaths', 'kd', 'capsBroken', 'capsCompleted', 'playtime'], nation: ['kills', 'deaths', 'kd', 'playtime'] }

function statsOf(type, name, war, week) {
  const e = (type === 'nation' ? nations : players).get(name)
  const s = war ? e?.war[week] : e?.all
  return s && (type === 'nation' ? s.kills + s.deaths > 0 || !war : true) ? { name, ...fix(s), ...(type === 'player' ? { nation: e.nation } : {}) } : null
}
const nameList = type => [...(type === 'nation' ? nations : players).keys()]

function query(u) {
  const type = u.searchParams.get('type') === 'nation' ? 'nation' : 'player'
  const war = u.searchParams.get('scope') === 'war'
  const keys = KEYS[type].filter(k => !war || k !== 'playtime')
  const sort = u.searchParams.get('sort') || 'kills'
  if (!keys.includes(sort)) return null
  const weeks = WEEKS.filter(w => nameList(type).some(n => statsOf(type, n, true, w)))
  const week = war ? u.searchParams.get('week') || weeks[0] : null
  const snapshot = nameList(type).map(n => statsOf(type, n, war, week)).filter(Boolean)
  const sorted = [...snapshot].sort((a, b) => b[sort] - a[sort] || a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  let rank = 0
  const rows = sorted.slice(0, 20).map((p, i) => {
    if (i === 0 || p[sort] !== sorted[i - 1][sort]) rank = i + 1 // ties share a rank
    return { ...pick(p, type, keys), rank }
  })
  const q = (u.searchParams.get('player') || '').toLowerCase()
  const me = q ? snapshot.find(p => p.name.toLowerCase() === q) : null
  const player = me && { ...pick(me, type, keys), ranks: Object.fromEntries(keys.map(k => [k, 1 + snapshot.filter(p => p[k] > me[k]).length])) }
  return { sort, total: snapshot.length, ...(war ? { week, weeks, rally: week === weeks[0] ? rally : [] } : {}), players: rows, player: player ?? null }
}
const pick = (p, type, keys) => ({ name: p.name, ...(type === 'player' ? { nation: p.nation } : {}), ...Object.fromEntries(keys.map(k => [k, p[k]])) })

function history(u) {
  const type = u.searchParams.get('type') === 'nation' ? 'nation' : 'player'
  const q = (u.searchParams.get('player') || '').toLowerCase()
  if (!q) return null
  const name = nameList(type).find(n => n.toLowerCase() === q) ?? q
  const rows = WEEKS.map(week => [week, statsOf(type, name, true, week)]).filter(([, s]) => s).slice(0, 12)
  return { name, type, weeks: rows.map(([week, s]) => ({ week, kills: s.kills, deaths: s.deaths, kd: s.kd, capsBroken: s.capsBroken, capsCompleted: s.capsCompleted })) }
}

function search(u) {
  const q = (u.searchParams.get('q') || '').trim().slice(0, 16).toLowerCase()
  if (!q) return { results: [] }
  const all = [...nameList('player').map(n => ({ name: n, type: 'player', nation: players.get(n).nation })), ...nameList('nation').map(n => ({ name: n, type: 'nation' }))]
  const starts = n => n.toLowerCase().startsWith(q)
  const hits = all.filter(e => e.name.toLowerCase().includes(q)).sort((a, b) => starts(b.name) - starts(a.name) || a.name.toLowerCase().localeCompare(b.name.toLowerCase()))
  return { results: hits.slice(0, 8).map(e => (e.nation ? e : { name: e.name, type: e.type })) }
}

http.createServer((req, res) => {
  const u = new URL(req.url, 'http://x')
  const body = u.pathname === '/stats/search' ? search(u) : u.pathname === '/stats/history' ? history(u) : u.pathname === '/stats' ? query(u) : null
  res.writeHead(body ? 200 : 400, { 'Content-Type': 'application/json', 'Access-Control-Allow-Origin': '*' })
  res.end(JSON.stringify(body ?? { error: 'bad request' }))
}).listen(port, () => console.log(`[mock-stats] http://localhost:${port}/stats`))
