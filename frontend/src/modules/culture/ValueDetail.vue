<template>
  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlCard v-else-if="!detail" title="Calculation" loading />
  <PlCard v-else-if="!d" :title="detail.value" empty empty-text="No 360° rating for this value in this quarter." />
  <template v-else>
    <PlCard :title="`How ${detail.value} is calculated`" :subtitle="`Weight ${weight ?? '—'}% · reproduce the score from the numbers below`">
      <div class="cv-formula" role="group" aria-label="Score formula">
        <div>
          <span class="pl-eyebrow">360° rating</span>
          <b>{{ fixed(parts.rating360, 2) }}</b>
          <span class="pl-caption">× {{ 100 - parts.dataShare }}%</span>
        </div>
        <span class="cv-op" aria-hidden="true">+</span>
        <div>
          <span class="pl-eyebrow">Data signals</span>
          <b>{{ fixed(parts.dataScore, 2) }}</b>
          <span class="pl-caption">× {{ parts.dataShare }}%</span>
        </div>
        <span class="cv-op" aria-hidden="true">+</span>
        <div>
          <span class="pl-eyebrow">Extra-mile bonus</span>
          <b>+{{ fixed(parts.bonus, 2) }}</b>
          <span class="pl-caption">max +0.30</span>
        </div>
        <span class="cv-op" aria-hidden="true">=</span>
        <div class="cv-result">
          <span class="pl-eyebrow">Score</span>
          <b>{{ fixed(d.score, 2) }}</b>
          <span class="pl-caption">capped at 5</span>
        </div>
      </div>
      <p class="pl-caption cv-note">{{ formulaNote }}</p>

      <h3 class="pl-h3 cv-h3">1 · 360° rating — {{ fixed(parts.rating360, 2) }}</h3>
      <PlTable :columns="RATER_COLUMNS" :rows="raterRows" row-key="group" label="Rater groups" empty-text="No rater groups for this value." />
      <p class="pl-caption">1–5 behaviour-anchored rating from the last published 360° cycle; “Not observed” answers are left out.</p>

      <template v-if="detail.value === 'Moral & Ethics'">
        <h3 class="pl-h3 cv-h3">Data signals</h3>
        <p class="pl-caption">Moral &amp; Ethics comes only from 360° ratings with written examples — tool data is never turned into an ethics score (360° only).</p>
      </template>
      <template v-else>
        <h3 class="pl-h3 cv-h3">2 · Data signals — {{ parts.dataScore == null ? 'no data this period' : fixed(parts.dataScore, 2) }}</h3>
        <PlTable :columns="PARAM_COLUMNS" :rows="paramRows" row-key="key" label="Data parameters" empty-text="No data parameters.">
          <template #cell-name="{ row }">
            <b>{{ row.name }}</b>
            <div class="pl-caption">{{ row.how }}</div>
          </template>
          <template #cell-source="{ row }">
            <span class="pl-caption">{{ row.sourceLabel ?? row.source }}</span>
            <Badge v-if="row.isSimulated" text="Reported input" tone="info" />
          </template>
          <template #cell-score="{ row }">
            <span v-if="row.score == null" class="pl-caption">{{ row.notApplicable ? 'Not applicable · left out' : 'Left out' }}</span>
            <Badge v-else :text="`${row.score} / 5`" :tone="scoreTone(row.score, detail.tones?.score)" />
          </template>
        </PlTable>
        <p class="pl-caption">
          Each parameter is turned into 1–5 with its bands, then averaged by weight. Parameters with no data or no opportunity this period are left
          out and the other weights re-normalised.
        </p>
      </template>

      <template v-if="d.bonusItems?.length">
        <h3 class="pl-h3 cv-h3">3 · Extra-mile bonus — +{{ fixed(parts.bonus, 2) }}</h3>
        <ul class="cv-list">
          <li v-for="(b, i) in d.bonusItems" :key="b.ref ?? i">
            <span>{{ b.title ?? b.kind ?? 'Extra-mile item' }}</span>
            <span class="pl-caption">+{{ fixed(b.bonus, 2) }}</span>
          </li>
        </ul>
      </template>
    </PlCard>

    <div class="cv-grid">
      <PlCard :title="`${detail.value} · ${weight ?? '—'}%`" :subtitle="anchorCaption">
        <template #actions><Badge :text="`${fixed(d.score)} / 5`" :tone="scoreTone(d.score, detail.tones?.score)" /></template>
        <p v-if="detail.definition?.description" class="cv-note">{{ detail.definition.description }}</p>
        <h3 class="pl-h3 cv-h3">Behaviour anchors</h3>
        <ol class="cv-anchors">
          <li v-for="(a, i) in detail.definition?.anchors ?? []" :key="i" :class="{ 'is-current': detail.currentBand === i + 1 }">
            <b>{{ i + 1 }}</b>
            <span>{{ a }}<span v-if="detail.currentBand === i + 1" class="pl-caption"> ← current</span></span>
          </li>
        </ol>
      </PlCard>

      <PlCard title="Evidence log" subtitle="Kudos and confirmed extra-mile work for this value">
        <template #actions>
          <button type="button" class="pl-btn pl-btn-sm" @click="emit('kudos')">Give kudos</button>
        </template>
        <PlEmpty v-if="!detail.evidence?.length" text="No examples logged for this value yet. Scores without examples are hidden at review time." />
        <div v-for="(e, i) in detail.evidence" :key="i" class="cv-evidence">
          <div class="cv-row">
            <b>{{ e.kind === 'kudos' ? `Kudos · ${e.by}` : `Extra-mile · ${e.source ?? ''}` }}</b>
            <span class="pl-caption">{{ formatDate(e.at) }}</span>
          </div>
          <p>{{ e.text }}</p>
        </div>
      </PlCard>
    </div>
  </template>
</template>

<script setup>
// The calculation of one value (spec culture/culture-values "Traceable detail view"): the formula with real
// numbers, the rater table, the parameter table with source labels and reported-input markers, the
// extra-mile bonus, the behaviour anchors with the current band and the evidence log with Give kudos.
import { computed } from 'vue';
import { Badge } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlEmpty, PlTable } from '@/ui/index.js';
import { bandText, fixed, scoreTone } from './presentation.js';

const props = defineProps({
  detail: { type: Object, default: null },
  weight: { type: Number, default: null },
  teamAverage: { type: Number, default: null },
  error: { type: String, default: '' },
});

const emit = defineEmits(['kudos']);

const RATER_COLUMNS = [
  { key: 'group', label: 'Rater group' },
  { key: 'weight', label: 'Weight', num: true },
  { key: 'average', label: 'Average', num: true },
];

const PARAM_COLUMNS = [
  { key: 'name', label: 'Parameter' },
  { key: 'source', label: 'Source' },
  { key: 'display', label: 'Value' },
  { key: 'bands', label: 'Scoring bands' },
  { key: 'score', label: 'Score', num: true },
  { key: 'weight', label: 'Weight', num: true },
];

const d = computed(() => props.detail?.detail ?? null);
const parts = computed(() => d.value?.parts ?? {});

const raterRows = computed(() =>
  (d.value?.raterGroups ?? []).map((g) => ({ group: g.group, weight: g.weight == null ? '—' : `${g.weight}%`, average: fixed(g.average) })),
);

const paramRows = computed(() =>
  (d.value?.parameters ?? []).map((p) => ({ ...p, bands: bandText(p.bands, p.higherIsBetter, p.unit ?? ''), weight: `${p.weight}%` })),
);

const formulaNote = computed(() => {
  const p = parts.value;

  if (props.detail?.value === 'Moral & Ethics') {
    return 'Moral & Ethics uses the 360° rating and the bonus only.';
  }

  if (p.dataScore == null) {
    return 'No data signals for this period, so the 360° rating counts 100%.';
  }

  const share = p.dataShare / 100;

  return `= ${fixed(p.rating360, 2)} × ${(1 - share).toFixed(2)} + ${fixed(p.dataScore, 2)} × ${share.toFixed(2)} + ${fixed(p.bonus, 2)} = ${fixed(d.value.score, 2)}. The data share is set by HR in Rules & thresholds.`;
});

const anchorCaption = computed(() => {
  const bits = [];

  if (props.teamAverage != null) {
    bits.push(`Team average ${fixed(props.teamAverage)}`);
  }

  bits.push(`360° ${fixed(parts.value.rating360)}`);

  if (parts.value.dataScore != null) {
    bits.push(`data ${fixed(parts.value.dataScore)}`);
  }

  if (parts.value.bonus) {
    bits.push(`bonus +${fixed(parts.value.bonus, 2)}`);
  }

  return bits.join(' · ');
});
</script>
