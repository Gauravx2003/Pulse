<script setup>
// Evidence modal (reference 7.4): kind, ref and title; Hours, Points, Status and Given by; how it was
// detected; the culture values it credits.
import { Badge } from '@/kit/index.js';
import { formatDateWithYear } from '@/ui/format.js';
import { PlModal } from '@/ui/index.js';
import { num } from '../work/presentation.js';
import { kind, state } from './presentation.js';

defineProps({
  item: { type: Object, default: null },
});

const emit = defineEmits(['close']);
</script>

<template>
  <PlModal :open="!!item" :title="item ? `${item.ref} · ${item.title}` : 'Evidence'" @close="emit('close')">
    <div v-if="item" class="evidence">
      <div><Badge :text="kind(item.kind).label" :tone="kind(item.kind).tone" /></div>
      <div class="tiles">
        <div class="pl-stat"><div class="pl-eyebrow">Hours</div><div class="pl-stat-value">{{ num(item.hours) }}</div></div>
        <div class="pl-stat"><div class="pl-eyebrow">Points</div><div class="pl-stat-value">{{ num(item.points, 2) }}</div></div>
        <div class="pl-stat"><div class="pl-eyebrow">Status</div><Badge :text="state(item).label" :tone="state(item).tone" /></div>
        <div class="pl-stat">
          <div class="pl-eyebrow">Given by</div>
          <div>{{ item.requestedBy }}</div>
          <div class="pl-caption">{{ formatDateWithYear(item.occurredOn) }}</div>
        </div>
      </div>
      <div>
        <div class="pl-eyebrow">How it was detected</div>
        <p>{{ item.detectionNote }}</p>
        <div class="pl-caption">Source: {{ item.evidenceSource }}</div>
      </div>
      <div>
        <div class="pl-eyebrow">Culture values credited</div>
        <div class="values">
          <Badge v-for="v in item.valuesCredited ?? []" :key="v" :text="v" tone="brand" />
          <span v-if="!(item.valuesCredited ?? []).length" class="pl-caption">None</span>
        </div>
      </div>
    </div>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Close</button>
    </template>
  </PlModal>
</template>

<style scoped>
.evidence {
  display: grid;
  gap: var(--space-4);
}

.tiles {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: var(--space-2);
}

.tiles .pl-stat-value {
  font-size: 20px;
  line-height: 24px;
}

.values {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

p {
  margin: var(--space-1) 0;
}
</style>
