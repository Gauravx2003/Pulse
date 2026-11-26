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
          :description="t.reads ?? ''"
          :detail="t.feeds ? `Feeds: ${t.feeds}` : ''"
          :status-text="t.status"
          :status-tone="STATE_TONES[t.state] ?? ''"
          :action-label="ACTION_LABELS[t.action] ?? 'Settings'"
          :badge="isLive(t) ? 'Live' : ''"
          @action="onTileAction(t)"
        >
          <template v-if="isLive(t)" #extra>
            <button
              type="button"
              class="pl-btn pl-btn-sm"
              :disabled="isSyncing(t)"
              :aria-label="`Sync ${t.name} now`"
              @click="liveSync(t)"
            >
              {{ isSyncing(t) ? 'Syncing…' : 'Sync now' }}
            </button>
          </template>
        </IntegrationTile>
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

    <PlCard
      title="Scheduled jobs"
      subtitle="Times are Asia/Kolkata · a failed job is retried, then shows here and in the Action center"
      :loading="loading"
    >
      <PlTable :columns="JOB_COLUMNS" :rows="jobRows" row-key="name" label="Scheduled jobs" empty-text="No scheduled jobs.">
        <template #cell-job="{ row }">
          <b class="pl-mono">{{ row.name }}</b>
          <div class="pl-caption">{{ row.description }}</div>
        </template>
        <template #cell-next="{ value }"><span class="pl-caption">{{ value }}</span></template>
        <template #cell-last="{ row }">
          <span v-if="row.lastOutcome" class="last-run">
            <Badge :text="row.lastOutcome" :tone="row.lastTone" />
            <span class="pl-caption">{{ row.lastAt }}</span>
          </span>
          <span v-else class="pl-caption">Never</span>
          <div v-if="row.lastError" class="pl-caption">{{ row.lastError }}</div>
        </template>
        <template #cell-actions="{ row }">
          <button
            type="button"
            class="pl-btn pl-btn-sm"
            :aria-label="`Run ${row.name} now`"
            :disabled="runningJob !== ''"
            @click="runJob(row.name)"
          >
            {{ runningJob === row.name ? 'Running…' : 'Run now' }}
          </button>
        </template>
      </PlTable>
    </PlCard>

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
          :description="t.reads ?? ''"
          :detail="t.note ?? (t.feeds ? `Feeds: ${t.feeds}` : '')"
          :status-text="t.status"
          action-label="Connect"
          :action-disabled="!t.connectEnabled"
          @action="openCredentials(t.id)"
        />
      </div>
    </PlCard>

    <UnmatchedAccountsPanel v-if="liveTools.length" ref="accountsPanel" :tools="liveTools" />

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
      :connection="currentConnection"
      @close="settingsOpen = false"
      @change-credentials="switchToCredentials"
      @changed="load"
    />
    <CredentialsModal
      :open="credentialsOpen && Boolean(currentConnection)"
      :tile="current?.tile ?? null"
      :connection="currentConnection"
      @close="credentialsOpen = false"
      @saved="onCredentialsSaved"
    />
    <SeededCredentialsModal
      :open="credentialsOpen && !currentConnection"
      :tool="current"
      @close="credentialsOpen = false"
      @saved="onCredentialsSaved"
    />
  </div>
</template>

<script setup>
// Integrations (reference 11.3 "Screen detail — Integrations", section 5; spec admin/integrations-
// connectors). Admin only (integrations.manage). Phase 1 is a seeded simulation: Sync now and Test
// connection make no outbound call, and credentials are stored as masked placeholders. The Scheduled
// jobs card (task 5.5, spec platform/jobs-scheduler) lists each job's next and last run and lets the
// Admin run one now.
//
// Live connectors (task 24.7, change pulse-v2-live-connectors) need nothing tool-specific here: a
// tile with `liveConnector` gets its form from GET /integrations/:tool/connection (CredentialsModal),
// a Live badge and its own Sync now once a live connection is saved, and the Unmatched accounts panel
// lists the accounts its syncs saw. While a live sync runs the tiles are re-read every few seconds.
import { computed, onBeforeUnmount, onMounted, ref } from 'vue';
import { api } from '@/api/client.js';
import { reloadPeriods } from '@/shell/period.js';
import { Badge, IntegrationTile } from '@/kit/index.js';
import { formatDateTime, formatDateWithYear } from '@/ui/format.js';
import { PlAlert, PlCard, PlField, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import CredentialsModal from './CredentialsModal.vue';
import SeededCredentialsModal from './SeededCredentialsModal.vue';
import SettingsModal from './SettingsModal.vue';
import UnmatchedAccountsPanel from './UnmatchedAccountsPanel.vue';
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

const JOB_COLUMNS = [
  { key: 'job', label: 'Job' },
  { key: 'next', label: 'Next run' },
  { key: 'last', label: 'Last run' },
  { key: 'actions', label: 'Actions' },
];

const JOB_OUTCOMES = {
  succeeded: { text: 'Succeeded', tone: 'ok' },
  failed: { text: 'Failed', tone: 'danger' },
  running: { text: 'Retrying', tone: 'accent' },
};

const tiles = ref([]);
const jobs = ref([]);
const runningJob = ref('');
const details = ref({});
const jira = ref(null);
const syncLog = ref([]);
const activity = ref([]);
const loading = ref(true);
const loadError = ref('');
const busy = ref(false);
const syncing = ref(false);

/** GET /integrations/:tool/connection of every tool with a live connector */
const connections = ref({});
/** tools whose Sync now this page started and that has not answered yet */
const syncingHere = ref(new Set());
const accountsPanel = ref(null);
let pollTimer = null;

const SYNC_POLL_MS = 4000;

const current = ref(null);
const currentConnection = computed(() => (current.value ? (connections.value[current.value.tile?.id] ?? null) : null));
const settingsOpen = ref(false);
const credentialsOpen = ref(false);

const connected = computed(() => tiles.value.filter((t) => t.connected));
const liveTools = computed(() => tiles.value.filter((t) => t.liveConnector).map((t) => ({ id: t.id, name: t.name })));
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
    .map((d) => (isLive(d.tile) ? liveCredRow(d) : {
      id: d.tile.id,
      tool: d.tile.name,
      method: d.method ?? 'No credentials stored',
      saved: d.savedAt ? `${formatDateWithYear(d.savedAt)} · ${d.savedBy ?? '—'}` : '—',
      rotation: rotationText(d),
      rotationTone: d.tile.state === 'token_expiring' ? 'accent' : d.tile.state === 'sync_failed' ? 'danger' : 'ok',
      lastTest: d.testedAt ? `${formatDateTime(d.testedAt)} · ${d.testOk ? 'passed' : 'failed'}` : 'Never',
    })),
);

/** A credentials row of a live connection: the secret's last 4, when it was saved and its expiry. */
function liveCredRow(d) {
  const stored = connections.value[d.tile.id]?.connection ?? {};

  return {
    id: d.tile.id,
    tool: d.tile.name,
    method: `Live · ••••${stored.last4 ?? ''}`,
    saved: stored.rotatedAt ? formatDateWithYear(stored.rotatedAt) : '—',
    rotation: d.tile.state === 'token_expiring' ? d.tile.status : stored.expiresAt ? `Expires ${formatDateWithYear(stored.expiresAt)}` : 'No expiry known',
    rotationTone: d.tile.state === 'token_expiring' ? 'accent' : d.tile.state === 'sync_failed' ? 'danger' : 'ok',
    lastTest: 'Passed before save',
  };
}

/** A tile with a saved live connection (its sync state is reported as `syncing`). */
function isLive(t) {
  return Boolean(t.liveConnector && connections.value[t.id]?.connection?.hasSecret);
}

function isSyncing(t) {
  return Boolean(t.syncing) || syncingHere.value.has(t.id);
}

const jobRows = computed(() =>
  jobs.value.map((j) => {
    const last = j.lastRun;
    const outcome = last ? (JOB_OUTCOMES[last.outcome] ?? { text: last.outcome, tone: '' }) : null;

    return {
      name: j.name,
      description: j.description,
      next: j.nextRunAt ? formatDateTime(j.nextRunAt) : 'Not scheduled',
      lastOutcome: outcome?.text ?? '',
      lastTone: outcome?.tone ?? '',
      lastAt: last ? formatDateTime(last.startedAt) : '',
      lastError: last?.outcome === 'failed' || last?.outcome === 'running' ? last.error : '',
    };
  }),
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
    const [toolsRes, jiraRes, logRes, activityRes, jobsRes] = await Promise.all([
      api.get('/integrations/tools'),
      api.get('/integrations/jira/config'),
      api.get('/integrations/sync-log'),
      api.get('/integrations/credential-log'),
      api.get('/jobs'),
    ]);

    tiles.value = toolsRes.tiles ?? [];
    jira.value = { ...jiraRes, boards: [...(jiraRes.boards ?? [])] };
    syncLog.value = logRes.items ?? [];
    activity.value = activityRes.items ?? [];
    jobs.value = jobsRes.items ?? [];

    const live = tiles.value.filter((t) => t.liveConnector);
    const [all, liveViews] = await Promise.all([
      Promise.all(connected.value.map((t) => api.get(`/integrations/tools/${t.id}`))),
      Promise.all(live.map((t) => api.get(`/integrations/${encodeURIComponent(t.id)}/connection`))),
    ]);

    details.value = Object.fromEntries(all.map((d) => [d.tile.id, d]));
    connections.value = Object.fromEntries(liveViews.map((c) => [c.tool, c]));
    schedulePoll();
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

/** Re-reads the tiles every few seconds while a live sync runs elsewhere, so Sync now comes back. */
function schedulePoll() {
  clearTimeout(pollTimer);

  if (tiles.value.some((t) => t.syncing && !syncingHere.value.has(t.id))) {
    pollTimer = setTimeout(async () => {
      try {
        tiles.value = (await api.get('/integrations/tools', { quiet: true })).tiles ?? tiles.value;
      } catch {
        // the next full load shows any error
      }

      schedulePoll();
    }, SYNC_POLL_MS);
  }
}

/** Sync now of a live tool: disabled while it runs; a failure shows on the tile as "Sync failed · code". */
async function liveSync(t) {
  syncingHere.value = new Set([...syncingHere.value, t.id]);

  try {
    const res = await api.post(`/integrations/${encodeURIComponent(t.id)}/sync`);

    showToast(res.sync?.outcome === 'failed' ? `${t.name} · Sync failed · ${res.sync.message}` : `${t.name} · synced`);
  } catch {
    // shown by the API client (409 while another sync of the tool runs)
  } finally {
    const next = new Set(syncingHere.value);

    next.delete(t.id);
    syncingHere.value = next;
  }

  await load();
  accountsPanel.value?.load();
  // a sync may add sprints: the period choices of every screen must include them
  reloadPeriods().catch((e) => console.error('GET /periods failed', e));
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

/** Runs a scheduled job now (task 5.5); the answer comes after its first attempt. */
async function runJob(name) {
  runningJob.value = name;

  try {
    const { run } = await api.post(`/jobs/${encodeURIComponent(name)}/run`);
    const outcome = JOB_OUTCOMES[run.outcome]?.text ?? run.outcome;

    showToast(run.outcome === 'skipped' ? `${name} is still running · skipped` : `${name} · ${outcome}`);

    jobs.value = (await api.get('/jobs')).items ?? [];
  } catch {
    // shown by the API client
  } finally {
    runningJob.value = '';
  }
}

onMounted(load);
onBeforeUnmount(() => clearTimeout(pollTimer));
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

.last-run {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  flex-wrap: wrap;
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
