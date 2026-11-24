<script setup>
// Action center (reference 6.1 "Screen detail — Action center", spec insight/action-center): the manager's
// landing page. Five KPIs; Do today (every module's items, High → Medium → Low, one button each opening
// the right screen, tab and person); people signals (click → Progress); the sprint forecast; next-sprint
// capacity with a team total; skills coverage; team pulse and team wins; the Weekly digest preview. The
// page loads in one request (GET /action_center/overview); a section that fails shows its own error.
import { computed, onMounted, reactive, ref } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { loadPeriods } from '@/shell/period.js';
import { setPerson } from '@/shell/person.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlPageHeader, PlTable } from '@/ui/index.js';
import { initials, num, val } from '../work/presentation.js';
import WinsList from '../progress/WinsList.vue';
import DigestModal from './DigestModal.vue';
import { openTarget } from './targets.js';

const SECTIONS = ['items', 'signals', 'forecast', 'capacity', 'coverage', 'pulse', 'wins'];

const PRIORITY_TONES = { High: 'danger', Medium: 'accent', Low: '' };

const SIGNAL_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'risk', label: 'Risk' },
  { key: 'signals', label: 'Signals' },
  { key: 'next', label: 'Suggested next step' },
];

const FORECAST_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'remaining', label: 'Remaining', num: true },
  { key: 'capacity', label: 'Capacity left', num: true },
  { key: 'pct', label: 'Fits' },
  { key: 'carry', label: 'Likely to carry over' },
];

const CAPACITY_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'days', label: 'Days', num: true },
  { key: 'leave', label: 'Leave', num: true },
  { key: 'visits', label: 'Visits', num: true },
  { key: 'trainingHours', label: 'Training h', num: true },
  { key: 'mentoringHours', label: 'Mentoring h', num: true },
  { key: 'focusDays', label: 'Focus days', num: true },
  { key: 'velocity', label: 'Velocity', num: true },
  { key: 'suggestedPoints', label: 'Suggest SP', num: true },
];

const router = useRouter();

const data = reactive(Object.fromEntries(SECTIONS.map((s) => [s, null])));
const errors = reactive(Object.fromEntries(SECTIONS.map((s) => [s, ''])));
const today = ref(null);
const digestOpen = ref(false);

onMounted(() => {
  loadPeriods()
    .then((p) => {
      today.value = p.today;
    })
    .catch((e) => console.error('GET /periods failed', e));

  load();
});

// one request for the whole page (GET /action_center/overview); a section that failed carries its own
// message and the others still show
async function load() {
  SECTIONS.forEach((s) => {
    errors[s] = '';
  });

  try {
    const body = await api.get('/action_center/overview');

    SECTIONS.forEach((s) => {
      data[s] = body.sections?.[s] ?? null;
      errors[s] = body.errors?.[s] ?? '';
    });
  } catch (e) {
    SECTIONS.forEach((s) => {
      errors[s] = e.message || 'This section could not be loaded.';
    });
  }
}

const items = computed(() => data.items?.items ?? []);
const high = computed(() => items.value.filter((i) => i.priority === 'High').length);
const unavailable = computed(() => data.items?.unavailable ?? []);

const signalRows = computed(() => data.signals?.people ?? []);
const watch = computed(() => data.signals?.watch ?? []);

const forecastRows = computed(() => data.forecast?.people ?? []);
const sprintName = computed(() => {
  const key = data.forecast?.sprint;

  return typeof key === 'string' ? `Sprint ${key.replace(/\D/g, '')}` : 'Sprint';
});

const capacitySprint = computed(() => data.capacity?.sprint ?? null);
const capacityRows = computed(() => {
  const rows = (data.capacity?.people ?? []).map((r) => ({ ...r }));

  if (rows.length) {
    rows.push({ id: 'total', name: 'Team', total: true, focusDays: data.capacity.total.focusDays, suggestedPoints: data.capacity.total.suggestedPoints });
  }

  return rows;
});

const coveragePeople = computed(() => data.coverage?.people ?? []);
const coverageColumns = computed(() => [
  { key: 'area', label: 'Area' },
  ...coveragePeople.value.map((p) => ({ key: `p:${p.id}`, label: p.initials })),
  { key: 'coverage', label: 'Coverage' },
  { key: 'suggestion', label: 'Suggestion' },
]);
const coverageRows = computed(() =>
  (data.coverage?.areas ?? []).map((a) => ({
    ...a,
    ...Object.fromEntries(coveragePeople.value.map((p) => [`p:${p.id}`, a.counts?.[p.id] ?? 0])),
  })),
);

const pulseWeeks = computed(() => data.pulse?.weeks ?? []);
const lowPulse = computed(() => data.pulse?.low ?? []);
const wins = computed(() => data.wins?.items ?? []);

function firstNames(names) {
  return names.map((n) => n.split(' ')[0]).join(', ');
}

function openPerson(row) {
  if (row?.id) {
    setPerson(row.id);
    router.push('/progress');
  }
}

function fitTone(pct) {
  return pct >= 100 ? '' : pct >= 80 ? 'is-accent' : 'is-danger';
}

function heat(count) {
  return count >= 4 ? 'u2' : count >= 2 ? 'u1' : 'u0';
}

function meter(value) {
  return `${Math.min(Math.max((val(value) ?? 0) / 5, 0), 1) * 100}%`;
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader
      :eyebrow="`Manager view · ${today ? formatDate(today) : ''}`"
      title="Action center"
      caption="Everything across Pulse that needs you, early warnings about people, and how the sprint and the next one look"
    >
      <template #actions>
        <button type="button" class="pl-btn" @click="digestOpen = true">Weekly digest</button>
      </template>
    </PlPageHeader>

    <PlKpiRow :columns="5" label="Action center summary">
      <StatTile
        eyebrow="Needs you"
        :value="data.items ? items.length : '—'"
        :badge="data.items ? (high ? `${high} high priority` : 'Nothing urgent') : ''"
        :badge-tone="high ? 'danger' : 'ok'"
      />
      <StatTile
        eyebrow="People to watch"
        :value="data.signals ? watch.length : '—'"
        :unit="data.signals ? `/ ${data.signals.count}` : ''"
        :foot="data.signals ? firstNames(watch) || 'Nobody at high risk' : ''"
      />
      <StatTile
        :eyebrow="`${sprintName} forecast`"
        :value="data.forecast ? num(data.forecast.fitsPct, 0) : '—'"
        :unit="data.forecast ? '% fits' : ''"
        :badge="data.forecast ? (data.forecast.carry ? `${data.forecast.carry} issues likely to carry over` : 'Fits capacity') : ''"
        :badge-tone="data.forecast?.carry ? 'accent' : 'ok'"
      />
      <StatTile
        eyebrow="Team pulse"
        :value="data.pulse ? num(data.pulse.mood, 1) : '—'"
        :unit="data.pulse && val(data.pulse.mood) !== null ? '/ 5' : ''"
        :badge="data.pulse ? (lowPulse.length ? `${lowPulse.length} low this week` : 'No low scores') : ''"
        :badge-tone="lowPulse.length ? 'danger' : 'ok'"
      />
      <StatTile
        eyebrow="Next sprint capacity"
        :value="data.capacity ? num(data.capacity.total.suggestedPoints, 0) : '—'"
        :unit="data.capacity ? 'SP' : ''"
        :foot="capacitySprint ? `${capacitySprint.name} · ${formatDate(capacitySprint.from)}` : ''"
      />
    </PlKpiRow>

    <PlCard
      title="Do today"
      subtitle="Collected from Work items, Effort, 1:1s, pulse, customer visits, training, 360°, bugs, PRs, appraisal and integrations · highest priority first"
      :loading="!data.items && !errors.items"
      :empty="!!data.items && !items.length"
      empty-text="Nothing needs you right now."
    >
      <template #actions>
        <Badge v-if="data.items" :text="`${items.length} item${items.length === 1 ? '' : 's'}`" :tone="items.length ? 'accent' : 'ok'" />
      </template>
      <PlAlert v-if="errors.items" badge="Error" tone="danger">{{ errors.items }}</PlAlert>
      <ul class="todo">
        <li v-for="(i, n) in items" :key="`${i.key}-${n}`" class="todo-item">
          <div class="todo-body">
            <div class="todo-head">
              <Badge :text="i.priority" :tone="PRIORITY_TONES[i.priority] ?? ''" />
              <Badge :text="i.source" tone="brand" />
              <b>{{ i.personName }}</b>
            </div>
            <div>{{ i.text }}</div>
          </div>
          <button
            type="button"
            class="pl-btn pl-btn-sm"
            :class="{ 'pl-btn-primary': i.priority === 'High' }"
            :aria-label="`${i.button}: ${i.personName} · ${i.text}`"
            @click="openTarget(router, i.target)"
          >{{ i.button }}</button>
        </li>
      </ul>
      <p v-if="unavailable.length" class="pl-caption">Some sources could not be read just now: {{ unavailable.join(', ') }}.</p>
    </PlCard>

    <PlCard
      title="People signals"
      subtitle="Early warnings from workload, weekend work, unplanned share, Jira updates, 1:1 gaps, weekly pulse, index trend, recognition and training — signals for a conversation, never a rating"
      :loading="!data.signals && !errors.signals"
    >
      <PlAlert v-if="errors.signals" badge="Error" tone="danger">{{ errors.signals }}</PlAlert>
      <PlTable v-if="data.signals" :columns="SIGNAL_COLUMNS" :rows="signalRows" row-key="id" label="People signals" clickable @row-click="openPerson">
        <template #cell-name="{ row }">
          <span class="who"><span class="pl-avatar" aria-hidden="true">{{ initials(row.name) }}</span>{{ row.name }}</span>
        </template>
        <template #cell-risk="{ row }">
          <Badge :text="row.risk" :tone="row.tone" />
        </template>
        <template #cell-signals="{ row }">
          <ul v-if="row.signals.length" class="signals">
            <li v-for="(s, n) in row.signals" :key="n">
              <Badge :text="s.label" :tone="s.tone" />
              <span class="pl-caption">{{ s.reason }}</span>
            </li>
          </ul>
          <span v-else class="pl-caption">No signals</span>
        </template>
        <template #cell-next="{ row }">
          <span class="pl-caption">{{ row.next }}</span>
        </template>
      </PlTable>
    </PlCard>

    <PlCard
      :title="`${sprintName} forecast`"
      subtitle="Open issues ordered by due date are filled into each person’s remaining focus hours; what does not fit is likely to carry over — decide now, not on the last day"
      :loading="!data.forecast && !errors.forecast"
    >
      <template #actions>
        <RouterLink class="pl-btn pl-btn-sm" to="/team-utilization">Team utilization →</RouterLink>
      </template>
      <PlAlert v-if="errors.forecast" badge="Error" tone="danger">{{ errors.forecast }}</PlAlert>
      <PlTable v-if="data.forecast" :columns="FORECAST_COLUMNS" :rows="forecastRows" row-key="id" label="Sprint forecast" empty-text="No active sprint.">
        <template #cell-remaining="{ row }">{{ num(row.remaining, 0) }} h</template>
        <template #cell-capacity="{ row }">{{ num(row.capacity, 0) }} h</template>
        <template #cell-pct="{ row }">
          <span class="fit">
            <span class="pl-meter" role="img" :aria-label="`${row.name}: ${row.pct}% fits`">
              <span class="pl-meter-fill" :class="fitTone(row.pct)" :style="{ width: `${Math.min(row.pct, 100)}%` }" />
            </span>
            <span class="pl-mono">{{ row.pct }}%</span>
          </span>
        </template>
        <template #cell-carry="{ row }">
          <span v-if="row.carry.length" class="carry">
            <span v-for="c in row.carry" :key="c.key"><span class="pl-mono">{{ c.key }}</span> <span class="pl-caption">{{ num(c.rem, 0) }} h</span></span>
          </span>
          <span v-else class="pl-caption">None</span>
        </template>
      </PlTable>
    </PlCard>

    <PlCard
      :title="capacitySprint ? `Next sprint capacity · ${capacitySprint.name} (${formatDate(capacitySprint.from)} – ${formatDate(capacitySprint.end)})` : 'Next sprint capacity'"
      subtitle="Leave from HRMS, planned customer visits, training plans and mentoring time are taken out first · velocity = done SP per working day over the last 3 sprints"
      :loading="!data.capacity && !errors.capacity"
    >
      <PlAlert v-if="errors.capacity" badge="Error" tone="danger">{{ errors.capacity }}</PlAlert>
      <PlTable v-if="data.capacity" :columns="CAPACITY_COLUMNS" :rows="capacityRows" row-key="id" label="Next sprint capacity">
        <template #cell-name="{ row }">
          <b v-if="row.total">{{ row.name }}</b>
          <span v-else class="who">{{ row.name }}<Badge v-if="row.newJoiner" text="Onboarding" tone="info" /></span>
        </template>
        <template #cell-days="{ row }">{{ row.total ? '' : row.days }}</template>
        <template #cell-leave="{ row }">{{ row.total ? '' : row.leave }}</template>
        <template #cell-visits="{ row }">
          <template v-if="!row.total"><Badge v-if="row.visits" :text="`${row.visits} d`" tone="brand" /><span v-else>0</span></template>
        </template>
        <template #cell-trainingHours="{ row }">{{ row.total ? '' : row.trainingHours }}</template>
        <template #cell-mentoringHours="{ row }">{{ row.total ? '' : num(row.mentoringHours, 1) }}</template>
        <template #cell-focusDays="{ row }"><b>{{ num(row.focusDays, 1) }}</b></template>
        <template #cell-velocity="{ row }">{{ row.total ? '' : `${num(row.velocity, 1)} SP/d` }}</template>
        <template #cell-suggestedPoints="{ row }"><b>{{ val(row.suggestedPoints) === null ? '—' : row.suggestedPoints }}</b></template>
      </PlTable>
    </PlCard>

    <PlCard
      title="Skills coverage"
      subtitle="PRs merged and Jira components worked per area in the last 6 months (TFS, GitHub, Jira) · pair someone on thin areas or add a training plan"
      :loading="!data.coverage && !errors.coverage"
    >
      <PlAlert v-if="errors.coverage" badge="Error" tone="danger">{{ errors.coverage }}</PlAlert>
      <template v-if="data.coverage">
        <PlTable :columns="coverageColumns" :rows="coverageRows" row-key="area" label="Skills coverage">
          <template v-for="p in coveragePeople" :key="p.id" #[`cell-p:${p.id}`]="{ row }">
            <span v-if="row[`p:${p.id}`]" class="pl-heat-cell cell" :class="heat(row[`p:${p.id}`])" :aria-label="`${p.name}: ${row[`p:${p.id}`]}`">{{ row[`p:${p.id}`] }}</span>
            <span v-else class="pl-caption" :aria-label="`${p.name}: none`">·</span>
          </template>
          <template #cell-area="{ row }"><b>{{ row.area }}</b></template>
          <template #cell-coverage="{ row }"><Badge :text="row.coverage" :tone="row.tone" /></template>
          <template #cell-suggestion="{ row }"><span class="pl-caption">{{ row.suggestion }}</span></template>
        </PlTable>
        <p class="pl-caption">{{ coveragePeople.map((p) => `${p.initials} ${p.name}`).join(' · ') }}</p>
      </template>
    </PlCard>

    <div class="pl-grid two">
      <PlCard
        title="Team pulse · last 6 weeks"
        subtitle="Weekly check-in: mood and workload (1–5)"
        :loading="!data.pulse && !errors.pulse"
        :empty="!!data.pulse && !pulseWeeks.length"
        empty-text="No pulse check-ins yet."
      >
        <PlAlert v-if="errors.pulse" badge="Error" tone="danger">{{ errors.pulse }}</PlAlert>
        <ul class="weeks">
          <li v-for="w in pulseWeeks" :key="w.week" class="week">
            <span class="pl-caption">Week of {{ formatDate(w.week) }}</span>
            <span class="bar">
              <span class="pl-caption label">Mood</span>
              <span class="pl-meter" role="img" :aria-label="`Mood ${w.mood} of 5`">
                <span class="pl-meter-fill" :class="w.mood >= 3.5 ? '' : 'is-accent'" :style="{ width: meter(w.mood) }" />
              </span>
              <span class="pl-mono">{{ num(w.mood, 1) }}</span>
            </span>
            <span class="bar">
              <span class="pl-caption label">Load</span>
              <span class="pl-meter" role="img" :aria-label="`Workload ${w.workload} of 5`">
                <span class="pl-meter-fill" :class="w.workload >= 4 ? 'is-warn' : 'is-info'" :style="{ width: meter(w.workload) }" />
              </span>
              <span class="pl-mono">{{ num(w.workload, 1) }}</span>
            </span>
          </li>
        </ul>
      </PlCard>

      <PlCard title="Team wins" subtitle="Visible to the whole team" :loading="!data.wins && !errors.wins">
        <PlAlert v-if="errors.wins" badge="Error" tone="danger">{{ errors.wins }}</PlAlert>
        <WinsList v-if="data.wins" :items="wins" />
      </PlCard>
    </div>

    <DigestModal :open="digestOpen" @close="digestOpen = false" />
  </div>
</template>

<style scoped>
.pl-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
  align-items: start;
}

@media (max-width: 900px) {
  .pl-grid.two {
    grid-template-columns: minmax(0, 1fr);
  }
}

.todo {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
}

.todo-item {
  display: grid;
  grid-template-columns: minmax(0, 1fr) auto;
  gap: var(--space-3);
  align-items: center;
  padding: var(--space-3) 0;
  border-bottom: 1px solid var(--border);
}

.todo-item:last-child {
  border-bottom: 0;
}

.todo-body {
  min-width: 0;
  overflow-wrap: anywhere;
}

.todo-head {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-1);
}

.who {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
}

.signals {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-1);
}

.fit {
  display: grid;
  grid-template-columns: minmax(60px, 1fr) auto;
  gap: var(--space-2);
  align-items: center;
  min-width: 120px;
}

.carry {
  display: flex;
  flex-wrap: wrap;
  gap: var(--space-1) var(--space-3);
}

.cell {
  display: inline-grid;
  place-items: center;
  width: 34px;
  height: 22px;
}

.weeks {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.week {
  display: grid;
  grid-template-columns: 110px minmax(0, 1fr) minmax(0, 1fr);
  gap: var(--space-3);
  align-items: center;
}

@media (max-width: 700px) {
  .week {
    grid-template-columns: minmax(0, 1fr);
  }
}

.bar {
  display: grid;
  grid-template-columns: 40px minmax(40px, 1fr) auto;
  gap: var(--space-2);
  align-items: center;
}

.label {
  white-space: nowrap;
}
</style>
