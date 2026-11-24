<template>
  <div>
    <p class="pl-caption">
      {{ results.responded }} responses · groups under {{ results.threshold }} are merged or hidden so nobody can be identified
      <Badge v-if="results.preliminary" text="Preliminary" tone="accent" />
    </p>
    <ul class="rv-groups">
      <li v-for="(g, key) in results.groups" :key="key">
        <b>{{ groupLabel(key) }}</b>
        <span class="pl-caption"> {{ g.submitted }} of {{ g.assigned }} · {{ g.state }}</span>
      </li>
    </ul>
    <PlTable :columns="columns" :rows="rows" row-key="value" label="360° scores by value">
      <template #cell-self="{ row }">{{ score(row.self) }}</template>
      <template #cell-others="{ row }">{{ score(row.others) }}</template>
      <template #cell-weighted="{ row }"><b>{{ score(row.weighted) }}</b></template>
    </PlTable>
    <div class="rv-grid">
      <div>
        <p class="pl-eyebrow">Hidden strengths</p>
        <p v-for="h in results.hiddenStrengths ?? []" :key="h.value">{{ h.value }} · self {{ score(h.self) }} vs others {{ score(h.others) }}</p>
        <p v-if="!results.hiddenStrengths?.length" class="pl-caption">None</p>
      </div>
      <div>
        <p class="pl-eyebrow">Blind spots</p>
        <p v-for="b in results.blindSpots ?? []" :key="b.value">{{ b.value }} · self {{ score(b.self) }} vs others {{ score(b.others) }}</p>
        <p v-if="!results.blindSpots?.length" class="pl-caption">None</p>
      </div>
    </div>
    <template v-for="(list, key) in results.comments ?? {}" :key="key">
      <p class="pl-eyebrow">{{ groupLabel(key) }} comments</p>
      <div v-for="(c, i) in list" :key="i" class="rv-comment">
        <div v-if="c.keep"><b>Keep:</b> {{ c.keep }}</div>
        <div v-if="c.start"><b>Start:</b> {{ c.start }}</div>
      </div>
    </template>
  </div>
</template>

<script setup>
// One subject's 360° results (spec culture/review-360 "Results"): the server already merged or hid the
// groups under the anonymity threshold and never sends a reviewer's identity.
import { computed } from 'vue';
import { Badge } from '@/kit/index.js';
import { PlTable } from '@/ui/index.js';
import { RATED_VALUES, groupLabel, score } from './presentation.js';

const props = defineProps({
  results: { type: Object, required: true },
});

const columns = [
  { key: 'value', label: 'Value' },
  { key: 'self', label: 'Self', num: true },
  { key: 'others', label: 'Others', num: true },
  { key: 'weighted', label: 'Weighted', num: true },
];

const rows = computed(() =>
  RATED_VALUES.map((value) => ({
    value,
    self: props.results.averages?.self?.[value] ?? null,
    others: props.results.averages?.others?.[value] ?? null,
    weighted: props.results.weighted?.[value] ?? null,
  })),
);
</script>
