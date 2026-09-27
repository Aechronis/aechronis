// Keep the last response text for the small mutable files. Some static hosts
// ignore conditional headers, so an unchanged 200 must not rebuild the map.
export async function fetchJsonUpdate(url, previous, { initial = false, optional = false } = {}) {
  const headers = {};
  if (previous?.etag) headers['If-None-Match'] = previous.etag;
  if (previous?.lastMod) headers['If-Modified-Since'] = previous.lastMod;
  try {
    const response = await fetch(url, initial ? undefined : { headers, cache: 'no-store' });
    if (response.status === 304) return initial ? { body: null, meta: null } : null;
    if (!response.ok) {
      // A transient server failure must not remove existing map data.
      return initial || (optional && response.status === 404)
        ? { body: null, meta: null } : null;
    }
    const source = await response.text();
    const meta = {
      etag: response.headers.get('ETag'),
      lastMod: response.headers.get('Last-Modified'),
      source,
    };
    if (source === previous?.source) return { unchanged: true, meta };
    return { body: JSON.parse(source), meta };
  } catch {
    return initial ? { body: null, meta: null } : null;
  }
}

function sameValues(a, b) {
  a = a || [];
  b = b || [];
  return a === b || (a.length === b.length && a.every((value, i) => value === b[i]));
}

function sameOwnershipRecords(previous = {}, next = {}, membership) {
  const names = Object.keys(previous), nextNames = Object.keys(next);
  // Order controls precedence when claims or nation memberships overlap.
  return sameValues(names, nextNames) && names.every(name =>
    previous[name].uuid === next[name].uuid
    && sameValues(previous[name].captured, next[name].captured)
    && sameValues(previous[name].color, next[name].color)
    && sameValues(previous[name][membership], next[name][membership]));
}

export function ownershipChanged(previous, next) {
  return (previous.towns !== next.towns
    && (!sameOwnershipRecords(previous.towns?.towns || {}, next.towns?.towns || {}, 'territories')
      || !sameOwnershipRecords(previous.towns?.nations || {}, next.towns?.nations || {}, 'towns')))
    || (previous.war !== next.war
      && (JSON.stringify(previous.war?.occupied || {}) !== JSON.stringify(next.war?.occupied || {})
        || JSON.stringify(previous.war?.territoryOccupations || {}) !== JSON.stringify(next.war?.territoryOccupations || {})));
}
