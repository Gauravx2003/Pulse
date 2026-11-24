<template>
  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlLoading v-else-if="!plans" />
  <template v-else>
    <div class="tr-actions">
      <button type="button" class="pl-btn pl-btn-primary" @click="openPlan(null)">New plan</button>
    </div>
    <PlCard title="Plan library" subtitle="Pass rate, average outcome and the trainees’ rating of each plan">
      <PlTable :columns="columns" :rows="plans" row-key="id" label="Plan library" empty-text="No plans yet.">
        <template #cell-name="{ row }">
          <b>{{ row.name }}</b>
          <span class="pl-caption"> · {{ row.audience || 'Anyone' }}</span>
        </template>
        <template #cell-shape="{ row }">{{ row.weeks }} w · {{ row.modules.length }} modules · {{ hours(row) }} h</template>
        <template #cell-values="{ row }">{{ (row.values ?? []).join(', ') || '—' }}</template>
        <template #cell-passRate="{ row }">{{ row.passRate === null ? '—' : `${row.passRate}%` }}</template>
        <template #cell-averageOutcome="{ row }">{{ rating(row.averageOutcome) }}</template>
        <template #cell-traineeRating="{ row }">{{ rating(row.traineeRating) }}</template>
        <template #cell-use="{ row }">{{ row.active }} active · {{ row.assignments }} total</template>
        <template #cell-act="{ row }">
          <span class="tr-row-actions">
            <button type="button" class="pl-btn pl-btn-sm" @click="openPlan(row)">Edit</button>
            <button type="button" class="pl-btn pl-btn-sm" @click="duplicate(row)">Duplicate</button>
            <button type="button" class="pl-btn pl-btn-sm" @click="toDelete = row">Delete</button>
          </span>
        </template>
      </PlTable>
    </PlCard>

    <FormModal
      :open="planOpen"
      :title="editing ? `Edit plan · ${editing.name}` : 'New training plan'"
      intro="Modules: one per line as week | type | hours | title | link (type: Course, Reading, Hands-on task, Shadowing, Meeting or Assessment)."
      :fields="planFields"
      :initial="planInitial"
      :submit-label="editing ? 'Save as new version' : 'Create plan'"
      :save="savePlan"
      @close="planOpen = false"
      @saved="onSaved(editing ? 'Plan saved as a new version' : 'Plan created')"
    />
    <PlConfirm
      :open="!!toDelete"
      title="Delete this plan?"
      :message="`${toDelete?.name ?? ''} leaves the library. Running assignments keep their version.`"
      confirm-label="Delete plan"
      @confirm="remove"
      @cancel="toDelete = null"
    />
  </template>
</template>

<script setup>
// Plan library (reference trLibrary): plans with pass rate, average outcome and trainee rating
// (GET /training/plans), New / Edit (each edit is a new version), Duplicate and Delete (hidden from the
// library; assignments keep running).
import { ref } from 'vue';
import { api } from '@/api/client.js';
import { PlAlert, PlCard, PlConfirm, PlLoading, PlTable, showToast } from '@/ui/index.js';
import FormModal from './FormModal.vue';
import { rating } from './presentation.js';

const plans = ref(null);
const error = ref('');
const planOpen = ref(false);
const editing = ref(null);
const planInitial = ref({});
const toDelete = ref(null);

async function load() {
  error.value = '';

  try {
    plans.value = (await api.get('/training/plans')).items;
  } catch (e) {
    error.value = e.message || 'Could not load the plan library.';
  }
}

load();

const columns = [
  { key: 'name', label: 'Plan' },
  { key: 'shape', label: 'Length' },
  { key: 'values', label: 'Builds' },
  { key: 'passRate', label: 'Pass rate', num: true },
  { key: 'averageOutcome', label: 'Avg outcome', num: true },
  { key: 'traineeRating', label: 'Trainee rating', num: true },
  { key: 'use', label: 'Assignments' },
  { key: 'act', label: '' },
];

const planFields = [
  { key: 'name', label: 'Name', required: true, maxlength: 120 },
  { key: 'audience', label: 'Audience', maxlength: 120 },
  { key: 'weeks', label: 'Weeks', type: 'number', required: true },
  { key: 'description', label: 'Description', type: 'textarea', maxlength: 1000 },
  { key: 'values', label: 'Culture values it builds (comma separated)', maxlength: 400 },
  { key: 'checkpoints', label: 'Checkpoint days (comma separated)', maxlength: 40 },
  { key: 'cadence', label: 'Check-in cadence', type: 'select', options: ['Daily', 'Weekly', 'Monthly'].map((c) => ({ value: c, label: c })) },
  { key: 'assessmentType', label: 'Assessment type', maxlength: 60 },
  { key: 'passMark', label: 'Pass mark %', type: 'number' },
  { key: 'modules', label: 'Modules', type: 'textarea', maxlength: 8000 },
];

function hours(plan) {
  return (plan.modules ?? []).reduce((s, m) => s + (Number(m.hours) || 0), 0);
}

function openPlan(plan) {
  editing.value = plan;
  planInitial.value = plan
    ? {
        ...plan,
        values: (plan.values ?? []).join(', '),
        checkpoints: (plan.checkpoints ?? []).join(', '),
        modules: (plan.modules ?? []).map((m) => [m.week, m.type, m.hours, m.title, m.link ?? ''].join(' | ')).join('\n'),
      }
    : { weeks: '4', cadence: 'Weekly', passMark: '70', assessmentType: 'Practical task' };
  planOpen.value = true;
}

function list(text) {
  return (text ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);
}

function modules(text, keep) {
  return (text ?? '')
    .split('\n')
    .map((l) => l.trim())
    .filter(Boolean)
    .map((line, i) => {
      const [week, type, hrs, title, link] = line.split('|').map((s) => s.trim());
      const id = keep?.[i]?.id;

      return { ...(id ? { id } : {}), week: Number(week), type, hours: Number(hrs), title, link: link ?? '' };
    });
}

async function savePlan(v) {
  const body = {
    name: v.name,
    audience: v.audience ?? '',
    weeks: v.weeks,
    description: v.description ?? '',
    values: list(v.values),
    checkpoints: list(v.checkpoints),
    cadence: v.cadence,
    assessmentType: v.assessmentType,
    passMark: v.passMark,
    modules: modules(v.modules, editing.value?.modules),
  };

  if (editing.value) {
    await api.put(`/training/plans/${editing.value.id}`, { ...body, version: editing.value.version }, { quiet: true });
  } else {
    await api.post('/training/plans', body, { quiet: true });
  }
}

async function onSaved(text) {
  planOpen.value = false;
  showToast(text);
  await load();
}

async function duplicate(plan) {
  try {
    await api.post(`/training/plans/${plan.id}/duplicate`, {}, { quiet: true });
    showToast(`Copied · ${plan.name}`);
  } catch (e) {
    showToast(e.message);
  }

  await load();
}

async function remove() {
  const plan = toDelete.value;
  toDelete.value = null;

  try {
    await api.delete(`/training/plans/${plan.id}`, { body: { version: plan.version }, quiet: true });
    showToast(`Deleted · ${plan.name}`);
  } catch (e) {
    showToast(e.message);
  }

  await load();
}
</script>
