<template>
  <div class="pl-kpis" :class="{ k4: columns === 4 }" role="group" :aria-label="label">
    <slot />
  </div>
</template>

<script setup>
// KPI row (reference section 3): 4 or 5 StatTiles, three per row under 1100 px, two on phones.
defineProps({
  label: { type: String, default: 'Key figures' },
  columns: { type: Number, default: 5, validator: (n) => n === 4 || n === 5 },
});
</script>
