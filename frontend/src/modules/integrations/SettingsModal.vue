<template>
  <PlModal :open="open" :title="tool?.tile?.name ?? 'Settings'" size="lg" @close="emit('close')">
    <PlTabs v-model="tab" :tabs="TABS" label="Settings sections" :panel-id="panelId" />
    <div :id="panelId" role="tabpanel" class="settings-panel">
      <template v-if="tab === 'credentials'">
        <dl class="facts">
          <dt>Method</dt>
          <dd>{{ tool?.method ?? 'Not set' }}</dd>
          <template v-for="(value, key) in tool?.credentials ?? {}" :key="key">
            <dt>{{ labelOf(key) }}</dt>
            <dd class="pl-mono">{{ value }}</dd>
          </template>
          <dt>Saved</dt>
          <dd>{{ tool?.savedAt ? `${formatDateWithYear(tool.savedAt)} · ${tool.savedBy ?? '—'}` : '—' }}</dd>
          <dt>Rotation</dt>
          <dd>{{ tool?.rotateDays ? `Every ${tool.rotateDays} days` : '—' }}</dd>
          <dt>Last test</dt>
          <dd>
            <template v-if="tool?.testedAt">
              {{ formatDateTime(tool.testedAt) }} ·
              <Badge :text="tool.testOk ? 'Passed' : 'Failed'" :tone="tool.testOk ? 'ok' : 'danger'" />
            </template>
            <template v-else>Never</template>
          </dd>
        </dl>
        <p class="pl-caption">Secrets are shown as the last 4 characters only.</p>
      </template>

      <template v-else-if="tab === 'scopes'">
        <dl class="facts">
          <dt>Reads</dt>
          <dd>{{ tool?.tile?.reads }}</dd>
          <dt>Feeds</dt>
          <dd>{{ tool?.tile?.feeds }}</dd>
          <dt>Pulse needs</dt>
          <dd class="pl-mono">{{ (tool?.scopes ?? []).join(', ') || '—' }}</dd>
          <dt>Reported{{ tool?.reportedScopesSimulated ? ' (simulated)' : '' }}</dt>
          <dd class="pl-mono">{{ (tool?.reportedScopes ?? []).join(', ') || '—' }}</dd>
        </dl>
        <PlAlert v-if="tool?.scopeWarning" badge="Scope">{{ tool.scopeWarning }}</PlAlert>
      </template>

      <template v-else>
        <PlField
          v-model="syncMode"
          label="Sync mode"
          type="select"
          required
          :options="(tool?.syncModes ?? []).map((m) => ({ value: m, label: SYNC_MODE_LABELS[m] ?? m }))"
        />
        <button type="button" class="pl-btn pl-btn-sm save-sync" :disabled="busy || syncMode === tool?.syncMode" @click="saveSync">
          Save sync mode
        </button>
        <PlTable :columns="SYNC_COLUMNS" :rows="syncRows" label="Recent syncs" empty-text="No syncs yet." />
      </template>
    </div>
    <template #actions>
      <button type="button" class="pl-btn pl-btn-danger" :disabled="busy" @click="confirmDisconnect = true">Disconnect</button>
      <button type="button" class="pl-btn" @click="emit('change-credentials')">Test connection</button>
      <button type="button" class="pl-btn" @click="emit('change-credentials')">Change credentials</button>
      <button type="button" class="pl-btn pl-btn-primary" @click="emit('close')">Close</button>
    </template>
  </PlModal>

  <PlConfirm
    :open="confirmDisconnect"
    :title="`Disconnect ${tool?.tile?.name ?? ''}?`"
    message="Pulse stops reading from this tool and removes the stored credentials. Data already synced stays."
    confirm-label="Disconnect"
    :busy="busy"
    @cancel="confirmDisconnect = false"
    @confirm="disconnect"
  />
</template>

<script setup>
// Settings modal (reference 11.3 "Settings modal (<tool>)"): tabs Credentials · Data & scopes · Sync;
// actions Change credentials · Test connection · Disconnect · Close. Stored secrets are masked by the
// server; testing needs the secret again, so Test connection opens the credentials form.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { uid } from '@/ui/dialog.js';
import { formatDateTime, formatDateWithYear } from '@/ui/format.js';
import { PlAlert, PlConfirm, PlField, PlModal, PlTable, PlTabs, showToast } from '@/ui/index.js';
import { SYNC_MODE_LABELS } from './presentation.js';

const TABS = [
  { key: 'credentials', label: 'Credentials' },
  { key: 'scopes', label: 'Data & scopes' },
  { key: 'sync', label: 'Sync' },
];

const SYNC_COLUMNS = [
  { key: 'when', label: 'When' },
  { key: 'message', label: 'Result' },
  { key: 'outcome', label: 'Outcome' },
];

const props = defineProps({
  open: { type: Boolean, default: false },
  tool: { type: Object, default: null },
});

const emit = defineEmits(['close', 'change-credentials', 'changed']);

const panelId = uid('settings');
const tab = ref('credentials');
const syncMode = ref('');
const busy = ref(false);
const confirmDisconnect = ref(false);

watch(
  () => [props.open, props.tool],
  () => {
    if (props.open) {
      syncMode.value = props.tool?.syncMode ?? '';
    }
  },
  { immediate: true },
);

watch(
  () => props.open,
  (open) => {
    if (open) {
      tab.value = 'credentials';
    }
  },
);

const syncRows = computed(() =>
  (props.tool?.syncLog?.items ?? props.tool?.syncLog ?? []).map((s) => ({
    id: s.id,
    when: formatDateTime(s.started_at),
    message: s.message,
    outcome: s.outcome === 'success' ? 'OK' : 'Error',
  })),
);

function labelOf(key) {
  for (const m of props.tool?.methods ?? []) {
    const f = m.fields.find((x) => x.key === key);

    if (f) {
      return f.label;
    }
  }

  return key;
}

async function saveSync() {
  busy.value = true;

  try {
    await api.put(`/integrations/tools/${props.tool.tile.id}/settings`, { syncMode: syncMode.value });
    showToast(`Sync mode saved · ${SYNC_MODE_LABELS[syncMode.value] ?? syncMode.value}`);
    emit('changed');
  } catch {
    // shown by the API client
  } finally {
    busy.value = false;
  }
}

async function disconnect() {
  busy.value = true;

  try {
    await api.delete(`/integrations/tools/${props.tool.tile.id}/credentials`);
    showToast(`Disconnected · ${props.tool.tile.name}`);
    confirmDisconnect.value = false;
    emit('changed');
    emit('close');
  } catch {
    // shown by the API client
  } finally {
    busy.value = false;
  }
}
</script>

<style scoped>
.settings-panel {
  display: flex;
  flex-direction: column;
  gap: var(--space-3);
}

.facts {
  display: grid;
  grid-template-columns: minmax(120px, max-content) minmax(0, 1fr);
  gap: var(--space-2) var(--space-4);
  margin: 0;
}

.facts dt {
  color: var(--ink-muted);
  font-size: 13px;
}

.facts dd {
  margin: 0;
  overflow-wrap: anywhere;
}

.save-sync {
  align-self: flex-start;
}
</style>
