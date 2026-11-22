<template>
  <div class="pl-field">
    <label :for="id" class="pl-field-label">
      {{ label }}<span v-if="required" class="req" aria-hidden="true">*</span>
    </label>
    <select
      v-if="type === 'select'"
      :id="id"
      class="pl-input"
      :value="modelValue ?? ''"
      :required="required"
      :disabled="disabled"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      @change="emit('update:modelValue', $event.target.value)"
    >
      <option value="" :disabled="required">{{ placeholder || 'Select…' }}</option>
      <option v-for="o in options" :key="o.value" :value="o.value">{{ o.label }}</option>
    </select>
    <textarea
      v-else-if="type === 'textarea'"
      :id="id"
      class="pl-input"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :maxlength="maxlength"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      @input="emit('update:modelValue', $event.target.value)"
    ></textarea>
    <input
      v-else
      :id="id"
      class="pl-input"
      :type="type"
      :value="modelValue ?? ''"
      :placeholder="placeholder"
      :required="required"
      :disabled="disabled"
      :autocomplete="autocomplete"
      :maxlength="maxlength"
      :aria-invalid="error ? 'true' : undefined"
      :aria-describedby="describedBy"
      @input="emit('update:modelValue', $event.target.value)"
    />
    <div v-if="hint" :id="`${id}-hint`" class="pl-field-hint">{{ hint }}</div>
    <p v-if="error" :id="`${id}-error`" class="pl-field-error">{{ error }}</p>
  </div>
</template>

<script setup>
// Form field (reference section 3, "Forms"): label above the field, required marked *, inline error
// in danger-ink, selects with a "Select…" placeholder for required choices.
import { computed } from 'vue';
import { uid } from './dialog.js';

const props = defineProps({
  label: { type: String, required: true },
  modelValue: { type: [String, Number], default: '' },
  /** text, email, password, number, date, select or textarea */
  type: { type: String, default: 'text' },
  /** [{ value, label }] for type select */
  options: { type: Array, default: () => [] },
  placeholder: { type: String, default: '' },
  required: { type: Boolean, default: false },
  disabled: { type: Boolean, default: false },
  autocomplete: { type: String, default: undefined },
  maxlength: { type: Number, default: undefined },
  hint: { type: String, default: '' },
  error: { type: String, default: '' },
});

const emit = defineEmits(['update:modelValue']);

const id = uid('field');

const describedBy = computed(() => {
  const ids = [props.hint && `${id}-hint`, props.error && `${id}-error`].filter(Boolean);

  return ids.length ? ids.join(' ') : undefined;
});
</script>
