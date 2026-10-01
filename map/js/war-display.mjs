import { packCoord } from './data-index.mjs';

// Start/end timestamps come from the server; never infer elapsed time from polling.
export function buildAttackChunks(war, towns, index, nowSec = Date.now() / 1000) {
  const out = [];
  if (!war || !Array.isArray(war.attacks)) return out;
  const residents = (towns && towns.residents) || {};
  for (const a of war.attacks) {
    if (!a || !a.c) continue;
    if (typeof a.s === 'number' && nowSec < a.s) continue;
    const [cx, cz] = a.c;
    const hit = index.owner.get(packCoord(cx, cz));
    if (!hit) continue;
    const attacker = a.id && residents[a.id];
    const attackerStyle = attacker && attacker.town
      && index.townStyle.get(attacker.town);
    out.push({
      cx,
      cz,
      // Attacker colour fills the captured (left) part; the defender's owner
      // colour fills the not-yet-captured (right) part.
      color: attackerStyle ? attackerStyle.color : hit.style.color,
      defColor: hit.style.color,
      s: typeof a.s === 'number' ? a.s : null,
      e: typeof a.e === 'number' ? a.e : typeof a.t === 'number' ? a.t : null,
    });
  }
  return out;
}
