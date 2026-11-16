import { LineLayer } from '@deck.gl/layers';
import { chunkBoundary } from './selection-borders.mjs';

const WARZONE_COLOR = [180, 20, 20, 255];

// Start/end timestamps come from the server; a finished zone leaves warzone.json.
export function activeWarzones(warzone, nowMs = Date.now()) {
  return Object.entries(warzone?.zones || {})
    .filter(([, zone]) => nowMs >= zone?.start && nowMs < zone?.end)
    .map(([territoryId]) => territoryId);
}

// Zones that touch share one outline: the boundary of all their chunks together.
export function warzoneBorders(territoryIds, world) {
  const chunks = [];
  for (const id of territoryIds) {
    for (const coord of world?.territories?.[id]?.chunks || []) chunks.push(coord);
  }
  return chunkBoundary(chunks);
}

export function warzoneRingLayers(borders) {
  if (!borders.length) return [];
  // World-space widths shrink with the map, so the pixel floor keeps a zone
  // findable from the overview.
  return [new LineLayer({ id: 'warzone-ring', data: borders,
    getSourcePosition: d => [d.x0, d.z0], getTargetPosition: d => [d.x1, d.z1],
    getColor: WARZONE_COLOR, widthUnits: 'common', widthScale: 4, getWidth: 2,
    widthMinPixels: 2, pickable: false })];
}
