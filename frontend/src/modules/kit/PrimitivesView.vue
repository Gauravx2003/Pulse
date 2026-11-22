<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="UI kit" title="Shared primitives" caption="Task 6.3 · every building block screens share.">
      <template #actions>
        <PlPeriodControl v-model="periodKey" :periods="PERIODS" />
        <button type="button" class="pl-btn pl-btn-primary" data-testid="open-modal" @click="modalOpen = true">
          Open modal
        </button>
      </template>
    </PlPageHeader>

    <PlKpiRow :columns="4">
      <StatTile eyebrow="Utilization" value="92" unit="%" foot="Sprint 43" />
      <StatTile eyebrow="Stale items" value="3" foot="over 3 days" />
      <StatTile eyebrow="Open PRs" value="5" foot="2 waiting review" />
      <StatTile eyebrow="Points" value="14" unit="pts" foot="this month" />
    </PlKpiRow>

    <PlAlert badge="Planning">
      34% of logged time was unplanned. This is a planning signal, not a performance problem.
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm">View sources</button>
      </template>
    </PlAlert>

    <PlCard title="Tabs" subtitle="Arrow keys, Home and End move between tabs">
      <PlTabs v-model="tab" :tabs="TABS" label="Example tabs" panel-id="tab-panel" />
      <p id="tab-panel" class="pl-muted" role="tabpanel" :aria-label="`${tabLabel} panel`" data-testid="tab-panel">
        Showing {{ tabLabel }}
      </p>
    </PlCard>

    <PlCard title="Scrolling table" subtitle="Rows open a drawer on click or Enter">
      <template #actions>
        <button type="button" class="pl-btn pl-btn-sm" @click="confirmOpen = true">Delete all</button>
      </template>
      <PlTable :columns="COLUMNS" :rows="ROWS" label="Example issues" row-key="key" clickable @row-click="openRow">
        <template #cell-key="{ value }"><span class="pl-key">{{ value }}</span></template>
      </PlTable>
    </PlCard>

    <div class="pl-grid two">
      <PlCard title="Empty card" empty empty-text="No visits planned this quarter.">
        <template #empty>
          <button type="button" class="pl-btn pl-btn-primary pl-btn-sm">Plan a visit</button>
        </template>
      </PlCard>
      <PlCard title="Loading card" loading />
    </div>

    <PlCard title="Form fields">
      <form class="pl-form" novalidate @submit.prevent="save">
        <PlField v-model="form.name" label="Full name" required :error="errors.name" />
        <PlField v-model="form.role" label="Role" type="select" required :options="ROLES" />
        <PlField v-model="form.note" label="Note" type="textarea" hint="Optional" />
        <PlFormError :message="errors.form" />
        <div><button type="submit" class="pl-btn pl-btn-primary">Save</button></div>
      </form>
    </PlCard>

    <PlModal :open="modalOpen" title="Check in" @close="modalOpen = false">
      <PlField v-model="checkin" label="Current value" type="number" />
      <template #actions>
        <button type="button" class="pl-btn" @click="modalOpen = false">Cancel</button>
        <button type="button" class="pl-btn pl-btn-primary" @click="saveCheckin">Save check-in</button>
      </template>
    </PlModal>

    <PlDrawer :open="Boolean(row)" :title="row?.summary ?? ''" :eyebrow="row?.key ?? ''" @close="row = null">
      <p>Status: {{ row?.status }}</p>
      <button type="button" class="pl-btn pl-btn-sm">Nudge</button>
    </PlDrawer>

    <PlConfirm
      :open="confirmOpen"
      title="Delete all rows?"
      message="This cannot be undone."
      @cancel="confirmOpen = false"
      @confirm="deleteAll"
    />
  </div>
</template>

<script setup>
import { computed, reactive, ref } from 'vue';
import { StatTile } from '../../kit/index.js';
import {
  PlAlert,
  PlCard,
  PlConfirm,
  PlDrawer,
  PlField,
  PlFormError,
  PlKpiRow,
  PlModal,
  PlPageHeader,
  PlPeriodControl,
  PlTable,
  PlTabs,
  showToast,
} from '../../ui/index.js';

const PERIODS = {
  activeSprint: 's:S43',
  sprints: [
    { key: 's:S43', label: 'Sprint 43 · 21 Sep – 9 Oct (active)', live: true },
    { key: 's:S42', label: 'Sprint 42 · 7 – 18 Sep', live: false },
  ],
  months: [
    { key: 'm:2026-09', label: 'September 2026 (to date)', live: true },
    { key: 'm:2026-08', label: 'August 2026', live: false },
  ],
  releases: [{ key: 'r:8.2', label: 'Release 8.2 · 1 Sep – 30 Sep (in progress)', live: true }],
};

const TABS = [
  { key: 'team', label: 'Team' },
  { key: 'person', label: 'Person' },
  { key: 'history', label: 'History', count: 3 },
];

const COLUMNS = [
  { key: 'key', label: 'Key' },
  { key: 'summary', label: 'Summary' },
  { key: 'status', label: 'Status' },
  { key: 'sp', label: 'SP', num: true },
  { key: 'remaining', label: 'Remaining', num: true },
];

const ROWS = [
  { key: 'PLAT-2198', summary: 'Ingest retry backoff', status: 'In progress', sp: 5, remaining: '24 h' },
  { key: 'PLAT-2201', summary: 'Audit export filters', status: 'To do', sp: 3, remaining: null },
  { key: 'PLAT-2210', summary: 'Session revocation on role change', status: 'Review', sp: 2, remaining: '4 h' },
];

const ROLES = [
  { value: 'Employee', label: 'Employee' },
  { value: 'Manager', label: 'Manager' },
];

const periodKey = ref('s:S43');
const tab = ref('team');
const tabLabel = computed(() => TABS.find((t) => t.key === tab.value).label);
const modalOpen = ref(false);
const confirmOpen = ref(false);
const row = ref(null);
const checkin = ref('');
const form = reactive({ name: '', role: '', note: '' });
const errors = reactive({ name: '', form: '' });

function openRow(r) {
  row.value = r;
}

function save() {
  errors.name = form.name.trim() ? '' : 'Enter a name.';
  errors.form = errors.name ? 'Fix the highlighted field.' : '';

  if (!errors.form) {
    showToast('Saved');
  }
}

function saveCheckin() {
  modalOpen.value = false;
  showToast('Check-in saved');
}

function deleteAll() {
  confirmOpen.value = false;
  showToast('Rows deleted');
}
</script>

<style scoped>
.two {
  grid-template-columns: repeat(2, minmax(0, 1fr));
}

@media (max-width: 860px) {
  .two {
    grid-template-columns: 1fr;
  }
}
</style>
