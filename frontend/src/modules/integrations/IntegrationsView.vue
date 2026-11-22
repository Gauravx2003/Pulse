<template>
  <div class="pl-page">
    <PlPageHeader
      eyebrow="Settings"
      title="Integrations"
      caption="Pulse reads from these tools. It only writes when you nudge, give kudos or reassign."
    />

    <PlAlert v-if="loadError" badge="Error" tone="danger">
      {{ loadError }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlCard :title="`Connected (${connected.length})`" :loading="loading" :empty="!loading && !connected.length" empty-text="No tool is connected.">
      <div class="tiles">
        <IntegrationTile
          v-for="t in connected"
          :key="t.id"
          :mark="t.mark"
          :name="t.name"
          :description="t.reads"
          :detail="`Feeds: ${t.feeds}`"
          :status-text="t.status"
          :status-tone="STATE_TONES[t.state] ?? ''"
          :action-label="ACTION_LABELS[t.action] ?? 'Settings'"
          @action="onTileAction(t)"
        />
      </div>
    </PlCard>

    <div class="two">
      <PlCard title="Jira configuration" :subtitle="jiraSubtitle" :loading="loading">
        <template #actions>
          <button type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="!jira || busy" @click="saveJira">Save</button>
        </template>
        <form v-if="jira" class="pl-form" @submit.prevent="saveJira">
          <fieldset class="boards">
            <legend class="pl-field-label">Boards</legend>
            <label v-for="b in JIRA_BOARDS" :key="b" class="board">
              <input v-model="jira.boards" type="checkbox" :value="b" />
              <span class="pl-mono">{{ b }}</span> board
            </label>
          </fieldset>
          <PlField v-model="jira.storyPointsField" label="Story points field" required :maxlength="100" />
          <PlField
            v-model="jira.syncMode"
            label="Sync"
            type="select"
            required
            :options="(jira.syncModes ?? []).map((m) => ({ value: m, label: SYNC_MODE_LABELS[m] ?? m }))"
          />
          <label class="switch-row">
            <input v-model="jira.ignoreBotEdits" type="checkbox" role="switch" class="pl-switch" />
            Ignore edits by bots and non-assignees when computing last activity
          </label>
          <p class="pl-note">
            Endpoints: <span class="pl-mono">/rest/agile/1.0/board/{id}/sprint?state=active</span> ·
            <span class="pl-mono">/sprint/{id}/issue</span> · <span class="pl-mono">/issue/{key}/changelog</span> ·
            <span class="pl-mono">/worklog</span>. Webhooks: issue_updated, worklog_created, comment_created, sprint_started.
          </p>
        </form>
      </PlCard>

      <PlCard title="Sync log" subtitle="Today" :loading="loading">
        <template #actions>
          <button type="button" class="pl-btn pl-btn-sm" :disabled="syncing" @click="syncNow">Sync now</button>
        </template>
        <PlTable :columns="SYNC_COLUMNS" :rows="syncRows" row-key="id" label="Sync log" empty-text="No syncs today.">
          <template #cell-time="{ value }"><span class="pl-mono">{{ value }}</span></template>
          <template #cell-message="{ value }"><span class="pl-caption">{{ value }}</span></template>
          <template #cell-outcome="{ row }">
            <Badge :text="row.ok ? 'OK' : 'Error'" :tone="row.ok ? 'ok' : 'danger'" />
          </template>
        </PlTable>
      </PlCard>
    </div>

    <PlCard title="Credentials" subtitle="Only Admins see this page · secrets are encrypted and shown as the last 4 characters">
      <PlTable :columns="CRED_COLUMNS" :rows="credRows" row-key="id" label="Credentials" :loading="loading" empty-text="No credentials stored.">
        <template #cell-tool="{ row }"><b>{{ row.tool }}</b></template>
        <template #cell-saved="{ value }"><span class="pl-caption">{{ value }}</span></template>
        <template #cell-rotation="{ row }">
          <Badge :text="row.rotation" :tone="row.rotationTone" />
        </template>
        <template #cell-actions="{ row }">
          <span class="row-actions">
            <button type="button" class="pl-btn pl-btn-sm" :aria-label="`View ${row.tool}`" @click="openSettings(row.id)">View</button>
            <button type="button" class="pl-btn pl-btn-sm" :aria-label="`Change ${row.tool}`" @click="openCredentials(row.id)">Change</button>
          </span>
        </template>
      </PlTable>
    </PlCard>

    <PlCard :title="`Available (${available.length})`" :loading="loading" :empty="!loading && !available.length" empty-text="Every tool is connected.">
      <div class="tiles">
        <IntegrationTile
          v-for="t in available"
          :key="t.id"
          :mark="t.mark"
          :name="t.name"
          :description="t.reads"
          :detail="t.note ?? `Feeds: ${t.feeds}`"
          :status-text="t.status"
          action-label="Connect"
          :action-disabled="!t.connectEnabled"
          @action="openCredentials(t.id)"
        />
      </div>
    </PlCard>

    <PlCard title="Credential activity" :loading="loading" :empty="!loading && !activity.length" empty-text="No credential activity yet.">
      <ul class="activity">
        <li v-for="a in activity" :key="a.id">
          <span class="pl-mono when">{{ formatDateTime(a.at) }}</span>
          <span><b>{{ toolName(a.tool) }}</b> · {{ a.message }}</span>
          <span class="pl-caption">{{ a.by }}</span>
        </li>
      </ul>
    </PlCard>

    <SettingsModal
      :open="settingsOpen"
      :tool="current"
      @close="settingsOpen = false"
      @change-credentials="switchToCredentials"
      @changed="load"
    />
    <CredentialsModal :open="credentialsOpen" :tool="current" @close="credentialsOpen = false" @saved="onCredentialsSaved" />
  </div>
</template>

<script setup>
// Integrations (reference 11.3 "Screen detail — Integrations", section 5; spec admin/integrations-
// connectors). Admin only (integrations.manage). Phase 1 is a seeded simulation: Sync now and Test
// connection make no outbound call, and credentials are stored as masked placeholders.
import { computed, onMounted, ref } from 'vue';
import { api } from '@/api/client.js';
import { Badge, IntegrationTile } from '@/kit/index.js';
import { formatDateTime, formatDateWithYear } from '@/ui/format.js';
import { PlAlert, PlCard, PlField, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import CredentialsModal from './CredentialsModal.vue';
import SettingsModal from './SettingsModal.vue';
import { ACTION_LABELS, JIRA_BOARDS, STATE_TONES, SYNC_MODE_LABELS } from './presentation.js';

const SYNC_COLUMNS = [
  { key: 'time', label: 'Time' },
  { key: 'tool', label: 'Tool' },
  { key: 'message', label: 'Result' },
  { key: 'outcome', label: 'Outcome' },
];

const CRED_COLUMNS = [
  { key: 'tool', label: 'Tool' },
  { key: 'method', label: 'Method' },
  { key: 'saved', label: 'Saved' },
  { key: 'rotation', label: 'Rotation' },
  { key: 'lastTest', label: 'Last test' },
  { key: 'actions', label: 'Actions' },
];

const tiles = ref([]);
const details = ref({});
const jira = ref(null);
const syncLog = ref([]);
const activity = ref([]);
const loading = ref(true);
const loadError = ref('');
const busy = ref(false);
const syncing = ref(false);

const current = ref(null);
const settingsOpen = ref(false);
const credentialsOpen = ref(false);

const connected = computed(() => tiles.value.filter((t) => t.connected));
const available = computed(() => tiles.value.filter((t) => !t.connected));

const jiraSubtitle = computed(() => {
  const d = details.value.jira;

  if (!d?.tile?.connected) {
    return 'Not connected';
  }

  return [d.method, d.credentials?.email ?? d.credentials?.site].filter(Boolean).join(' · ');
});

const syncRows = computed(() =>
  syncLog.value.slice(0, 12).map((s) => ({
    id: s.id,
    time: formatDateTime(s.started_at).split(', ')[1] ?? '—',
    tool: toolName(s.tool),
    message: s.message,
    ok: s.outcome === 'success',
  })),
);

const credRows = computed(() =>
  connected.value
    .map((t) => details.value[t.id])
    .filter(Boolean)
    .map((d) => ({
      id: d.tile.id,
      tool: d.tile.name,
      method: d.method ?? 'No credentials stored',
      saved: d.savedAt ? `${formatDateWithYear(d.savedAt)} · ${d.savedBy ?? '—'}` : '—',
      rotation: rotationText(d),
      rotationTone: d.tile.state === 'token_expiring' ? 'accent' : d.tile.state === 'sync_failed' ? 'danger' : 'ok',
      lastTest: d.testedAt ? `${formatDateTime(d.testedAt)} · ${d.testOk ? 'passed' : 'failed'}` : 'Never',
    })),
);

function rotationText(d) {
  if (d.tile.state === 'token_expiring') {
    return d.tile.status;
  }

  return d.rotateDays ? `Every ${d.rotateDays} d` : 'No rotation';
}

function toolName(id) {
  if (id === 'seed') {
    return 'Seed data';
  }

  return tiles.value.find((t) => t.id === id)?.name ?? id;
}

async function load() {
  loading.value = true;
  loadError.value = '';

  try {
    const [toolsRes, jiraRes, logRes, activityRes] = await Promise.all([
      api.get('/integrations/tools'),
      api.get('/integrations/jira/config'),
      api.get('/integrations/sync-log'),
      api.get('/integrations/credential-log'),
    ]);

    tiles.value = toolsRes.tiles ?? [];
    jira.value = { ...jiraRes, boards: [...(jiraRes.boards ?? [])] };
    syncLog.value = logRes.items ?? [];
    activity.value = activityRes.items ?? [];

    const all = await Promise.all(connected.value.map((t) => api.get(`/integrations/tools/${t.id}`)));

    details.value = Object.fromEntries(all.map((d) => [d.tile.id, d]));
  } catch (e) {
    loadError.value = e.message;
  } finally {
    loading.value = false;
  }
}

async function loadTool(id) {
  current.value = details.value[id] ?? (await api.get(`/integrations/tools/${id}`));
}

async function openSettings(id) {
  await loadTool(id);
  settingsOpen.value = true;
}

async function openCredentials(id) {
  current.value = await api.get(`/integrations/tools/${id}`);
  credentialsOpen.value = true;
}

function switchToCredentials() {
  settingsOpen.value = false;
  credentialsOpen.value = true;
}

/** Settings for a connected tool; Fix (failed sync or expiring token) goes straight to the credentials. */
function onTileAction(tile) {
  if (tile.action === 'fix') {
    openCredentials(tile.id);
  } else {
    openSettings(tile.id);
  }
}

function onCredentialsSaved() {
  credentialsOpen.value = false;
  load();
}

async function saveJira() {
  if (!jira.value.boards.length) {
    showToast('Pick at least one board.');

    return;
  }

  busy.value = true;

  try {
    const { boards, storyPointsField, syncMode, ignoreBotEdits } = jira.value;

    await api.put('/integrations/jira/config', { boards, storyPointsField, syncMode, ignoreBotEdits });
    showToast(`Jira settings saved · ${boards.join(', ')}`);
  } catch {
    // shown by the API client
  } finally {
    busy.value = false;
  }
}

async function syncNow() {
  syncing.value = true;

  try {
    const res = await api.post('/integrations/tools/jira/sync');

    showToast(res.sync?.message ? `Synced · ${res.sync.message}` : 'Synced');
    await load();
  } catch {
    // shown by the API client
  } finally {
    syncing.value = false;
  }
}

onMounted(load);
</script>

<style scoped>
.tiles {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(min(100%, 340px), 1fr));
  gap: var(--space-3);
}

.two {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(min(100%, 420px), 1fr));
  gap: var(--space-4);
  align-items: start;
}

.boards {
  border: 0;
  margin: 0;
  padding: 0;
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-2) var(--space-4);
}

.boards legend {
  margin-bottom: var(--space-2);
}

.board,
.switch-row {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  font-size: 14px;
}

.row-actions {
  display: inline-flex;
  gap: var(--space-1);
}

.activity {
  list-style: none;
  margin: 0;
  padding: 0;
}

.activity li {
  display: grid;
  grid-template-columns: max-content minmax(0, 1fr) max-content;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
  align-items: baseline;
}

.activity li:last-child {
  border-bottom: 0;
}

.when {
  font-size: 12px;
}

@media (max-width: 560px) {
  .activity li {
    grid-template-columns: minmax(0, 1fr);
    gap: 2px;
  }
}
</style>
