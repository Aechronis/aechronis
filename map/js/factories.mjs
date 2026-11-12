// Producing factories from buildings.json; idle active buildings are left out.
export function producingFactories(buildings) {
  return buildings
    .filter(entry => entry.data.type === 'active' && entry.data.production?.endsAt != null)
    .sort((a, b) => a.data.production.endsAt - b.data.production.endsAt);
}

export function factoryItem(entry) {
  const production = entry.data.production;
  return production.item || production.recipe.replace(/[-_]/g, ' ');
}

export function factoryTimeLeft(endsAt, now = Date.now()) {
  const seconds = Math.floor((endsAt - now) / 1000);
  if (seconds <= 0) return 'finishing';
  const pad = value => String(value).padStart(2, '0');
  const hours = Math.floor(seconds / 3600);
  const minutes = Math.floor(seconds % 3600 / 60);
  return hours > 0 ? `${hours}h ${pad(minutes)}m left` : `${minutes}m ${pad(seconds % 60)}s left`;
}
