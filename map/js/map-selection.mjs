import { packCoord } from './data-index.mjs';

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

export const buildingIconUrl = type => `img/buildings/${encodeURIComponent(type || 'unknown')}.png`;
