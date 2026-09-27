import { wrapX } from './world-wrap.mjs';
const BLOCKS_PER_CHUNK = 16;
const UNCLAIMED_TERRITORY_COLOR = [120, 120, 120];
const NATION_FONT = 'Cormorant Garamond';
const PACK_OFFSET = 0x80000;
const PACK_STRIDE = 0x100000;
function packCoord(x, z) {
  return (x + PACK_OFFSET) * PACK_STRIDE + (z + PACK_OFFSET);
}

const CELL_SIZE = 32;
const CELL_AREA = CELL_SIZE * CELL_SIZE;
const indexWorlds = new WeakMap();
const nationLabelCache = new WeakMap();

// Keep one integer per chunk, rather than a Map entry and object per chunk.
// Each integer refers to a shared ownership record; objects are created only
// for queried/visible chunks. Sparse cells also support distant territories
// without allocating a rectangle spanning the entire coordinate range.
class ChunkOwners {
  constructor(previous) {
    this.previous = previous;
    this.pending = [];
    this.cells = new Map();
    this.cellList = [];
    this.records = [null];
    this.sources = [null];
    this.size = 0;
    this.hasOverlaps = false;
    this.minCx = this.minCz = Infinity;
    this.maxCx = this.maxCz = -Infinity;
  }

  addChunks(chunks, record) {
    this.pending.push([chunks, record]);
  }

  _addChunks(chunks, record) {
    const id = this.records.push(record) - 1;
    this.sources.push(chunks);
    let previousKey, cell;
    for (let i = 0; i < chunks.length; i += 2) {
      const cx = chunks[i], cz = chunks[i + 1];
      const cellX = Math.floor(cx / CELL_SIZE), cellZ = Math.floor(cz / CELL_SIZE);
      const key = packCoord(cellX, cellZ);
      if (key !== previousKey) {
        previousKey = key;
        cell = this.cells.get(key);
        if (!cell) {
          cell = { x: cellX * CELL_SIZE, z: cellZ * CELL_SIZE,
            index: this.cellList.length, ids: new Uint32Array(CELL_AREA) };
          this.cells.set(key, cell);
          this.cellList.push(cell);
        }
      }
      const offset = (cz - cell.z) * CELL_SIZE + cx - cell.x;
      if (cell.ids[offset]) { this.hasOverlaps = true; continue; }
      cell.ids[offset] = id;
      this.size++;
      if (cx < this.minCx) this.minCx = cx;
      if (cx > this.maxCx) this.maxCx = cx;
      if (cz < this.minCz) this.minCz = cz;
      if (cz > this.maxCz) this.maxCz = cz;
    }
  }

  finish() {
    const previous = this.previous;
    const incoming = this.pending;
    const byTerritory = new Map(incoming.map(entry => [entry[1].territoryId, entry]));
    if (previous && !previous.hasOverlaps && byTerritory.size === incoming.length
        && incoming.length === previous.records.length - 1
        && previous.records.slice(1).every((record, i) =>
          byTerritory.get(record.territoryId)?.[0] === previous.sources[i + 1])) {
      // Nonoverlapping territory cells retain their IDs when ownership changes.
      this.pending = previous.records.slice(1).map(record => byTerritory.get(record.territoryId));
    }
    const idsByTerritory = new Map(this.pending.map(([, record], i) => [record.territoryId, i + 1]));
    this.sourceOrder = incoming.map(([, record], i) => this.pending === incoming ? i + 1 : idsByTerritory.get(record.territoryId));
    if (previous && this.pending.length === previous.records.length - 1
        && this.pending.every(([chunks, record], i) => chunks === previous.sources[i + 1]
          && record.territoryId === previous.records[i + 1].territoryId)) {
      Object.assign(this, { cells: previous.cells, cellList: previous.cellList, size: previous.size,
        minCx: previous.minCx, maxCx: previous.maxCx, minCz: previous.minCz, maxCz: previous.maxCz,
        hasOverlaps: previous.hasOverlaps,
        records: [null, ...this.pending.map(([, record]) => record)],
        sources: [null, ...this.pending.map(([chunks]) => chunks)] });
      delete this.previous; delete this.pending;
      return;
    }
    for (const [chunks, record] of this.pending) this._addChunks(chunks, record);
    delete this.previous; delete this.pending;
    for (const cell of this.cellList) {
      const x = cell.x / CELL_SIZE, z = cell.z / CELL_SIZE;
      cell.top = this.cells.get(packCoord(x, z - 1));
      cell.bottom = this.cells.get(packCoord(x, z + 1));
      cell.left = this.cells.get(packCoord(x - 1, z));
      cell.right = this.cells.get(packCoord(x + 1, z));
    }
  }

  getId(cx, cz) {
    const x = Math.floor(cx / CELL_SIZE), z = Math.floor(cz / CELL_SIZE);
    const cell = this.cells.get(packCoord(x, z));
    return cell?.ids[(cz - z * CELL_SIZE) * CELL_SIZE + cx - x * CELL_SIZE] || 0;
  }

  getChunk(cx, cz) {
    const record = this.records[this.getId(cx, cz)];
    return record ? { ...record, cx, cz } : undefined;
  }

  get(key) {
    return this.getChunk(Math.floor(key / PACK_STRIDE) - PACK_OFFSET,
      key % PACK_STRIDE - PACK_OFFSET);
  }
}

function nonZero(c) { return Array.isArray(c) && (c[0] | c[1] | c[2]) !== 0; }

// First non-zero RGB triple in priority order, else `fallback`. Centralises
// the nation-then-town-then-grey colour precedence used across the map.
function pickColor(candidates, fallback) {
  for (const c of candidates) if (nonZero(c)) return c;
  return fallback;
}

// w×h offscreen canvas at one pixel per chunk. Callers write RGBA straight
// into `buf`, then call commit() to upload and return the canvas.
function chunkCanvas(w, h) {
  const canvas = typeof document === 'undefined' ? new OffscreenCanvas(w, h) : document.createElement('canvas');
  canvas.width = w;
  canvas.height = h;
  const ctx = canvas.getContext('2d');
  const img = ctx.createImageData(w, h);
  return {
    buf: img.data,
    commit() { ctx.putImageData(img, 0, 0); return canvas; },
  };
}

// --- Data indexing -------------------------------------------------------
function buildIndex({ towns, world, war }, previous) {
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

  // Town snapshots are keyed by name; the war journal uses persistent UUIDs.
  const townNames = new Map(Object.entries(towns.towns || {}).flatMap(([name, town]) =>
    [[name, name], ...(town.uuid ? [[town.uuid, name]] : [])]));
  const resolveTown = id => townNames.get(id) || null;
  const occupiedChunks = new Map();
  for (const [id, coordinates] of Object.entries(war?.occupied || {})) {
    const name = resolveTown(id);
    if (!name || !Array.isArray(coordinates)) continue;
    const pairs = Array.isArray(coordinates[0]) ? coordinates
      : Array.from({ length: Math.floor(coordinates.length / 2) }, (_, i) => coordinates.slice(i * 2, i * 2 + 2));
    for (const [cx, cz] of pairs) occupiedChunks.set(packCoord(cx, cz), name);
  }
  const occupiedByTid = new Map();
  for (const [name, town] of Object.entries(towns.towns || {})) {
    for (const tid of town.captured || []) occupiedByTid.set(+tid, name);
  }
  // Explicit null journal entries liberate a territory, even if towns.json is older.
  for (const [tid, occupation] of Object.entries(war?.territoryOccupations || {})) {
    if (occupation.owner == null) occupiedByTid.delete(+tid);
    else {
      const name = resolveTown(occupation.owner);
      if (name) occupiedByTid.set(+tid, name);
    }
  }

  const territoryStyle = new Map();
  const owner = new ChunkOwners(previous?.owner);
  const homes = [];

  const addTerritory = (territoryId, territory, ownerName, originalStyle) => {
    const occupier = occupiedByTid.get(territoryId) || null;
    const controller = occupier || ownerName;
    const style = townStyle.get(controller) || originalStyle;
    territoryStyle.set(territoryId, style);
    const arr = territory.chunks || [];
    const record = (townName, chunkOccupier = null) => ({
      townName, ownerName, occupier, chunkOccupier, territoryId,
      style: townStyle.get(townName) || originalStyle,
    });
    // Only split records when individual chunks have a different controller.
    // Unaffected territories retain their original coordinate arrays and caches.
    let split = false;
    for (let i = 0; i < arr.length && !split; i += 2) {
      const captured = occupiedChunks.get(packCoord(arr[i], arr[i + 1]));
      split = captured != null && captured !== controller;
    }
    if (!split) owner.addChunks(arr, record(controller));
    else {
      const groups = new Map();
      for (let i = 0; i < arr.length; i += 2) {
        const captured = occupiedChunks.get(packCoord(arr[i], arr[i + 1]));
        const name = captured || controller;
        if (!groups.has(name)) groups.set(name, []);
        groups.get(name).push(arr[i], arr[i + 1]);
      }
      for (const [name, chunks] of groups) {
        owner.addChunks(chunks, record(name, name !== controller ? name : null));
      }
    }
    if (territory.core) {
      const hx = Math.floor(territory.core[0] / BLOCKS_PER_CHUNK);
      const hz = Math.floor(territory.core[1] / BLOCKS_PER_CHUNK);
      homes.push({ cx: hx, cz: hz });
    }
  };

  for (const [name, town] of Object.entries(towns.towns || {})) {
    for (const rawTid of town.territories || []) {
      const tid = +rawTid;
      const territory = world.territories && world.territories[tid];
      if (territory) addTerritory(tid, territory, name, townStyle.get(name));
    }
  }
  const unclaimedStyle = { name: null, nation: null, color: UNCLAIMED_TERRITORY_COLOR };
  for (const [rawTid, territory] of Object.entries(world.territories || {})) {
    const tid = +rawTid;
    if (!territoryStyle.has(tid)) addTerritory(tid, territory, null, unclaimedStyle);
  }

  owner.finish();
  const { minCx, maxCx, minCz, maxCz } = owner;
  const index = { townStyle, territoryStyle, owner, homes, minCx, maxCx, minCz, maxCz };
  indexWorlds.set(index, world);
  return index;
}

// Give separate landmasses their own labels, including overseas holdings.
// Tiny fragments are omitted to avoid repeating names over every coastal islet.
function buildNationLabels(index) {
  const { cellList: cells, records, sources, size } = index.owner;
  const world = indexWorlds.get(index);
  const cached = nationLabelCache.get(world);
  // Node refreshes replace town/war data, while world geometry is immutable.
  // Colors do not move labels; occupation changes controller membership. Compare source
  // order so ties and overlapping claims keep the same placement rules.
  const sourceOrder = index.owner.sourceOrder;
  if (cached && sourceOrder.every((id, i) => id === cached.order[i])
      && sources.length === cached.sources.length
      && sources.every((source, i) => source === cached.sources[i]
        && records[i]?.style.nation === cached.nations[i])) return cached.labels;
  const nationIds = new Uint32Array(records.length);
  const nations = new Map();
  for (let i = 1; i < records.length; i++) {
    const nation = records[i].style.nation;
    if (!nation) continue;
    if (!nations.has(nation)) nations.set(nation, nations.size + 1);
    nationIds[i] = nations.get(nation);
  }
  if (!nations.size) return [];
  const visited = new Uint8Array(cells.length * CELL_AREA);
  const queue = new Uint32Array(size);
  const regions = new Map();
  let end = 0;
  // Preserve original chunk insertion order for equally sized islands: the
  // first landmass always receives a label, even below the minimum area.
  for (const recordId of sourceOrder) {
    if (!nationIds[recordId]) continue;
    const coordinates = sources[recordId];
    let previousCellKey, cell;
    for (let i = 0; i < coordinates.length; i += 2) {
      const cx = coordinates[i], cz = coordinates[i + 1];
      const cellKey = packCoord(Math.floor(cx / CELL_SIZE), Math.floor(cz / CELL_SIZE));
      if (cellKey !== previousCellKey) {
        previousCellKey = cellKey;
        cell = index.owner.cells.get(cellKey);
      }
      const offset = (cz - cell.z) * CELL_SIZE + cx - cell.x;
      if (cell.ids[offset] !== recordId) continue;
      const seed = cell.index * CELL_AREA + offset;
      const nationId = nationIds[cell.ids[offset]];
      if (!nationId || visited[seed]) continue;
      const start = end;
      queue[end++] = seed;
      visited[seed] = 1;
      let sumX = 0, sumZ = 0;
      const visit = (neighborCell, neighborOffset) => {
        if (!neighborCell) return;
        const key = neighborCell.index * CELL_AREA + neighborOffset;
        if (visited[key] || nationIds[neighborCell.ids[neighborOffset]] !== nationId) return;
        visited[key] = 1;
        queue[end++] = key;
      };
      for (let head = start; head < end; head++) {
        const key = queue[head];
        const current = cells[Math.floor(key / CELL_AREA)];
        const local = key % CELL_AREA, x = local % CELL_SIZE, z = Math.floor(local / CELL_SIZE);
        sumX += current.x + x;
        sumZ += current.z + z;
        visit(x ? current : current.left, x ? local - 1 : local + CELL_SIZE - 1);
        visit(x < CELL_SIZE - 1 ? current : current.right,
          x < CELL_SIZE - 1 ? local + 1 : local - CELL_SIZE + 1);
        visit(z ? current : current.top, z ? local - CELL_SIZE : local + CELL_AREA - CELL_SIZE);
        visit(z < CELL_SIZE - 1 ? current : current.bottom,
          z < CELL_SIZE - 1 ? local + CELL_SIZE : local - CELL_AREA + CELL_SIZE);
      }
      const nation = records[cell.ids[offset]].style.nation;
      if (!regions.has(nation)) regions.set(nation, []);
      const count = end - start;
      regions.get(nation).push({ text: nation.replace(/_/g, ' '),
        chunks: queue.subarray(start, end), cells,
        centerX: sumX / count, centerZ: sumZ / count, count });
    }
  }
  const ctx = (typeof document === 'undefined' ? new OffscreenCanvas(1, 1)
    : document.createElement('canvas')).getContext('2d');
  ctx.font = `600 100px "${NATION_FONT}"`;
  const segmenter = new Intl.Segmenter(undefined, { granularity: 'grapheme' });
  const labels = [...regions.values()].flatMap((landmasses) => {
    landmasses.sort((a, b) => b.count - a.count);
    const minimumArea = Math.max(256, landmasses[0].count * 0.04);
    return landmasses.filter((region, i) => i === 0 || region.count >= minimumArea)
      .flatMap((region) => curveNationLabel(region, ctx, segmenter));
  });
  if (world) nationLabelCache.set(world, { sources: sources.slice(), order: sourceOrder.slice(),
    nations: records.map((record) => record?.style.nation), labels });
  return labels;
}

function curveNationLabel({ text, chunks, cells, centerX, centerZ }, ctx, segmenter) {
  let xx = 0, xz = 0, zz = 0;
  for (const key of chunks) {
    const cell = cells[Math.floor(key / CELL_AREA)];
    const local = key % CELL_AREA;
    const x = cell.x + local % CELL_SIZE - centerX;
    const z = cell.z + Math.floor(local / CELL_SIZE) - centerZ;
    xx += x * x; xz += x * z; zz += z * z;
  }
  // Compact nations read best horizontally; elongated nations follow their
  // principal axis. Keep the direction left-to-right so names stay upright.
  const anisotropy = Math.hypot(xx - zz, 2 * xz) / (xx + zz || 1);
  const angle = anisotropy > 0.25 ? Math.atan2(2 * xz, xx - zz) / 2 : 0;
  const dx = Math.cos(angle), dz = Math.sin(angle);
  const projected = new Float64Array(chunks.length * 2);
  let extent = 1;
  for (let i = 0; i < chunks.length; i++) {
    const key = chunks[i], cell = cells[Math.floor(key / CELL_AREA)];
    const local = key % CELL_AREA;
    const x = cell.x + local % CELL_SIZE - centerX;
    const z = cell.z + Math.floor(local / CELL_SIZE) - centerZ;
    const u = x * dx + z * dz;
    projected[i * 2] = u;
    projected[i * 2 + 1] = -x * dz + z * dx;
    extent = Math.max(extent, Math.abs(u));
  }
  let m2 = 0, m3 = 0, m4 = 0, uv = 0, u2v = 0;
  let lo = Infinity, hi = -Infinity;
  for (let i = 0; i < projected.length; i += 2) {
    const u = projected[i] / extent, v = projected[i + 1] / extent;
    const u2 = u * u;
    m2 += u2; m3 += u2 * u; m4 += u2 * u2;
    uv += u * v; u2v += u2 * v;
    lo = Math.min(lo, u); hi = Math.max(hi, u);
  }
  const n = chunks.length;
  m2 /= n; m3 /= n; m4 /= n; uv /= n; u2v /= n;
  // Least-squares quadratic across the landmass, with bounded curvature to
  // prevent a remote peninsula from making the text curl back on itself.
  const determinant = (m4 - m2 * m2) * m2 - m3 * m3;
  const a = determinant > 1e-9
    ? Math.max(-0.35, Math.min(0.35, (u2v * m2 - uv * m3) / determinant)) : 0;
  const b = m2 > 1e-9 ? (uv - a * m3) / m2 : 0;
  const c = -a * m2;
  const pointAt = (u) => {
    const v = a * u * u + b * u + c;
    return [(centerX + extent * (u * dx - v * dz) + 0.5) * BLOCKS_PER_CHUNK,
      (centerZ + extent * (u * dz + v * dx) + 0.5) * BLOCKS_PER_CHUNK];
  };
  // Arc-length sampling keeps letter spacing even around bends. Inset both
  // ends so the name occupies the central 75% of the nation's length.
  const inset = (hi - lo) * 0.125;
  const path = [];
  let length = 0;
  for (let i = 0; i <= 80; i++) {
    const u = lo + inset + (hi - lo - 2 * inset) * i / 80;
    const position = pointAt(u);
    if (i) length += Math.hypot(position[0] - path[i - 1].position[0],
      position[1] - path[i - 1].position[1]);
    path.push({ position, length, u });
  }
  // Keep combining accents attached to their letter when spacing glyphs.
  const letters = [...segmenter.segment(text)]
    .map(({ segment }) => segment);
  // Letter spacing and restrained height give the broad, quiet
  // country lettering of a political map, even on a long narrow coastline.
  const tracking = 12; // measured in the same 100px font as the advances
  const advances = letters.map((letter) => ctx.measureText(letter).width);
  const width = advances.reduce((sum, advance) => sum + advance, 0)
    + tracking * Math.max(0, letters.length - 1);
  let residual = 0;
  for (let i = 0; i < projected.length; i += 2) {
    const u = projected[i], v = projected[i + 1];
    const normalized = u / extent;
    const baseline = extent * (a * normalized * normalized + b * u / extent + c);
    const difference = v - baseline;
    residual += difference * difference;
  }
  const thickness = Math.max(1, Math.sqrt(residual / n)) * BLOCKS_PER_CHUNK;
  const size = Math.min(length / ((width || 1) / 100), thickness * 0.85);
  const textLength = width / 100 * size;
  const margin = (length - textLength) / 2;
  let advance = 0, sample = 1;
  return letters.map((letter, i) => {
    const distance = margin + (advance + advances[i] / 2) / 100 * size;
    advance += advances[i] + tracking;
    while (sample < path.length - 1 && path[sample].length < distance) sample++;
    const start = path[sample - 1], end = path[sample];
    const t = (distance - start.length) / (end.length - start.length || 1);
    const u = start.u + (end.u - start.u) * t;
    const slope = 2 * a * u + b;
    return { text: letter, size, position: pointAt(u),
      angle: -Math.atan2(dz + slope * dx, dx - slope * dz) * 180 / Math.PI };
  });
}

// Pre-rasterise the entire territory layer to a single image at one pixel per
// chunk; rendered as a BitmapLayer with nearest-neighbour filtering so
// upscaling stays crisp. The temporary ImageData uses four bytes per pixel
// within the occupied world's bounding rectangle.
// Opacity is encoded in blue; attack holes are applied by the shader mask.
function buildWorldImage(index, opacityAt) {
  // No territory chunks (e.g. world data absent) → minCx/minCz are still
  // Infinity, which would size the canvas from garbage bounds. Nothing to
  // raster, so return null; territoryRasterLayer() skips a null image.
  if (!index.owner.size) return null;
  const w = index.maxCx - index.minCx + 1;
  const h = index.maxCz - index.minCz + 1;
  const { buf, commit } = chunkCanvas(w, h);
  const pixels = new Uint32Array(buf.buffer, buf.byteOffset, buf.length / 4);
  const { records, cellList } = index.owner;
  // Build via clamped bytes to preserve ImageData's rounding and endianness.
  const colors = new Uint8ClampedArray(records.length * 4);
  for (let i = 1; i < records.length; i++) {
    const c = records[i].style.color;
    colors[i * 4] = c[0]; colors[i * 4 + 1] = c[1];
    colors[i * 4 + 2] = c[2]; colors[i * 4 + 3] = 255;
  }
  const palette = new Uint32Array(colors.buffer);
  for (const cell of cellList) {
    for (let z = 0; z < CELL_SIZE; z++) {
      const cz = cell.z + z;
      if (cz < index.minCz || cz > index.maxCz) continue;
      const target = (cz - index.minCz) * w + cell.x - index.minCx;
      const row = z * CELL_SIZE;
      for (let x = 0; x < CELL_SIZE; x++) {
        const id = cell.ids[row + x];
        if (id) {
          pixels[target + x] = palette[id];
          buf[(target + x) * 4 + 2] = Math.round(255 * opacityAt(cell.x + x, cz));
        }
      }
    }
  }
  return commit();
}

// Each chunk → up to 4 path segments along edges that border a different
// territory (or space outside any configured territory). Coords are in
// blocks. 2 px wide so they read over the 1 px chunk grid.
function buildOutlineSegments(index, bounds) {
  const segments = [];
  const { records, cellList } = index.owner;
  const left = bounds[0] / BLOCKS_PER_CHUNK, right = bounds[2] / BLOCKS_PER_CHUNK;
  // Some source territories continue beyond the canonical date line. Keep
  // their actual adjacent chunks together before consulting the other side.
  const neighborId = (cx, cz) => index.owner.getId(cx, cz)
    || index.owner.getId(wrapX(cx, left, right), cz);
  const emit = (owner, neighborId, cx, cz, x0, z0, x1, z1) => {
    const neighbor = records[neighborId];
    if (neighbor && owner.territoryId >= neighbor.territoryId) return;
    segments.push({ x0, z0, x1, z1, color: owner.style.color,
      nation: owner.style.nation, cx, cz });
  };
  for (const cell of cellList) {
    const { ids } = cell;
    for (let local = 0; local < CELL_AREA; local++) {
      const id = ids[local];
      if (!id) continue;
      const owner = records[id];
      const x = local % CELL_SIZE, z = Math.floor(local / CELL_SIZE);
      const cx = cell.x + x, cz = cell.z + z;
      const baseX = cx * BLOCKS_PER_CHUNK, baseZ = cz * BLOCKS_PER_CHUNK;
      const interior = cx > left && cx < right - 1;
      // Interior neighbors are adjacent integers in the cell arrays. Only
      // seam and out-of-world coordinates need wrapped fallback lookups.
      const top = interior ? z ? ids[local - CELL_SIZE]
        : cell.top?.ids[local + CELL_AREA - CELL_SIZE]
        : neighborId(cx, cz - 1);
      const bottom = interior ? z < CELL_SIZE - 1 ? ids[local + CELL_SIZE]
        : cell.bottom?.ids[local - CELL_AREA + CELL_SIZE]
        : neighborId(cx, cz + 1);
      const west = interior ? x ? ids[local - 1] : cell.left?.ids[local + CELL_SIZE - 1]
        : neighborId(cx - 1, cz);
      const east = interior ? x < CELL_SIZE - 1 ? ids[local + 1] : cell.right?.ids[local - CELL_SIZE + 1]
        : neighborId(cx + 1, cz);
      if (top !== id) emit(owner, top, cx, cz, baseX, baseZ, baseX + BLOCKS_PER_CHUNK, baseZ);
      if (bottom !== id) emit(owner, bottom, cx, cz,
        baseX, baseZ + BLOCKS_PER_CHUNK, baseX + BLOCKS_PER_CHUNK, baseZ + BLOCKS_PER_CHUNK);
      if (west !== id) emit(owner, west, cx, cz, baseX, baseZ, baseX, baseZ + BLOCKS_PER_CHUNK);
      if (east !== id) emit(owner, east, cx, cz,
        baseX + BLOCKS_PER_CHUNK, baseZ, baseX + BLOCKS_PER_CHUNK, baseZ + BLOCKS_PER_CHUNK);
    }
  }
  return segments;
}

export { buildIndex, buildNationLabels, buildOutlineSegments, buildWorldImage, chunkCanvas, packCoord };

// Structured cloning strips prototypes; restore the query API without rebuilding cells.
export function hydrateIndex(index) {
  Object.setPrototypeOf(index.owner, ChunkOwners.prototype);
  return index;
}
