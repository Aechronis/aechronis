import { packCoord } from './data-index.mjs';
import { wrapX } from './world-wrap.mjs';

const CELL_SIZE = 32;
const CELL_AREA = CELL_SIZE * CELL_SIZE;

export function buildNationOpacity(index, bounds, interiorOpacity, boundary = 'nation') {
  // Town borders fade inward over six chunks; border chunks stay opaque.
  const fadeChunks = boundary === 'town' ? 6 : 12;
  const { owner } = index;
  const { cellList, records } = owner;
  const distances = new Uint8Array(cellList.length * CELL_AREA).fill(fadeChunks);
  const queue = new Uint32Array(owner.size);
  let head = 0, tail = 0;
  const left = bounds[0] / 16, right = bounds[2] / 16;
  // Combine every territory belonging to a town, keeping internal seams out
  // of its outline. Unclaimed land has no town boundary.
  const nations = records.map(record => (boundary === 'town'
    ? record?.townName : record?.style.nation) || null);
  const slotAt = (cx, cz) => {
    const cell = owner.cells.get(packCoord(Math.floor(cx / CELL_SIZE), Math.floor(cz / CELL_SIZE)));
    return cell ? cell.index * CELL_AREA + (cz - cell.z) * CELL_SIZE + cx - cell.x : -1;
  };
  const nationAt = slot => slot < 0 ? null
    : nations[cellList[Math.floor(slot / CELL_AREA)].ids[slot % CELL_AREA]];
  // Match outline wrapping: actual source neighbors take precedence at the seam.
  const neighborAt = (cx, cz) => {
    const slot = slotAt(cx, cz);
    if (slot >= 0 && cellList[Math.floor(slot / CELL_AREA)].ids[slot % CELL_AREA]) return slot;
    return slotAt(wrapX(cx, left, right), cz);
  };
  const neighbors = new Int32Array(4);
  function readNeighbors(cell, offset) {
    const x = offset % CELL_SIZE, z = Math.floor(offset / CELL_SIZE);
    const cx = cell.x + x, cz = cell.z + z;
    const slot = cell.index * CELL_AREA + offset;
    neighbors[0] = x && cx > left && cx < right ? slot - 1 : neighborAt(cx - 1, cz);
    neighbors[1] = x < CELL_SIZE - 1 && cx >= left && cx < right - 1 ? slot + 1 : neighborAt(cx + 1, cz);
    neighbors[2] = z ? slot - CELL_SIZE : neighborAt(cx, cz - 1);
    neighbors[3] = z < CELL_SIZE - 1 ? slot + CELL_SIZE : neighborAt(cx, cz + 1);
  }
  for (const cell of cellList) {
    for (let offset = 0; offset < CELL_AREA; offset++) {
      const nation = nations[cell.ids[offset]];
      if (!nation) continue;
      readNeighbors(cell, offset);
      if (neighbors.some(slot => nationAt(slot) !== nation)) {
        const slot = cell.index * CELL_AREA + offset;
        distances[slot] = 0;
        queue[tail++] = slot;
      }
    }
  }
  // Multi-source flood stays within the chosen nation or town boundary.
  while (head < tail) {
    const slot = queue[head++], next = distances[slot] + 1;
    if (next >= fadeChunks) continue;
    const cell = cellList[Math.floor(slot / CELL_AREA)], offset = slot % CELL_AREA;
    const nation = nations[cell.ids[offset]];
    readNeighbors(cell, offset);
    for (const neighbor of neighbors) {
      if (neighbor >= 0 && distances[neighbor] > next && nationAt(neighbor) === nation) {
        distances[neighbor] = next;
        queue[tail++] = neighbor;
      }
    }
  }
  const alpha = Array.from({ length: fadeChunks + 1 }, (_, distance) => {
    const t = distance / fadeChunks;
    return Math.round(255 * (1 + (interiorOpacity - 1) * t * t * (3 - 2 * t)));
  });
  return hydrateNationOpacity(index, { distances, alpha, fadeChunks });
}

export function hydrateNationOpacity(index, state) {
  const { distances, alpha, fadeChunks } = state;
  const opacity = (cx, cz) => {
    const cell = index.owner.cells.get(packCoord(Math.floor(cx / CELL_SIZE), Math.floor(cz / CELL_SIZE)));
    const slot = cell ? cell.index * CELL_AREA + (cz - cell.z) * CELL_SIZE + cx - cell.x : -1;
    return alpha[slot < 0 ? fadeChunks : distances[slot]] / 255;
  };
  opacity.state = state;
  return opacity;
}
