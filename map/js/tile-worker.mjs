import { reprojectTile } from './tile-projection.mjs';
self.onmessage = async ({ data: { id, args: { blob, tile } } }) => {
  let image;
  try {
    image = await createImageBitmap(blob);
    const result = reprojectTile(image, tile).transferToImageBitmap();
    self.postMessage({ id, result }, [result]);
  } catch (error) { self.postMessage({ id, error: error.message }); }
  finally { image?.close(); }
};
