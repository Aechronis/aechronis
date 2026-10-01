// Town positions follow the spawn, falling back to the home core.
function townPosition(town, world) {
  if (!town) return null;
  const spawn = town.spawn;
  const core = world?.territories?.[town.home]?.core;
  const position = Array.isArray(spawn) && Number.isFinite(spawn[0]) && Number.isFinite(spawn[2])
    ? [spawn[0], spawn[2]] : core?.slice(0, 2);
  return position?.length === 2 && position.every(Number.isFinite) ? position : null;
}

export function buildNationCapitals(towns, world) {
  return Object.entries(towns?.nations || {}).flatMap(([nation, info]) => {
    const position = townPosition(towns.towns?.[info.capital], world);
    if (!position) return [];
    return [{ nation, town: info.capital, position }];
  });
}

export function buildOtherTowns(towns, world) {
  const capitals = new Set(Object.values(towns?.nations || {}).map(info => info.capital));
  return Object.entries(towns?.towns || {}).flatMap(([name, town]) => {
    if (capitals.has(name)) return [];
    const position = townPosition(town, world);
    return position ? [{ town: name, position }] : [];
  });
}
