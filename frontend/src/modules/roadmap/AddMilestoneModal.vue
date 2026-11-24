<script setup>
// Add milestone (reference 9.2 "Screen detail — Road map"): Outcome, When and Grows value, with Cancel /
// Propose. The milestone shows as Proposed until the manager confirms it; the server checks who may
// propose and names the field it refuses.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { CULTURE_VALUES } from '@/kit/index.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  personId: { type: String, default: '' },
  /** The suggested "When", for example "Q1 2027". */
  defaultWhen: { type: String, default: '' },
});

const emit = defineEmits(['close', 'saved']);

const form = ref({ title: '', when: '', value: '' });
const errors = ref({});
const formError = ref('');
const saving = ref(false);

const valueOptions = CULTURE_VALUES.map((v) => ({ value: v, label: v }));

watch(
  () => props.open,
  (open) => {
    if (open) {
      form.value = { title: '', when: props.defaultWhen, value: '' };
      errors.value = {};
      formError.value = '';
    }
  },
);

function validate() {
  const e = {};

  if (!form.value.title.trim()) {
    e.title = 'Write the outcome.';
  }

  if (!form.value.when.trim()) {
    e.when = 'Say when, for example Q1 2027.';
  }

  errors.value = e;

  return !Object.keys(e).length;
}

async function propose() {
  if (!validate()) {
    return;
  }

  saving.value = true;
  formError.value = '';

  try {
    await api.post(
      `/roadmap/people/${props.personId}/milestones`,
      { title: form.value.title.trim(), when: form.value.when.trim(), ...(form.value.value ? { value: form.value.value } : {}) },
      { quiet: true },
    );

    showToast('Milestone proposed — manager will confirm');

    emit('saved');
  } catch (e) {
    if (e.field === 'title' || e.field === 'when') {
      errors.value = { [e.field]: e.message };
    } else {
      formError.value = e.message || 'The milestone could not be proposed.';
    }
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <PlModal :open="open" title="Add milestone" @close="emit('close')">
    <form class="form" @submit.prevent="propose">
      <PlFormError v-if="formError" :message="formError" />
      <PlField v-model="form.title" label="Outcome" required :maxlength="200" placeholder="e.g. Author 2 ADRs for the event bus" :error="errors.title" />
      <PlField v-model="form.when" label="When" required :maxlength="40" :error="errors.when" />
      <PlField v-model="form.value" type="select" label="Grows value" placeholder="—" :options="valueOptions" />
    </form>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="propose">Propose</button>
    </template>
  </PlModal>
</template>

<style scoped>
.form {
  display: grid;
  gap: var(--space-3);
}
</style>
