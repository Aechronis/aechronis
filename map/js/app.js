import { buildAttackChunks } from './war-display.mjs';
import { buildTerritoryResources, territoryResourceKeys, resourceTooltip, resourceIconUrl } from './territory-resources.mjs';
import { animateFlagWave } from './flag-wave.mjs';
import { RelationBitmapLayer, buildRelationPalette } from './relation-raster.mjs';
import { relationIndex } from './nation-relations.mjs';
import { satelliteLayer, satelliteOverviewLayer } from './bing-tiles.mjs';
import earthOverviewUrl from '../assets/earth-overview.jpg';
import capitalStarUrl from '../assets/capital-star.svg';
import { buildNationCapitals, buildOtherTowns } from './nation-capitals.mjs';
import { Deck } from '@deck.gl/core';
import { memoize, resolveSelection, buildingIconUrl } from './map-selection.mjs';
import { buildIndex, hydrateIndex, chunkCanvas, packCoord } from './data-index.mjs';
import { Matrix4 } from '@math.gl/core';
import { fetchJsonUpdate, ownershipChanged } from './data-refresh.mjs';
import { worldLayers } from './layer-copies.mjs';
import { hydrateNationOpacity } from './nation-opacity.mjs';
import { WorkerClient } from './worker-client.mjs';
import { AttackMask } from './attack-mask.mjs';
import { zoomOpacityFade, fadeOpacity, zoomDetailOpacity, chunkDetailOpacity } from './zoom-opacity.mjs';
import { SpatialOverlayCache } from './spatial-overlays.mjs';
import { chunkBoundary, selectionBorderLayers } from './selection-borders.mjs';
import { MapView, clampMapTarget } from './map-view.mjs';
import { wrapX, worldCopies, wrappedIntervals } from './world-wrap.mjs';
import { cameraPixelRatio } from './camera-quality.mjs';
import { attackProgress, nextAttackFrame, AttackAnimationTimer } from './attack-animation.mjs';
import { screenZoomOffset, clampScreenZoom, resizeScreenZoom } from './responsive-zoom.mjs';
import { MAP_BOUNDS, WORLD_WIDTH } from './map-config.mjs';
import {
  IconLayer,
  ScatterplotLayer,
  SolidPolygonLayer,
  TextLayer,
} from '@deck.gl/layers';

// --- Constants -----------------------------------------------------------
const BLOCKS_PER_CHUNK = 16;
// Base fill opacity stays constant when selecting territories.
const TERRITORY_OPACITY = 0.1;
const TERRITORY_BORDER_COLOR = [64, 64, 64, 255];
const CHUNK_BORDER_COLOR = [192, 192, 192, 255];
// Inset the building image so it covers ~56% of the chunk, centered.
const BUILDING_IMAGE_SIZE = BLOCKS_PER_CHUNK * 0.56;
const PORT_RANGE_BY_TIER = [2500, 5000, 10000];
// Map labels retain their serif typeface independently of the interface.
const MAP_FONT = "'Times New Roman', Times, serif";
const NATION_FONT = 'Cormorant Garamond';

// Zoom limits are relative to a 1920px-wide viewport; map coordinates remain Minecraft blocks.
const VIEW_MAX_ZOOM = 0;
const NATIVE_VIEW_ZOOM = 0;
const SCROLL_ZOOM_SPEED = 0.00135;
// Match sixteen standard 100-unit wheel steps from the overview.
const CAPITAL_ZOOM_STEPS = 16 * Math.log2(2 / (1 + Math.exp(-100 * SCROLL_ZOOM_SPEED)));
const TOWN_ZOOM_STEPS = 18 * Math.log2(2 / (1 + Math.exp(-100 * SCROLL_ZOOM_SPEED)));
const BUILDING_ZOOM_STEPS = TOWN_ZOOM_STEPS;
const PAN_BOUNDS = MAP_BOUNDS;

function overviewZoom(height) {
  return Math.log2(Math.max(1, height) / (MAP_BOUNDS[3] - MAP_BOUNDS[1]));
}

function minimumViewZoom(width, height) {
  // Convert the height-fitting limit to the width-relative zoom convention.
  return overviewZoom(height) - screenZoomOffset(width);
}

function clamp01(x) { return x < 0 ? 0 : x > 1 ? 1 : x; }

// Centre of a chunk in block coords.
function chunkCenter(cx, cz) {
  return [cx * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2,
          cz * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2];
}

function escapeHtml(s) {
  return String(s).replace(/[&<>"']/g, (c) =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
}

// Chunk box corners plus the vertical seam at `frac` of the way across.
function chunkSplitX(cx, cz, frac) {
  const x0 = cx * BLOCKS_PER_CHUNK;
  const z0 = cz * BLOCKS_PER_CHUNK;
  return {
    x0,
    z0,
    x1: x0 + BLOCKS_PER_CHUNK,
    z1: z0 + BLOCKS_PER_CHUNK,
    xFill: x0 + BLOCKS_PER_CHUNK * clamp01(frac),
  };
}

// The captured (left) part, growing from the x0 edge toward the seam as the
// attack completes.
function chunkProgressPolygon(cx, cz, frac) {
  const { x0, z0, z1, xFill } = chunkSplitX(cx, cz, frac);
  return [[x0, z0], [xFill, z0], [xFill, z1], [x0, z1]];
}

// The complement: the not-yet-captured (right) part, from the seam to the
// chunk's right edge. Degenerate (zero-width) once the attack is complete.
function chunkRemainderPolygon(cx, cz, frac) {
  const { z0, x1, z1, xFill } = chunkSplitX(cx, cz, frac);
  return [[xFill, z0], [x1, z0], [x1, z1], [xFill, z1]];
}

// --- DOM panels ----------------------------------------------------------
const loadingEl = document.getElementById('loading');
const nationPanel = document.getElementById('nation-panel');
const townPanel = document.getElementById('town-panel');
const territoryPanel = document.getElementById('territory-panel');
const panelsEl = document.getElementById('panels');
const panelsCloseEl = document.getElementById('panels-close');
const coordsEl = document.getElementById('coords');
let panelImagesPending = false;
let cancelPanelImageWait = () => {};
let panelNationKey = null;
const panelTabs = [...document.querySelectorAll('[data-panel-tab]')];
function activatePanelTab(tab) {
  for (const button of panelTabs) {
    const active = button === tab;
    button.setAttribute('aria-selected', String(active));
    button.tabIndex = active ? 0 : -1;
    document.getElementById(button.getAttribute('aria-controls')).hidden = !active;
  }
  panelsEl.querySelector('.panel-body').scrollTop = 0;
}
for (const tab of panelTabs) {
  tab.addEventListener('click', () => activatePanelTab(tab));
  tab.addEventListener('keydown', event => {
    const index = panelTabs.indexOf(tab);
    let next;
    if (event.key === 'ArrowRight') next = (index + 1) % panelTabs.length;
    else if (event.key === 'ArrowLeft') next = (index + panelTabs.length - 1) % panelTabs.length;
    else if (event.key === 'Home') next = 0;
    else if (event.key === 'End') next = panelTabs.length - 1;
    else return;
    event.preventDefault();
    activatePanelTab(panelTabs[next]);
    panelTabs[next].focus();
  });
}

// The selection dossier only takes up screen space while it has something
// to show; toggle `has-content` from the panels' own empty state.
function refreshPanelsVisibility() {
  if (!panelsEl) return;
  const hasContent = !nationPanel.classList.contains('empty')
    || !territoryPanel.classList.contains('empty');
  panelsEl.classList.toggle('has-content', hasContent && !panelImagesPending);
}
// Split the coords readout into two stable child nodes once at boot so the
// hover handler can poke .nodeValue on text nodes instead of parsing HTML
// (innerHTML triggers layout + an HTML parse on every pointermove).
const coordsBlockText = document.createTextNode('');
const coordsChunkSpan = document.createElement('span');
coordsChunkSpan.className = 'coord-chunk';
const coordsChunkText = document.createTextNode('');
coordsChunkSpan.appendChild(coordsChunkText);
if (coordsEl) {
  coordsEl.appendChild(coordsBlockText);
  coordsEl.appendChild(coordsChunkSpan);
}

let loadingHidden = false;
function hideLoading() {
  if (loadingHidden || !loadingEl) return;
  loadingHidden = true;
  loadingEl.classList.add('is-hidden');
  loadingEl.addEventListener('transitionend', () => {
    loadingEl.style.display = 'none';
  }, { once: true });
}

function listHtml(items) {
  if (!items.length) return '<div class="empty-list">None</div>';
  return `<ul class="scroll-list">${items.map((s) => `<li>${escapeHtml(s)}</li>`).join('')}</ul>`;
}

// --- Boot ----------------------------------------------------------------
// Decode the bundled overview before exposing the map. Detail tiles may load
// later, fail, or be cancelled during a pan without leaving an empty background.
const earthOverview = new Image();
earthOverview.src = earthOverviewUrl;

const preparation = new WorkerClient(new Worker(new URL('./map-preparation-worker.mjs', import.meta.url), { type: 'module' }));
const initialPrepared = preparation.call('initial', { base: document.baseURI });
// Prepare the background beneath the loader while map data is built off-thread.
const fontsReady = Promise.all([
  document.fonts.load(`600 100px "${NATION_FONT}"`),
  ...[400, 700].map(weight => document.fonts.load(`${weight} 64px ${MAP_FONT}`)),
]).catch(error => console.warn('Map font:', error));
earthOverview.decode().catch(error => console.warn('Map overview:', error)).then(() => {
  bootMap({ data: { towns: null, world: null, war: null, buildings: null }, meta: {} });
});

function bootMap(initial) {
  // All "data-derived" state lives in `let`s so the periodic refresh can
  // rebuild in place — closures elsewhere in the function read the bindings,
  // not the values, so they automatically pick up the new objects after
  // a data refresh reassigns.
  let data = initial.data;
  const fileMeta = initial.meta;  // { towns, war, buildings } → { etag, lastMod }

  let index;
  let nationLabels;
  let worldImage;
  let nationOpacity;
  let mapMode = 'political';
  let rasterBoundary = null;
  let opacityByBoundary = new Map();
  let spatialOverlays;
  let attackChunks;
  let attackChunkKeys = new Set();  // packed coords of currently-shown attacks
  let buildingsList;
  let buildingsByChunk;
  const resourceData = memoize(buildTerritoryResources);
  const resourceIcons = new Map();

  let preparedRasters = { images: {}, names: [], colors: [] };
  let politicalPalette;
  let attackMask;
  let dataReady = false;
  let initialLayersSubmitted = false;

  function installPrepared(result) {
    const oldMask = attackMask;
    if (result.stable) {
      result.index.owner.cells = index.owner.cells;
      result.index.owner.cellList = index.owner.cellList;
    }
    index = hydrateIndex(result.index);
    nationLabels = result.labels;
    if (!result.stable) opacityByBoundary.clear();
    for (const [boundary, state] of Object.entries(result.opacity)) {
      opacityByBoundary.set(boundary, hydrateNationOpacity(index, state));
    }
    const oldImages = preparedRasters.images;
    preparedRasters = { ...result.rasters, images: { ...oldImages, ...result.rasters.images } };
    // ImageBitmap pixels have been uploaded before a later preparation finishes.
    for (const boundary of Object.keys(result.rasters.images)) oldImages[boundary]?.close?.();
    const { buf, commit } = chunkCanvas(256, Math.max(1, Math.ceil(preparedRasters.colors.length / 256)));
    preparedRasters.colors.forEach((color, id) => buf.set(color, id * 4));
    politicalPalette = commit();
    const buckets = result.stable ? new Map([...spatialOverlays.buckets, ...result.buckets]) : result.buckets;
    spatialOverlays = new SpatialOverlayCache(buckets);
    attackChunks = buildAttackChunks(data.war, data.towns, index);
    attackChunkKeys = new Set(attackChunks.map(a => packCoord(a.cx, a.cz)));
    attackMask = result.stable && oldMask ? oldMask : new AttackMask(index);
    attackMask.update(attackChunks);
    // Old layer instances may still draw until the batched redraw installs the replacement.
    if (oldMask && oldMask !== attackMask) requestAnimationFrame(() => requestAnimationFrame(() => oldMask.destroy()));
    rasterBoundary = null;
    updateMapRaster();
  }

  function updateMapRaster() {
    const boundary = mapMode === 'towns' ? 'town' : 'nation';
    if (rasterBoundary === boundary) return;
    nationOpacity = opacityByBoundary.get(boundary) || (() => TERRITORY_OPACITY);
    worldImage = preparedRasters.images[boundary] || null;
    rasterBoundary = boundary;
  }

  function buildBuildings() {
    buildingsByChunk = new Map();
    buildingsList = [];
    if (Array.isArray(data.buildings?.buildings)) {
      for (const b of data.buildings.buildings) {
        if (b == null || b.chunkX == null || b.chunkZ == null) continue;
        const cx = b.chunkX, cz = b.chunkZ;
        const entry = {
          data: b,
          cx,
          cz,
          // Used for the port-range ring and as the icon position.
          center: chunkCenter(cx, cz),
          // Top-centre of the chunk in block coords; label sits just above.
          labelAnchor: [cx * BLOCKS_PER_CHUNK + BLOCKS_PER_CHUNK / 2,
                        cz * BLOCKS_PER_CHUNK],
          labelText: [
            b.name,
            b.type,
            b.tier != null ? `tier ${b.tier}` : null,
            b.isPublic ? 'public' : null,
          ].filter(Boolean).join(' · '),
        };
        buildingsList.push(entry);
        buildingsByChunk.set(packCoord(cx, cz), entry);
      }
    }
  }

  index = buildIndex(data);
  nationLabels = [];
  attackChunks = [];
  spatialOverlays = new SpatialOverlayCache();
  updateMapRaster();
  buildBuildings();

  // --- Mutable selection / hover state -----------------------------------
  let redrawFrame = null;
  let qualityTimer = null;
  let cameraInteracting = false;

  function movingPixelRatio() {
    const software = deck.device?.info.gpu === 'software';
    clearTimeout(qualityTimer);
    // Slow software frames can exceed a normal interaction debounce. Keep
    // their buffer stable until movement has actually stopped.
    qualityTimer = setTimeout(restorePixelRatio, software ? 750 : 200);
    return cameraPixelRatio(window.innerWidth, window.innerHeight,
      window.devicePixelRatio || 1, software);
  }

  function restorePixelRatio() {
    if (cameraInteracting) {
      qualityTimer = setTimeout(restorePixelRatio, 200);
      return;
    }
    qualityTimer = null;
    deck.setProps({ useDevicePixels: window.devicePixelRatio || 1 });
    // A buffer-size change alone does not dirty deck's layers. Paint the
    // restored buffer now, even when the pointer and camera remain still.
    deck.redraw('camera resolution restored');
    redraw();
  }

  function cancelCameraInteraction() {
    // Browsers may cancel touch gestures or lose focus without a drag-end.
    // Do not leave the map at its movement resolution in that case.
    cameraInteracting = false;
    clearTimeout(qualityTimer);
    qualityTimer = setTimeout(restorePixelRatio, 200);
  }

  let zoomViewportWidth = window.innerWidth;
  let zoomOffset = screenZoomOffset(zoomViewportWidth);
  let viewZoom = NATIVE_VIEW_ZOOM + zoomOffset;
  let viewTarget = [0, 0];
  function visibleWorldCopies() {
    const halfWidth = window.innerWidth / 2 / 2 ** viewZoom + 64;
    return worldCopies(viewTarget[0] - halfWidth, viewTarget[0] + halfWidth,
      PAN_BOUNDS[0], PAN_BOUNDS[2]);
  }
  function canonicalCoordinate(coordinate) {
    return coordinate && [wrapX(coordinate[0], PAN_BOUNDS[0], PAN_BOUNDS[2]),
      ...coordinate.slice(1)];
  }
  let relationNation = null;
  const mapModeSelect = document.getElementById('map-mode');
  const mapModeButtons = document.getElementById('map-mode-buttons');
  mapModeButtons.hidden = false;
  mapModeSelect.hidden = true;
  function syncMapMode() {
    mapModeSelect.value = mapMode;
    for (const button of mapModeButtons.querySelectorAll('button')) {
      button.setAttribute('aria-pressed', String(button.dataset.mapMode === mapMode));
    }
  }
  mapModeSelect.addEventListener('change', () => {
    mapMode = mapModeSelect.value;
    relationNation = mapMode === 'relations' ? selection().hit?.style.nation || null : null;
    updateSelection();
  });
  mapModeButtons.addEventListener('click', event => {
    const button = event.target.closest('button[data-map-mode]');
    if (!button || button.dataset.mapMode === mapMode) return;
    mapModeSelect.value = button.dataset.mapMode;
    mapModeSelect.dispatchEvent(new Event('change'));
  });
  const relationStyles = memoize(relationIndex);
  const displayIndex = () => mapMode === 'relations'
    ? relationStyles(index, data.towns.nations, relationNation, data.war?.flagDeathWar) : index;
  const relationPalette = memoize(buildRelationPalette);
  const relationAttacks = memoize((attacks, styledIndex) =>
    buildAttackChunks(data.war, data.towns, styledIndex));
  let selectedCoordinate = null;
  const deriveSelection = memoize(resolveSelection);
  const selection = () => deriveSelection(selectedCoordinate, index, buildingsByChunk);
  let hoveredBuilding = null;
  let hoverPixel = null;

  // Cache selection boundaries independently of camera and animation updates.
  const townBoundary = memoize(chunkBoundary);
  const territoryBoundary = memoize(chunkBoundary);
  // Selection follows the original town claims, including occupied chunks.
  const townChunks = memoize((index, townName) => {
    const chunks = [];
    if (townName == null) return chunks;
    const territories = new Set(index.owner.records.filter(record => record?.ownerName === townName)
      .map(record => record.territoryId));
    for (const tid of territories) {
      const arr = data.world.territories[tid]?.chunks || [];
      for (let i = 0; i < arr.length; i += 2) {
        const id = index.owner.getId(arr[i], arr[i + 1]);
        if (index.owner.records[id]?.ownerName === townName) chunks.push(arr[i], arr[i + 1]);
      }
    }
    return chunks;
  });
  let attackAnimFrame = 0;
  let visibleAttackBounds = null;
  const attackGeometry = memoize((attacks, frame) => {
    const now = Date.now() / 1000;
    const captured = [], remaining = [];
    let left = Infinity, top = Infinity, right = -Infinity, bottom = -Infinity;
    for (const attack of attacks) {
      attack.frac = attackProgress(attack.s, attack.e, now);
      const { cx, cz, frac } = attack;
      captured.push({ ...attack, polygon: chunkProgressPolygon(cx, cz, frac) });
      remaining.push({ ...attack, polygon: chunkRemainderPolygon(cx, cz, frac) });
      left = Math.min(left, cx * BLOCKS_PER_CHUNK);
      top = Math.min(top, cz * BLOCKS_PER_CHUNK);
      right = Math.max(right, (cx + 1) * BLOCKS_PER_CHUNK);
      bottom = Math.max(bottom, (cz + 1) * BLOCKS_PER_CHUNK);
    }
    return { captured, remaining, tile: { boundingBox: [[left, top], [right, bottom]] } };
  });
  const portData = memoize(building => building ? [building] : []);
  const labelData = memoize((hovered, selected) =>
    [...new Set([hovered, selected].filter(Boolean))]);
  const capitalData = memoize(buildNationCapitals);
  const otherTownData = memoize(buildOtherTowns);
  // Last block / chunk coords pushed into the coords readout. Cache so the
  // hover handler can short-circuit DOM writes when the pointer hasn't moved
  // a full block.
  let lastCoordBx = NaN, lastCoordBz = NaN;
  let lastCoordCx = NaN, lastCoordCz = NaN;
  let renderedNationContentKey;
  let renderedTownContentKey;

  function renderLeaderPortrait(panel, leaderId, waitForImage = (image, crop) => {
    image.addEventListener('error', () => crop.remove(), { once: true });
  }) {
    const leader = data.towns.residents[leaderId];
    if (leaderId && leader) {
      const portrait = document.createElement('figure');
      portrait.className = 'nation-leader';
      const crop = document.createElement('div');
      crop.className = 'nation-leader-image';
      const image = document.createElement('img');
      image.alt = `${leader.name}'s Minecraft skin`;
      image.width = 160;
      image.height = 160;
      image.decoding = 'async';
      image.referrerPolicy = 'no-referrer';
      waitForImage(image, crop);
      image.src = `https://nmsr.nickac.dev/bust/${encodeURIComponent(leaderId)}`;
      const caption = document.createElement('figcaption');
      const label = document.createElement('span');
      label.className = 'nation-leader-label';
      label.textContent = 'Leader';
      const name = document.createElement('span');
      name.textContent = leader.name;
      caption.append(label, name);
      const portraitWindow = document.createElement('div');
      portraitWindow.className = 'nation-leader-crop';
      portraitWindow.append(image);
      crop.append(portraitWindow);
      portrait.append(crop, caption);
      panel.prepend(portrait);
    }
  }

  function renderNationPanel(hit) {
    const title = document.getElementById('nation-title');
    if (hit.ownerName == null) {
      title.textContent = 'Unclaimed land';
      nationPanel.classList.remove('empty');
      nationPanel.innerHTML = '<div class="subtitle">This territory is unclaimed.</div>';
      return;
    }
    const style = index.townStyle.get(hit.ownerName);
    if (!style.nation) {
      title.textContent = 'Independent town';
      nationPanel.classList.remove('empty');
      nationPanel.innerHTML = '<div class="subtitle">This town belongs to no nation.</div>';
      return;
    }
    const nation = data.towns.nations[style.nation];
    const towns = nation.towns || [];
    const longName = nation.longName && nation.longName !== 'null' ? nation.longName : style.nation;
    const showLong = longName !== style.nation;
    title.textContent = longName;
    nationPanel.classList.remove('empty');
    nationPanel.innerHTML = `
      ${showLong ? `<div class="subtitle">${escapeHtml(style.nation)}</div>` : ''}
      <dl>
        <dt>Capital</dt><dd>${escapeHtml(nation.capital || '—')}</dd>
        <dt>Towns</dt><dd>${towns.length}</dd>
        <dt>Allies</dt><dd>${(nation.allies || []).length}</dd>
        <dt>Enemies</dt><dd>${(nation.enemies || []).length}</dd>
      </dl>
      <h3>Towns</h3>
      ${listHtml(towns)}
      <h3>Allies</h3>
      ${listHtml(nation.allies || [])}
      <h3>Enemies</h3>
      ${listHtml(nation.enemies || [])}
    `;
    const waitForImages = !panelsEl.classList.contains('has-content');
    const imageTimers = new Set();
    let pendingImages = 0;
    let active = true;
    cancelPanelImageWait = () => {
      active = false;
      for (const timer of imageTimers) clearTimeout(timer);
      imageTimers.clear();
    };
    function waitForImage(image, container, loaded = () => {}) {
      pendingImages++;
      if (waitForImages) panelImagesPending = true;
      let settled = false;
      const finish = failed => {
        if (!active || settled) return;
        settled = true;
        clearTimeout(timer);
        imageTimers.delete(timer);
        if (failed) container.remove();
        else loaded();
        pendingImages--;
        panelImagesPending = waitForImages && pendingImages > 0;
        refreshPanelsVisibility();
      };
      // A failed or stalled image must not hold the selection panel closed.
      const timer = setTimeout(() => finish(true), 8000);
      imageTimers.add(timer);
      image.addEventListener('error', () => finish(true), { once: true });
      image.addEventListener('load', async () => {
        try {
          await image.decode();
          finish(false);
        } catch {
          finish(true);
        }
      }, { once: true });
    }
    renderLeaderPortrait(nationPanel, data.towns.towns[nation.capital]?.leader, waitForImage);
    if (typeof nation.flagUrl === 'string' && /^https?:\/\//i.test(nation.flagUrl)) {
      const mount = document.createElement('div');
      mount.className = 'nation-flag-mount';
      const cloth = document.createElement('div');
      cloth.className = 'nation-flag-cloth';
      cloth.setAttribute('role', 'img');
      cloth.setAttribute('aria-label', `${longName} flag`);
      const flag = document.createElement('img');
      flag.className = 'nation-flag';
      flag.alt = '';
      flag.referrerPolicy = 'no-referrer';
      waitForImage(flag, mount, () => animateFlagWave(cloth));
      flag.src = nation.flagUrl;
      cloth.append(flag);
      mount.append(cloth);
      nationPanel.prepend(mount);
    }
  }

  function renderTownPanel(hit) {
    const town = hit.ownerName == null
      ? null
      : data.towns?.towns?.[hit.ownerName];
    const leaderName = data.towns?.residents?.[town?.leader]?.name || '—';
    const residents = (town?.residents || [])
      .map((uuid) => data.towns?.residents?.[uuid]?.name || uuid);
    const townSummary = town ? `
      <h2>${escapeHtml(hit.ownerName)}</h2>
      <dl>
        <dt>Leader</dt><dd>${escapeHtml(leaderName)}</dd>
        <dt>Residents</dt><dd>${residents.length}</dd>
        <dt>Territories</dt><dd>${(town.territories || []).length}</dd>
      </dl>
    ` : `
      <h2>Unclaimed</h2>
      <div class="subtitle">No town owns this territory.</div>
    `;

    townPanel.classList.remove('empty');
    townPanel.innerHTML = `
      ${townSummary}
      ${town ? `<h3>Residents</h3>${listHtml(residents)}` : ''}
    `;
    if (town) {
      renderLeaderPortrait(townPanel, town.leader);
      if (typeof town.coatOfArmsUrl === 'string' && /^https?:\/\//i.test(town.coatOfArmsUrl)) {
        const arms = document.createElement('img');
        arms.className = 'town-coat-of-arms';
        arms.alt = `${hit.ownerName} coat of arms`;
        arms.decoding = 'async';
        arms.referrerPolicy = 'no-referrer';
        arms.addEventListener('error', () => arms.remove(), { once: true });
        arms.src = town.coatOfArmsUrl;
        townPanel.prepend(arms);
      }
    }
  }

  function renderTerritoryPanel(hit) {
    const territory = data.world.territories[hit.territoryId];
    const tname = territory && territory.name ? territory.name : `#${hit.territoryId}`;
    const core = territory && territory.core;
    const sizeChunks = territory ? territory.size : 0;
    const resources = resourceTooltip(territoryResourceKeys(territory, data.world.nodes), data.world.nodes);
    territoryPanel.classList.remove('empty');
    territoryPanel.innerHTML = `
      <h2>Territory: ${escapeHtml(tname)}</h2>
      <dl>
        <dt>Owner</dt><dd>${escapeHtml(hit.ownerName || 'Unclaimed')}</dd>
        ${hit.occupier ? `<dt>Territory occupier</dt><dd>${escapeHtml(hit.occupier)}</dd>` : ''}
        ${hit.chunkOccupier ? `<dt>Chunk controller</dt><dd>${escapeHtml(hit.chunkOccupier)}</dd>` : ''}
        <dt>Size</dt><dd>${sizeChunks} chunks</dd>
        ${core ? `<dt>Core</dt><dd>${core[0]}, ${core[1]}</dd>` : ''}
      </dl>
      <h3>Resources</h3>
      ${resources || '<div class="subtitle">None</div>'}
    `;
  }

  function updateSelection() {
    const { hit } = selection();
    const nationName = hit && index.townStyle.get(hit.ownerName)?.nation;
    const nationKey = hit
      ? JSON.stringify([nationName || hit.ownerName, data.towns.nations[nationName]?.flagUrl])
      : null;
    if (nationKey !== panelNationKey) panelsEl.classList.remove('has-content');
    panelNationKey = nationKey;
    // Keep the existing flag connected when selecting within the same nation.
    // Include the nation data so live changes still refresh the panel.
    const nation = data.towns.nations[nationName];
    const leaderId = data.towns.towns[nation?.capital]?.leader;
    const nationContentKey = JSON.stringify(hit
      ? [nationName || hit.ownerName, nation, leaderId, data.towns.residents[leaderId]?.name]
      : null);
    const refreshNation = nationContentKey !== renderedNationContentKey;
    if (refreshNation) {
      cancelPanelImageWait();
      panelImagesPending = false;
      nationPanel.innerHTML = '';
      renderedNationContentKey = nationContentKey;
    }
    const town = data.towns.towns[hit?.ownerName];
    const townContentKey = JSON.stringify(hit
      ? [hit.ownerName, town, (town?.residents || []).map(id => data.towns.residents[id]?.name),
        data.towns.residents[town?.leader]?.name]
      : null);
    const refreshTown = townContentKey !== renderedTownContentKey;
    if (refreshTown) {
      townPanel.innerHTML = '';
      renderedTownContentKey = townContentKey;
    }
    territoryPanel.innerHTML = '';
    nationPanel.classList.toggle('empty', !hit);
    townPanel.classList.toggle('empty', !hit);
    territoryPanel.classList.toggle('empty', !hit);
    if (relationNation && !data.towns?.nations?.[relationNation]) relationNation = null;
    syncMapMode();
    updateMapRaster();
    if (hit) {
      if (refreshNation) renderNationPanel(hit);
      if (refreshTown) renderTownPanel(hit);
      renderTerritoryPanel(hit);
    }
    refreshPanelsVisibility();
    updateVisible();
    redraw();
  }

  function selectAt(coordinate) {
    selectedCoordinate = coordinate;
    if (mapMode === 'relations') {
      const nation = selection().hit?.style.nation;
      if (nation) relationNation = nation;
    }
    updateSelection();
  }

  function exitRelations() {
    if (mapMode === 'relations') mapMode = 'political';
    relationNation = null;
    selectAt(null);
  }

  panelsCloseEl?.addEventListener('click', exitRelations);

  // --- Layer construction -------------------------------------------------
  const PIXELATED = { magFilter: 'nearest', minFilter: 'nearest' };

  function highlightLayers() {
    const { hit } = selection();
    if (!hit) return [];
    const territory = data.world.territories[hit.territoryId];
    return selectionBorderLayers(
      townBoundary(townChunks(index, hit.ownerName)),
      territoryBoundary(territory.chunks));
  }

  // Reuse tile-sized overlay geometry as the camera moves. The padded range
  // covers stroke widths, including both sides of the world seam.
  function updateVisible() {
    const scale = 2 ** viewZoom;
    const halfW = window.innerWidth / 2 / scale * 1.2;
    const halfH = window.innerHeight / 2 / scale * 1.2;
    const intervals = wrappedIntervals(viewTarget[0] - halfW, viewTarget[0] + halfW,
      PAN_BOUNDS[0], PAN_BOUNDS[2]);
    visibleAttackBounds = { intervals, minZ: viewTarget[1] - halfH, maxZ: viewTarget[1] + halfH };
    return spatialOverlays.updateVisible(
      intervals,
      viewTarget[1] - halfH, viewTarget[1] + halfH, {
        grid: chunkDetailOpacity(viewZoom, NATIVE_VIEW_ZOOM + zoomOffset) > 0,
        outlines: viewZoom >= (mapMode === 'resources' ? -4 : -2) + zoomOffset,
        zoom: viewZoom,
      });
  }

  function territoryRasterLayer() {
    if (!worldImage) return null;
    return new RelationBitmapLayer({
      id: 'territory-raster',
      image: worldImage,
      attackMask,
      palette: mapMode === 'relations' ? relationPalette(preparedRasters.names, data.towns.nations,
        relationNation, data.war?.flagDeathWar) : politicalPalette,
      // Image's top-left chunk = (minCx, minCz) — top-left of that chunk in
      // blocks is (minBlockX, minBlockZ). Bottom-right is (maxBlockX,
      // maxBlockZ). With y-down, "top" is the smaller y.
      bounds: [index.minCx * BLOCKS_PER_CHUNK, (index.maxCz + 1) * BLOCKS_PER_CHUNK,
        (index.maxCx + 1) * BLOCKS_PER_CHUNK, index.minCz * BLOCKS_PER_CHUNK],
      opacity: 1,
      fillFade: currentFillFade(),
      textureParameters: PIXELATED,
    });
  }

  function currentFillFade() {
    return zoomOpacityFade(viewZoom - overviewZoom(window.innerHeight), SCROLL_ZOOM_SPEED);
  }

  // Attack fills follow the same zoom opacity as the base nation colour.
  function attackFillColor(rgb, cx, cz) {
    return [rgb[0], rgb[1], rgb[2],
      Math.round(fadeOpacity(nationOpacity(cx, cz), currentFillFade()) * 255)];
  }

  // Attacked chunks are cut out of the base raster so these fills supply
  // their attacker/defender colours without changing opacity on selection.
  function attacksLayer() {
    if (!attackChunks.length) return [];
    const geometry = attackGeometry(mapMode === 'relations' ? relationAttacks(attackChunks, displayIndex()) : attackChunks, attackAnimFrame);
    return ['remaining', 'captured'].map(part => new SolidPolygonLayer({
      id: `attacks-${part}`,
      data: geometry[part], tile: geometry.tile,
      getPolygon: d => d.polygon,
      getFillColor: d => attackFillColor(part === 'captured' ? d.color : d.defColor, d.cx, d.cz),
      updateTriggers: { getFillColor: [nationOpacity, currentFillFade()] },
    }));
  }

  function homesLayer() {
    if (!displayIndex().homes.length) return null;
    return new TextLayer({
      id: 'homes',
      data: displayIndex().homes,
      getPosition: (d) => chunkCenter(d.cx, d.cz),
      getText: () => 'H',
      getColor: [255, 255, 255, 255],
      // 12 blocks ≈ 75% of the chunk. sizeUnits 'common' makes the H scale
      // with zoom, matching leaflet's 0.8*pxPerChunk font size.
      sizeUnits: 'common',
      getSize: BLOCKS_PER_CHUNK * 0.75,
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'center',
      fontFamily: MAP_FONT,
      fontWeight: 700,
      fontSettings: { sdf: true, fontSize: 64, buffer: 8 },
      characterSet: ['H'],
    });
  }

  function portRingLayer() {
    const building = hoveredBuilding?.data.type === 'port'
      ? hoveredBuilding : selection().building;
    const radius = building?.data.type === 'port' && PORT_RANGE_BY_TIER[building.data.tier - 1];
    if (!radius) return null;
    return new ScatterplotLayer({
      id: 'port-ring',
      data: portData(building),
      getPosition: (d) => d.center,
      getRadius: radius,
      radiusUnits: 'common',
      filled: true,
      stroked: true,
      lineWidthUnits: 'pixels',
      getLineWidth: 4,
      getFillColor: [255, 255, 255, 64],
      getLineColor: [255, 255, 255, 255],
    });
  }

  function buildingBackplatesLayer() {
    if (!buildingsList.length) return null;
    // Match the port's white round backing so black symbols remain legible
    // over both dark water and satellite terrain.
    return new ScatterplotLayer({
      id: 'building-backplates',
      data: buildingsList,
      getPosition: d => d.center,
      getRadius: BUILDING_IMAGE_SIZE / 2,
      radiusUnits: 'common',
      radiusMinPixels: 8,
      getFillColor: [255, 255, 255, 255],
      stroked: false,
      pickable: false,
    });
  }

  function buildingsLayer() {
    if (!buildingsList.length) return null;
    return new IconLayer({
      id: 'buildings',
      data: buildingsList,
      pickable: true,
      getPosition: (d) => d.center,
      getIcon: (d) => ({
        url: buildingIconUrl(d.data.type),
        // All building silhouettes share a square icon frame.
        width: 64,
        height: 64,
        // ID lets the icon manager dedupe URLs across buildings of the same type.
        id: d.data.type || 'unknown',
        anchorX: 32,
        anchorY: 32,
      }),
      // Icons sized in world units so they scale with zoom; match the original
      // image inset (~70% of a chunk).
      sizeUnits: 'common',
      getSize: BUILDING_IMAGE_SIZE,
      sizeMinPixels: 16,
      // Pixelated upscaling — building images are 8-bit pixel art.
      textureParameters: PIXELATED,
    });
  }

  function buildingLabelLayer() {
    const items = labelData(hoveredBuilding, selection().building);
    if (!items.length) return null;
    return new TextLayer({
      id: 'building-labels',
      data: items,
      getPosition: (d) => d.labelAnchor,
      getText: (d) => d.labelText,
      // Pixel-fixed size so the chip stays readable at any zoom.
      sizeUnits: 'pixels',
      getSize: 20,
      getColor: [230, 230, 230, 255],
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'bottom',
      // Lift the chip a few pixels above the chunk top edge.
      getPixelOffset: [0, -6],
      background: true,
      backgroundPadding: [6, 2, 6, 2],
      getBackgroundColor: [20, 20, 20, 240],
      fontFamily: MAP_FONT,
      fontSettings: { sdf: true, fontSize: 64, buffer: 8 },
    });
  }

  function nationCapitalsLayer() {
    return new IconLayer({
      id: 'nation-capitals',
      data: capitalData(data.towns, data.world),
      getPosition: (d) => d.position,
      getIcon: () => ({ url: capitalStarUrl, width: 32, height: 32,
        anchorX: 16, anchorY: 16 }),
      sizeUnits: 'pixels',
      getSize: 20,
      pickable: false,
    });
  }

  function townDetailLayers() {
    const capitals = capitalData(data.towns, data.world);
    const towns = otherTownData(data.towns, data.world);
    const labels = (id, items, offset, size) => new TextLayer({
      id,
      data: items,
      getPosition: (d) => d.position,
      getText: (d) => d.town.replace(/_/g, ' '),
      getPixelOffset: [offset, 0],
      getTextAnchor: 'start',
      getAlignmentBaseline: 'center',
      sizeUnits: 'pixels',
      getSize: size,
      getColor: [255, 244, 199, 255],
      fontFamily: MAP_FONT,
      characterSet: 'auto',
      // Soften small label edges as they move across pixels during zooming.
      fontSettings: { sdf: true, fontSize: 64, buffer: 8, smoothing: 0.2 },
      outlineWidth: 5,
      outlineColor: [37, 32, 25, 255],
      pickable: false,
    });
    return [
      new ScatterplotLayer({
        id: 'town-circles',
        data: towns,
        getPosition: (d) => d.position,
        radiusUnits: 'pixels',
        getRadius: 4,
        filled: true,
        stroked: true,
        getFillColor: [255, 244, 199, 255],
        getLineColor: [37, 32, 25, 255],
        lineWidthUnits: 'pixels',
        getLineWidth: 1.5,
        pickable: false,
      }),
      labels('town-names', towns, 8, 18),
      labels('capital-names', capitals, 14, 20),
    ];
  }

  function nationLabelsLayer() {
    const opacity = 1 - zoomDetailOpacity(viewZoom, -1 + zoomOffset);
    if (!nationLabels.length || opacity <= 0) return null;
    return new TextLayer({
      id: 'nation-labels',
      data: nationLabels,
      opacity,
      parameters: { depthCompare: 'always', depthWriteEnabled: false },
      getPosition: (d) => d.position,
      getText: (d) => d.text,
      getSize: (d) => d.size,
      sizeUnits: 'common',
      getAngle: (d) => d.angle,
      getColor: [16, 16, 16, 153],
      getTextAnchor: 'middle',
      getAlignmentBaseline: 'center',
      fontFamily: NATION_FONT,
      fontWeight: 600,
      characterSet: 'auto',
      // Large world-space letters magnify the atlas at close zoom. Preserve
      // the serif contours in a detailed SDF without changing label layout.
      fontSettings: { sdf: true, fontSize: 512, buffer: 16 },
      outlineWidth: 2,
      outlineColor: [30, 25, 20, 210],
      pickable: false,
    });
  }

  function showLandmarks() {
    return viewZoom >= overviewZoom(window.innerHeight) + CAPITAL_ZOOM_STEPS;
  }

  function resourceLayers() {
    if (mapMode !== 'resources') return [];
    const items = resourceData(data.world).filter(d => {
      if (!resourceIcons.has(d.icon)) {
        resourceIcons.set(d.icon, null);
        const image = new Image();
        image.src = resourceIconUrl(d.icon);
        image.decode().then(() => {
          resourceIcons.set(d.icon, {
            id: d.icon, url: image.src,
            width: image.naturalWidth, height: image.naturalHeight,
          });
          redraw();
        }).catch(error => console.warn(`Resource icon ${d.icon}:`, error));
      }
      return resourceIcons.get(d.icon) !== null;
    });
    const iconSize = Math.max(6, Math.min(28, 96 * 2 ** viewZoom));
    return [
      new IconLayer({
        id: 'resource-icons',
        data: items,
        getPosition: d => d.position,
        getIcon: d => resourceIcons.get(d.icon),
        getPixelOffset: d => [d.offset * (iconSize + 2), 0],
        updateTriggers: { getPixelOffset: iconSize },
        sizeUnits: 'common',
        getSize: 96,
        sizeMinPixels: 6,
        sizeMaxPixels: 28,
        textureParameters: PIXELATED,
        pickable: true,
      }),
    ];
  }

  function showBuildings() {
    return viewZoom >= overviewZoom(window.innerHeight) + BUILDING_ZOOM_STEPS;
  }

  function getLayers() {
    const chunkOpacity = chunkDetailOpacity(viewZoom, NATIVE_VIEW_ZOOM + zoomOffset);
    const showChunkDetail = chunkOpacity > 0;
    const territoryOpacity = zoomDetailOpacity(viewZoom, (mapMode === 'resources' ? -4 : -2) + zoomOffset);
    const fadeDetail = (layer, opacity) => layer?.clone({ opacity });
    const copies = visibleWorldCopies();
    const layers = [
      ...spatialOverlays.gridLayers(CHUNK_BORDER_COLOR)
        .map(layer => fadeDetail(layer, chunkOpacity)),
      ...spatialOverlays.outlineLayers(TERRITORY_BORDER_COLOR)
        .map(layer => fadeDetail(layer, territoryOpacity)),
      territoryRasterLayer(),
      showChunkDetail ? fadeDetail(homesLayer(), chunkOpacity) : null,
      ...(attacksLayer() || []),
      ...highlightLayers(),
      portRingLayer(),
      mapMode === 'resources' ? null : nationLabelsLayer(),
      showBuildings() ? buildingBackplatesLayer() : null,
      showBuildings() ? buildingsLayer() : null,
      mapMode !== 'resources' && showLandmarks()
        ? nationCapitalsLayer() : null,
      ...(mapMode !== 'resources' && viewZoom >= overviewZoom(window.innerHeight) + TOWN_ZOOM_STEPS
        ? townDetailLayers() : []),
      ...resourceLayers(),
      buildingLabelLayer(),
    ].filter(Boolean);
    // Preserve overlay order across all visible copies of the world.
    const copyProps = (layer, copy) => ({
      modelMatrix: new Matrix4().translate([copy * WORLD_WIDTH, 0, 0]),
      parameters: { ...layer.props.parameters, depthCompare: 'always', depthWriteEnabled: false },
    });
    return [
      ...worldLayers([satelliteOverviewLayer(earthOverview)], copies, copyProps),
      satelliteLayer(deck.props.useDevicePixels),
      ...worldLayers(layers, copies, copyProps),
    ];
  }

  // Keep north/south limits, but let the camera cross any number of worlds
  // horizontally. Normalizing only interactions avoids jumps during a drag.
  function clampTarget(vs) {
    vs = clampScreenZoom(vs, zoomViewportWidth,
      minimumViewZoom(zoomViewportWidth, window.innerHeight), VIEW_MAX_ZOOM);
    return clampMapTarget(vs, window.innerHeight);
  }

  // Controlled view state: clamping in onViewStateChange only takes effect if
  // deck.gl is told the resulting state (uncontrolled initialViewState ignores
  // the handler's return). Seed it already clamped so the first paint can't
  // start out of bounds either.
  let viewState = clampTarget({
    target: [(MAP_BOUNDS[0] + MAP_BOUNDS[2]) / 2,
      (MAP_BOUNDS[1] + MAP_BOUNDS[3]) / 2, 0],
    // Fill the viewport vertically without cropping the map's north/south edges.
    zoom: overviewZoom(window.innerHeight),
  });

  function makeView() {
    return new MapView({
      id: 'main',
      controller: { dragRotate: false, scrollZoom: { speed: SCROLL_ZOOM_SPEED, smooth: true } },
    });
  }

  function applyCameraState(useDevicePixels) {
    updateVisible();
    const copies = visibleWorldCopies();
    const current = deck.props.layers.filter(layer => layer.id.startsWith('satellite-overview-world-'));
    const copiesChanged = current.length !== copies.length
      || current.some((layer, i) => layer.id !== `satellite-overview-world-${copies[i]}`);
    // A seam crossing or a large zoom-out can expose a new world copy. Install
    // it together with the camera, before deck draws that frame. Ordinary pans
    // keep the existing background and batch the other layer updates as usual.
    deck.setProps({ viewState, useDevicePixels,
      ...(copiesChanged ? { layers: getLayers() } : {}),
    });
    updateHover();
    redraw();
  }

  // --- Deck instance ------------------------------------------------------
  const deck = new Deck({
    parent: document.getElementById('map'),
    useDevicePixels: window.devicePixelRatio || 1,
    views: makeView(),
    viewState,
    onInteractionStateChange: ({ isDragging, inTransition }) => {
      cameraInteracting = Boolean(isDragging || inTransition);
    },
    onViewStateChange: ({ viewState: next }) => {
      viewState = clampTarget(next);
      viewZoom = viewState.zoom;
      viewTarget = viewState.target;
      // Controlled mode: push the clamped state back so the pan/zoom that the
      // controller computed is actually applied (and bounded).
      applyCameraState(movingPixelRatio());
      ensureAttackAnim();
      return viewState;
    },
    onClick: (info, event) => {
      if (event?.srcEvent?.button === 2 || event?.rightButton) return;
      // Icons can extend beyond their chunk at their minimum screen size.
      // Their stored center also resolves clicks on wrapped world copies.
      const coordinate = info.layer?.id.startsWith('buildings-world-') && info.object
        ? info.object.center
        : info.layer?.id.startsWith('resource-icons-world-') && info.object
          ? info.object.position : info.coordinate;
      if (coordinate) selectAt(canonicalCoordinate(coordinate));
    },
    onHover: ({ x, y }) => {
      hoverPixel = x >= 0 && y >= 0 ? [x, y] : null;
      updateHover();
    },
    getTooltip: ({ object, layer }) => mapMode === 'resources'
      && layer?.id.startsWith('resource-icons-world-') && object?.tooltip
      ? { html: object.tooltip, className: 'resource-tooltip', style: {
        background: 'var(--panel)', color: 'var(--ink)',
        border: '1px solid var(--edge)', padding: '10px 12px',
        fontFamily: 'var(--font-family)', fontSize: '13px', lineHeight: '1.5',
        whiteSpace: 'normal', maxWidth: 'min(300px, calc(100vw - 24px))',
      } } : null,
    onAfterRender: () => {
      // The background can render long before the worker finishes. Wait for
      // the prepared scene and its textures/font atlases to render as well.
      // Detail imagery streams independently over the decoded overview.
      if (!loadingHidden && initialLayersSubmitted
          && deck.props.layers.every(layer => layer.id === 'satellite' || layer.isLoaded)) {
        hideLoading();
      }
    },
    layers: [],  // populated by the post-init updateVisible+redraw below
  });

  function updateHover() {
    const viewport = deck.getViewports()[0];
    const coordinate = hoverPixel && viewport
      ? canonicalCoordinate(viewport.unproject(hoverPixel)) : null;
    // Pick the rendered silhouette, including its minimum screen size and
    // wrapped world copies, rather than just the underlying chunk.
    const next = showBuildings() && hoverPixel
      ? deck.pickObject({ x: hoverPixel[0], y: hoverPixel[1],
        layerIds: deck.props.layers.filter(layer => layer.id.startsWith('buildings-world-'))
          .map(layer => layer.id),
      })?.object ?? null : null;
    if (next !== hoveredBuilding) {
      hoveredBuilding = next;
      redraw();
    }
    // Coords readout — pointermove fires at display rate, so guard against
    // touching the DOM when nothing changed. Only write to the two cached
    // text nodes when the integer block (or chunk) coord actually moves.
    // (`>> 4` is an arithmetic shift on int32 — equivalent to Math.floor(x/16)
    // including for negatives, no Math.floor call needed.)
    if (coordinate) {
      const bx = Math.floor(coordinate[0]);
      const bz = Math.floor(coordinate[1]);
      if (bx !== lastCoordBx || bz !== lastCoordBz) {
        coordsBlockText.nodeValue = `X ${bx}, Z ${bz}`;
        lastCoordBx = bx;
        lastCoordBz = bz;
        const cx = bx >> 4;
        const cz = bz >> 4;
        if (cx !== lastCoordCx || cz !== lastCoordCz) {
          coordsChunkText.nodeValue = `chunk ${cx}, ${cz}`;
          lastCoordCx = cx;
          lastCoordCz = cz;
        }
      }
      if (coordsEl.hidden) coordsEl.hidden = false;
    } else if (!coordsEl.hidden) {
      coordsEl.hidden = true;
    }
  }

  // Initial cull then first paint. Doing this after `new Deck` rather than via
  // `layers:` lets updateVisible read the actual canvas size. Seed
  // viewZoom/viewTarget from the (clamped) initial viewState first — otherwise
  // the first cull runs against the [0,0]/NATIVE_VIEW_ZOOM defaults instead of
  // the real starting camera, and the culled layers (outlines, chunk grid)
  // stay blank until the first pan fires onViewStateChange.
  viewZoom = viewState.zoom;
  viewTarget = viewState.target;
  updateVisible();
  redraw();

  document.getElementById('map').addEventListener('contextmenu', event => {
    event.preventDefault();
    const viewport = deck.getViewports()[0];
    if (!viewport) return;
    const bounds = deck.getCanvas().getBoundingClientRect();
    const coordinate = canonicalCoordinate(viewport.unproject([
      event.clientX - bounds.left, event.clientY - bounds.top,
    ]));
    const { hit } = resolveSelection(coordinate, index, buildingsByChunk);
    selectedCoordinate = coordinate;
    if (hit?.style.nation) {
      mapMode = 'relations';
      relationNation = hit.style.nation;
    }
    updateSelection();
  });
  window.addEventListener('keydown', event => {
    if (event.key === 'Escape') {
      mapMode = 'political';
      exitRelations();
    }
  });
  document.getElementById('map').addEventListener('mouseleave', () => {
    hoverPixel = null;
    updateHover();
  });
  document.getElementById('map').addEventListener('pointercancel', cancelCameraInteraction);
  window.addEventListener('blur', cancelCameraInteraction);

  // Preserve the current map width when resizing, including at either limit.
  // Recompute detail visibility as well: physical zoom can now be negative
  // at maximum zoom-in on a narrow display.
  window.addEventListener('resize', () => {
    const newWidth = window.innerWidth;
    const resized = resizeScreenZoom(viewState, zoomViewportWidth, newWidth,
      minimumViewZoom(newWidth, window.innerHeight), VIEW_MAX_ZOOM);
    zoomViewportWidth = newWidth;
    zoomOffset = screenZoomOffset(newWidth);
    viewState = clampTarget({ ...resized, transitionDuration: 0 });
    viewZoom = viewState.zoom;
    viewTarget = viewState.target;
    applyCameraState(window.devicePixelRatio || 1);
  });

  function redraw() {
    // Hover, attacks and camera events can all change in one frame.
    // Build and diff the scene once, using their latest state.
    if (redrawFrame !== null) return;
    redrawFrame = requestAnimationFrame(() => {
      redrawFrame = null;
      deck.setProps({ layers: getLayers() });
      if (dataReady) initialLayersSubmitted = true;
      ensureAttackAnim();
    });
  }

  // --- Periodic refresh --------------------------------------------------
  // Poll the three mutable files every 30s. Conditional GETs (If-None-Match /
  // If-Modified-Since) make the unchanged-case a single empty 304 per file,
  // so unchanged town data is not re-downloaded on every tick. world.json is
  // treated as static and never re-fetched.
  const REFRESH_INTERVAL_MS = 30_000;
  const REFRESH_FILES = ['towns', 'war', 'buildings'];
  // war.json may be 404 (no active war); buildings.json may be 404 too.
  // For those, a 404 transitions the in-memory copy to `null` exactly once.
  const REFRESH_OPTIONAL = new Set(['war', 'buildings']);

  async function fetchIfChanged(name) {
    const update = await fetchJsonUpdate(`nodes/${name}.json`, fileMeta[name], {
      optional: REFRESH_OPTIONAL.has(name),
    });
    if (!update) return null;
    fileMeta[name] = update.meta;
    if (update.unchanged || (update.body === null && data[name] === null)) return null;
    return update;
  }

  // Cheap, index-reusing re-eval of which attacks have reached their start
  // time. The visible set can change even when no file did; completed attacks
  // remain visible until war.json removes them. Returns true if the set changed
  // (the caller then invalidates the layer cache and redraws).
  // Compare rendering inputs, including timing changes from war.json. This
  // also runs on a 1s timer, so avoid allocating comparison strings.
  function sameAttacks(a, b) {
    if (a.length !== b.length) return false;
    for (let i = 0; i < a.length; i++) {
      const x = a[i], y = b[i];
      if (x.cx !== y.cx || x.cz !== y.cz
          || x.color[0] !== y.color[0] || x.color[1] !== y.color[1]
          || x.color[2] !== y.color[2] || x.s !== y.s || x.e !== y.e
          || x.defColor[0] !== y.defColor[0] || x.defColor[1] !== y.defColor[1]
          || x.defColor[2] !== y.defColor[2]) return false;
    }
    return true;
  }
  function refreshActiveAttacks() {
    const next = buildAttackChunks(data.war, data.towns, index);
    if (sameAttacks(next, attackChunks)) return false;
    attackChunks = next;
    attackAnimFrame++;
    const nextKeys = new Set(next.map((a) => packCoord(a.cx, a.cz)));
    if (nextKeys.size !== attackChunkKeys.size
        || [...nextKeys].some((key) => !attackChunkKeys.has(key))) {
      // Patch only changed mask texels. Timing and attacker colors never
      // regenerate the ownership texture.
      attackChunkKeys = nextKeys;
      attackMask?.update(attackChunks);
    }
    return true;
  }

  let refreshInFlight = false;
  async function checkForUpdates() {
    // setInterval can fire while a slow refresh is still resolving (esp. when
    // the tab was backgrounded and the runtime fires a backlog). Skip rather
    // than rebuild concurrently — concurrent rebuilds would race on the
    // shared mutable state.
    if (!dataReady || document.hidden || refreshInFlight) return;
    refreshInFlight = true;
    try {
      const updates = await Promise.all(REFRESH_FILES.map(fetchIfChanged));
      let dirty = false;
      const newData = { ...data };
      for (let i = 0; i < REFRESH_FILES.length; i++) {
        if (updates[i]) {
          newData[REFRESH_FILES[i]] = updates[i].body;
          dirty = true;
        }
      }
      if (!dirty) {
        // No file changed, but an attack may have reached its start time since
        // the last tick — re-evaluate cheaply rather than skip.
        if (refreshActiveAttacks()) redraw();
        return;
      }
      const rebuildTerritories = ownershipChanged(data, newData);
      const rebuildBuildings = data.buildings !== newData.buildings;
      const prepared = rebuildTerritories ? await preparation.call('prepare', {
        towns: newData.towns, war: newData.war, buildings: newData.buildings,
      }) : null;
      data = newData;
      if (rebuildTerritories) {
        installPrepared(prepared);
        attackAnimFrame++;
      } else {
        refreshActiveAttacks();
      }
      if (rebuildBuildings) buildBuildings();

      updateSelection();
      updateHover();

      // A new ownership index owns a fresh overlay cache.
      if (rebuildTerritories) updateVisible();
      redraw();
      // A changed war.json may have introduced new timed attacks — (re)start
      // the real-time fill loop if it isn't already running.
      ensureAttackAnim();
    } catch (error) {
      console.error('Map refresh failed:', error);
      // Retry a failed preparation even if the feed has not changed again.
      for (const name of REFRESH_FILES) delete fileMeta[name];
    } finally {
      refreshInFlight = false;
    }
  }

  setInterval(checkForUpdates, REFRESH_INTERVAL_MS);

  const attackAnimTimer = new AttackAnimationTimer(tickAttackAnim);
  function attackIsVisible(attack) {
    if (!visibleAttackBounds) return false;
    const x = wrapX(attack.cx * BLOCKS_PER_CHUNK, PAN_BOUNDS[0], PAN_BOUNDS[2]);
    const z = attack.cz * BLOCKS_PER_CHUNK;
    return z + BLOCKS_PER_CHUNK >= visibleAttackBounds.minZ && z <= visibleAttackBounds.maxZ
      && visibleAttackBounds.intervals.some(([left, right]) =>
        x + BLOCKS_PER_CHUNK >= left && x <= right);
  }

  function tickAttackAnim() {
    if (document.hidden) return;
    attackAnimFrame++;
    redraw();
  }

  function ensureAttackAnim() {
    const active = mapMode === 'relations' ? relationAttacks(attackChunks, displayIndex()) : attackChunks;
    const delay = document.hidden ? null : nextAttackFrame(active, Date.now() / 1000,
      BLOCKS_PER_CHUNK * 2 ** viewZoom, attackIsVisible);
    attackAnimTimer.schedule(delay);
  }

  // Membership still follows the live clock even when no fill needs a redraw.
  function refreshAttackState() {
    if (document.hidden) return;
    if (refreshActiveAttacks()) redraw();
    ensureAttackAnim();
  }
  setInterval(refreshAttackState, 1000);
  document.addEventListener('visibilitychange', () => {
    ensureAttackAnim();
    if (!document.hidden) {
      refreshAttackState();
      checkForUpdates();
    }
  });

  Promise.all([initialPrepared, fontsReady]).then(([result]) => {
    data = result.initial.data;
    Object.assign(fileMeta, result.initial.meta);
    installPrepared(result);
    buildBuildings();
    dataReady = true;
    updateSelection();
    updateHover();
    redraw();
  }).catch(error => {
    console.error('Map preparation failed:', error);
    if (loadingEl && !loadingHidden) {
      loadingEl.setAttribute('aria-label', 'Map could not be loaded');
      loadingEl.querySelector('span').textContent = 'Could not load map. Please reload to try again.';
    }
  });

  ensureAttackAnim();
}
