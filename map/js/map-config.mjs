// World coordinates in Minecraft blocks, shared by navigation and wrapping.
export const MAP_BOUNDS = [-26880, -12289, 26880, 12290];
export const WORLD_WIDTH = MAP_BOUNDS[2] - MAP_BOUNDS[0];

export const TERRITORY_BORDER_STYLE = {
  getColor: [255, 240, 160, 255],
  getWidth: 2,
  widthUnits: 'common',
  widthScale: 4,
};
