<script setup>
// One dot per PR standard check: pass, fail or pending (left out of the score). Label and state are
// in each dot's accessible name.
import { STATE_LABELS } from './presentation.js';

defineProps({
  checks: { type: Array, required: true },
});
</script>

<template>
  <span class="dots" role="list">
    <span
      v-for="c in checks"
      :key="c.k"
      role="listitem"
      class="dot"
      :class="`is-${c.state}`"
      :title="`${c.l}: ${STATE_LABELS[c.state] ?? c.state}`"
      :aria-label="`${c.l}: ${STATE_LABELS[c.state] ?? c.state}`"
    />
  </span>
</template>

<style scoped>
.dots {
  display: inline-flex;
  gap: 4px;
}

.dot {
  width: 10px;
  height: 10px;
  border-radius: 50%;
  background: var(--surface-sunken);
  border: 1px solid var(--border-strong);
}

.dot.is-pass {
  background: var(--ok);
  border-color: var(--ok);
}

.dot.is-fail {
  background: var(--danger);
  border-color: var(--danger);
}
</style>
