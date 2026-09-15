import { readFileSync, copyFileSync, writeFileSync, existsSync } from 'node:fs'
import { join } from 'node:path'
import { Euler, Vector3 } from 'three'

// Minecraft cuboids use 0..16 UV coordinates, independent of texture resolution.
export function exportItemModel(pack, id, output, outputId = id) {
  const model = JSON.parse(readFileSync(join(pack, `models/item/${id}.json`), 'utf8'))
  const meshes = []
  for (const [key, path] of Object.entries(model.textures).filter(([key]) => key !== 'particle')) {
    const texture = `${outputId}-${key}.png`
    const source = join(pack, `textures/${path.split(':')[1]}.png`)
    copyFileSync(source, join(output, texture))
    const animation = existsSync(`${source}.mcmeta`)
      ? JSON.parse(readFileSync(`${source}.mcmeta`, 'utf8')).animation : undefined
    const positions = [], uvs = []
    for (const element of model.elements) {
      const [x, y, z] = element.from, [X, Y, Z] = element.to
      const corners = {
        north: [[X,Y,z], [X,y,z], [x,y,z], [x,Y,z]],
        south: [[x,Y,Z], [x,y,Z], [X,y,Z], [X,Y,Z]],
        east: [[X,Y,Z], [X,y,Z], [X,y,z], [X,Y,z]],
        west: [[x,Y,z], [x,y,z], [x,y,Z], [x,Y,Z]],
        up: [[x,Y,z], [x,Y,Z], [X,Y,Z], [X,Y,z]],
        down: [[x,y,Z], [x,y,z], [X,y,z], [X,y,Z]],
      }
      for (const [side, face] of Object.entries(element.faces)) {
        if (face.texture !== `#${key}`) continue
        const [u,v,U,V] = face.uv
        const uv = [[u,v], [u,V], [U,V], [U,v]]
        const uvRotation = face.rotation ?? 0
        if (![0, 90, 180, 270].includes(uvRotation)) throw new Error(`Unsupported UV rotation: ${id}`)
        for (const i of [0,1,2,0,2,3]) {
          const point = new Vector3(...corners[side][i])
          if (element.rotation?.angle) {
            const { origin, axis, angle, rescale } = element.rotation
            if (rescale) throw new Error(`Unsupported rescale: ${id}`)
            const pivot = new Vector3(...origin)
            const direction = new Vector3(axis === 'x' ? 1 : 0, axis === 'y' ? 1 : 0, axis === 'z' ? 1 : 0)
            point.sub(pivot).applyAxisAngle(direction, angle * Math.PI / 180).add(pivot)
          } else if (element.rotation && ['x', 'y', 'z'].some(axis => element.rotation[axis])) {
            const { origin, x = 0, y = 0, z = 0 } = element.rotation
            const pivot = new Vector3(...origin)
            // Minecraft CuboidRotation applies X, then Y, then Z to vertices.
            // Three's intrinsic Euler convention expresses that as ZYX.
            point.sub(pivot).applyEuler(new Euler(x * Math.PI / 180, y * Math.PI / 180, z * Math.PI / 180, 'ZYX')).add(pivot)
          }
          positions.push(...point.toArray())
          const [u, v] = uv[(i + uvRotation / 90) % 4]
          uvs.push(u / 16, 1 - v / 16)
        }
      }
    }
    meshes.push({ texture, positions, uvs, ...(animation ? { animation } : {}) })
  }
  writeFileSync(join(output, `${outputId}.json`), JSON.stringify({ meshes }))
  return meshes
}
