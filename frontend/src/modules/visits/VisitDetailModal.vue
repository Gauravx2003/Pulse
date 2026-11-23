<template>
  <PlModal :open="open" :title="v ? `${v.customer} · ${v.city}` : 'Visit'" size="lg" @close="emit('close')">
    <PlAlert v-if="error && !v" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!v" />
    <template v-else>
      <p class="pl-caption">
        {{ v.personName }} · {{ dates(v) }} ({{ v.days }} d) · {{ v.purpose }} · requested by {{ v.requestedBy }}
        <Badge :text="statusLabel(v.status)" :tone="statusTone(v.status)" />
      </p>
      <dl class="vs-kv">
        <div><dt>Account owner</dt><dd>{{ v.ownerName }}</dd></div>
        <div><dt>Reference</dt><dd>{{ v.reference || '—' }}</dd></div>
        <div><dt>Points</dt><dd>{{ num(v.points?.total) }}</dd></div>
        <div><dt>Values</dt><dd>{{ (v.values ?? []).join(', ') || '—' }}</dd></div>
      </dl>
      <p v-for="p in v.points?.parts ?? []" :key="p.label" class="pl-caption">{{ p.label }}: {{ num(p.points) }}</p>
      <template v-if="v.reportedOn">
        <p class="pl-eyebrow">Visit report · {{ formatDate(v.reportedOn) }}</p>
        <p>{{ v.reportDone }}</p>
        <p class="pl-caption">{{ num(v.onsiteHours) }} h on site · {{ num(v.travelHours) }} h travel · {{ v.outcome }}{{ v.followUps ? ` · follow-ups: ${v.followUps}` : '' }}</p>
      </template>
      <template v-if="v.feedbackOn">
        <p class="pl-eyebrow">Customer feedback · {{ formatDate(v.feedbackOn) }} · {{ v.feedbackSource }}</p>
        <p>CSAT {{ v.csat }}/5 · {{ v.askAgain ? 'would ask for them again' : 'would not ask again' }}{{ v.quote ? ` · “${v.quote}”` : '' }}</p>
      </template>
      <p v-if="v.verification && v.verification !== 'pending'" class="pl-caption">
        {{ v.verification === 'confirmed' ? 'Verified' : 'Not counted' }}{{ v.verifyNote ? ` · ${v.verifyNote}` : '' }}
      </p>
    </template>
    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Close</button>
      <template v-if="v">
        <button v-if="has('delete')" type="button" class="pl-btn" @click="confirmDelete = true">Delete</button>
        <button v-if="has('edit')" type="button" class="pl-btn" @click="form = editForm()">Edit</button>
        <button v-if="canRemind" type="button" class="pl-btn" @click="remind">Remind engineer</button>
        <button v-if="has('reject')" type="button" class="pl-btn" @click="form = decisionForm('reject')">Do not count</button>
        <button v-if="has('verify')" type="button" class="pl-btn pl-btn-primary" @click="form = decisionForm('verify')">Verify points</button>
        <button v-if="has('report')" type="button" class="pl-btn pl-btn-primary" @click="form = reportForm()">File report</button>
        <button v-if="has('feedback')" type="button" class="pl-btn pl-btn-primary" @click="form = feedbackForm()">Record feedback</button>
      </template>
    </template>
  </PlModal>

  <FormModal
    v-if="form"
    :open="!!form"
    :title="form.title"
    :fields="form.fields"
    :initial="form.initial ?? {}"
    :submit-label="form.submitLabel"
    :save="form.save"
    @close="form = null"
    @saved="saved"
  />
  <PlConfirm
    :open="confirmDelete"
    title="Delete this visit?"
    message="The planned visit is removed. A visit with a report can't be deleted."
    confirm-label="Delete visit"
    @confirm="remove"
    @cancel="confirmDelete = false"
  />
</template>

<script setup>
// Visit detail (reference vDetail): the visit, its points and values, the report and the feedback, with the
// actions the server allows (edit and delete before the report, report by the engineer, feedback by the
// account owner, verify or do not count by effort.confirm, reminders by the manager).
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { can } from '@/shell/session.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlConfirm, PlLoading, PlModal, showToast } from '@/ui/index.js';
import FormModal from '../training/FormModal.vue';
import { PURPOSES, dates, num, statusLabel, statusTone } from './presentation.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  visitId: { type: String, default: '' },
  owners: { type: Array, default: () => [] },
});

const emit = defineEmits(['close', 'changed']);

const v = ref(null);
const error = ref('');
const form = ref(null);
const confirmDelete = ref(false);

async function load() {
  v.value = null;
  error.value = '';

  try {
    v.value = await api.get(`/visits/items/${props.visitId}`);
  } catch (e) {
    error.value = e.message || 'Could not open the visit.';
  }
}

watch(
  () => [props.open, props.visitId],
  ([open]) => {
    if (open && props.visitId) {
      load();
    }
  },
  { immediate: true },
);

function has(action) {
  return (v.value?.actions ?? []).includes(action);
}

const canRemind = computed(() => v.value?.status === 'report_due' && can('team.view', 'all.view'));

function editForm() {
  return {
    title: `Edit visit · ${v.value.customer}`,
    fields: [
      { key: 'customer', label: 'Customer', required: true, maxlength: 120 },
      { key: 'city', label: 'City', required: true, maxlength: 80 },
      { key: 'purpose', label: 'Purpose', type: 'select', required: true, options: PURPOSES.map((p) => ({ value: p, label: p })) },
      { key: 'from', label: 'From', type: 'date', required: true },
      { key: 'to', label: 'To', type: 'date', required: true },
      { key: 'requestedBy', label: 'Requested by', required: true, maxlength: 120 },
      { key: 'ownerId', label: 'Account owner', type: 'select', required: true, options: props.owners.map((o) => ({ value: o.id, label: o.name })) },
      { key: 'reference', label: 'CRM deal or ticket', maxlength: 60 },
      { key: 'notes', label: 'Notes', type: 'textarea', maxlength: 1000 },
    ],
    initial: v.value,
    submitLabel: 'Save visit',
    save: (b) => api.put(`/visits/items/${v.value.id}`, { ...b, version: v.value.version }, { quiet: true }),
    done: 'Visit saved',
  };
}

function reportForm() {
  return {
    title: `Visit report · ${v.value.customer}`,
    fields: [
      { key: 'done', label: 'What was done', type: 'textarea', required: true, maxlength: 2000 },
      { key: 'onsiteHours', label: 'Hours on site', type: 'number', required: true },
      { key: 'travelHours', label: 'Hours of travel', type: 'number', required: true },
      { key: 'outcome', label: 'Outcome', type: 'select', required: true, options: ['Resolved', 'Partially resolved', 'Follow-up needed', 'Deal progressed'].map((o) => ({ value: o, label: o })) },
      { key: 'followUps', label: 'Follow-ups', type: 'textarea', maxlength: 1000 },
      { key: 'contact', label: 'Customer contact', maxlength: 200 },
    ],
    initial: { outcome: 'Resolved' },
    submitLabel: 'File report',
    save: (b) => api.post(`/visits/items/${v.value.id}/report`, { ...b, version: v.value.version }, { quiet: true }),
    done: 'Report filed · the account owner is asked for feedback',
  };
}

function feedbackForm() {
  return {
    title: `Customer feedback · ${v.value.customer}`,
    fields: [
      { key: 'csat', label: 'CSAT (1–5)', type: 'number', required: true },
      { key: 'askAgain', label: 'The customer would ask for this engineer again', type: 'checkbox' },
      { key: 'source', label: 'Source', type: 'select', required: true, options: ['Customer call', 'Email', 'CSAT survey', 'Meeting'].map((o) => ({ value: o, label: o })) },
      { key: 'feedbackOn', label: 'Date', type: 'date', required: true },
      { key: 'quote', label: 'Quote', type: 'textarea', maxlength: 1000 },
    ],
    initial: { source: 'Customer call', askAgain: true },
    submitLabel: 'Record feedback',
    save: (b) => api.post(`/visits/items/${v.value.id}/feedback`, { ...b, version: v.value.version }, { quiet: true }),
    done: 'Feedback recorded',
  };
}

function decisionForm(kind) {
  return {
    title: kind === 'verify' ? `Verify points · ${v.value.personName}` : `Do not count · ${v.value.personName}`,
    fields: [{ key: 'note', label: kind === 'verify' ? 'Note (optional)' : 'Why it does not count', type: 'textarea', maxlength: 500 }],
    submitLabel: kind === 'verify' ? 'Verify' : 'Do not count',
    save: (b) => api.post(`/visits/items/${v.value.id}/${kind}`, { ...b, version: v.value.version }, { quiet: true }),
    done: kind === 'verify' ? 'Points verified' : 'Marked not counted',
  };
}

async function saved() {
  showToast(form.value?.done ?? 'Saved');
  form.value = null;
  refreshNotifications();
  await load();
  emit('changed');
}

async function remind() {
  try {
    await api.post(`/visits/items/${v.value.id}/remind`, {}, { quiet: true });
    showToast(`${v.value.personName} reminded about the report`);
  } catch (e) {
    showToast(e.message);
  }
}

async function remove() {
  confirmDelete.value = false;

  try {
    await api.delete(`/visits/items/${v.value.id}`, { body: { version: v.value.version }, quiet: true });
    showToast('Visit deleted');
    emit('changed');
    emit('close');
  } catch (e) {
    showToast(e.message);
  }
}
</script>
