<template>
  <div class="rule-row">
    <div class="rule-text">
      <label :for="id" class="rule-name">{{ field.label }}</label>
      <span v-if="field.help" :id="`${id}-help`" class="pl-caption">{{ field.help }}</span>
      <p v-if="error" :id="`${id}-error`" class="pl-field-error">{{ error }}</p>
    </div>

    <input
      v-if="field.type === 'boolean'"
      :id="id"
      type="checkbox"
      role="switch"
      class="pl-switch"
      :checked="Boolean(field.value)"
      :disabled="disabled"
      :aria-describedby="describedBy"
      @change="emit('save', $event.target.checked)"
    />

    <input
      v-else-if="field.type === 'pattern'"
      :id="id"
      v-model="text"
      class="pl-input pl-mono rule-pattern"
      type="text"
      spellcheck="false"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      @change="emit('save', text)"
    />

    <input
      v-else-if="field.type === 'time'"
      :id="id"
      v-model="text"
      class="pl-input is-sm pl-mono rule-time"
      type="text"
      inputmode="numeric"
      placeholder="HH:MM"
      maxlength="5"
      pattern="[0-2][0-9]:[0-5][0-9]"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      @change="text && emit('save', text)"
    />

    <div v-else class="rule-range">
      <input
        :id="id"
        v-model.number="number"
        type="range"
        :min="field.min"
        :max="field.max"
        :step="field.step"
        :disabled="disabled"
        :aria-describedby="describedBy"
        :aria-valuetext="display"
        @change="emit('save', number)"
      />
      <output :for="id" class="pl-mono rule-value">{{ display }}</output>
    </div>
  </div>
</template>

<script setup>
// One rule (reference 11.4 "Control pattern"): name, help line, then a range slider with the live
// value, a switch for on/off rules, a text field for the PR title pattern and a time field for
// "Late after" (a 24-hour HH:MM text field: a native time input follows the browser's 12-hour locale).
// The value is saved when the control is released (change), not on every step.
import { computed, ref, watch } from 'vue';
import { uid } from '@/ui/dialog.js';

const props = defineProps({
  /** { path, label, help, type, min, max, step, unit, value, default } from GET /rules/groups */
  field: { type: Object, required: true },
  disabled: { type: Boolean, default: false },
  error: { type: String, default: '' },
});

const emit = defineEmits(['save']);

const id = uid('rule');

const number = ref(0);
const text = ref('');

watch(
  () => props.field.value,
  (v) => {
    number.value = typeof v === 'number' ? v : Number(v) || 0;
    text.value = v === null || v === undefined ? '' : String(v);
  },
  { immediate: true },
);

/** The live value with the step's decimals and the unit ("6 h", "40%", "0.05"). */
const display = computed(() => {
  const step = Number(props.field.step) || 1;
  const decimals = step % 1 === 0 ? 0 : String(step).split('.')[1]?.length ?? 0;
  const value = Number.isFinite(number.value) ? number.value.toFixed(decimals) : '—';

  return `${value}${props.field.unit ?? ''}`;
});

const describedBy = computed(
  () => [props.field.help && `${id}-help`, props.error && `${id}-error`].filter(Boolean).join(' ') || undefined,
);
</script>

<style scoped>
.rule-row {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(160px, 260px);
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--border);
}

.rule-row:last-child {
  border-bottom: 0;
}

.rule-text {
  display: flex;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.rule-name {
  font-weight: 600;
}

.rule-range {
  display: flex;
  align-items: center;
  gap: var(--space-2);
  min-width: 0;
}

.rule-range input {
  flex: 1;
  min-width: 0;
  accent-color: var(--brand);
}

.rule-value {
  min-width: 56px;
  text-align: right;
}

.rule-time {
  width: 80px;
}

.rule-pattern {
  font-size: 12px;
}

.pl-switch,
input.rule-time {
  justify-self: end;
}

@media (max-width: 560px) {
  .rule-row {
    grid-template-columns: minmax(0, 1fr);
  }

  .pl-switch,
  input.rule-time {
    justify-self: start;
  }
}
</style>
