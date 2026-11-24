<template>
  <PlTabs v-model="view" :tabs="views" label="360° view" />

  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>

  <!-- To give -->
  <PlCard v-if="view === 'give'" title="To give" subtitle="360° reviews and training checkpoints you were asked for">
    <PlTable :columns="giveColumns" :rows="toGive" row-key="id" label="Reviews to give" empty-text="Nothing to give." clickable @row-click="formFor = $event.id">
      <template #cell-cycle="{ row }">{{ row.kind === 'training' ? `Training checkpoint · Day ${row.checkpointDay}` : row.cycleName }}</template>
      <template #cell-group="{ row }">{{ groupLabel(row.group) }}</template>
      <template #cell-dueOn="{ row }">{{ formatDate(row.dueOn) }}</template>
      <template #cell-status="{ row }"><Badge :text="statusLabel(row.status)" :tone="statusTone(row.status)" /></template>
    </PlTable>
  </PlCard>

  <!-- My feedback -->
  <template v-else-if="view === 'mine'">
    <div class="rv-actions">
      <PlField v-model="mineCycle" type="select" label="Cycle" :options="cycleOptions" />
    </div>
    <PlCard :title="`My feedback · ${cycleName(mineCycle)}`" :empty="!mine" empty-text="Pick a cycle.">
      <template v-if="mine">
        <p v-if="!mine.results" class="pl-caption">
          {{ mine.progress?.message ?? 'Results open when the cycle is published. Until then you see how many reviews are in.' }}
        </p>
        <p v-if="mine.progress" class="pl-caption">{{ progressText(mine.progress) }}</p>
        <ResultsPanel v-if="mine.results" :results="mine.results" />
      </template>
    </PlCard>
  </template>

  <!-- Team -->
  <template v-else-if="view === 'team'">
    <div class="rv-actions">
      <button type="button" class="pl-btn pl-btn-primary" :disabled="!running" @click="assignOpen = true">Assign reviewers</button>
      <button type="button" class="pl-btn" :disabled="!running" @click="remindAll">Remind all</button>
    </div>
    <PlCard :title="running ? `Team · ${running.name}` : 'Team'" :empty="!running" empty-text="No 360° cycle is running.">
      <PlTable v-if="running" :columns="teamColumns" :rows="assignments" row-key="id" label="Reviewer assignments" empty-text="Nobody assigned yet.">
        <template #cell-subjectName="{ row }">
          <button type="button" class="pl-btn pl-btn-sm" @click="openResults(row)">{{ row.subjectName }}</button>
        </template>
        <template #cell-group="{ row }">{{ groupLabel(row.group) }}</template>
        <template #cell-status="{ row }">
          <span class="rv-row">
            <Badge :text="statusLabel(row.status)" :tone="statusTone(row.status)" />
            <button v-if="['not_started', 'in_progress'].includes(row.status)" type="button" class="pl-btn pl-btn-sm" @click="remindOne(row)">Remind</button>
          </span>
        </template>
      </PlTable>
    </PlCard>
  </template>

  <!-- Cycles -->
  <template v-else-if="view === 'cycles'">
    <div class="rv-actions">
      <button type="button" class="pl-btn pl-btn-primary" @click="cycleOpen = true">New cycle</button>
    </div>
    <PlCard title="Cycles">
      <PlTable :columns="cycleColumns" :rows="cycles" row-key="id" label="360° cycles" empty-text="No cycles yet.">
        <template #cell-dueOn="{ row }">{{ formatDate(row.dueOn) }}</template>
        <template #cell-status="{ row }"><Badge :text="row.status" :tone="cycleTone(row.status)" /></template>
        <template #cell-progress="{ row }">{{ row.submitted }} of {{ row.assigned }}</template>
        <template #cell-act="{ row }">
          <button v-if="row.status !== 'published'" type="button" class="pl-btn pl-btn-sm" @click="toPublish = row">Publish</button>
        </template>
      </PlTable>
    </PlCard>
  </template>

  <ReviewFormModal :open="!!formFor" :request-id="formFor ?? ''" @close="formFor = null" @changed="loadGive" />

  <PlModal :open="!!resultsFor" :title="resultsFor ? `360° results · ${resultsFor.name}` : 'Results'" size="lg" @close="resultsFor = null">
    <PlLoading v-if="resultsFor && !resultsFor.results" />
    <ResultsPanel v-else-if="resultsFor" :results="resultsFor.results" />
  </PlModal>

  <FormModal
    :open="assignOpen"
    title="Assign reviewers"
    intro="Groups are derived: the subject is Self, their manager is Manager, the same department is Peers, others are Cross-department."
    :fields="assignFields"
    submit-label="Assign"
    :save="assign"
    @close="assignOpen = false"
    @saved="afterAssign"
  />
  <FormModal
    :open="cycleOpen"
    title="New 360° cycle"
    :fields="[
      { key: 'name', label: 'Name', required: true, maxlength: 120 },
      { key: 'quarter', label: 'Quarter (e.g. Q4 2026)', required: true, maxlength: 10 },
      { key: 'dueOn', label: 'Due', type: 'date', required: true },
      { key: 'department', label: 'Department (optional)', maxlength: 120 },
    ]"
    submit-label="Create cycle"
    :save="createCycle"
    @close="cycleOpen = false"
    @saved="afterCycle"
  />
  <PlConfirm
    :open="!!toPublish"
    title="Publish this cycle?"
    :message="`Subjects of ${toPublish?.name ?? ''} can open their own results and are told. Groups under the anonymity threshold stay merged or hidden.`"
    confirm-label="Publish"
    @confirm="publish"
    @cancel="toPublish = null"
  />
</template>

<script setup>
// 360° feedback (spec culture/review-360): To give with the review form, My feedback (own results once
// published), Team (managers: assign reviewers, reminders, preliminary results) and Cycles (Admin: new
// cycle, publish). Anonymity is enforced by the server; the UI never sees a reviewer behind a score.
import { computed, ref, watch } from 'vue';
import { useRoute } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { loadPeople, people } from '@/shell/person.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { can, session } from '@/shell/session.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlField, PlLoading, PlModal, PlTable, PlTabs, showToast } from '@/ui/index.js';
import FormModal from '../training/FormModal.vue';
import ResultsPanel from './ResultsPanel.vue';
import ReviewFormModal from './ReviewFormModal.vue';
import { cycleTone, groupLabel, statusLabel, statusTone } from './presentation.js';

const route = useRoute();

const isManager = can('review360.manage');
const isAdmin = can('review360.manage') && can('users.all');
const myPerson = session.me?.user?.personId ?? null;

const views = [
  { key: 'give', label: 'To give' },
  ...(myPerson ? [{ key: 'mine', label: 'My feedback' }] : []),
  ...(isManager ? [{ key: 'team', label: 'Team' }] : []),
  ...(isAdmin ? [{ key: 'cycles', label: 'Cycles' }] : []),
];

const view = ref(views.some((v) => v.key === route.query.view) ? route.query.view : 'give');
const error = ref('');
const toGive = ref([]);
const cycles = ref([]);
const assignments = ref([]);
const reviewers = ref([]);
const mineCycle = ref(typeof route.query.cycle === 'string' ? route.query.cycle : '');
const mine = ref(null);
const formFor = ref(typeof route.query.request === 'string' ? route.query.request : null);
const resultsFor = ref(null);
const assignOpen = ref(false);
const cycleOpen = ref(false);
const toPublish = ref(null);

const giveColumns = [
  { key: 'subjectName', label: 'Person' },
  { key: 'cycle', label: 'Cycle' },
  { key: 'group', label: 'As' },
  { key: 'dueOn', label: 'Due' },
  { key: 'status', label: 'Status' },
];

const teamColumns = [
  { key: 'subjectName', label: 'Subject' },
  { key: 'reviewerName', label: 'Reviewer' },
  { key: 'reviewerDepartment', label: 'Department' },
  { key: 'group', label: 'Group' },
  { key: 'status', label: 'Status' },
];

const cycleColumns = [
  { key: 'name', label: 'Cycle' },
  { key: 'quarter', label: 'Quarter' },
  { key: 'dueOn', label: 'Due' },
  { key: 'status', label: 'Status' },
  { key: 'progress', label: 'Submitted', num: true },
  { key: 'act', label: '' },
];

const running = computed(() => cycles.value.find((c) => c.status === 'running' && c.kind === '360') ?? null);

const cycleOptions = computed(() => cycles.value.filter((c) => c.kind === '360').map((c) => ({ value: c.id, label: `${c.name} · ${c.status}` })));

const assignFields = computed(() => [
  { key: 'subjectId', label: 'Subject', type: 'select', required: true, options: (people.value ?? []).map((p) => ({ value: p.id, label: p.name })) },
  {
    key: 'reviewerId',
    label: 'Reviewer',
    type: 'select',
    required: true,
    options: reviewers.value.map((u) => ({ value: u.id, label: `${u.name}${u.department ? ` · ${u.department}` : ''}` })),
  },
  { key: 'includeSelf', label: 'Also ask the subject for a self review', type: 'checkbox' },
  { key: 'includeManager', label: 'Also ask their manager', type: 'checkbox' },
]);

function cycleName(id) {
  return cycles.value.find((c) => c.id === id)?.name ?? '';
}

/** "5 of 8 reviews are in" from the per-group counts ({ group: { assigned, submitted } }). */
function progressText(p) {
  const groups = Object.values(p.progress ?? {});

  if (!groups.length) {
    return '';
  }

  const submitted = groups.reduce((n, g) => n + (g.submitted ?? 0), 0);
  const assigned = groups.reduce((n, g) => n + (g.assigned ?? 0), 0);

  return `${submitted} of ${assigned} reviews are in.`;
}

async function loadGive() {
  try {
    toGive.value = (await api.get('/reviews/360/to-give')).items;
  } catch (e) {
    error.value = e.message;
  }
}

async function loadCycles() {
  try {
    cycles.value = (await api.get('/reviews/360/cycles')).items;

    if (!mineCycle.value) {
      mineCycle.value = (cycles.value.find((c) => c.kind === '360' && c.status === 'published') ?? cycles.value[0])?.id ?? '';
    }
  } catch (e) {
    error.value = e.message;
  }
}

async function loadTeam() {
  if (!running.value) {
    return;
  }

  try {
    const [a, r] = await Promise.all([api.get(`/reviews/360/cycles/${running.value.id}/assignments`), api.get('/reviews/360/reviewer-options')]);
    assignments.value = a.items;
    reviewers.value = r.items;
  } catch (e) {
    error.value = e.message;
  }
}

async function loadMine() {
  mine.value = null;

  if (!mineCycle.value) {
    return;
  }

  const out = { progress: null, results: null };

  try {
    out.progress = await api.get(`/reviews/360/cycles/${mineCycle.value}/progress`, { quiet: true });
  } catch (e) {
    out.progress = { message: e.message };
  }

  if (out.progress?.published) {
    try {
      out.results = await api.get(`/reviews/360/cycles/${mineCycle.value}/results`, { quiet: true });
    } catch (e) {
      out.progress = { message: e.message };
    }
  }

  mine.value = out;
}

loadGive();
loadCycles().then(() => {
  if (isManager) {
    loadPeople().catch(() => {});
    loadTeam();
  }
});

watch(mineCycle, loadMine, { immediate: true });
watch(view, (v) => {
  if (v === 'team') loadTeam();
  if (v === 'cycles' || v === 'mine') loadCycles();
});

async function openResults(row) {
  resultsFor.value = { name: row.subjectName, results: null };

  try {
    const results = await api.get(`/reviews/360/cycles/${running.value.id}/results/${row.subjectId}`, { quiet: true });
    resultsFor.value = { name: row.subjectName, results };
  } catch (e) {
    resultsFor.value = null;
    showToast(e.message);
  }
}

let lastAssign = null;

async function assign(v) {
  lastAssign = await api.post(
    `/reviews/360/cycles/${running.value.id}/assignments`,
    { subjectIds: [v.subjectId], reviewerIds: [v.reviewerId], includeSelf: !!v.includeSelf, includeManager: !!v.includeManager },
    { quiet: true },
  );
}

async function afterAssign() {
  assignOpen.value = false;

  const made = (lastAssign?.assigned ?? []).length;
  const skipped = (lastAssign?.skipped ?? []).map((s) => s.reason ?? s.message ?? '').filter(Boolean);

  showToast([`${made} reviewer${made === 1 ? '' : 's'} assigned · they are notified`, ...skipped].join(' · '));
  refreshNotifications();
  await loadTeam();
}

async function remindAll() {
  try {
    const r = await api.post(`/reviews/360/cycles/${running.value.id}/remind`, {}, { quiet: true });
    showToast(`${r.reminded} reviewer${r.reminded === 1 ? '' : 's'} reminded`);
  } catch (e) {
    showToast(e.message);
  }
}

async function remindOne(row) {
  try {
    await api.post(`/reviews/360/requests/${row.id}/remind`, {}, { quiet: true });
    showToast(`${row.reviewerName} reminded`);
  } catch (e) {
    showToast(e.message);
  }
}

function createCycle(v) {
  return api.post('/reviews/360/cycles', v, { quiet: true });
}

async function afterCycle() {
  cycleOpen.value = false;
  showToast('Cycle created');
  await loadCycles();
}

async function publish() {
  const c = toPublish.value;
  toPublish.value = null;

  try {
    const r = await api.post(`/reviews/360/cycles/${c.id}/publish`, {}, { quiet: true });
    showToast(`${c.name} published · ${r.subjectsTold} people told`);
  } catch (e) {
    showToast(e.message);
  }

  await loadCycles();
}
</script>
