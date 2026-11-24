<template>
  <div class="pl-page">
    <PlPageHeader :eyebrow="eyebrow" :title="title" :caption="caption">
      <template #actions>
        <template v-if="isApprover">
          <button type="button" class="pl-btn" :disabled="busy" @click="confirmRelease = true">Release letters</button>
          <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="approveAll">Approve all submitted</button>
        </template>
        <template v-else>
          <button type="button" class="pl-btn" :disabled="busy" @click="applyRecommendations">Apply recommendations</button>
          <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="submit">Submit to HR</button>
        </template>
      </template>
    </PlPageHeader>

    <PlAlert v-if="error" badge="Error" tone="danger">
      {{ error }}
      <template #action>
        <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
      </template>
    </PlAlert>
    <PlLoading v-else-if="!data" />
    <template v-else>
      <PlKpiRow :columns="5" label="Appraisal summary">
        <StatTile eyebrow="Annual payroll" :value="inr(k.payroll)" :foot="`${k.people} people`" />
        <StatTile eyebrow="Increment budget" :value="inr(k.budget)" :foot="`${data.rules.budget}% of payroll`" />
        <StatTile
          eyebrow="Proposed spend"
          :value="inr(k.spend)"
          :badge="k.overBudget ? `Over by ${inr(k.spend - k.budget)}` : `${inr(k.budget - k.spend)} left`"
          :badge-tone="k.overBudget ? 'danger' : 'ok'"
        />
        <StatTile eyebrow="Average increment" :value="k.averagePct" unit="%" :foot="`cap ${data.rules.max}% per person`" />
        <StatTile eyebrow="Status" :value="approvedCount" :unit="` / ${k.people}`" :foot="statusFoot" />
      </PlKpiRow>

      <PlAlert v-if="k.overBudget" badge="Budget" tone="danger">
        <b>Proposals exceed the budget by {{ inr(k.spend - k.budget) }}.</b>
        Lower some increments or ask HR for more budget before submitting.
      </PlAlert>
      <PlAlert v-if="exceptions.length" badge="Exceptions" tone="accent">
        <b>{{ exceptions.length }} proposal{{ exceptions.length > 1 ? 's are' : ' is' }} outside the rating band</b>
        ({{ exceptions.map((r) => r.name.split(' ')[0]).join(', ') }}). Each needs a written reason and HR approval.
      </PlAlert>

      <div class="apr-grid">
        <PlCard title="Rating distribution" subtitle="Final appraisal score bands">
          <div v-for="d in distribution" :key="d.band.name" class="apr-bar">
            <span>{{ d.band.name }}</span>
            <span class="apr-meter" aria-hidden="true"><span :style="{ width: `${d.share}%` }" /></span>
            <span class="pl-mono pl-caption">{{ d.n }} · {{ d.band.hi ? `${d.band.lo}–${d.band.hi}%` : '0%' }}</span>
          </div>
          <p class="pl-caption">Guide: no more than about 20% of a team in Outstanding. Calibrate across managers before approval.</p>
        </PlCard>

        <PlCard title="How the increment is decided">
          <template #actions>
            <button v-if="isApprover" type="button" class="pl-btn pl-btn-sm" @click="standardsOpen = true">Edit standards</button>
            <Badge v-else text="Set by HR" />
          </template>
          <p>
            <b>Appraisal score (1–5)</b> = Performance index {{ data.rules.w.perf }}% + 360° feedback {{ data.rules.w.r360 }}% +
            Manager review {{ data.rules.w.mgr }}% + Discipline {{ data.rules.w.disc }}%. Missing parts are left out and the rest
            re-weighted.
          </p>
          <PlTable :columns="bandColumns" :rows="bandRows" row-key="name" label="Score bands">
            <template #cell-name="{ row }"><Badge :text="row.name" :tone="row.tone" /></template>
          </PlTable>
          <p class="pl-caption">
            Position inside the band follows the score, rounded to 0.5%, capped at {{ data.rules.max }}%; pro-rated for
            {{ data.rules.minMonths }}–11 months of service. Effective {{ formatDateWithYear(data.cycle.effectiveOn) }}.
          </p>
        </PlCard>
      </div>

      <PlCard title="People" subtitle="Click a name for evidence">
        <PlTable :columns="columns" :rows="data.people" row-key="personId" label="Appraisal proposals" empty-text="Nobody to appraise.">
          <template #cell-name="{ row }">
            <button type="button" class="apr-person" @click="openEvidence(row)">
              <span class="pl-avatar" aria-hidden="true">{{ initials(row.name) }}</span>
              <span class="apr-person-text">
                <b>{{ row.name }}</b>
                <span class="pl-caption">{{ [row.level, row.title].filter(Boolean).join(' · ') }}</span>
              </span>
            </button>
          </template>
          <template #cell-perf="{ row }">{{ score(part(row, 'perf')) }}</template>
          <template #cell-r360="{ row }">{{ score(part(row, 'r360')) }}</template>
          <template #cell-mgr="{ row }">{{ score(part(row, 'mgr')) }}</template>
          <template #cell-disc="{ row }">{{ score(part(row, 'disc')) }}</template>
          <template #cell-score="{ row }"><b>{{ score(row.score) }}</b></template>
          <template #cell-band="{ row }">
            <Badge :text="row.recommendation.band?.name ?? '—'" :tone="row.recommendation.band?.tone ?? ''" />
            <Badge v-if="row.proposal.promotion" text="Promotion" tone="brand" />
          </template>
          <template #cell-ctc="{ row }">{{ inr(row.ctc) }}</template>
          <template #cell-recommended="{ row }">
            <template v-if="row.recommendation.eligible">
              {{ pct(row.recommendation.pct) }}
              <span v-if="row.recommendation.prorated" class="pl-caption">pro-rated</span>
            </template>
            <span v-else class="pl-caption">{{ row.recommendation.why }}</span>
          </template>
          <template #cell-proposed="{ row }">
            <input
              v-if="canEdit(row)"
              class="pl-input is-sm pl-mono apr-pct"
              type="number"
              min="0"
              :max="data.rules.max"
              step="0.5"
              :value="row.proposal.pct"
              :aria-label="`Proposed increment for ${row.name}`"
              @change="savePct(row, $event.target.value)"
            />
            <span v-else class="pl-mono">{{ pct(row.proposal.pct) }}</span>
            <Badge v-if="row.exception" text="Exception" tone="accent" />
          </template>
          <template #cell-newCtc="{ row }">{{ row.ctc === null ? '—' : inr(row.newCtc) }}</template>
          <template #cell-status="{ row }">
            <span class="apr-actions">
              <Badge :text="row.proposal.status" :tone="statusTone(row.proposal.status)" />
              <button
                v-if="isApprover && row.proposal.status === 'Submitted'"
                type="button"
                class="pl-btn pl-btn-sm"
                :aria-label="`Approve ${row.name}`"
                @click="approve(row)"
              >
                Approve
              </button>
            </span>
          </template>
        </PlTable>
      </PlCard>
    </template>

    <EvidenceModal
      :open="!!evidenceFor"
      :person-id="evidenceFor?.personId ?? ''"
      :rules="data?.rules ?? null"
      :can-propose="canPropose"
      :is-approver="isApprover"
      @close="evidenceFor = null"
      @changed="load"
    />
    <StandardsModal :open="standardsOpen" @close="standardsOpen = false" @saved="onStandardsSaved" />
    <PlConfirm
      :open="confirmRelease"
      title="Release increment letters?"
      :message="`Letters go out for every approved proposal, effective ${data ? formatDateWithYear(data.cycle.effectiveOn) : ''}. Released proposals can no longer change.`"
      confirm-label="Release letters"
      @confirm="release"
      @cancel="confirmRelease = false"
    />
  </div>
</template>

<script setup>
// Appraisal & increments (reference pAppraisal; spec appraisal/appraisal). The table, KPIs and every rule
// value come from GET /appraisal/proposals: the server picks the people (salary scope), computes the
// score and recommendation, freezes them at submission and audits each read. Managers propose (inline
// percent, Apply recommendations, Submit to HR); HR approves, sends back and releases letters.
import { computed, ref } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { can } from '@/shell/session.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { formatDateWithYear } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlKpiRow, PlLoading, PlPageHeader, PlTable, showToast } from '@/ui/index.js';
import EvidenceModal from './EvidenceModal.vue';
import StandardsModal from './StandardsModal.vue';
import { editable, initials, inr, part, pct, score, statusTone } from './presentation.js';
import './appraisal.css';

const isApprover = can('appraisal.approve');
const canPropose = can('appraisal.manage');

const data = ref(null);
const error = ref('');
const busy = ref(false);
const evidenceFor = ref(null);
const standardsOpen = ref(false);
const confirmRelease = ref(false);

async function load() {
  error.value = '';

  try {
    data.value = await api.get('/appraisal/proposals');
  } catch (e) {
    error.value = e.message || 'Could not load the appraisal.';
  }
}

load();

const k = computed(() => data.value.kpis);

const eyebrow = computed(() => `${isApprover ? 'HR · all teams' : 'Manager view · your team'} · confidential`);

const title = computed(() => `Appraisal & increments${data.value ? ` · ${data.value.cycle.year}` : ''}`);

const caption = computed(() => {
  if (!data.value) {
    return 'Visible to managers and HR only';
  }

  const year = data.value.cycle.year;

  return `Review period Oct ${year - 1} – Sep ${year} · effective ${formatDateWithYear(data.value.cycle.effectiveOn)} · visible to managers and HR only`;
});

const approvedCount = computed(() => (k.value.statuses.Approved ?? 0) + (k.value.statuses.Released ?? 0));

const statusFoot = computed(
  () => `approved · ${k.value.statuses.Submitted ?? 0} submitted · ${(k.value.statuses.Draft ?? 0) + (k.value.statuses['Sent back'] ?? 0)} draft`,
);

const exceptions = computed(() => data.value.people.filter((r) => r.exception));

const distribution = computed(() => {
  const bands = data.value.rules.bands ?? [];

  const counts = bands.map((b) => ({ band: b, n: data.value.people.filter((r) => r.score !== null && r.recommendation.band?.name === b.name).length }));

  const max = Math.max(1, ...counts.map((c) => c.n));

  return counts.map((c) => ({ ...c, share: Math.round((c.n / max) * 100) }));
});

const bandColumns = [
  { key: 'range', label: 'Score' },
  { key: 'name', label: 'Band' },
  { key: 'increment', label: 'Increment', num: true },
];

const bandRows = computed(() =>
  (data.value.rules.bands ?? []).map((b, i, all) => ({
    name: b.name,
    tone: b.tone,
    range: `${Number(b.min).toFixed(1)} – ${i === 0 ? '5.0' : (all[i - 1].min - 0.01).toFixed(2)}`,
    increment: b.hi ? `${b.lo}% – ${b.hi}%` : '0% · improvement plan',
  })),
);

const columns = [
  { key: 'name', label: 'Person' },
  { key: 'perf', label: 'Perf.', num: true },
  { key: 'r360', label: '360°', num: true },
  { key: 'mgr', label: 'Manager', num: true },
  { key: 'disc', label: 'Discipline', num: true },
  { key: 'score', label: 'Score', num: true },
  { key: 'band', label: 'Band' },
  { key: 'ctc', label: 'Current CTC', num: true },
  { key: 'recommended', label: 'Recommended', num: true },
  { key: 'proposed', label: 'Proposed %' },
  { key: 'newCtc', label: 'New CTC', num: true },
  { key: 'status', label: 'Status' },
];

function canEdit(row) {
  return canPropose && editable(row) && row.recommendation.eligible && row.ctc !== null;
}

function openEvidence(row) {
  evidenceFor.value = row;
}

async function savePct(row, value) {
  try {
    await api.put(
      `/appraisal/proposals/${row.personId}`,
      { pct: Number(value), reason: row.proposal.reason ?? '', promotion: !!row.proposal.promotion },
      { quiet: true },
    );

    showToast(`Saved ${row.name} · ${pct(value)}`);
  } catch (e) {
    showToast(e.message);

    // Outside the band: the reason is written in the evidence panel.
    if (e.code === 'reason_required') {
      openEvidence(row);
    }
  }

  await load();
}

async function run(call, done) {
  busy.value = true;

  try {
    const res = await call();

    showToast(done(res));

    refreshNotifications();
  } catch (e) {
    showToast(e.message);
  } finally {
    busy.value = false;

    await load();
  }
}

function applyRecommendations() {
  run(() => api.post('/appraisal/proposals/apply', {}, { quiet: true }), (r) => `Recommendations applied to ${r.applied} people`);
}

function submit() {
  run(
    () => api.post('/appraisal/proposals/submit', {}, { quiet: true }),
    (r) => `${r.submitted} proposals submitted to HR${r.noCtc.length ? ` · not submitted (no CTC): ${r.noCtc.join(', ')}` : ''}`,
  );
}

function approve(row) {
  run(() => api.post(`/appraisal/proposals/${row.personId}/approve`, {}, { quiet: true }), () => `Approved · ${row.name}`);
}

function approveAll() {
  run(() => api.post('/appraisal/proposals/approve-all', {}, { quiet: true }), (r) => (r.approved ? `${r.approved} increments approved` : 'Nothing submitted yet'));
}

function release() {
  confirmRelease.value = false;

  run(
    () => api.post('/appraisal/release', {}, { quiet: true }),
    (r) => (r.released ? `${r.released} increment letters released · effective ${formatDateWithYear(r.effectiveOn)}` : 'Approve increments before releasing letters'),
  );
}

function onStandardsSaved() {
  standardsOpen.value = false;

  showToast('Appraisal standards saved');

  load();
}
</script>
