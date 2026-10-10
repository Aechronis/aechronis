// Run from any directory with: node site/scripts/sync-weapons.mjs
// Export only static weapon geometry; authoring helpers and animations stay in Blockbench.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { exportItemModel } from './export-item-model.mjs'
import { boolean, displayName, number, string } from '../../tools/kotlin-source.cjs'
import { ammunitionNames, defaultsFor, readConstants } from '../../tools/combat-source.cjs'

const site = fileURLToPath(new URL('../', import.meta.url))
const iterations = join(site, '../modules/iterations')
const catalogues = {}
for (const entry of readdirSync(iterations, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
  if (!entry.isDirectory() || entry.name.startsWith('.')) continue
  const iterationId = entry.name
  const iteration = join(iterations, iterationId)
  const constants = readConstants(iteration, ['Gun', 'Melee', 'Ammo'])
  const guns = constants.filter(({ type, owner }) => type === 'Gun' && owner === 'Guns')
  const melees = constants.filter(({ type }) => type === 'Melee')
  if (!guns.length && !melees.length) continue
  const ammoNames = ammunitionNames(constants)
  const gunDefinitions = new Map(guns.map(definition => [string(definition.args.name, 'gun name'), definition]))
  const output = join(site, 'src/public/iterations', iterationId, 'weapons')
  // This directory contains only generated exports. Remove obsolete weapon assets too.
  rmSync(output, { recursive: true, force: true })
  mkdirSync(output, { recursive: true })
  const weapons = []
  const models = join(iteration, 'models')
  for (const file of (existsSync(models) ? readdirSync(models, { withFileTypes: true }) : [])
    .filter(entry => entry.isFile() && entry.name.endsWith('.bbmodel')).map(entry => entry.name).sort()) {
    const id = file.slice(0, -8)
    const definition = gunDefinitions.get(id)
    if (!definition) throw new Error(`No gun definition for ${file}`)
    const args = { ...defaultsFor('Gun', definition.source, iteration), ...definition.args }
    const stat = field => number(args[field], `${iterationId}/${id}/${field}`)
    const ammo = ammoNames.get(args.ammo)
    if (!ammo) throw new Error(`Unknown ammunition for ${iterationId}/${id}: ${args.ammo}`)
    const model = JSON.parse(readFileSync(join(iteration, 'models', file), 'utf8'))
    const textures = model.textures.map((texture, index) => {
      const filename = `${id}-${index}.png`
      writeFileSync(join(output, filename), Buffer.from(texture.source.split(',')[1], 'base64'))
      return { ...texture, filename }
    })
    // Current authored meshes are already in model space. Fail if this changes.
    for (const node of [...model.groups, ...model.elements.filter(e => e.type === 'mesh')]) {
      if (node.rotation?.some(n => n !== 0)) throw new Error(`Unsupported static rotation in ${file}: ${node.name}`)
    }
    const meshes = textures.map(texture => {
      const positions = [], uvs = []
      for (const element of model.elements.filter(e => e.type === 'mesh' && e.visibility !== false)) {
        for (const face of Object.values(element.faces)) {
          if (face.texture !== texture.uuid) continue
          if (![3, 4].includes(face.vertices.length)) throw new Error(`Unsupported polygon in ${file}`)
          const vertices = face.vertices
          const triangles = vertices.length === 3 ? [0, 1, 2] : [0, 1, 2, 0, 2, 3]
          for (const index of triangles) {
            const key = vertices[index]
            positions.push(...element.vertices[key].map(n => Number(n.toFixed(6))))
            const [u, v] = face.uv[key]
            uvs.push(u / texture.uv_width, 1 - v / texture.uv_height)
          }
        }
      }
      if (!positions.length) throw new Error(`Empty texture geometry in ${file}`)
      return { texture: texture.filename, positions, uvs }
    })
    writeFileSync(join(output, `${id}.json`), JSON.stringify({ meshes }))
    weapons.push({ id, name: displayName(args.itemName), ammo,
      magazine: stat('maxAmmo'), damage: stat('damage'),
      mode: boolean(args.automatic, `${id}/automatic`) ? 'Automatic' : 'Single shot',
      reload: stat('reloadTime') / 1000,
      rpm: Math.round(60_000 / stat('cooldown')),
      recoilMin: stat('recoilMin'), recoilMax: stat('recoilMax'),
      spreadMin: stat('spreadMin'), spreadMax: stat('spreadMax'),
      maxRange: stat('maxRange'),
    })
  }
  const pack = join(iteration, 'resource-pack/assets/aechronis')
  for (const definition of melees) {
    const args = { ...defaultsFor('Melee', definition.source, iteration), ...definition.args }
    const id = string(args.name, 'melee name')
    const name = displayName(args.itemName)
    if (weapons.some(weapon => weapon.id === id)) throw new Error(`Duplicate weapon ID: ${iterationId}/${id}`)
    const stat = field => number(args[field], `${iterationId}/${id}/${field}`)
    exportItemModel(pack, id, output)
    weapons.push({ id, name, damage: stat('damage'), attackSpeed: stat('attackSpeed'), knockback: stat('knockback') })
  }
  catalogues[iterationId] = weapons.map(weapon => ({
    ...weapon, model: `/iterations/${iterationId}/weapons/${weapon.id}.json`,
  }))
  const page = `<!-- Generated by scripts/sync-weapons.mjs; edit the generator to update this page. -->
<script setup>
import WeaponCatalogue from '../../../.vitepress/theme/WeaponCatalogue.vue'
</script>

# Weapons

<WeaponCatalogue iteration="${iterationId}" />

`
  const pages = join(site, 'src/iterations', iterationId)
  mkdirSync(pages, { recursive: true })
  writeFileSync(join(pages, 'weapons.md'), page)
  console.log(`Exported ${weapons.length} weapon models and their current stats for ${iterationId}.`)
}
writeFileSync(join(site, '.vitepress/theme/weapons.json'), JSON.stringify(catalogues, null, 2) + '\n')
