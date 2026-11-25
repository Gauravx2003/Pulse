<template>
  <div class="pl-page">
    <PlPageHeader eyebrow="People" title="Culture values" :caption="caption">
      <template #actions>
        <PlField v-model="period" type="select" label="Period" :options="periodOptions" />
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>
    <PlCard v-else-if="ready && !personId" title="Culture values" empty empty-text="There is nobody whose culture values you can see." />
    <PlLoading v-else-if="!page" />
    <template v-else>
      <PlCard
        v-if="!page.scored"
        title="Culture values"
        empty
        :empty-text="`No culture scores for ${periodLabel}: there is no published 360° result for this quarter.`"
      />
      <CultureValues
        v-else
        title="DO — our 11 values"
        :caption="`Culture pillar ${fixed(page.pillar, 2)} · click a value to open it`"
        :scores="scores"
        :comparison="teamAverage"
        :weights="weights"
        :series-label="firstName"
        comparison-label="Team average"
        @select="selectValue"
      />

      <DisciplinePanel
        v-if="isDiscipline"
        :discipline="page.discipline"
        :tones="page.tones?.score ?? []"
        :weight="weights?.[index]"
        :can-override="page.canOverride"
        :person-name="page.person?.name ?? ''"
        @override="overrideOpen = true"
        @clear="clearOpen = true"
      />
      <ValueDetail
        v-else-if="page.scored"
        :detail="detail"
        :weight="weights?.[index]"
        :team-average="teamAverage?.[index]"
        :error="detailError"
        @kudos="kudosOpen = true"
      />

      <PlCard title="Calculation rules for all 11 values" subtitle="Every value but Discipline also gets the extra-mile bonus · click a value to open it">
        <PlTable :columns="RULE_COLUMNS" :rows="ruleRows" row-key="value" label="Calculation rules" clickable :loading="!rules" @row-click="(r) => selectValue(r.index)">
          <template #cell-value="{ row }"><b>{{ row.value }}</b></template>
        </PlTable>
      </PlCard>
    </template>

    <FormModal
      :open="overrideOpen"
      :title="`Override Discipline score · ${page?.person?.name ?? ''}`"
      :intro="`Automatic score: ${fixed(page?.discipline?.metrics?.auto ?? page?.discipline?.score, 2)}. Use only when the data misses context (for example a Jira outage or an approved exception).`"
      :fields="[
        { key: 'score', label: 'Score', type: 'select', required: true, options: OVERRIDE_SCORES },
        { key: 'reason', label: 'Reason (visible to the employee)', type: 'textarea', required: true, maxlength: 1000, hint: 'At least 10 characters.' },
      ]"
      :initial="{ score: String(page?.discipline?.override?.score ?? 3), reason: page?.discipline?.override?.reason ?? '' }"
      submit-label="Save override"
      :save="saveOverride"
      @close="overrideOpen = false"
      @saved="done('Discipline override saved', () => (overrideOpen = false))"
    />
    <PlConfirm
      :open="clearOpen"
      title="Use the automatic score?"
      :message="`${page?.person?.name ?? ''}'s Discipline goes back to the score calculated from Jira. This is recorded in the audit log.`"
      confirm-label="Use auto score"
      :busy="busy"
      @cancel="clearOpen = false"
      @confirm="clearOverride"
    />
    <FormModal
      :open="kudosOpen"
      title="Give kudos"
      intro="Kudos are evidence for a value, never a score."
      :fields="[
        { key: 'personId', label: 'To', type: 'select', required: true, options: kudosTargets },
        { key: 'value', label: 'Value', type: 'select', required: true, options: VALUES.map((v) => ({ value: v, label: v })) },
        { key: 'message', label: 'What did they do?', type: 'textarea', required: true, maxlength: 500, hint: 'Be specific — this becomes evidence.' },
      ]"
      :initial="{ personId: kudosDefault, value: VALUES[index] }"
      submit-label="Give kudos"
      :save="giveKudos"
      @close="kudosOpen = false"
      @saved="done('Kudos given', () => (kudosOpen = false))"
    />
  </div>
</template>

<script setup>
// Culture values (reference pCulture; spec culture/culture-values): the 11 values with the radar against the
// team average (GET /culture/people/:id?period=), the calculation detail of the chosen value (GET
// /culture/people/:id/values/:value), Discipline with rules a–h and the override (reviews.calibrate, never
// your own), Give kudos, and the rules table for all values (GET /culture/rules).
import { computed, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { api } from '@/api/client.js';
import { CULTURE_VALUES as VALUES, CultureValues } from '@/kit/index.js';
import { loadPeople, people } from '@/shell/person.js';
import { session } from '@/shell/session.js';
import { PlAlert, PlCard, PlConfirm, PlField, PlLoading, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import FormModal from '../training/FormModal.vue';
// The person picked in the top-bar selector, else yourself (shared with the work screens).
import { useViewedPerson } from '../work/useViewedPerson.js';
import DisciplinePanel from './DisciplinePanel.vue';
import ValueDetail from './ValueDetail.vue';
import { fixed, OVERRIDE_SCORES } from './presentation.js';
import './culture.css';

const RULE_COLUMNS = [
  { key: 'value', label: 'Value' },
  { key: 'weight', label: 'Weight', num: true },
  { key: 'rating', label: '360°', num: true },
  { key: 'data', label: 'Data', num: true },
  { key: 'parameters', label: 'Data parameters (weight within data)' },
];

const route = useRoute();
const router = useRouter();

const { id: personId } = useViewedPerson();
const period = ref('');
const periods = ref([]);
const page = ref(null);
const detail = ref(null);
const detailError = ref('');
const rules = ref(null);
const error = ref('');
const ready = ref(false);
const overrideOpen = ref(false);
const clearOpen = ref(false);
const kudosOpen = ref(false);
const busy = ref(false);

const me = computed(() => session.me?.user?.personId ?? null);

const index = computed(() => {
  const i = VALUES.indexOf(route.query.value);

  return i < 0 ? 0 : i;
});

const isDiscipline = computed(() => VALUES[index.value] === 'Discipline');

const periodOptions = computed(() => periods.value.map((p) => ({ value: p.key, label: p.label })));
const periodLabel = computed(() => periods.value.find((p) => p.key === period.value)?.label ?? '');
const firstName = computed(() => (page.value?.person?.name ?? '').split(' ')[0] || 'Score');
const caption = computed(() => (page.value?.person ? `${page.value.person.name} · ${periodLabel.value}` : 'Behaviour-anchored 360° with data signals'));

const scores = computed(() => (page.value?.values ?? []).map((v) => v.score));
const teamAverage = computed(() => (page.value?.teamAverage?.length === VALUES.length ? page.value.teamAverage : null));
const weights = computed(() => rules.value?.values.map((v) => v.weight) ?? null);

const ruleRows = computed(() =>
  (rules.value?.values ?? []).map((v, i) => ({
    index: i,
    value: v.value,
    weight: `${v.weight}%`,
    rating: `${100 - v.dataShare}%`,
    data: `${v.dataShare}%`,
    parameters:
      v.value === 'Discipline'
        ? 'a Daily update compliance (base) · b Stale tickets · c Re-planning · d Status accuracy · e Time logging · f Early blockers · g Process adherence · h Attendance & leave'
        : v.value === 'Moral & Ethics'
          ? 'None — 360° with examples only'
          : v.parameters.map((p) => `${p.name} ${p.weight}% (${p.source})`).join(' · '),
  })),
);

const kudosTargets = computed(() => (people.value ?? []).filter((p) => p.id !== me.value).map((p) => ({ value: p.id, label: p.name })));
const kudosDefault = computed(() => (personId.value !== me.value ? personId.value : (kudosTargets.value[0]?.value ?? '')));

function selectValue(i) {
  router.replace({ query: { ...route.query, value: VALUES[i] } });
}

async function start() {
  try {
    const [res] = await Promise.all([api.get('/culture/periods'), loadPeople().catch(() => [])]);

    periods.value = res.items;
    period.value = periods.value.some((p) => p.key === route.query.period) ? route.query.period : res.default;
  } catch (e) {
    error.value = e.message || 'Could not load the culture values.';
  } finally {
    ready.value = true;
  }

  api
    .get('/culture/rules')
    .then((r) => (rules.value = r))
    .catch(() => (rules.value = { values: [] }));
}

async function load() {
  if (!personId.value || !period.value) {
    return;
  }

  error.value = '';
  page.value = null;

  try {
    page.value = await api.get(`/culture/people/${personId.value}`, { query: { period: period.value }, quiet: true });
  } catch (e) {
    error.value = e.message || 'Could not load the culture values.';

    return;
  }

  await loadDetail();
}

async function loadDetail() {
  detail.value = null;
  detailError.value = '';

  if (!page.value?.scored || isDiscipline.value) {
    return;
  }

  try {
    detail.value = await api.get(`/culture/people/${personId.value}/values/${encodeURIComponent(VALUES[index.value])}`, {
      query: { period: period.value },
      quiet: true,
    });
  } catch (e) {
    detailError.value = e.message || 'Could not load the calculation.';
  }
}

watch([personId, period], load);
watch(index, loadDetail);

async function saveOverride(values) {
  const res = await api.put(
    `/culture/people/${personId.value}/discipline-override`,
    { score: Number(values.score), reason: values.reason },
    { quiet: true },
  );

  page.value = { ...page.value, discipline: res };
}

async function clearOverride() {
  busy.value = true;

  try {
    await api.delete(`/culture/people/${personId.value}/discipline-override`);
    showToast('Discipline uses the automatic score again');
    clearOpen.value = false;
    await load();
  } catch {
    // the API client showed the error
  } finally {
    busy.value = false;
  }
}

async function giveKudos(values) {
  await api.post('/reviews/kudos', { personId: values.personId, value: values.value, message: values.message }, { quiet: true });
}

async function done(message, close) {
  showToast(message);
  close();
  await load();
}

start();
</script>
