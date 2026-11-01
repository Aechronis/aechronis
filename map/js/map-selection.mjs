import { packCoord } from './data-index.mjs';
import { MAP_BOUNDS, WORLD_WIDTH } from './map-config.mjs';
import { wrapX } from './world-wrap.mjs';

// Cache derived data by its actual inputs, independently of layer instances.
export function memoize(fn) {
  let previous, result;
  return (...inputs) => {
    if (!previous || inputs.length !== previous.length || inputs.some((value, i) => value !== previous[i])) {
      result = fn(...inputs);
      previous = inputs;
    }
    return result;
  };
}

export function resolveSelection(coordinate, index, buildings) {
  if (!coordinate) return { hit: null, chunk: null, building: null };
  const chunk = coordinate.slice(0, 2).map(value => Math.floor(value / 16));
  const key = packCoord(...chunk);
  const hit = index.owner.get(key) || null;
  return { hit, chunk: hit ? chunk : null, building: buildings.get(key) || null };
}

// World copies draw in ascending order. Query the last painted copy first,
// retaining source coordinates even when a territory extends past the date line.
export function sourceCoordinate(coordinate, index, buildings, copies) {
  if (!coordinate) return null;
  const cz = Math.floor(coordinate[1] / 16);
  for (let i = copies.length - 1; i >= 0; i--) {
    const x = coordinate[0] - copies[i] * WORLD_WIDTH;
    const cx = Math.floor(x / 16);
    if (index.owner.getId(cx, cz) || buildings.has(packCoord(cx, cz))) {
      return [x, ...coordinate.slice(1)];
    }
  }
  return [wrapX(coordinate[0], MAP_BOUNDS[0], MAP_BOUNDS[2]), ...coordinate.slice(1)];
}

export const buildingIconUrl = type => `img/buildings/${encodeURIComponent(type || 'unknown')}.png`;
