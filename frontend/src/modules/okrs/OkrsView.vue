<script setup>
// OKRs (reference 9.1 "Screen detail — OKRs", spec growth/okrs): Person (My OKRs) and Team tabs, a quarter
// dropdown with counts, Import CSV and Export CSV (Data imports), New objective; four KPIs; one card per
// objective with its key results, Check-in, Edit and Delete. Progress, status and the OKR pillar come from
// the server (GET /okrs/people/:id/objectives and /okrs/teams/:team/objectives); it also checks who may
// change what (okr.own for one's own, okr.team for visible people and Team OKRs).
import { computed, onMounted, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { loadPeople, people } from '@/shell/person.js';
import { loadPeriods } from '@/shell/period.js';
import { can, session } from '@/shell/session.js';
import { PlAlert, PlCard, PlConfirm, PlEmpty, PlKpiRow, PlLoading, PlPageHeader, PlTabs, showToast } from '@/ui/index.js';
import { num, val } from '../work/presentation.js';
import { useViewedPerson } from '../work/useViewedPerson.js';
import CheckInModal from './CheckInModal.vue';
import ObjectiveModal from './ObjectiveModal.vue';
import { STATUS_TONES, quarterLabel, quarterOf, quartersAround } from './presentation.js';

const TABS = [
  { key: 'person', label: 'My OKRs' },
  { key: 'team', label: 'Team' },
];

const route = useRoute();
const router = useRouter();
const { id: personId, selfId } = useViewedPerson();

const tab = computed({
  get() {
    return route.query.tab === 'team' ? 'team' : 'person';
  },
  set(value) {
    router.replace({ query: { ...route.query, tab: value } });
  },
});

const today = ref(null);
const quarter = ref(null);
const data = ref(null);
const error = ref('');
const editing = ref(null);
const creating = ref(false);
const checkingIn = ref(null);
const deleting = ref(null);
const deleteBusy = ref(false);
const exporting = ref(false);

onMounted(async () => {
  loadPeople().catch((e) => console.error('GET /people failed', e));

  try {
    const periods = await loadPeriods();

    today.value = periods.today;
  } catch (e) {
    console.error('GET /periods failed', e);
  }
});

watch(today, (t) => {
  if (t && !quarter.value) {
    quarter.value = quarterOf(t);
  }
});

const viewed = computed(() => (people.value ?? []).find((p) => p.id === personId.value) ?? null);
const team = computed(() => viewed.value?.department ?? (people.value ?? []).find((p) => p.id === selfId.value)?.department ?? null);
const self = computed(() => !!personId.value && personId.value === selfId.value);

let request = 0;

watch([tab, personId, quarter, team], load, { immediate: true });

async function load() {
  if (!quarter.value || (tab.value === 'person' && !personId.value) || (tab.value === 'team' && !team.value)) {
    return;
  }

  const mine = ++request;

  error.value = '';

  try {
    const path = tab.value === 'team' ? `/okrs/teams/${encodeURIComponent(team.value)}/objectives` : `/okrs/people/${personId.value}/objectives`;
    const body = await api.get(path, { query: { quarter: quarter.value } });

    if (mine === request) {
      data.value = body;
    }
  } catch (e) {
    if (mine === request) {
      error.value = e.message || 'OKRs could not be loaded.';
    }
  }
}

const counts = computed(() => Object.fromEntries((data.value?.quarters ?? []).map((q) => [q.quarter, q.count])));

const quarterOptions = computed(() => {
  const keys = new Set(quartersAround(today.value ? quarterOf(today.value) : quarter.value));

  (data.value?.quarters ?? []).forEach((q) => keys.add(q.quarter));

  return [...keys].sort().reverse();
});

const title = computed(() => {
  if (tab.value === 'team') {
    return `${team.value ?? 'Team'} team OKRs`;
  }

  return self.value ? 'My OKRs' : `${viewed.value?.name ?? 'Person'} · OKRs`;
});

const summary = computed(() => data.value?.summary ?? {});
const items = computed(() => data.value?.items ?? []);

function ownerName(o) {
  if (o.level === 'Team') {
    return `${o.team} team`;
  }

  return (people.value ?? []).find((p) => p.id === o.ownerId)?.name ?? (o.ownerId === selfId.value ? session.me?.user?.name : '—');
}

function canChange(o) {
  if (o.level === 'Team') {
    return can('okr.team');
  }

  return o.ownerId === selfId.value ? can('okr.own', 'okr.team') : can('okr.team');
}

const canCreate = computed(() => (tab.value === 'team' ? can('okr.team') : self.value ? can('okr.own', 'okr.team') : can('okr.team')));

function pctOf(progress) {
  const p = val(progress);

  return p === null ? '—' : `${Math.round(p * 100)}%`;
}

function meterWidth(progress) {
  return `${Math.min(Math.max(val(progress) ?? 0, 0), 1) * 100}%`;
}

const pillarFoot = computed(() => {
  const p = val(summary.value.pillar);

  return p === null ? 'No individual OKRs this quarter' : `${quarterLabel(quarter.value)} · ${num(summary.value.avgProgress, 2)} progress ≈ ${num(p, 1)}`;
});

const deleteMessage = computed(() => {
  const o = deleting.value;

  if (!o) {
    return '';
  }

  const n = (o.keyResults ?? []).length;

  return `“${o.title}” and its ${n} key result${n === 1 ? '' : 's'} will be deleted. This cannot be undone.`;
});

async function confirmDelete() {
  deleteBusy.value = true;

  try {
    await api.delete(`/okrs/objectives/${deleting.value.id}`);

    showToast('Objective deleted');

    deleting.value = null;

    await load();
  } catch (e) {
    console.warn(`Delete failed: ${e.message}`);
  } finally {
    deleteBusy.value = false;
  }
}

async function exportCsv() {
  exporting.value = true;

  try {
    const res = await fetch(`/api/v1/imports/okrs/export?quarter=${encodeURIComponent(quarter.value)}`, { credentials: 'same-origin' });

    if (!res.ok) {
      showToast(res.status === 404 ? 'OKR export is not available yet.' : 'Export failed. Try again.');
      return;
    }

    const url = URL.createObjectURL(await res.blob());
    const a = document.createElement('a');

    a.href = url;
    a.download = `okrs_${quarter.value}.csv`;
    a.click();
    URL.revokeObjectURL(url);
  } finally {
    exporting.value = false;
  }
}

function saved() {
  creating.value = false;
  editing.value = null;
  checkingIn.value = null;
  load();
}
</script>

<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="Objectives & key results" :title="title" caption="Progress = average of key-result progress · 0.7 = on track · feeds the Goals / OKRs pillar">
      <template #actions>
        <PlTabs v-model="tab" :tabs="TABS" label="OKR view" />
        <select v-model="quarter" class="pl-input is-sm" aria-label="Quarter">
          <option v-for="q in quarterOptions" :key="q" :value="q">{{ quarterLabel(q) }}{{ counts[q] ? ` (${counts[q]})` : '' }}</option>
        </select>
        <RouterLink class="pl-btn pl-btn-sm" :to="{ path: '/imports', query: { type: 'okrs' } }">Import CSV</RouterLink>
        <button type="button" class="pl-btn pl-btn-sm" :disabled="exporting || !quarter" @click="exportCsv">Export CSV</button>
        <button v-if="canCreate" type="button" class="pl-btn pl-btn-sm pl-btn-primary" @click="creating = true">New objective</button>
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
      <PlKpiRow :columns="4" label="OKRs this quarter">
        <StatTile eyebrow="Objectives" :value="num(summary.objectives, 0)" :foot="quarterLabel(quarter)" />
        <StatTile
          eyebrow="Avg progress"
          :value="pctOf(summary.avgProgress)"
          foot="Average of the objectives"
          :badge="val(summary.status) ?? ''"
          :badge-tone="STATUS_TONES[val(summary.status)] ?? ''"
        />
        <StatTile eyebrow="Key results" :value="num(summary.keyResults, 0)" :foot="`${num(summary.achieved, 0)} achieved`" />
        <StatTile eyebrow="OKR pillar" :value="num(summary.pillar, 1)" unit="/ 5" :foot="pillarFoot" />
      </PlKpiRow>

      <PlEmpty v-if="!items.length" :text="`No objectives for ${quarterLabel(quarter)} yet.`">
        <button v-if="canCreate" type="button" class="pl-btn pl-btn-primary" @click="creating = true">Create the first one</button>
      </PlEmpty>

      <PlCard v-for="o in items" :key="o.id" :title="o.title" :subtitle="`Owner ${ownerName(o)} · ${quarterLabel(o.quarter)}`">
        <template #actions>
          <span class="badges">
            <Badge :text="o.level" tone="brand" />
            <Badge :text="o.status" :tone="STATUS_TONES[o.status] ?? ''" />
            <Badge v-if="o.cultureValue" :text="o.cultureValue" tone="info" />
            <Badge v-if="o.epicKey" :text="o.epicKey" />
          </span>
          <span class="pl-mono big">{{ pctOf(o.progress) }}</span>
          <template v-if="canChange(o)">
            <button type="button" class="pl-btn pl-btn-sm" @click="editing = o">Edit</button>
            <button type="button" class="pl-btn pl-btn-sm pl-btn-danger" @click="deleting = o">Delete</button>
          </template>
        </template>
        <ul class="krs">
          <li v-for="k in o.keyResults" :key="k.id" class="kr">
            <span class="desc">{{ k.description }}</span>
            <span class="pl-meter" role="img" :aria-label="`${k.description}: ${pctOf(k.progress)}`">
              <span class="pl-meter-fill" :class="STATUS_TONES[k.status] && STATUS_TONES[k.status] !== 'ok' ? `is-${STATUS_TONES[k.status]}` : ''" :style="{ width: meterWidth(k.progress) }" />
            </span>
            <span class="pl-mono value">{{ num(k.currentValue) }} / {{ num(k.targetValue) }} {{ k.unit }}</span>
            <button v-if="canChange(o)" type="button" class="pl-btn pl-btn-sm" :aria-label="`Check-in: ${k.description}`" @click="checkingIn = k">Check-in</button>
          </li>
        </ul>
      </PlCard>
    </template>

    <ObjectiveModal
      :open="creating || !!editing"
      :objective="editing"
      :quarters="quarterOptions"
      :default-quarter="quarter ?? ''"
      :default-level="tab === 'team' ? 'Team' : 'Individual'"
      :team="team ?? ''"
      :owner-id="self ? '' : personId ?? ''"
      :people="people ?? []"
      @close="creating = false; editing = null"
      @saved="saved"
    />
    <CheckInModal :key-result="checkingIn" @close="checkingIn = null" @saved="saved" />
    <PlConfirm
      :open="!!deleting"
      title="Delete objective?"
      :message="deleteMessage"
      confirm-label="Delete"
      :busy="deleteBusy"
      @cancel="deleting = null"
      @confirm="confirmDelete"
    />
  </div>
</template>

<style scoped>
.badges {
  display: inline-flex;
  flex-wrap: wrap;
  gap: var(--space-1);
}

.big {
  font-size: 24px;
  font-weight: 600;
}

.krs {
  list-style: none;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-3);
}

.kr {
  display: grid;
  grid-template-columns: minmax(0, 2fr) minmax(80px, 1fr) auto auto;
  align-items: center;
  gap: var(--space-3);
}

@media (max-width: 700px) {
  .kr {
    grid-template-columns: minmax(0, 1fr) auto;
  }

  .kr .desc {
    grid-column: 1 / -1;
  }
}

.desc {
  overflow-wrap: anywhere;
}

.value {
  white-space: nowrap;
}
</style>
