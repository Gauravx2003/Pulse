<script setup>
// Effort (reference 7.4 "Screen detail — Effort", spec work/effort-extra-mile). Managers get Team and
// Person tabs, an Employee the Person tab only. Team: KPIs, people, period totals, who gives unplanned
// work and the extra-mile points to review with Confirm all. Person: the planning signal, KPIs, where the
// time went, the daily log calendar, unplanned and extra work, the period table and culture value
// updates. Every number comes from GET /effort/team and /effort/people/:id; Confirm and Reject call
// the task 11.2 endpoints, which check the version, the permission and separation of duties.
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { StatTile } from '@/kit/index.js';
import { heatClass } from '@/kit/tones.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { setPerson } from '@/shell/person.js';
import { can } from '@/shell/session.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable, PlTabs, showToast } from '@/ui/index.js';
import { hours, identityCaption, num, pct, val } from '../work/presentation.js';
import { useViewedPerson } from '../work/useViewedPerson.js';
import EvidenceModal from './EvidenceModal.vue';
import ExtraItems from './ExtraItems.vue';

const PEOPLE_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'hours', label: 'Hours', num: true },
  { key: 'daysWorked', label: 'Days worked', num: true },
  { key: 'active', label: 'Active / working days', num: true },
  { key: 'codeOnly', label: 'Code-only days', num: true },
  { key: 'share', label: 'Unplanned share', num: true },
  { key: 'items', label: 'Extra items', num: true },
  { key: 'points', label: 'Points', num: true },
];

const PERIOD_COLUMNS = [
  { key: 'short', label: 'Period' },
  { key: 'hours', label: 'Hours', num: true },
  { key: 'daysWorked', label: 'Days worked', num: true },
  { key: 'work', label: 'Work days', num: true },
  { key: 'unplanned', label: 'Unplanned', num: true },
  { key: 'weekend', label: 'Weekend', num: true },
  { key: 'visits', label: 'Visits', num: true },
  { key: 'items', label: 'Extra items', num: true },
  { key: 'points', label: 'Points', num: true },
];

const CULTURE_COLUMNS = [
  { key: 'value', label: 'Value' },
  { key: 'rating', label: '360 score', num: true },
  { key: 'bonus', label: 'Extra-mile bonus', num: true },
  { key: 'score', label: 'Shown score', num: true },
];

const SEGMENTS = [
  { key: 'planned', label: 'Planned sprint work', color: 'var(--brand)' },
  { key: 'unplanned', label: 'Unplanned (mid-sprint, incidents, takeovers)', color: 'var(--accent)' },
  { key: 'untracked', label: 'Untracked (GitHub, no Jira key)', color: 'var(--info)' },
  { key: 'reviews', label: 'Reviews for others', color: 'var(--ok)' },
  { key: 'visitH', label: 'Customer visits (on site + travel)', color: 'var(--border-strong)' },
];

const WEEKDAYS = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const route = useRoute();
const router = useRouter();
const { id: personId, selfId } = useViewedPerson();
const { key: periodKey, periods, kinds } = useSharedPeriod(['sprint', 'month']);

const manager = computed(() => can('team.view', 'all.view'));

const tab = computed({
  get() {
    return manager.value && route.query.tab !== 'person' ? 'team' : 'person';
  },
  set(value) {
    router.replace({ query: { ...route.query, tab: value } });
  },
});

const TABS = [
  { key: 'team', label: 'Team' },
  { key: 'person', label: 'Person' },
];

const team = ref(null);
const person = ref(null);
const error = ref('');
const busy = ref('');
const evidence = ref(null);

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

    if (tab.value === 'team') {
      const body = await api.get('/effort/team', query);

      if (mine === request) {
        team.value = body;
      }
    } else {
      const body = await api.get(`/effort/people/${personId.value}`, query);

      if (mine === request) {
        person.value = body;
      }
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'Effort could not be loaded.';
    }
  }
}

const current = computed(() => (tab.value === 'team' ? team.value : person.value));

const title = computed(() => {
  if (tab.value === 'team') {
    return `Effort · ${team.value?.team ? `${team.value.team} team` : 'Team'}`;
  }

  return person.value?.person?.name ?? 'Effort';
});

const caption = computed(() => {
  const label = current.value?.period?.short ?? '';

  return tab.value === 'team' ? `Jira worklogs + GitHub activity · ${label}` : identityCaption(person.value?.person);
});

// ------------------------------------------------------------------ team

const teamKpis = computed(() => team.value?.kpis ?? {});

const peopleRows = computed(() =>
  (team.value?.people ?? []).map((p) => ({
    id: p.id,
    name: p.name,
    hours: hours(p.logged),
    daysWorked: num(p.daysWorked),
    active: `${num(p.active, 0)} / ${num(p.work, 0)}`,
    codeOnly: num(p.codeOnly, 0),
    share: pct(p.share),
    items: num(p.items, 0),
    points: num(p.points, 2),
  })),
);

const requesters = computed(() => {
  const list = team.value?.requesters ?? [];
  const max = Math.max(1, ...list.map((r) => val(r.hours) ?? 0));

  return list.map((r) => ({ ...r, width: `${((val(r.hours) ?? 0) / max) * 100}%` }));
});

const review = computed(() => team.value?.review ?? []);
const doneToReview = computed(() => review.value.filter((i) => i.isDone));

function openPerson(row) {
  setPerson(row.id);
  tab.value = 'person';
}

// ------------------------------------------------------------------ person

const effort = computed(() => person.value?.effort ?? {});

const self = computed(() => !!personId.value && personId.value === selfId.value);

const segments = computed(() => {
  const total = Math.max(val(effort.value.logged) ?? 0, 1);

  return SEGMENTS.map((s) => ({ ...s, hours: val(effort.value[s.key]) ?? 0, width: `${((val(effort.value[s.key]) ?? 0) / total) * 100}%` }));
});

const calendar = computed(() => {
  const days = val(person.value?.dailyLog?.days) ?? [];

  if (!days.length) {
    return { lead: 0, days: [] };
  }

  const first = new Date(`${days[0].date}T00:00:00Z`).getUTCDay();

  return {
    lead: (first + 6) % 7,
    days: days.map((d) => ({
      ...d,
      day: Number(d.date.slice(8)),
      cls: ['future', 'off', 'prejoin'].includes(d.kind) ? 'is-blank' : d.tone ? heatClass(d.tone) : 'u0',
    })),
  };
});

const periodRows = computed(() =>
  ((tab.value === 'team' ? team.value : person.value)?.periods ?? []).map((p) => ({
    period: p.period,
    short: p.short,
    hours: hours(p.logged),
    daysWorked: num(p.daysWorked),
    work: num(p.work, 0),
    unplanned: hours(p.unplanned),
    weekend: num(p.weekend, 0),
    visits: hours(p.visitHours),
    items: num(p.items, 0),
    points: num(p.points, 2),
  })),
);

const periodTitle = computed(() => (periodKey.value?.startsWith('s:') ? 'Sprint by sprint' : 'Month by month'));

const cultureRows = computed(() =>
  (val(person.value?.culture) ?? []).map((c) => ({
    value: c.value,
    rating: num(c.rating360, 1),
    bonus: `+${num(c.bonus, 2)}`,
    score: num(c.score, 2),
  })),
);

const topSource = computed(() => (effort.value.requesters ?? [])[0]?.by ?? null);

// ------------------------------------------------------------------ decisions

async function decide(item, decision) {
  busy.value = item.id;

  try {
    await api.post(`/effort/items/${item.id}/${decision}`, { version: item.version });

    showToast(decision === 'confirm' ? `Confirmed: +${num(item.points, 2)} pts → ${(item.valuesCredited ?? []).join(', ')}` : 'Not counted');

    refreshNotifications();

    await load();
  } catch (e) {
    console.warn(`${decision} ${item.ref} failed: ${e.message}`);
  } finally {
    busy.value = '';
  }
}

async function confirmAll() {
  busy.value = 'all';

  try {
    const result = await api.post('/effort/items/confirm-all', { ids: doneToReview.value.map((i) => i.id) });

    showToast(`${result.confirmed.length} items confirmed · culture values updated`);

    refreshNotifications();

    await load();
  } catch (e) {
    console.warn(`Confirm all failed: ${e.message}`);
  } finally {
    busy.value = '';
  }
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Effort" :title="title" :caption="caption">
      <template #actions>
        <PlTabs v-if="manager" v-model="tab" :tabs="TABS" label="Effort view" />
        <PlPeriodControl v-model="periodKey" :kinds="kinds" :periods="periods" />
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
      <PlKpiRow :columns="4" label="Team effort">
        <StatTile eyebrow="Team hours logged" :value="num(teamKpis.logged)" unit="h" :foot="`${num(teamKpis.daysWorked)} person-days`" />
        <StatTile eyebrow="Unplanned share" :value="num(teamKpis.share, 0)" unit="%" :foot="`${hours(teamKpis.unplanned)} unplanned or untracked`" />
        <StatTile eyebrow="Extra work items" :value="num(teamKpis.items, 0)" :foot="`${num(teamKpis.doneItems, 0)} done`" />
        <StatTile
          eyebrow="Awaiting your review"
          :value="num(teamKpis.awaiting, 0)"
          foot="Confirm to add points"
          :badge="teamKpis.awaiting ? 'Review' : ''"
          badge-tone="accent"
        />
      </PlKpiRow>

      <PlCard :title="`People · ${team.period.short}`" subtitle="Click a person to open their daily log">
        <PlTable :columns="PEOPLE_COLUMNS" :rows="peopleRows" row-key="id" label="People of the team" clickable empty-text="Nobody to show." @row-click="openPerson" />
      </PlCard>

      <div class="pl-grid two">
        <PlCard :title="periodTitle" subtitle="Team totals">
          <PlTable :columns="PERIOD_COLUMNS" :rows="periodRows" row-key="period" label="Team totals by period" empty-text="No periods yet." />
        </PlCard>

        <PlCard title="Who gives unplanned work" subtitle="Requesters by unplanned hours" :empty="!requesters.length" empty-text="No unplanned work in this period.">
          <ul class="bars">
            <li v-for="r in requesters" :key="r.by">
              <span class="by">{{ r.by }}</span>
              <span class="pl-meter" aria-hidden="true"><span class="pl-meter-fill is-accent" :style="{ width: r.width }" /></span>
              <span class="pl-mono">{{ hours(r.hours) }} · {{ num(r.items, 0) }}</span>
            </li>
          </ul>
        </PlCard>
      </div>

      <PlCard title="Review extra-mile points" subtitle="Confirm to add points and update culture values" :empty="!review.length" empty-text="Nothing to review.">
        <template #actions>
          <button
            v-if="team.canConfirm && doneToReview.length"
            type="button"
            class="pl-btn pl-btn-sm pl-btn-primary"
            :disabled="busy === 'all'"
            @click="confirmAll"
          >
            Confirm all done items
          </button>
        </template>
        <ExtraItems
          :items="review"
          :can-confirm="team.canConfirm"
          show-person
          :busy="busy"
          @open="evidence = $event"
          @confirm="decide($event, 'confirm')"
          @reject="decide($event, 'reject')"
        />
      </PlCard>
    </template>

    <!-- person -->
    <template v-if="tab === 'person' && person">
      <PlAlert v-if="effort.planning" badge="Planning" tone="accent">
        {{ pct(effort.share) }} of logged time was unplanned. This is a planning signal for the manager, not a performance
        problem.<template v-if="topSource"> Top source: {{ topSource }}.</template>
      </PlAlert>

      <PlKpiRow :columns="5" :label="`Effort · ${person.period.short}`">
        <StatTile eyebrow="Hours logged" :value="num(effort.logged)" unit="h" :foot="`Jira worklogs · ${person.period.short}`" />
        <StatTile
          eyebrow="Days worked"
          :value="num(effort.daysWorked)"
          unit="d"
          :foot="`hours ÷ ${num(person.dailyLog?.dayHours)} h · vs ${num(effort.work, 0)} working days`"
        />
        <StatTile
          eyebrow="Active days"
          :value="num(effort.active, 0)"
          :unit="`/ ${num(effort.work, 0)}${effort.weekend ? ` +${effort.weekend} wknd` : ''}`"
          :foot="effort.codeOnly ? 'with no worklog' : 'Worklog or Git activity'"
          :badge="effort.codeOnly ? `${effort.codeOnly} code-only` : 'All logged'"
          :badge-tone="effort.codeOnly ? 'info' : 'ok'"
        />
        <StatTile
          eyebrow="Unplanned share"
          :value="num(effort.share, 0)"
          unit="%"
          :foot="`${hours((val(effort.unplanned) ?? 0) + (val(effort.untracked) ?? 0))} of ${hours(effort.logged)}`"
        />
        <StatTile
          eyebrow="Extra-mile points"
          :value="num(effort.ptsConf, 2)"
          :unit="`+ ${num(effort.ptsPend, 2)} pending`"
          :foot="effort.capped ? 'Monthly cap reached' : 'Within the monthly cap'"
          :badge="effort.capped ? 'Capped' : ''"
          badge-tone="accent"
        />
      </PlKpiRow>

      <div class="pl-grid two">
        <PlCard title="Where the time went" :subtitle="`Jira worklogs + GitHub · ${person.period.short}`">
          <div class="stack" role="img" :aria-label="segments.map((s) => `${s.label} ${num(s.hours)} h`).join(', ')">
            <span v-for="s in segments" :key="s.key" :style="{ width: s.width, background: s.color }" />
          </div>
          <ul class="legend">
            <li v-for="s in segments" :key="s.key">
              <span class="swatch" :style="{ background: s.color }" />
              <span>{{ s.label }}</span>
              <span class="pl-mono">{{ hours(s.hours) }}</span>
            </li>
          </ul>
        </PlCard>

        <PlCard
          :title="`Daily log · ${person.period.short}`"
          subtitle="Verified against Jira worklog timestamps, GitHub commits, leave and holidays"
          :empty="!calendar.days.length"
        >
          <div class="calendar" role="list" aria-label="Daily log">
            <span v-for="w in WEEKDAYS" :key="w" class="pl-caption head" aria-hidden="true">{{ w }}</span>
            <span v-for="n in calendar.lead" :key="`lead-${n}`" aria-hidden="true" />
            <span
              v-for="d in calendar.days"
              :key="d.date"
              role="listitem"
              class="pl-heat-cell day"
              :class="d.cls"
              :title="`${formatDate(d.date)} · ${d.kind}`"
              :aria-label="`${formatDate(d.date)} · ${d.kind}${d.label ? ` · ${d.label}` : ''}`"
            >
              <span class="dn" aria-hidden="true">{{ d.day }}</span>
              <b aria-hidden="true">{{ d.label }}</b>
            </span>
          </div>
          <p class="pl-caption legend-line">Code = commits with no worklog · Hol = holiday · colours show the day against {{ num(person.dailyLog?.dayHours) }} h</p>
        </PlCard>
      </div>

      <PlCard title="Unplanned & extra work" subtitle="Click an item for how it was detected" :empty="!(effort.items ?? []).length" empty-text="No unplanned or extra work in this period.">
        <ExtraItems
          :items="effort.items ?? []"
          :can-confirm="person.canConfirm && !self"
          :busy="busy"
          @open="evidence = $event"
          @confirm="decide($event, 'confirm')"
          @reject="decide($event, 'reject')"
        />
      </PlCard>

      <div class="pl-grid two">
        <PlCard :title="periodTitle">
          <PlTable :columns="PERIOD_COLUMNS" :rows="periodRows" row-key="period" label="Effort by period" empty-text="No periods yet." />
        </PlCard>

        <PlCard title="Culture value updates from extra work" subtitle="This quarter · confirmed items only">
          <template #actions>
            <RouterLink class="pl-btn pl-btn-sm" to="/culture">Open culture values →</RouterLink>
          </template>
          <PlTable :columns="CULTURE_COLUMNS" :rows="cultureRows" row-key="value" label="Culture value updates" empty-text="No confirmed extra work this quarter." />
        </PlCard>
      </div>
    </template>

    <EvidenceModal :item="evidence" @close="evidence = null" />
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
  grid-template-columns: minmax(0, 1.2fr) minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2);
}

.by {
  overflow-wrap: anywhere;
}

.stack {
  display: flex;
  height: 16px;
  border-radius: var(--radius-sm);
  overflow: hidden;
  background: var(--surface-sunken);
  margin-bottom: var(--space-3);
}

.legend li {
  display: grid;
  grid-template-columns: 14px minmax(0, 1fr) auto;
  align-items: center;
  gap: var(--space-2);
}

.swatch {
  width: 14px;
  height: 14px;
  border-radius: 3px;
}

.calendar {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
}

.head {
  text-align: center;
}

.day {
  height: 44px;
  display: flex;
  flex-direction: column;
  gap: 2px;
}

.day.is-blank {
  background: transparent;
  border: 1px dashed var(--border);
  color: var(--ink-muted);
}

.dn {
  font-size: 10px;
}

.legend-line {
  margin: var(--space-2) 0 0;
}
</style>
