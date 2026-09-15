import * as THREE from 'three'
import { OrbitControls } from 'three/addons/controls/OrbitControls.js'
import { createTextureAnimation } from './texture-animation.js'

export function createModelViewer(container) {
  const renderer = new THREE.WebGLRenderer({ antialias: true, alpha: true })
  renderer.setPixelRatio(Math.min(window.devicePixelRatio, 2))
  container.appendChild(renderer.domElement)
  const scene = new THREE.Scene()
  scene.add(new THREE.HemisphereLight(0xffffff, 0x778899, 2.5))
  const light = new THREE.DirectionalLight(0xffffff, 2)
  light.position.set(10, 20, 15)
  scene.add(light)
  const camera = new THREE.PerspectiveCamera(35, 1, 0.01, 1000)
  const controls = new OrbitControls(camera, renderer.domElement)
  controls.enablePan = false
  controls.enableZoom = true
  let model, distance = 30, generation = 0, disposed = false
  let animationFrame, animations = [], animationStart = 0
  const draw = () => { if (!disposed) renderer.render(scene, camera) }
  function stopAnimation() {
    cancelAnimationFrame(animationFrame)
    animationFrame = undefined
    animations = []
  }
  function animate(now) {
    if (disposed) return
    let changed = false
    for (const update of animations) changed = update(now - animationStart) || changed
    if (changed) draw()
    animationFrame = requestAnimationFrame(animate)
  }
  controls.addEventListener('change', draw)
  const resize = new ResizeObserver(() => {
    const { width, height } = container.getBoundingClientRect()
    if (!width || !height) return
    renderer.setSize(width, height)
    camera.aspect = width / height
    camera.updateProjectionMatrix()
    if (model) reset()
  })
  resize.observe(container)
  function release(group) {
    group?.traverse(object => {
      object.geometry?.dispose()
      if (object.material) { object.material.map?.dispose(); object.material.dispose() }
    })
  }
  function reset() {
    const aspect = Math.min(camera.aspect, 1)
    const radius = distance / aspect
    camera.position.set(radius * 0.92, radius * 0.28, radius * 0.3)
    controls.target.set(0, 0, 0)
    controls.minDistance = radius * 0.35
    controls.maxDistance = radius * 3
    controls.update()
    draw()
  }
  return {
    async load(url) {
      const token = ++generation
      stopAnimation()
      if (model) { scene.remove(model); release(model); model = null; draw() }
      const response = await fetch(url)
      if (!response.ok) throw new Error('Model unavailable')
      const data = await response.json()
      const group = new THREE.Group()
      const updates = []
      try {
        for (const mesh of data.meshes) {
          const texture = await new THREE.TextureLoader().loadAsync(new URL(mesh.texture, new URL(url, location.href)).href)
          if (disposed || token !== generation) { texture.dispose(); release(group); return false }
          texture.colorSpace = THREE.SRGBColorSpace
          texture.magFilter = THREE.NearestFilter
          if (mesh.animation) {
            // Avoid mipmaps sampling neighbouring frames in the sprite sheet.
            texture.minFilter = THREE.NearestFilter
            texture.generateMipmaps = false
            const update = createTextureAnimation(texture, mesh.animation)
            update(0)
            updates.push(update)
          }
          const geometry = new THREE.BufferGeometry()
          geometry.setAttribute('position', new THREE.Float32BufferAttribute(mesh.positions, 3))
          geometry.setAttribute('uv', new THREE.Float32BufferAttribute(mesh.uvs, 2))
          geometry.computeVertexNormals()
          group.add(new THREE.Mesh(geometry, new THREE.MeshLambertMaterial({ map: texture, side: THREE.DoubleSide, alphaTest: 0.1 })))
        }
        if (disposed || token !== generation) { release(group); return false }
        const bounds = new THREE.Box3().setFromObject(group)
        group.position.sub(bounds.getCenter(new THREE.Vector3()))
        const sphere = bounds.getBoundingSphere(new THREE.Sphere())
        distance = sphere.radius / Math.sin(THREE.MathUtils.degToRad(camera.fov / 2)) * 1.15
        model = group
        scene.add(model)
        reset()
        if (updates.length) {
          animations = updates
          animationStart = performance.now()
          animationFrame = requestAnimationFrame(animate)
        }
        return true
      } catch (error) { release(group); throw error }
    },
    dispose() {
      disposed = true
      generation++
      stopAnimation()
      resize.disconnect()
      controls.dispose()
      release(model)
      renderer.dispose()
      renderer.forceContextLoss()
      renderer.domElement.remove()
    },
  }
}
