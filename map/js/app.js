import { Deck, OrthographicView } from '@deck.gl/core';
import { TileLayer } from '@deck.gl/geo-layers';
import {
  BitmapLayer,
  IconLayer,
  LineLayer,
  ScatterplotLayer,
  SolidPolygonLayer,
  TextLayer,
} from '@deck.gl/layers';

// --- Constants -----------------------------------------------------------
const NATIVE_MIN_ZOOM = -4;
const NATIVE_MAX_ZOOM = 0;
// Number of LOD levels in the webp tile pyramid (5 levels: -4..0 → LOD 0..4).
const NATIVE_LOD_RANGE = NATIVE_MAX_ZOOM - NATIVE_MIN_ZOOM;
// Each rendered tile image is 256×256 px and represents 256 blocks at native
// zoom; at the most zoomed-out LOD a single tile covers 256 × 2^4 = 4096 blocks.
const TILE_BLOCK_EXTENT = 256 * Math.pow(2, NATIVE_LOD_RANGE);
const BLOCKS_PER_CHUNK = 16;
// Opacity of the base territory fill (the chunk colour) and of the selection
// highlights layered over it. The attack fill reuses these so it always reads
// at the same opacity as the chunk it's drawn on, in every selection state.
const TERRITORY_OPACITY = 0.1;
const TOWN_HL_OPACITY = 0.2;
const TERRITORY_HL_OPACITY = 0.3;
const CHUNK_HL_OPACITY = 1;
// Configured territories without a town use the same neutral gray as other
// missing-color fallbacks, so they remain visible without implying ownership.
const UNCLAIMED_TERRITORY_COLOR = [120, 120, 120];
// Inset the building image so it covers ~70% of the chunk, centered.
const BUILDING_IMAGE_SIZE = BLOCKS_PER_CHUNK * 0.7;
const PORT_RANGE_BY_TIER = [2500, 5000, 10000];
// Mirror of CSS --mono so TextLayer glyphs match the rest of the UI.
const MONO_FONT = "'JetBrains Mono', ui-monospace, SFMono-Regular, Menlo, Consolas, monospace";

// World units in OrthographicView are blocks. zoom 0 → 1 block per pixel
// (matches the deepest webp LOD); negative zooms scale tiles down for the
// shallower LODs, positive zooms upscale past native.
const VIEW_MIN_ZOOM = -NATIVE_LOD_RANGE;
// Cap zoom at native resolution (1 block per pixel, the deepest LOD) — no
// upscaling past it, so the deepest you can zoom in is the crisp tile imagery.
const VIEW_MAX_ZOOM = NATIVE_MAX_ZOOM;
// Threshold for chunk-resolution detail (grid, buildings) — same idea as the
// leaflet LEAFLET_MAX_NATIVE_ZOOM cutoff.
const NATIVE_VIEW_ZOOM = 0;
// Pan limit, in block coords: [minX, minZ, maxX, maxZ]. Panning is bounded to
// exactly this box. Example values — replace with the real world bounds.
const PAN_BOUNDS = [-26880, -12289, 26880, 12290];

// World-space box the webp tile pyramid actually covers. Tiles exist wherever
// the base map was rendered (the whole terrain) — a superset of the territory
// hull and at least as large as PAN_BOUNDS. The TileLayer extent and the
// fallback canvas must be bounded by *this*, not by the territory bbox:
// deriving them from indexed chunks clipped peripheral terrain at the edges
// of the world. Snap PAN_BOUNDS outward
// to whole TILE_BLOCK_EXTENT tiles so partial edge tiles load in full.
const TILE_WORLD_BOUNDS = [
  Math.floor(PAN_BOUNDS[0] / TILE_BLOCK_EXTENT) * TILE_BLOCK_EXTENT,
  Math.floor(PAN_BOUNDS[1] / TILE_BLOCK_EXTENT) * TILE_BLOCK_EXTENT,
  Math.ceil(PAN_BOUNDS[2] / TILE_BLOCK_EXTENT) * TILE_BLOCK_EXTENT,
  Math.ceil(PAN_BOUNDS[3] / TILE_BLOCK_EXTENT) * TILE_BLOCK_EXTENT,
];

// Pack a signed (x, z) pair into a single non-negative integer suitable as a
// Map key. Avoids per-lookup string allocations on the click-hit path. Range:
// each axis fits in [-0x80000, 0x7FFFF] (±524k), well above any realistic
// chunk coord.
const PACK_OFFSET = 0x80000;
const PACK_STRIDE = 0x100000;
function packCoord(x, z) {
  return (x + PACK_OFFSET) * PACK_STRIDE + (z + PACK_OFFSET);
}

// Tile filenames are bucketed by floor(coord/10) so directory listings stay
// shallow. Negative coords intentionally floor toward -infinity.
function bucketOf(coord) {
  return Math.floor(coord / 10);
}

function nonZero(c) { return Array.isArray(c) && (c[0] | c[1] | c[2]) !== 0; }
function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

// First non-zero RGB triple in priority order, else `fallback`. Centralises
// the nation-then-town-then-grey colour precedence used across the map.
function pickColor(candidates, fallback) {
  for (const c of candidates) if (nonZero(c)) return c;
  return fallback;
}

// Centre of a chunk in block coords.
function chunkCenter(cx, cz) {
  return [cx * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2,
          cz * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2];
}

// w×h offscreen canvas at one pixel per chunk. Callers write RGBA straight
// into `buf` (so the per-pixel hot loop stays inline — buildWorldImage runs
// it over ~2.4M chunks) then call commit() to upload + return the canvas.
function chunkCanvas(w, h) {
  const canvas = document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(w, h);
  return {
    buf: img.data,
    commit() { ctx.putImageData(img, 0, 0); return canvas; },
  };
}

// Bucket items (each exposing .cx/.cz) into a Map keyed by their cellChunks-
// sized spatial cell. Owner chunks and outline segments share this so their
// cell keys stay byte-identical — updateVisible() indexes both Maps by the
// same key, so a divergent key formula would silently misalign the culls.
function bucketByCell(items, cellChunks) {
  const cells = new Map();
  for (const it of items) {
    const k = packCoord(
      Math.floor(it.cx / cellChunks),
      Math.floor(it.cz / cellChunks),
    );
    let bucket = cells.get(k);
    if (!bucket) { bucket = []; cells.set(k, bucket); }
    bucket.push(it);
  }
  return cells;
}
function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// --- Data indexing -------------------------------------------------------
function buildIndex({ towns, world, war }) {
  // Any of these may be null when the corresponding node file is absent;
  // default to empty objects so the index contains whatever data is available
  // instead of throwing on `.nations` / `.territories` access.
  towns = towns || {};
  world = world || {};
  const townNation = new Map();
  for (const [nationName, nation] of Object.entries(towns.nations || {})) {
    for (const tn of nation.towns || []) townNation.set(tn, nationName);
  }

  const townStyle = new Map();
  for (const [name, town] of Object.entries(towns.towns || {})) {
    const nationName = townNation.get(name) || null;
    const nation = nationName ? towns.nations[nationName] : null;
    const color = pickColor(
      [nation && nation.color, town.color], UNCLAIMED_TERRITORY_COLOR);
    townStyle.set(name, {
      name,
      nation: nationName,
      color,
    });
  }

  // Resolve each war.occupied entry's "any chunk inside the occupied territory"
  // coord to the actual territoryId, then record which color should override
  // the town's normal color for that territory.
  const occupiedByTid = new Map();
  if (war && war.occupied) {
    const chunkToTid = new Map();
    for (const [tid, t] of Object.entries(world.territories || {})) {
      const arr = t.chunks;
      for (let i = 0; i < arr.length; i += 2) {
        chunkToTid.set(packCoord(arr[i], arr[i + 1]), +tid);
      }
    }
    for (const [occupier, value] of Object.entries(war.occupied)) {
      const pairs = Array.isArray(value[0])
        ? value
        : Array.from(
          { length: Math.floor(value.length / 2) },
          (_, i) => [value[i * 2], value[i * 2 + 1]],
        );
      const occStyle = townStyle.get(occupier);
      const nation = towns.nations && towns.nations[occupier];
      const color = pickColor(
        [occStyle && occStyle.color, nation && nation.color], [220, 50, 50]);
      for (const [cx, cz] of pairs) {
        const tid = chunkToTid.get(packCoord(cx, cz));
        if (tid != null) occupiedByTid.set(tid, { occupier, color });
      }
    }
  }

  const territoryStyle = new Map();
  const owner = new Map();
  const homes = [];
  let minCx = Infinity, maxCx = -Infinity, minCz = Infinity, maxCz = -Infinity;

  const addTerritory = (territoryId, territory, townName, style) => {
    territoryStyle.set(territoryId, style);
    const arr = territory.chunks || [];
    for (let i = 0; i < arr.length; i += 2) {
      const cx = arr[i];
      const cz = arr[i + 1];
      const ownerKey = packCoord(cx, cz);
      if (owner.has(ownerKey)) continue;
      owner.set(ownerKey, { townName, territoryId, style, cx, cz });
      if (cx < minCx) minCx = cx;
      if (cx > maxCx) maxCx = cx;
      if (cz < minCz) minCz = cz;
      if (cz > maxCz) maxCz = cz;
    }
    if (territory.core) {
      const hx = Math.floor(territory.core[0] / BLOCKS_PER_CHUNK);
      const hz = Math.floor(territory.core[1] / BLOCKS_PER_CHUNK);
      homes.push({ cx: hx, cz: hz, color: style.color });
    }
  };

  for (const [name, town] of Object.entries(towns.towns || {})) {
    const tStyle = townStyle.get(name);
    for (const rawTid of town.territories || []) {
      const tid = +rawTid;
      const occ = occupiedByTid.get(tid);
      const style = occ ? {
        ...tStyle,
        color: occ.color,
      } : tStyle;

      const territory = world.territories && world.territories[tid];
      if (!territory) continue;
      addTerritory(tid, territory, name, style);
    }
  }

  // Town data describes ownership, but world data describes every configured
  // territory. Index anything no town referenced as unclaimed so it still
  // receives a fill, outline, chunk grid, core marker, and click target.
  const unclaimedStyle = {
    name: null,
    nation: null,
    color: UNCLAIMED_TERRITORY_COLOR,
  };
  for (const [rawTid, territory] of Object.entries(world.territories || {})) {
    const tid = +rawTid;
    if (territoryStyle.has(tid)) continue;
    const occ = occupiedByTid.get(tid);
    const style = occ ? { ...unclaimedStyle, color: occ.color } : unclaimedStyle;
    addTerritory(tid, territory, null, style);
  }

  return { townStyle, territoryStyle, owner, homes, minCx, maxCx, minCz, maxCz };
}

// Pre-rasterise the entire territory layer to a single image at one pixel per
// chunk; rendered as a BitmapLayer with nearest-neighbour filtering so
// upscaling stays crisp. Bounded by world extent (~3415×2072 px ≈ 28 MB peak
// during construction, then released after upload).
// `skipKeys` (packed-coord Set) are chunks left transparent so the attack
// fill blends straight onto the map imagery — no old-owner colour tinting it.
function buildWorldImage(index, skipKeys) {
  // No territory chunks (e.g. world data absent) → minCx/minCz are still
  // Infinity, which would size the canvas from garbage bounds. Nothing to
  // raster, so return null; territoryRasterLayer() skips a null image.
  if (!index.owner.size) return null;
  const w = index.maxCx - index.minCx + 1;
  const h = index.maxCz - index.minCz + 1;
  const { buf, commit } = chunkCanvas(w, h);
  const hasSkips = skipKeys && skipKeys.size > 0;
  for (const entry of index.owner.values()) {
    if (hasSkips && skipKeys.has(packCoord(entry.cx, entry.cz))) continue;
    const c = entry.style.color;
    const p = ((entry.cz - index.minCz) * w + (entry.cx - index.minCx)) * 4;
    buf[p] = c[0];
    buf[p + 1] = c[1];
    buf[p + 2] = c[2];
    buf[p + 3] = 255;
  }
  return commit();
}

// Each chunk → up to 4 path segments along edges that border a different
// territory (or space outside any configured territory). Coords are in
// blocks. 2 px wide so they read over the 1 px chunk grid.
function buildOutlineSegments(index) {
  const segments = [];
  const dirs = [
    { dx: 0, dz: -1, x0o: 0, z0o: 0, x1o: 1, z1o: 0 }, // top
    { dx: 0, dz: 1, x0o: 0, z0o: 1, x1o: 1, z1o: 1 },  // bottom
    { dx: -1, dz: 0, x0o: 0, z0o: 0, x1o: 0, z1o: 1 }, // left
    { dx: 1, dz: 0, x0o: 1, z0o: 0, x1o: 1, z1o: 1 },  // right
  ];
  for (const owner of index.owner.values()) {
    const { cx, cz, territoryId } = owner;
    const baseX = cx * BLOCKS_PER_CHUNK;
    const baseZ = cz * BLOCKS_PER_CHUNK;
    for (const d of dirs) {
      const n = index.owner.get(packCoord(cx + d.dx, cz + d.dz));
      // Only the chunk with smaller territoryId emits the shared edge — the
      // neighbour will skip it. Missing neighbours always emit.
      if (n && territoryId >= n.territoryId) continue;
      segments.push({
        path: [
          [baseX + d.x0o * BLOCKS_PER_CHUNK, baseZ + d.z0o * BLOCKS_PER_CHUNK],
          [baseX + d.x1o * BLOCKS_PER_CHUNK, baseZ + d.z1o * BLOCKS_PER_CHUNK],
        ],
        color: owner.style.color,
        // Owning chunk — used by the spatial bucketer so segments along the
        // bottom/right edge of a cell-boundary chunk land in their chunk's
        // cell, not the next cell over.
        cx,
        cz,
      });
    }
  }
  return segments;
}

// Attack chunks carry the *attacking* nation's colour so the SolidPolygonLayer
// can fill them via getFillColor. `a.id` is the attacker's resident UUID;
// resolve resident → town → (town/nation) colour via index.townStyle, which
// already applies the same nation-then-town-then-grey precedence as the rest
// of the map. Falls back to the attacked chunk's owner colour if the attacker
// can't be resolved.
//
// `s` (start) / `e` (end) are Unix-second timestamps. An attack that hasn't
// started yet is skipped; once started it renders (filling between s and e,
// then staying fully filled after e — completed attacks persist as captured
// territory until war.json drops them).
function buildAttackChunks(war, towns, index, nowSec = Date.now() / 1000) {
  const out = [];
  if (!war || !Array.isArray(war.attacks)) return out;
  const residents = (towns && towns.residents) || {};
  for (const a of war.attacks) {
    if (!a || !a.c) continue;
    if (typeof a.s === 'number' && nowSec < a.s) continue;
    const [cx, cz] = a.c;
    const hit = index.owner.get(packCoord(cx, cz));
    if (!hit) continue;
    const attacker = a.id && residents[a.id];
    const attackerStyle = attacker && attacker.town
      && index.townStyle.get(attacker.town);
    out.push({
      cx,
      cz,
      // Attacker colour fills the captured (left) part; the defender's owner
      // colour fills the not-yet-captured (right) part.
      color: attackerStyle ? attackerStyle.color : hit.style.color,
      defColor: hit.style.color,
      s: typeof a.s === 'number' ? a.s : null,
      e: typeof a.e === 'number' ? a.e : null,
    });
  }
  return out;
}

// 0..1 fraction of how far `nowSec` is through an attack's [s, e] window.
// No window, or a malformed/zero one, reads as fully complete (1).
function attackProgress(s, e, nowSec = Date.now() / 1000) {
  if (typeof s !== 'number' || typeof e !== 'number' || e <= s) return 1;
  return clamp01((nowSec - s) / (e - s));
}

// Chunk box corners plus the vertical seam at `frac` of the way across.
function chunkSplitX(cx, cz, frac) {
  const x0 = cx * BLOCKS_PER_CHUNK;
  const z0 = cz * BLOCKS_PER_CHUNK;
  return {
    x0,
    z0,
    x1: x0 + BLOCKS_PER_CHUNK,
    z1: z0 + BLOCKS_PER_CHUNK,
    xFill: x0 + BLOCKS_PER_CHUNK * clamp01(frac),
  };
}

// The captured (left) part, growing from the x0 edge toward the seam as the
// attack completes.
function chunkProgressPolygon(cx, cz, frac) {
  const { x0, z0, z1, xFill } = chunkSplitX(cx, cz, frac);
  return [[x0, z0], [xFill, z0], [xFill, z1], [x0, z1]];
}

// The complement: the not-yet-captured (right) part, from the seam to the
// chunk's right edge. Degenerate (zero-width) once the attack is complete.
function chunkRemainderPolygon(cx, cz, frac) {
  const { z0, x1, z1, xFill } = chunkSplitX(cx, cz, frac);
  return [[xFill, z0], [x1, z0], [x1, z1], [xFill, z1]];
}

// --- DOM panels ----------------------------------------------------------
const loadingEl = document.getElementById('loading');
const nationPanel = document.getElementById('nation-panel');
const territoryPanel = document.getElementById('territory-panel');
const panelsEl = document.getElementById('panels');
const panelsCloseEl = document.getElementById('panels-close');
const coordsEl = document.getElementById('coords');

// The mobile bottom sheet only takes up screen space while it has something
// to show; toggle `has-content` from the panels' own empty state.
function refreshPanelsVisibility() {
  if (!panelsEl) return;
  const hasContent = !nationPanel.classList.contains('empty')
    || !territoryPanel.classList.contains('empty');
  panelsEl.classList.toggle('has-content', hasContent);
}
// Split the coords readout into two stable child nodes once at boot so the
// hover handler can poke .nodeValue on text nodes instead of parsing HTML
// (innerHTML triggers layout + an HTML parse on every pointermove).
const coordsBlockText = document.createTextNode('');
const coordsChunkSpan = document.createElement('span');
coordsChunkSpan.className = 'coord-chunk';
const coordsChunkText = document.createTextNode('');
coordsChunkSpan.appendChild(coordsChunkText);
if (coordsEl) {
  coordsEl.appendChild(coordsBlockText);
  coordsEl.appendChild(coordsChunkSpan);
}

let loadingHidden = false;
function hideLoading() {
  if (loadingHidden || !loadingEl) return;
  loadingHidden = true;
  loadingEl.classList.add('is-hidden');
  loadingEl.addEventListener('transitionend', () => {
    loadingEl.style.display = 'none';
  }, { once: true });
}

function listHtml(items) {
  if (!items.length) return '<div class="subtitle">none</div>';
  return `<ul class="scroll-list">${items.map((s) => `<li>${escapeHtml(s)}</li>`).join('')}</ul>`;
}

// --- Boot ----------------------------------------------------------------
// Capture ETag + Last-Modified from each initial response so the periodic
// refresher can issue conditional GETs (If-None-Match / If-Modified-Since)
// — the server then answers 304 for unchanged files, avoiding repeated
// downloads of the 5+ MiB towns.json.
async function fetchOptionalJsonWithMeta(url) {
  try {
    const r = await fetch(url);
    if (!r.ok) return { body: null, meta: null };
    return {
      body: await r.json(),
      meta: {
        etag: r.headers.get('ETag'),
        lastMod: r.headers.get('Last-Modified'),
      },
    };
  } catch {
    return { body: null, meta: null };
  }
}

Promise.all([
  // All node files are optional: when none are present the base map (tiles)
  // still renders. Without world.json there is no territory overlay; without
  // towns.json every configured territory is shown as unclaimed.
  fetchOptionalJsonWithMeta('nodes/towns.json'),
  fetchOptionalJsonWithMeta('nodes/world.json'),
  // war.json may be absent when there's no active war; treat any failure as
  // "no war data" rather than blocking the whole map load.
  fetchOptionalJsonWithMeta('nodes/war.json'),
  fetchOptionalJsonWithMeta('nodes/buildings.json'),
]).then(([towns, world, war, buildings]) => {
  bootMap({
    data: { towns: towns.body, world: world.body, war: war.body, buildings: buildings.body },
    // world.json is intentionally omitted from `meta` — territory geometry is
    // treated as static, so the refresher never re-fetches it.
    meta: { towns: towns.meta, war: war.meta, buildings: buildings.meta },
  });
}).catch((err) => {
  console.error('Failed to load nodes data:', err);
  hideLoading();
});

function bootMap(initial) {
  // All "data-derived" state lives in `let`s so the periodic refresh can
  // rebuild in place — closures elsewhere in the function read the bindings,
  // not the values, so they automatically pick up the new objects after
  // buildAll() reassigns.
  let data = initial.data;
  const fileMeta = initial.meta;  // { towns, war, buildings } → { etag, lastMod }

  let index;
  let worldImage;
  let outlineSegments;
  let attackChunks;
  let attackChunkKeys = new Set();  // packed coords of currently-shown attacks
  let territoryChunks;
  // World-space bbox in block coords. Recomputed each rebuild from the
  // current index — territoryRasterLayer reads these inline so its bounds
  // stay aligned with the (possibly-resized) worldImage.
  let minBlockX, minBlockZ, maxBlockX, maxBlockZ;

  // Spatial bucket index. The world has ~2.4M territory chunks (one wilderness
  // territory alone spans 1.5M); rendering them as a single PolygonLayer would
  // exhaust GPU memory. Bucket by 32×32-chunk cells so per-frame culling can
  // gather just the cells that overlap the viewport, and pass the resulting
  // (typically <10k) chunks to the grid/outline layers.
  const SPATIAL_CELL_CHUNKS = 32;
  const SPATIAL_CELL_BLOCKS = SPATIAL_CELL_CHUNKS * BLOCKS_PER_CHUNK;
  let ownerCells;
  let segmentCells;
  let residentNames;
  let buildingsList;
  let buildingsByChunk;

  function buildAll() {
    index = buildIndex(data);
    attackChunks = buildAttackChunks(data.war, data.towns, index);
    attackChunkKeys = new Set(attackChunks.map((a) => packCoord(a.cx, a.cz)));
    worldImage = buildWorldImage(index, attackChunkKeys);
    outlineSegments = buildOutlineSegments(index);
    territoryChunks = [...index.owner.values()];

    minBlockX = index.minCx * BLOCKS_PER_CHUNK;
    minBlockZ = index.minCz * BLOCKS_PER_CHUNK;
    maxBlockX = (index.maxCx + 1) * BLOCKS_PER_CHUNK;
    maxBlockZ = (index.maxCz + 1) * BLOCKS_PER_CHUNK;

    ownerCells = bucketByCell(territoryChunks, SPATIAL_CELL_CHUNKS);
    segmentCells = bucketByCell(outlineSegments, SPATIAL_CELL_CHUNKS);

    // Resident UUID → name; callers fall back to the raw UUID when absent.
    residentNames = new Map();
    for (const [uuid, r] of Object.entries(data.towns?.residents || {})) {
      if (r && r.name) residentNames.set(uuid, r.name);
    }

    buildingsByChunk = new Map();
    buildingsList = [];
    if (Array.isArray(data.buildings?.buildings)) {
      for (const b of data.buildings.buildings) {
        if (b == null || b.chunkX == null || b.chunkZ == null) continue;
        const cx = b.chunkX, cz = b.chunkZ;
        const entry = {
          data: b,
          cx,
          cz,
          // Used for the port-range ring and as the icon position.
          center: chunkCenter(cx, cz),
          // Top-centre of the chunk in block coords; label sits just above.
          labelAnchor: [cx * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2,
                        cz * BLOCKS_PER_CHUNK],
          labelText: [
            b.name,
            b.type,
            b.tier != null ? `tier ${b.tier}` : null,
            b.isPublic ? 'public' : null,
          ].filter(Boolean).join(' · '),
        };
        buildingsList.push(entry);
        buildingsByChunk.set(packCoord(cx, cz), entry);
      }
    }
  }

  buildAll();

  // --- Fallback / always-loaded shallowest tiles -------------------------
  // Deck.gl's TileLayer cancels in-flight requests when the viewport moves
  // off them (and 'best-available' refinement only fills gaps with tiles
  // that are already cached). Fast pan/zoom into a never-visited area shows
  // black until the new tiles arrive. Pre-fetch the entire deepest-zoom
  // pyramid level (~112 tiles) into one canvas, render it behind the main
  // tiles, and the gaps fall back to a low-res view of the world instead of
  // black.
  let fallbackCanvas = null;
  let fallbackCanvasBounds = null;
  buildFallback();

  async function buildFallback() {
    // Cover the whole tile pyramid, same as the TileLayer extent — the
    // territory bbox would leave the world's edge rows/cols black.
    const [tbMinX, tbMinZ, tbMaxX, tbMaxZ] = TILE_WORLD_BOUNDS;
    const minTX = Math.floor(tbMinX / TILE_BLOCK_EXTENT);
    const maxTX = Math.ceil(tbMaxX / TILE_BLOCK_EXTENT) - 1;
    const minTY = Math.floor(tbMinZ / TILE_BLOCK_EXTENT);
    const maxTY = Math.ceil(tbMaxZ / TILE_BLOCK_EXTENT) - 1;
    const tilePx = 256;
    const cols = maxTX - minTX + 1;
    const rows = maxTY - minTY + 1;

    const fetchTile = async (x, y) => {
      const url = `tiles/zoom.${NATIVE_MIN_ZOOM}/${bucketOf(x)}/${bucketOf(y)}/tile.${x}.${y}.webp`;
      try {
        const r = await fetch(url);
        if (!r.ok) return null;
        return await createImageBitmap(await r.blob());
      } catch { return null; }
    };

    const requests = [];
    for (let x = minTX; x <= maxTX; x++) {
      for (let y = minTY; y <= maxTY; y++) {
        requests.push(fetchTile(x, y).then((img) => ({ x, y, img })));
      }
    }
    const results = await Promise.all(requests);

    const canvas = document.createElement('canvas');
    canvas.width = cols * tilePx;
    canvas.height = rows * tilePx;
    const ctx = canvas.getContext('2d');
    for (const { x, y, img } of results) {
      if (!img) continue;
      ctx.drawImage(img, (x - minTX) * tilePx, (y - minTY) * tilePx);
      img.close();
    }
    fallbackCanvas = canvas;
    // BitmapLayer bounds [left, bottom, right, top] in world (block) coords.
    fallbackCanvasBounds = [
      minTX * TILE_BLOCK_EXTENT,
      (maxTY + 1) * TILE_BLOCK_EXTENT,
      (maxTX + 1) * TILE_BLOCK_EXTENT,
      minTY * TILE_BLOCK_EXTENT,
    ];
    redraw();
  }

  // --- Mutable selection / hover state -----------------------------------
  let viewZoom = NATIVE_VIEW_ZOOM;
  let viewTarget = [0, 0];
  // townName is null for a configured but unclaimed territory.
  let selectedHit = null;        // { townName, territoryId, style, cx, cz }
  let selectedChunk = null;      // [cx, cz]
  let selectedBuilding = null;   // entry from buildingsByChunk
  let hoveredBuilding = null;    // entry from buildingsByChunk
  // Pre-built BitmapLayers for the current selection. Cached because building
  // them is non-trivial for huge territories (Impassable spans 1.5M chunks)
  // and selection rarely changes — pan/zoom should not rebuild them.
  let cachedTownHL = null;
  let cachedTerritoryHL = null;
  let cachedChunkHL = null;
  // Layer instance caches. Keep stable references across redraws so deck.gl
  // doesn't see fresh TileLayer/BitmapLayer instances every viewport tick
  // (which would thrash the tile cache and force buffer re-uploads). Each
  // layer factory below populates its own slot and only invalidates when its
  // inputs actually change.
  let cachedTilesLayer = null;
  let cachedFallbackLayer = null;
  let cachedTerritoryRasterLayer = null;
  let cachedAttacksLayer = null;
  // Bumped each animation frame. Fed to the attacks layer's
  // updateTriggers.getPolygon so deck.gl actually re-tessellates the fill —
  // without it, deck reuses cached geometry because the `data` array
  // reference is unchanged between frames.
  let attackAnimFrame = 0;
  // homes/buildings deliberately not cached: they're gated by showChunkDetail
  // in getLayers, so a cached instance would be finalized by deck.gl on each
  // zoom-out and become unusable when zoomed back in.
  let cachedOutlinesLayer = null;
  let cachedOutlinesData = null;       // visibleSegments ref when last built
  let cachedChunkGridLayer = null;
  let cachedChunkGridData = null;      // visibleChunks ref when last built
  let cachedPortRingFor;               // selectedBuilding when last built
  let cachedPortRingLayer = null;
  let cachedBuildingLabelFor = null;   // [hovered, selected] when last built
  let cachedBuildingLabelLayer = null;
  // Last block / chunk coords pushed into the coords readout. Cache so the
  // hover handler can short-circuit DOM writes when the pointer hasn't moved
  // a full block.
  let lastCoordBx = NaN, lastCoordBz = NaN;
  let lastCoordCx = NaN, lastCoordCz = NaN;
  // Viewport-culled subsets, recomputed when viewState or canvas size changes.
  let visibleChunks = [];
  let visibleSegments = [];

  // Threshold zoom for outlines: at zoom -2 a chunk is 4 px wide — below that
  // the 2 px stroke would dominate the chunk's own colour. Original leaflet
  // had no threshold but its tile-based rendering naturally clipped invisible
  // segments; this keeps us well under PathLayer's geometry budget.
  const OUTLINE_MIN_ZOOM = -2;

  function renderNationPanel(hit) {
    if (hit.townName == null) {
      nationPanel.classList.remove('empty');
      nationPanel.innerHTML = '<h2>No town</h2><div class="subtitle">This territory is unclaimed.</div>';
      return;
    }
    const style = hit.style;
    if (!style.nation) {
      nationPanel.classList.remove('empty');
      nationPanel.innerHTML = '<h2>No nation</h2><div class="subtitle">This town is independent.</div>';
      return;
    }
    const nation = data.towns.nations[style.nation];
    const towns = nation.towns || [];
    const longName = nation.longName && nation.longName !== 'null' ? nation.longName : style.nation;
    const showLong = longName !== style.nation;
    nationPanel.classList.remove('empty');
    nationPanel.innerHTML = `
      <h2>${escapeHtml(longName)}</h2>
      ${showLong ? `<div class="subtitle">${escapeHtml(style.nation)}</div>` : ''}
      <dl>
        <dt>Capital</dt><dd>${escapeHtml(nation.capital || '—')}</dd>
        <dt>Towns</dt><dd>${towns.length}</dd>
        <dt>Allies</dt><dd>${(nation.allies || []).length}</dd>
        <dt>Enemies</dt><dd>${(nation.enemies || []).length}</dd>
      </dl>
      <h3>Towns</h3>
      ${listHtml(towns)}
      <h3>Allies</h3>
      ${listHtml(nation.allies || [])}
      <h3>Enemies</h3>
      ${listHtml(nation.enemies || [])}
    `;
  }

  function renderTerritoryPanel(hit) {
    const town = hit.townName == null
      ? null
      : data.towns?.towns?.[hit.townName];
    const territory = data.world.territories[hit.territoryId];
    const tname = territory && territory.name ? territory.name : `#${hit.territoryId}`;
    const leaderName = (town?.leader && residentNames.get(town.leader)) || '—';
    const residents = (town?.residents || [])
      .map((uuid) => residentNames.get(uuid) || uuid);
    const nodes = (territory && territory.nodes) || [];
    const core = territory && territory.core;
    const sizeChunks = territory ? territory.size : 0;
    const townSummary = town ? `
      <h2>${escapeHtml(hit.townName)}</h2>
      <dl>
        <dt>Leader</dt><dd>${escapeHtml(leaderName)}</dd>
        <dt>Residents</dt><dd>${residents.length}</dd>
        <dt>Territories</dt><dd>${(town.territories || []).length}</dd>
      </dl>
    ` : `
      <h2>Unclaimed</h2>
      <div class="subtitle">No town occupies this territory.</div>
    `;

    territoryPanel.classList.remove('empty');
    territoryPanel.innerHTML = `
      ${townSummary}
      <h3>Territory: ${escapeHtml(tname)}</h3>
      <dl>
        <dt>Size</dt><dd>${sizeChunks} chunks</dd>
        ${core ? `<dt>Core</dt><dd>${core[0]}, ${core[1]}</dd>` : ''}
        <dt>Nodes</dt><dd>${nodes.length ? nodes.map(escapeHtml).join(', ') : '—'}</dd>
      </dl>
      ${town ? `<h3>Residents</h3>${listHtml(residents)}` : ''}
    `;
  }

  // Drop the town/nation/territory selection and empty the panels (which
  // collapses the mobile sheet). Building selection is handled by the caller.
  function clearSelection() {
    selectedHit = null;
    selectedChunk = null;
    nationPanel.innerHTML = '';
    territoryPanel.innerHTML = '';
    nationPanel.classList.add('empty');
    territoryPanel.classList.add('empty');
    refreshPanelsVisibility();
    rebuildHighlights();
    redraw();
  }

  function selectAt(coord) {
    const cx = Math.floor(coord[0] / BLOCKS_PER_CHUNK);
    const cz = Math.floor(coord[1] / BLOCKS_PER_CHUNK);
    selectedBuilding = buildingsByChunk.get(packCoord(cx, cz)) || null;
    const hit = index.owner.get(packCoord(cx, cz));
    if (!hit) {
      clearSelection();
      return;
    }
    selectedHit = hit;
    selectedChunk = [cx, cz];
    renderNationPanel(hit);
    renderTerritoryPanel(hit);
    refreshPanelsVisibility();
    rebuildHighlights();
    redraw();
  }

  // Close button on the mobile sheet: clear everything (including any
  // building label) and let the sheet collapse.
  if (panelsCloseEl) {
    panelsCloseEl.addEventListener('click', () => {
      selectedBuilding = null;
      clearSelection();
    });
  }

  // --- Layer construction -------------------------------------------------
  const PIXELATED = { magFilter: 'nearest', minFilter: 'nearest' };

  // Rasterise an arbitrary set of chunks into a BitmapLayer at one pixel per
  // chunk, sized to the chunks' bounding box. Mirrors the original leaflet
  // chunksToOverlay — keeps highlight memory bounded by the selection's bbox
  // even when the selection is huge (e.g. the large wilderness territory).
  // `chunks` is a flat [cx0, cz0, cx1, cz1, ...] array.
  function chunksToBitmapLayer(id, chunks, color, opacity, skipKeys) {
    if (!chunks.length) return null;
    let cMinCx = Infinity, cMaxCx = -Infinity, cMinCz = Infinity, cMaxCz = -Infinity;
    for (let i = 0; i < chunks.length; i += 2) {
      const cx = chunks[i], cz = chunks[i + 1];
      if (cx < cMinCx) cMinCx = cx;
      if (cx > cMaxCx) cMaxCx = cx;
      if (cz < cMinCz) cMinCz = cz;
      if (cz > cMaxCz) cMaxCz = cz;
    }
    const w = cMaxCx - cMinCx + 1;
    const h = cMaxCz - cMinCz + 1;
    const { buf, commit } = chunkCanvas(w, h);
    const r = color[0], g = color[1], b = color[2];
    const hasSkips = skipKeys && skipKeys.size > 0;
    for (let i = 0; i < chunks.length; i += 2) {
      // Attacked chunks are left transparent here too, so the attack fill is
      // the only colour on them — no highlight tint bleeding through.
      if (hasSkips && skipKeys.has(packCoord(chunks[i], chunks[i + 1]))) continue;
      const px = chunks[i] - cMinCx;
      const py = chunks[i + 1] - cMinCz;
      const p = (py * w + px) * 4;
      buf[p] = r; buf[p + 1] = g; buf[p + 2] = b; buf[p + 3] = 255;
    }
    return new BitmapLayer({
      id,
      image: commit(),
      bounds: [
        cMinCx * BLOCKS_PER_CHUNK,            // left
        (cMaxCz + 1) * BLOCKS_PER_CHUNK,      // bottom (larger y, y-down)
        (cMaxCx + 1) * BLOCKS_PER_CHUNK,      // right
        cMinCz * BLOCKS_PER_CHUNK,            // top (smaller y)
      ],
      opacity,
      textureParameters: PIXELATED,
    });
  }

  function rebuildHighlights() {
    cachedTownHL = null;
    cachedTerritoryHL = null;
    cachedChunkHL = null;
    if (selectedHit) {
      const town = selectedHit.townName == null
        ? null
        : data.towns?.towns?.[selectedHit.townName];
      if (town) {
        const townChunks = [];
        for (const tid of town.territories || []) {
          const t = data.world.territories[tid];
          if (!t) continue;
          const arr = t.chunks;
          for (let i = 0; i < arr.length; i++) townChunks.push(arr[i]);
        }
        cachedTownHL = chunksToBitmapLayer(
          'town-highlight', townChunks, selectedHit.style.color, TOWN_HL_OPACITY,
          attackChunkKeys);
      }
      const territory = data.world.territories[selectedHit.territoryId];
      if (territory) {
        cachedTerritoryHL = chunksToBitmapLayer(
          'territory-highlight', territory.chunks, selectedHit.style.color,
          TERRITORY_HL_OPACITY, attackChunkKeys);
      }
    }
    if (selectedChunk) {
      const color = selectedHit ? selectedHit.style.color : [255, 255, 255];
      cachedChunkHL = chunksToBitmapLayer(
        'chunk-highlight', [selectedChunk[0], selectedChunk[1]], color,
        CHUNK_HL_OPACITY, attackChunkKeys);
    }
    // Attack fill alpha is per-chunk and depends on the selection state, so
    // it must be rebuilt whenever the highlights change.
    cachedAttacksLayer = null;
  }

  // Gather chunks/segments inside the current viewport (plus a small margin so
  // a quick pan doesn't reveal blank chunks). Only runs at zooms where the
  // grid/outlines are visible, so the worst case is bounded by the viewport
  // pixel area, not the world size.
  //
  // Short-circuits when the visible cell range hasn't changed: a small pan
  // within a single 32×32-chunk cell does no work and leaves the existing
  // arrays alone (so cached outline/grid layers stay valid). Returns true
  // when the visible set actually changed.
  let prevMinCellX = null;
  let prevMaxCellX, prevMinCellZ, prevMaxCellZ;
  let prevWantGrid = false;
  let prevWantOutlines = false;
  function updateVisible() {
    const wantGrid = viewZoom >= NATIVE_VIEW_ZOOM;
    const wantOutlines = viewZoom >= OUTLINE_MIN_ZOOM;
    if (!wantGrid && !wantOutlines) {
      if (prevMinCellX === null && !prevWantGrid && !prevWantOutlines) return false;
      visibleChunks = [];
      visibleSegments = [];
      prevMinCellX = null;
      prevWantGrid = false;
      prevWantOutlines = false;
      return true;
    }
    const W = window.innerWidth;
    const H = window.innerHeight;
    const scale = Math.pow(2, viewZoom);
    // 20% margin on each side for pan latency.
    const halfW = (W / 2 / scale) * 1.2;
    const halfH = (H / 2 / scale) * 1.2;
    const minCellX = Math.floor((viewTarget[0] - halfW) / SPATIAL_CELL_BLOCKS);
    const maxCellX = Math.floor((viewTarget[0] + halfW) / SPATIAL_CELL_BLOCKS);
    const minCellZ = Math.floor((viewTarget[1] - halfH) / SPATIAL_CELL_BLOCKS);
    const maxCellZ = Math.floor((viewTarget[1] + halfH) / SPATIAL_CELL_BLOCKS);
    if (prevMinCellX === minCellX && prevMaxCellX === maxCellX
        && prevMinCellZ === minCellZ && prevMaxCellZ === maxCellZ
        && prevWantGrid === wantGrid && prevWantOutlines === wantOutlines) {
      return false;
    }
    prevMinCellX = minCellX;
    prevMaxCellX = maxCellX;
    prevMinCellZ = minCellZ;
    prevMaxCellZ = maxCellZ;
    prevWantGrid = wantGrid;
    prevWantOutlines = wantOutlines;
    visibleChunks = [];
    visibleSegments = [];
    for (let bx = minCellX; bx <= maxCellX; bx++) {
      for (let bz = minCellZ; bz <= maxCellZ; bz++) {
        const k = packCoord(bx, bz);
        if (wantGrid) {
          const c = ownerCells.get(k);
          if (c) for (const item of c) visibleChunks.push(item);
        }
        if (wantOutlines) {
          const s = segmentCells.get(k);
          if (s) for (const item of s) visibleSegments.push(item);
        }
      }
    }
    return true;
  }

  function fallbackLayer() {
    if (cachedFallbackLayer || !fallbackCanvas) return cachedFallbackLayer;
    cachedFallbackLayer = new BitmapLayer({
      id: 'tiles-fallback',
      image: fallbackCanvas,
      bounds: fallbackCanvasBounds,
    });
    return cachedFallbackLayer;
  }

  function tilesLayer() {
    if (cachedTilesLayer) return cachedTilesLayer;
    cachedTilesLayer = new TileLayer({
      id: 'tiles',
      // tileSize is in world units (= blocks here): each tile covers
      // TILE_BLOCK_EXTENT / 2^z blocks at LOD z. zoomOffset shifts the LOD
      // selection so viewport.zoom 0 (1 px = 1 block) requests the deepest
      // pyramid level (LOD = NATIVE_LOD_RANGE).
      tileSize: TILE_BLOCK_EXTENT,
      zoomOffset: NATIVE_LOD_RANGE,
      minZoom: 0,
      maxZoom: NATIVE_LOD_RANGE,
      // Bound tile requests by the tile pyramid's true coverage, not the
      // territory bbox (which would clip terrain beyond configured territory).
      extent: TILE_WORLD_BOUNDS,
      // Hold extra rings of off-screen tiles so backtracking pans don't
      // re-fetch.
      maxCacheSize: 512,
      // 'best-available' (default) reuses cached parent/child tiles to fill
      // gaps while the current LOD is in flight; the fallback BitmapLayer
      // covers the remaining "never visited" gaps.
      refinementStrategy: 'best-available',
      // Bump from the default 6 so a fast pan can keep more tiles in flight
      // instead of cancelling them on every viewport tick.
      maxRequests: 24,
      getTileData: ({ index: { x, y, z }, signal }) => {
        const nativeZoom = z - NATIVE_LOD_RANGE;
        const url = `tiles/zoom.${nativeZoom}/${bucketOf(x)}/${bucketOf(y)}/tile.${x}.${y}.webp`;
        return fetch(url, { signal }).then((r) => {
          if (!r.ok) return null;
          return r.blob().then((blob) => createImageBitmap(blob));
        }).catch(() => null);
      },
      renderSubLayers: (props) => {
        const image = props.data;
        if (!image) return null;
        // tile.boundingBox is [[left, top], [right, bottom]] in world coords;
        // BitmapLayer bounds are [left, bottom, right, top] (top is the
        // smaller-y world coord with flipY).
        const [[left, top], [right, bottom]] = props.tile.boundingBox;
        return new BitmapLayer({
          id: `${props.id}-bitmap`,
          image,
          bounds: [left, bottom, right, top],
        });
      },
    });
    return cachedTilesLayer;
  }

  function territoryRasterLayer() {
    if (!worldImage) return null;
    if (cachedTerritoryRasterLayer) return cachedTerritoryRasterLayer;
    cachedTerritoryRasterLayer = new BitmapLayer({
      id: 'territory-raster',
      image: worldImage,
      // Image's top-left chunk = (minCx, minCz) — top-left of that chunk in
      // blocks is (minBlockX, minBlockZ). Bottom-right is (maxBlockX,
      // maxBlockZ). With y-down, "top" is the smaller y.
      bounds: [minBlockX, maxBlockZ, maxBlockX, minBlockZ],
      opacity: TERRITORY_OPACITY,
      textureParameters: PIXELATED,
    });
    return cachedTerritoryRasterLayer;
  }

  // Each outline element is a single chunk-edge — independent 2-vertex
  // segments, not a connected polyline — so LineLayer (quad-expand vertex
  // shader, one segment = one quad) is dramatically lighter than PathLayer,
  // which builds miter joints and end caps for every entry even with rounding
  // disabled.
  function outlinesLayer() {
    if (!visibleSegments.length) {
      cachedOutlinesLayer = null;
      cachedOutlinesData = null;
      return null;
    }
    if (cachedOutlinesData === visibleSegments) return cachedOutlinesLayer;
    cachedOutlinesData = visibleSegments;
    cachedOutlinesLayer = new LineLayer({
      id: 'outlines',
      data: visibleSegments,
      getSourcePosition: (d) => d.path[0],
      getTargetPosition: (d) => d.path[1],
      getColor: (d) => d.color,
      widthUnits: 'pixels',
      getWidth: 2,
    });
    return cachedOutlinesLayer;
  }

  // Opacity a chunk is currently drawn at, matching rebuildHighlights: the
  // selected chunk reads through the opaque chunk highlight, chunks in the
  // selected territory/town through their highlights, everything else through
  // the base territory raster.
  function chunkDisplayOpacity(cx, cz) {
    if (selectedChunk && cx === selectedChunk[0] && cz === selectedChunk[1]) {
      return CHUNK_HL_OPACITY;
    }
    if (selectedHit) {
      const o = index.owner.get(packCoord(cx, cz));
      if (o) {
        if (o.territoryId === selectedHit.territoryId) return TERRITORY_HL_OPACITY;
        if (selectedHit.townName != null && o.townName === selectedHit.townName) {
          return TOWN_HL_OPACITY;
        }
      }
    }
    return TERRITORY_OPACITY;
  }

  // Per-chunk alpha so the fill matches the opacity its chunk is shown at
  // (layer opacity stays 1; the alpha here is the only opacity applied).
  function attackFillColor(rgb, cx, cz) {
    return [rgb[0], rgb[1], rgb[2],
      Math.round(chunkDisplayOpacity(cx, cz) * 255)];
  }

  // The attacked chunk is knocked out of the territory raster + highlights
  // (see buildWorldImage / chunksToBitmapLayer), so these two fills are the
  // only colour on it — drawn straight over the map, no old-owner tint:
  //   • captured  — attacker colour, left part, grows with progress
  //   • remaining — defender colour, right part, shrinks to nothing
  function attacksLayer() {
    if (cachedAttacksLayer || !attackChunks.length) return cachedAttacksLayer;
    // Token changes whenever the selection does, so deck.gl re-runs
    // getFillColor (the cached layer is also nulled in rebuildHighlights).
    const selToken = `${selectedChunk ? selectedChunk.join(',') : ''}|`
      + `${selectedHit ? `${selectedHit.townName}#${selectedHit.territoryId}` : ''}`;
    const triggers = {
      getPolygon: attackAnimFrame,
      getFillColor: selToken,
    };
    // Stamp the live progress fraction once per (per-frame) rebuild so the two
    // layers' getPolygon accessors don't each recompute it — and read the
    // wall-clock — 2× per chunk every animation frame.
    const now = Date.now() / 1000;
    for (const a of attackChunks) a.frac = attackProgress(a.s, a.e, now);
    cachedAttacksLayer = [
      new SolidPolygonLayer({
        id: 'attacks-remaining',
        data: attackChunks,
        getPolygon: (d) => chunkRemainderPolygon(d.cx, d.cz, d.frac),
        getFillColor: (d) => attackFillColor(d.defColor, d.cx, d.cz),
        updateTriggers: triggers,
      }),
      new SolidPolygonLayer({
        id: 'attacks-captured',
        data: attackChunks,
        getPolygon: (d) => chunkProgressPolygon(d.cx, d.cz, d.frac),
        getFillColor: (d) => attackFillColor(d.color, d.cx, d.cz),
        updateTriggers: triggers,
      }),
    ];
    return cachedAttacksLayer;
  }

  // Per-territory-chunk stroked square in the chunk's territory colour. Only
  // visible at native zoom and above where chunks are at least 16 px wide;
  // viewport-culled so we never upload more than a few thousand chunks.
  //
  // Rendered as two LineLayers (right edge + bottom edge of each chunk)
  // rather than one PolygonLayer-with-stroke. PolygonLayer tesselates each
  // square boundary as a closed polyline strip with miter joints — expensive
  // for ~8k chunks per viewport. Each chunk's top/left edge is supplied by
  // its top/left neighbour's bottom/right edge (the viewport cull pulls
  // those neighbours in too via the 20% margin), so visually nothing is lost.
  function chunkGridLayer() {
    if (!visibleChunks.length) {
      cachedChunkGridLayer = null;
      cachedChunkGridData = null;
      return null;
    }
    if (cachedChunkGridData === visibleChunks) return cachedChunkGridLayer;
    cachedChunkGridData = visibleChunks;
    cachedChunkGridLayer = [
      new LineLayer({
        id: 'chunk-grid-bottom',
        data: visibleChunks,
        getSourcePosition: (d) => [
          d.cx * BLOCKS_PER_CHUNK,
          (d.cz + 1) * BLOCKS_PER_CHUNK,
        ],
        getTargetPosition: (d) => [
          (d.cx + 1) * BLOCKS_PER_CHUNK,
          (d.cz + 1) * BLOCKS_PER_CHUNK,
        ],
        getColor: (d) => d.style.color,
        widthUnits: 'pixels',
        getWidth: 1,
      }),
      new LineLayer({
        id: 'chunk-grid-right',
        data: visibleChunks,
        getSourcePosition: (d) => [
          (d.cx + 1) * BLOCKS_PER_CHUNK,
          d.cz * BLOCKS_PER_CHUNK,
        ],
        getTargetPosition: (d) => [
          (d.cx + 1) * BLOCKS_PER_CHUNK,
          (d.cz + 1) * BLOCKS_PER_CHUNK,
        ],
        getColor: (d) => d.style.color,
        widthUnits: 'pixels',
        getWidth: 1,
      }),
    ];
    return cachedChunkGridLayer;
  }

  // Visibility at low zooms is handled in getLayers (omitted from the layer
  // array, not toggled via `visible`). Reconstructed each redraw — deck.gl
  // finalizes the layer when it leaves the array, so a cached instance would
  // be dead on the way back in. Cheap: index.homes is small (~hundreds).
  function homesLayer() {
    return new TextLayer({
      id: 'homes',
      data: index.homes,
      getPosition: (d) => chunkCenter(d.cx, d.cz),
      getText: () => 'H',
      getColor: (d) => d.color,
      // 12 blocks ≈ 75% of the chunk. sizeUnits 'common' makes the H scale
      // with zoom, matching leaflet's 0.8*pxPerChunk font size.
      sizeUnits: 'common',
      getSize: BLOCKS_PER_CHUNK * 0.75,
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'center',
      fontFamily: MONO_FONT,
      fontWeight: 700,
      characterSet: ['H'],
    });
  }

  function portRingLayer() {
    if (cachedPortRingFor === selectedBuilding) return cachedPortRingLayer;
    cachedPortRingFor = selectedBuilding;
    if (!selectedBuilding || selectedBuilding.data.type !== 'port'
        || selectedBuilding.data.tier == null) {
      cachedPortRingLayer = null;
      return null;
    }
    const radius = PORT_RANGE_BY_TIER[selectedBuilding.data.tier - 1];
    if (radius == null) {
      cachedPortRingLayer = null;
      return null;
    }
    cachedPortRingLayer = new ScatterplotLayer({
      id: 'port-ring',
      data: [{ position: selectedBuilding.center }],
      getPosition: (d) => d.position,
      getRadius: radius,
      radiusUnits: 'common',
      filled: true,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 4,
      getFillColor: [255, 255, 255, 64],
      getLineColor: [255, 255, 255, 255],
    });
    return cachedPortRingLayer;
  }

  // Visibility at low zooms is handled in getLayers — see homesLayer note.
  function buildingsLayer() {
    if (!buildingsList.length) return null;
    return new IconLayer({
      id: 'buildings',
      data: buildingsList,
      pickable: true,
      getPosition: (d) => d.center,
      getIcon: (d) => ({
        url: `img/buildings/${encodeURIComponent(d.data.type || 'unknown')}.png`,
        // Building images are pixel art at 64×64 (port.png). Treat all icons
        // as 64×64 even if the file is missing — IconLayer just shows nothing.
        width: 64,
        height: 64,
        // ID lets the icon manager dedupe URLs across buildings of the same type.
        id: d.data.type || 'unknown',
        anchorX: 32,
        anchorY: 32,
      }),
      // Icons sized in world units so they scale with zoom; match the original
      // image inset (~70% of a chunk).
      sizeUnits: 'common',
      getSize: BUILDING_IMAGE_SIZE,
      // Pixelated upscaling — building images are 8-bit pixel art.
      textureParameters: PIXELATED,
    });
  }

  function buildingLabelLayer() {
    if (cachedBuildingLabelFor
        && cachedBuildingLabelFor[0] === hoveredBuilding
        && cachedBuildingLabelFor[1] === selectedBuilding) {
      return cachedBuildingLabelLayer;
    }
    cachedBuildingLabelFor = [hoveredBuilding, selectedBuilding];
    // Render only the labels actually visible (hovered + selected), instead
    // of carrying invisible label markers for every building like leaflet did.
    const seen = new Set();
    const items = [];
    for (const e of [hoveredBuilding, selectedBuilding]) {
      if (!e) continue;
      const key = packCoord(e.cx, e.cz);
      if (seen.has(key)) continue;
      seen.add(key);
      items.push(e);
    }
    if (!items.length) {
      cachedBuildingLabelLayer = null;
      return null;
    }
    cachedBuildingLabelLayer = new TextLayer({
      id: 'building-labels',
      data: items,
      getPosition: (d) => d.labelAnchor,
      getText: (d) => d.labelText,
      // Pixel-fixed size so the chip stays readable at any zoom (matches the
      // original DOM-rendered label).
      sizeUnits: 'pixels',
      getSize: 11,
      getColor: [230, 230, 230, 255],
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'bottom',
      // Lift the chip a few pixels above the chunk top edge.
      getPixelOffset: [0, -6],
      background: true,
      backgroundPadding: [6, 2, 6, 2],
      getBackgroundColor: [20, 20, 20, 240],
      fontFamily: MONO_FONT,
    });
    return cachedBuildingLabelLayer;
  }

  function getLayers() {
    const showChunkDetail = viewZoom >= NATIVE_VIEW_ZOOM;
    const grid = chunkGridLayer();
    return [
      fallbackLayer(),
      tilesLayer(),
      territoryRasterLayer(),
      cachedTownHL,
      cachedTerritoryHL,
      outlinesLayer(),
      ...(grid || []),
      showChunkDetail ? homesLayer() : null,
      cachedChunkHL,
      // Above the chunk highlight: the selected-chunk highlight is opaque, so
      // drawing attacks under it would hide the fill on a selected chunk.
      ...(attacksLayer() || []),
      portRingLayer(),
      showChunkDetail ? buildingsLayer() : null,
      buildingLabelLayer(),
    ].filter(Boolean);
  }

  // Constrain the pan target so the visible viewport can never show anything
  // outside PAN_BOUNDS. `target` is [worldX, worldZ, depth] (target[1] is
  // world Z — same axis updateVisible culls on). The viewport spans
  // target ± (innerSize / 2 / scale) in world units, so the target must stay
  // that half-extent inside each edge. When the box is smaller than the
  // viewport on an axis (zoomed far enough out), the bounds invert — pin the
  // target to the box centre on that axis instead.
  function clampTarget(vs) {
    const [bMinX, bMinZ, bMaxX, bMaxZ] = PAN_BOUNDS;
    const scale = Math.pow(2, vs.zoom);
    const halfW = window.innerWidth / 2 / scale;
    const halfH = window.innerHeight / 2 / scale;
    const xLo = bMinX + halfW;
    const xHi = bMaxX - halfW;
    const zLo = bMinZ + halfH;
    const zHi = bMaxZ - halfH;
    const tx = xLo > xHi
      ? (bMinX + bMaxX) / 2
      : Math.min(xHi, Math.max(xLo, vs.target[0]));
    const tz = zLo > zHi
      ? (bMinZ + bMaxZ) / 2
      : Math.min(zHi, Math.max(zLo, vs.target[1]));
    return { ...vs, target: [tx, tz, vs.target[2] || 0] };
  }

  // Controlled view state: clamping in onViewStateChange only takes effect if
  // deck.gl is told the resulting state (uncontrolled initialViewState ignores
  // the handler's return). Seed it already clamped so the first paint can't
  // start out of bounds either.
  let viewState = clampTarget({
    target: [19000, -4500, 0],
    zoom: -1,
    minZoom: VIEW_MIN_ZOOM,
    maxZoom: VIEW_MAX_ZOOM,
  });

  // --- Deck instance ------------------------------------------------------
  const deck = new Deck({
    parent: document.getElementById('map'),
    views: new OrthographicView({
      id: 'main',
      controller: { dragRotate: false, scrollZoom: { smooth: false, speed: 0.2 } },
    }),
    viewState,
    onViewStateChange: ({ viewState: next }) => {
      viewState = clampTarget(next);
      viewZoom = viewState.zoom;
      viewTarget = viewState.target;
      // Controlled mode: push the clamped state back so the pan/zoom that the
      // controller computed is actually applied (and bounded).
      deck.setProps({ viewState });
      // Skip the layer setProps when the visible set hasn't changed — deck.gl
      // still re-renders the cached layers against the new viewport on its
      // own, so we only need to push new layers when their contents change.
      if (updateVisible()) redraw();
      return viewState;
    },
    onClick: (info) => {
      if (info.coordinate) selectAt(info.coordinate);
    },
    onHover: (info) => {
      // Building hover state (only IconLayer is pickable, so info.object is
      // either a building entry or null).
      const next = info.object || null;
      if (next !== hoveredBuilding) {
        hoveredBuilding = next;
        redraw();
      }
      // Coords readout — pointermove fires at display rate, so guard against
      // touching the DOM when nothing changed. Only write to the two cached
      // text nodes when the integer block (or chunk) coord actually moves.
      // (`>> 4` is an arithmetic shift on int32 — equivalent to Math.floor(x/16)
      // including for negatives, no Math.floor call needed.)
      if (info.coordinate) {
        const bx = Math.floor(info.coordinate[0]);
        const bz = Math.floor(info.coordinate[1]);
        if (bx !== lastCoordBx || bz !== lastCoordBz) {
          coordsBlockText.nodeValue = `X ${bx}, Z ${bz}`;
          lastCoordBx = bx;
          lastCoordBz = bz;
          const cx = bx >> 4;
          const cz = bz >> 4;
          if (cx !== lastCoordCx || cz !== lastCoordCz) {
            coordsChunkText.nodeValue = `chunk ${cx}, ${cz}`;
            lastCoordCx = cx;
            lastCoordCz = cz;
          }
        }
        if (coordsEl.hidden) coordsEl.hidden = false;
      } else if (!coordsEl.hidden) {
        coordsEl.hidden = true;
      }
    },
    onLoad: hideLoading,
    layers: [],  // populated by the post-init updateVisible+redraw below
  });

  // Initial cull then first paint. Doing this after `new Deck` rather than via
  // `layers:` lets updateVisible read the actual canvas size. Seed
  // viewZoom/viewTarget from the (clamped) initial viewState first — otherwise
  // the first cull runs against the [0,0]/NATIVE_VIEW_ZOOM defaults instead of
  // the real starting camera, and the culled layers (outlines, chunk grid)
  // stay blank until the first pan fires onViewStateChange.
  viewZoom = viewState.zoom;
  viewTarget = viewState.target;
  updateVisible();
  redraw();

  document.getElementById('map').addEventListener('mouseleave', () => {
    coordsEl.hidden = true;
  });

  // Window resize widens/shrinks the visible viewport — re-cull so chunks
  // stay populated all the way to the new edges.
  window.addEventListener('resize', () => {
    // A larger viewport could now reach past an edge — re-clamp the target.
    const clamped = clampTarget(viewState);
    if (clamped.target[0] !== viewState.target[0]
        || clamped.target[1] !== viewState.target[1]) {
      viewState = clamped;
      viewTarget = viewState.target;
      deck.setProps({ viewState });
    }
    if (updateVisible()) redraw();
  });

  function redraw() {
    deck.setProps({ layers: getLayers() });
  }

  // --- Periodic refresh --------------------------------------------------
  // Poll the three mutable files every 30s. Conditional GETs (If-None-Match /
  // If-Modified-Since) make the unchanged-case a single empty 304 per file,
  // so the 5 MiB towns.json isn't re-downloaded on every tick. world.json is
  // treated as static and never re-fetched.
  const REFRESH_INTERVAL_MS = 30_000;
  const REFRESH_FILES = ['towns', 'war', 'buildings'];
  // war.json may be 404 (no active war); buildings.json may be 404 too.
  // For those, a 404 transitions the in-memory copy to `null` exactly once.
  const REFRESH_OPTIONAL = new Set(['war', 'buildings']);

  async function fetchIfChanged(name) {
    const url = `nodes/${name}.json`;
    const prev = fileMeta[name];
    const headers = {};
    if (prev?.etag) headers['If-None-Match'] = prev.etag;
    if (prev?.lastMod) headers['If-Modified-Since'] = prev.lastMod;
    // cache: 'no-store' bypasses the HTTP cache so we see the real 304/200
    // status rather than the browser opaquely returning its cached body.
    let r;
    try {
      r = await fetch(url, { headers, cache: 'no-store' });
    } catch {
      return null;
    }
    if (r.status === 304) return null;
    if (!r.ok) {
      if (REFRESH_OPTIONAL.has(name) && data[name] != null) {
        fileMeta[name] = null;
        return { body: null };
      }
      return null;
    }
    try {
      const body = await r.json();
      fileMeta[name] = {
        etag: r.headers.get('ETag'),
        lastMod: r.headers.get('Last-Modified'),
      };
      return { body };
    } catch {
      return null;
    }
  }

  // Cheap, index-reusing re-eval of which attacks have reached their start
  // time. The visible set can change even when no file did; completed attacks
  // remain visible until war.json removes them. Returns true if the set changed
  // (the caller then invalidates the layer cache and redraws).
  // Membership-only equality (ignores s/e — the fill is animated separately
  // against the live clock). Zero-allocation: this runs on a 1s timer, so the
  // old join-string approach rebuilt two big strings every second forever.
  function sameAttacks(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      const x = a[i], y = b[i];
      if (x.cx !== y.cx || x.cz !== y.cz
          || x.color[0] !== y.color[0] || x.color[1] !== y.color[1]
          || x.color[2] !== y.color[2]) return false;
    }
    return true;
  }
  function refreshActiveAttacks() {
    const next = buildAttackChunks(data.war, data.towns, index);
    if (sameAttacks(next, attackChunks)) return false;
    attackChunks = next;
    // The set of knocked-out chunks changed (an attack started, or one was
    // dropped from war.json), so the raster + highlights must be re-masked.
    attackChunkKeys = new Set(next.map((a) => packCoord(a.cx, a.cz)));
    worldImage = buildWorldImage(index, attackChunkKeys);
    cachedTerritoryRasterLayer = null;
    cachedAttacksLayer = null;
    rebuildHighlights();
    return true;
  }

  let refreshInFlight = false;
  async function checkForUpdates() {
    // setInterval can fire while a slow refresh is still resolving (esp. when
    // the tab was backgrounded and the runtime fires a backlog). Skip rather
    // than rebuild concurrently — concurrent buildAll() would race on the
    // shared mutable state.
    if (refreshInFlight) return;
    refreshInFlight = true;
    try {
      const updates = await Promise.all(REFRESH_FILES.map(fetchIfChanged));
      let dirty = false;
      const newData = { ...data };
      for (let i = 0; i < REFRESH_FILES.length; i++) {
        if (updates[i]) {
          newData[REFRESH_FILES[i]] = updates[i].body;
          dirty = true;
        }
      }
      if (!dirty) {
        // No file changed, but an attack may have reached its start time since
        // the last tick — re-evaluate cheaply rather than skip.
        if (refreshActiveAttacks()) redraw();
        return;
      }
      data = newData;
      buildAll();

      // Invalidate data-dependent layer caches. tiles/fallback/homes/buildings
      // either don't read data-derived state or are already constructed each
      // redraw, so they don't need explicit invalidation here.
      cachedTerritoryRasterLayer = null;
      cachedAttacksLayer = null;
      cachedOutlinesLayer = null;
      cachedOutlinesData = null;
      cachedChunkGridLayer = null;
      cachedChunkGridData = null;

      // Re-resolve selection/hover by coord. The town/territory/building
      // pointed at may have changed owner or vanished — keep what still
      // resolves, drop what doesn't.
      if (selectedHit) {
        const newHit = index.owner.get(packCoord(selectedHit.cx, selectedHit.cz));
        if (newHit) {
          selectedHit = newHit;
          renderNationPanel(newHit);
          renderTerritoryPanel(newHit);
        } else {
          selectedHit = null;
          selectedChunk = null;
        }
      }
      const reResolveBuilding = (e) =>
        (e && buildingsByChunk.get(packCoord(e.cx, e.cz))) || null;
      selectedBuilding = reResolveBuilding(selectedBuilding);
      hoveredBuilding = reResolveBuilding(hoveredBuilding);
      rebuildHighlights();

      // Force a fresh viewport cull — the cell range may not have changed,
      // but the underlying chunk/segment buckets have, so visibleChunks /
      // visibleSegments must be regenerated. Nulling prevMinCellX alone is
      // enough: every cull-skip check ANDs it in, so a null forces the full
      // recompute (the other prev* are overwritten before they're next read).
      prevMinCellX = null;
      updateVisible();
      redraw();
      // A changed war.json may have introduced new timed attacks — (re)start
      // the real-time fill loop if it isn't already running.
      ensureAttackAnim();
    } finally {
      refreshInFlight = false;
    }
  }

  setInterval(checkForUpdates, REFRESH_INTERVAL_MS);

  // Drive the attack progress-fill in real time. The 30s data poll is far too
  // coarse for a visible "filling" effect, so a requestAnimationFrame loop
  // invalidates the cached layer every frame (getPolygon then recomputes the
  // fill fraction against the live clock) for as long as any timed attack is
  // in flight. An attack only animates while it's still *filling* (now < e);
  // once complete it's a static full chunk, so the loop self-terminates and
  // we stop redrawing every frame for nothing.
  const hasAnimatingAttack = () => {
    const now = Date.now() / 1000;
    return attackChunks.some(
      (c) => typeof c.s === 'number' && typeof c.e === 'number'
        && c.e > c.s && now < c.e,
    );
  };
  let attackAnimRunning = false;
  function tickAttackAnim() {
    if (!hasAnimatingAttack()) {
      // Final frame: settle every just-completed fill exactly at 100% (the
      // loop may have stopped a frame short of e) before going idle.
      attackAnimFrame++;
      cachedAttacksLayer = null;
      redraw();
      attackAnimRunning = false;
      return;
    }
    attackAnimFrame++;
    cachedAttacksLayer = null;
    redraw();
    requestAnimationFrame(tickAttackAnim);
  }
  function ensureAttackAnim() {
    if (attackAnimRunning || !hasAnimatingAttack()) return;
    attackAnimRunning = true;
    requestAnimationFrame(tickAttackAnim);
  }

  // The rAF loop only renders the fill; it never changes which attacks are
  // active. A 1s interval picks up attacks that just reached their start time,
  // (re)starts the animation loop for them, and lets the loop settle completed
  // fills without waiting up to 30s for the next war.json poll.
  setInterval(() => {
    if (refreshActiveAttacks()) {
      cachedAttacksLayer = null;
      redraw();
    }
    ensureAttackAnim();
  }, 1000);

  ensureAttackAnim();
}
