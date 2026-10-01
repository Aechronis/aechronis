export { mergeLineSegments } from './overlay-geometry.mjs';
import { LineLayer } from '@deck.gl/layers';
import { packCoord } from './data-index.mjs';

const GLOW_STROKES = [[20, 9], [14, 16], [9, 28], [5, 50], [2, 150]];
const GLOW_WIDTH = GLOW_STROKES[0][0];
const glowFragment = `
  // Preserve the rasterizer's half-open edge rule for pixel-aligned bands.
  float glowSlopeX = dFdx(geometry.uv.y);
  float glowSlopeY = dFdy(geometry.uv.y);
  float glowEdgeBias = glowSlopeX < 0.0 || (glowSlopeX == 0.0 && glowSlopeY < 0.0)
    ? -0.000001 : 0.000001;
  float glowDistance = abs(geometry.uv.y + glowEdgeBias);
  float glowTransmission = 1.0;
  ${GLOW_STROKES.map(([width, alpha]) =>
    `glowTransmission *= 1.0 - color.a * (${alpha}.0 / 255.0)`
      + (width === GLOW_WIDTH ? ';'
        : ` * (1.0 - step(${width / GLOW_WIDTH}, glowDistance));`)).join('\n  ')}
  color.a = 1.0 - glowTransmission;
`;

// White source-over strokes combine as 1 - product(1 - alpha), including at
// intersections. color.a already includes deck.gl's opacity gamma correction.
// One wide stroke preserves the five bands with one set of vertices/draws.
export class NationGlowLayer extends LineLayer {
  static layerName = 'NationGlowLayer';

  getShaders() {
    const shaders = super.getShaders();
    return { ...shaders, inject: { ...shaders.inject,
      'fs:DECKGL_FILTER_COLOR': { order: -1, injection: glowFragment } } };
  }
}

const sourcePosition = (d) => [d.x0, d.z0];
const targetPosition = (d) => [d.x1, d.z1];
const lineColor = (d) => d.color;
// Cache expensive geometry. Deck retains GPU resources by stable layer ID.
export class SpatialOverlayCache {
  constructor(buckets = new Map()) {
    this.cellBlocks = 128 * 16;
    this.buckets = buckets;
    this.visible = [];
    this.wantGrid = false;
    this.wantOutlines = false;
    this.glowPadding = 16;
  }

  updateVisible(intervals, minZ, maxZ, { grid = false, outlines = false, zoom = 0 } = {}) {
    // Include wide strokes when culling at distant zoom levels.
    const scale = 2 ** zoom;
    const glowPadding = Math.max(1, 2 ** Math.ceil(Math.log2(12 / scale)));
    const next = [], seen = new Set();
    const top = Math.floor((minZ - glowPadding) / this.cellBlocks);
    const bottom = Math.floor((maxZ + glowPadding) / this.cellBlocks);
    for (const [minX, maxX] of intervals) {
      const left = Math.floor((minX - glowPadding) / this.cellBlocks);
      const right = Math.floor((maxX + glowPadding) / this.cellBlocks);
      for (let x = left; x <= right; x++) {
        for (let z = top; z <= bottom; z++) {
          const key = packCoord(x, z);
          const bucket = this.buckets.get(key);
          if (bucket && !seen.has(key)) { seen.add(key); next.push(bucket); }
        }
      }
    }
    const changed = grid !== this.wantGrid || outlines !== this.wantOutlines
      || glowPadding !== this.glowPadding
      || next.length !== this.visible.length || next.some((bucket, i) => bucket !== this.visible[i]);
    this.visible = next;
    this.wantGrid = grid;
    this.wantOutlines = outlines;
    this.glowPadding = glowPadding;
    return changed;
  }

  _layer(bucket, kind, data, width, getColor = lineColor) {
    return new LineLayer({
      id: `${kind}-${bucket.id}`, data,
      getSourcePosition: sourcePosition, getTargetPosition: targetPosition,
      getColor, updateTriggers: { getColor }, widthUnits: 'pixels', getWidth: width,
      pickable: false,
    });
  }

  outlineLayers(getColor) {
    if (!this.wantOutlines) return [];
    return this.visible.filter((bucket) => bucket.packedOutlines.length)
      .map((bucket) => this._layer(bucket, 'outlines', bucket.packedOutlines, 4, getColor));
  }

  gridLayers(getColor) {
    if (!this.wantGrid) return [];
    return this.visible.filter((bucket) => bucket.packedGrid.length)
      .map((bucket) => this._layer(bucket, 'chunk-grid', bucket.packedGrid, 1, getColor));
  }

}
