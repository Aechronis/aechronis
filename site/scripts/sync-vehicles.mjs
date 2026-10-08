// Run from any directory with: node site/scripts/sync-vehicles.mjs
import { existsSync, mkdirSync, readFileSync, readdirSync, rmSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'
import { fileURLToPath } from 'node:url'
import { exportItemModel } from './export-item-model.mjs'
import { callArguments, definitions, displayName, namedArguments, number, splitArguments, string, stripComments } from './kotlin-source.mjs'

const site = fileURLToPath(new URL('../', import.meta.url))
const modules = join(site, '../modules')
const types = ['Car', 'Boat', 'Tank', 'Plane', 'Drone', 'Cannon', 'AutomaticFieldPiece']
const fieldPieces = ['Cannon', 'AutomaticFieldPiece']
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

function modelScale(expression, source, label) {
  if (/^\w+$/.test(expression)) {
    const code = stripComments(source)
    const match = code.match(new RegExp(`\\bval\\s+${expression}\\s*=\\s*Vec\\s*\\(`))
    if (!match) throw new Error(`Missing model scale: ${expression}`)
    expression = `Vec(${callArguments(code, match.index + match[0].length - 1)})`
  }
  const axes = splitArguments(constructorArgs(expression, 'Vec')).map(value => number(value, label))
  if (axes.length !== 3 || axes.some(value => value <= 0)) throw new Error(`Invalid model scale: ${label}`)
  return axes
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
    // Field pieces may borrow a Gun declared beside them in the same object.
    const localGuns = new Map(definitions(source, ['Gun']).map(({ key, args }) => [key, args]))
    const mountedGun = type === 'AutomaticFieldPiece' ? localGuns.get(args.gun) ?? guns.get(args.gun) : null
    if (type === 'AutomaticFieldPiece' && !mountedGun) throw new Error(`Unknown mounted gun: ${args.gun}`)
    const itemName = args.itemName?.match(/^(\w+)\.itemName$/) ? localGuns.get(args.itemName.split('.')[0])?.itemName : args.itemName
    if (!/^[a-z0-9_-]+$/.test(id) || ids.has(id)) throw new Error(`Invalid or duplicate vehicle ID: ${iterationId}/${id}`)
    ids.add(id)
    const defaults = defaultsFor(type, source, iteration)
    const value = field => args[field] ?? defaults[field]
    const stat = field => number(value(field), `${id}/${field}`)
    const armament = type === 'Boat' && args.armament && args.armament !== 'null'
      ? { ...defaultsFor('BoatArmament', source, iteration), ...namedArguments(constructorArgs(args.armament, 'BoatArmament')) }
      : null
    const stats = []
    const add = (label, value) => stats.push({ label, value: String(value) })
    add('Type', fieldPieces.includes(type) ? 'Field piece' : armament ? 'Armed boat' : type)
    const health = type === 'Drone' ? stat('health') : number(splitArguments(constructorArgs(args.health, 'Health'))[0], `${id}/health`)
    add('Health', health)
    const speedField = type === 'Plane' ? 'speed' : fieldPieces.includes(type) ? 'moveSpeed' : 'maxSpeed'
    add('Top speed', `${format(stat(speedField) * 20)} blocks/s`)
    if (type !== 'Drone') {
      const seats = value(type === 'Plane' ? 'seatOffset' : 'seatOffsets')
      const seatCount = splitArguments(constructorArgs(seats, 'listOf')).length
      if (seatCount > 1) add('Seats', seatCount)
    }
    if (type === 'Car' || type === 'Tank') add('Maximum climb', `${format(stat('maxClimbHeight'))} blocks`)
    if (mountedGun) {
      const ammo = ammoNames.get(mountedGun.ammo)
      if (!ammo) throw new Error(`Unknown ammunition: ${mountedGun.ammo}`)
      add('Ammunition', ammo)
      add('Capacity', number(args.maxAmmo ?? mountedGun.maxAmmo, `${id}/maxAmmo`))
      add('Armament', displayName(mountedGun.itemName))
      add('Gun damage', number(mountedGun.damage, `${id}/gun damage`))
      add('Gun fire rate', `${Math.round(60_000 / number(mountedGun.cooldown, `${id}/gun cooldown`))} RPM`)
      add('Reload time', `${format(number(mountedGun.reloadTime, `${id}/reloadTime`) / 1000)} s`)
    } else if (armament) {
      const ammo = ammoNames.get(armament.ammo)
      if (!ammo) throw new Error(`Unknown ammunition: ${armament.ammo}`)
      add('Ammunition', ammo)
      add('Capacity per gun station', number(armament.maxAmmo, `${id}/maxAmmo`))
    } else if (value('ammo')) {
      const ammo = ammoNames.get(value('ammo'))
      if (!ammo) throw new Error(`Unknown ammunition: ${value('ammo')}`)
      add('Ammunition', ammo)
      add('Capacity', stat('maxAmmo'))
    }
    if (type === 'Tank' || type === 'Cannon' || args.bomb) {
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
      add('Armament', args.bomb ? 'Bombs' : 'Cannon')
      add('Explosion damage', n('projectileExplosionDamage'))
      add('Explosion radius', `${format(n('projectileExplosionRadius'))} blocks`)
      add('Reload time', `${format(n('reloadTime') / 1000)} s`)
      add('Projectile range', `${format(n('projectileMaxRange'))} blocks`)
    }
    if (armament) {
      const weapons = splitArguments(constructorArgs(armament.weapons, 'listOf')).map(expression => {
        const weapon = namedArguments(constructorArgs(expression, 'BoatWeapon'))
        return { ...weapon, count: splitArguments(constructorArgs(weapon.muzzleOffsets, 'listOf')).length }
      })
      add('Gun stations', weapons.length)
      const batteries = new Map()
      for (const weapon of weapons) {
        const kind = string(weapon.model, `${id}/weapon model`).endsWith('-turret') ? 'Main' : 'Light'
        const values = ['projectileExplosionDamage', 'projectileExplosionRadius', 'reloadTime', 'projectileMaxRange']
          .map(field => number(weapon[field], `${id}/${field}`))
        const key = JSON.stringify([kind, ...values])
        const battery = batteries.get(key) ?? { kind, values, count: 0 }
        battery.count += weapon.count
        batteries.set(key, battery)
      }
      add('Armament', [...batteries.values()].map(({ kind, count }) => `${count} ${kind.toLowerCase()} ${count === 1 ? 'gun' : 'guns'}`).join(' + '))
      for (const { kind, values: [damage, radius, reloadTime, range] } of batteries.values()) {
        const prefix = batteries.size > 1 ? `${kind} gun ` : ''
        const label = text => prefix ? prefix + text.toLowerCase() : text
        add(label('Explosion damage'), damage)
        add(label('Explosion radius'), `${format(radius)} ${radius === 1 ? 'block' : 'blocks'}`)
        add(label('Reload time'), `${format(reloadTime / 1000)} s`)
        add(label('Projectile range'), `${format(range)} blocks`)
      }
    } else if (args.weapons) {
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
    // Boats use separate moving displays in game. Their inventory model retains
    // the assembled ship so catalogue previews include every paddle and gun.
    const previewModel = type === 'Boat' ? args.itemModel : args.model
    const model = previewModel ? string(previewModel, `${id}/model`) : `aechronis:${id}`
    const [namespace, modelId] = model.split(':')
    const pack = join(iteration, 'resource-pack/assets', namespace)
    const meshes = exportItemModel(pack, modelId, output, id)
    if (type === 'Boat' && args.modelScale) {
      const axes = modelScale(args.modelScale, source, `${id}/modelScale`)
      const min = [Infinity, Infinity, Infinity], max = [-Infinity, -Infinity, -Infinity]
      for (const mesh of meshes) {
        mesh.positions = mesh.positions.map((value, i) => {
          const axis = i % 3
          const metres = (value - 8) * axes[axis] / 16
          min[axis] = Math.min(min[axis], metres)
          max[axis] = Math.max(max[axis], metres)
          // The viewer fits each model to its frame; retain the actual proportions.
          return (value - 8) * axes[axis] / axes[2] + 8
        })
      }
      add('Length', `${format(max[2] - min[2])} blocks`)
      add('Overall width', `${format(max[0] - min[0])} blocks`)
      writeFileSync(join(output, `${id}.json`), JSON.stringify({ meshes }))
    }
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
    catalogue.push({ id, name: displayName(itemName), stats, model: `/iterations/${iterationId}/vehicles/${id}.json` })
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
