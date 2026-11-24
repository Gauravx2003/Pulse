<script setup>
// New / Edit objective (reference 9.1): Objective *, Level (Individual; Team with okr.team), Quarter *,
// Owner (individual, okr.team only), Grows value, Linked Jira epic, and 1 to 5 key results with start,
// target, current and unit. The messages of the spec are checked here first; the server checks again
// and names the field it refuses.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { CULTURE_VALUES } from '@/kit/index.js';
import { can } from '@/shell/session.js';
import { PlField, PlFormError, PlModal, showToast } from '@/ui/index.js';
import { MAX_KEY_RESULTS, quarterLabel } from './presentation.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  /** The objective to edit, or null for a new one. */
  objective: { type: Object, default: null },
  quarters: { type: Array, default: () => [] },
  defaultQuarter: { type: String, default: '' },
  defaultLevel: { type: String, default: 'Individual' },
  team: { type: String, default: '' },
  ownerId: { type: String, default: '' },
  people: { type: Array, default: () => [] },
});

const emit = defineEmits(['close', 'saved']);

const form = ref(blank());
const errors = ref({});
const formError = ref('');
const saving = ref(false);

const teamAllowed = computed(() => can('okr.team'));

function blank() {
  return { title: '', level: 'Individual', quarter: '', ownerId: '', cultureValue: '', epicKey: '', keyResults: [kr()] };
}

function kr(from = {}) {
  return {
    id: from.id ?? null,
    description: from.description ?? '',
    startValue: from.startValue ?? 0,
    targetValue: from.targetValue ?? 100,
    currentValue: from.currentValue ?? 0,
    unit: from.unit ?? '%',
  };
}

watch(
  () => props.open,
  (open) => {
    if (!open) {
      return;
    }

    errors.value = {};
    formError.value = '';

    const o = props.objective;

    form.value = o
      ? {
          title: o.title,
          level: o.level,
          quarter: o.quarter,
          ownerId: o.ownerId ?? '',
          cultureValue: o.cultureValue ?? '',
          epicKey: o.epicKey ?? '',
          keyResults: (o.keyResults ?? []).map(kr),
        }
      : { ...blank(), quarter: props.defaultQuarter, level: props.defaultLevel, ownerId: props.ownerId };
  },
);

function validate() {
  const e = {};
  const f = form.value;

  if (!f.title.trim()) {
    e.title = 'Write the objective.';
  }

  if (!f.quarter) {
    e.quarter = 'Select a quarter.';
  }

  if (f.keyResults.some((k) => !String(k.description).trim())) {
    e.keyResults = 'Every key result needs a description.';
  } else if (f.keyResults.some((k) => Number(k.startValue) === Number(k.targetValue))) {
    e.keyResults = 'Target must differ from start.';
  }

  errors.value = e;

  return !Object.keys(e).length;
}

async function save() {
  if (!validate()) {
    return;
  }

  const f = form.value;

  const keyResults = f.keyResults.map((k) => ({
    ...(k.id ? { id: k.id } : {}),
    description: k.description.trim(),
    startValue: Number(k.startValue),
    targetValue: Number(k.targetValue),
    currentValue: Number(k.currentValue),
    unit: k.unit,
  }));

  const body = {
    title: f.title.trim(),
    quarter: f.quarter,
    epicKey: f.epicKey.trim() || null,
    cultureValue: f.cultureValue || null,
    keyResults,
  };

  saving.value = true;
  formError.value = '';

  try {
    if (props.objective) {
      await api.put(`/okrs/objectives/${props.objective.id}`, { ...body, version: props.objective.version }, { quiet: true });
    } else {
      const extra = f.level === 'Team' ? { level: 'Team', team: props.team } : { level: 'Individual', ...(f.ownerId ? { ownerId: f.ownerId } : {}) };

      await api.post('/okrs/objectives', { ...body, ...extra }, { quiet: true });
    }

    showToast(props.objective ? 'Objective updated' : 'Objective created');

    emit('saved');
  } catch (e) {
    if (e.field && ['title', 'quarter'].includes(e.field)) {
      errors.value = { [e.field]: e.message };
    } else if (e.field) {
      errors.value = { keyResults: e.message };
    } else {
      formError.value = e.status === 409 ? 'Someone else changed this objective. Close and reopen it to see their changes.' : e.message;
    }
  } finally {
    saving.value = false;
  }
}

function addKeyResult() {
  if (form.value.keyResults.length < MAX_KEY_RESULTS) {
    form.value.keyResults.push(kr());
  }
}

function removeKeyResult(i) {
  if (form.value.keyResults.length > 1) {
    form.value.keyResults.splice(i, 1);
  }
}

const levelOptions = computed(() =>
  [{ value: 'Individual', label: 'Individual' }].concat(teamAllowed.value ? [{ value: 'Team', label: `Team${props.team ? ` · ${props.team}` : ''}` }] : []),
);

const quarterOptions = computed(() => props.quarters.map((q) => ({ value: q, label: quarterLabel(q) })));
const valueOptions = CULTURE_VALUES.filter((v) => v !== 'Discipline').map((v) => ({ value: v, label: v }));
const ownerOptions = computed(() => props.people.map((p) => ({ value: p.id, label: p.name })));
</script>

<template>
  <PlModal :open="open" size="lg" :title="objective ? 'Edit objective' : 'New objective'" @close="emit('close')">
    <form class="form" @submit.prevent="save">
      <PlFormError v-if="formError" :message="formError" />
      <PlField v-model="form.title" label="Objective" required :maxlength="300" :error="errors.title" />
      <div class="grid">
        <PlField v-if="!objective" v-model="form.level" type="select" label="Level" :options="levelOptions" required />
        <PlField v-model="form.quarter" type="select" label="Quarter" placeholder="Select quarter…" :options="quarterOptions" required :error="errors.quarter" />
        <PlField
          v-if="!objective && form.level === 'Individual' && teamAllowed && ownerOptions.length"
          v-model="form.ownerId"
          type="select"
          label="Owner"
          placeholder="Me"
          :options="ownerOptions"
        />
        <PlField v-model="form.cultureValue" type="select" label="Grows value" placeholder="—" :options="valueOptions" />
        <PlField v-model="form.epicKey" label="Linked Jira epic" placeholder="e.g. PLAT-2231" hint="Optional" :maxlength="50" />
      </div>

      <fieldset class="krs">
        <legend class="pl-h3">Key results</legend>
        <div v-for="(k, i) in form.keyResults" :key="i" class="kr">
          <PlField v-model="k.description" :label="`Key result ${i + 1}`" :maxlength="300" />
          <PlField v-model="k.startValue" type="number" label="Start" />
          <PlField v-model="k.targetValue" type="number" label="Target" />
          <PlField v-model="k.currentValue" type="number" label="Current" />
          <PlField v-model="k.unit" label="Unit" :maxlength="20" />
          <button
            type="button"
            class="pl-x remove"
            :aria-label="`Remove key result ${i + 1}`"
            :disabled="form.keyResults.length === 1"
            @click="removeKeyResult(i)"
          >×</button>
        </div>
        <p v-if="errors.keyResults" class="pl-field-error">{{ errors.keyResults }}</p>
        <button type="button" class="pl-btn pl-btn-sm" :disabled="form.keyResults.length >= MAX_KEY_RESULTS" @click="addKeyResult">+ Add key result</button>
      </fieldset>
    </form>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Cancel</button>
      <button type="button" class="pl-btn pl-btn-primary" :disabled="saving" @click="save">{{ objective ? 'Save changes' : 'Create objective' }}</button>
    </template>
  </PlModal>
</template>

<style scoped>
.form {
  display: grid;
  gap: var(--space-3);
}

.grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(200px, 1fr));
  gap: var(--space-3);
}

.krs {
  border: 0;
  margin: 0;
  padding: 0;
  display: grid;
  gap: var(--space-3);
}

.kr {
  display: grid;
  grid-template-columns: minmax(180px, 3fr) repeat(3, minmax(70px, 1fr)) minmax(60px, 0.8fr) auto;
  gap: var(--space-2);
  align-items: end;
}

@media (max-width: 700px) {
  .kr {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .kr > :first-child {
    grid-column: 1 / -1;
  }
}

.remove {
  margin-bottom: var(--space-2);
}
</style>
