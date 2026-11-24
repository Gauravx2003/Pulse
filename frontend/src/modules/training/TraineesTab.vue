<template>
  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlLoading v-else-if="!items" />
  <template v-else>
    <div class="tr-actions">
      <button type="button" class="pl-btn pl-btn-primary" @click="openAssign()">Assign plan</button>
    </div>

    <PlAlert v-if="suggestions.length" badge="Onboarding" tone="accent">
      {{ suggestions.length }} new joiner{{ suggestions.length > 1 ? 's have' : ' has' }} no plan yet:
      {{ suggestions.map((s) => s.name).join(', ') }}.
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="openAssign(suggestions[0])">Assign onboarding</button>
      </template>
    </PlAlert>

    <PlCard title="Trainees & assignments" subtitle="Click a row to open the plan">
      <PlTable :columns="columns" :rows="items" row-key="id" label="Trainees & assignments" clickable empty-text="No training assigned yet." @row-click="selectedId = $event.id">
        <template #cell-personName="{ row }">
          <b>{{ row.personName }}</b>
          <span class="pl-caption"> · mentor {{ row.mentorName ?? '—' }}</span>
        </template>
        <template #cell-period="{ row }">{{ span(row.startOn, row.end) }}</template>
        <template #cell-stage="{ row }">
          <Badge :text="row.stage" :tone="stageTone(row.stage)" />
          <Badge v-if="row.disagreementFlagged" text="Disagreed" tone="danger" />
        </template>
        <template #cell-progress="{ row }">{{ progress(row) }}%</template>
        <template #cell-next="{ row }">
          <span class="tr-row-actions">
            <span class="pl-caption">{{ row.actions.length ? `${row.actions.length} to do` : '—' }}</span>
            <button v-if="row.actions.includes('markDiscussed')" type="button" class="pl-btn pl-btn-sm" @click.stop="discussed(row)">Mark discussed</button>
          </span>
        </template>
      </PlTable>
    </PlCard>

    <AssignmentDetail v-if="selectedId" :assignment-id="selectedId" @changed="load" />

    <PlCard title="Mentors" subtitle="Active mentees, outcomes and the trainees’ rating of their mentor">
      <PlTable :columns="mentorColumns" :rows="mentors" row-key="mentorId" label="Mentors" empty-text="No mentors yet.">
        <template #cell-averageMentorRating="{ row }">{{ rating(row.averageMentorRating) }}</template>
        <template #cell-lastSessionOn="{ row }">{{ formatDate(row.lastSessionOn) }}</template>
      </PlTable>
    </PlCard>

    <FormModal
      :open="assignOpen"
      title="Assign training plan"
      :fields="assignFields"
      :initial="assignInitial"
      submit-label="Assign"
      :save="assign"
      @close="assignOpen = false"
      @saved="onAssigned"
    />
  </template>
</template>

<script setup>
// Trainees & assignments (reference trTrainees): every visible assignment, the Assign plan modal
// (POST /training/assignments: people already on a plan are skipped, a heavy load is a warning), the
// onboarding suggestions and the Mentors table. A row opens the plan detail with the manager's actions.
import { computed, ref } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlLoading, PlTable, showToast } from '@/ui/index.js';
import AssignmentDetail from './AssignmentDetail.vue';
import FormModal from './FormModal.vue';
import { progress, rating, span, stageTone } from './presentation.js';

const items = ref(null);
const mentors = ref([]);
const plans = ref([]);
const options = ref([]);
const suggestions = ref([]);
const error = ref('');
const selectedId = ref('');
const assignOpen = ref(false);
const assignInitial = ref({});

async function load() {
  error.value = '';

  try {
    const [list, m, p, o, s] = await Promise.all([
      api.get('/training/assignments'),
      api.get('/training/mentors'),
      api.get('/training/plans'),
      api.get('/training/mentor-options'),
      api.get('/training/suggestions/onboarding'),
    ]);

    items.value = list.items.filter((x) => x.roles.includes('manager'));
    mentors.value = m.items;
    plans.value = p.items;
    options.value = o.items;
    suggestions.value = s.items;
  } catch (e) {
    error.value = e.message || 'Could not load the trainees.';
  }
}

load();

const columns = [
  { key: 'personName', label: 'Trainee' },
  { key: 'planName', label: 'Plan' },
  { key: 'period', label: 'Period' },
  { key: 'stage', label: 'Stage' },
  { key: 'progress', label: 'Progress', num: true },
  { key: 'next', label: 'Next' },
];

const mentorColumns = [
  { key: 'mentorName', label: 'Mentor' },
  { key: 'active', label: 'Active', num: true },
  { key: 'completed', label: 'Completed', num: true },
  { key: 'notCompleted', label: 'Not completed', num: true },
  { key: 'averageMentorRating', label: 'Mentor rating', num: true },
  { key: 'lastSessionOn', label: 'Last session' },
];

const assignFields = computed(() => [
  { key: 'personId', label: 'Person', type: 'select', required: true, options: options.value.map((o) => ({ value: o.personId, label: o.name })) },
  { key: 'planId', label: 'Plan', type: 'select', required: true, options: plans.value.map((p) => ({ value: p.id, label: `${p.name} · ${p.weeks} weeks` })) },
  {
    key: 'mentorId',
    label: 'Mentor',
    type: 'select',
    required: true,
    options: options.value.map((o) => ({ value: o.id, label: `${o.name}${o.title ? ` · ${o.title}` : ''} · mentoring ${o.mentoring}` })),
  },
  { key: 'startOn', label: 'Start date', type: 'date', required: true },
  { key: 'cadence', label: 'Check-in cadence', type: 'select', options: ['Daily', 'Weekly', 'Monthly'].map((c) => ({ value: c, label: c })) },
  { key: 'meetingFrequency', label: 'Mentor meetings', type: 'select', options: ['Daily', 'Twice a week', 'Weekly'].map((c) => ({ value: c, label: c })) },
  { key: 'assessmentWindowDays', label: 'Working days for the assessment', type: 'number' },
]);

function openAssign(suggestion) {
  const onboarding = plans.value.find((p) => /onboarding/i.test(p.name));

  assignInitial.value = {
    personId: suggestion?.id ?? '',
    planId: suggestion && onboarding ? onboarding.id : '',
    cadence: 'Weekly',
    meetingFrequency: 'Weekly',
    assessmentWindowDays: '3',
  };

  assignOpen.value = true;
}

let lastResult = null;

async function assign(v) {
  lastResult = await api.post(
    '/training/assignments',
    {
      personIds: [v.personId],
      planIds: [v.planId],
      mentorId: v.mentorId,
      startOn: v.startOn,
      cadence: v.cadence,
      meetingFrequency: v.meetingFrequency,
      assessmentWindowDays: v.assessmentWindowDays,
    },
    { quiet: true },
  );
}

async function onAssigned() {
  assignOpen.value = false;

  const r = lastResult ?? {};
  const made = (r.assigned ?? []).length;
  const skipped = (r.skipped ?? []).map((s) => `${s.personName}: ${s.reason ?? 'skipped'}`);
  const warn = (r.warnings ?? []).map((w) => w.message ?? w).join(' ');

  showToast([made ? `${made} plan assigned` : 'Nothing assigned', ...skipped, warn].filter(Boolean).join(' · '));
  refreshNotifications();
  await load();
}

async function discussed(row) {
  try {
    await api.post(`/training/assignments/${row.id}/discussed`, { version: row.version }, { quiet: true });
    showToast(`Disagreement marked discussed · ${row.personName}`);
  } catch (e) {
    showToast(e.message);
  }

  await load();
}
</script>
