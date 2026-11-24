<template>
  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlLoading v-else-if="!data" />
  <template v-else>
    <PlKpiRow :columns="4" label="Mentoring">
      <StatTile eyebrow="Mentees" :value="data.kpis.mentees" :foot="`${data.kpis.active} active · ${data.kpis.completed} completed`" />
      <StatTile eyebrow="Pending for you" :value="data.kpis.pending" :badge="data.kpis.pending ? 'Oldest first' : 'All clear'" :badge-tone="data.kpis.pending ? 'accent' : 'ok'" />
      <StatTile eyebrow="Mentor rating" :value="rating(data.kpis.averageMentorRating)" :unit="typeof data.kpis.averageMentorRating === 'number' ? ' / 5' : ''" foot="from trainees at sign-off" />
      <StatTile eyebrow="Open plan" :value="selectedName ?? '—'" foot="click an item to open it" />
    </PlKpiRow>

    <PlCard title="Pending for you" subtitle="Check-ins to verify, assessments, ratings, session requests and disagreements">
      <PlTable :columns="columns" :rows="data.items" :row-key="rowKey" label="Pending for you" clickable empty-text="Nothing waiting for you." @row-click="open">
        <template #cell-since="{ row }">{{ formatDate(row.since) }}</template>
        <template #cell-go="{ row }">
          <RouterLink v-if="row.reviewRequestId" class="pl-btn pl-btn-sm" :to="{ path: '/reviews', query: { tab: '360', request: row.reviewRequestId } }" @click.stop>
            Give rating
          </RouterLink>
          <span v-else class="pl-caption">Open</span>
        </template>
      </PlTable>
    </PlCard>

    <AssignmentDetail v-if="selectedId" :assignment-id="selectedId" @changed="load" />
  </template>
</template>

<script setup>
// Mentoring (reference mentorDash): the mentor's Pending-for-you list and KPIs from GET
// /training/mentoring/pending; an item opens that assignment's detail, where the mentor verifies
// check-ins, logs sessions and writes reviews. Checkpoint ratings open the 360° form in Reviews.
import { computed, ref } from 'vue';
import { api } from '@/api/client.js';
import { StatTile } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlTable } from '@/ui/index.js';
import AssignmentDetail from './AssignmentDetail.vue';
import { rating } from './presentation.js';

const data = ref(null);
const error = ref('');
const selectedId = ref('');

async function load() {
  error.value = '';

  try {
    const res = await api.get('/training/mentoring/pending');

    // PlTable needs a unique key per row.
    res.items = res.items.map((x, i) => ({ ...x, key: `${x.type}-${x.assignmentId}-${i}` }));
    data.value = res;
  } catch (e) {
    error.value = e.message || 'Could not load mentoring.';
  }
}

load();

const columns = [
  { key: 'type', label: 'To do' },
  { key: 'personName', label: 'Trainee' },
  { key: 'what', label: 'Details' },
  { key: 'since', label: 'Since' },
  { key: 'go', label: '' },
];

const rowKey = 'key';

const selectedName = computed(() => data.value.items.find((x) => x.assignmentId === selectedId.value)?.personName ?? null);

function open(row) {
  if (!row.reviewRequestId) {
    selectedId.value = row.assignmentId;
  }
}
</script>
