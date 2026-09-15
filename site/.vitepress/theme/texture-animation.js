// Minecraft animation durations are ticks (50 ms). UVs always address one frame.
export function createTextureAnimation(texture, metadata) {
  const { width, height } = texture.image
  const frameWidth = metadata.width ?? (metadata.height ? width : Math.min(width, height))
  const frameHeight = metadata.height ?? (metadata.width ? height : Math.min(width, height))
  const columns = width / frameWidth, rows = height / frameHeight
  if (![frameWidth, frameHeight, columns, rows].every(n => Number.isInteger(n) && n > 0)) {
    throw new Error('Invalid animated texture frame dimensions')
  }
  const frames = (metadata.frames ?? Array.from({ length: columns * rows }, (_, i) => i)).map(frame => ({
    index: typeof frame === 'number' ? frame : frame.index,
    duration: (typeof frame === 'number' ? metadata.frametime ?? 1 : frame.time ?? metadata.frametime ?? 1) * 50,
  }))
  if (!frames.length || frames.some(frame => !Number.isInteger(frame.index) || frame.index < 0
    || frame.index >= columns * rows || !Number.isInteger(frame.duration / 50) || frame.duration <= 0)) {
    throw new Error('Invalid animated texture frames')
  }
  const duration = frames.reduce((sum, frame) => sum + frame.duration, 0)
  texture.repeat.set(1 / columns, 1 / rows)
  let previous = -1
  return elapsed => {
    let time = Math.max(0, elapsed) % duration
    let frame = frames[0]
    for (const candidate of frames) {
      frame = candidate
      if (time < frame.duration) break
      time -= frame.duration
    }
    if (frame.index === previous) return false
    previous = frame.index
    texture.offset.set((frame.index % columns) / columns, 1 - (Math.floor(frame.index / columns) + 1) / rows)
    return true
  }
}
