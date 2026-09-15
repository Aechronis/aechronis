<script setup>
import { computed, ref } from 'vue'
import WeaponPreview from './WeaponPreview.vue'
import catalogues from './weapons.json'

const props = defineProps({ iteration: { type: String, required: true } })
const weapons = computed(() => catalogues[props.iteration] ?? [])

const unavailablePreviews = ref(new Set())
</script>

<template>
  <div class="weapon-catalogue">
    <section v-for="weapon in weapons" :key="weapon.id" class="weapon-row" :class="{ 'weapon-row-stats-only': unavailablePreviews.has(weapon.id) }" :aria-labelledby="`weapon-${weapon.id}`">
      <div class="weapon-details">
        <h2 :id="`weapon-${weapon.id}`">{{ weapon.name }}</h2>
        <dl>
          <div v-if="weapon.ammo"><dt>Ammunition</dt><dd>{{ weapon.ammo }}</dd></div>
          <div v-if="weapon.magazine != null"><dt>Capacity</dt><dd>{{ weapon.magazine }}</dd></div>
          <div v-if="weapon.mode"><dt>Fire mode</dt><dd>{{ weapon.mode }}</dd></div>
          <div v-if="weapon.rpm != null"><dt>Fire rate</dt><dd>{{ weapon.rpm }} RPM</dd></div>
          <div><dt>Base damage</dt><dd>{{ weapon.damage }}</dd></div>
          <div v-if="weapon.attackSpeed != null"><dt>Attack speed</dt><dd>{{ weapon.attackSpeed }} / s</dd></div>
          <div v-if="weapon.reload != null"><dt>Reload</dt><dd>{{ weapon.reload }} s</dd></div>
          <div v-if="weapon.recoilMin != null"><dt>Recoil (min–max)</dt><dd>{{ weapon.recoilMin }}–{{ weapon.recoilMax }}°</dd></div>
          <div v-if="weapon.spreadMin != null"><dt>Spread (min–max)</dt><dd>{{ weapon.spreadMin }}–{{ weapon.spreadMax }}°</dd></div>
          <div v-if="weapon.maxRange != null"><dt>Maximum range</dt><dd>{{ weapon.maxRange }} blocks</dd></div>
          <div v-if="weapon.knockback != null"><dt>Knockback</dt><dd>{{ weapon.knockback }}</dd></div>
        </dl>
      </div>
      <WeaponPreview v-if="!unavailablePreviews.has(weapon.id)" :weapon="weapon" @unavailable="unavailablePreviews.add(weapon.id)" />
    </section>
  </div>
</template>

<style scoped>
.weapon-catalogue { margin: 24px 0 32px; }
.weapon-row { display: grid; grid-template-columns: minmax(180px, 2fr) minmax(0, 3fr); gap: 20px; padding: 24px 0; border-bottom: 1px solid var(--vp-c-divider); }
.weapon-row-stats-only { grid-template-columns: minmax(0, 1fr); }
.weapon-details h2 { margin: 0 0 16px; padding: 0; border: 0; font-size: 21px; }
.weapon-details dl { margin: 0; font-size: 14px; }
.weapon-details dl div { display: flex; justify-content: space-between; gap: 12px; padding: 4px 0; }
.weapon-details dt { color: var(--vp-c-text-2); }
.weapon-details dd { margin: 0; text-align: right; font-weight: 500; }
@media (max-width: 600px) { .weapon-row { grid-template-columns: minmax(0, 1fr); gap: 16px; } }
</style>
