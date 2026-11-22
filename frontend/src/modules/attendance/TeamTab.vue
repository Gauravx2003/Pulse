<template>
  <PlAlert v-if="error" badge="Error" tone="danger">
    {{ error }}
    <template #action>
      <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
    </template>
  </PlAlert>
  <PlLoading v-else-if="!data" />
  <template v-else>
    <MissingBanner :missing="data.missing" :can-upload="data.canUpload" />

    <PlKpiRow :columns="5" label="Team attendance">
      <StatTile eyebrow="People" :value="data.kpis.people" foot="in your team for this period" />
      <StatTile
        eyebrow="Attendance score"
        :value="data.kpis.averageScore ?? '—'"
        :unit="data.kpis.averageScore === null ? '' : '%'"
        :foot="data.kpis.averageScore === null ? 'No data' : 'Average of people with a record'"
      />
      <StatTile eyebrow="Leave taken" :value="fmtN(data.kpis.leaveDays)" unit=" d" foot="Planned, short notice and sick" />
      <StatTile
        eyebrow="Absent without leave"
        :value="fmtN(data.kpis.absentDays)"
        unit=" d"
        :badge="data.kpis.absentDays ? 'Check in' : ''"
        :badge-tone="data.kpis.absentDays ? 'danger' : ''"
        :foot="data.kpis.absentDays ? '' : 'None in this period'"
      />
      <StatTile
        eyebrow="To talk to"
        :value="data.kpis.talk"
        :badge="data.kpis.talk ? 'Talk' : ''"
        :badge-tone="data.kpis.talk ? 'danger' : ''"
        :foot="data.kpis.talk ? 'at the next 1:1' : 'Nobody in the Talk band'"
      />
    </PlKpiRow>

    <PlCard v-if="!hasRecords" :title="`No attendance for ${data.period.short}`" empty :empty-text="emptyText" />
    <template v-else>
      <PlCard :title="`People · ${data.period.label}`" subtitle="Click a person to open their attendance">
        <PlTable
          :columns="columns"
          :rows="data.people"
          row-key="personId"
          label="People"
          clickable
          @row-click="emit('open-person', $event.personId)"
        >
          <template #cell-name="{ row }">
            <span class="att-person">
              <span class="pl-avatar" aria-hidden="true">{{ initials(row.name) }}</span>
              <span class="att-person-text">
                <span>{{ row.name }}</span>
                <span class="pl-caption">{{ row.department ?? '—' }}</span>
              </span>
            </span>
          </template>
          <template #cell-split="{ row }">
            {{ row.workingDays ? `${row.office} · ${row.wfh} · ${row.duty}` : '—' }}
          </template>
          <template #cell-leave="{ row }">
            <template v-if="row.workingDays">
              {{ fmtN(row.leave) }}
              <span v-if="unplanned(row)" class="pl-caption">({{ fmtN(unplanned(row)) }} unpl.)</span>
            </template>
            <template v-else>—</template>
          </template>
          <template #cell-late="{ row }">{{ row.workingDays ? (row.late ?? '—') : '—' }}</template>
          <template #cell-officePct="{ row }">{{ pct(row.officePct) }}</template>
          <template #cell-noticePct="{ row }">{{ pct(row.noticePct) }}</template>
          <template #cell-band="{ row }">
            <Badge
              v-if="row.workingDays"
              :text="row.score === null ? row.band : `${row.band} · ${row.score}%`"
              :tone="bandTone(row.band)"
            />
            <span v-else class="pl-caption">No record</span>
          </template>
        </PlTable>
      </PlCard>

      <PlCard
        v-if="data.grid && gridUntil"
        title="Day by day"
        :subtitle="`Working days in ${data.period.short} · hover a day for the details`"
      >
        <DayGrid
          :grid="data.grid"
          :names="names"
          :until="gridUntil"
          :label="data.period.short"
          @open-person="emit('open-person', $event)"
        />
        <AttLegend />
      </PlCard>
    </template>

    <UploadsCard :months="attPeriods.months" :uploads="data.uploads" :today="today" :can-upload="data.canUpload" />
  </template>
</template>

<script setup>
// Team tab (reference attTeam): KPIs, people table, day-by-day grid and monthly uploads, from
// GET /attendance/team?period=. The server scopes the people and leaves out check-in figures the
// viewer may not see; a missing `late` shows as "—".
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlTable } from '@/ui/index.js';
import AttLegend from './AttLegend.vue';
import DayGrid from './DayGrid.vue';
import MissingBanner from './MissingBanner.vue';
import UploadsCard from './UploadsCard.vue';
import { bandTone, fmtN, pct } from './presentation.js';
import './attendance.css';

const props = defineProps({
  periodKey: { type: String, required: true },
  attPeriods: { type: Object, required: true },
  today: { type: String, default: null },
});

const emit = defineEmits(['open-person']);

const data = ref(null);
const error = ref('');
let seq = 0;

async function load() {
  const mine = ++seq;
  error.value = '';
  data.value = null;

  try {
    const res = await api.get('/attendance/team', {
      query: { period: props.periodKey },
    });

    if (mine === seq) {
      data.value = res;
    }
  } catch (e) {
    if (mine === seq) {
      error.value = e.message || 'Could not load team attendance.';
    }
  }
}

watch(() => props.periodKey, load, { immediate: true });

const hasRecords = computed(() => data.value.people.some((p) => p.workingDays));

const showLate = computed(() => data.value.people.some((p) => 'late' in p));

const columns = computed(() => [
  { key: 'name', label: 'Person' },
  { key: 'split', label: 'Office · WFH · duty', num: true },
  { key: 'leave', label: 'Leave', num: true },
  ...(showLate.value ? [{ key: 'late', label: 'Late', num: true }] : []),
  { key: 'officePct', label: 'Office policy', num: true },
  { key: 'noticePct', label: 'Notice', num: true },
  { key: 'band', label: 'Status' },
]);

const names = computed(() => Object.fromEntries(data.value.people.map((p) => [p.personId, p.name])));

/** Last day of the grid: the period end, or today for a live period. */
const gridUntil = computed(() => {
  const end = data.value.period.end ?? data.value.period.to;

  return props.today && props.today < end ? props.today : end;
});

const emptyText = computed(() =>
  data.value.canUpload
    ? 'Nothing uploaded for this period yet. Use Upload monthly CSV above.'
    : 'Nothing uploaded for this period yet.',
);

function unplanned(row) {
  return (row.shortNotice ?? 0) + (row.absent ?? 0);
}

function initials(name) {
  return (name ?? '')
    .split(' ')
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0])
    .join('')
    .toUpperCase();
}
</script>

<style scoped>
.att-person {
  display: flex;
  align-items: center;
  gap: var(--space-2);
}
.att-person-text {
  display: flex;
  flex-direction: column;
}
</style>
