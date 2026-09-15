<script setup>
import { ref, onMounted, onBeforeUnmount } from 'vue'
import { withBase } from 'vitepress'

const emit = defineEmits(['unavailable'])
const props = defineProps({ model: { type: String, required: true }, name: { type: String, required: true } })
const viewport = ref(null)
const status = ref('Loading 3D preview…')
let viewer, observer, generation = 0

function release() {
  generation++
  viewer?.dispose()
  viewer = undefined
}
async function activate() {
  const token = ++generation
  status.value = 'Loading 3D preview…'
  try {
    const { createModelViewer } = await import('./model-viewer.js')
    if (token !== generation) return
    viewer = createModelViewer(viewport.value)
    const loaded = await viewer.load(withBase(props.model))
    if (token !== generation) return
    if (loaded) status.value = ''
  } catch {
    if (token !== generation) return
    release()
    observer?.disconnect()
    emit('unavailable')
  }
}
onMounted(() => {
  if (!('IntersectionObserver' in window)) { emit('unavailable'); return }
  // Keep GPU contexts bounded while scrolling through the entire catalogue.
  observer = new IntersectionObserver(([entry]) => {
    if (entry.isIntersecting) activate()
    else { release(); status.value = 'Loading 3D preview…' }
  }, { rootMargin: '100px' })
  observer.observe(viewport.value)
})
onBeforeUnmount(() => { observer?.disconnect(); release() })
</script>

<template>
  <div class="model-preview">
    <div ref="viewport" class="model-viewport" role="img" :aria-label="`Interactive 3D model of ${name}`">
      <p v-if="status" class="model-status" role="status">{{ status }}</p>
    </div>

  </div>
</template>

<style scoped>
.model-preview { min-width: 0; align-self: start; overflow: hidden; }
.model-viewport { height: 220px; position: relative; }
.model-viewport :deep(canvas) { display: block; width: 100%; height: 100%; }
.model-status { position: absolute; inset: 0; display: grid; place-content: center; padding: 16px; margin: 0; color: var(--vp-c-text-2); font-size: 13px; text-align: center; pointer-events: none; }
</style>
