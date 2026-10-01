import { buildIndex, buildNationLabels, buildOutlineSegments, buildWorldImage, packCoord } from './data-index.mjs';
import { buildNationOpacity, hydrateNationOpacity } from './nation-opacity.mjs';
import { mergeLineSegments, buildGrid } from './overlay-geometry.mjs';
import { MAP_BOUNDS } from './map-config.mjs';

const interior = 0.1 ** (1 / 2.2);
const sameMembership = (a, b, boundary) => a && a.owner.cells === b.owner.cells
  && b.owner.records.every((r, i) => boundary === 'town'
    ? r?.townName === a.owner.records[i]?.townName
    : r?.style.nation === a.owner.records[i]?.style.nation);

// Build spatial buckets when the territory geometry changes.
export function prepareBuckets(index, outlines) {
  const buckets = new Map();
  const at = (cx, cz) => {
    const x = Math.floor(cx / 128), z = Math.floor(cz / 128), key = packCoord(x, z);
    if (!buckets.has(key)) buckets.set(key, { id: `${x}-${z}`, x, z,
      left: x * 2048, top: z * 2048, right: (x + 1) * 2048, bottom: (z + 1) * 2048,
      cells: [], outlines: [] });
    return buckets.get(key);
  };
  for (const cell of index.owner.cellList) at(cell.x, cell.z).cells.push(cell);
  for (const edge of outlines) {
    const bucket = at(edge.cx, edge.cz);
    bucket.outlines.push(edge);
  }
  for (const bucket of buckets.values()) {
    bucket.mergedOutlines = mergeLineSegments(bucket.outlines);
    bucket.grid = buildGrid(index.owner, bucket.cells);
  }
  return buckets;
}

export class MapPreparation {
  prepare(data) {
    const oldIndex = this.index;
    const index = buildIndex(data, oldIndex);
    const labels = buildNationLabels(index);
    const opacity = {};
    for (const boundary of ['nation', 'town']) {
      opacity[boundary] = sameMembership(oldIndex, index, boundary)
        ? hydrateNationOpacity(index, this.opacity[boundary].state)
        : buildNationOpacity(index, MAP_BOUNDS, interior, boundary);
    }
    // Borders and grid lines use fixed colours. Reuse their geometry while
    // chunk-to-territory IDs stay stable, even when ownership or colours change.
    const buckets = oldIndex?.owner.cells === index.owner.cells
      ? this.buckets : prepareBuckets(index, buildOutlineSegments(index, MAP_BOUNDS));
    this.index = index; this.opacity = opacity; this.buckets = buckets;
    return { index, labels, opacity, buckets };
  }
}

// A stable territory-record ID texture separates geometry from presentation.
// Color/diplomacy changes now upload a small palette, not every world pixel.
export function buildPreparedRasters(index, opacity) {
  const names = [null], colors = [[0, 0, 0, 0]];
  const records = index.owner.records.map((record, id) => {
    if (!record) return record;
    if (id > 65535) throw new Error('Too many territory records for the palette');
    names.push(record.style.nation || null);
    colors.push([...record.style.color, 255]);
    return { ...record, style: { color: [id & 255, id >> 8, 0] } };
  });
  const encoded = { ...index, owner: { ...index.owner, records } };
  return { names, colors, images: Object.fromEntries(Object.entries(opacity).map(([boundary, value]) =>
    [boundary, buildWorldImage(encoded, value)])) };
}
