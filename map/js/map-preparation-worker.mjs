import { MapPreparation, buildPreparedRasters } from './map-preparation.mjs';
import { fetchJsonUpdate } from './data-refresh.mjs';
import fontUrl from '../css/fonts/CormorantGaramond.woff2?url';

const preparation = new MapPreparation();
let data;
let initialWorld;
let initialFetch = true;
let lastIndex, lastOpacity, lastBuckets;
const fontsReady = (async () => {
  try {
    const font = new FontFace('Cormorant Garamond', `url(${fontUrl})`, { weight: '600' });
    self.fonts.add(await font.load());
  } catch (error) { console.warn('Map worker font:', error); }
})();
const isRecord = value => value !== null && typeof value === 'object' && !Array.isArray(value);
function packLines(lines, transfer) {
  const source = new Float32Array(lines.length * 2), target = new Float32Array(lines.length * 2);
  lines.forEach((line, i) => {
    source[i * 2] = line.x0; source[i * 2 + 1] = line.z0;
    target[i * 2] = line.x1; target[i * 2 + 1] = line.z1;
  });
  transfer.push(source.buffer, target.buffer);
  return { length: lines.length, attributes: {
    getSourcePosition: { value: source, size: 2 }, getTargetPosition: { value: target, size: 2 },
  } };
}
function collectBuffers(value, transfer, seen = new Set()) {
  if (!value || typeof value !== 'object' || seen.has(value)) return;
  seen.add(value);
  if (ArrayBuffer.isView(value)) { if (!seen.has(value.buffer)) { seen.add(value.buffer); transfer.push(value.buffer); } }
  else if (value instanceof Map) for (const item of value.values()) collectBuffers(item, transfer, seen);
  else for (const item of Object.values(value)) collectBuffers(item, transfer, seen);
}
async function prepare(nextData, initial) {
  await fontsReady;
  const prepared = preparation.prepare(nextData);
  const stable = prepared.index.owner.cells === lastIndex?.owner.cells;
  const dirtyOpacity = Object.fromEntries(Object.entries(prepared.opacity).filter(([boundary, value]) =>
    !stable || value.state !== lastOpacity?.[boundary].state));
  const rasters = buildPreparedRasters(prepared.index, dirtyOpacity);
  const transfer = [];
  const buckets = new Map([...prepared.buckets].flatMap(([key, bucket]) => {
    const old = stable && lastBuckets?.get(key);
    if (old && old.grid === bucket.grid && old.mergedOutlines === bucket.mergedOutlines) return [];
    return [[key, { id: bucket.id, x: bucket.x, z: bucket.z,
      left: bucket.left, top: bucket.top, right: bucket.right, bottom: bucket.bottom,
      packedOutlines: packLines(bucket.mergedOutlines, transfer), packedGrid: packLines(bucket.grid, transfer),
    }]];
  }));
  const owner = { ...prepared.index.owner };
  // Source coordinates belong to the worker's index; UI lookups need only cells.
  delete owner.sources;
  if (stable) { delete owner.cells; delete owner.cellList; }
  const wire = structuredClone({ index: { ...prepared.index, owner },
    opacity: Object.fromEntries(Object.entries(dirtyOpacity).map(([key, value]) => [key, value.state])),
    initial });
  collectBuffers(wire, transfer);
  lastIndex = prepared.index; lastOpacity = prepared.opacity; lastBuckets = prepared.buckets;
  for (const [boundary, image] of Object.entries(rasters.images)) {
    if (image) {
      rasters.images[boundary] = image.transferToImageBitmap();
      transfer.push(rasters.images[boundary]);
    }
  }
  return { result: { ...wire, labels: prepared.labels, buckets, stable, rasters }, transfer };
}
// Serialize mutations even if refresh messages arrive while font/fetch work awaits.
let queue = Promise.resolve();
self.onmessage = ({ data: { id, method, args } }) => {
  queue = queue.then(async () => {
    let preparingInitial = false;
    try {
      let initial;
      let nextData;
      if (method === 'initial') {
        // Keep the large static world download if another startup feed fails.
        // Mutable feeds are fetched again so retries use current ownership.
        const files = ['towns', 'world', 'war', 'buildings'].filter(name => name !== 'world' || !initialWorld);
        const updates = await Promise.all(files.map(name =>
          fetchJsonUpdate(new URL(`nodes/${name}.json`, args.base).href, null, {
            initial: initialFetch, optional: name === 'war' || name === 'buildings', timeoutMs: 30_000,
          })));
        initialFetch = false;
        nextData = { world: initialWorld, ...Object.fromEntries(files.map((name, i) => [name, updates[i]?.body ?? null])) };
        if (!isRecord(nextData.world?.territories)) nextData.world = null;
        if (!['towns', 'nations', 'residents'].every(key => isRecord(nextData.towns?.[key]))) nextData.towns = null;
        if (!initialWorld && nextData.world) {
          for (const territory of Object.values(nextData.world.territories || {})) {
            territory.chunks = new Int32Array(territory.chunks || []);
          }
          initialWorld = nextData.world;
        }
        const missing = ['world', 'towns'].filter(name => !nextData[name]);
        if (missing.length) throw new Error(`Map data unavailable: ${missing.join(', ')}`);
        initial = { data: nextData, meta: Object.fromEntries(files.filter(name => name !== 'world')
          .map(name => [name, updates[files.indexOf(name)]?.meta ?? null])) };
      } else if (method === 'prepare') nextData = { ...data, ...args };
      else throw new Error(`Unknown preparation request: ${method}`);
      if (initial) {
        preparingInitial = true;
        // Startup retries also recover failed UI installs, so send a full scene.
        lastIndex = lastOpacity = lastBuckets = null;
      }
      const { result, transfer } = await prepare(nextData, initial);
      self.postMessage({ id, result }, transfer);
      data = nextData;
    } catch (error) {
      // A malformed world can pass the top-level shape check but fail geometry
      // preparation. Fetch its corrected replacement on the next startup retry.
      if (preparingInitial) initialWorld = null;
      // A failed handoff cannot become the baseline for the next delta.
      lastIndex = lastOpacity = lastBuckets = null;
      self.postMessage({ id, error: error.message });
    }
  });
};
