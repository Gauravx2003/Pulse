<template>
  <div class="pl-card">
    <div class="pl-card-head">
      <div>
        <h3 class="pl-h3">{{ title }}</h3>
        <div v-if="caption" class="pl-caption">{{ caption }}</div>
      </div>
      <div v-if="$slots.actions" class="actions"><slot name="actions" /></div>
    </div>
    <div v-if="$slots.alert" class="alert-slot"><slot name="alert" /></div>
    <div class="scroll">
      <table class="pl-table">
        <thead>
          <tr>
            <th>Key</th>
            <th>Summary</th>
            <th>Status</th>
            <th class="right">SP</th>
            <th class="right">Remaining</th>
            <th>Last updated</th>
            <th>Signal</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="row in rows" :key="row.key" :class="{ 'is-flagged': !!row.signal }">
            <td>
              <a v-if="row.url" class="pl-key" :href="row.url" target="_blank" rel="noopener noreferrer">{{ row.key }}</a>
              <span v-else class="pl-key">{{ row.key }}</span>
            </td>
            <td>{{ row.summary }}</td>
            <td><Badge :text="row.status" :tone="statusTone(row.status)" /></td>
            <td class="num">{{ row.storyPoints }}</td>
            <td class="num">{{ row.remainingHours }} h</td>
            <td><span class="pl-caption">{{ row.lastUpdated }}</span></td>
            <td><Badge v-if="row.signal" :text="row.signal.text" :tone="row.signal.tone" /></td>
          </tr>
        </tbody>
      </table>
    </div>
  </div>
</template>

<script setup>
import Badge from './Badge.vue';
import { statusTone } from './tones.js';

/**
 * SprintTaskTable (reference/3-design-system/components/SprintTaskTable): the active-sprint issues
 * of one person or team with a freshness signal per row. Rows are
 * `{ key, summary, status, storyPoints, remainingHours, lastUpdated, signal?, url? }`; the backend
 * computes `lastUpdated` (assignee activity, working days, e.g. "3 working days ago") and `signal`
 * (`{ tone: 'accent', text: 'Stale' }` or `{ tone: 'danger', text: 'Overdue' }`), and a row with a
 * signal is flagged. The `actions` slot holds header buttons (Only stale, Nudge assignee) and the
 * `alert` slot the stale summary.
 */
defineProps({
  title: { type: String, required: true },
  caption: { type: String, default: '' },
  rows: { type: Array, required: true },
});
</script>

<style scoped>
.actions {
  display: flex;
  gap: var(--space-2);
}

.alert-slot {
  margin-bottom: var(--space-3);
}

.scroll {
  overflow-x: auto;
}

.right {
  text-align: right;
}
</style>
