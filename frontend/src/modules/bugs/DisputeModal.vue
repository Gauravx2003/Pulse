<script setup>
// Dispute attribution (reference 7.5 "Modals"): "Why is this not from your code?" is required; the
// server asks for at least 10 characters and names the field when it refuses.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { PlField, PlModal, showToast } from '@/ui/index.js';

const props = defineProps({
  bug: { type: Object, default: null },
});

const emit = defineEmits(['close', 'saved']);

const reason = ref('');
const error = ref('');
const saving = ref(false);

watch(
  () => props.bug,
  () => {
    reason.value = '';
    error.value = '';
  },
);

async function send() {
  if (!reason.value.trim()) {
    error.value = 'Say why this is not from your code.';
    return;
  }

  saving.value = true;
  error.value = '';

  try {
    await api.post(`/bugs/items/${encodeURIComponent(props.bug.key)}/dispute`, { reason: reason.value }, { quiet: true });

    showToast('Dispute sent to your manager');

    emit('saved');
  } catch (e) {
    error.value = e.message || 'The dispute could not be sent.';
  } finally {
    saving.value = false;
  }
}
</script>

<template>
  <PlModal :open="!!bug" title="Dispute attribution" @close="emit('close')">
    <div v-if="bug" class="body">
      <div><b>{{ bug.key }}</b> · {{ bug.title }}</div>
      <div class="pl-caption">Traced to {{ bug.originRef || '—' }}. Your manager reviews the dispute.</div>
      <PlField
        v-model="reason"
        type="textarea"
        label="Why is this not from your code?"
        placeholder="e.g. the regression came from a config change in another PR"
        required
        :maxlength="1000"
        :error="error"
      />
    </div>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="send">Send dispute</button>
    </template>
  </PlModal>
</template>

<style scoped>
.body {
  display: grid;
  gap: var(--space-3);
}
</style>
