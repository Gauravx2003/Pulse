<template>
  <PlModal :open="open" :title="`Import ${type?.name ?? ''}`" size="lg" @close="close">
    <ol class="im-steps" aria-label="Steps">
      <li :aria-current="step === 'choose' ? 'step' : undefined">1 · Choose file</li>
      <li :aria-current="step === 'check' ? 'step' : undefined">2 · Check</li>
      <li :aria-current="step === 'done' ? 'step' : undefined">3 · Done</li>
    </ol>

    <template v-if="step === 'choose'">
      <p class="pl-caption">Feeds: {{ type?.feeds }}. Nothing is saved before you press Import.</p>
      <PlField v-model="mode" type="select" label="Mode" :options="(type?.modes ?? []).map((m) => ({ value: m.key, label: m.label }))" />
      <label class="pl-field-label" for="im-file">CSV file (at most 5 MB)</label>
      <input id="im-file" class="pl-input" type="file" accept=".csv,text/csv" @change="file = $event.target.files?.[0] ?? null" />
      <PlFormError v-if="error" :message="error" />
    </template>

    <template v-else-if="step === 'check'">
      <p><b>{{ result.summary }}</b></p>
      <p class="pl-caption">{{ result.rowsRead }} rows read from {{ result.fileName }}{{ result.ignoredColumns?.length ? ` · ignored columns: ${result.ignoredColumns.join(', ')}` : '' }}</p>
      <PlAlert v-if="result.errors" badge="Errors" tone="danger">
        {{ result.errors }} row{{ result.errors > 1 ? 's' : '' }} with errors won't be imported. Fix them in the file and check again, or import the rest.
      </PlAlert>
      <PlTable v-if="messages.length" :columns="messageColumns" :rows="messages" row-key="key" label="Errors and warnings">
        <template #cell-kind="{ row }"><Badge :text="row.kind" :tone="row.kind === 'Error' ? 'danger' : 'accent'" /></template>
      </PlTable>
      <p v-if="result.listsTruncated" class="pl-caption">Only the first messages are listed.</p>
    </template>

    <template v-else-if="step === 'done'">
      <p><b>{{ done.imported }} row{{ done.imported === 1 ? '' : 's' }} imported</b> · {{ done.skipped }} skipped</p>
      <p v-for="n in done.summary?.notes ?? []" :key="n" class="pl-caption">{{ n }}</p>
      <p class="pl-caption">You can undo this import from the history for 24 hours.</p>
    </template>

    <template #actions>
      <button type="button" class="pl-btn" @click="close">{{ step === 'done' ? 'Close' : 'Cancel' }}</button>
      <button v-if="step === 'choose'" type="button" class="pl-btn pl-btn-primary" :disabled="!file || busy" @click="runCheck">Check file</button>
      <template v-if="step === 'check'">
        <button type="button" class="pl-btn" :disabled="busy" @click="back">Choose another file</button>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="!result.ready || busy" @click="commit">Import {{ result.ready }} row{{ result.ready === 1 ? '' : 's' }}</button>
      </template>
    </template>
  </PlModal>
</template>

<script setup>
// The three-step upload (reference impModal): choose a file and mode, check it (POST /imports/:type/check,
// nothing written; errors and warnings by line), import the ready rows (POST /imports/:type/commit) and
// show the result. Cancelling after a check drops it (DELETE /imports/checks/:id).
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { PlAlert, PlField, PlFormError, PlModal, PlTable } from '@/ui/index.js';
import { checkCsv } from './files.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  type: { type: Object, default: null },
});

const emit = defineEmits(['close', 'imported']);

const step = ref('choose');
const mode = ref('');
const file = ref(null);
const result = ref(null);
const done = ref(null);
const error = ref('');
const busy = ref(false);

watch(
  () => [props.open, props.type?.id],
  ([open]) => {
    if (open) {
      step.value = 'choose';
      mode.value = props.type?.defaultMode ?? props.type?.modes?.[0]?.key ?? '';
      file.value = null;
      result.value = null;
      done.value = null;
      error.value = '';
    }
  },
  { immediate: true },
);

const messageColumns = [
  { key: 'line', label: 'Line', num: true },
  { key: 'kind', label: 'Kind' },
  { key: 'column', label: 'Column' },
  { key: 'message', label: 'Message' },
];

const messages = computed(() => [
  ...(result.value?.errorList ?? []).map((m, i) => ({ ...m, kind: 'Error', key: `e${i}` })),
  ...(result.value?.warningList ?? []).map((m, i) => ({ ...m, kind: 'Warning', key: `w${i}` })),
]);

async function runCheck() {
  busy.value = true;
  error.value = '';

  try {
    result.value = await checkCsv(props.type.id, mode.value, file.value);
    step.value = 'check';
  } catch (e) {
    error.value = e.message;
  } finally {
    busy.value = false;
  }
}

async function commit() {
  busy.value = true;

  try {
    done.value = await api.post(`/imports/${props.type.id}/commit`, { checkId: result.value.checkId }, { quiet: true });
    step.value = 'done';
    refreshNotifications();
    emit('imported');
  } catch (e) {
    error.value = e.message;
    step.value = 'choose';
  } finally {
    busy.value = false;
  }
}

async function dropCheck() {
  if (result.value?.checkId && step.value === 'check') {
    await api.delete(`/imports/checks/${result.value.checkId}`, { quiet: true }).catch(() => {});
  }
}

async function back() {
  await dropCheck();
  step.value = 'choose';
  result.value = null;
}

async function close() {
  await dropCheck();
  emit('close');
}
</script>
