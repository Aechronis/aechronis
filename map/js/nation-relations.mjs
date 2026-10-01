// Matches Nodes DiplomaticRelationship and Nation.areEnemies.
export const RELATION_COLORS = {
  nation: [0, 170, 0], ally: [0, 170, 170],
  neutral: [255, 170, 0], enemy: [255, 85, 85],
};

export function nationRelationship(nations, source, target, deathWar = false) {
  if (!source || !target || !nations[source] || !nations[target]) return 'neutral';
  if (source === target) return 'nation';
  const from = nations[source], to = nations[target];
  if (from.allies?.includes(target)) return 'ally';
  // A reverse alliance also prevents hostility, as in Nation.areEnemies.
  if (!to.allies?.includes(source)
      && (deathWar || from.enemies?.includes(target) || to.enemies?.includes(source))) return 'enemy';
  return 'neutral';
}

export function relationIndex(index, nations, source, deathWar) {
  const style = original => ({ ...original, color: original.nation
    ? RELATION_COLORS[nationRelationship(nations, source, original.nation, deathWar)]
    : [128, 128, 128] });
  const owner = Object.assign(Object.create(Object.getPrototypeOf(index.owner)), index.owner, {
    records: index.owner.records.map(record => record && ({ ...record, style: style(record.style) })),
  });
  return { ...index, owner,
    townStyle: new Map([...index.townStyle || []].map(([name, value]) => [name, style(value)])),
  };
}
