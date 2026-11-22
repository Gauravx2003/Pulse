<template>
  <div class="team-view">
    <div class="pl-grid kpis">
      <StatTile v-for="kpi in kpis" :key="kpi.eyebrow" v-bind="kpi" />
    </div>
    <TeamHeatmap v-bind="heatmap" :rows="sortedRows" @select="(id) => emit('select', id)" />
    <div>
      <div class="pl-tabs" role="tablist" aria-label="Team detail">
        <button
          v-for="tab in TABS"
          :key="tab.id"
          type="button"
          role="tab"
          :class="['pl-tab', { 'is-active': active === tab.id }]"
          :aria-selected="active === tab.id"
          @click="active = tab.id"
        >{{ tab.label }}</button>
      </div>
      <div class="tab-body" role="tabpanel">
        <slot :name="active" />
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import StatTile from './StatTile.vue';
import TeamHeatmap from './TeamHeatmap.vue';

/**
 * TeamView (reference/3-design-system/components/TeamView): the manager's team screen, team KPIs
 * over a TeamHeatmap, then tabs for stale work and culture (slots `stale` and `culture`).
 * `kpis` are StatTile props; each KPI foot names the people behind it. `heatmap` is the TeamHeatmap
 * props; its rows carry a numeric `utilization` and are shown highest first so overload is seen
 * first. Rebalance is only suggested by the screen, never applied here.
 */
const props = defineProps({
  kpis: { type: Array, required: true },
  heatmap: { type: Object, required: true },
});

const emit = defineEmits(['select']);

const TABS = [
  { id: 'stale', label: 'Stale work' },
  { id: 'culture', label: 'Culture' },
];

const active = ref('stale');

const sortedRows = computed(() =>
  [...props.heatmap.rows].sort((a, b) => (b.utilization ?? 0) - (a.utilization ?? 0)),
);
</script>

<style scoped>
.team-view {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.kpis {
  grid-template-columns: repeat(auto-fit, minmax(180px, 1fr));
}

.tab-body {
  margin-top: var(--space-4);
}
</style>
