<template>
  <div class="pl-period">
    <div v-if="kinds.length > 1" class="pl-tabs" role="group" :aria-label="`${label} type`">
      <button
        v-for="k in kinds"
        :key="k"
        type="button"
        class="pl-tab"
        :class="{ 'is-active': k === kind }"
        :aria-pressed="k === kind"
        @click="chooseKind(k)"
      >
        {{ KIND_LABELS[k] }}
      </button>
    </div>
    <select
      class="pl-input is-sm"
      :aria-label="label"
      :value="modelValue ?? ''"
      :disabled="!options.length"
      @change="emit('update:modelValue', $event.target.value)"
    >
      <option v-if="!options.length" value="">No periods</option>
      <option v-for="p in options" :key="p.key" :value="p.key">{{ p.label }}</option>
    </select>
  </div>
</template>

<script setup>
// Period control (reference section 3): Sprint / Month / Release segmented buttons plus a dropdown of
// that kind's periods, labelled by the server ("(active)", "(to date)"). Period keys follow spec
// platform/period-calendar: s:S43, m:2026-09, r:8.2.
import { computed } from 'vue';
import { KIND_LABELS, kindOf, defaultKey } from './periods.js';

const props = defineProps({
  /** The selected period key, or null for none yet. */
  modelValue: { type: String, default: null },
  /** Kinds offered, in button order: 'sprint', 'month', 'release'. */
  kinds: { type: Array, default: () => ['sprint', 'month', 'release'] },
  /** { sprints, months, releases, activeSprint } from GET /periods */
  periods: { type: Object, default: null },
  label: { type: String, default: 'Period' },
});

const emit = defineEmits(['update:modelValue']);

const kind = computed(() => {
  const k = kindOf(props.modelValue);

  return props.kinds.includes(k) ? k : props.kinds[0];
});

const options = computed(() => props.periods?.[`${kind.value}s`] ?? []);

function chooseKind(k) {
  if (k !== kind.value) {
    emit('update:modelValue', defaultKey(props.periods, k));
  }
}
</script>
