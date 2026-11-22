<template>
  <div class="dashboard">
    <div class="identity">
      <span class="pl-avatar lg">{{ person.initials }}</span>
      <div class="who">
        <h2 class="pl-h2">{{ person.name }}</h2>
        <div class="pl-caption">{{ person.role }}<template v-if="person.manager"> · Manager {{ person.manager }}</template></div>
      </div>
      <div v-if="periods.length" class="pl-tabs" role="tablist" aria-label="Period">
        <button
          v-for="p in periods"
          :key="p"
          type="button"
          role="tab"
          :class="['pl-tab', { 'is-active': period === p }]"
          :aria-selected="period === p"
          @click="emit('update:period', p)"
        >{{ p }}</button>
      </div>
      <button v-if="canStartOneOnOne" type="button" class="pl-btn pl-btn-primary" @click="emit('start-one-on-one')">
        Start 1:1
      </button>
    </div>

    <div class="pl-grid kpis">
      <StatTile v-for="stat in stats" :key="stat.eyebrow" v-bind="stat" />
    </div>

    <slot name="alert" />
    <slot name="tasks" />

    <div v-if="$slots.culture || $slots.roadmap" class="pl-grid pair">
      <slot name="culture" />
      <slot name="roadmap" />
    </div>

    <slot name="index" />
    <slot name="integrations" />
  </div>
</template>

<script setup>
import StatTile from './StatTile.vue';

/**
 * EmployeeDashboard (reference/3-design-system/components/EmployeeDashboard): the employee overview,
 * in this fixed order: identity row with period tabs and Start 1:1; five StatTiles (Tasks assigned,
 * Not updated 2+ days, Work assigned, Utilization, Performance index); the `alert` slot (shown by the
 * screen only for Over / Critical or many stale tasks); `tasks` (SprintTaskTable); `culture` and
 * `roadmap` side by side; `index` (performance index breakdown); `integrations`. Visible to the
 * employee, their manager and HR; the screen enforces that.
 */
defineProps({
  person: { type: Object, required: true },
  stats: { type: Array, required: true },
  periods: { type: Array, default: () => [] },
  period: { type: String, default: '' },
  canStartOneOnOne: { type: Boolean, default: false },
});

const emit = defineEmits(['update:period', 'start-one-on-one']);
</script>

<style scoped>
.dashboard {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}

.identity {
  display: flex;
  align-items: center;
  gap: var(--space-4);
  flex-wrap: wrap;
}

.who {
  flex: 1;
  min-width: 200px;
}

.kpis {
  grid-template-columns: repeat(5, minmax(0, 1fr));
}

.pair {
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
}

@media (max-width: 899px) {
  .kpis {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
