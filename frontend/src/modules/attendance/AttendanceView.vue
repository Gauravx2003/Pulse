<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Attendance & leave" :title="title" :caption="CAPTION">
      <template #actions>
        <select
          class="pl-input is-sm att-period"
          aria-label="Period"
          :value="periodKey ?? ''"
          :disabled="!attPeriods"
          @change="choosePeriod($event.target.value)"
        >
          <option v-if="!attPeriods" value="">Loading…</option>
          <template v-else>
            <optgroup label="Month">
              <option v-for="m in attPeriods.months" :key="m.key" :value="m.key">
                {{ m.uploaded ? m.label : `${m.label} · not uploaded` }}
              </option>
            </optgroup>
            <optgroup label="Sprint">
              <option v-for="s in attPeriods.sprints" :key="s.key" :value="s.key">
                {{ s.label }}
              </option>
            </optgroup>
            <optgroup label="Quarter">
              <option v-for="q in attPeriods.quarters" :key="q.key" :value="q.key">
                {{ q.label }}
              </option>
            </optgroup>
          </template>
        </select>
        <RouterLink v-if="canUpload" class="pl-btn pl-btn-primary" :to="UPLOAD_LINK">Upload monthly CSV</RouterLink>
      </template>
    </PlPageHeader>

    <PlTabs v-if="teamView" v-model="tab" :tabs="TABS" label="Attendance view" />

    <PlAlert v-if="loadError" badge="Error" tone="danger">
      {{ loadError }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="loadPeriods">Retry</button>
      </template>
    </PlAlert>
    <PlLoading v-else-if="!periodKey" />
    <TeamTab
      v-else-if="tab === 'team'"
      :period-key="periodKey"
      :att-periods="attPeriods"
      :today="today"
      @open-person="openPerson"
    />
    <PersonTab
      v-else
      :period-key="periodKey"
      :person-id="personId"
      :can-upload="canUpload"
      @loaded="personName = $event"
      @open-month="choosePeriod"
    />
  </div>
</template>

<script setup>
// Attendance & leave (reference "pAttendance"; spec time/attendance). Team tab for team.view / all.view,
// Person tab for everyone (the top-bar person, else the signed-in user). The period is Attendance's own
// choice (useAttendancePeriod) and defaults to the last uploaded month from GET /attendance/periods.
// A quarter is kept for this visit only, since the shared period list has no quarters.
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { useAttendancePeriod, loadPeriods as loadSharedPeriods } from '@/shell/period.js';
import { loadPeople, people, personState, setPerson } from '@/shell/person.js';
import { can, session } from '@/shell/session.js';
import { PlAlert, PlLoading, PlPageHeader, PlTabs } from '@/ui/index.js';
import PersonTab from './PersonTab.vue';
import TeamTab from './TeamTab.vue';

const CAPTION =
  'Monthly record from the HR system · office, WFH, on duty and leave · feeds capacity, Discipline (rule h) and Collaboration';
const UPLOAD_LINK = { path: '/imports', query: { type: 'attendance' } };
const TABS = [
  { key: 'team', label: 'Team' },
  { key: 'person', label: 'Person' },
];

const route = useRoute();
const router = useRouter();

const teamView = computed(() => can('team.view', 'all.view'));
const attPeriods = ref(null);
const loadError = ref('');
const today = ref(null);
const personName = ref('');

const canUpload = computed(() => Boolean(attPeriods.value?.canUpload));

const { key: savedKey } = useAttendancePeriod(['month', 'sprint'], () => attPeriods.value?.default ?? null);
const quarter = ref(null);

const periodKey = computed(() => (attPeriods.value ? (quarter.value ?? savedKey.value) : null));

function choosePeriod(key) {
  if (!key) {
    return;
  }

  if (key.startsWith('q:')) {
    quarter.value = key;
  } else {
    quarter.value = null;
    savedKey.value = key;
  }
}

const tab = ref(teamView.value && route.query.tab !== 'person' && !route.query.person ? 'team' : 'person');

watch(tab, (t) => {
  if (route.query.tab !== t) {
    router.replace({ query: { ...route.query, tab: t } });
  }
});

watch(
  () => route.query.person,
  (id) => {
    if (typeof id === 'string' && id) {
      setPerson(id);
      tab.value = 'person';
    }
  },
  { immediate: true },
);

if (typeof route.query.period === 'string') {
  choosePeriod(route.query.period);
}

/** The person shown on the Person tab: the top-bar choice, else me, else the first person in scope. */
const personId = computed(() => personState.id ?? session.me?.user?.personId ?? people.value?.[0]?.id ?? null);

if (!personState.id && !session.me?.user?.personId) {
  loadPeople().catch(() => {});
}

function openPerson(id) {
  setPerson(id);
  tab.value = 'person';
}

const title = computed(() => {
  if (tab.value === 'team') {
    return 'Team attendance';
  }

  if (!personName.value) {
    return 'Attendance';
  }

  return personId.value && personId.value === session.me?.user?.personId
    ? `${personName.value} · my attendance`
    : `${personName.value} · attendance`;
});

async function loadPeriods() {
  loadError.value = '';

  try {
    const [att, shared] = await Promise.all([api.get('/attendance/periods'), loadSharedPeriods()]);
    attPeriods.value = att;
    today.value = shared?.today ?? null;
  } catch (e) {
    loadError.value = e.message || 'Could not load attendance periods.';
  }
}

loadPeriods();
</script>

<style scoped>
.att-period {
  width: 14rem;
  max-width: 100%;
}
</style>
