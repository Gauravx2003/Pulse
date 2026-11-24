<template>
  <PlModal :open="open" :title="title" @close="emit('close')">
    <p v-if="intro" class="pl-caption">{{ intro }}</p>
    <template v-for="f in fields" :key="f.key">
      <label v-if="f.type === 'checkbox'" class="row">
        <input v-model="values[f.key]" type="checkbox" />
        {{ f.label }}
      </label>
      <PlField
        v-else
        v-model="values[f.key]"
        :type="f.type ?? 'text'"
        :label="f.label"
        :options="f.options ?? []"
        :required="!!f.required"
        :maxlength="f.maxlength"
        :hint="f.hint ?? ''"
        :error="errors[f.key] ?? ''"
      />
    </template>
    <PlFormError v-if="error" :message="error" />
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="submit">{{ submitLabel }}</button>
    </template>
  </PlModal>
</template>

<script setup>
// One form in a modal for the Training actions (check-in, review, assessment, final review, sign-off,
// acknowledgement, session, plan, assign ...). The parent passes the fields and an async `save(values)`;
// a server error naming a field shows under that field, any other under the form.
import { reactive, ref, watch } from 'vue';
import { PlField, PlFormError, PlModal } from '@/ui/index.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  intro: { type: String, default: '' },
  /** [{ key, label, type?: text|textarea|number|date|select|checkbox, options?, required?, maxlength?, hint? }] */
  fields: { type: Array, required: true },
  initial: { type: Object, default: () => ({}) },
  submitLabel: { type: String, default: 'Save' },
  /** async (values) => void; throws an ApiError to show it */
  save: { type: Function, required: true },
});

const emit = defineEmits(['close', 'saved']);

const values = reactive({});
const errors = reactive({});
const error = ref('');
const busy = ref(false);

function reset() {
  for (const k of Object.keys(values)) {
    delete values[k];
  }

  for (const f of props.fields) {
    const v = props.initial[f.key];

    values[f.key] = f.type === 'checkbox' ? !!v : v === undefined || v === null ? '' : String(v);
  }

  for (const k of Object.keys(errors)) {
    delete errors[k];
  }

  error.value = '';
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      reset();
    }
  },
  { immediate: true },
);

async function submit() {
  busy.value = true;
  error.value = '';

  for (const k of Object.keys(errors)) {
    delete errors[k];
  }

  const out = {};

  for (const f of props.fields) {
    const v = values[f.key];

    if (f.type === 'checkbox') {
      out[f.key] = !!v;
    } else if (v === '' || v === undefined) {
      continue;
    } else {
      out[f.key] = f.type === 'number' ? Number(v) : v;
    }
  }

  try {
    await props.save(out);

    emit('saved');
  } catch (e) {
    if (e.field && props.fields.some((f) => f.key === e.field)) {
      errors[e.field] = e.message;
    } else {
      error.value = e.message || 'Could not save.';
    }
  } finally {
    busy.value = false;
  }
}
</script>
