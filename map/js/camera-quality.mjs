// Bound high-DPI raster work while moving, without undersampling small labels.
// One framebuffer pixel per CSS pixel is the minimum for readable map text.
// Software WebGL skips high-DPI supersampling because it shades on the CPU.
export function cameraPixelRatio(width, height, devicePixelRatio = 1, software = false) {
  const pixels = Math.max(1, width) * Math.max(1, height);
  const ratio = software ? 1 : Math.max(1, Math.min(2, Math.sqrt(8_000_000 / pixels)));
  return Math.min(devicePixelRatio, ratio);
}
