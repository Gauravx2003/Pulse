<template>
  <div class="pl-meter-row">
    <span>{{ label }}</span>
    <div
      class="pl-meter"
      role="meter"
      :aria-valuenow="value"
      aria-valuemin="0"
      :aria-valuemax="max"
      :aria-label="label"
    >
      <div :class="['pl-meter-fill', fillClass]" :style="{ width: pct(Math.min(value, max)) }"></div>
      <div class="pl-meter-mark" :style="{ left: pct(100) }" title="Capacity"></div>
    </div>
    <span class="pl-mono value">{{ value }}%</span>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { BAND_TONES } from './tones.js';

/**
 * UtilizationMeter (reference/3-design-system/components/UtilizationMeter): assigned effort against
 * capacity with an ink mark at 100%. The bar clamps at `max` but the true value is printed.
 * `band` is the band tone returned by the backend (never a color and never derived here), so the
 * configurable band limits stay in CalcService.
 */
const props = defineProps({
  label: { type: String, required: true },
  value: { type: Number, required: true },
  max: { type: Number, default: 140 },
  band: { type: String, required: true, validator: (v) => BAND_TONES.includes(v) },
});

// bundle.css: the plain fill is the `ok` color; the other bands add a class.
const fillClass = computed(() => (props.band === 'ok' ? '' : `is-${props.band}`));

function pct(v) {
  return `${((Math.max(v, 0) / props.max) * 100).toFixed(1)}%`;
}
</script>

<style scoped>
.value {
  text-align: right;
}
</style>
