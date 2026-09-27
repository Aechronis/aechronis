import { packCoord } from './data-index.mjs';

// One byte per world chunk, with individual texel writes for membership changes.
// Colors and attack progress remain independent of this persistent mask.
export class AttackMask {
  constructor(index) {
    this.minX = index.minCx; this.minZ = index.minCz;
    this.width = Math.max(1, index.maxCx - index.minCx + 1) || 1;
    this.height = Math.max(1, index.maxCz - index.minCz + 1) || 1;
    if (!Number.isFinite(this.width)) this.width = this.height = 1;
    this.keys = new Set(); this.pending = new Map();
  }
  update(attacks) {
    const next = new Set(attacks.map(a => packCoord(a.cx, a.cz)));
    for (const key of this.keys) if (!next.has(key)) this.pending.set(key, 0);
    for (const key of next) if (!this.keys.has(key)) this.pending.set(key, 255);
    this.keys = next;
  }
  getTexture(device) {
    if (!this.texture) this.texture = device.createTexture({
      id: 'attack-mask', width: this.width, height: this.height, format: 'r8unorm',
      data: new Uint8Array(this.width * this.height),
      sampler: { minFilter: 'nearest', magFilter: 'nearest', addressModeU: 'clamp-to-edge', addressModeV: 'clamp-to-edge' },
    });
    for (const [key, value] of this.pending) {
      const cx = Math.floor(key / 0x100000) - 0x80000, cz = key % 0x100000 - 0x80000;
      const x = cx - this.minX, y = cz - this.minZ;
      if (x >= 0 && y >= 0 && x < this.width && y < this.height) {
        this.texture.copyImageData({ data: new Uint8Array([value]), x, y, width: 1, height: 1 });
      }
    }
    this.pending.clear();
    return this.texture;
  }
  destroy() { this.texture?.destroy(); }
}
