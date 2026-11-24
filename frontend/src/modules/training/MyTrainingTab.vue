<template>
  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlLoading v-else-if="!items" />
  <PlCard v-else-if="!items.length" title="My training" empty empty-text="No training plan assigned yet." />
  <template v-else>
    <PlKpiRow :columns="4" label="My training">
      <StatTile eyebrow="My training plans" :value="items.length" :foot="`${active} in progress · ${completed} completed`" />
      <StatTile
        eyebrow="Overall training rating"
        :value="rating(overall?.score)"
        :unit="typeof overall?.score === 'number' ? ' / 5' : ''"
        :badge="overall?.final ? overall?.band?.label ?? '' : typeof overall?.score === 'number' ? 'Provisional' : 'Not rated'"
        :badge-tone="overall?.band?.tone ?? ''"
      />
      <StatTile eyebrow="Waiting for you" :value="waitingCount" :badge="waitingCount ? 'Open the plan' : 'Nothing waiting'" :badge-tone="waitingCount ? 'accent' : 'ok'" />
      <StatTile eyebrow="Mentor" :value="selected?.mentorName ?? '—'" :foot="selected?.meetingFrequency ?? ''" />
    </PlKpiRow>

    <div class="tr-picker" role="tablist" aria-label="My training plans">
      <button
        v-for="it in items"
        :key="it.id"
        type="button"
        role="tab"
        class="tr-pick"
        :aria-selected="it.id === selectedId ? 'true' : 'false'"
        @click="selectedId = it.id"
      >
        <b>{{ it.planName }}</b>
        <Badge :text="it.stage" :tone="stageTone(it.stage)" />
        <span class="pl-caption">{{ span(it.startOn, it.end) }}</span>
      </button>
    </div>

    <AssignmentDetail :assignment-id="selectedId" @changed="load" />

    <PlCard title="All my training">
      <PlTable :columns="columns" :rows="items" row-key="id" label="All my training" clickable @row-click="selectedId = $event.id">
        <template #cell-planName="{ row }">
          <b>{{ row.planName }}</b>
          <span class="pl-caption"> · mentor {{ row.mentorName ?? '—' }}</span>
        </template>
        <template #cell-period="{ row }">{{ span(row.startOn, row.end) }}</template>
        <template #cell-stage="{ row }"><Badge :text="row.stage" :tone="stageTone(row.stage)" /></template>
        <template #cell-assessment="{ row }">{{ row.assessment ? `${row.assessment.score}%` : '—' }}</template>
      </PlTable>
    </PlCard>
  </template>
</template>

<script setup>
// My training (reference trMine): the signed-in person's plans with a picker, the selected plan's detail
// and the list. GET /training/assignments?personId= and /training/rating?personId=.
import { computed, ref } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlTable } from '@/ui/index.js';
import AssignmentDetail from './AssignmentDetail.vue';
import { rating, span, stageTone } from './presentation.js';

const props = defineProps({
  personId: { type: String, required: true },
});

const items = ref(null);
const overall = ref(null);
const error = ref('');
const selectedId = ref('');

const ORDER = ['Trainee sign-off', 'Final review', 'Assessment', 'In training', 'Not started', 'Not completed', 'Completed'];

async function load() {
  error.value = '';

  try {
    const [list, r] = await Promise.all([
      api.get('/training/assignments', { query: { personId: props.personId } }),
      api.get('/training/rating', { query: { personId: props.personId } }),
    ]);

    items.value = list.items
      .filter((x) => x.roles.includes('trainee'))
      .sort((x, y) => ORDER.indexOf(x.stage) - ORDER.indexOf(y.stage) || (x.startOn < y.startOn ? 1 : -1));
    overall.value = r?.notApplicable ? null : r;

    if (!items.value.some((x) => x.id === selectedId.value)) {
      selectedId.value = (items.value.find((x) => x.actions.length) ?? items.value[0])?.id ?? '';
    }
  } catch (e) {
    error.value = e.message || 'Could not load your training.';
  }
}

load();

const selected = computed(() => items.value?.find((x) => x.id === selectedId.value) ?? null);
const active = computed(() => items.value.filter((x) => !['Completed', 'Not completed', 'Not started'].includes(x.stage)).length);
const completed = computed(() => items.value.filter((x) => x.stage === 'Completed').length);
const waitingCount = computed(() => items.value.reduce((n, x) => n + x.actions.length, 0));

const columns = [
  { key: 'planName', label: 'Plan' },
  { key: 'period', label: 'Period' },
  { key: 'stage', label: 'Stage' },
  { key: 'assessment', label: 'Assessment', num: true },
];
</script>
