<script setup>
import ModelPreview from './ModelPreview.vue'
defineProps({ weapon: { type: Object, required: true } })
defineEmits(['unavailable'])
</script>

<template>
  <ModelPreview :model="weapon.model" :name="weapon.name" @unavailable="$emit('unavailable')" />
</template>
