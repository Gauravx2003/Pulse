<template>
  <div class="rv-actions">
    <PlField v-if="choices.length > 1" v-model="personId" type="select" label="Person" :options="choices" />
    <button type="button" class="pl-btn" :disabled="!review?.index" @click="summaryOpen = true">Review summary</button>
    <Badge v-if="review?.submission" text="Submitted for calibration" tone="ok" />
    <button v-else-if="review?.canSubmit" type="button" class="pl-btn pl-btn-primary" @click="submitFor = review.person">
      Submit for calibration
    </button>
  </div>

  <PlAlert v-if="error" badge="Error" tone="danger">
    {{ error }}
    <template #action>
      <button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button>
    </template>
  </PlAlert>
  <PlCard v-else-if="ready && !personId" title="Performance review" empty empty-text="There is nobody whose performance review you can see." />
  <PlLoading v-else-if="!review" />
  <template v-else>
    <p v-if="review.submission" class="pl-caption" role="status">
      Submitted for calibration by {{ review.submission.submittedBy }} · {{ formatDateTime(review.submission.submittedAt) }}
    </p>

    <div class="rv-grid">
      <PlCard title="Performance index" :subtitle="review.label">
        <template v-if="review.index != null">
          <div class="rv-index">{{ review.index.toFixed(2) }}<small>/ 5</small></div>
          <p class="pl-caption">
            Weighted by HR rules · <RouterLink v-if="canRules" to="/rules">change weights in Rules &amp; thresholds</RouterLink>
            <template v-else>weights are set in Rules &amp; thresholds</template>
          </p>
          <p class="pl-caption">Proposed rating: <b>{{ review.proposed }}</b> · an input to calibration, not the rating</p>
        </template>
        <PlEmpty v-else text="No performance index for this quarter yet." />

        <svg
          v-if="trendPoints.length"
          class="rv-trend"
          :viewBox="`0 0 ${W} ${H}`"
          role="img"
          :aria-label="`Index trend: ${review.trend.map((t) => `${t.label} ${t.index ?? 'no data'}`).join(', ')}`"
        >
          <g v-for="v in [3, 4, 5]" :key="v">
            <line class="rv-trend-grid" x1="30" :x2="W - 20" :y1="y(v)" :y2="y(v)" />
            <text x="4" :y="y(v) + 4">{{ v.toFixed(1) }}</text>
          </g>
          <polyline class="rv-trend-line" :points="trendPoints.map((p) => `${p.x},${p.y}`).join(' ')" />
          <g v-for="p in trendPoints" :key="p.label">
            <circle class="rv-trend-dot" :cx="p.x" :cy="p.y" r="4" />
            <text :x="p.x" :y="p.y - 10" text-anchor="middle">{{ p.value.toFixed(1) }}</text>
          </g>
          <text v-for="(t, i) in review.trend" :key="t.period" :x="x(i)" :y="H - 8" text-anchor="middle">{{ t.label }}</text>
        </svg>
      </PlCard>

      <PlCard title="Breakdown" :subtitle="review.label">
        <div v-for="p in review.pillars" :key="p.key" class="rv-pillar">
          <div class="rv-row">
            <span><b>{{ p.label }}</b> <span class="pl-caption">{{ p.weight }}%</span></span>
            <span class="rv-mono">{{ p.score == null ? '—' : p.score.toFixed(1) }}</span>
          </div>
          <div
            class="rv-bar"
            role="meter"
            :aria-label="`${p.label} score`"
            aria-valuemin="0"
            aria-valuemax="5"
            :aria-valuenow="p.score ?? 0"
          >
            <span :class="{ 'is-low': p.tone === 'accent' }" :style="{ width: `${Math.round(((p.score ?? 0) / 5) * 100)}%` }" />
          </div>
          <p class="pl-caption">{{ p.inputs }}</p>
        </div>
      </PlCard>
    </div>
  </template>

  <PlCard v-if="canCalibrate" title="Calibration" :subtitle="calibration ? `${calibration.label} · your team · the index proposes, you decide` : ''">
    <template v-if="unsubmitted" #actions>
      <button type="button" class="pl-btn pl-btn-sm" @click="teamOpen = true">Submit all ({{ unsubmitted }})</button>
    </template>
    <PlAlert v-if="calError" badge="Error" tone="danger">{{ calError }}</PlAlert>
    <PlTable
      v-else
      :columns="CAL_COLUMNS"
      :rows="calRows"
      row-key="id"
      label="Calibration"
      :loading="!calibration"
      clickable
      empty-text="Nobody to calibrate."
      @row-click="(row) => (personId = row.id)"
    >
      <template #cell-proposed="{ row }">
        <Badge v-if="row.proposed" :text="row.proposed" :tone="TONES[row.proposed]" />
        <span v-else class="pl-caption">No index</span>
      </template>
      <template #cell-status="{ row }">
        <Badge v-if="row.submission" text="Submitted" tone="ok" />
        <span v-else class="pl-caption">Not submitted</span>
      </template>
    </PlTable>
  </PlCard>

  <PlModal :open="summaryOpen" :title="`Review summary · ${review?.person?.name ?? ''}`" @close="summaryOpen = false">
    <template v-if="review">
      <p class="pl-caption">{{ summaryHeading }}</p>
      <table class="pl-table">
        <caption class="pl-sr-only">Review summary</caption>
        <thead>
          <tr>
            <th scope="col">Pillar</th>
            <th scope="col" class="num">Weight</th>
            <th scope="col" class="num">Score</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in review.pillars" :key="p.key">
            <td>{{ p.label }}</td>
            <td class="num">{{ p.weight }}%</td>
            <td class="num">{{ p.score == null ? '—' : p.score.toFixed(1) }}</td>
          </tr>
          <tr>
            <td><b>Performance index</b></td>
            <td />
            <td class="num"><b>{{ review.index?.toFixed(2) ?? '—' }}</b></td>
          </tr>
        </tbody>
      </table>
    </template>
    <template #actions>
      <button type="button" class="pl-btn" @click="summaryOpen = false">Close</button>
      <button type="button" class="pl-btn pl-btn-primary" @click="copySummary">Copy summary</button>
    </template>
  </PlModal>

  <PlConfirm
    :open="!!submitFor"
    title="Submit for calibration?"
    :message="`${submitFor?.name ?? ''}'s review goes to calibration with the index as it is now. This is recorded in the audit log.`"
    confirm-label="Submit for calibration"
    :busy="busy"
    @cancel="submitFor = null"
    @confirm="submitOne"
  />
  <PlConfirm
    :open="teamOpen"
    title="Submit the team for calibration?"
    :message="`${unsubmitted} review${unsubmitted === 1 ? '' : 's'} go to calibration with the index as it is now. People without an index are skipped.`"
    confirm-label="Submit all"
    :busy="busy"
    @cancel="teamOpen = false"
    @confirm="submitTeam"
  />
</template>

<script setup>
// Performance review (reference pReviews; spec culture/reviews-performance): the index with its pillar
// breakdown and 4-quarter trend (GET /reviews/performance/people/:id), the calibration table for
// reviews.calibrate holders (GET /reviews/performance/calibration), the review summary with Copy
// summary, and submit for calibration (never yourself; the server refuses it).
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { loadPeople, people } from '@/shell/person.js';
import { can, session } from '@/shell/session.js';
import { formatDateTime } from '@/ui/format.js';
import { PlAlert, PlCard, PlConfirm, PlEmpty, PlField, PlLoading, PlModal, PlTable, showToast } from '@/ui/index.js';

const W = 520;
const H = 180;
const TONES = { Exceeds: 'ok', Meets: 'brand', Developing: 'accent' };
const CAL_COLUMNS = [
  { key: 'name', label: 'Person' },
  { key: 'level', label: 'Level' },
  { key: 'delivery', label: 'Delivery', num: true },
  { key: 'quality', label: 'Quality', num: true },
  { key: 'culture', label: 'Culture', num: true },
  { key: 'okr', label: 'OKR', num: true },
  { key: 'growth', label: 'Growth', num: true },
  { key: 'index', label: 'Index', num: true },
  { key: 'proposed', label: 'Proposed' },
  { key: 'status', label: 'Status' },
];

const canCalibrate = can('reviews.calibrate');
const canRules = can('rules.manage');
const personId = ref('');
const review = ref(null);
const calibration = ref(null);
const error = ref('');
const calError = ref('');
const ready = ref(false);
const summaryOpen = ref(false);
const submitFor = ref(null);
const teamOpen = ref(false);
const busy = ref(false);

loadPeople()
  .then((rows) => {
    personId.value = session.me?.user?.personId ?? rows?.[0]?.id ?? '';
  })
  .catch(() => {
    personId.value = session.me?.user?.personId ?? '';
  })
  .finally(() => {
    ready.value = true;
  });

const choices = computed(() => (people.value ?? []).map((p) => ({ value: p.id, label: p.name })));

const one = (v) => (v == null ? '—' : v.toFixed(1));

const calRows = computed(() =>
  (calibration.value?.people ?? []).map((r) => ({
    ...r,
    level: r.level ?? '—',
    delivery: one(r.pillars.delivery),
    quality: one(r.pillars.quality),
    culture: one(r.pillars.culture),
    okr: one(r.pillars.okr),
    growth: one(r.pillars.growth),
    index: r.index == null ? '—' : r.index.toFixed(2),
  })),
);

const unsubmitted = computed(() => (calibration.value?.people ?? []).filter((r) => !r.submission && r.index != null).length);

const summaryHeading = computed(() => {
  const p = review.value?.person ?? {};

  return [review.value?.label, p.title, p.level].filter(Boolean).join(' · ');
});

function x(i) {
  return 40 + (i * (W - 80)) / 3;
}

function y(v) {
  return H - 30 - ((v - 2.5) / 2.5) * (H - 50);
}

const trendPoints = computed(() =>
  (review.value?.trend ?? [])
    .map((t, i) => ({ label: t.label, value: t.index, x: x(i), y: t.index == null ? 0 : y(Math.max(2.5, t.index)) }))
    .filter((p) => p.value != null),
);

async function load() {
  if (!personId.value) {
    return;
  }

  error.value = '';
  review.value = null;

  try {
    review.value = await api.get(`/reviews/performance/people/${personId.value}`, { quiet: true });
  } catch (e) {
    error.value = e.message || 'Could not load the performance review.';
  }
}

async function loadCalibration() {
  if (!canCalibrate) {
    return;
  }

  calError.value = '';

  try {
    calibration.value = await api.get('/reviews/performance/calibration', { quiet: true });
  } catch (e) {
    calError.value = e.message || 'Could not load the calibration table.';
  }
}

watch(personId, load);
loadCalibration();

async function submitOne() {
  busy.value = true;

  try {
    review.value = await api.post(`/reviews/performance/people/${submitFor.value.id}/submit`, {});
    showToast('Submitted to calibration');
    submitFor.value = null;
    await loadCalibration();
  } catch {
    // the API client showed the error
  } finally {
    busy.value = false;
  }
}

async function submitTeam() {
  busy.value = true;

  try {
    const res = await api.post('/reviews/performance/submit', {});
    showToast(`Submitted ${res.submitted} review${res.submitted === 1 ? '' : 's'} to calibration`);
    teamOpen.value = false;
    await Promise.all([loadCalibration(), load()]);
  } catch {
    // the API client showed the error
  } finally {
    busy.value = false;
  }
}

function summaryText() {
  const r = review.value;
  const lines = [`Review summary · ${r.person?.name ?? ''}`, summaryHeading.value];

  r.pillars.forEach((p) => lines.push(`${p.label} · ${p.weight}% · ${p.score == null ? '—' : p.score.toFixed(1)}`));
  lines.push(`Performance index · ${r.index?.toFixed(2) ?? '—'}`);

  return lines.join('\n');
}

async function copySummary() {
  try {
    await navigator.clipboard.writeText(summaryText());
    showToast('Summary copied');
  } catch {
    showToast('Copy is blocked here — select the table and copy it');
  }
}
</script>
