const BLOCKS_PER_CHUNK = 16;
const OWNER_CELL_CHUNKS = 32;
const horizontalStart = (a, b) => Math.min(a.x0, a.x1) - Math.min(b.x0, b.x1);
const verticalStart = (a, b) => Math.min(a.z0, a.z1) - Math.min(b.z0, b.z1);

// Adjacent chunk edges with the same appearance are one straight stroke.
export function mergeLineSegments(segments) {
  const appearances = new Map(), byColor = new Map(), runs = [];
  for (const segment of segments) {
    const horizontal = segment.z0 === segment.z1;
    const fixed = horizontal ? segment.z0 : segment.x0;
    // Ownership records share colors and nation names across thousands
    // of edges. Intern their values once, then use numeric row coordinates;
    // serializing the complete key for every edge stalls cold tile entry.
    let byOwner = byColor.get(segment.color);
    if (!byOwner) { byOwner = new Map(); byColor.set(segment.color, byOwner); }
    let appearance = byOwner.get(segment.nation);
    if (!appearance) {
      const key = JSON.stringify([segment.color, segment.nation]);
      appearance = appearances.get(key);
      if (!appearance) {
        appearance = { horizontal: new Map(), vertical: new Map() };
        appearances.set(key, appearance);
      }
      byOwner.set(segment.nation, appearance);
    }
    const rows = horizontal ? appearance.horizontal : appearance.vertical;
    let row = rows.get(fixed);
    if (!row) {
      row = { horizontal, fixed, color: segment.color, nation: segment.nation, spans: [] };
      rows.set(fixed, row);
      // Keep first-seen row order even when appearances are interleaved.
      runs.push(row);
    }
    row.spans.push(segment);
  }
  const result = [];
  for (const { horizontal, fixed, color, nation, spans } of runs) {
    // Sort references rather than allocating a two-element span per edge.
    spans.sort(horizontal ? horizontalStart : verticalStart);
    let start = horizontal ? Math.min(spans[0].x0, spans[0].x1) : Math.min(spans[0].z0, spans[0].z1);
    let end = horizontal ? Math.max(spans[0].x0, spans[0].x1) : Math.max(spans[0].z0, spans[0].z1);
    const emit = () => result.push(horizontal
      ? { x0: start, z0: fixed, x1: end, z1: fixed, color, ...(nation !== undefined ? { nation } : {}) }
      : { x0: fixed, z0: start, x1: fixed, z1: end, color, ...(nation !== undefined ? { nation } : {}) });
    for (let i = 1; i < spans.length; i++) {
      const span = spans[i];
      const nextStart = horizontal ? Math.min(span.x0, span.x1) : Math.min(span.z0, span.z1);
      const nextEnd = horizontal ? Math.max(span.x0, span.x1) : Math.max(span.z0, span.z1);
      if (nextStart <= end) end = Math.max(end, nextEnd);
      else { emit(); start = nextStart; end = nextEnd; }
    }
    emit();
  }
  return result;
}

function sameColor(a, b) {
  return a === b || (a && b && a[0] === b[0] && a[1] === b[1] && a[2] === b[2]);
}

// Read compact ownership cells directly. Avoid materializing ownership
// objects and accessor arrays for every chunk each time the camera pans.
export function buildGrid(owner, cells) {
  const segments = [];
  for (const cell of cells) {
    const styleAt = (offset) => owner.records[cell.ids[offset]]?.style;
    for (let fixed = 0; fixed < OWNER_CELL_CHUNKS; fixed++) {
      for (const horizontal of [true, false]) {
        let start = 0, color, nation;
        const emit = (end) => {
          if (!color) return;
          const along = (horizontal ? cell.x : cell.z) * BLOCKS_PER_CHUNK;
          const across = ((horizontal ? cell.z : cell.x) + fixed + 1) * BLOCKS_PER_CHUNK;
          segments.push(horizontal
            ? { x0: along + start * BLOCKS_PER_CHUNK, z0: across,
              x1: along + end * BLOCKS_PER_CHUNK, z1: across, color, nation }
            : { x0: across, z0: along + start * BLOCKS_PER_CHUNK,
              x1: across, z1: along + end * BLOCKS_PER_CHUNK, color, nation });
        };
        for (let moving = 0; moving <= OWNER_CELL_CHUNKS; moving++) {
          const next = moving < OWNER_CELL_CHUNKS
            ? styleAt(horizontal ? fixed * OWNER_CELL_CHUNKS + moving
              : moving * OWNER_CELL_CHUNKS + fixed) : undefined;
          if (!sameColor(color, next?.color) || nation !== next?.nation) {
            emit(moving); color = next?.color; nation = next?.nation; start = moving;
          }
        }
      }
    }
  }
  return mergeLineSegments(segments);
}
