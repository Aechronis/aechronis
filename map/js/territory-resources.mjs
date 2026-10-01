function title(name) {
  return String(name).replace(/[_-]/g, ' ').replace(/\b\w/g, c => c.toUpperCase());
}

export function territoryResourceKeys(territory, definitions = {}) {
  return [...new Set(territory?.nodes || [])].filter(key => {
    const node = definitions[key];
    // Tier, warzone and wasteland nodes describe rules rather than deposits.
    return node && ['income', 'ore'].some(field =>
      Object.values(node[field] || {}).some(amount => Number(amount) > 0));
  });
}

export function resourceTooltip(keys, definitions = {}) {
  const number = value => Number(Number(value).toFixed(4)).toString();
  const escape = value => String(value).replace(/[&<>"']/g, c =>
    ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[c]));
  const section = (heading, entries, format) => entries.length
    ? `<section class="resource-tooltip-section"><div class="resource-tooltip-heading">${heading}</div><dl>${entries.map(([item, value]) =>
      `<dt>${escape(title(item))}</dt><dd>${format(value)}</dd>`).join('')}</dl></section>` : '';
  return keys.map(key => {
    const node = definitions[key];
    if (!node) return '';
    const name = key === 'potatos' ? 'Potatoes' : title(node.name || key);
    const income = Object.entries(node.income || {}).filter(([, value]) => Number(value) > 0);
    const ores = Object.entries(node.ore || {}).filter(([, value]) => Number(value) > 0);
    return `<div class="resource-tooltip-resource"><strong class="resource-tooltip-name">${escape(name)}</strong>`
      + section('Base income / hour', income, number)
      + section('Base ore drop chance', ores, value => `${number(Number(value) * 100)}%`)
      + '</div>';
  }).filter(Boolean).join('');
}

export function buildTerritoryResources(world) {
  return Object.entries(world?.territories || {}).flatMap(([id, territory]) => {
    const position = territory.core?.slice(0, 2)
      || (territory.chunks?.length >= 2
        ? territory.chunks.slice(0, 2).map(n => n * 16 + 8) : null);
    if (!position || !position.every(Number.isFinite)) return [];
    const keys = territoryResourceKeys(territory, world.nodes);
    const icons = [...new Set(keys.map(key => world.nodes[key].icon).filter(Boolean))];
    return icons.map((icon, i) => ({ id, position, icon, offset: i - (icons.length - 1) / 2,
      tooltip: resourceTooltip(keys.filter(key => world.nodes[key].icon === icon), world.nodes),
    }));
  });
}

export const resourceIconUrl = icon => `nodes/resources/${encodeURIComponent(icon)}.png`;
