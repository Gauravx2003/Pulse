<template>
  <PlAlert v-if="error" badge="Error" tone="danger">
    {{ error }}
    <template #action><button type="button" class="pl-btn pl-btn-sm" @click="load">Retry</button></template>
  </PlAlert>
  <PlLoading v-else-if="!a" />
  <template v-else>
    <PlCard :title="a.planName" :subtitle="subtitle">
      <template #actions><Badge :text="a.stage" :tone="stageTone(a.stage)" /></template>
      <p class="pl-caption">{{ waiting }}</p>
      <div class="tr-actions">
        <button v-for="act in actionButtons" :key="act.key" type="button" class="pl-btn pl-btn-sm" @click="openForm(act.form)">
          {{ act.label }}
        </button>
      </div>
    </PlCard>

    <PlKpiRow :columns="4" :label="`${a.planName} progress`">
      <StatTile eyebrow="Progress" :value="progress(a)" unit="%" :foot="`${Object.keys(a.moduleProgress ?? {}).length} of ${a.modules.length} modules`" />
      <StatTile
        eyebrow="Check-ins"
        :value="a.rating?.checkIns?.verified ?? 0"
        :unit="` / ${a.rating?.checkIns?.total ?? 0} verified`"
        :badge="checkInBadge.text"
        :badge-tone="checkInBadge.tone"
      />
      <StatTile
        eyebrow="Assessment"
        :value="a.assessment ? a.assessment.score : '—'"
        :unit="a.assessment ? '%' : ''"
        :badge="a.assessment ? a.assessment.result : ''"
        :badge-tone="a.assessment ? (a.assessment.result === 'Pass' ? 'ok' : 'danger') : ''"
        :foot="a.assessment ? '' : `after ${formatDate(a.end)}`"
      />
      <StatTile
        eyebrow="Training rating"
        :value="rating(a.rating?.score)"
        :unit="typeof a.rating?.score === 'number' ? ' / 5' : ''"
        :badge="a.rating?.final ? a.rating?.band?.label ?? '' : typeof a.rating?.score === 'number' ? 'Provisional' : 'Not rated'"
        :badge-tone="a.rating?.band?.tone ?? ''"
      />
    </PlKpiRow>

    <div class="tr-grid">
      <PlCard :title="`${a.cadence} check-ins`" :subtitle="isTrainee ? 'Submit an update for each period' : 'Verify each update or send it back'">
        <ul class="tr-list">
          <li v-for="p in a.periods" :key="p.key">
            <span>
              <b>{{ p.label }}</b>
              <span v-if="checkInOf(p.key)?.comment" class="pl-caption"> · {{ checkInOf(p.key).reviewer }}: “{{ checkInOf(p.key).comment }}”</span>
            </span>
            <span class="tr-row-actions">
              <Badge :text="p.status" :tone="periodTone(p.status)" />
              <button
                v-if="isTrainee && ['Due', 'Missed', 'Needs work'].includes(p.status)"
                type="button"
                class="pl-btn pl-btn-sm"
                @click="openForm(forms.checkIn(p))"
              >
                {{ p.status === 'Needs work' ? 'Resend' : 'Submit' }}
              </button>
              <button
                v-if="canReview && p.status === 'Submitted' && checkInOf(p.key)"
                type="button"
                class="pl-btn pl-btn-sm"
                @click="openForm(forms.review(checkInOf(p.key)))"
              >
                Review
              </button>
            </span>
          </li>
        </ul>
      </PlCard>

      <PlCard title="Feedback" :subtitle="isTrainee ? 'Read and acknowledge each item' : 'Feedback given and the trainee’s answers'" :empty="!feedback.length" empty-text="No feedback yet.">
        <ul class="tr-list">
          <li v-for="f in feedback" :key="f.key">
            <span>
              <b>{{ f.label }}</b>
              <span class="pl-caption"> · {{ f.text }}</span>
              <span v-if="f.ack" class="pl-caption"> · {{ f.ack.st }}{{ f.ack.comment ? `: “${f.ack.comment}”` : '' }}</span>
              <span v-if="f.ack?.reply" class="pl-caption"> · reply: “{{ f.ack.reply.text ?? f.ack.reply }}”</span>
            </span>
            <span class="tr-row-actions">
              <button v-if="isTrainee && !f.ack" type="button" class="pl-btn pl-btn-sm" @click="openForm(forms.ack(f))">Acknowledge</button>
              <button
                v-if="isMentor && f.ack?.st === 'Disagreed' && !f.ack.reply"
                type="button"
                class="pl-btn pl-btn-sm"
                @click="openForm(forms.reply(f))"
              >
                Reply
              </button>
            </span>
          </li>
        </ul>
      </PlCard>
    </div>

    <PlCard title="Mentoring sessions" :subtitle="`Mentor ${a.mentorName ?? '—'} · ${a.meetingFrequency}`">
      <template #actions>
        <button v-if="isTrainee && !finished" type="button" class="pl-btn pl-btn-sm" @click="openForm(forms.ask())">Ask for a session</button>
        <button v-if="(isMentor || isManager) && !finished" type="button" class="pl-btn pl-btn-sm" @click="openForm(forms.session())">Log session</button>
      </template>
      <p v-for="r in openRequests" :key="r.id" class="pl-caption">Session requested {{ formatDate(r.requestedOn) }}: “{{ r.note }}”</p>
      <PlTable :columns="sessionColumns" :rows="a.sessions" row-key="id" label="Mentoring sessions" empty-text="No sessions logged yet.">
        <template #cell-heldOn="{ row }">{{ formatDate(row.heldOn) }}</template>
        <template #cell-topics="{ row }">
          {{ row.topics }}
          <Badge v-if="row.blocker" text="Blocker" tone="danger" />
        </template>
      </PlTable>
    </PlCard>

    <div class="tr-grid">
      <PlCard title="Modules" :subtitle="isTrainee ? 'Tick modules as you finish them' : 'Mentor and manager can tick too'">
        <template v-for="w in byWeek(a.modules)" :key="w.week">
          <p class="pl-eyebrow">Week {{ w.week }}</p>
          <label v-for="m in w.modules" :key="m.id" class="tr-module">
            <input type="checkbox" :checked="!!a.moduleProgress?.[m.id]" :disabled="finished || busy" @change="tick(m, $event.target.checked)" />
            <span>{{ m.title }} <span class="pl-caption">· {{ m.type }} · {{ m.hours }} h{{ m.link ? ` · ${m.link}` : '' }}</span></span>
          </label>
        </template>
      </PlCard>

      <PlCard title="Checkpoint reviews" subtitle="Same review form as 360° feedback" :empty="!a.checkpoints?.length" empty-text="This plan has no checkpoints.">
        <ul class="tr-list">
          <li v-for="day in a.checkpoints" :key="day">
            <span><b>Day {{ day }}</b> <span class="pl-caption">· due {{ formatDate(addDays(a.startOn, day)) }}</span></span>
            <button v-if="isManager && !finished" type="button" class="pl-btn pl-btn-sm" @click="requestCheckpoint(day)">Request</button>
          </li>
        </ul>
      </PlCard>
    </div>

    <FormModal
      v-if="form"
      :open="!!form"
      :title="form.title"
      :intro="form.intro ?? ''"
      :fields="form.fields"
      :initial="form.initial ?? {}"
      :submit-label="form.submitLabel ?? 'Save'"
      :save="form.save"
      @close="form = null"
      @saved="onSaved"
    />
  </template>
</template>

<script setup>
// One training assignment (reference trBody): stage and next steps, KPIs, check-ins, feedback with the
// trainee's acknowledgements, mentoring sessions, modules and checkpoints, from GET
// /training/assignments/:id. `roles` and `actions` come from the server, so the buttons follow its rules.
import { computed, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge, StatTile } from '@/kit/index.js';
import { refreshNotifications } from '@/shell/notifications.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlKpiRow, PlLoading, PlTable, showToast } from '@/ui/index.js';
import FormModal from './FormModal.vue';
import { byWeek, periodTone, progress, rating, stageTone } from './presentation.js';

const props = defineProps({
  assignmentId: { type: String, required: true },
});

const emit = defineEmits(['changed']);

const a = ref(null);
const error = ref('');
const busy = ref(false);
const form = ref(null);

async function load() {
  error.value = '';

  try {
    a.value = await api.get(`/training/assignments/${props.assignmentId}`);
  } catch (e) {
    error.value = e.message || 'Could not load the training.';
  }
}

watch(
  () => props.assignmentId,
  () => {
    a.value = null;
    load();
  },
  { immediate: true },
);

const roles = computed(() => a.value?.roles ?? []);
const isTrainee = computed(() => roles.value.includes('trainee'));
const isMentor = computed(() => roles.value.includes('mentor'));
const isManager = computed(() => roles.value.includes('manager'));
const canReview = computed(() => isMentor.value || isManager.value);
const finished = computed(() => ['Completed', 'Not completed'].includes(a.value?.stage));

const subtitle = computed(() => `${a.value.personName} · ${formatDate(a.value.startOn)} – ${formatDate(a.value.end)} · mentor ${a.value.mentorName ?? '—'}`);

const waiting = computed(() => {
  const s = a.value.stage;

  if (s === 'Not started') return `Starts ${formatDate(a.value.startOn)}`;
  if (s === 'In training') return `Training period ends ${formatDate(a.value.end)}`;
  if (s === 'Assessment') return 'Waiting for the assessment by the mentor or manager';
  if (s === 'Final review') return 'Waiting for the mentor and manager final reviews';
  if (s === 'Trainee sign-off') return 'Waiting for the trainee to sign off the final feedback';

  return s;
});

const checkInBadge = computed(() => {
  const c = a.value.rating?.checkIns ?? {};

  if (c.submitted) return { text: `${c.submitted} to review`, tone: 'info' };
  if (c.missed) return { text: `${c.missed} missed`, tone: 'danger' };

  return { text: 'Up to date', tone: 'ok' };
});

const openRequests = computed(() => (a.value.requests ?? []).filter((r) => !r.handledOn));

const sessionColumns = [
  { key: 'heldOn', label: 'Date' },
  { key: 'topics', label: 'Topics' },
  { key: 'nextSteps', label: 'Next steps' },
  { key: 'loggedBy', label: 'Logged by' },
];

function checkInOf(period) {
  return (a.value.checkIns ?? []).find((c) => c.periodStart === period) ?? null;
}

function addDays(iso, n) {
  const d = new Date(`${iso}T00:00:00Z`);

  d.setUTCDate(d.getUTCDate() + n);

  return d.toISOString().slice(0, 10);
}

/** Feedback items to acknowledge: check-in comments, assessment notes and final reviews. */
const feedback = computed(() => {
  const acks = a.value.acknowledgements ?? {};

  return (a.value.feedbackKeys ?? []).map((key) => {
    const [kind, ref2] = key.split(':');
    let label = key;
    let text = '';

    if (kind === 'ci') {
      const c = checkInOf(ref2);
      label = `Check-in · week of ${formatDate(ref2)}`;
      text = c ? `${c.reviewer ?? ''}: ${c.comment ?? ''}` : '';
    } else if (kind === 'as') {
      label = `Assessment · attempt ${ref2}`;
      text = a.value.assessment?.notes ?? '';
    } else if (kind === 'fm' || kind === 'fg') {
      const r = a.value.finalReviews?.[kind === 'fm' ? 'mentor' : 'manager'];
      label = `${kind === 'fm' ? 'Mentor' : 'Manager'} final review · attempt ${ref2}`;
      text = r ? `${r.rating}/5 · Good: ${r.good} · Improve: ${r.improve}` : '';
    }

    return { key, label, text, ack: acks[key] ?? null };
  });
});

const forms = {
  checkIn: (p) => ({
    title: `Check-in · ${p.label}`,
    fields: [
      { key: 'done', label: 'What you did', type: 'textarea', required: true, maxlength: 2000 },
      { key: 'hours', label: 'Hours spent', type: 'number', required: true },
      { key: 'confidence', label: 'Confidence', type: 'select', options: [1, 2, 3, 4, 5].map((n) => ({ value: String(n), label: `${n} / 5` })) },
      { key: 'blockers', label: 'Blockers', type: 'textarea', maxlength: 1000 },
      { key: 'nextPlan', label: 'Plan for the next period', type: 'textarea', maxlength: 1000 },
    ],
    initial: { confidence: '3' },
    submitLabel: 'Submit check-in',
    save: (v) => api.post(`/training/assignments/${a.value.id}/checkins`, { ...v, periodStart: p.key, confidence: Number(v.confidence ?? 3) }, { quiet: true }),
    done: 'Check-in submitted',
  }),
  review: (c) => ({
    title: `Review check-in · ${formatDate(c.periodStart)}`,
    intro: `${c.done} · ${c.hours} h${c.blockers ? ` · blockers: ${c.blockers}` : ''}`,
    fields: [
      { key: 'decision', label: 'Decision', type: 'select', required: true, options: [{ value: 'Verified', label: 'Verified' }, { value: 'Needs work', label: 'Needs work' }] },
      { key: 'comment', label: 'Comment for the trainee', type: 'textarea', maxlength: 1000 },
    ],
    initial: { decision: 'Verified' },
    submitLabel: 'Save review',
    save: (v) => api.post(`/training/checkins/${c.id}/review`, { ...v, version: c.version }, { quiet: true }),
    done: 'Check-in reviewed',
  }),
  assess: () => ({
    title: `Record assessment · ${a.value.planName}`,
    intro: `Pass mark ${a.value.passMark}% · ${a.value.assessmentType}`,
    fields: [
      { key: 'date', label: 'Date', type: 'date', required: true },
      { key: 'type', label: 'Type', maxlength: 60 },
      { key: 'score', label: 'Score %', type: 'number', required: true },
      { key: 'notes', label: 'Notes', type: 'textarea', required: true, maxlength: 2000 },
    ],
    initial: { type: a.value.assessmentType },
    submitLabel: 'Record assessment',
    save: (v) => api.post(`/training/assignments/${a.value.id}/assessment`, { ...v, version: a.value.version }, { quiet: true }),
    done: 'Assessment recorded',
  }),
  finalReview: (role) => ({
    title: `${role === 'manager' ? 'Manager review & decision' : 'Mentor review'} · ${a.value.personName}`,
    fields: [
      { key: 'rating', label: 'Rating (1–5)', type: 'number', required: true },
      { key: 'good', label: 'What went well', type: 'textarea', required: true, maxlength: 1000 },
      { key: 'improve', label: 'What to improve', type: 'textarea', required: true, maxlength: 1000 },
      ...(role === 'manager'
        ? [
            { key: 'decision', label: 'Decision', type: 'select', required: true, options: ['Completed', 'Extend', 'Not completed'].map((d) => ({ value: d, label: d })) },
            { key: 'reason', label: 'Reason (for Extend or Not completed)', type: 'textarea', maxlength: 1000 },
            { key: 'newEnd', label: 'New end date (Extend)', type: 'date' },
          ]
        : []),
    ],
    initial: role === 'manager' ? { decision: 'Completed' } : {},
    submitLabel: 'Save review',
    save: (v) => api.post(`/training/assignments/${a.value.id}/final-review`, { ...v, role, version: a.value.version }, { quiet: true }),
    done: 'Final review saved',
  }),
  signOff: () => ({
    title: `Sign off · ${a.value.planName}`,
    fields: [
      { key: 'agree', label: 'Your answer', type: 'select', required: true, options: [{ value: 'Agreed', label: 'I agree with the feedback' }, { value: 'Disagreed', label: 'I disagree' }] },
      { key: 'comment', label: 'Comment (required to disagree)', type: 'textarea', maxlength: 1000 },
      { key: 'planRating', label: 'Rate the plan (1–5)', type: 'number', required: true },
      { key: 'mentorRating', label: 'Rate your mentor (1–5)', type: 'number', required: true },
      { key: 'improve', label: 'What would make the plan better', type: 'textarea', maxlength: 1000 },
    ],
    initial: { agree: 'Agreed' },
    submitLabel: 'Sign off',
    save: (v) => api.post(`/training/assignments/${a.value.id}/sign-off`, { ...v, version: a.value.version }, { quiet: true }),
    done: 'Signed off',
  }),
  ack: (f) => ({
    title: `Acknowledge · ${f.label}`,
    intro: f.text,
    fields: [
      { key: 'agree', label: 'Your answer', type: 'select', required: true, options: [{ value: 'Agreed', label: 'Agree' }, { value: 'Disagreed', label: 'Disagree' }] },
      { key: 'comment', label: 'Comment (required to disagree)', type: 'textarea', maxlength: 1000 },
    ],
    initial: { agree: 'Agreed' },
    submitLabel: 'Acknowledge',
    save: (v) => api.post(`/training/assignments/${a.value.id}/acknowledgements`, { ...v, key: f.key, version: a.value.version }, { quiet: true }),
    done: 'Feedback acknowledged',
  }),
  reply: (f) => ({
    title: `Reply · ${f.label}`,
    intro: f.ack?.comment ?? '',
    fields: [{ key: 'text', label: 'Your reply', type: 'textarea', required: true, maxlength: 1000 }],
    submitLabel: 'Reply',
    save: (v) => api.post(`/training/assignments/${a.value.id}/acknowledgements/${encodeURIComponent(f.key)}/reply`, { ...v, version: a.value.version }, { quiet: true }),
    done: 'Reply sent',
  }),
  session: () => ({
    title: `Log mentoring session · ${a.value.personName}`,
    fields: [
      { key: 'heldOn', label: 'Date', type: 'date', required: true },
      { key: 'durationMin', label: 'Minutes', type: 'number', required: true },
      { key: 'mode', label: 'Mode', type: 'select', required: true, options: ['In person', 'Video call', 'Chat'].map((m) => ({ value: m, label: m })) },
      { key: 'topics', label: 'Topics', type: 'textarea', required: true, maxlength: 1000 },
      { key: 'nextSteps', label: 'Next steps', type: 'textarea', maxlength: 1000 },
      { key: 'blocker', label: 'The trainee is blocked (tells the manager)', type: 'checkbox' },
      { key: 'blockerNote', label: 'Blocker note', type: 'textarea', maxlength: 1000 },
    ],
    initial: { durationMin: '30', mode: 'In person' },
    submitLabel: 'Log session',
    save: (v) =>
      api.post(`/training/assignments/${a.value.id}/sessions`, { ...v, ...(openRequests.value[0] ? { requestId: openRequests.value[0].id } : {}) }, { quiet: true }),
    done: 'Session logged',
  }),
  ask: () => ({
    title: 'Ask your mentor for a session',
    fields: [{ key: 'note', label: 'What would you like to go over?', type: 'textarea', required: true, maxlength: 500 }],
    submitLabel: 'Send request',
    save: (v) => api.post(`/training/assignments/${a.value.id}/session-requests`, v, { quiet: true }),
    done: 'Request sent to your mentor',
  }),
};

const actionButtons = computed(() => {
  const acts = a.value.actions ?? [];
  const out = [];

  if (acts.includes('assess')) out.push({ key: 'assess', label: 'Record assessment', form: forms.assess() });
  if (acts.includes('mentorReview')) out.push({ key: 'mentorReview', label: 'Write mentor review', form: forms.finalReview('mentor') });
  if (acts.includes('managerReview')) out.push({ key: 'managerReview', label: 'Write manager review & decision', form: forms.finalReview('manager') });
  if (acts.includes('signOff')) out.push({ key: 'signOff', label: 'Review & sign off feedback', form: forms.signOff() });

  return out;
});

function openForm(f) {
  form.value = f;
}

async function onSaved() {
  showToast(form.value?.done ?? 'Saved');
  form.value = null;
  refreshNotifications();
  await load();
  emit('changed');
}

async function tick(m, done) {
  busy.value = true;

  try {
    await api.put(`/training/assignments/${a.value.id}/modules/${m.id}`, { done, version: a.value.version }, { quiet: true });
  } catch (e) {
    showToast(e.message);
  } finally {
    busy.value = false;
    await load();
  }
}

async function requestCheckpoint(day) {
  try {
    await api.post(`/training/assignments/${a.value.id}/checkpoints/${day}`, {}, { quiet: true });
    showToast(`Day ${day} checkpoint requested · self, manager and mentor get the review form`);
  } catch (e) {
    showToast(e.message);
  }
}

defineExpose({ load });
</script>
