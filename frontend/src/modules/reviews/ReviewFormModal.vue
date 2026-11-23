<template>
  <PlModal :open="open" :title="r ? `${r.kind === 'training' ? `Checkpoint · Day ${r.checkpointDay}` : r.cycleName} · ${r.subjectName}` : 'Review'" size="lg" @close="emit('close')">
    <PlAlert v-if="error && !r" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!r" />
    <template v-else>
      <p class="pl-caption">
        You review as {{ groupLabel(r.group) }} · due {{ formatDate(r.dueOn) }} · scores 1–5, or Not observed
        <Badge :text="statusLabel(r.status)" :tone="statusTone(r.status)" />
      </p>
      <div v-for="v in RATED_VALUES" :key="v" class="rv-score">
        <PlField v-model="examples[v]" :label="`${v} · example`" :maxlength="500" :disabled="locked" />
        <PlField
          v-model="scores[v]"
          type="select"
          :label="`${v} score`"
          :options="SCORE_OPTIONS"
          :disabled="locked"
          :error="fieldErrors[v] ?? ''"
        />
      </div>
      <PlField v-model="keepDoing" type="textarea" label="Keep doing" :maxlength="2000" :disabled="locked" />
      <PlField v-model="startDoing" type="textarea" label="Start doing" :maxlength="2000" :disabled="locked" />
      <PlFormError v-if="error" :message="error" />
    </template>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Close</button>
      <template v-if="r && !locked">
        <button type="button" class="pl-btn" :disabled="busy" @click="decline">Decline</button>
        <button type="button" class="pl-btn" :disabled="busy" @click="save(false)">Save draft</button>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="save(true)">Submit review</button>
      </template>
    </template>
  </PlModal>
</template>

<script setup>
// The 360° review form (spec culture/review-360 "Form"), also used for training checkpoints: a score
// (or Not observed) and an optional example per rated value, Keep doing and Start doing. Draft saves keep
// the form open; Submit locks it. The server validates and never shows the subject who wrote it.
import { computed, reactive, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlField, PlFormError, PlLoading, PlModal, showToast } from '@/ui/index.js';
import { RATED_VALUES, groupLabel, statusLabel, statusTone } from './presentation.js';

// '' = not answered yet; 'na' = Not observed, sent as null (the server wants every value answered).
const SCORE_OPTIONS = [
  { value: '', label: 'Choose…' },
  { value: 'na', label: 'Not observed' },
  ...[5, 4, 3, 2, 1].map((n) => ({ value: String(n), label: `${n}` })),
];

const props = defineProps({
  open: { type: Boolean, default: false },
  requestId: { type: String, default: '' },
});

const emit = defineEmits(['close', 'changed']);

const r = ref(null);
const error = ref('');
const busy = ref(false);
const scores = reactive({});
const examples = reactive({});
const fieldErrors = reactive({});
const keepDoing = ref('');
const startDoing = ref('');

const locked = computed(() => !r.value || r.value.status === 'submitted' || r.value.status === 'declined' || r.value.cycleStatus !== 'running');

async function load() {
  r.value = null;
  error.value = '';

  try {
    const res = await api.get(`/reviews/360/requests/${props.requestId}`);

    for (const v of RATED_VALUES) {
      const stored = res.scores ?? {};
      scores[v] = stored[v] ? String(stored[v]) : v in stored ? 'na' : '';
      examples[v] = res.examples?.[v] ?? '';
      delete fieldErrors[v];
    }

    keepDoing.value = res.keepDoing ?? '';
    startDoing.value = res.startDoing ?? '';
    r.value = res;
  } catch (e) {
    error.value = e.message || 'Could not open the review.';
  }
}

watch(
  () => [props.open, props.requestId],
  ([open]) => {
    if (open && props.requestId) {
      load();
    }
  },
  { immediate: true },
);

function body() {
  const s = {};
  const ex = {};

  for (const v of RATED_VALUES) {
    if (scores[v] === 'na') s[v] = null;
    else if (scores[v]) s[v] = Number(scores[v]);
    if (examples[v]) ex[v] = examples[v];
  }

  return { scores: s, examples: ex, keepDoing: keepDoing.value, startDoing: startDoing.value, version: r.value.version };
}

async function save(submit) {
  busy.value = true;
  error.value = '';

  try {
    if (submit) {
      await api.post(`/reviews/360/requests/${r.value.id}/submit`, body(), { quiet: true });
      showToast(`Review submitted · ${r.value.subjectName}`);
      refreshNotifications();
      emit('changed');
      emit('close');
    } else {
      await api.put(`/reviews/360/requests/${r.value.id}`, body(), { quiet: true });
      showToast('Draft saved');
      await load();
      emit('changed');
    }
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

async function decline() {
  busy.value = true;

  try {
    await api.post(`/reviews/360/requests/${r.value.id}/decline`, { version: r.value.version }, { quiet: true });
    showToast('Review declined');
    refreshNotifications();
    emit('changed');
    emit('close');
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}
</script>
