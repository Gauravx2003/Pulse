<script setup>
// The extra-mile item rows of both Effort tabs (reference 7.4): kind badge, ref, title, who it is for
// (team review), who gave it, hours, points and state. A holder of effort.confirm gets Reject and
// Confirm on a pending item; only done work can be confirmed. A row opens the evidence modal.
import { Badge } from '@/kit/index.js';
import { num } from '../work/presentation.js';
import { kind, state } from './presentation.js';

defineProps({
  items: { type: Array, required: true },
  canConfirm: { type: Boolean, default: false },
  showPerson: { type: Boolean, default: false },
  busy: { type: String, default: '' },
});

const emit = defineEmits(['open', 'confirm', 'reject']);
</script>

<template>
  <ul class="items" :aria-label="showPerson ? 'Items to review' : 'Unplanned and extra work'">
    <li v-for="item in items" :key="item.id" class="item">
      <button type="button" class="open" @click="emit('open', item)">
        <span class="line">
          <Badge :text="kind(item.kind).label" :tone="kind(item.kind).tone" />
          <span class="pl-key">{{ item.ref }}</span>
          <span v-if="showPerson" class="pl-caption">· {{ item.personName }}</span>
        </span>
        <span class="title">{{ item.title }}</span>
        <span class="pl-caption">Given by {{ item.requestedBy }} · {{ num(item.hours) }} h</span>
      </button>
      <span class="side">
        <span class="pl-mono points">+{{ num(item.points, 2) }}</span>
        <template v-if="canConfirm && item.state === 'pending'">
          <button type="button" class="pl-btn pl-btn-sm" :disabled="busy === item.id" :aria-label="`Reject ${item.ref}`" @click="emit('reject', item)">✕ Reject</button>
          <button
            v-if="item.isDone"
            type="button"
            class="pl-btn pl-btn-sm pl-btn-primary"
            :disabled="busy === item.id"
            :aria-label="`Confirm ${item.ref}`"
            @click="emit('confirm', item)"
          >✓ Confirm</button>
          <Badge v-else text="Waiting for Done" tone="info" />
        </template>
        <Badge v-else :text="state(item).label" :tone="state(item).tone" />
      </span>
    </li>
  </ul>
</template>

<style scoped>
.items {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.item {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.open {
  display: grid;
  gap: var(--space-1);
  flex: 1 1 260px;
  min-width: 0;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  color: inherit;
  font: inherit;
  cursor: pointer;
}

.line,
.side {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.title {
  overflow-wrap: anywhere;
}

.points {
  font-weight: 600;
}
</style>
