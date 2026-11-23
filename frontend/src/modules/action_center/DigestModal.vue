<script setup>
// Weekly digest preview (reference 6.1 "Weekly digest", spec "Digest preview"): the Monday summary sent by
// email and Slack: high-priority actions and the first five, people to watch and the week's wins. Close
// only. GET /action_center/digest.
import { ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlLoading, PlModal } from '@/ui/index.js';

const props = defineProps({
  open: { type: Boolean, default: false },
});

const emit = defineEmits(['close']);

const digest = ref(null);
const error = ref('');

watch(
  () => props.open,
  async (open) => {
    if (!open) {
      return;
    }

    digest.value = null;
    error.value = '';

    try {
      digest.value = await api.get('/action_center/digest');
    } catch (e) {
      error.value = e.message || 'The digest could not be loaded.';
    }
  },
);
</script>

<template>
  <PlModal :open="open" size="lg" :title="`Weekly digest · Monday ${digest ? formatDate(digest.date) : ''}`" @close="emit('close')">
    <p class="pl-caption">Sent to you by email and Slack every Monday at 9:00. Employees get their own version with their next steps and wins.</p>
    <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!digest" />
    <template v-else>
      <section class="note" aria-label="Actions">
        <b>{{ digest.high }} high-priority action{{ digest.high === 1 ? '' : 's' }}</b> · {{ digest.total }} in total
        <ul>
          <li v-for="(i, n) in digest.items" :key="n">{{ i.personName }}: {{ i.text }}</li>
        </ul>
      </section>
      <section class="note" aria-label="People to watch">
        <b>People to watch:</b>
        {{ digest.watch.length ? digest.watch.map((w) => `${w.name} (${w.signals})`).join('; ') : 'nobody' }}
      </section>
      <section class="note" aria-label="Wins">
        <b>Wins last week:</b>
        {{ digest.wins.length ? digest.wins.map((w) => `${w.who} — ${w.what}`).join('; ') : '—' }}
      </section>
    </template>
    <template #actions>
      <button type="button" class="pl-btn pl-btn-primary" @click="emit('close')">Close</button>
    </template>
  </PlModal>
</template>

<style scoped>
.note {
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
  padding: var(--space-3);
  margin-top: var(--space-3);
  overflow-wrap: anywhere;
}

.note ul {
  margin: var(--space-2) 0 0;
  padding-left: var(--space-4);
}
</style>
