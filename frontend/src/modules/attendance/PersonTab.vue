<template>
  <PlCard v-if="!personId" title="Person" empty empty-text="Pick a person in the top bar to see their attendance." />
  <PlAlert v-else-if="error" badge="Error" tone="danger">
    {{ error }}
    <template #action>
      <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
    </template>
  </PlAlert>
  <PlLoading v-else-if="!data" />
  <template v-else>
    <MissingBanner :missing="s.missing ?? []" :can-upload="canUpload" />

    <PlKpiRow :columns="5" :label="`Attendance of ${data.person.name}`">
      <StatTile
        eyebrow="Office days"
        :value="s.office ?? 0"
        :foot="s.duty ? `${s.duty} on duty (customer)` : `${s.officeAttended ?? 0} of ${s.officeRequired ?? 0} required`"
      />
      <StatTile
        eyebrow="WFH days"
        :value="s.wfh ?? 0"
        :foot="s.workingDays ? `${Math.round(((s.wfh ?? 0) / s.workingDays) * 100)}% of working days` : '—'"
      />
      <StatTile
        eyebrow="Leave"
        :value="fmtN(s.leave)"
        unit=" d"
        :foot="`${fmtN(s.planned)} planned · ${fmtN((s.shortNotice ?? 0) + (s.absent ?? 0))} unplanned · ${fmtN(s.excused)} sick`"
      />
      <StatTile
        v-if="data.checkInsVisible"
        eyebrow="Late check-ins"
        :value="s.late ?? 0"
        :badge="s.lateExcess ? `${s.lateExcess} over allowed` : ''"
        badge-tone="accent"
        :foot="s.lateExcess ? '' : `${s.allowance ?? 0} allowed in this period`"
      />
      <StatTile
        v-else
        eyebrow="Office policy"
        :value="s.officePct ?? '—'"
        :unit="s.officePct === null ? '' : '%'"
        foot="of required office days"
      />
      <StatTile
        eyebrow="Attendance score"
        :value="s.score ?? '—'"
        :unit="s.score === null ? '' : '%'"
        :badge="s.band ?? 'No data'"
        :badge-tone="bandTone(s.band)"
      />
    </PlKpiRow>

    <PlCard
      v-if="!s.workingDays"
      :title="`No record for ${data.period.short}`"
      empty
      :empty-text="s.missing?.length ? 'This period has not been uploaded yet.' : 'No working days in this period.'"
    />
    <template v-else>
      <div class="att-g21">
        <PlCard
          :title="`Calendar · ${data.period.label}`"
          subtitle="Each day as recorded by the HR system · customer visits from Pulse show as on duty"
        >
          <MonthCalendar
            :period="data.period"
            :calendar="data.calendar"
            :visits="data.visits"
            :stats="s"
            :label="data.period.short"
          />
          <AttLegend :show-late="data.checkInsVisible" />
        </PlCard>

        <PlCard title="How the score is built" :subtitle="`Period: ${data.period.short}`">
          <dl class="att-score">
            <div>
              <dt>
                <strong>Leave with notice</strong>
                <span class="pl-caption">
                  {{ fmtN(s.planned) }} planned ÷ {{ fmtN(needNotice) }} leave days that needed notice (sick and emergency left
                  out)
                </span>
              </dt>
              <dd class="pl-mono">{{ pct(s.noticePct) }}</dd>
            </div>
            <div>
              <dt>
                <strong>Office policy</strong>
                <span class="pl-caption">
                  {{ s.officeAttended ?? 0 }} of {{ s.officeRequired ?? 0 }} required office days (fewer in leave weeks; customer
                  on-site days count)
                </span>
              </dt>
              <dd class="pl-mono">{{ pct(s.officePct) }}</dd>
            </div>
            <div v-if="data.checkInsVisible">
              <dt>
                <strong>Punctuality</strong>
                <span class="pl-caption">
                  {{ (s.timed ?? 0) - (s.lateExcess ?? 0) }} of {{ s.timed ?? 0 }} check-ins on time after
                  {{ s.allowance ?? 0 }} allowed late days
                </span>
              </dt>
              <dd class="pl-mono">{{ pct(s.punctualityPct) }}</dd>
            </div>
          </dl>
          <p class="pl-caption att-note">
            Attendance score = average of the parts =
            <strong>{{ pct(s.score) }}</strong>
            <template v-if="s.band"> · {{ s.band }}</template>
          </p>
        </PlCard>
      </div>

      <div class="att-g2">
        <PlCard :title="`Leave · ${data.period.short}`" subtitle="Planned = applied with enough notice">
          <PlTable :columns="LEAVE_COLUMNS" :rows="leaves" row-key="date" label="Leave" empty-text="No leave in this period.">
            <template #cell-date="{ row }">
              {{ formatDate(row.date) }}
              <span v-if="row.amount === 0.5" class="pl-caption">½ day</span>
            </template>
            <template #cell-type="{ row }">{{ row.status === 'A' ? 'No leave applied' : (row.leaveType ?? '—') }}</template>
            <template #cell-applied="{ row }">{{ row.appliedOn ? formatDate(row.appliedOn) : '—' }}</template>
            <template #cell-notice="{ row }">{{
              row.notice === null || row.notice === undefined ? '—' : `${row.notice} d`
            }}</template>
            <template #cell-counted="{ row }">
              <Badge :text="LEAVE_CLASS[row.class]?.label ?? 'Leave'" :tone="LEAVE_CLASS[row.class]?.tone ?? ''" />
            </template>
          </PlTable>
        </PlCard>

        <PlCard v-if="data.checkInsVisible" title="Late check-ins" subtitle="Office and WFH log-in">
          <ul v-if="s.lateDays?.length" class="att-late-list">
            <li v-for="[day, time] in s.lateDays" :key="day">
              <span>{{ formatDate(day) }} · {{ WEEKDAYS[weekday(day)] }}</span>
              <span class="pl-mono">{{ time }}</span>
            </li>
          </ul>
          <PlEmpty v-else text="Always on time." />
          <p class="pl-caption att-note">
            {{ s.allowance ?? 0 }} late check-ins are allowed in this period before punctuality drops.
          </p>
        </PlCard>
      </div>
    </template>

    <PlCard title="Month by month" subtitle="Click a month to open it">
      <PlTable
        :columns="monthColumns"
        :rows="monthRows"
        row-key="month"
        label="Month by month"
        clickable
        @row-click="emit('open-month', `m:${$event.month}`)"
      >
        <template #cell-score="{ row }">
          <Badge v-if="row.score !== null && row.score !== undefined" :text="`${row.score}%`" :tone="bandTone(row.band)" />
          <span v-else class="pl-caption">{{ row.hasStats ? '—' : 'No record' }}</span>
        </template>
      </PlTable>
    </PlCard>

    <PlCard title="How attendance feeds culture values" subtitle="Same numbers the culture page uses">
      <div class="att-g2">
        <div class="att-feed">
          <Badge text="Discipline · rule h" tone="brand" />
          <span class="att-big pl-mono">{{ pct(data.culture?.ruleH?.score) }}</span>
          <span class="pl-caption">{{ ruleHText }}</span>
          <RouterLink class="pl-btn pl-btn-sm" to="/culture">Open culture values</RouterLink>
        </div>
        <div class="att-feed">
          <Badge text="Collaboration · presence" tone="brand" />
          <span class="att-big pl-mono">{{ pct(data.culture?.presence?.officePct) }}</span>
          <span class="pl-caption">{{ presenceText }}</span>
          <RouterLink class="pl-btn pl-btn-sm" to="/culture">Open culture values</RouterLink>
        </div>
      </div>
      <p class="pl-caption att-note">
        Approved leave never lowers a score: leave days are taken out of every denominator. Sick and emergency leave are always
        excused.
      </p>
    </PlCard>
  </template>
</template>

<script setup>
// Person tab (reference attPerson): KPIs, calendar, how the score is built, leave, late check-ins,
// month by month and the culture effect, from GET /attendance/people/:id?period=. Check-in figures
// arrive only for the person and their direct manager (checkInsVisible); otherwise they are hidden.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlEmpty, PlKpiRow, PlLoading, PlTable } from '@/ui/index.js';
import AttLegend from './AttLegend.vue';
import MissingBanner from './MissingBanner.vue';
import MonthCalendar from './MonthCalendar.vue';
import { LEAVE_CLASS, WEEKDAYS, bandTone, fmtN, pct, weekday } from './presentation.js';
import './attendance.css';

const LEAVE_COLUMNS = [
  { key: 'date', label: 'Date' },
  { key: 'type', label: 'Type' },
  { key: 'applied', label: 'Applied' },
  { key: 'notice', label: 'Notice', num: true },
  { key: 'counted', label: 'Counted as' },
];

const props = defineProps({
  periodKey: { type: String, required: true },
  personId: { type: String, default: null },
  canUpload: { type: Boolean, default: false },
});

const emit = defineEmits(['loaded', 'open-month']);

const data = ref(null);
const error = ref('');
let seq = 0;

async function load() {
  const mine = ++seq;
  error.value = '';
  data.value = null;

  if (!props.personId) {
    return;
  }

  try {
    const res = await api.get(`/attendance/people/${encodeURIComponent(props.personId)}`, {
      query: { period: props.periodKey },
    });

    if (mine === seq) {
      data.value = res;
      emit('loaded', res.person?.name ?? '');
    }
  } catch (e) {
    if (mine === seq) {
      error.value = e.message || 'Could not load attendance.';
    }
  }
}

watch(() => [props.periodKey, props.personId], load, { immediate: true });

const s = computed(() => data.value?.stats ?? {});

const needNotice = computed(() => (s.value.planned ?? 0) + (s.value.shortNotice ?? 0) + (s.value.absent ?? 0));

const ruleHText = computed(() => {
  const absent = data.value?.culture?.ruleH?.absent;
  const extra = absent ? ` · ${absent} absent without leave` : '';

  return `Attendance score for the quarter${extra}. A low score or an absence without leave lowers Discipline.`;
});

const presenceText = computed(() => {
  const score = data.value?.culture?.presence?.score;
  const extra = score === null || score === undefined ? '' : ` · scores ${score} of 5`;

  return `Office and on-site presence for the quarter${extra}.`;
});

const leaves = computed(() => s.value.leaves ?? []);

const monthColumns = computed(() => [
  { key: 'label', label: 'Month' },
  { key: 'office', label: 'Office', num: true },
  { key: 'wfh', label: 'WFH', num: true },
  { key: 'duty', label: 'Duty', num: true },
  { key: 'leave', label: 'Leave', num: true },
  { key: 'unplanned', label: 'Unpl.', num: true },
  ...(data.value?.checkInsVisible ? [{ key: 'late', label: 'Late', num: true }] : []),
  { key: 'policy', label: 'Policy', num: true },
  { key: 'score', label: 'Score', num: true },
]);

const monthRows = computed(() =>
  (data.value?.months ?? []).map((m) => {
    const x = m.stats;

    return {
      month: m.month,
      label: m.label,
      hasStats: Boolean(x),
      office: x?.office ?? null,
      wfh: x?.wfh ?? null,
      duty: x?.duty ?? null,
      leave: x ? fmtN(x.leave) : null,
      unplanned: x ? fmtN((x.shortNotice ?? 0) + (x.absent ?? 0)) : null,
      late: x?.late ?? null,
      policy: x?.officePct === null || x?.officePct === undefined ? null : `${x.officePct}%`,
      score: x?.score ?? null,
      band: x?.band ?? null,
    };
  }),
);
</script>

<style scoped>
.att-g21,
.att-g2 {
  display: grid;
  gap: var(--space-4);
  grid-template-columns: minmax(0, 1fr);
}
@media (min-width: 1100px) {
  .att-g21 {
    grid-template-columns: minmax(0, 2fr) minmax(0, 1fr);
  }
  .att-g2 {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
.att-score {
  margin: 0;
}
.att-score > div {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
}
.att-score dt {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.att-score dd {
  margin: 0;
  font-weight: 600;
}
.att-note {
  margin: var(--space-3) 0 0;
}
.att-late-list {
  margin: 0;
  padding: 0;
  list-style: none;
}
.att-late-list li {
  display: flex;
  justify-content: space-between;
  gap: var(--space-3);
  padding: var(--space-2) 0;
  border-bottom: 1px solid var(--border);
}
.att-feed {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: var(--space-2);
}
.att-big {
  font-size: 24px;
  font-weight: 600;
}
</style>
