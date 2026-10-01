import { TileLayer, _Tileset2D as Tileset2D } from '@deck.gl/geo-layers';
import { BitmapLayer } from '@deck.gl/layers';
import { worldCopies } from './world-wrap.mjs';
import { MAP_BOUNDS, WORLD_WIDTH } from './map-config.mjs';

import { WorkerClient } from './worker-client.mjs';
import { latitudeToZ, tileLatitude, visibleTiles, BING_AERIAL, tileUrl } from './tile-projection.mjs';
export * from './tile-projection.mjs';
let reprojection;

// The map uses linear block coordinates, so only tile indexing and imagery
// reprojection are custom. TileLayer owns requests, cancellation and caching.
export class MillerTileset extends Tileset2D {
  getTileIndices({ viewport }) {
    // Retry failed requests on a later camera update, as the old loader did.
    for (const tile of this.tiles) {
      if (tile.isLoaded && !tile.content && tile.userData?.retryAt <= Date.now()) tile.setNeedsReload();
    }
    const [left, top] = viewport.unproject([0, 0]);
    const [right, bottom] = viewport.unproject([viewport.width, viewport.height]);
    const ratio = 2 ** (this.opts.zoomOffset || 0);
    const zoom = Math.max(1, Math.min(19,
      Math.ceil(Math.log2(WORLD_WIDTH * 2 ** viewport.zoom * ratio / 256))));
    return worldCopies(left, right, MAP_BOUNDS[0], MAP_BOUNDS[2]).flatMap(copy =>
      visibleTiles([[left - copy * WORLD_WIDTH, right - copy * WORLD_WIDTH]], top, bottom, zoom)
        .map(tile => ({ x: tile.x + copy * 2 ** zoom, y: tile.y, z: zoom })));
  }

  getTileMetadata({ x, y, z }) {
    const count = 2 ** z;
    return { bbox: {
      left: MAP_BOUNDS[0] + x * WORLD_WIDTH / count,
      right: MAP_BOUNDS[0] + (x + 1) * WORLD_WIDTH / count,
      top: Math.max(MAP_BOUNDS[1], latitudeToZ(tileLatitude(y, count))),
      bottom: Math.min(MAP_BOUNDS[3], latitudeToZ(tileLatitude(y + 1, count))),
    } };
  }
}

export async function loadTile({ index: { x, y, z }, bbox, signal }) {
  const count = 2 ** z;
  const tile = { x: ((x % count) + count) % count, y, zoom: z,
    bounds: [bbox.left, bbox.bottom, bbox.right, bbox.top] };
  const response = await fetch(tileUrl(BING_AERIAL, tile), {
    signal: AbortSignal.any([signal, AbortSignal.timeout(15000)]),
  });
  if (!response.ok) throw new Error(`Satellite tile: HTTP ${response.status}`);
  const blob = await response.blob();
  signal.throwIfAborted();
  reprojection ||= new WorkerClient(new Worker(new URL('./tile-worker.mjs', import.meta.url), { type: 'module' }));
  const result = await reprojection.call('reproject', { blob, tile });
  if (signal.aborted) { result.close(); signal.throwIfAborted(); }
  return result;
}

// An always-resident world image covers areas never visited by the tile cache.
// It uses the same Miller bounds as the detail tiles and territory overlays.
export function satelliteOverviewLayer(image) {
  return new BitmapLayer({
    id: 'satellite-overview', image,
    bounds: [MAP_BOUNDS[0], MAP_BOUNDS[3], MAP_BOUNDS[2], MAP_BOUNDS[1]],
    pickable: false,
  });
}

// deck.gl's public callback currently receives only the error. Capture the
// tile in the layer hook so retries also work through the real TileLayer path.
export class SatelliteTileLayer extends TileLayer {
  static layerName = 'SatelliteTileLayer';
  _onTileError(error, tile) {
    tile.userData = { retryAt: Date.now() + 30000 };
    super._onTileError(error, tile);
  }
}

export function satelliteLayer(pixelRatio = globalThis.devicePixelRatio || 1) {
  return new SatelliteTileLayer({
    id: 'satellite', TilesetClass: MillerTileset,
    zoomOffset: Math.log2(Math.min(pixelRatio, 2)),
    // extent avoids comparing the map's negative zoom with Bing's minZoom.
    extent: MAP_BOUNDS, minZoom: 1, maxZoom: 19, tileSize: 256,
    maxRequests: 6, maxCacheSize: 384, refinementStrategy: 'no-overlap',
    getTileData: loadTile,
    onTileUnload: tile => tile.content?.close?.(),
    renderSubLayers: props => {
      if (!props.data) return null;
      const { left, top, right, bottom } = props.tile.bbox;
      return new BitmapLayer(props, { data: null, image: props.data,
        bounds: [left, bottom, right, top], pickable: false });
    },
    parameters: { depthCompare: 'always', depthWriteEnabled: false },
    pickable: false,
  });
}
