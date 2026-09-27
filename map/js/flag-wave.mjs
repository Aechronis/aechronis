const PADDING = 12;

export function flagWavePoint(fraction, width, height, time) {
  const amplitude = fraction ** 1.2;
  const phase = 2 * Math.PI * (time / 2400 + fraction * 0.75);
  const y = -Math.cos(phase) * amplitude * 5;
  const z = Math.sin(phase) * amplitude * 18;
  const perspective = 450 / (450 - z);
  return {
    x: fraction * width * perspective,
    top: height / 2 + (y - height / 2) * perspective,
    bottom: height / 2 + (y + height / 2) * perspective,
    shade: 0.1 + 0.2 * Math.sin(phase),
  };
}

function weatheredTexture(image) {
  const texture = document.createElement('canvas');
  texture.width = image.naturalWidth;
  texture.height = image.naturalHeight;
  const context = texture.getContext('2d');
  context.drawImage(image, 0, 0);
  context.scale(texture.width, texture.height);
  for (const [x, y, radius, color] of [
    [0.83, 0.78, 0.38, '#49351c88'], [0.27, 0.18, 0.3, '#59452666'],
    [0.58, 0.55, 0.23, '#48382044'], [0.96, 0.32, 0.26, '#392b1c66'],
  ]) {
    const stain = context.createRadialGradient(x, y, 0, x, y, radius);
    stain.addColorStop(0, color);
    stain.addColorStop(1, 'transparent');
    context.fillStyle = stain;
    context.fillRect(0, 0, 1, 1);
  }
  const hem = context.createLinearGradient(0, 0, 0, 1);
  for (const [offset, color] of [[0, '#49351c66'], [0.2, 'transparent'],
    [0.81, 'transparent'], [1, '#49351c77']]) hem.addColorStop(offset, color);
  context.fillStyle = hem;
  context.fillRect(0, 0, 1, 1);
  return texture;
}

// Rasterize the projected surface into adjacent *device-pixel* columns on one
// canvas. There are no separately composited elements or antialiased mesh seams.
export function drawFlagSurface(context, texture, width, height, time, ratio) {
  const end = flagWavePoint(1, width, height, time).x * ratio;
  context.clearRect(0, 0, context.canvas.width, context.canvas.height);
  let fraction = 0;
  let left = flagWavePoint(0, width, height, time);
  let right = flagWavePoint(1 / 128, width, height, time);
  for (let pixel = 0; pixel < Math.ceil(end); pixel++) {
    const x = Math.min((pixel + 0.5) / ratio, end / ratio);
    while (x > right.x && fraction < 127) {
      fraction++;
      left = right;
      right = flagWavePoint((fraction + 1) / 128, width, height, time);
    }
    const mix = Math.max(0, Math.min(1, (x - left.x) / (right.x - left.x)));
    const u = (fraction + mix) / 128;
    const top = left.top + (right.top - left.top) * mix;
    const bottom = left.bottom + (right.bottom - left.bottom) * mix;
    const sampleWidth = Math.min(texture.width, texture.width / (128 * (right.x - left.x) * ratio));
    const sourceX = Math.max(0, Math.min(texture.width - sampleWidth, u * texture.width - sampleWidth / 2));
    const y = (PADDING + top) * ratio;
    const h = (bottom - top) * ratio;
    context.drawImage(texture, sourceX, 0, sampleWidth, texture.height, pixel, y, 1, h);
    const shade = left.shade + (right.shade - left.shade) * mix;
    context.fillStyle = shade < 0 ? `rgba(255,247,225,${-shade})` : `rgba(32,24,14,${shade})`;
    context.fillRect(pixel, y, 1, h);
  }
}

export function animateFlagWave(cloth) {
  const image = cloth.querySelector('.nation-flag');
  const texture = weatheredTexture(image);
  const canvas = document.createElement('canvas');
  canvas.className = 'nation-flag-surface';
  canvas.setAttribute('aria-hidden', 'true');
  const context = canvas.getContext('2d');
  cloth.append(canvas);
  cloth.classList.add('is-waving');
  const reducedMotion = matchMedia('(prefers-reduced-motion: reduce)');
  let previousTime, previousWidth, previousHeight, previousRatio;
  function frame() {
    if (!cloth.isConnected) return;
    const time = reducedMotion.matches ? 0 : document.timeline.currentTime ?? 0;
    const { width, height } = image.getBoundingClientRect();
    const ratio = Math.min(devicePixelRatio || 1, 3);
    if (width > 0 && height > 0 && (time !== previousTime || width !== previousWidth
      || height !== previousHeight || ratio !== previousRatio)) {
      if (width !== previousWidth || height !== previousHeight || ratio !== previousRatio) {
        canvas.width = Math.ceil((width + PADDING) * ratio);
        canvas.height = Math.ceil((height + PADDING * 2) * ratio);
        canvas.style.width = `${canvas.width / ratio}px`;
        canvas.style.height = `${canvas.height / ratio}px`;
      }
      drawFlagSurface(context, texture, width, height, time, ratio);
      previousTime = time;
      previousWidth = width;
      previousHeight = height;
      previousRatio = ratio;
    }
    requestAnimationFrame(frame);
  }
  frame();
}
