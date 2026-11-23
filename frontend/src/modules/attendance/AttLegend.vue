<template>
  <ul class="att-legend" aria-label="Legend">
    <li v-for="c in LEGEND" :key="c.key">
      <span :class="['att-cell', c.cls]" aria-hidden="true">{{ c.code }}</span>
      <span class="pl-caption">{{ c.label }}</span>
    </li>
    <li v-if="showLate">
      <span class="att-cell att-o att-late" aria-hidden="true">O</span>
      <span class="pl-caption">Late check-in</span>
    </li>
  </ul>
</template>

<script setup>
import { LEGEND } from './presentation.js';

defineProps({
  showLate: { type: Boolean, default: false },
});
</script>

<style scoped>
.att-legend {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
  margin: var(--space-3) 0 0;
  padding: 0;
  list-style: none;
}
.att-legend li {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.att-legend .att-cell {
  width: 22px;
  height: 22px;
}
</style>
