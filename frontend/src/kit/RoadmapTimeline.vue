<template>
  <div class="pl-card">
    <div class="pl-card-head">
      <h3 class="pl-h3">{{ title }}</h3>
      <Badge v-if="track" :text="track" tone="brand" />
    </div>
    <div class="pl-road">
      <div v-for="(item, i) in items" :key="i" class="pl-road-item">
        <span :class="['pl-road-dot', DOT_CLASS[item.state]]"></span>
        <div>
          <div class="pl-h3">{{ item.title }}</div>
          <div class="pl-caption">{{ item.when }}</div>
          <div v-if="item.progress != null" class="pl-road-prog">
            <i :style="{ width: `${Math.min(Math.max(item.progress, 0), 100)}%` }"></i>
          </div>
        </div>
        <Badge :text="item.note" :tone="item.tone ?? NOTE_TONE[item.state]" />
      </div>
    </div>
  </div>
</template>

<script setup>
import Badge from './Badge.vue';

/**
 * RoadmapTimeline (reference/3-design-system/components/RoadmapTimeline): past, current and planned
 * growth milestones. Items are `{ state: 'done' | 'now' | 'next', title, when, progress?, note, tone? }`;
 * one `now` item at a time, which may carry a 0-100 progress bar. `note` is the badge word
 * ("Done", "62% · on track", "Planned"); its tone defaults from the state.
 */
defineProps({
  title: { type: String, required: true },
  track: { type: String, default: '' },
  items: { type: Array, required: true },
});

const DOT_CLASS = { done: 'is-done', now: 'is-now', next: '' };
const NOTE_TONE = { done: 'ok', now: 'brand', next: '' };
</script>
