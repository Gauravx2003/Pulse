<script setup>
// Pull requests (reference 7.3 "Screen detail — Pull requests", spec work/pull-requests): signals for
// 1:1s, not a ranking. Managers get Team and Person tabs, an Employee the Person tab only; the period is
// a sprint, a month or All (the dropdown is hidden for All and the PRs are grouped by month). Team: KPIs,
// people, the period table, checks across the team, the comment mix and the PRs merged below the
// standard. Person: KPIs, the pass share per check, the comments they wrote with rewrites, the period
// table, their PRs with a dot per check and the reviews they gave. Every number and class is the
// server's (GET /pull_requests/team and /people/:id).
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { setPerson } from '@/shell/person.js';
import { can } from '@/shell/session.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable, PlTabs } from '@/ui/index.js';
import { identityCaption, num, pct, val } from '../work/presentation.js';
import { useViewedPerson } from '../work/useViewedPerson.js';
import CheckDots from './CheckDots.vue';
import PrDetailModal from './PrDetailModal.vue';
import PrStandardModal from './PrStandardModal.vue';
import { approvals, classTone, COMMENT_CLASSES } from './presentation.js';

const TEAM_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'prs', label: 'PRs', num: true },
  { key: 'comp', label: 'PR standard', num: true },
  { key: 'merged', label: 'Merged', num: true },
  { key: 'firstReview', label: '1st review', num: true },
  { key: 'reviews', label: 'Reviews', num: true },
  { key: 'stdPct', label: 'Comments OK', num: true },
  { key: 'stamps', label: 'Rubber-stamp', num: true },
];

const TEAM_HISTORY = [
  { key: 'short', label: 'Period' },
  { key: 'prs', label: 'PRs', num: true },
  { key: 'merged', label: 'Merged', num: true },
  { key: 'comp', label: 'PR standard', num: true },
  { key: 'titles', label: 'Titles OK', num: true },
  { key: 'comments', label: 'Comments', num: true },
  { key: 'stdPct', label: 'Comments OK', num: true },
  { key: 'stamps', label: 'Rubber-stamp', num: true },
];

const PERSON_HISTORY = [
  { key: 'short', label: 'Period' },
  { key: 'prs', label: 'PRs', num: true },
  { key: 'merged', label: 'Merged', num: true },
  { key: 'comp', label: 'PR standard', num: true },
  { key: 'titles', label: 'Titles OK', num: true },
  { key: 'size', label: 'Median size', num: true },
  { key: 'reviews', label: 'Reviews', num: true },
  { key: 'stdPct', label: 'Comments OK', num: true },
  { key: 'stamps', label: 'Rubber-stamp', num: true },
];

const PR_COLUMNS = [
  { key: 'pr', label: 'PR' },
  { key: 'title', label: 'Title' },
  { key: 'created', label: 'Created' },
  { key: 'lines', label: 'Lines', num: true },
  { key: 'approvals', label: 'Approvals', num: true },
  { key: 'status', label: 'Status' },
  { key: 'checks', label: 'Standard checks' },
];

const REVIEW_COLUMNS = [
  { key: 'pr', label: 'PR' },
  { key: 'title', label: 'Title' },
  { key: 'vote', label: 'Vote' },
  { key: 'comments', label: 'Comments', num: true },
];

const TABS = [
  { key: 'team', label: 'Team' },
  { key: 'person', label: 'Person' },
];

const route = useRoute();
const router = useRouter();
const { id: personId } = useViewedPerson();
const { key: sharedKey, periods, kinds } = useSharedPeriod(['sprint', 'month']);

const manager = computed(() => can('team.view', 'all.view'));

const tab = computed({
  get() {
    return manager.value && route.query.tab !== 'person' ? 'team' : 'person';
  },
  set(value) {
    router.replace({ query: { ...route.query, tab: value } });
  },
});

// All is this screen's own choice: the shared period carries to other screens, All does not.
const all = ref(false);
const periodKey = computed(() => (all.value ? 'all' : sharedKey.value));

const team = ref(null);
const person = ref(null);
const error = ref('');
const detailId = ref(null);
const standardOpen = ref(false);

let request = 0;

watch([tab, personId, periodKey], load, { immediate: true });

async function load() {
  if (!periodKey.value || (tab.value === 'person' && !personId.value)) {
    return;
  }

  const mine = ++request;

  error.value = '';

  try {
    const query = { query: { period: periodKey.value } };

    const body = tab.value === 'team' ? await api.get('/pull_requests/team', query) : await api.get(`/pull_requests/people/${personId.value}`, query);

    if (mine === request) {
      if (tab.value === 'team') {
        team.value = body;
      } else {
        person.value = body;
      }
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'Pull requests could not be loaded.';
    }
  }
}

const current = computed(() => (tab.value === 'team' ? team.value : person.value));
const standard = computed(() => current.value?.standard ?? null);
const target = computed(() => standard.value?.target ?? 85);
const periodLabel = computed(() => (all.value ? 'All' : current.value?.period?.short ?? ''));

const title = computed(() => {
  if (tab.value === 'team') {
    return `Pull requests · ${team.value?.team ? `${team.value.team} team` : 'Team'}`;
  }

  return person.value?.person?.name ?? 'Pull requests';
});

const caption = computed(() =>
  tab.value === 'team'
    ? `From Microsoft TFS (Azure DevOps Server) and GitHub · checked against the PR standard and the review comment standard · ${periodLabel.value}`
    : identityCaption(person.value?.person),
);

const historyTitle = computed(() => (sharedKey.value?.startsWith('s:') && !all.value ? 'Sprint by sprint' : 'Month by month'));

function compTone(value) {
  const v = val(value);

  return v === null ? '' : v >= target.value ? 'ok' : 'accent';
}

// ------------------------------------------------------------------ team

const teamStats = computed(() => val(team.value?.stats) ?? {});

const teamRows = computed(() =>
  (team.value?.people ?? []).map((p) => ({
    id: p.id,
    name: p.name,
    prs: num(p.prs, 0),
    comp: pct(p.comp),
    merged: num(p.merged, 0),
    firstReview: val(p.firstReview) !== null ? `${num(p.firstReview)} h` : '—',
    reviews: num(p.reviews, 0),
    stdPct: pct(p.stdPct),
    stamps: num(p.stamps, 0),
  })),
);

const teamHistory = computed(() =>
  (team.value?.history ?? []).map((h) => ({
    period: h.period,
    short: h.short,
    prs: num(h.prs, 0),
    merged: num(h.merged, 0),
    comp: pct(h.comp),
    titles: pct(h.titlePct),
    comments: num(h.comments, 0),
    stdPct: pct(h.stdPct),
    stamps: num(h.stamps, 0),
  })),
);

const checkBars = computed(() =>
  (val(teamStats.value.checks) ?? []).map((c) => ({ key: c.k, label: c.l, passed: c.p, judged: c.n, width: c.n ? `${(c.p / c.n) * 100}%` : '0%' })),
);

function mix(classes) {
  const counts = val(classes) ?? {};
  const total = Object.values(counts).reduce((a, n) => a + n, 0);

  return COMMENT_CLASSES.map((c) => ({ ...c, count: counts[c.key] ?? 0, width: total ? `${((counts[c.key] ?? 0) / total) * 100}%` : '0%' }));
}

const teamMix = computed(() => mix(teamStats.value.commentClasses));

const belowRows = computed(() =>
  (team.value?.belowStandard ?? []).map((d) => ({
    id: d.id,
    pr: `${d.pr.number} · ${d.pr.tool}`,
    title: d.pr.title,
    created: formatDate(val(d.pr.openedAt)),
    lines: num(d.pr.linesChanged, 0),
    approvals: approvals(d),
    status: d.pr.status,
    checks: d.checks,
  })),
);

function openPerson(row) {
  setPerson(row.id);
  tab.value = 'person';
}

// ------------------------------------------------------------------ person

const stats = computed(() => val(person.value?.stats) ?? {});
const view = computed(() => val(person.value?.view) ?? {});
const personMix = computed(() => mix(view.value.comments?.classes));
const rewrites = computed(() => (view.value.comments?.items ?? []).filter((c) => val(c.rewrite)).slice(0, 6));

function prRow(p) {
  return {
    id: p.id,
    pr: `${p.number} · ${p.tool}`,
    title: p.title,
    suggested: val(p.suggestedTitle),
    created: formatDate(val(p.openedAt)),
    lines: num(p.linesChanged, 0),
    approvals: num(p.approvals, 0),
    status: p.status,
    checks: p.checks,
  };
}

const prGroups = computed(() => {
  if (all.value && person.value?.groups) {
    return person.value.groups.map((g) => ({ label: g.month, rows: g.prs.map(prRow) }));
  }

  return [{ label: '', rows: (view.value.prs ?? []).map(prRow) }];
});

const personHistory = computed(() =>
  (person.value?.history ?? []).map((h) => ({
    period: h.period,
    short: h.short,
    prs: num(h.prs, 0),
    merged: num(h.merged, 0),
    comp: pct(h.comp),
    titles: num(h.titles, 0),
    size: num(h.size, 0),
    reviews: num(h.reviews, 0),
    stdPct: pct(h.stdPct),
    stamps: num(h.stamps, 0),
  })),
);

const reviewRows = computed(() =>
  (view.value.reviews ?? []).map((r) => ({ id: r.id, pr: `${r.number} · ${r.tool}`, title: r.title, vote: r.vote ?? '—', comments: num(r.comments, 0) })),
);

const prCount = computed(() => (view.value.prs ?? []).length);
</script>

<template>
  <div class="pl-page">
    <PlPageHeader :eyebrow="tab === 'team' ? 'Manager view' : 'Pull requests'" :title="title" :caption="caption">
      <template #actions>
        <PlTabs v-if="manager" v-model="tab" :tabs="TABS" label="Pull requests view" />
        <PlPeriodControl v-if="!all" v-model="sharedKey" :kinds="kinds" :periods="periods" />
        <button type="button" class="pl-btn pl-btn-sm" :aria-pressed="all" @click="all = !all">{{ all ? 'Back to periods' : 'All' }}</button>
        <button type="button" class="pl-btn pl-btn-sm" @click="standardOpen = true">PR standard</button>
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>

    <PlLoading v-if="!current && !error" />

    <!-- team -->
    <template v-if="tab === 'team' && team">
      <PlKpiRow :columns="5" label="Team pull requests">
        <StatTile eyebrow="PRs raised" :value="num(teamStats.prs, 0)" :foot="`${num(teamStats.merged, 0)} merged`" />
        <StatTile
          eyebrow="PR standard"
          :value="num(teamStats.comp, 0)"
          unit="%"
          :foot="`Target ${target}%`"
          :badge="val(teamStats.comp) !== null ? (teamStats.comp >= target ? 'On target' : 'Below target') : ''"
          :badge-tone="compTone(teamStats.comp)"
        />
        <StatTile eyebrow="Title standard" :value="num(teamStats.titlePct, 0)" unit="%" :foot="`${num(teamStats.titles, 0)} of ${num(teamStats.prs, 0)} titles`" />
        <StatTile eyebrow="Review comments" :value="num(teamStats.stdPct, 0)" unit="%" :foot="`to standard · ${num(teamStats.comments, 0)} comments`" />
        <StatTile eyebrow="Rubber-stamp approvals" :value="num(teamStats.stamps, 0)" foot="Big PRs, no comments" />
      </PlKpiRow>

      <PlCard :title="`People · ${periodLabel}`" subtitle="Signals for 1:1s, not a ranking · click a person to open them">
        <PlTable :columns="TEAM_COLUMNS" :rows="teamRows" row-key="id" label="People and their pull requests" clickable empty-text="No pull requests in this period." @row-click="openPerson" />
      </PlCard>

      <PlCard :title="historyTitle">
        <PlTable :columns="TEAM_HISTORY" :rows="teamHistory" row-key="period" label="Team pull requests by period" empty-text="No periods yet." />
      </PlCard>

      <div class="pl-grid two">
        <PlCard title="Checks across the team" subtitle="Which parts of the standard are missed most" :empty="!checkBars.length" empty-text="No PRs judged yet.">
          <ul class="bars">
            <li v-for="c in checkBars" :key="c.key">
              <span>{{ c.label }}</span>
              <span class="pl-meter" aria-hidden="true"><span class="pl-meter-fill" :style="{ width: c.width }" /></span>
              <span class="pl-mono">{{ c.passed }} / {{ c.judged }}</span>
            </li>
          </ul>
        </PlCard>

        <PlCard title="Review comments across the team" :empty="!teamMix.some((c) => c.count)" empty-text="No review comments yet.">
          <div class="stack" role="img" :aria-label="teamMix.map((c) => `${c.key} ${c.count}`).join(', ')">
            <span v-for="c in teamMix" :key="c.key" :style="{ width: c.width, background: c.color }" />
          </div>
          <ul class="legend">
            <li v-for="c in teamMix" :key="c.key"><Badge :text="c.key" :tone="c.tone" /><span class="pl-mono">{{ c.count }}</span></li>
          </ul>
        </PlCard>
      </div>

      <PlCard title="Merged without meeting the standard" subtitle="Lowest first · click a PR for its checks" :empty="!belowRows.length" empty-text="Every merged PR met the standard.">
        <PlTable :columns="PR_COLUMNS" :rows="belowRows" row-key="id" label="PRs merged below the standard" clickable @row-click="detailId = $event.id">
          <template #cell-checks="{ row }"><CheckDots :checks="row.checks" /></template>
        </PlTable>
      </PlCard>
    </template>

    <!-- person -->
    <template v-if="tab === 'person' && person">
      <PlKpiRow :columns="5" label="Pull requests of the person">
        <StatTile eyebrow="PRs raised" :value="num(prCount, 0)" :foot="`${num(stats.merged, 0)} merged · ${num(stats.active, 0)} in review`" />
        <StatTile
          eyebrow="PR standard"
          :value="num(stats.comp, 0)"
          unit="%"
          :foot="`Target ${target}%`"
          :badge="val(stats.comp) !== null ? (stats.comp >= target ? 'On target' : 'Below target') : ''"
          :badge-tone="compTone(stats.comp)"
        />
        <StatTile eyebrow="Title standard" :value="num(stats.title, 0)" :unit="`/ ${num(prCount, 0)}`" :foot="`${num(prCount - (val(stats.title) ?? 0), 0)} to fix`" />
        <StatTile eyebrow="Median size" :value="num(stats.size, 0)" unit="lines" :foot="`${num(stats.big, 0)} over ${num(standard?.maxLines, 0)}`" />
        <StatTile
          eyebrow="Review comments"
          :value="num((val(stats.cm) ?? []).length, 0)"
          :foot="`on ${num((val(stats.rv) ?? []).length, 0)} PRs · ${num(stats.stamps, 0)} rubber-stamps`"
        />
      </PlKpiRow>

      <div class="pl-grid two">
        <PlCard :title="`Standard checks · ${periodLabel}`" subtitle="Share of their PRs passing each check · in-review checks are left out" :empty="!(view.checkShare ?? []).length" empty-text="No PRs in this period.">
          <ul class="bars">
            <li v-for="c in view.checkShare ?? []" :key="c.k">
              <span>{{ c.l }}</span>
              <span class="pl-meter" aria-hidden="true"><span class="pl-meter-fill" :style="{ width: `${val(c.pct) ?? 0}%` }" /></span>
              <span class="pl-mono">{{ pct(c.pct) }}</span>
            </li>
          </ul>
        </PlCard>

        <PlCard :title="`Review comments ${person.person?.name?.split(' ')[0] ?? ''} wrote`" :empty="!personMix.some((c) => c.count)" empty-text="No review comments in this period.">
          <template #actions>
            <button type="button" class="pl-btn pl-btn-sm" @click="standardOpen = true">Comment standard</button>
          </template>
          <ul class="legend">
            <li v-for="c in personMix" :key="c.key"><Badge :text="c.key" :tone="c.tone" /><span class="pl-mono">{{ c.count }}</span></li>
          </ul>
          <div v-if="rewrites.length" class="rewrites">
            <div class="pl-eyebrow">Rewrite to the standard</div>
            <div v-for="(c, i) in rewrites" :key="i" class="rewrite">
              <div><Badge :text="c.class" :tone="classTone(c.class)" /> <span class="pl-mono">{{ c.number }}</span> “{{ c.body }}”</div>
              <div class="pl-caption">Try: {{ c.rewrite }}</div>
            </div>
          </div>
        </PlCard>
      </div>

      <PlCard v-if="!all" :title="historyTitle">
        <PlTable :columns="PERSON_HISTORY" :rows="personHistory" row-key="period" label="Pull requests by period" empty-text="No periods yet." />
      </PlCard>

      <PlCard :title="`Pull requests · ${periodLabel}`" subtitle="Click a PR for its checks, reviewers and comments" :empty="!prCount" empty-text="No pull requests in this period.">
        <div v-for="g in prGroups" :key="g.label" class="group">
          <div v-if="g.label" class="pl-eyebrow">{{ g.label }}</div>
          <PlTable :columns="PR_COLUMNS" :rows="g.rows" row-key="id" :label="`Pull requests ${g.label}`" clickable @row-click="detailId = $event.id">
            <template #cell-title="{ row }">
              <div>{{ row.title }}</div>
              <div v-if="row.suggested" class="pl-caption">Try: <span class="pl-mono">{{ row.suggested }}</span></div>
            </template>
            <template #cell-checks="{ row }"><CheckDots :checks="row.checks" /></template>
          </PlTable>
        </div>
      </PlCard>

      <PlCard :title="`Reviews given · ${periodLabel}`" subtitle="Counts toward Collaboration" :empty="!reviewRows.length" empty-text="No reviews given in this period.">
        <PlTable :columns="REVIEW_COLUMNS" :rows="reviewRows" row-key="id" label="Reviews given" clickable @row-click="detailId = $event.id" />
      </PlCard>
    </template>

    <PrDetailModal :pr-id="detailId" @close="detailId = null" />
    <PrStandardModal :open="standardOpen" :standard="standard" @close="standardOpen = false" />
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

.bars,
.legend {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.bars li {
  display: grid;
  grid-template-columns: minmax(0, 1.4fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2);
}

.legend li {
  display: flex;
  justify-content: space-between;
  align-items: center;
  gap: var(--space-2);
}

.stack {
  display: flex;
  height: 16px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-sunken);
  margin-bottom: var(--space-3);
}

.rewrites {
  display: grid;
  gap: var(--space-2);
  margin-top: var(--space-3);
}

.rewrite {
  overflow-wrap: anywhere;
}

.group {
  display: grid;
  gap: var(--space-2);
  margin-bottom: var(--space-3);
}
</style>
