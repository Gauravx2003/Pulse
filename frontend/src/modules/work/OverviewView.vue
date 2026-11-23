<script setup>
// Overview (reference 7.1 "Screen detail — Overview", spec work/work-items). The live sprint shows the
// overload alert, five KPIs and the issues that need attention; a closed sprint, month or release shows
// the history KPIs, delivery and the sprints of the period, and a release adds its card. Utilization by
// sprint and Bugs & support follow. An onboarding joiner gets the Onboarding badge and utilization shows
// Training. Every number comes from GET /work/people/:id/overview.
import { computed, ref, watch } from 'vue';
import { useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile, UtilizationMeter } from '@/kit/index.js';
import { statusTone } from '@/kit/tones.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable } from '@/ui/index.js';
import IssueDrawer from './IssueDrawer.vue';
import { band, flags, hours, idleText, identityCaption, num, pct, val } from './presentation.js';
import { useViewedPerson } from './useViewedPerson.js';

const ATTENTION_COLUMNS = [
  { key: 'key', label: 'Key' },
  { key: 'summary', label: 'Summary' },
  { key: 'status', label: 'Status' },
  { key: 'sp', label: 'SP', num: true },
  { key: 'remaining', label: 'Remaining', num: true },
  { key: 'due', label: 'Due' },
  { key: 'last', label: 'Last activity' },
  { key: 'signals', label: 'Signals' },
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

const router = useRouter();
const { id: personId, selfId } = useViewedPerson();
const { key: periodKey, periods, kinds } = useSharedPeriod(['sprint', 'month', 'release']);

const data = ref(null);
const loading = ref(false);
const error = ref('');
const drawerKey = ref(null);

let request = 0;

watch([personId, periodKey], load, { immediate: true });

async function load() {
  if (!personId.value || !periodKey.value) {
    return;
  }

  const mine = ++request;

  loading.value = true;
  error.value = '';

  try {
    const body = await api.get(`/work/people/${personId.value}/overview`, { query: { period: periodKey.value } });

    if (mine === request) {
      data.value = body;
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'The overview could not be loaded.';
    }
  } finally {
    if (mine === request) {
      loading.value = false;
    }
  }
}

const person = computed(() => data.value?.person ?? null);
const kpis = computed(() => data.value?.kpis ?? {});
const onboarding = computed(() => !!kpis.value.onboarding);
const utilBand = computed(() => band(kpis.value.band));
const metrics = computed(() => data.value?.metrics ?? {});
const closedBand = computed(() => band(data.value?.utilizationBand));
const release = computed(() => val(data.value?.release));

const indexValue = computed(() => num(data.value?.index?.value, 2));

const indexFoot = computed(() => {
  const now = val(data.value?.index?.value);
  const before = val(data.value?.index?.previous);

  if (now === null || before === null) {
    return 'This quarter';
  }

  const change = now - before;

  return `${change >= 0 ? '+' : '−'}${Math.abs(change).toFixed(2)} vs last quarter`;
});

const alert = computed(() => {
  const a = val(data.value?.alert);

  if (!a) {
    return null;
  }

  if (a.kind === 'onboarding') {
    return { badge: 'Onboarding', tone: 'accent', text: 'On an onboarding plan: utilization is not judged while training.' };
  }

  if (a.kind === 'overloaded') {
    const leave = (a.leave ?? []).length ? ` (leave on ${a.leave.map(formatDate).join(', ')})` : '';
    const move = val(a.suggestMove);
    const suggestion = move ? ` Consider moving ${move.key} (${num(move.storyPoints)} SP, ${hours(move.remainingHours)}).` : '';

    return {
      badge: a.critical ? 'Critical' : 'Over',
      tone: a.critical ? 'danger' : 'accent',
      text: `Overloaded: ${num(a.workDays)} days of estimated work against ${num(a.capacity)} days of capacity left${leave}, ${num(
        a.daysOver,
      )} days over.${suggestion}`,
    };
  }

  return { badge: 'Under', tone: 'accent', text: `Room for more: ${num(a.spareDays)} days of capacity are not planned yet.` };
});

const attentionRows = computed(() =>
  (data.value?.needsAttention ?? []).map((i) => ({
    key: i.key,
    summary: i.summary,
    status: i.status,
    sp: num(i.storyPoints),
    remaining: hours(i.remainingHours),
    due: formatDate(val(i.dueOn)),
    last: idleText(i.idleDays),
    flags: flags(i),
  })),
);

const trend = computed(() => data.value?.utilizationTrend ?? null);
const trendBand = computed(() => band(trend.value?.band));

const trendRows = computed(() =>
  (trend.value?.sprints ?? [])
    .filter((s) => val(s.util) !== null)
    .map((s) => ({ key: s.period, label: `${s.short}${s.live ? ' · forecast' : ''}`, value: val(s.util), tone: band(s.band).tone })),
);

const sprintRows = computed(() =>
  (data.value?.sprints ?? []).map((s) => ({
    period: s.period,
    sprint: s.short,
    issues: num(s.issues, 0),
    committed: num(s.committed),
    done: num(s.done),
    commitment: pct(s.commitment),
    carried: num(s.carried, 0),
    hours: hours(s.logged),
    utilization: pct(s.util),
  })),
);

const bugs = computed(() => data.value?.bugs ?? {});

const isSelf = computed(() => !!personId.value && personId.value === selfId.value);

const issueCount = computed(() => (data.value?.sprints ?? []).reduce((total, s) => total + (val(s.issues) ?? 0), 0));

function openSprint(row) {
  periodKey.value = row.period;
}

function openWorkItems() {
  router.push('/work-items');
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Overview" :title="person?.name ?? 'Overview'" :caption="identityCaption(person)">
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
      <template v-if="data.live">
        <PlAlert v-if="alert" :badge="alert.badge" :tone="alert.tone">{{ alert.text }}</PlAlert>

        <PlKpiRow :columns="5" label="Live sprint">
          <StatTile
            eyebrow="Tasks assigned"
            :value="num(kpis.tasks, 0)"
            :foot="`${num(kpis.storyPoints)} SP · ${num(kpis.done, 0)} done · ${num(kpis.open, 0)} open`"
          />
          <StatTile
            eyebrow="No update > 2 days"
            :value="num(kpis.stale, 0)"
            :foot="val(kpis.oldest) !== null ? `Oldest ${num(kpis.oldest, 0)} d` : 'None stale'"
            :badge="kpis.stale ? 'Stale' : ''"
            badge-tone="accent"
          />
          <StatTile
            eyebrow="Work assigned"
            :value="num(kpis.workDays)"
            unit="d"
            :foot="`${hours(kpis.remainingHours)} ÷ ${num(kpis.focus)} h/day · capacity ${num(kpis.capacity, 0)} d`"
          />
          <StatTile
            v-if="onboarding"
            eyebrow="Utilization"
            value="Training"
            foot="Not judged while onboarding"
            badge="Onboarding"
            badge-tone="info"
          />
          <StatTile
            v-else
            eyebrow="Utilization"
            :value="num(kpis.utilization, 0)"
            unit="%"
            foot="Work assigned vs capacity"
            :badge="utilBand.label"
            :badge-tone="utilBand.tone"
          />
          <StatTile eyebrow="Performance index" :value="indexValue" unit="/ 5" :foot="indexFoot" />
        </PlKpiRow>

        <PlCard title="Needs attention" :subtitle="`Jira · ${data.period.short}`">
          <template #actions>
            <button type="button" class="pl-btn pl-btn-sm" @click="openWorkItems">All work items →</button>
          </template>
          <PlTable
            :columns="ATTENTION_COLUMNS"
            :rows="attentionRows"
            row-key="key"
            label="Issues that need attention"
            clickable
            :flagged="() => true"
            empty-text="Nothing needs attention: every issue is moving."
            @row-click="drawerKey = $event.key"
          >
            <template #cell-key="{ row }"><span class="pl-key">{{ row.key }}</span></template>
            <template #cell-status="{ row }"><Badge :text="row.status" :tone="statusTone(row.status)" /></template>
            <template #cell-signals="{ row }">
              <span class="badges"><Badge v-for="f in row.flags" :key="f.text" :text="f.text" :tone="f.tone" /></span>
            </template>
          </PlTable>
        </PlCard>
      </template>

      <!-- closed sprint, month or release -->
      <template v-else>
        <PlCard v-if="release" :title="`${release.name} · release ${release.key}`" :subtitle="`${formatDate(release.from)} – ${formatDate(release.releaseDate ?? release.to)}`">
          <template #actions>
            <Badge
              :text="release.status === 'Released' ? 'Released' : `Due ${formatDate(release.releaseDate ?? release.to)} · ${num(release.workingDaysLeft, 0)} working days left`"
              :tone="release.status === 'Released' ? 'ok' : 'brand'"
            />
          </template>
          <div class="facts">
            <div><span class="pl-eyebrow">Sprints</span><b>{{ (release.sprints ?? []).map((s) => s.replace('s:', '')).join(', ') || '—' }}</b></div>
            <div><span class="pl-eyebrow">{{ person?.name }} SP done</span><b class="pl-mono">{{ num(release.personDone) }}</b></div>
            <div><span class="pl-eyebrow">Team SP done</span><b class="pl-mono">{{ num(release.teamDone) }}</b></div>
            <div><span class="pl-eyebrow">Share of release</span><b class="pl-mono">{{ pct(release.share) }}</b></div>
          </div>
        </PlCard>

        <PlKpiRow :columns="5" :label="data.period.short">
          <StatTile
            eyebrow="Issues"
            :value="num(issueCount, 0)"
            :foot="`${num((val(metrics.carry) ?? []).length, 0)} carried over`"
          />
          <StatTile
            eyebrow="Story points"
            :value="num(metrics.dsp)"
            :unit="`/ ${num(metrics.com)}`"
            :foot="val(metrics.commit) !== null ? `${pct(metrics.commit)} of commitment` : 'Nothing committed'"
          />
          <StatTile eyebrow="Hours logged" :value="num(metrics.logged)" unit="h" :foot="`${num(metrics.work, 0)} working days`" />
          <StatTile
            eyebrow="Utilization"
            :value="num(data.utilization, 0)"
            unit="%"
            foot="Hours logged vs working days"
            :badge="closedBand.label"
            :badge-tone="closedBand.tone"
          />
          <StatTile eyebrow="Performance index" :value="indexValue" unit="/ 5" :foot="indexFoot" />
        </PlKpiRow>

        <div class="pl-grid two">
          <PlCard :title="`Delivery · ${data.period.short}`">
            <template #actions>
              <button type="button" class="pl-btn pl-btn-sm" @click="openWorkItems">All work items →</button>
            </template>
            <div class="facts">
              <div><span class="pl-eyebrow">Median cycle time</span><b class="pl-mono">{{ num(metrics.cyc) }} d</b></div>
              <div><span class="pl-eyebrow">Estimate accuracy</span><b class="pl-mono">{{ pct(metrics.est) }}</b></div>
              <div><span class="pl-eyebrow">Bugs worked</span><b class="pl-mono">{{ num((val(metrics.bugs) ?? []).length, 0) }}</b></div>
              <div><span class="pl-eyebrow">Issues that went stale</span><b class="pl-mono">{{ num(metrics.stale, 0) }}</b></div>
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
        </div>
      </template>

      <!-- every period -->
      <div class="pl-grid two">
        <PlCard title="Utilization by sprint" :empty="!trendRows.length" empty-text="No finished sprint yet.">
          <template #actions>
            <Badge v-if="trend?.overall != null" :text="`Overall ${num(trend.overall, 0)}% · ${trendBand.label}`" :tone="trendBand.tone" />
          </template>
          <UtilizationMeter v-for="s in trendRows" :key="s.key" :label="s.label" :value="s.value" :band="s.tone || 'ok'" />
        </PlCard>

        <PlCard title="Bugs & support" subtitle="A learning signal, not blame">
          <template #actions>
            <RouterLink class="pl-btn pl-btn-sm" to="/bugs-support">Details →</RouterLink>
          </template>
          <div class="facts">
            <div>
              <span class="pl-eyebrow">Open bugs in {{ isSelf ? 'my' : 'their' }} code</span>
              <b class="pl-mono">{{ num(bugs.open, 0) }}</b>
              <Badge :text="bugs.high ? `${num(bugs.high, 0)} high` : 'No high'" :tone="bugs.high ? 'danger' : 'ok'" />
            </div>
            <div>
              <span class="pl-eyebrow">Others' bugs fixed proactively</span>
              <b class="pl-mono">{{ num(bugs.proactive, 0) }}</b>
              <span class="pl-caption">this quarter</span>
            </div>
            <div>
              <span class="pl-eyebrow">Extra support tickets</span>
              <b class="pl-mono">{{ num(bugs.supM, 0) }}</b>
              <span class="pl-caption">{{ hours(bugs.supH) }} · {{ data.period.short }}</span>
            </div>
          </div>
        </PlCard>
      </div>
    </template>

    <IssueDrawer :issue-key="drawerKey" @close="drawerKey = null" />
  </div>
</template>

<style scoped>
.pl-grid.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 900px) {
  .pl-grid.two {
    grid-template-columns: minmax(0, 1fr);
  }
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

.badges {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}
</style>
