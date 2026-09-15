// Run from any directory with: node site/scripts/sync-weapons.mjs
// Export only static weapon geometry; authoring helpers and animations stay in Blockbench.
import { readFileSync, writeFileSync, mkdirSync, readdirSync, rmSync, existsSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import { join } from 'node:path'
import { exportItemModel } from './export-item-model.mjs'

const site = fileURLToPath(new URL('../', import.meta.url))
const iterations = join(site, '../modules/iterations')
const catalogues = {}
for (const entry of readdirSync(iterations, { withFileTypes: true }).sort((a, b) => a.name.localeCompare(b.name))) {
  if (!entry.isDirectory() || entry.name.startsWith('.')) continue
  const iterationId = entry.name
  const iteration = join(iterations, iterationId)
  const definitionsPath = join(iteration, 'src/main/kotlin/net/aechronis/server/constants/Guns.kt')
  const meleesPath = join(iteration, 'src/main/kotlin/net/aechronis/server/constants/Melees.kt')
  if (!existsSync(definitionsPath) && !existsSync(meleesPath)) continue
  const output = join(site, 'src/public/iterations', iterationId, 'weapons')
  // This directory contains only generated exports. Remove obsolete weapon assets too.
  rmSync(output, { recursive: true, force: true })
  mkdirSync(output, { recursive: true })
  const guns = existsSync(definitionsPath) ? readFileSync(definitionsPath, 'utf8') : ''
  const definitions = new Map([...guns.matchAll(/val (\w+)\s*=\s*Gun\(([\s\S]*?)\n        \)/g)]
    .map(([, key, body]) => [body.match(/name = "([^"]+)"/)[1], { key, body }]))
  const weapons = []
  const models = join(iteration, 'models')
  for (const file of (existsSync(models) ? readdirSync(models, { withFileTypes: true }) : [])
    .filter(entry => entry.isFile() && entry.name.endsWith('.bbmodel')).map(entry => entry.name).sort()) {
    const id = file.slice(0, -8)
    const definition = definitions.get(id)
    if (!definition) throw new Error(`No gun definition for ${file}`)
    const { body } = definition
    const value = (field, fallback) => {
      const match = body.match(new RegExp(`\\b${field} = ([^,\\n]+)`))
      if (!match && fallback !== undefined) return String(fallback)
      if (!match) throw new Error(`Missing ${field} for ${id}`)
      return match[1]
    }
    const stat = (field, fallback) => {
      const number = Number(value(field, fallback).trim().replace(/[FfLl]$/, ''))
      if (!Number.isFinite(number)) throw new Error(`Unsupported ${field} for ${iterationId}/${id}`)
      return number
    }
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
    weapons.push({ id, name: body.match(/itemName = Component.text\("([^"]+)"/)[1],
      ammo: { 'Ammo.ammo762x39mm': '7.62×39 mm', 'Ammo.ammo9mm': '9 mm', 'Ammo.rocket': 'Rocket' }[value('ammo')],
      magazine: Number(value('maxAmmo')), damage: Number(value('damage').replace('F', '')),
      mode: value('automatic') === 'true' ? 'Automatic' : 'Single shot',
      reload: Number(value('reloadTime')) / 1000,
      rpm: Math.round(60_000 / stat('cooldown')),
      recoilMin: stat('recoilMin'), recoilMax: stat('recoilMax'),
      spreadMin: stat('spreadMin'), spreadMax: stat('spreadMax'),
      maxRange: stat('maxRange', 128.0),
    })
  }
  const pack = join(iteration, 'resource-pack/assets/aechronis')
  const melees = existsSync(meleesPath) ? readFileSync(meleesPath, 'utf8') : ''
  for (const [, key, body] of melees.matchAll(/val (\w+)\s*=\s*Melee\(([\s\S]*?)\n\s*\)/g)) {
    const id = body.match(/\bname\s*=\s*"([^"]+)"/)?.[1]
    const name = body.match(/\bitemName\s*=\s*Component.text\("([^"]+)"/)?.[1]
    if (!id || !name) throw new Error(`Missing melee name for ${iterationId}/${key}`)
    if (weapons.some(weapon => weapon.id === id)) throw new Error(`Duplicate weapon ID: ${iterationId}/${id}`)
    const stat = (field, fallback) => {
      const value = body.match(new RegExp(`\\b${field}\\s*=\\s*([^,\\n]+)`))?.[1]
      if (value === undefined && fallback !== undefined) return fallback
      const number = value === undefined ? NaN : Number(value)
      if (!Number.isFinite(number)) throw new Error(`Missing or unsupported ${field} for ${iterationId}/${id}`)
      return number
    }
    exportItemModel(pack, id, output)
    // Match Melee's defaults when the definition omits them.
    weapons.push({ id, name, damage: stat('damage'), attackSpeed: stat('attackSpeed', 4.0), knockback: stat('knockback', 0.4) })
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
