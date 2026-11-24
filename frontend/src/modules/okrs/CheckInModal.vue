<script setup>
// Check-in (reference 9.1): the key result, "Start · target", the new current value and an optional
// note; the toast shows the objective's new progress.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { PlField, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  keyResult: { type: Object, default: null },
});

const emit = defineEmits(['close', 'saved']);

const value = ref('');
const note = ref('');
const error = ref('');
const saving = ref(false);

watch(
  () => props.keyResult,
  (kr) => {
    value.value = kr ? String(kr.currentValue ?? '') : '';
    note.value = '';
    error.value = '';
  },
);

async function save() {
  const n = Number(value.value);

  if (value.value === '' || Number.isNaN(n)) {
    error.value = 'Enter the current value.';
    return;
  }

  saving.value = true;
  error.value = '';

  try {
    const result = await api.post(`/okrs/key-results/${props.keyResult.id}/check-ins`, { value: n, note: note.value.trim() || null }, { quiet: true });

    const progress = result?.objective?.progress;

    showToast(typeof progress === 'number' ? `Check-in saved · objective at ${Math.round(progress * 100)}%` : 'Check-in saved');

    emit('saved');
  } catch (e) {
    error.value = e.message || 'The check-in could not be saved.';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <PlModal :open="!!keyResult" title="Check-in" @close="emit('close')">
    <div v-if="keyResult" class="body">
      <div>{{ keyResult.description }}</div>
      <div class="pl-caption">Start {{ keyResult.startValue }} · target {{ keyResult.targetValue }} {{ keyResult.unit }}</div>
      <PlField v-model="value" type="number" label="Current value" required :error="error" />
      <PlField v-model="note" type="textarea" label="Note" :maxlength="500" hint="Optional" />
    </div>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="save">Save check-in</button>
    </template>
  </PlModal>
</template>

<style scoped>
.body {
  display: grid;
  gap: var(--space-3);
}
</style>
