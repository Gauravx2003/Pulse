<template>
  <div v-if="status" class="pl-sync" :class="`is-${status.state}`" role="status">
    <i aria-hidden="true"></i>
    <span>{{ text }}</span>
  </div>
</template>

<script setup>
// Jira sync status in the top bar (reference section 3), from GET /sync-status; the state is a word,
// the dot color is second (spec platform/app-shell-ui, "Accessibility").
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { api } from '../api/client.js';

const REFRESH_MS = 60_000;

const status = ref(null);
const now = ref(Date.now());
let timer = null;

async function load() {
  try {
    status.value = await api.get('/sync-status', { quiet: true });
  } catch {
    status.value = null;
  }

  now.value = Date.now();
}

onMounted(() => {
  load();
  timer = setInterval(load, REFRESH_MS);
});

onBeforeUnmount(() => clearInterval(timer));

function ago(iso) {
  const minutes = Math.max(0, Math.round((now.value - Date.parse(iso)) / 60_000));

  if (!Number.isFinite(minutes)) {
    return '';
  }

  if (minutes < 1) {
    return 'just now';
  }

  if (minutes < 60) {
    return `${minutes} min ago`;
  }

  const hours = Math.round(minutes / 60);

  return hours < 48 ? `${hours} h ago` : `${Math.round(hours / 24)} days ago`;
}

const text = computed(() => {
  const s = status.value;

  switch (s?.state) {
    case 'synced':
      return s.lastSyncedAt ? `Jira synced ${ago(s.lastSyncedAt)}` : 'Jira synced';
    case 'syncing':
      return 'Jira syncing…';
    case 'failed':
      return s.lastSyncedAt ? `Jira sync failed · last good ${ago(s.lastSyncedAt)}` : 'Jira sync failed';
    default:
      return 'Jira not synced yet';
  }
});
</script>
