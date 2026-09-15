// Run from any directory with: node site/scripts/sync-vehicles.mjs
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { exportItemModel } from './export-item-model.mjs'
import { callArguments, definitions, displayName, namedArguments, number, splitArguments, string, stripComments } from './kotlin-source.mjs'

const site = fileURLToPath(new URL('../', import.meta.url))
const modules = join(site, '../modules')
const types = ['Car', 'Boat', 'Tank', 'Plane', 'Drone']
const catalogues = {}
const read = path => readFileSync(path, 'utf8')
const format = value => Number(value.toFixed(3)).toString()

function defaultsFor(type, source, iteration) {
  const imported = source.match(new RegExp(`import ([\\w.]+\\.${type})\\b`))?.[1]
  if (!imported) throw new Error(`Missing import for vehicle type ${type}`)
  const relative = `src/main/kotlin/${imported.replaceAll('.', '/')}.kt`
  const path = [join(iteration, relative), join(modules, 'combat', relative)].find(existsSync)
  if (!path) throw new Error(`Missing vehicle class source: ${imported}`)
  const code = stripComments(read(path))
  const match = code.match(new RegExp(`\\bclass ${type}\\s*\\(`))
  if (!match) throw new Error(`Missing constructor: ${imported}`)
  return namedArguments(callArguments(code, match.index + match[0].length - 1))
}

function constructorArgs(expression, type) {
  const match = expression?.match(new RegExp(`^${type}\\s*\\(`))
  if (!match) throw new Error(`Expected ${type} constructor: ${expression}`)
  return callArguments(expression, match[0].length - 1)
}

for (const entry of readdirSync(join(modules, 'iterations'), { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
  if (!entry.isDirectory() || entry.name.startsWith('.')) continue
  const iterationId = entry.name
  const iteration = join(modules, 'iterations', iterationId)
  const constants = join(iteration, 'src/main/kotlin/net/aechronis/server/constants')
  if (!existsSync(constants)) continue
  const sources = readdirSync(constants, { withFileTypes: true })
    .filter(entry => entry.isFile() && entry.name.endsWith('.kt')).sort((a, b) => a.name.localeCompare(b.name))
    .map(entry => read(join(constants, entry.name)))
  const vehicles = sources.flatMap(source => definitions(source, types).map(definition => ({ ...definition, source })))
  if (!vehicles.length) continue
  const ammoNames = new Map(sources.flatMap(source => definitions(source, ['Ammo']))
    .map(({ key, args }) => [`Ammo.${key}`, displayName(args.itemName)]))
  const guns = new Map(sources.flatMap(source => definitions(source, ['Gun'])).map(({ key, args }) => [`Guns.${key}`, args]))
  const output = join(site, 'src/public/iterations', iterationId, 'vehicles')
  rmSync(output, { recursive: true, force: true })
  mkdirSync(output, { recursive: true })
  const catalogue = []
  const ids = new Set()
  for (const { type, args, source } of vehicles) {
    const id = string(args.name, 'vehicle name')
    if (!/^[a-z0-9_-]+$/.test(id) || ids.has(id)) throw new Error(`Invalid or duplicate vehicle ID: ${iterationId}/${id}`)
    ids.add(id)
    const defaults = defaultsFor(type, source, iteration)
    const value = field => args[field] ?? defaults[field]
    const stat = field => number(value(field), `${id}/${field}`)
    const stats = []
    const add = (label, value) => stats.push({ label, value: String(value) })
    add('Type', type)
    const health = type === 'Drone' ? stat('health') : number(splitArguments(constructorArgs(args.health, 'Health'))[0], `${id}/health`)
    add('Health', health)
    add('Top speed', `${format(stat(type === 'Plane' ? 'speed' : 'maxSpeed') * 20)} blocks/s`)
    if (type !== 'Drone') {
      const seats = value(type === 'Plane' ? 'seatOffset' : 'seatOffsets')
      const seatCount = splitArguments(constructorArgs(seats, 'listOf')).length
      if (seatCount > 1) add('Seats', seatCount)
    }
    if (type === 'Car' || type === 'Tank') add('Maximum climb', `${format(stat('maxClimbHeight'))} blocks`)
    if (value('ammo')) {
      const ammo = ammoNames.get(value('ammo'))
      if (!ammo) throw new Error(`Unknown ammunition: ${value('ammo')}`)
      add('Ammunition', ammo)
      add('Capacity', stat('maxAmmo'))
    }
    if (type === 'Tank' || args.bomb) {
      let weapon = { ...defaults, ...args }
      if (args.bomb) {
        // Bomb defaults live alongside the Plane class.
        const planePath = join(modules, 'combat/src/main/kotlin/net/aechronis/combat/objects/Plane.kt')
        const code = stripComments(read(planePath))
        const match = code.match(/class PlaneBombWeapon\s*\(/)
        const bombDefaults = namedArguments(callArguments(code, match.index + match[0].length - 1))
        weapon = { ...bombDefaults, ...namedArguments(constructorArgs(args.bomb, 'PlaneBombWeapon')) }
      }
      const n = field => number(weapon[field], `${id}/${field}`)
      add('Armament', type === 'Tank' ? 'Cannon' : 'Bombs')
      add('Explosion damage', n('projectileExplosionDamage'))
      add('Explosion radius', `${format(n('projectileExplosionRadius'))} blocks`)
      add('Firing cooldown', `${format(n('fireCooldown') / 1000)} s`)
      add('Projectile range', `${format(n('projectileMaxRange'))} blocks`)
    }
    if (args.weapons) {
      for (const expression of splitArguments(constructorArgs(args.weapons, 'listOf'))) {
        const weapon = namedArguments(constructorArgs(expression, 'PlaneWeapon'))
        const gun = guns.get(weapon.gun)
        if (!gun) throw new Error(`Unknown mounted gun: ${weapon.gun}`)
        add('Armament', displayName(gun.itemName))
        const firingPoints = splitArguments(constructorArgs(weapon.firePoints, 'listOf')).length
        add('Gun damage', number(gun.damage, `${id}/gun damage`))
        add('Gun fire rate', `${firingPoints}× ${Math.round(60_000 / number(gun.cooldown, `${id}/gun cooldown`))} RPM`)
      }
    }
    if (type === 'Drone') {
      add('Control range', `${format(stat('maxRange'))} blocks`)
      add('Battery at full throttle', `${format(stat('batteryLifeTicks') / 20)} s`)
      if (args.projectileModel && args.projectileModel !== 'null') {
        add('Explosion damage', stat('explosionDamage'))
        add('Explosion radius', `${format(stat('explosionRadius'))} blocks`)
      }
    }
    const model = args.model ? string(args.model, `${id}/model`) : `aechronis:${id}`
    const [namespace, modelId] = model.split(':')
    const pack = join(iteration, 'resource-pack/assets', namespace)
    const meshes = exportItemModel(pack, modelId, output, id)
    if (type === 'Drone' && args.projectileModel && args.projectileModel !== 'null') {
      const [payloadNamespace, payloadId] = string(args.projectileModel, `${id}/payload`).split(':')
      const payload = exportItemModel(join(iteration, 'resource-pack/assets', payloadNamespace), payloadId, output, `${id}-payload`)
      const offset = splitArguments(constructorArgs(value('projectileMountOffset'), 'Vec')).map(v => number(v, `${id}/payload offset`))
      const scale = number(args.projectileScale ?? value('scale'), `${id}/payload scale`) / stat('scale')
      for (const mesh of payload) {
        mesh.positions = mesh.positions.map((v, i) => (v - 8) * scale + 8 + offset[i % 3] * 16 / stat('scale'))
        meshes.push(mesh)
      }
      writeFileSync(join(output, `${id}.json`), JSON.stringify({ meshes }))
    }
    catalogue.push({ id, name: displayName(args.itemName), stats, model: `/iterations/${iterationId}/vehicles/${id}.json` })
  }
  catalogues[iterationId] = catalogue
  const pagePath = join(site, 'src/iterations', iterationId, 'vehicles.md')
  mkdirSync(dirname(pagePath), { recursive: true })
  writeFileSync(pagePath, `<!-- Generated by scripts/sync-vehicles.mjs; edit the generator to update this page. -->
<script setup>
import VehicleCatalogue from '../../../.vitepress/theme/VehicleCatalogue.vue'
</script>

# Vehicles

<VehicleCatalogue iteration="${iterationId}" />
`)
  console.log(`Exported ${catalogue.length} vehicles and their current stats for ${iterationId}.`)
}
writeFileSync(join(site, '.vitepress/theme/vehicles.json'), JSON.stringify(catalogues, null, 2) + '\n')
