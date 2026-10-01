// Deck transfers GPU state by ID. Fresh descriptors avoid retaining finalized
// instances when a world copy leaves the viewport and later returns.
export function worldLayers(layers, copies, propsForCopy) {
  return layers.flatMap(layer => copies.map(copy => layer.clone({
    ...propsForCopy(layer, copy), id: `${layer.id}-world-${copy}`,
  })));
}
