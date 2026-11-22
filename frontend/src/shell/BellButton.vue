<template>
  <div ref="root" class="pl-bell">
    <button
      type="button"
      class="pl-btn pl-btn-sm"
      :aria-expanded="open"
      aria-controls="pl-bell-panel"
      aria-label="Notifications"
      @click="open = !open"
    >
      <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
           stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
        <path d="M6 8a6 6 0 0 1 12 0c0 7 3 9 3 9H3s3-2 3-9" />
        <path d="M10.3 21a1.94 1.94 0 0 0 3.4 0" />
      </svg>
    </button>
    <div v-if="open" id="pl-bell-panel" class="pl-bell-panel" role="region" aria-label="Notifications">
      <PlEmpty text="No notifications yet." />
    </div>
  </div>
</template>

<script setup>
// The notification bell (reference section 3). The list and unread count arrive with the
// notifications service (tasks 5.3 and 6.5); until then the panel shows its empty state.
import { onBeforeUnmount, onMounted, ref } from 'vue';
import { PlEmpty } from '../ui/index.js';

const open = ref(false);
const root = ref(null);

function onDocument(event) {
  if (open.value && root.value && !root.value.contains(event.target)) {
    open.value = false;
  }
}

function onKey(event) {
  if (open.value && event.key === 'Escape') {
    open.value = false;
    root.value?.querySelector('button')?.focus();
  }
}

onMounted(() => {
  document.addEventListener('mousedown', onDocument);
  document.addEventListener('keydown', onKey);
});

onBeforeUnmount(() => {
  document.removeEventListener('mousedown', onDocument);
  document.removeEventListener('keydown', onKey);
});
</script>
