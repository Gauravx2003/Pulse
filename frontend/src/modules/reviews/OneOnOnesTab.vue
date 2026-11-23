<template>
  <div class="rv-actions">
    <PlField v-if="choices.length > 1" v-model="personId" type="select" label="Person" :options="choices" />
    <button v-if="canManage" type="button" class="pl-btn pl-btn-primary" :disabled="!personId" @click="newOpen = true">New 1:1</button>
    <button type="button" class="pl-btn" :disabled="!personId" @click="kudosOpen = true">Give kudos</button>
  </div>

  <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
  <PlLoading v-else-if="!meetings" />
  <template v-else>
    <PlCard title="1:1s" :subtitle="personName ? `With ${personName} · shared notes and action items` : ''" :empty="!meetings.length" empty-text="No 1:1s yet.">
      <div v-for="m in meetings" :key="m.id" class="rv-comment">
        <div class="rv-row">
          <b>{{ formatDateWithYear(m.heldOn) }}</b>
          <span class="pl-caption">{{ m.managerName }} · {{ m.personName }}</span>
        </div>
        <p v-if="m.agenda?.length" class="pl-caption">Agenda: {{ m.agenda.join(' · ') }}</p>
        <PlField v-model="notes[m.id]" type="textarea" :label="`Notes · ${formatDate(m.heldOn)}`" :maxlength="4000" />
        <div class="rv-actions">
          <button type="button" class="pl-btn pl-btn-sm" :disabled="notes[m.id] === m.notes" @click="saveNotes(m)">Save notes</button>
          <button type="button" class="pl-btn pl-btn-sm" @click="actionFor = m">Add action item</button>
        </div>
        <label v-for="act in m.actions" :key="act.id" class="row">
          <input type="checkbox" :checked="act.done" @change="toggle(act, $event.target.checked)" />
          <span :class="{ 'pl-caption': act.done }">{{ act.text }}{{ act.dueOn ? ` · due ${formatDate(act.dueOn)}` : '' }}</span>
        </label>
      </div>
    </PlCard>

    <PlCard title="Kudos" subtitle="Evidence for culture values, not a score" :empty="!kudos.length" empty-text="No kudos yet.">
      <div v-for="k in kudos" :key="k.id" class="rv-comment">
        <div class="rv-row">
          <span><Badge :text="k.value" tone="brand" /> <b>{{ k.fromName ?? 'Slack' }}</b> → {{ k.personName }}</span>
          <span class="pl-caption">{{ formatDate(k.givenAt) }}</span>
        </div>
        <p>{{ k.message }}</p>
      </div>
    </PlCard>
  </template>

  <FormModal
    :open="newOpen"
    :title="`New 1:1 · ${personName}`"
    :fields="[
      { key: 'heldOn', label: 'Date', type: 'date', required: true },
      { key: 'agenda', label: 'Agenda (comma separated)', maxlength: 400 },
    ]"
    submit-label="Create 1:1"
    :save="create"
    @close="newOpen = false"
    @saved="done('1:1 created', () => (newOpen = false))"
  />
  <FormModal
    :open="!!actionFor"
    title="Add action item"
    :fields="[
      { key: 'text', label: 'Action', required: true, maxlength: 300 },
      { key: 'dueOn', label: 'Due', type: 'date' },
    ]"
    submit-label="Add"
    :save="addAction"
    @close="actionFor = null"
    @saved="done('Action item added', () => (actionFor = null))"
  />
  <FormModal
    :open="kudosOpen"
    :title="`Give kudos · ${personName}`"
    :fields="[
      { key: 'value', label: 'Culture value', type: 'select', required: true, options: CULTURE_VALUES.map((v) => ({ value: v, label: v })) },
      { key: 'message', label: 'What they did', type: 'textarea', required: true, maxlength: 500 },
      { key: 'shareTo', label: 'Also post to', type: 'select', options: SHARE },
    ]"
    :initial="{ value: 'Collaboration', shareTo: 'none' }"
    submit-label="Give kudos"
    :save="giveKudos"
    @close="kudosOpen = false"
    @saved="done('Kudos given', () => (kudosOpen = false))"
  />
</template>

<script setup>
// 1:1s & feedback (spec culture/one-on-ones-kudos): the chosen person's 1:1s with shared notes and action
// items (GET /reviews/one-on-ones?personId=) and their kudos feed; New 1:1 for managers, Give kudos for
// everyone (the server refuses kudos to yourself).
import { computed, reactive, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { loadPeople, people } from '@/shell/person.js';
import { can, session } from '@/shell/session.js';
import { formatDate, formatDateWithYear } from '@/ui/format.js';
import { PlAlert, PlCard, PlField, PlLoading, showToast } from '@/ui/index.js';
// One form-in-a-modal shared with the Training screens.
import FormModal from '../training/FormModal.vue';
import { CULTURE_VALUES } from './presentation.js';

const SHARE = [
  { value: 'none', label: 'Only Pulse' },
  { value: 'slack', label: 'Slack' },
  { value: 'teams', label: 'Teams' },
];

const canManage = can('team.view', 'all.view');
const personId = ref('');
const meetings = ref(null);
const kudos = ref([]);
const notes = reactive({});
const error = ref('');
const newOpen = ref(false);
const kudosOpen = ref(false);
const actionFor = ref(null);

loadPeople()
  .then((rows) => {
    const me = session.me?.user?.personId;
    personId.value = (rows ?? []).find((p) => p.id !== me)?.id ?? me ?? rows?.[0]?.id ?? '';
  })
  .catch(() => {
    personId.value = session.me?.user?.personId ?? '';
  });

const choices = computed(() => (people.value ?? []).map((p) => ({ value: p.id, label: p.name })));
const personName = computed(() => (people.value ?? []).find((p) => p.id === personId.value)?.name ?? '');

async function load() {
  if (!personId.value) {
    return;
  }

  error.value = '';
  meetings.value = null;

  try {
    const [m, k] = await Promise.all([
      api.get('/reviews/one-on-ones', { query: { personId: personId.value } }),
      api.get('/reviews/kudos', { query: { personId: personId.value } }),
    ]);

    meetings.value = m.items;
    kudos.value = k.items;
    m.items.forEach((x) => (notes[x.id] = x.notes));
  } catch (e) {
    error.value = e.message || 'Could not load the 1:1s.';
  }
}

watch(personId, load);

async function saveNotes(m) {
  try {
    await api.put(`/reviews/one-on-ones/${m.id}/notes`, { notes: notes[m.id], version: m.version }, { quiet: true });
    showToast('Notes saved');
  } catch (e) {
    showToast(e.message);
  }

  await load();
}

async function toggle(act, done) {
  try {
    await api.put(`/reviews/actions/${act.id}`, { text: act.text, done, version: act.version, ...(act.dueOn ? { dueOn: act.dueOn } : {}) }, { quiet: true });
  } catch (e) {
    showToast(e.message);
  }

  await load();
}

function create(v) {
  const agenda = (v.agenda ?? '')
    .split(',')
    .map((s) => s.trim())
    .filter(Boolean);

  return api.post('/reviews/one-on-ones', { personId: personId.value, heldOn: v.heldOn, agenda }, { quiet: true });
}

function addAction(v) {
  return api.post(`/reviews/one-on-ones/${actionFor.value.id}/actions`, v, { quiet: true });
}

function giveKudos(v) {
  return api.post('/reviews/kudos', { ...v, personId: personId.value }, { quiet: true });
}

async function done(text, close) {
  close();
  showToast(text);
  await load();
}
</script>
