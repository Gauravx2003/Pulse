<script setup>
// Team utilization (reference 6.3 "Screen detail — Team utilization", spec work/team-utilization), for
// team.view and all.view (others get the no-access card from the route). The live sprint shows the
// rebalance suggestion, four KPIs, the daily heatmap and the people table; a closed sprint, month or
// release shows the history KPIs, the people of the period, team utilization by sprint and the release
// card. Every number and band comes from GET /utilization/team; a row opens that person's Overview.
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile, TeamHeatmap, UtilizationMeter } from '@/kit/index.js';
import { setPerson } from '@/shell/person.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable, showToast } from '@/ui/index.js';
import { band, hours, initials, num, pct, toolUrl, val } from '../work/presentation.js';

const LIVE_COLUMNS = [
  { key: 'person', label: 'Person' },
  { key: 'open', label: 'Open', num: true },
  { key: 'stale', label: 'Stale' },
  { key: 'work', label: 'Work', num: true },
  { key: 'capacity', label: 'Capacity', num: true },
  { key: 'utilization', label: 'Utilization' },
  { key: 'dailyUpdates', label: 'Daily updates', num: true },
  { key: 'discipline', label: 'Discipline', num: true },
  { key: 'index', label: 'Index', num: true },
];

const CLOSED_COLUMNS = [
  { key: 'person', label: 'Person' },
  { key: 'issues', label: 'Issues done', num: true },
  { key: 'sp', label: 'SP done / committed', num: true },
  { key: 'commitment', label: 'Commitment', num: true },
  { key: 'carried', label: 'Carried over', num: true },
  { key: 'logged', label: 'Hours logged', num: true },
  { key: 'utilization', label: 'Utilization' },
];

// The band names only: their limits are rules (Rules & thresholds), so the screen does not print them.
const LEGEND = [
  { band: 'info', label: 'Under' },
  { band: 'ok', label: 'Healthy' },
  { band: 'accent', label: 'At capacity' },
  { band: 'warn', label: 'Over' },
  { band: 'danger', label: 'Critical' },
  { label: 'Leave, visit, training or not joined yet' },
];

const router = useRouter();
const { key: periodKey, periods, kinds } = useSharedPeriod(['sprint', 'month', 'release']);

const data = ref(null);
const error = ref('');

let request = 0;

watch(periodKey, load, { immediate: true });

async function load() {
  if (!periodKey.value) {
    return;
  }

  const mine = ++request;

  error.value = '';

  try {
    const body = await api.get('/utilization/team', { query: { period: periodKey.value } });

    if (mine === request) {
      data.value = body;
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'Team utilization could not be loaded.';
    }
  }
}

const live = computed(() => !!data.value?.live);
const kpis = computed(() => data.value?.kpis ?? {});
const teamBand = computed(() => band(kpis.value.band));
const suggestion = computed(() => val(data.value?.suggestion));

// a live Jira issue opens in Jira to be reassigned there (task 25.2); Pulse never writes to Jira
const suggestionUrl = computed(() => toolUrl(suggestion.value?.issue));

const release = computed(() => val(data.value?.release));

const title = computed(() => `${data.value?.team?.name ? `${data.value.team.name} team` : 'Team'} · ${data.value?.period?.short ?? ''}`);

const caption = computed(() => {
  if (!data.value) {
    return '';
  }

  const count = data.value.team?.people ?? 0;
  const onboarding = live.value && kpis.value.onboarding ? ` (${kpis.value.onboarding} onboarding)` : '';

  return `${data.value.period.label} · ${count} ${count === 1 ? 'person' : 'people'}${onboarding}`;
});

function names(list) {
  const people = val(list) ?? [];

  return people.length ? people.map((p) => `${p.name.split(' ')[0]} ${p.utilization}%`).join(', ') : 'Nobody';
}

const heatmap = computed(() => {
  const h = data.value?.heatmap;

  if (!h) {
    return { days: [], rows: [] };
  }

  return {
    days: (h.days ?? []).map((d) => `${formatDate(d).split(' ')[0]}${d === h.today ? ' ●' : ''}`),
    rows: (h.rows ?? []).map((r) => ({
      id: r.id,
      name: r.name,
      initials: initials(r.name),
      cells: (r.cells ?? []).map((c) => (c.label ? { label: c.label } : { value: num(c.value, 0), band: band(c.band).tone })),
      avg: r.onboarding ? '—' : pct(r.now),
    })),
  };
});

const liveRows = computed(() =>
  (data.value?.people ?? []).map((p) => ({
    id: p.id,
    person: p.name,
    initials: initials(p.name),
    open: num(p.open, 0),
    stale: val(p.stale) ?? 0,
    work: `${num(p.work)} d`,
    capacity: `${num(p.capacity, 0)} d`,
    utilization: val(p.utilization) ?? 0,
    band: band(p.band),
    onboarding: !!p.onboarding,
    dailyUpdates: pct(p.dailyUpdates),
    discipline: num(p.discipline, 1),
    index: num(p.index, 2),
  })),
);

const closedRows = computed(() =>
  (data.value?.people ?? []).map((p) => ({
    id: p.id,
    person: p.name,
    initials: initials(p.name),
    issues: `${num(p.issuesDone, 0)} / ${num(p.issues, 0)}`,
    sp: `${num(p.storyPointsDone)} / ${num(p.committed)}`,
    commitment: pct(p.commitment),
    carried: num(p.carriedOver, 0),
    logged: hours(p.logged),
    utilization: val(p.utilization) ?? 0,
    band: band(p.band),
  })),
);

const sprints = computed(() =>
  (data.value?.sprints ?? []).map((s) => ({
    period: s.period,
    label: `${s.short}${s.live ? ' · forecast' : ''}`,
    value: val(s.utilization) ?? 0,
    tone: band(s.band).tone || 'ok',
  })),
);

function meterWidth(value) {
  return `${(Math.min(Math.max(value, 0), 140) / 140) * 100}%`;
}

function openPerson(id) {
  setPerson(id);
  router.push('/overview');
}

function reassign() {
  showToast(`Reassign ${suggestion.value.issue.key} in Jira: Pulse only suggests the move and changes nothing.`);
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Manager view" :title="title" :caption="caption">
      <template #actions>
        <PlPeriodControl v-model="periodKey" :kinds="kinds" :periods="periods" />
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlLoading v-if="!data && !error" />

    <template v-if="data">
      <!-- live sprint -->
      <template v-if="live">
        <PlAlert v-if="suggestion" badge="Suggestion" tone="accent">
          {{ suggestion.text }}
          <template #action>
            <a
              v-if="suggestionUrl"
              class="pl-btn pl-btn-sm"
              :href="suggestionUrl"
              target="_blank"
              rel="noopener noreferrer"
            >
              Reassign in Jira<span class="pl-sr-only"> (opens {{ suggestion.issue.key }} in a new tab)</span>
            </a>
            <button v-else type="button" class="pl-btn pl-btn-sm" @click="reassign">Reassign in Jira</button>
          </template>
        </PlAlert>

        <PlKpiRow :columns="4" label="Team this sprint">
          <StatTile
            eyebrow="Team utilization"
            :value="num(kpis.utilization, 0)"
            unit="%"
            foot="All work vs all capacity"
            :badge="teamBand.label"
            :badge-tone="teamBand.tone"
          />
          <StatTile
            eyebrow="Overloaded"
            :value="num((kpis.overloaded ?? []).length, 0)"
            :unit="`/ ${num(kpis.people, 0)}`"
            :foot="names(kpis.overloaded)"
          />
          <StatTile
            eyebrow="Under-utilized"
            :value="num((kpis.underUtilized ?? []).length, 0)"
            :unit="`/ ${num(kpis.countable, 0)}`"
            :foot="names(kpis.underUtilized)"
          />
          <StatTile
            eyebrow="Stale issues"
            :value="num(kpis.stale, 0)"
            :unit="`/ ${num(kpis.open, 0)} open`"
            :foot="val(kpis.staleShare) !== null ? `${pct(kpis.staleShare)} of open work` : 'No open work'"
            :badge="kpis.stale ? 'Stale' : ''"
            :badge-tone="kpis.staleAlert ? 'danger' : 'accent'"
          />
        </PlKpiRow>

        <div class="heat-scroll" role="region" aria-label="Daily utilization" tabindex="0">
          <TeamHeatmap
            title="Daily utilization"
            caption="Before today: logged ÷ focus hours · today onward: remaining ÷ capacity · click a person"
            :days="heatmap.days"
            :rows="heatmap.rows"
            :legend="LEGEND"
            @select="openPerson"
          />
        </div>

        <PlCard title="People" subtitle="Sorted by utilization · click a person to open their overview">
          <PlTable
            :columns="LIVE_COLUMNS"
            :rows="liveRows"
            row-key="id"
            label="People of the team"
            clickable
            empty-text="Nobody to show for this team."
            @row-click="openPerson($event.id)"
          >
            <template #cell-person="{ row }">
              <span class="who"><span class="pl-avatar" aria-hidden="true">{{ row.initials }}</span>{{ row.person }}</span>
            </template>
            <template #cell-stale="{ row }">
              <Badge :text="row.stale ? `${row.stale} stale` : 'Fresh'" :tone="row.stale ? 'accent' : 'ok'" />
            </template>
            <template #cell-utilization="{ row }">
              <span class="meter-cell">
                <span class="pl-meter" aria-hidden="true">
                  <span class="pl-meter-fill" :class="row.band.tone && row.band.tone !== 'ok' ? `is-${row.band.tone}` : ''" :style="{ width: meterWidth(row.utilization) }" />
                </span>
                <Badge v-if="row.onboarding" text="Onboarding" tone="info" />
                <Badge v-else :text="`${row.utilization}%`" :tone="row.band.tone" />
              </span>
            </template>
          </PlTable>
        </PlCard>
      </template>

      <!-- closed sprint, month or release -->
      <template v-else>
        <PlKpiRow :columns="5" :label="data.period.short">
          <StatTile
            eyebrow="Team utilization"
            :value="num(kpis.utilization, 0)"
            unit="%"
            foot="Logged vs capacity"
            :badge="teamBand.label"
            :badge-tone="teamBand.tone"
          />
          <StatTile
            eyebrow="Story points done"
            :value="num(kpis.storyPointsDone)"
            :unit="`/ ${num(kpis.committed)}`"
            :foot="val(kpis.commitment) !== null ? `${pct(kpis.commitment)} of commitment` : 'Nothing committed'"
          />
          <StatTile eyebrow="Carried over" :value="num(kpis.carriedOver, 0)" foot="issues" />
          <StatTile
            eyebrow="Over 100%"
            :value="num((kpis.over ?? []).length, 0)"
            :unit="`/ ${num(kpis.people, 0)}`"
            :foot="names(kpis.over)"
          />
          <StatTile eyebrow="Under-used" :value="num((kpis.under ?? []).length, 0)" :foot="names(kpis.under)" />
        </PlKpiRow>

        <PlCard
          v-if="release"
          :title="`${release.name} · release ${release.key}`"
          :subtitle="`Sprints ${(release.sprints ?? []).map((s) => s.replace('s:', '')).join(', ') || '—'} · team delivered ${num(release.teamDone)} of ${num(release.committed)} SP`"
        >
          <template #actions>
            <Badge
              :text="release.status === 'Released' ? `Released ${formatDate(release.releaseDate)}` : `Due ${formatDate(release.releaseDate ?? release.to)}`"
              :tone="release.status === 'Released' ? 'ok' : 'brand'"
            />
          </template>
          <p class="pl-caption">{{ num(release.workingDaysLeft, 0) }} working days left</p>
        </PlCard>

        <PlCard :title="`People · ${data.period.short}`" subtitle="Logged hours ÷ working days · click a person to open their overview">
          <PlTable
            :columns="CLOSED_COLUMNS"
            :rows="closedRows"
            row-key="id"
            label="People of the period"
            clickable
            empty-text="Nobody worked in this period."
            @row-click="openPerson($event.id)"
          >
            <template #cell-person="{ row }">
              <span class="who"><span class="pl-avatar" aria-hidden="true">{{ row.initials }}</span>{{ row.person }}</span>
            </template>
            <template #cell-utilization="{ row }">
              <span class="meter-cell">
                <span class="pl-meter" aria-hidden="true">
                  <span class="pl-meter-fill" :class="row.band.tone && row.band.tone !== 'ok' ? `is-${row.band.tone}` : ''" :style="{ width: meterWidth(row.utilization) }" />
                </span>
                <Badge :text="`${row.utilization}%`" :tone="row.band.tone" />
              </span>
            </template>
          </PlTable>
        </PlCard>

        <PlCard v-if="sprints.length" title="Team utilization by sprint" subtitle="Click a sprint to open it">
          <button v-for="s in sprints" :key="s.period" type="button" class="sprint-row" @click="periodKey = s.period">
            <UtilizationMeter :label="s.label" :value="s.value" :band="s.tone" />
          </button>
        </PlCard>
      </template>
    </template>
  </div>
</template>

<style scoped>
.heat-scroll {
  max-width: 100%;
  overflow-x: auto;
}

.heat-scroll :deep(.rows) {
  min-width: 860px;
}

.who {
  display: inline-flex;
  align-items: center;
  gap: var(--space-2);
  white-space: nowrap;
}

.meter-cell {
  display: inline-grid;
  grid-template-columns: 120px auto;
  align-items: center;
  gap: var(--space-2);
}

.sprint-row {
  display: block;
  width: 100%;
  padding: 0;
  border: 0;
  background: none;
  text-align: left;
  cursor: pointer;
  font: inherit;
  color: inherit;
}
</style>
