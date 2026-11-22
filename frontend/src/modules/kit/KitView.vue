<template>
  <div :class="component ? 'pl bare' : 'gallery'">
    <div v-if="!component" class="pl-card-head">
      <div>
        <h2 class="pl-h2">UI kit</h2>
        <p class="pl-caption">Design-system components wrapped for Vue. Open one alone to compare it with its preview.html.</p>
      </div>
      <ThemeToggle />
    </div>

    <p v-if="component && !shown.length" class="pl-caption">Unknown component "{{ component }}".</p>

    <section v-for="name in shown" :key="name" :data-kit="name" class="section">
      <div v-if="!component" class="pl-eyebrow section-head">
        {{ name }} · <RouterLink :to="`/kit/${name}`">open alone</RouterLink>
      </div>

      <div v-if="name === 'Badge'" class="row-wrap">
        <Badge v-for="b in data.badges" :key="b.text + b.tone" :text="b.text" :tone="b.tone" />
      </div>

      <div v-else-if="name === 'StatTile'" class="pl-grid four">
        <StatTile v-for="s in data.stats" :key="s.eyebrow" v-bind="s" />
      </div>

      <div v-else-if="name === 'UtilizationMeter'" class="pl-card narrow">
        <div class="pl-card-head">
          <h3 class="pl-h3">Sprint utilization</h3>
          <span class="pl-caption">Capacity line = 100%</span>
        </div>
        <UtilizationMeter v-for="m in data.meters" :key="m.label" v-bind="m" />
        <UtilizationScale />
      </div>

      <TeamHeatmap v-else-if="name === 'TeamHeatmap'" v-bind="data.heatmap" @select="picked = $event" />

      <SprintTaskTable v-else-if="name === 'SprintTaskTable'" v-bind="data.sprint">
        <template #actions>
          <button type="button" class="pl-btn pl-btn-sm">Only stale</button>
          <button type="button" class="pl-btn pl-btn-sm pl-btn-primary">Nudge assignee</button>
        </template>
        <template #alert>
          <div class="pl-alert">
            <Badge text="Stale" tone="accent" />
            <span><b>4 issues</b> in progress or due with no status change, comment or worklog in 2+ working days.</span>
            <button type="button" class="pl-btn pl-btn-sm">Review</button>
          </div>
        </template>
      </SprintTaskTable>

      <CultureValues v-else-if="name === 'CultureValues'" v-bind="data.culture" @select="picked = $event" />

      <div v-else-if="name === 'IntegrationTile'" class="pl-grid two">
        <IntegrationTile v-for="t in data.integrations" :key="t.mark" v-bind="t" />
      </div>

      <div v-else-if="name === 'RoadmapTimeline'" class="narrow">
        <RoadmapTimeline v-bind="data.roadmap" />
      </div>

      <TeamView v-else-if="name === 'TeamView'" :kpis="data.teamKpis" :heatmap="data.heatmap" @select="picked = $event">
        <template #stale>
          <SprintTaskTable v-bind="data.sprint" :rows="data.sprint.rows.filter((r) => r.signal)" />
        </template>
        <template #culture>
          <CultureValues v-bind="data.culture" />
        </template>
      </TeamView>

      <EmployeeDashboard
        v-else-if="name === 'EmployeeDashboard'"
        v-bind="data.employee"
        v-model:period="period"
        can-start-one-on-one
      >
        <template #alert>
          <div class="pl-alert is-danger">
            <Badge text="Critical" tone="danger" />
            <span><b>Utilization 128%.</b> Suggest moving PLAT-2198 (8 SP) to Sprint 43.</span>
            <button type="button" class="pl-btn pl-btn-sm">Review</button>
          </div>
        </template>
        <template #tasks>
          <SprintTaskTable v-bind="data.sprint" />
        </template>
        <template #culture>
          <CultureValues v-bind="data.culture" />
        </template>
        <template #roadmap>
          <RoadmapTimeline v-bind="data.roadmap" />
        </template>
        <template #integrations>
          <div class="pl-grid two">
            <IntegrationTile v-for="t in data.integrations.slice(0, 4)" :key="t.mark" v-bind="t" />
          </div>
        </template>
      </EmployeeDashboard>
    </section>

    <p v-if="!component && picked !== null" class="pl-caption">Selected: {{ picked }}</p>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue';
import {
  Badge,
  StatTile,
  UtilizationMeter,
  UtilizationScale,
  TeamHeatmap,
  SprintTaskTable,
  CultureValues,
  IntegrationTile,
  RoadmapTimeline,
  TeamView,
  EmployeeDashboard,
  ThemeToggle,
} from '@/kit';
import * as data from './kitData.js';

const props = defineProps({
  component: { type: String, default: '' },
});

const NAMES = [
  'Badge',
  'StatTile',
  'UtilizationMeter',
  'TeamHeatmap',
  'SprintTaskTable',
  'CultureValues',
  'IntegrationTile',
  'RoadmapTimeline',
  'TeamView',
  'EmployeeDashboard',
];

const shown = computed(() => (props.component ? NAMES.filter((n) => n === props.component) : NAMES));
const picked = ref(null);
const period = ref('Sprint');
</script>

<style scoped>
.gallery {
  display: flex;
  flex-direction: column;
  gap: var(--space-8);
}

/* Same inset as the reference preview pages. */
.bare {
  padding: 16px;
  min-height: 100vh;
}

.section-head {
  margin-bottom: var(--space-3);
}

.section-head a {
  color: var(--brand-ink);
}

.row-wrap {
  display: flex;
  gap: var(--space-2);
  flex-wrap: wrap;
}

.four {
  grid-template-columns: repeat(4, 1fr);
}

.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

.narrow {
  max-width: 640px;
}

@media (max-width: 699px) {
  .four,
  .two {
    grid-template-columns: minmax(0, 1fr);
  }
}
</style>
