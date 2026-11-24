<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Admin" title="Data imports" caption="CSV uploads with a check before anything is saved · templates and round-trip exports · undo for 24 hours" />
    <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!types" />
    <template v-else>
      <div class="im-cards">
        <PlCard v-for="t in types" :key="t.id" :title="t.name" :subtitle="t.feeds">
          <p class="pl-caption">Required: {{ required(t).join(', ') }}<template v-if="optional(t).length"> · optional: {{ optional(t).join(', ') }}</template></p>
          <p class="pl-caption">Last upload: {{ lastUpload(t.id) }}</p>
          <div class="im-actions">
            <button type="button" class="pl-btn pl-btn-primary pl-btn-sm" @click="upload(t)">Upload CSV</button>
            <button type="button" class="pl-btn pl-btn-sm" @click="get(`/imports/${t.id}/template`, `${t.id}_template.csv`)">Template</button>
            <button type="button" class="pl-btn pl-btn-sm" @click="get(exportPath(t.id), `${t.id}.csv`)">Export current</button>
          </div>
        </PlCard>
        <PlCard v-if="canAppraisal" title="Appraisal proposals" subtitle="Approved and released increments for payroll">
          <p class="pl-caption">Export only · the one export that carries CTC · audited</p>
          <div class="im-actions">
            <button type="button" class="pl-btn pl-btn-sm" @click="get('/appraisal/export', 'appraisal_proposals.csv')">Export for payroll</button>
          </div>
        </PlCard>
      </div>

      <PlCard title="Where each module gets its data">
        <PlTable :columns="sourceColumns" :rows="SOURCES" row-key="module" label="Data sources" />
      </PlCard>

      <PlCard title="Upload history" subtitle="Undo restores the data as it was before the import">
        <PlTable :columns="historyColumns" :rows="history" row-key="id" label="Upload history" empty-text="No uploads yet.">
          <template #cell-import_type="{ row }">{{ typeName(row.import_type) }}</template>
          <template #cell-committed_at="{ row }">{{ formatDateTime(row.committed_at) }}</template>
          <template #cell-rows="{ row }">{{ row.imported }} of {{ row.rows_read }}{{ row.errors ? ` · ${row.errors} errors` : '' }}</template>
          <template #cell-undo="{ row }">
            <button v-if="row.undo_state === 'available'" type="button" class="pl-btn pl-btn-sm" @click="toUndo = row">Undo</button>
            <Badge v-else :text="row.undo_state === 'undone' ? 'Undone' : 'Final'" :tone="row.undo_state === 'undone' ? 'warn' : ''" />
          </template>
        </PlTable>
      </PlCard>
    </template>

    <UploadModal :open="!!uploading" :type="uploading" @close="uploading = null" @imported="loadHistory" />
    <PlConfirm
      :open="!!toUndo"
      title="Undo this import?"
      :message="`${toUndo?.file_name ?? ''}: every row it added or changed goes back to how it was. Refused if someone changed those records since.`"
      confirm-label="Undo import"
      @confirm="undo"
      @cancel="toUndo = null"
    />
  </div>
</template>

<script setup>
// Data imports (reference pImports; spec admin/data-imports-exports): one card per import the user may run
// (GET /imports/types), the appraisal payroll export for appraisal roles, the data sources map and the
// upload history with Undo. ?type=<id> opens that upload at once (links from Users, Visits, Attendance).
import { ref } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { loadPeriods } from '@/shell/period.js';
import { can } from '@/shell/session.js';
import { formatDateTime } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlLoading, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import UploadModal from './UploadModal.vue';
import { download } from './files.js';
import './imports.css';

const SOURCES = [
  { module: 'Work items', source: 'Jira', upload: 'Live integration' },
  { module: 'Effort · worklogs', source: 'Jira + GitHub / TFS', upload: 'Live integration' },
  { module: 'Pull requests', source: 'GitHub + Microsoft TFS', upload: 'Live integration' },
  { module: 'Bugs & support', source: 'Jira + support desk', upload: 'Live integration' },
  { module: 'Customer visits', source: 'Form in Pulse', upload: 'CSV — planned visits from the CRM' },
  { module: 'OKRs', source: 'Form in Pulse', upload: 'CSV — add, update and bulk check-in' },
  { module: 'Attendance & leave', source: 'HR system / biometric', upload: 'CSV — one file a month (the only source)' },
  { module: 'Holiday calendar', source: 'Set by HR', upload: 'CSV — once a year' },
  { module: 'Users', source: 'Form in Pulse', upload: 'CSV — bulk create and update' },
  { module: 'Training plans, 1:1s, 360°, extra-mile points', source: 'Workflows in Pulse', upload: 'No upload — they need a reviewer, rating or sign-off' },
  { module: 'Culture values', source: '360° reviews + data above', upload: 'No upload — calculated' },
  { module: 'Appraisal', source: 'Calculated in Pulse', upload: 'Export only (CSV for payroll)' },
];

const sourceColumns = [
  { key: 'module', label: 'Module' },
  { key: 'source', label: 'Source' },
  { key: 'upload', label: 'Upload' },
];

const historyColumns = [
  { key: 'committed_at', label: 'When' },
  { key: 'import_type', label: 'Import' },
  { key: 'file_name', label: 'File' },
  { key: 'user_name', label: 'By' },
  { key: 'rows', label: 'Rows', num: true },
  { key: 'undo', label: '' },
];

const route = useRoute();
const canAppraisal = can('appraisal.manage', 'appraisal.approve');

const types = ref(null);
const history = ref([]);
const error = ref('');
const uploading = ref(null);
const toUndo = ref(null);
const today = ref(null);

async function loadHistory() {
  try {
    history.value = (await api.get('/imports/history', { query: { pageSize: 50 } })).items;
  } catch (e) {
    error.value = e.message;
  }
}

async function load() {
  try {
    types.value = (await api.get('/imports/types')).items;
    await loadHistory();

    const wanted = types.value.find((t) => t.id === route.query.type);

    if (wanted) {
      uploading.value = wanted;
    }
  } catch (e) {
    error.value = e.message || 'Could not load the imports.';
  }
}

load();
loadPeriods()
  .then((p) => (today.value = p?.today ?? null))
  .catch(() => {});

function required(t) {
  return t.columns.filter((c) => c.required).map((c) => c.key);
}

function optional(t) {
  return t.columns.filter((c) => !c.required).map((c) => c.key);
}

function typeName(id) {
  return types.value?.find((t) => t.id === id)?.name ?? id;
}

function lastUpload(id) {
  const last = history.value.find((h) => h.import_type === id && h.undo_state !== 'undone');

  return last ? `${formatDateTime(last.committed_at)} · ${last.user_name ?? '—'} · ${last.file_name}` : 'Never';
}

/** Exports that need a period get the current one from the server's today. */
function exportPath(id) {
  const t = today.value ?? new Date().toISOString().slice(0, 10);
  const [year, month] = t.split('-').map(Number);

  if (id === 'okrs') {
    return `/imports/okrs/export?quarter=${encodeURIComponent(`Q${Math.floor((month - 1) / 3) + 1} ${year}`)}`;
  }

  if (id === 'attendance') {
    const prev = month === 1 ? `${year - 1}-12` : `${year}-${String(month - 1).padStart(2, '0')}`;
    return `/imports/attendance/export?month=${prev}`;
  }

  return `/imports/${id}/export`;
}

async function get(path, name) {
  try {
    await download(path, name);
  } catch (e) {
    showToast(e.message);
  }
}

function upload(t) {
  uploading.value = t;
}

async function undo() {
  const row = toUndo.value;
  toUndo.value = null;

  try {
    const r = await api.post(`/imports/history/${row.id}/undo`, {}, { quiet: true });
    showToast(`Import undone · ${r.restored ?? 0} records restored`);
  } catch (e) {
    showToast(e.message);
  }

  await loadHistory();
}
</script>
