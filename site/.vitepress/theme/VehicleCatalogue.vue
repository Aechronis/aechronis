<script setup>
import { computed, ref, watch } from 'vue'
import ModelPreview from './ModelPreview.vue'
import catalogues from './vehicles.json'

const props = defineProps({ iteration: { type: String, required: true } })
const vehicles = computed(() => catalogues[props.iteration] ?? [])

const unavailablePreviews = ref(new Set())
watch(() => props.iteration, () => { unavailablePreviews.value = new Set() })
</script>

<template>
  <div class="vehicle-catalogue">
    <section v-for="vehicle in vehicles" :key="`${iteration}/${vehicle.id}`" class="vehicle-row" :class="{ 'vehicle-row-stats-only': unavailablePreviews.has(vehicle.id) }" :aria-labelledby="`vehicle-${vehicle.id}`">
      <div class="vehicle-details">
        <h2 :id="`vehicle-${vehicle.id}`">{{ vehicle.name }}</h2>
        <dl>
          <div v-for="(stat, index) in vehicle.stats" :key="index"><dt>{{ stat.label }}</dt><dd>{{ stat.value }}</dd></div>
        </dl>
      </div>
      <ModelPreview v-if="!unavailablePreviews.has(vehicle.id)" :model="vehicle.model" :name="vehicle.name" @unavailable="unavailablePreviews.add(vehicle.id)" />
    </section>
  </div>
</template>

<style scoped>
.vehicle-catalogue { margin: 24px 0 32px; }
.vehicle-row { display: grid; grid-template-columns: minmax(180px, 2fr) minmax(0, 3fr); gap: 20px; padding: 24px 0; border-bottom: 1px solid var(--vp-c-divider); }
.vehicle-row-stats-only { grid-template-columns: minmax(0, 1fr); }
.vehicle-details h2 { margin: 0 0 16px; padding: 0; border: 0; font-size: 21px; }
.vehicle-details dl { margin: 0; font-size: 14px; }
.vehicle-details dl div { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
.vehicle-details dt { color: var(--vp-c-text-2); }
.vehicle-details dd { margin: 0; text-align: right; font-weight: 500; }
@media (max-width: 600px) { .vehicle-row { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
</style>
