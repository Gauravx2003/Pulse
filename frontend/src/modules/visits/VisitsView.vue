<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Time & visits" title="Customer visits" caption="Planned and logged visits, reports, customer feedback and verified points · visit days leave capacity">
      <template #actions>
        <PlField v-if="PERIODS.length" v-model="period" type="select" label="Period" :options="PERIODS" />
        <RouterLink v-if="isManager" class="pl-btn" :to="{ path: '/imports', query: { type: 'visits' } }">Import CSV</RouterLink>
        <button type="button" class="pl-btn pl-btn-primary" @click="openPlan">{{ isManager ? 'Plan visit' : 'Log visit' }}</button>
      </template>
    </PlPageHeader>

    <PlTabs v-if="tabs.length > 1" v-model="tab" :tabs="tabs" label="Visits view" />
    <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>

    <!-- Team -->
    <template v-if="tab === 'team'">
      <PlLoading v-if="!team" />
      <template v-else>
        <PlCard title="Team" :subtitle="`Compared with the team average (${averageText}) · new joiners are left out of it`">
          <PlTable :columns="teamColumns" :rows="team.people" row-key="personId" label="Team visits" clickable @row-click="openPerson($event.personId)">
            <template #cell-name="{ row }">
              <b>{{ row.name }}</b>
              <Badge v-if="row.newJoiner" text="New joiner" tone="info" />
            </template>
            <template #cell-visits="{ row }">{{ row.stats.visits ?? 0 }}</template>
            <template #cell-days="{ row }">{{ row.stats.days ?? 0 }}</template>
            <template #cell-csat="{ row }">{{ num(row.stats.csat) }}</template>
            <template #cell-points="{ row }">
              {{ num(row.stats.points) }}
              <span v-if="typeof team.average.points === 'number' && !row.newJoiner" class="pl-caption">
                {{ row.stats.points >= team.average.points ? '≥' : '<' }} avg
              </span>
            </template>
          </PlTable>
        </PlCard>
        <PlCard title="Team visits" subtitle="Click a visit for its detail">
          <VisitList :visits="teamVisits" label="Team visits list" @open="openVisit" />
        </PlCard>
      </template>
    </template>

    <!-- Person -->
    <template v-else-if="tab === 'person'">
      <PlCard v-if="!personId" title="Visits" empty empty-text="You have no visits of your own." />
      <PlLoading v-else-if="!personData" />
      <template v-else>
        <PlKpiRow :columns="4" :label="`Visits · ${personName}`">
          <StatTile eyebrow="Visits" :value="personData.stats.visits ?? 0" :foot="`${personData.stats.days ?? 0} days on site`" />
          <StatTile eyebrow="Hours" :value="num(personData.stats.onsiteHours)" unit=" h" :foot="`${num(personData.stats.travelHours)} h travel`" />
          <StatTile eyebrow="CSAT" :value="num(personData.stats.csat)" :unit="typeof personData.stats.csat === 'number' ? ' / 5' : ''" :foot="`${personData.stats.feedback ?? 0} feedback`" />
          <StatTile eyebrow="Points" :value="num(personData.stats.points)" :foot="`${num(personData.stats.pointsConfirmed)} verified`" />
        </PlKpiRow>
        <PlCard :title="`Visits · ${personName}`">
          <VisitList :visits="personData.visits" :label="`Visits of ${personName}`" :show-person="false" empty-text="No visits in this period." @open="openVisit" />
        </PlCard>
      </template>
    </template>

    <!-- Account owner -->
    <PlCard v-else-if="tab === 'owner'" title="Visits to my accounts" subtitle="Record the customer’s feedback after the engineer’s report">
      <VisitList :visits="owned" label="Visits to my accounts" empty-text="No visits to your accounts." @open="openVisit" />
    </PlCard>

    <VisitDetailModal :open="!!visitId" :visit-id="visitId ?? ''" :owners="owners" @close="visitId = null" @changed="loadAll" />
    <FormModal
      :open="planOpen"
      :title="isManager ? 'Plan a customer visit' : 'Log a customer visit'"
      intro="At most 14 days; it can't overlap another visit or leave of the same person."
      :fields="planFields"
      :initial="planInitial"
      :submit-label="isManager ? 'Plan visit' : 'Log visit'"
      :save="plan"
      @close="planOpen = false"
      @saved="planned"
    />
  </div>
</template>

<script setup>
// Customer visits (reference pVisits; spec time/customer-visits "Views"): Team (managers: everyone with
// or without visits against the team average, new joiners left out), Person (the top-bar person or you)
// and the account owner's view; Plan (manager) or Log (engineer); the detail modal carries every action.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { loadPeriods } from '@/shell/period.js';
import { loadPeople, people, personState } from '@/shell/person.js';
import { can, session } from '@/shell/session.js';
import { PlAlert, PlCard, PlField, PlKpiRow, PlLoading, PlPageHeader, PlTable, PlTabs, showToast } from '@/ui/index.js';
import FormModal from '../training/FormModal.vue';
import VisitDetailModal from './VisitDetailModal.vue';
import VisitList from './VisitList.vue';
import { PURPOSES, num, periods } from './presentation.js';
import './visits.css';

// The quarter and year come from the server's today (pinned in tests), never the browser clock.
const PERIODS = ref([]);

const isManager = can('team.view', 'all.view');
const myPerson = session.me?.user?.personId ?? null;

const period = ref('');
const tab = ref(isManager ? 'team' : 'person');
const error = ref('');
const team = ref(null);
const teamVisits = ref([]);
const personData = ref(null);
const owned = ref([]);
const owners = ref([]);
const visitId = ref(null);
const planOpen = ref(false);
const planInitial = ref({});
const chosenPerson = ref(null);

const tabs = computed(() => [
  ...(isManager ? [{ key: 'team', label: 'Team' }] : []),
  ...(myPerson || isManager ? [{ key: 'person', label: 'Person' }] : []),
  ...(owned.value.length ? [{ key: 'owner', label: 'My accounts' }] : []),
]);

const personId = computed(() => chosenPerson.value ?? personState.id ?? myPerson ?? team.value?.people?.[0]?.personId ?? null);
const personName = computed(
  () => (people.value ?? []).find((p) => p.id === personId.value)?.name ?? team.value?.people.find((p) => p.personId === personId.value)?.name ?? 'you',
);

const averageText = computed(() => {
  const a = team.value?.average ?? {};
  return `${num(a.visits)} visits · ${num(a.points)} points`;
});

const teamColumns = [
  { key: 'name', label: 'Person' },
  { key: 'visits', label: 'Visits', num: true },
  { key: 'days', label: 'Days', num: true },
  { key: 'csat', label: 'CSAT', num: true },
  { key: 'points', label: 'Points', num: true },
];

/** The period as dates for the visit lists. */
function range() {
  const p = PERIODS.value.find((x) => x.value === period.value);

  return p ? { from: p.from, to: p.to } : {};
}

async function loadTeam() {
  if (!isManager || !period.value) return;
  team.value = null;

  try {
    const [t, list] = await Promise.all([api.get('/visits/team', { query: { period: period.value } }), api.get('/visits/items', { query: range() })]);
    team.value = t;
    teamVisits.value = list.items;
  } catch (e) {
    error.value = e.message;
  }
}

async function loadPerson() {
  if (!personId.value || !period.value) return;
  personData.value = null;

  try {
    const [stats, list] = await Promise.all([
      api.get('/visits/stats', { query: { personId: personId.value, period: period.value } }),
      api.get('/visits/items', { query: { personId: personId.value, ...range() } }),
    ]);
    personData.value = { stats, visits: list.items };
  } catch (e) {
    error.value = e.message;
  }
}

async function loadOwned() {
  try {
    owned.value = (await api.get('/visits/items', { query: { owned: 'true', ...range() } })).items;
  } catch {
    owned.value = [];
  }
}

function loadAll() {
  error.value = '';
  loadTeam();
  loadPerson();
  loadOwned();
}

loadPeople().catch(() => {});
api
  .get('/visits/owner-options')
  .then((r) => (owners.value = r.items))
  .catch(() => {});

loadPeriods()
  .then((shared) => {
    PERIODS.value = periods(shared?.today ?? new Date().toISOString().slice(0, 10));
    period.value = PERIODS.value[0].value;
  })
  .catch(() => {
    PERIODS.value = periods(new Date().toISOString().slice(0, 10));
    period.value = PERIODS.value[0].value;
  });

watch(period, loadAll);
watch(personId, loadPerson);
watch(tabs, (list) => {
  if (!list.some((t) => t.key === tab.value)) {
    tab.value = list[0]?.key ?? 'person';
  }
});

function openPerson(id) {
  chosenPerson.value = id;
  tab.value = 'person';
}

function openVisit(row) {
  visitId.value = row.id;
}

const planFields = computed(() => [
  ...(isManager
    ? [{ key: 'personId', label: 'Engineer', type: 'select', required: true, options: (people.value ?? []).map((p) => ({ value: p.id, label: p.name })) }]
    : []),
  { key: 'customer', label: 'Customer', required: true, maxlength: 120 },
  { key: 'city', label: 'City', required: true, maxlength: 80 },
  { key: 'purpose', label: 'Purpose', type: 'select', required: true, options: PURPOSES.map((p) => ({ value: p, label: p })) },
  { key: 'from', label: 'From', type: 'date', required: true },
  { key: 'to', label: 'To', type: 'date', required: true },
  { key: 'requestedBy', label: 'Requested by', required: true, maxlength: 120 },
  { key: 'ownerId', label: 'Account owner', type: 'select', required: true, options: owners.value.map((o) => ({ value: o.id, label: `${o.name}${o.title ? ` · ${o.title}` : ''}` })) },
  { key: 'reference', label: 'CRM deal or ticket', maxlength: 60 },
  { key: 'notes', label: 'Notes', type: 'textarea', maxlength: 1000 },
]);

function openPlan() {
  planInitial.value = { purpose: PURPOSES[0], personId: isManager ? (team.value?.people?.[0]?.personId ?? '') : myPerson };
  planOpen.value = true;
}

function plan(b) {
  return api.post('/visits/items', { ...b, personId: isManager ? b.personId : myPerson }, { quiet: true });
}

function planned() {
  planOpen.value = false;
  showToast(isManager ? 'Visit planned · the engineer is notified' : 'Visit logged');
  refreshNotifications();
  loadAll();
}
</script>
