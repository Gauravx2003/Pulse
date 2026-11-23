<script setup>
// Team wins (spec insight/progress-pulse "Team wins", reference 6.1 and 6.2): kudos, 5-star visits,
// completed training and proactive fixes, newest first; each with an achievement dot, what was won and
// a value badge. Shared by the Action center and Progress.
import { Badge } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlEmpty } from '@/ui/index.js';

defineProps({
  items: { type: Array, default: () => [] },
  limit: { type: Number, default: 6 },
});

function when(at) {
  return typeof at === 'string' && /^\d{4}-\d{2}-\d{2}/.test(at) ? formatDate(at) : typeof at === 'string' ? at : '';
}
</script>

<template>
  <PlEmpty v-if="!items.length" text="No wins yet this quarter." />
  <ul v-else class="wins">
    <li v-for="(w, i) in items.slice(0, limit)" :key="i" class="win">
      <span class="dot" aria-hidden="true"></span>
      <div class="body">
        <div class="head">
          <b>{{ w.who }}</b>
          <span class="pl-caption">{{ when(w.at) }}</span>
        </div>
        <div>{{ w.what }}</div>
        <div v-if="typeof w.text === 'string'" class="pl-caption">{{ w.text }}</div>
      </div>
      <Badge v-if="typeof w.value === 'string'" :text="w.value" tone="brand" />
    </li>
  </ul>
</template>

<style scoped>
.wins {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-3);
}

.win {
  display: grid;
  grid-template-columns: auto minmax(0, 1fr) auto;
  gap: var(--space-2);
  align-items: start;
}

.dot {
  width: 10px;
  height: 10px;
  margin-top: 6px;
  border-radius: 50%;
  background: var(--ok);
}

.body {
  min-width: 0;
  overflow-wrap: anywhere;
}

.head {
  display: flex;
  justify-content: space-between;
  gap: var(--space-2);
}
</style>
