export function attackProgress(start, end, nowSec) {
  if (!Number.isFinite(start) || !Number.isFinite(end)) return 0;
  if (end <= start) return 1;
  return Math.max(0, Math.min(1, (nowSec - start) / (end - start)));
}

// Wake when a visible fill has moved a quarter CSS pixel, or reaches its end.
// A long capture can otherwise retessellate and redraw map thousands of
// times before its edge advances even one pixel. `frac` is the last drawn fill.
export function nextAttackFrame(attacks, nowSec, pixelsPerChunk, isVisible) {
  const step = 0.25 / Math.max(Number.EPSILON, pixelsPerChunk);
  let delay = Infinity;
  for (const attack of attacks) {
    if (!isVisible(attack)) continue;
    const progress = attackProgress(attack.s, attack.e, nowSec);
    if (!Number.isFinite(attack.frac)
        || Math.abs(progress - attack.frac) >= step
        || (progress === 1 && attack.frac !== 1)) return 0;
    if (progress < 1 && Number.isFinite(attack.s) && Number.isFinite(attack.e)) {
      const next = Math.min(1, attack.frac + step);
      delay = Math.min(delay, (attack.s + next * (attack.e - attack.s) - nowSec) * 1000);
    }
  }
  return Number.isFinite(delay) ? Math.max(0, delay) : null;
}

export class AttackAnimationTimer {
  constructor(callback) {
    this.callback = callback;
    this.timer = null;
    this.deadline = Infinity;
  }

  schedule(delay) {
    if (delay === null) {
      clearTimeout(this.timer);
      this.timer = null;
      this.deadline = Infinity;
      return;
    }
    const wait = Math.min(2_147_483_647, Math.max(16, delay));
    const deadline = performance.now() + wait;
    // Camera events can arrive faster than this timeout. Keep an earlier
    // wakeup so continuous movement cannot postpone a due fill indefinitely.
    if (this.timer !== null && this.deadline <= deadline) return;
    clearTimeout(this.timer);
    this.deadline = deadline;
    this.timer = setTimeout(() => {
      this.timer = null;
      this.deadline = Infinity;
      this.callback();
    }, wait);
  }
}
