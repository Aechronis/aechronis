import { reprojectTile } from './tile-projection.mjs';
// Stitch the 2x2 Bing tiles (row-major) into one Mercator image, then reproject.
self.onmessage = async ({ data: { id, args: { blobs, tile } } }) => {
  let images = [];
  try {
    images = await Promise.all(blobs.map(blob => createImageBitmap(blob)));
    const { width, height } = images[0];
    const mosaic = new OffscreenCanvas(width * 2, height * 2);
    const ctx = mosaic.getContext('2d');
    images.forEach((image, i) => ctx.drawImage(image, (i & 1) * width, (i >> 1) * height));
    const result = reprojectTile(mosaic, tile).transferToImageBitmap();
    self.postMessage({ id, result }, [result]);
  } catch (error) { self.postMessage({ id, error: error.message }); }
  finally { for (const image of images) image.close(); }
};
