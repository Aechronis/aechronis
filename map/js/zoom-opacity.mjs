// Match the controller's standard 100-unit wheel steps from the overview.
export function zoomOpacityFade(zoomFromOverview, scrollSpeed) {
  const step = Math.log2(2 / (1 + Math.exp(-100 * scrollSpeed)));
  const t = Math.max(0, Math.min(1, (zoomFromOverview / step - 20) / 12));
  return 0.65 * t * t * (3 - 2 * t);
}

export function fadeOpacity(alpha, fade) {
  return alpha * (1 - fade);
}

// Reveal detail smoothly over half a zoom level, reversing on zoom-out.
export function zoomDetailOpacity(zoom, threshold) {
  const t = Math.max(0, Math.min(1, (zoom - threshold) / 0.5));
  return t * t * (3 - 2 * t);
}

// Native chunk zoom is also the camera limit, so finish the fade there.
export function chunkDetailOpacity(zoom, nativeZoom) {
  return zoomDetailOpacity(zoom, nativeZoom - 0.5);
}
