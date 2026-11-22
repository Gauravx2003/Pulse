<template>
  <div class="pl-card">
    <div class="pl-card-head">
      <h3 class="pl-h3">{{ title }}</h3>
      <span v-if="caption" class="pl-caption">{{ caption }}</span>
    </div>
    <div class="rows">
      <div class="pl-heat" :style="gridStyle">
        <span></span>
        <span v-for="day in days" :key="day" class="pl-caption center">{{ day }}</span>
        <span class="pl-caption right">Avg</span>
      </div>
      <div
        v-for="row in rows"
        :key="row.id"
        class="pl-heat person"
        :style="gridStyle"
        role="button"
        tabindex="0"
        :aria-label="`Open ${row.name}'s sprint work`"
        @click="emit('select', row.id)"
        @keydown.enter.prevent="emit('select', row.id)"
        @keydown.space.prevent="emit('select', row.id)"
      >
        <span class="who"><span class="pl-avatar">{{ row.initials }}</span>{{ row.name }}</span>
        <span
          v-for="(cell, i) in row.cells"
          :key="i"
          :class="['pl-heat-cell', cell.label ? 'u0' : heatClass(cell.band)]"
        >{{ cell.label ?? `${cell.value}%` }}</span>
        <span class="pl-mono right">{{ row.avg }}</span>
      </div>
    </div>
    <div v-if="legend.length" class="legend pl-caption">
      <span v-for="item in legend" :key="item.label" class="legend-item">
        <span :class="['pl-heat-cell', 'swatch', heatClass(item.band)]"></span>{{ item.label }}
      </span>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { heatClass } from './tones.js';

/**
 * TeamHeatmap (reference/3-design-system/components/TeamHeatmap): person x working-day utilization.
 * Every cell prints its value; color is the second cue. Rows are
 * `{ id, name, initials, cells: [{ value, band } | { label: 'Leave' }], avg }`, where `band` and
 * `avg` (working days only) come from the backend. The legend labels carry the configured limits,
 * so they are passed in: `[{ band, label }]` (`band` omitted for Leave). Selecting a row emits
 * `select` with the person id so the screen can open that person's SprintTaskTable.
 */
const props = defineProps({
  title: { type: String, required: true },
  caption: { type: String, default: '' },
  days: { type: Array, required: true },
  rows: { type: Array, required: true },
  legend: { type: Array, default: () => [] },
});

const emit = defineEmits(['select']);

const gridStyle = computed(() => ({
  gridTemplateColumns: `160px repeat(${props.days.length}, 1fr) 72px`,
}));
</script>

<style scoped>
.rows {
  display: grid;
  gap: 4px;
}

.center {
  text-align: center;
}

.right {
  text-align: right;
}

.person {
  cursor: pointer;
}

.person:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}

.who {
  display: flex;
  gap: var(--space-2);
  align-items: center;
}

.legend {
  display: flex;
  gap: var(--space-3);
  flex-wrap: wrap;
  margin-top: var(--space-3);
}

.legend-item {
  display: flex;
  gap: 6px;
  align-items: center;
}

.swatch {
  width: 28px;
  height: 16px;
}
</style>
