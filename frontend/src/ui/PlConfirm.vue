<template>
  <PlModal :open="open" :title="title" :initial-focus="focusCancel" @close="emit('cancel')">
    <p class="pl-confirm-text">{{ message }}</p>
    <template #actions>
      <button ref="cancelBtn" type="button" class="pl-btn" @click="emit('cancel')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-danger is-confirm" :disabled="busy" @click="emit('confirm')">
        {{ confirmLabel }}
      </button>
    </template>
  </PlModal>
</template>

<script setup>
// Confirmation for destructive actions (spec platform/app-shell-ui, "Feedback patterns"). Focus starts
// on Cancel so Enter never confirms by accident.
import { ref } from 'vue';
import PlModal from './PlModal.vue';

defineProps({
  open: { type: Boolean, default: false },
  title: { type: String, required: true },
  message: { type: String, required: true },
  confirmLabel: { type: String, default: 'Delete' },
  busy: { type: Boolean, default: false },
});

const emit = defineEmits(['confirm', 'cancel']);

const cancelBtn = ref(null);

function focusCancel() {
  return cancelBtn.value;
}
</script>
