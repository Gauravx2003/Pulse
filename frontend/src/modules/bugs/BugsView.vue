<script setup>
// Bugs & support (reference 7.5 "Screen detail — Bugs & support", spec work/bugs-support): a learning
// signal, never blame. Managers get Team and Person tabs, an Employee the Person tab only. Team: KPIs,
// people, the period table and the disputes to decide (Keep / Remove from their count). Person: KPIs,
// open bugs in their code (Mark resolved, Dispute, Take it), bugs fixed for others, bugs to pick up,
// the period table, support tickets (Resolve) and the support queue (Take ticket). Every rule is the
// server's (GET /bugs/team and /bugs/people/:id and the task 12.1 actions).
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { setPerson } from '@/shell/person.js';
import { can } from '@/shell/session.js';
import { useSharedPeriod } from '@/shell/period.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlPageHeader, PlPeriodControl, PlTable, PlTabs, showToast } from '@/ui/index.js';
import { hours, identityCaption, num, val } from '../work/presentation.js';
import { useViewedPerson } from '../work/useViewedPerson.js';
import DisputeModal from './DisputeModal.vue';
import ResolveTicketModal from './ResolveTicketModal.vue';

const PEOPLE_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'open', label: 'Open bugs own code', num: true },
  { key: 'high', label: 'High', num: true },
  { key: 'q3', label: 'Bugs from code · quarter', num: true },
  { key: 'prod', label: 'Reached production', num: true },
  { key: 'fixed', label: "Others' bugs fixed", num: true },
  { key: 'proactive', label: 'Proactive', num: true },
  { key: 'tickets', label: 'Support tickets', num: true },
  { key: 'hours', label: 'Support hours', num: true },
];

const HISTORY_COLUMNS = [
  { key: 'short', label: 'Period' },
  { key: 'created', label: 'Bugs in own code', num: true },
  { key: 'high', label: 'High', num: true },
  { key: 'prod', label: 'In production', num: true },
  { key: 'fixed', label: 'Fixed for others', num: true },
  { key: 'proactive', label: 'Proactive', num: true },
  { key: 'tickets', label: 'Tickets', num: true },
  { key: 'hours', label: 'Support h', num: true },
];

const TABS = [
  { key: 'team', label: 'Team' },
  { key: 'person', label: 'Person' },
];

const SEVERITY_TONES = { Critical: 'danger', High: 'danger', Medium: 'accent', Low: '' };

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

const team = ref(null);
const person = ref(null);
const error = ref('');
const busy = ref('');
const disputing = ref(null);
const resolving = ref(null);

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

    const body = tab.value === 'team' ? await api.get('/bugs/team', query) : await api.get(`/bugs/people/${personId.value}`, query);

    if (mine === request) {
      if (tab.value === 'team') {
        team.value = body;
      } else {
        person.value = body;
      }
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'Bugs & support could not be loaded.';
    }
  }
}

const current = computed(() => (tab.value === 'team' ? team.value : person.value));
const self = computed(() => !!personId.value && personId.value === selfId.value);

const title = computed(() => {
  if (tab.value === 'team') {
    return `Bugs & support · ${team.value?.team ? `${team.value.team} team` : 'Team'}`;
  }

  return person.value?.person?.name ?? 'Bugs & support';
});

const caption = computed(() =>
  tab.value === 'team'
    ? "Bugs traced to each person's code, proactive fixes and extra support tickets"
    : identityCaption(person.value?.person),
);

const historyRows = computed(() =>
  (current.value?.history ?? []).map((r) => ({
    period: r.period,
    short: r.short,
    created: num(r.created, 0),
    high: num(r.high, 0),
    prod: num(r.prod, 0),
    fixed: num(r.fixed, 0),
    proactive: num(r.proactive, 0),
    tickets: num(r.tickets, 0),
    hours: hours(r.hours),
  })),
);

const historyTitle = computed(() => (periodKey.value?.startsWith('s:') ? 'Sprint by sprint' : 'Month by month'));

// ------------------------------------------------------------------ team

const teamKpis = computed(() => team.value?.kpis ?? {});

const peopleRows = computed(() =>
  (team.value?.people ?? []).map((p) => ({
    id: p.id,
    name: p.name,
    open: num(p.stats?.open, 0),
    high: num(p.stats?.high, 0),
    q3: num(p.stats?.q3, 0),
    prod: num(p.stats?.prod, 0),
    fixed: num(p.stats?.fixedOthers, 0),
    proactive: num(p.stats?.proactive, 0),
    tickets: num(p.stats?.supM, 0),
    hours: hours(p.stats?.supH),
  })),
);

function openPerson(row) {
  setPerson(row.id);
  tab.value = 'person';
}

async function decide(dispute, decision) {
  busy.value = dispute.id;

  try {
    await api.post(`/bugs/disputes/${dispute.id}/decide`, { decision, version: dispute.version });

    showToast(decision === 'keep' ? 'Attribution kept' : `${dispute.bugKey} no longer counted against ${dispute.personName}`);

    await load();
  } catch (e) {
    console.warn(`Dispute decision failed: ${e.message}`);
  } finally {
    busy.value = '';
  }
}

// ------------------------------------------------------------------ person

const stats = computed(() => val(person.value?.stats) ?? {});

function age(bug) {
  const from = new Date(`${bug.reportedOn}T00:00:00Z`);
  const to = bug.resolvedOn ? new Date(`${val(bug.resolvedOn)}T00:00:00Z`) : new Date(`${periods.value?.today ?? bug.reportedOn}T00:00:00Z`);

  return Math.max(0, Math.round((to - from) / 86400000));
}

async function act(path, done, key) {
  busy.value = key;

  try {
    await api.post(path, {});

    showToast(done);

    await load();
  } catch (e) {
    console.warn(`${path} failed: ${e.message}`);
  } finally {
    busy.value = '';
  }
}

function takeIt(bug) {
  act(`/bugs/items/${encodeURIComponent(bug.key)}/take`, `${bug.key} assigned to you in Jira`, bug.key);
}

function pickUp(bug) {
  act(`/bugs/items/${encodeURIComponent(bug.key)}/pick-up`, `${bug.key} picked up — counts as proactive once resolved`, bug.key);
}

function resolveBug(bug) {
  act(`/bugs/items/${encodeURIComponent(bug.key)}/resolve`, `${bug.key} resolved`, bug.key);
}

function takeTicket(ticket) {
  act(`/bugs/tickets/${encodeURIComponent(ticket.key)}/take`, `${ticket.key} is yours — resolve it to earn extra-mile points`, ticket.key);
}

function saved() {
  disputing.value = null;
  resolving.value = null;
  load();
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Bugs & support" :title="title" :caption="caption">
      <template #actions>
        <PlTabs v-if="manager" v-model="tab" :tabs="TABS" label="Bugs & support view" />
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
      <PlKpiRow :columns="4" label="Team bugs and support">
        <StatTile
          eyebrow="Open bugs (team code)"
          :value="num(teamKpis.openBugs, 0)"
          :foot="teamKpis.unassigned ? 'Some nobody is fixing yet' : 'All assigned'"
          :badge="teamKpis.unassigned ? `${teamKpis.unassigned} unassigned` : 'All assigned'"
          :badge-tone="teamKpis.unassigned ? 'accent' : 'ok'"
        />
        <StatTile eyebrow="Proactive fixes" :value="num(teamKpis.proactiveFixes, 0)" foot="This quarter · others' bugs self-assigned" />
        <StatTile eyebrow="Extra support tickets" :value="num(teamKpis.supportTickets, 0)" :foot="`${hours(teamKpis.supportHours)} · ${team.period.short}`" />
        <StatTile eyebrow="Support queue" :value="num(teamKpis.queue, 0)" foot="Unassigned tickets" />
      </PlKpiRow>

      <PlCard title="Disputes to decide" subtitle="Keep the attribution or remove the bug from their count" :empty="!team.disputes.length" empty-text="No dispute waiting.">
        <ul class="rows">
          <li v-for="d in team.disputes" :key="d.id" class="row-item">
            <div class="text">
              <div class="line"><span class="pl-key">{{ d.bugKey }}</span><span>{{ d.bugTitle }}</span></div>
              <div class="pl-caption">{{ d.personName }}: “{{ d.reason }}” · traced to {{ d.originRef || '—' }}</div>
            </div>
            <div class="actions">
              <button type="button" class="pl-btn pl-btn-sm" :disabled="busy === d.id" @click="decide(d, 'keep')">Keep attribution</button>
              <button type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === d.id" @click="decide(d, 'remove')">
                Remove from their count
              </button>
            </div>
          </li>
        </ul>
      </PlCard>

      <PlCard title="People" :subtitle="`${team.subtitle} · click a person to open them`">
        <PlTable :columns="PEOPLE_COLUMNS" :rows="peopleRows" row-key="id" label="People of the team" clickable empty-text="Nobody to show." @row-click="openPerson" />
      </PlCard>

      <PlCard :title="historyTitle" subtitle="Bugs by the date they were created · fixes by the date they were resolved · tickets by date">
        <PlTable :columns="HISTORY_COLUMNS" :rows="historyRows" row-key="period" label="Team bugs by period" empty-text="No periods yet." />
      </PlCard>
    </template>

    <!-- person -->
    <template v-if="tab === 'person' && person">
      <PlKpiRow :columns="5" label="Bugs and support">
        <StatTile
          :eyebrow="`Open bugs in ${self ? 'my' : 'their'} code`"
          :value="num(stats.open, 0)"
          foot="Traced to their PRs"
          :badge="stats.high ? `${stats.high} high severity` : 'None high'"
          :badge-tone="stats.high ? 'danger' : 'ok'"
        />
        <StatTile eyebrow="Bugs from code · quarter" :value="num(stats.q3, 0)" :foot="`${num(stats.prod, 0)} reached production`" />
        <StatTile eyebrow="Others' bugs fixed" :value="num(stats.fixedOthers, 0)" :foot="`${num(stats.proactive, 0)} proactive · self-assigned`" />
        <StatTile eyebrow="Extra support tickets" :value="num(stats.supM, 0)" :foot="`${hours(stats.supH)} · ${person.period.short}`" />
        <StatTile eyebrow="Support CSAT" :value="num(stats.csat, 1)" unit="/ 5" foot="Across rated tickets" />
      </PlKpiRow>

      <PlCard :title="`Open bugs in ${self ? 'my' : `${person.person?.name?.split(' ')[0]}'s`} code`" :subtitle="person.subtitle" :empty="!person.open.length" empty-text="No open bugs traced to this person's code.">
        <ul class="rows">
          <li v-for="b in person.open" :key="b.key" class="row-item">
            <div class="text">
              <div class="line">
                <Badge :text="b.severity" :tone="SEVERITY_TONES[b.severity] ?? ''" />
                <span class="pl-key">{{ b.key }}</span>
                <Badge :text="b.environment" :tone="b.environment === 'Production' ? 'warn' : ''" />
                <Badge v-if="b.openDisputeId" text="Attribution disputed" tone="accent" />
              </div>
              <div>{{ b.title }}</div>
              <div class="pl-caption">
                Traced to {{ b.originRef || '—' }} · found by {{ b.foundBy || '—' }} · {{ formatDate(b.reportedOn) }} · {{ age(b) }} days open · Fixing:
                {{ b.assigneeName ? `${b.assigneeName}${b.assigneeId === personId ? ' (author)' : ''}` : 'unassigned' }}
              </div>
            </div>
            <div v-if="self" class="actions">
              <button v-if="b.assigneeId !== personId" type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === b.key" @click="takeIt(b)">Take it</button>
              <button v-else type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === b.key" @click="resolveBug(b)">Mark resolved</button>
              <button v-if="!b.openDisputeId" type="button" class="pl-btn pl-btn-sm" @click="disputing = b">Dispute</button>
            </div>
          </li>
        </ul>
      </PlCard>

      <div class="pl-grid two">
        <PlCard title="Bugs fixed for others" subtitle="Proactive = self-assigned from the backlog, in someone else's code" :empty="!person.fixedForOthers.length" empty-text="None yet.">
          <ul class="rows">
            <li v-for="b in person.fixedForOthers" :key="b.key" class="row-item">
              <div class="text">
                <div class="line">
                  <Badge :text="b.severity" :tone="SEVERITY_TONES[b.severity] ?? ''" />
                  <span class="pl-key">{{ b.key }}</span>
                  <Badge :text="b.proactive ? 'Proactive' : 'Assigned'" :tone="b.proactive ? 'ok' : ''" />
                  <Badge v-if="b.status !== 'Resolved'" text="In progress" tone="info" />
                </div>
                <div>{{ b.title }}</div>
                <div class="pl-caption">{{ val(b.resolvedOn) ? `Resolved ${formatDate(val(b.resolvedOn))}` : 'Being fixed' }}</div>
              </div>
              <div v-if="self && b.status !== 'Resolved' && b.assigneeId === personId" class="actions">
                <button type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === b.key" @click="resolveBug(b)">Mark resolved</button>
              </div>
            </li>
          </ul>
        </PlCard>

        <PlCard v-if="self" title="Open bugs you could pick up" subtitle="Fixing these counts as proactive work" :empty="!(person.toPickUp ?? []).length" empty-text="Nothing waiting.">
          <ul class="rows">
            <li v-for="b in person.toPickUp ?? []" :key="b.key" class="row-item">
              <div class="text">
                <div class="line"><Badge :text="b.severity" :tone="SEVERITY_TONES[b.severity] ?? ''" /><span class="pl-key">{{ b.key }}</span></div>
                <div>{{ b.title }}</div>
                <div class="pl-caption">
                  {{ b.ownerName ? `Code by ${b.ownerName}` : 'In a teammate’s code' }} ·
                  {{ b.assigneeName ? `with ${b.assigneeName}` : b.assigned ? 'being fixed' : 'unassigned' }}
                </div>
              </div>
              <div class="actions">
                <button type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === b.key" @click="pickUp(b)">Pick up</button>
              </div>
            </li>
          </ul>
        </PlCard>

        <PlCard v-else title="Culture credit">
          <p class="pl-note">
            Proactive fixes credit <b>Problem Solving</b> and <b>Collaboration</b>; extra support tickets credit
            <b>Outcome Ownership</b> and <b>Collaboration</b>, once a manager confirms them in Effort.
          </p>
        </PlCard>
      </div>

      <PlCard :title="historyTitle" subtitle="Bugs by the date they were created · fixes by the date they were resolved · tickets by date">
        <PlTable :columns="HISTORY_COLUMNS" :rows="historyRows" row-key="period" label="Bugs by period" empty-text="No periods yet." />
      </PlCard>

      <div class="pl-grid two">
        <PlCard :title="`Support tickets taken · ${person.period.short}`" :empty="!person.tickets.length" empty-text="No support tickets taken.">
          <ul class="rows">
            <li v-for="t in person.tickets" :key="t.key" class="row-item">
              <div class="text">
                <div class="line">
                  <Badge :text="t.priority" :tone="SEVERITY_TONES[t.priority] ?? ''" />
                  <span class="pl-key">{{ t.key }}</span>
                  <Badge :text="/Self-picked/.test(t.howTaken ?? '') ? 'Self-picked' : 'Assigned'" :tone="/Self-picked/.test(t.howTaken ?? '') ? 'ok' : ''" />
                </div>
                <div>{{ t.title }}</div>
                <div class="pl-caption">{{ t.customer ?? '—' }} · {{ t.howTaken ?? '—' }} · {{ formatDate(t.openedOn) }}</div>
              </div>
              <div class="actions">
                <template v-if="t.status === 'Resolved'">
                  <Badge text="Resolved" tone="ok" />
                  <span class="pl-mono">{{ hours(t.hours) }}</span>
                  <Badge v-if="val(t.csat)" :text="`CSAT ${t.csat}`" tone="brand" />
                </template>
                <button v-else-if="self" type="button" class="pl-btn pl-btn-sm pl-btn-primary" @click="resolving = t">Resolve</button>
                <Badge v-else text="Open" tone="info" />
              </div>
            </li>
          </ul>
        </PlCard>

        <PlCard v-if="self" title="Support queue" subtitle="Unassigned tickets anyone can pick up" :empty="!(person.queue ?? []).length" empty-text="Queue is empty.">
          <ul class="rows">
            <li v-for="t in person.queue ?? []" :key="t.key" class="row-item">
              <div class="text">
                <div class="line"><Badge :text="t.priority" :tone="SEVERITY_TONES[t.priority] ?? ''" /><span class="pl-key">{{ t.key }}</span></div>
                <div>{{ t.title }}</div>
                <div class="pl-caption">{{ t.customer ?? '—' }} · waiting since {{ formatDate(t.openedOn) }}</div>
              </div>
              <div class="actions">
                <button type="button" class="pl-btn pl-btn-sm pl-btn-primary" :disabled="busy === t.key" @click="takeTicket(t)">Take ticket</button>
              </div>
            </li>
          </ul>
        </PlCard>
      </div>
    </template>

    <DisputeModal :bug="disputing" @close="disputing = null" @saved="saved" />
    <ResolveTicketModal :ticket="resolving" @close="resolving = null" @saved="saved" />
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

.rows {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-2);
}

.row-item {
  display: flex;
  flex-wrap: wrap;
  justify-content: space-between;
  gap: var(--space-2);
  padding: var(--space-3);
  border: 1px solid var(--border);
  border-radius: var(--radius-md);
}

.text {
  display: grid;
  gap: var(--space-1);
  flex: 1 1 260px;
  min-width: 0;
  overflow-wrap: anywhere;
}

.line,
.actions {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: var(--space-2);
}

.pl-note {
  margin: 0;
}
</style>
