<template>
  <PlModal :open="open" title="Appraisal standards" @close="emit('close')">
    <PlLoading v-if="!loaded && !error" />
    <PlAlert v-else-if="error && !loaded" badge="Error" tone="danger">{{ error }}</PlAlert>
    <template v-else>
      <p class="pl-eyebrow">Score weights (must total 100%)</p>
      <div class="apr-tiles">
        <PlField v-for="w in WEIGHTS" :key="w.path" v-model="values[w.path]" type="number" :label="`${w.label} %`" :error="errors[w.path] ?? ''" />
      </div>
      <p class="pl-caption">Total {{ total }}%</p>
      <div class="apr-tiles">
        <PlField v-model="values['appraisal.max']" type="number" label="Max increment %" :error="errors['appraisal.max'] ?? ''" />
        <PlField v-model="values['appraisal.budget']" type="number" label="Budget % of payroll" :error="errors['appraisal.budget'] ?? ''" />
        <PlField v-model="values['appraisal.minMonths']" type="number" label="Min months to be eligible" :error="errors['appraisal.minMonths'] ?? ''" />
      </div>
      <PlFormError v-if="error" :message="error" />
    </template>

    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="!loaded || busy" @click="save">Save standards</button>
    </template>
  </PlModal>
</template>

<script setup>
// Appraisal standards (reference "aprrules"; spec appraisal/appraisal "Appraisal score", "Cap and budget"):
// the rule group appraisal_standards of Rules & thresholds (GET/PUT /rules/groups/appraisal_standards,
// appraisal.approve). The server checks the ranges and that the weights total 100%; submitted proposals
// keep the score frozen at submission.
import { computed, reactive, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { PlAlert, PlField, PlFormError, PlLoading, PlModal } from '@/ui/index.js';

const WEIGHTS = [
  { path: 'appraisal.w.perf', label: 'Performance index' },
  { path: 'appraisal.w.r360', label: '360° feedback' },
  { path: 'appraisal.w.mgr', label: 'Manager review' },
  { path: 'appraisal.w.disc', label: 'Discipline' },
];

const PATHS = [...WEIGHTS.map((w) => w.path), 'appraisal.max', 'appraisal.budget', 'appraisal.minMonths'];

const props = defineProps({
  open: { type: Boolean, default: false },
});

const emit = defineEmits(['close', 'saved']);

const values = reactive({});
const errors = reactive({});
const version = ref(null);
const loaded = ref(false);
const busy = ref(false);
const error = ref('');

/** The value of a rule path in a group view ({@code fields: [{path, value}]}). */
function valueOf(group, path) {
  const field = (group.fields ?? []).find((f) => f.path === path);

  return field ? field.value : '';
}

async function load() {
  loaded.value = false;
  error.value = '';
  PATHS.forEach((p) => delete errors[p]);

  try {
    const group = await api.get('/rules/groups/appraisal_standards');

    PATHS.forEach((p) => {
      values[p] = String(valueOf(group, p));
    });

    version.value = group.version;
    loaded.value = true;
  } catch (e) {
    error.value = e.message || 'Could not load the standards.';
  }
}

watch(
  () => props.open,
  (open) => {
    if (open) {
      load();
    }
  },
  { immediate: true },
);

const total = computed(() => WEIGHTS.reduce((s, w) => s + (Number(values[w.path]) || 0), 0));

async function save() {
  busy.value = true;
  error.value = '';
  PATHS.forEach((p) => delete errors[p]);

  try {
    await api.put(
      '/rules/groups/appraisal_standards',
      { values: Object.fromEntries(PATHS.map((p) => [p, Number(values[p])])), version: version.value },
      { quiet: true },
    );

    emit('saved');
  } catch (e) {
    if (e.field && PATHS.includes(e.field)) {
      errors[e.field] = e.message;
    } else {
      error.value = e.message;
    }
  } finally {
    busy.value = false;
  }
}
</script>
