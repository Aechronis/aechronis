// Canonical map coordinates are half-open [left, right); only the camera and
// rendered copies travel beyond them. Ownership data remains a single world.
export function wrapX(x, left, right) {
  const width = right - left;
  return left + ((x - left) % width + width) % width;
}

export function worldCopies(minX, maxX, left, right) {
  const width = right - left;
  const copies = [];
  for (let i = Math.floor((minX - left) / width); i < Math.ceil((maxX - left) / width); i++) copies.push(i);
  return copies;
}

export function wrappedIntervals(minX, maxX, left, right) {
  const span = maxX - minX;
  if (span >= right - left) return [[left, right]];
  const start = wrapX(minX, left, right);
  return start + span <= right ? [[start, start + span]]
    : [[start, right], [left, left + start + span - right]];
}
