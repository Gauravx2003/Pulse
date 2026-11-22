<template>
  <div class="pl-card culture">
    <div class="layout">
      <div>
        <svg
          class="pl-radar"
          :viewBox="`0 0 ${W} ${H}`"
          :width="W"
          :height="H"
          role="img"
          :aria-label="`Culture values: ${seriesLabel}${comparison ? ` vs ${comparisonLabel}` : ''}`"
        >
          <polygon v-for="ring in 5" :key="`g${ring}`" class="grid" :points="ringPoints(ring)" />
          <line
            v-for="(axis, i) in axes"
            :key="`a${i}`"
            class="axis"
            :x1="fmt(CX)"
            :y1="fmt(CY)"
            :x2="fmt(axis.x)"
            :y2="fmt(axis.y)"
          />
          <polygon class="area" :points="seriesPoints(scores)" />
          <polygon v-if="comparison" class="team" :points="seriesPoints(comparison)" />
          <circle
            v-for="(p, i) in points(scores)"
            :key="`p${i}`"
            class="pt"
            :cx="fmt(p.x)"
            :cy="fmt(p.y)"
            r="3"
          />
          <text
            v-for="(l, i) in labelPositions"
            :key="`t${i}`"
            :x="fmt(l.x)"
            :y="fmt(l.y)"
            :text-anchor="l.anchor"
          >{{ labels[i] }}</text>
        </svg>
        <div class="pl-caption legend">
          <span><span class="key-series"></span> {{ seriesLabel }}</span>
          <span v-if="comparison"><span class="key-comparison"></span> {{ comparisonLabel }}</span>
        </div>
      </div>
      <div>
        <div class="pl-card-head">
          <h3 class="pl-h3">{{ title }}</h3>
          <span v-if="caption" class="pl-caption">{{ caption }}</span>
        </div>
        <div
          v-for="(label, i) in labels"
          :key="label"
          class="pl-value-row value-row"
          role="button"
          tabindex="0"
          :title="weights ? `Weight ${weights[i]}%` : undefined"
          @click="emit('select', i)"
          @keydown.enter.prevent="emit('select', i)"
          @keydown.space.prevent="emit('select', i)"
        >
          <span>{{ label }}</span>
          <template v-if="scores[i] != null">
            <span class="pl-dots"><i v-for="d in 5" :key="d" :class="{ on: d <= Math.round(scores[i]) }"></i></span>
            <span class="pl-mono right">{{ scores[i].toFixed(1) }}</span>
          </template>
          <span v-else class="pl-caption no-input">Not enough input</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup>
import { computed } from 'vue';
import { CULTURE_VALUES } from './tones.js';

/**
 * CultureValues (reference/3-design-system/components/CultureValues): the 11 DO values as a radar
 * (person vs an optional comparison, never more than two series) beside a 1-5 score per value.
 * `scores` and `comparison` are 11 numbers in the fixed order of `CULTURE_VALUES` (tones.js); a null score is
 * one the backend withheld for too few raters and reads "Not enough input". `weights` (percent)
 * show on hover. Selecting a row emits `select` with the value index to open the evidence drawer.
 */
defineProps({
  title: { type: String, required: true },
  caption: { type: String, default: '' },
  scores: { type: Array, required: true, validator: (v) => v.length === CULTURE_VALUES.length },
  comparison: { type: Array, default: null, validator: (v) => v === null || v.length === CULTURE_VALUES.length },
  weights: { type: Array, default: null },
  seriesLabel: { type: String, required: true },
  comparisonLabel: { type: String, default: 'Team average' },
});

const emit = defineEmits(['select']);

// Radar geometry of the reference preview: 480 x 300, radius 126, labels 14 px outside the rim.
const W = 480;
const H = 300;
const CX = 240;
const CY = 150;
const R = 126;
const LABEL_R = R + 14;
const MAX_SCORE = 5;
const labels = CULTURE_VALUES;
const N = labels.length;

function angle(i) {
  return (2 * Math.PI * i) / N;
}

function at(i, r) {
  return { x: CX + r * Math.sin(angle(i)), y: CY - r * Math.cos(angle(i)) };
}

function fmt(v) {
  return v.toFixed(1);
}

function join(pts) {
  return pts.map((p) => `${fmt(p.x)},${fmt(p.y)}`).join(' ');
}

function ringPoints(ring) {
  return join(labels.map((_, i) => at(i, (R * ring) / 5)));
}

function points(values) {
  return values.map((v, i) => at(i, (R * Math.min(Math.max(v ?? 0, 0), MAX_SCORE)) / MAX_SCORE));
}

function seriesPoints(values) {
  return join(points(values));
}

const axes = labels.map((_, i) => at(i, R));

const labelPositions = computed(() =>
  labels.map((_, i) => {
    const p = at(i, LABEL_R);
    const dx = p.x - CX;
    return { x: p.x, y: p.y + 4, anchor: Math.abs(dx) < 1 ? 'middle' : dx > 0 ? 'start' : 'end' };
  }),
);
</script>

<style scoped>
/* Radar beside the scores as in the preview; stacked when the card is narrow (e.g. half a page). */
.culture {
  container-type: inline-size;
}

.layout {
  display: grid;
  grid-template-columns: auto 1fr;
  gap: var(--space-6);
  align-items: center;
}

.layout svg {
  max-width: 100%;
  height: auto;
}

@container (max-width: 820px) {
  .layout {
    grid-template-columns: minmax(0, 1fr);
  }
}

.legend {
  display: flex;
  gap: var(--space-4);
  justify-content: center;
}

.key-series {
  display: inline-block;
  width: 14px;
  height: 8px;
  background: var(--brand);
  border-radius: 2px;
}

.key-comparison {
  display: inline-block;
  width: 14px;
  border-top: 2px dashed var(--ink-muted);
  vertical-align: middle;
}

.value-row {
  cursor: pointer;
}

.value-row:focus-visible {
  outline: 2px solid var(--focus);
  outline-offset: 2px;
}

.right {
  text-align: right;
}

.no-input {
  grid-column: 2 / span 2;
  text-align: right;
}
</style>
