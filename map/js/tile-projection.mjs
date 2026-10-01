import { MAP_BOUNDS, WORLD_WIDTH } from './map-config.mjs';
// Match the world's Miller projection so satellite tiles align with overlays.
const RADIUS = WORLD_WIDTH / (2 * Math.PI);
const EQUATOR_Z = 1520;
const NORTHING_SCALE = 1.002;
export function latitudeToZ(latitude) {
  return EQUATOR_Z - RADIUS * NORTHING_SCALE * 1.25
    * Math.log(Math.tan(Math.PI / 4 + latitude * Math.PI / 180 * 0.4));
}
export function zToLatitude(z) {
  return (2 * Math.atan(Math.exp((EQUATOR_Z - z) / (RADIUS * NORTHING_SCALE * 1.25)))
    - Math.PI / 2) / 0.8 * 180 / Math.PI;
}
function mercatorY(latitude) {
  const radians = Math.max(-85.05112878, Math.min(85.05112878, latitude)) * Math.PI / 180;
  return (1 - Math.asinh(Math.tan(radians)) / Math.PI) / 2;
}
export function tileLatitude(y, count) {
  return Math.atan(Math.sinh(Math.PI * (1 - 2 * y / count))) * 180 / Math.PI;
}
export function quadkey(x, y, zoom) {
  let key = '';
  for (let bit = zoom - 1; bit >= 0; bit--) key += ((x >> bit) & 1) + 2 * ((y >> bit) & 1);
  return key;
}
export function tileUrl(metadata, tile) {
  const subdomains = metadata.imageUrlSubdomains || ['t0'];
  return metadata.imageUrl.replace(/^http:/, 'https:')
    .replace('{subdomain}', subdomains[(tile.x + tile.y) % subdomains.length])
    .replace('{quadkey}', quadkey(tile.x, tile.y, tile.zoom))
    .replace('{culture}', 'en-US').replace('{zoom}', tile.zoom);
}
export function visibleTiles(intervals, minZ, maxZ, zoom) {
  const count = 2 ** zoom;
  const firstY = Math.max(0, Math.floor(mercatorY(zToLatitude(Math.max(MAP_BOUNDS[1], minZ))) * count));
  const lastY = Math.min(count - 1, Math.floor(mercatorY(zToLatitude(Math.min(MAP_BOUNDS[3], maxZ))) * count));
  const tiles = new Map();
  for (const [left, right] of intervals) {
    const firstX = Math.max(0, Math.floor((left - MAP_BOUNDS[0]) / WORLD_WIDTH * count));
    const lastX = Math.min(count - 1, Math.ceil((right - MAP_BOUNDS[0]) / WORLD_WIDTH * count) - 1);
    for (let x = firstX; x <= lastX; x++) for (let y = firstY; y <= lastY; y++) {
      const north = Math.max(MAP_BOUNDS[1], latitudeToZ(tileLatitude(y, count)));
      const south = Math.min(MAP_BOUNDS[3], latitudeToZ(tileLatitude(y + 1, count)));
      const id = `${zoom}/${x}/${y}`;
      if (south > north) tiles.set(id, { id, x, y, zoom, bounds: [
        MAP_BOUNDS[0] + x * WORLD_WIDTH / count, south,
        MAP_BOUNDS[0] + (x + 1) * WORLD_WIDTH / count, north,
      ] });
    }
  }
  return [...tiles.values()];
}
// Public aerial tiles use the same quadkeys as the metadata-based service.
export const BING_AERIAL = {
  imageUrl: 'https://ecn.{subdomain}.tiles.virtualearth.net/tiles/a{quadkey}.jpeg?g=1',
  imageUrlSubdomains: ['t0', 't1', 't2', 't3'],
  imageWidth: 256,
  zoomMin: 1,
  zoomMax: 19,
};

// Resample Web Mercator scanlines into Miller coordinates before uploading.
// Each resulting bitmap is linear in block coordinates, like all map overlays.
export function reprojectTile(image, tile) {
  const canvas = typeof document === 'undefined' ? new OffscreenCanvas(image.width, image.height) : document.createElement('canvas');
  canvas.width = image.width;
  canvas.height = image.height;
  const ctx = canvas.getContext('2d');
  const [, south, , north] = tile.bounds;
  for (let row = 0; row < canvas.height; row++) {
    const sourceY = (mercatorY(zToLatitude(north + (south - north) * (row + 0.5) / canvas.height))
      * 2 ** tile.zoom - tile.y) * image.height;
    ctx.drawImage(image, 0, Math.max(0, Math.min(image.height - 1, sourceY)), image.width, 1,
      0, row, canvas.width, 1);
  }
  return canvas;
}
