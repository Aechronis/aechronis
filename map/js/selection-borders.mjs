import { LineLayer } from '@deck.gl/layers';
import { packCoord } from './data-index.mjs';
import { MAP_BOUNDS, WORLD_WIDTH, TERRITORY_BORDER_STYLE } from './map-config.mjs';
import { mergeLineSegments, NationGlowLayer } from './spatial-overlays.mjs';

export function chunkBoundary(chunks) {
  const minX = MAP_BOUNDS[0] / 16;
  const width = WORLD_WIDTH / 16;
  const key = (x, z) => packCoord(minX + ((x - minX) % width + width) % width, z);
  const occupied = new Set();
  for (let i = 0; i < chunks.length; i += 2) occupied.add(key(chunks[i], chunks[i + 1]));
  const edges = [];
  for (let i = 0; i < chunks.length; i += 2) {
    const x = chunks[i], z = chunks[i + 1];
    const add = (x0, z0, x1, z1) => edges.push({ x0: x0 * 16, z0: z0 * 16,
      x1: x1 * 16, z1: z1 * 16 });
    if (!occupied.has(key(x - 1, z))) add(x, z, x, z + 1);
    if (!occupied.has(key(x + 1, z))) add(x + 1, z, x + 1, z + 1);
    if (!occupied.has(key(x, z - 1))) add(x, z, x + 1, z);
    if (!occupied.has(key(x, z + 1))) add(x, z + 1, x + 1, z + 1);
  }
  return mergeLineSegments(edges);
}

export function selectionBorderLayers(town, territory) {
  // World-space widths shrink with the map; match the previous stroke sizes
  // at zoom -2, where territory details become visible.
  const common = { getSourcePosition: d => [d.x0, d.z0],
    getTargetPosition: d => [d.x1, d.z1], widthUnits: 'common', widthScale: 4,
    pickable: false };
  return [
    new LineLayer({ ...common, id: 'selected-territory-border', data: territory,
      ...TERRITORY_BORDER_STYLE }),
    new NationGlowLayer({ ...common, id: 'selected-town-glow', data: town,
      getColor: [255, 255, 255, 255], getWidth: 20 }),
    new LineLayer({ ...common, id: 'selected-town-border', data: town,
      getColor: [255, 255, 255, 255], getWidth: 4 }),
  ];
}
