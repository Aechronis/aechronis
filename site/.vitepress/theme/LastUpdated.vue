<script setup lang="ts">
import { computed } from 'vue'
import { useData } from 'vitepress'

const { page } = useData()
const revision = computed(() => page.value.gitRevision as
  { hash: string; date: string; author: string } | undefined)
const formattedDate = computed(() => revision.value
  ? new Intl.DateTimeFormat('en-US', {
    month: 'short', day: 'numeric', year: 'numeric', timeZone: 'UTC',
  }).format(new Date(`${revision.value.date}T00:00:00Z`))
  : '')
</script>

<template>
  <p v-if="revision" class="revision">
    Last updated: <time :datetime="revision.date">{{ formattedDate }}</time>
    by {{ revision.author }} in
    <a :href="`https://github.com/Aechronis/aechronis/commit/${revision.hash}`">
      {{ revision.hash.slice(0, 7) }}
    </a>
  </p>
</template>

<style scoped>
.revision {
  margin: 0 0 8px;
  color: var(--vp-c-text-2);
  font-size: 14px;
  line-height: 1.75;
  overflow-wrap: anywhere;
}

.revision a {
  color: var(--vp-c-brand-1);
  text-decoration: underline;
  text-underline-offset: 2px;
}
</style>
