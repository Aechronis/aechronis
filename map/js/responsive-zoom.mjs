// Zoom settings describe map coverage on a 1920 CSS-pixel-wide viewport.
// CSS pixels, rather than device pixels, keep high-DPI displays consistent.
export const ZOOM_REFERENCE_WIDTH = 1920;

export function screenZoomOffset(width) {
  return Math.log2(Math.max(1, width) / ZOOM_REFERENCE_WIDTH);
}

function currentZoom(viewState) {
  return viewState.zoomX ?? (Array.isArray(viewState.zoom) ? viewState.zoom[0] : viewState.zoom);
}

export function clampScreenZoom(viewState, width, baseMin, baseMax) {
  const offset = screenZoomOffset(width);
  const minZoom = baseMin + offset;
  const maxZoom = baseMax + offset;
  const zoom = Math.min(maxZoom, Math.max(minZoom, currentZoom(viewState)));
  // OrthographicController emits axis-specific values and constraints. They
  // take precedence over the scalar props in both the controller and viewport.
  // Keep all of them synchronized instead of preserving stale resize limits.
  return { ...viewState, zoom, zoomX: zoom, zoomY: zoom,
    minZoom, minZoomX: minZoom, minZoomY: minZoom,
    maxZoom, maxZoomX: maxZoom, maxZoomY: maxZoom };
}

export function resizeScreenZoom(viewState, oldWidth, newWidth, baseMin, baseMax) {
  const zoom = currentZoom(viewState) + screenZoomOffset(newWidth) - screenZoomOffset(oldWidth);
  return clampScreenZoom({ ...viewState, zoom, zoomX: zoom, zoomY: zoom,
  }, newWidth, baseMin, baseMax);
}
