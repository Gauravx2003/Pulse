<template>
  <div class="pl-tabs" role="tablist" :aria-label="label">
    <button
      v-for="(tab, i) in tabs"
      :id="tabId(tab.key)"
      :key="tab.key"
      ref="buttons"
      type="button"
      role="tab"
      class="pl-tab"
      :class="{ 'is-active': tab.key === modelValue }"
      :aria-selected="tab.key === modelValue"
      :aria-controls="panelId || undefined"
      :tabindex="tab.key === modelValue ? 0 : -1"
      @click="select(tab.key)"
      @keydown="onKey($event, i)"
    >
      {{ tab.label }}<span v-if="tab.count != null" class="cnt">{{ tab.count }}</span>
    </button>
  </div>
</template>

<script setup>
// Tabs (reference section 3): the pl-tabs segmented control; arrow keys, Home and End move between
// tabs (WAI-ARIA tabs pattern, automatic activation).
import { ref } from 'vue';
import { uid } from './dialog.js';

const props = defineProps({
  /** [{ key, label, count? }] */
  tabs: { type: Array, required: true },
  modelValue: { type: String, required: true },
  label: { type: String, required: true },
  /** Id of the panel the tabs control, when there is one. */
  panelId: { type: String, default: '' },
});

const emit = defineEmits(['update:modelValue']);

const buttons = ref([]);
const base = uid('tab');

function tabId(key) {
  return `${base}-${key}`;
}

function select(key) {
  if (key !== props.modelValue) {
    emit('update:modelValue', key);
  }
}

function onKey(event, i) {
  const n = props.tabs.length;
  const moves = { ArrowRight: i + 1, ArrowLeft: i - 1 + n, Home: 0, End: n - 1 };

  if (!(event.key in moves)) {
    return;
  }

  event.preventDefault();

  const next = moves[event.key] % n;

  select(props.tabs[next].key);
  buttons.value[next]?.focus();
}
</script>
