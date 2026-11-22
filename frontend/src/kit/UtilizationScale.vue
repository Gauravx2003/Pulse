<template>
  <div class="scale-row">
    <span></span>
    <div class="pl-meter-scale">
      <span v-for="(tick, i) in ticks" :key="i">{{ tick }}{{ i === ticks.length - 1 ? '%' : '' }}</span>
    </div>
    <span></span>
  </div>
</template>

<script setup>
import { computed } from 'vue';

/** The percent scale under a list of UtilizationMeter rows (0 to `max` in four steps). */
const props = defineProps({
  max: { type: Number, default: 140 },
});

const ticks = computed(() => [0, 1, 2, 3, 4].map((i) => Math.round((props.max * i) / 4)));
</script>

<style scoped>
/* Same columns as .pl-meter-row so the scale lines up under the bars. */
.scale-row {
  display: grid;
  grid-template-columns: 160px 1fr 64px;
  gap: var(--space-3);
}
</style>
