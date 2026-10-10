'use strict';

const { existsSync, readFileSync, readdirSync } = require('node:fs');
const { basename, join, resolve } = require('node:path');
const { constructorDefaults, definitions, displayName, stripComments } = require('./kotlin-source.cjs');

function readConstants(iteration, types) {
  const directory = join(iteration, 'src/main/kotlin/net/aechronis/server/constants');
  if (!existsSync(directory)) return [];
  return readdirSync(directory, { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.kt')).sort((a, b) => a.name.localeCompare(b.name))
    .flatMap(entry => {
      const source = readFileSync(join(directory, entry.name), 'utf8');
      return definitions(source, types).map(definition => ({ ...definition, source, owner: basename(entry.name, '.kt') }));
    });
}

function defaultsFor(type, source, iteration) {
  const imported = stripComments(source).match(new RegExp(`^import\\s+([\\w.]+\\.${type})\\s*$`, 'm'))?.[1];
  if (!imported) throw new Error(`Missing import for type ${type}`);
  const relative = `src/main/kotlin/${imported.replaceAll('.', '/')}.kt`;
  const file = [join(iteration, relative), join(resolve(iteration, '../..'), 'combat', relative)].find(existsSync);
  if (!file) throw new Error(`Missing class source: ${imported}`);
  return constructorDefaults(readFileSync(file, 'utf8'), type);
}

function ammunitionNames(constants) {
  return new Map(constants.filter(({ type }) => type === 'Ammo')
    .map(({ owner, key, args }) => [`${owner}.${key}`, displayName(args.itemName)]));
}

module.exports = { readConstants, defaultsFor, ammunitionNames };
