<script setup>
// Resolve <ticket> (reference 7.5 "Modals"): hours spent and a resolution note are required; the
// resolved ticket becomes a 1-point item awaiting the manager.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { PlField, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  ticket: { type: Object, default: null },
});

const emit = defineEmits(['close', 'saved']);

const hours = ref('1');
const note = ref('');
const errors = ref({});
const saving = ref(false);

watch(
  () => props.ticket,
  () => {
    hours.value = '1';
    note.value = '';
    errors.value = {};
  },
);

async function resolve() {
  const h = Number(hours.value);

  errors.value = {};

  if (!(h > 0)) {
    errors.value.hours = 'Enter the hours spent.';
  }

  if (!note.value.trim()) {
    errors.value.note = 'Add a resolution note.';
  }

  if (Object.keys(errors.value).length) {
    return;
  }

  saving.value = true;

  try {
    await api.post(`/bugs/tickets/${encodeURIComponent(props.ticket.key)}/resolve`, { hours: h, note: note.value }, { quiet: true });

    showToast(`${props.ticket.key} resolved · ${h} h · points sent for manager confirmation`);

    emit('saved');
  } catch (e) {
    errors.value = { [e.field ?? 'note']: e.message || 'The ticket could not be resolved.' };
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <PlModal :open="!!ticket" :title="ticket ? `Resolve ${ticket.key}` : 'Resolve ticket'" @close="emit('close')">
    <div v-if="ticket" class="body">
      <div>{{ ticket.title }} · {{ ticket.customer ?? '—' }}</div>
      <PlField v-model="hours" type="number" label="Hours spent" required :error="errors.hours" />
      <PlField v-model="note" type="textarea" label="Resolution note" required :maxlength="2000" :error="errors.note" />
    </div>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="resolve">Resolve ticket</button>
    </template>
  </PlModal>
</template>

<style scoped>
.body {
  display: grid;
  gap: var(--space-3);
}
</style>
