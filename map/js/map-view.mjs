import { OrthographicController, OrthographicView, OrthographicViewport } from '@deck.gl/core';
import { OrthographicState } from '../node_modules/@deck.gl/core/dist/controllers/orthographic-controller.js';
import { MAP_BOUNDS } from './map-config.mjs';

export function clampMapTarget(state, height) {
  const zoom = state.zoomY ?? state.zoom;
  const halfHeight = height / 2 / 2 ** zoom;
  const low = MAP_BOUNDS[1] + halfHeight;
  const high = MAP_BOUNDS[3] - halfHeight;
  // Preserve 2D/3D target shape so smooth frames compare equal in deck.gl.
  const target = state.target.slice();
  target[1] = low > high ? (MAP_BOUNDS[1] + MAP_BOUNDS[3]) / 2
    : Math.min(high, Math.max(low, target[1]));
  return { ...state, target };
}

export class MapState extends OrthographicState {
  applyConstraints(props) {
    super.applyConstraints(props);
    // Constrain inside the controller, including intermediate animation frames.
    // Clamping only in onViewStateChange interrupts zoom near the map edges.
    props.target = clampMapTarget(props, props.height).target;
    return props;
  }
}

export class MapController extends OrthographicController {
  constructor(...args) {
    super(...args);
    this.ControllerState = MapState;
  }

  _onWheel(event) {
    if (!this.scrollZoom) return false;
    const pos = this.getCenter(event);
    if (!this.isPointInBounds(pos, event)) return false;
    event.srcEvent.preventDefault();

    const { speed = 0.01, smooth = false } = this.scrollZoom === true ? {} : this.scrollZoom;
    let scale = 2 / (1 + Math.exp(-Math.abs(event.delta * speed)));
    if (event.delta < 0) scale = 1 / scale;

    const current = this.controllerState;
    const transition = this.transitionManager.transition;
    if (smooth && transition.inProgress && transition.settings === this._wheelTransition) {
      // Include unfinished wheel movement, but anchor the combined zoom at
      // the cursor in the currently visible frame. Clamp each new destination
      // so scrolling at a limit never builds up a hidden backlog.
      scale *= 2 ** (transition.settings.endProps.zoomX - current.getViewportProps().zoomX);
    }
    this.updateViewport(current.zoom({ pos, scale }), smooth
      ? { ...this._getTransitionProps({ around: pos }), transitionDuration: 250 }
      : { transitionDuration: 0 }, { isZooming: true, isPanning: true });
    this._wheelTransition = smooth ? transition.settings : null;
    if (!smooth) this._setInteractionState({ isZooming: false, isPanning: false });
    return true;
  }
}

export class MapViewport extends OrthographicViewport {
  constructor(props) {
    super({ near: -100000, far: 100000, ...props });
  }

  panByPosition(coords, pixel) {
    const from = this.unproject(pixel, { targetZ: 0 });
    const target = this.target.slice();
    target[0] += coords[0] - from[0];
    target[1] += coords[1] - from[1];
    return { target };
  }
}

export class MapView extends OrthographicView {
  static displayName = 'MapView';
  get ControllerType() { return MapController; }
  getViewportType() { return MapViewport; }
}
