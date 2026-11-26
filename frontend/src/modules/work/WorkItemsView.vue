<script setup>
// Work items (reference 7.2 "Screen detail — Work items", spec work/work-items). The live sprint shows
// four KPIs, the issue list with filter chips and counts, search and sort, Nudge on Slack, and the daily
// load; a closed sprint, month or release shows the history KPIs, the release card, the sprints of the
// period and the issue list with its own filters. A row opens the issue drawer. The server filters,
// searches and sorts (GET /work/people/:id/work-items). Once live Jira rows exist (task 25.2), each row
// shows its source (Seed / Jira), a Jira row links to the issue in Jira, and a source filter appears.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { statusTone } from '@/kit/tones.js';
import { can } from '@/shell/session.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable, PlTabs, showToast } from '@/ui/index.js';
import IssueDrawer from './IssueDrawer.vue';
import {
  HISTORY_FILTERS,
  HISTORY_SORTS,
  LIVE_FILTERS,
  LIVE_SORTS,
  SOURCES,
  band,
  flags,
  hours,
  idleText,
  identityCaption,
  num,
  pct,
  sourceBadge,
  toolUrl,
  val,
} from './presentation.js';
import { useViewedPerson } from './useViewedPerson.js';

const LIVE_COLUMNS = [
  { key: 'key', label: 'Key' },
  { key: 'summary', label: 'Summary' },
  { key: 'status', label: 'Status' },
  { key: 'sp', label: 'SP', num: true },
  { key: 'remaining', label: 'Remaining', num: true },
  { key: 'due', label: 'Due' },
  { key: 'last', label: 'Last activity' },
  { key: 'signals', label: 'Signals' },
];

const HISTORY_COLUMNS = [
  { key: 'key', label: 'Key' },
  { key: 'summary', label: 'Summary' },
  { key: 'type', label: 'Type' },
  { key: 'sprint', label: 'Sprint' },
  { key: 'release', label: 'Release' },
  { key: 'status', label: 'Status' },
  { key: 'sp', label: 'SP', num: true },
  { key: 'estimate', label: 'Estimate → Logged', num: true },
  { key: 'cycle', label: 'Cycle', num: true },
  { key: 'resolved', label: 'Resolved' },
];

const SPRINT_COLUMNS = [
  { key: 'sprint', label: 'Sprint' },
  { key: 'issues', label: 'Issues', num: true },
  { key: 'committed', label: 'Committed', num: true },
  { key: 'done', label: 'Done', num: true },
  { key: 'commitment', label: 'Commitment', num: true },
  { key: 'carried', label: 'Carried', num: true },
  { key: 'hours', label: 'Hours', num: true },
  { key: 'utilization', label: 'Utilization', num: true },
];

const { id: personId } = useViewedPerson();
const { key: periodKey, periods, kinds } = useSharedPeriod(['sprint', 'month', 'release']);

const data = ref(null);
const loading = ref(false);
const error = ref('');
const filter = ref('all');
const source = ref('all');
const sort = ref('');
const query = ref('');
const drawerKey = ref(null);
const nudging = ref(false);

let request = 0;
let typing = null;

watch([personId, periodKey], () => {
  filter.value = 'all';
  source.value = 'all';
  sort.value = '';
  load();
}, { immediate: true });

watch([filter, source, sort], load);

watch(query, () => {
  clearTimeout(typing);
  typing = setTimeout(load, 250);
});

async function load() {
  if (!personId.value || !periodKey.value) {
    return;
  }

  const mine = ++request;

  loading.value = true;
  error.value = '';

  try {
    const body = await api.get(`/work/people/${personId.value}/work-items`, {
      query: {
        period: periodKey.value,
        filter: filter.value,
        source: source.value === 'all' ? null : source.value,
        q: query.value.trim() || null,
        sort: sort.value || null,
      },
    });

    if (mine === request) {
      data.value = body;
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'The work items could not be loaded.';
    }
  } finally {
    if (mine === request) {
      loading.value = false;
    }
  }
}

const live = computed(() => !!data.value?.live);
const kpis = computed(() => data.value?.kpis ?? {});
const metrics = computed(() => data.value?.metrics ?? {});
const release = computed(() => val(data.value?.release));
const closedBand = computed(() => band(data.value?.utilizationBand));
const onboarding = computed(() => !!data.value?.onboarding);

const chips = computed(() =>
  (live.value ? LIVE_FILTERS : HISTORY_FILTERS).map((f) => ({ ...f, count: data.value?.chips?.[f.key] ?? 0 })),
);

const sorts = computed(() => (live.value ? LIVE_SORTS : HISTORY_SORTS));

// the source filter and badges appear once the person has live rows; seed-only lists look as before
const showSource = computed(() => (data.value?.sources?.jira ?? 0) > 0 || source.value !== 'all');

const sourceOptions = computed(() => SOURCES.map((s) => ({ ...s, count: data.value?.sources?.[s.key] ?? 0 })));

const liveRows = computed(() =>
  (data.value?.items ?? []).map((i) => ({
    key: i.key,
    summary: i.summary,
    status: i.status,
    sp: num(i.storyPoints),
    remaining: hours(i.remainingHours),
    due: formatDate(val(i.dueOn)),
    last: idleText(i.idleDays),
    flags: flags(i),
    source: sourceBadge(i.source),
    url: toolUrl(i),
  })),
);

const historyRows = computed(() =>
  (data.value?.items ?? []).map((i) => ({
    key: i.key,
    summary: i.summary,
    type: i.issueType,
    sprint: val(i.sprintKey) ?? '—',
    release: val(i.releaseKey) ?? '—',
    status: i.status,
    sp: num(i.storyPoints),
    estimate: `${hours(i.estimateHours)} → ${hours(i.spentHours)}`,
    cycle: val(i.cycle) !== null ? `${num(i.cycle, 0)} d` : '—',
    resolved: formatDate(val(i.resolvedOn)),
    carried: !!i.carriedOver,
    source: sourceBadge(i.source),
    url: toolUrl(i),
  })),
);

const sprintRows = computed(() =>
  (data.value?.sprints ?? []).map((s) => ({
    period: s.period,
    // The live sprint's utilization is planned work vs capacity, not hours logged: say so.
    sprint: s.live ? `${s.short} · live` : s.short,
    issues: num(s.issues, 0),
    committed: num(s.committed),
    done: num(s.done),
    commitment: pct(s.commitment),
    carried: num(s.carried, 0),
    hours: hours(s.logged),
    utilization: s.live ? `${pct(s.util)} forecast` : pct(s.util),
  })),
);

const staleKeys = computed(() =>
  (data.value?.items ?? []).filter((i) => flags(i).some((f) => f.text === 'Stale' || f.text === 'Not started')).map((i) => i.key),
);

const daily = computed(() => {
  const d = data.value?.dailyLoad;
  const focus = val(d?.focus) ?? 0;

  return (val(d?.days) ?? []).map((day) => {
    const h = val(day.hours);

    return {
      date: day.date,
      label: formatDate(day.date),
      kind: day.kind,
      note: val(day.note),
      today: day.today,
      hours: h,
      width: focus && h !== null ? Math.min(h / focus, 1.4) / 1.4 : 0,
    };
  });
});

const focusMark = `${(1 / 1.4) * 100}%`;

function openSprint(row) {
  periodKey.value = row.period;
}

async function nudgeStale() {
  nudging.value = true;

  let sent = 0;

  for (const key of staleKeys.value) {
    try {
      await api.post(`/work/issues/${encodeURIComponent(key)}/nudge`, {}, { quiet: true });

      sent += 1;
    } catch (e) {
      console.warn(`Nudge for ${key} not sent: ${e.message}`);
    }
  }

  nudging.value = false;

  showToast(sent ? `Slack DM sent about ${sent} stale issue${sent === 1 ? '' : 's'}` : 'Nothing stale to nudge, or already nudged today');
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Work items" :title="data?.person?.name ?? 'Work items'" :caption="identityCaption(data?.person)">
      <template #actions>
        <Badge v-if="onboarding" text="Onboarding" tone="info" />
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
        <PlKpiRow :columns="4" label="Live sprint">
          <StatTile eyebrow="Committed" :value="num(kpis.issues, 0)" unit="issues" :foot="`${num(kpis.committed)} SP at sprint start`" />
          <StatTile
            eyebrow="Done"
            :value="num(kpis.done)"
            unit="SP"
            :foot="val(kpis.commitment) !== null ? `${pct(kpis.commitment)} of commitment` : 'Nothing committed'"
          />
          <StatTile
            eyebrow="Remaining"
            :value="hours(kpis.remainingHours)"
            :foot="`= ${num(kpis.workDays)} days at ${num(kpis.focus)} h/day`"
          />
          <StatTile
            eyebrow="Sprint elapsed"
            :value="num(kpis.elapsedPct, 0)"
            unit="%"
            :foot="`${num(kpis.workingDaysLeft, 0)} working days left`"
          />
        </PlKpiRow>

        <PlCard :title="data.period.short" subtitle="Synced from Jira">
          <template #actions>
            <button
              v-if="can('team.view', 'all.view')"
              type="button"
              class="pl-btn pl-btn-sm pl-btn-primary"
              :disabled="!staleKeys.length || nudging"
              @click="nudgeStale"
            >
              Nudge on Slack
            </button>
          </template>
          <div class="controls">
            <div class="chips"><PlTabs v-model="filter" :tabs="chips" label="Filter work items" /></div>
            <input v-model="query" class="pl-input is-sm search" type="search" placeholder="Search key or summary" aria-label="Search key or summary" />
            <select v-if="showSource" v-model="source" class="pl-input is-sm" aria-label="Source">
              <option v-for="s in sourceOptions" :key="s.key" :value="s.key">{{ s.label }} ({{ s.count }})</option>
            </select>
            <select v-model="sort" class="pl-input is-sm" aria-label="Sort by">
              <option value="">Sort: default</option>
              <option v-for="s in sorts" :key="s.key" :value="s.key">Sort: {{ s.label }}</option>
            </select>
          </div>
          <PlTable
            :columns="LIVE_COLUMNS"
            :rows="liveRows"
            row-key="key"
            label="Work items of the sprint"
            clickable
            :loading="loading"
            :flagged="(row) => row.flags.length > 0"
            empty-text="No work items match."
            @row-click="drawerKey = $event.key"
          >
            <template #cell-key="{ row }">
              <span class="key-cell">
                <span class="pl-key">{{ row.key }}</span>
                <Badge v-if="showSource" :text="row.source.text" :tone="row.source.tone" />
                <a v-if="row.url" class="tool-link" :href="row.url" target="_blank" rel="noopener noreferrer" @click.stop @keydown.enter.stop>
                  Open in Jira<span class="pl-sr-only"> {{ row.key }}, opens in a new tab</span>
                </a>
              </span>
            </template>
            <template #cell-status="{ row }"><Badge :text="row.status" :tone="statusTone(row.status)" /></template>
            <template #cell-signals="{ row }">
              <span class="badges"><Badge v-for="f in row.flags" :key="f.text" :text="f.text" :tone="f.tone" /></span>
            </template>
          </PlTable>
        </PlCard>

        <PlCard title="Daily load" subtitle="Before today: hours logged · from today: remaining hours spread over capacity" :empty="!daily.length">
          <ul class="daily" aria-label="Daily load">
            <li v-for="d in daily" :key="d.date" class="pl-meter-row">
              <span>{{ d.label }}<span v-if="d.today" class="pl-caption"> · today</span></span>
              <span v-if="d.note" class="pl-caption">{{ d.note }}</span>
              <span v-else class="pl-meter" role="img" :aria-label="`${d.label}: ${num(d.hours)} h ${d.kind}`">
                <span class="pl-meter-fill" :class="{ 'is-info': d.kind === 'planned' }" :style="{ width: `${d.width * 100}%` }" />
                <span class="pl-meter-mark" :style="{ left: focusMark }" />
              </span>
              <span class="pl-mono right">{{ d.note ? '—' : hours(d.hours) }}</span>
            </li>
          </ul>
        </PlCard>
      </template>

      <!-- closed sprint, month or release -->
      <template v-else>
        <PlKpiRow :columns="5" :label="data.period.short">
          <StatTile eyebrow="Committed" :value="num(metrics.com)" unit="SP" :foot="`${num(data.sources?.all ?? data.chips?.all, 0)} issues`" />
          <StatTile
            eyebrow="Done"
            :value="num(metrics.dsp)"
            unit="SP"
            :foot="val(metrics.commit) !== null ? `${pct(metrics.commit)} of commitment` : 'Nothing committed'"
          />
          <StatTile eyebrow="Carried over" :value="num((val(metrics.carry) ?? []).length, 0)" unit="issues" foot="To the next sprint" />
          <StatTile eyebrow="Median cycle time" :value="num(metrics.cyc)" unit="d" foot="Issues done" />
          <StatTile
            v-if="onboarding"
            eyebrow="Utilization"
            value="Training"
            :foot="`${hours(metrics.logged)} logged · not judged while onboarding`"
            badge="Onboarding"
            badge-tone="info"
          />
          <StatTile
            v-else
            eyebrow="Utilization"
            :value="num(data.utilization, 0)"
            unit="%"
            :foot="`${hours(metrics.logged)} logged`"
            :badge="closedBand.label"
            :badge-tone="closedBand.tone"
          />
        </PlKpiRow>

        <PlCard v-if="release" :title="`${release.name} · release ${release.key}`" :subtitle="`${formatDate(release.from)} – ${formatDate(release.releaseDate ?? release.to)}`">
          <div class="facts">
            <div><span class="pl-eyebrow">Sprints</span><b>{{ (release.sprints ?? []).map((s) => s.replace('s:', '')).join(', ') || '—' }}</b></div>
            <div><span class="pl-eyebrow">{{ data.person?.name }} SP done</span><b class="pl-mono">{{ num(release.personDone) }}</b></div>
            <div><span class="pl-eyebrow">Team SP done</span><b class="pl-mono">{{ num(release.teamDone) }}</b></div>
            <div><span class="pl-eyebrow">Share of release</span><b class="pl-mono">{{ pct(release.share) }}</b></div>
          </div>
        </PlCard>

        <PlCard :title="`Sprints in ${data.period.short}`" subtitle="Click a sprint to open it">
          <PlTable
            :columns="SPRINT_COLUMNS"
            :rows="sprintRows"
            row-key="period"
            label="Sprints in the period"
            clickable
            empty-text="No sprint in this period."
            @row-click="openSprint"
          />
        </PlCard>

        <PlCard :title="`Issues · ${data.period.short}`" subtitle="From Jira sprint history · click an issue for its timeline">
          <div class="controls">
            <div class="chips"><PlTabs v-model="filter" :tabs="chips" label="Filter issues" /></div>
            <input v-model="query" class="pl-input is-sm search" type="search" placeholder="Search key or summary" aria-label="Search key or summary" />
            <select v-if="showSource" v-model="source" class="pl-input is-sm" aria-label="Source">
              <option v-for="s in sourceOptions" :key="s.key" :value="s.key">{{ s.label }} ({{ s.count }})</option>
            </select>
            <select v-model="sort" class="pl-input is-sm" aria-label="Sort by">
              <option value="">Sort: default</option>
              <option v-for="s in sorts" :key="s.key" :value="s.key">Sort: {{ s.label }}</option>
            </select>
          </div>
          <PlTable
            :columns="HISTORY_COLUMNS"
            :rows="historyRows"
            row-key="key"
            label="Issues of the period"
            clickable
            :loading="loading"
            :flagged="(row) => row.carried"
            empty-text="No issues match."
            @row-click="drawerKey = $event.key"
          >
            <template #cell-key="{ row }">
              <span class="key-cell">
                <span class="pl-key">{{ row.key }}</span>
                <Badge v-if="showSource" :text="row.source.text" :tone="row.source.tone" />
                <a v-if="row.url" class="tool-link" :href="row.url" target="_blank" rel="noopener noreferrer" @click.stop @keydown.enter.stop>
                  Open in Jira<span class="pl-sr-only"> {{ row.key }}, opens in a new tab</span>
                </a>
              </span>
            </template>
            <template #cell-status="{ row }"><Badge :text="row.status" :tone="statusTone(row.status)" /></template>
          </PlTable>
        </PlCard>
      </template>
    </template>

    <IssueDrawer :issue-key="drawerKey" @close="drawerKey = null" @nudged="load" />
  </div>
</template>

<style scoped>
.controls {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}

.chips {
  max-width: 100%;
  overflow-x: auto;
}

.search {
  width: 220px;
  max-width: 100%;
}

.key-cell {
  display: inline-flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-1);
}

.tool-link {
  font-size: 12px;
  white-space: nowrap;
}

.badges {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.daily {
  list-style: none;
  margin: 0;
  padding: 0;
}

.daily .pl-meter-row {
  grid-template-columns: minmax(70px, 120px) minmax(0, 1fr) 56px;
}

.right {
  text-align: right;
}

.facts {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(140px, 1fr));
  gap: var(--space-3);
}

.facts > div {
  display: grid;
  gap: var(--space-1);
  align-content: start;
}
</style>
