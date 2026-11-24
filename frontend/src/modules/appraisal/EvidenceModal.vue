<template>
  <PlModal :open="open" :title="ev ? ev.row.name : 'Evidence'" size="lg" @close="emit('close')">
    <PlAlert v-if="error" badge="Error" tone="danger">{{ error }}</PlAlert>
    <PlLoading v-else-if="!ev" />
    <template v-else>
      <p class="pl-caption">
        {{ [ev.row.level, ev.row.title].filter(Boolean).join(' · ') }}
        <Badge :text="ev.row.recommendation.band?.name ?? '—'" :tone="ev.row.recommendation.band?.tone ?? ''" />
        <Badge :text="ev.row.proposal.status" :tone="statusTone(ev.row.proposal.status)" />
      </p>

      <div class="apr-kv">
        <div><span class="pl-eyebrow">Appraisal score</span><b>{{ score(ev.row.score) }}</b></div>
        <div><span class="pl-eyebrow">Recommended</span><b>{{ ev.row.recommendation.eligible ? pct(ev.row.recommendation.pct) : '—' }}</b></div>
        <div><span class="pl-eyebrow">Proposed</span><b>{{ pct(ev.row.proposal.pct) }}</b></div>
      </div>
      <PlAlert v-if="ev.row.proposal.sendBackReason && ev.row.proposal.status === 'Sent back'" badge="Sent back" tone="warn">
        {{ ev.row.proposal.sendBackReason }}
      </PlAlert>

      <h3 class="pl-eyebrow">Score breakdown</h3>
      <div v-for="p in ev.row.parts ?? []" :key="p.key" class="apr-bar">
        <span>{{ p.label }} <span class="pl-caption">{{ p.weight }}%</span></span>
        <span v-if="p.value === null" class="pl-caption">no data</span>
        <span v-else class="apr-meter" aria-hidden="true"><span :style="{ width: `${Math.round((p.value / 5) * 100)}%` }" /></span>
        <span class="pl-mono">{{ score(p.value) }}</span>
      </div>

      <h3 class="pl-eyebrow">Evidence for the year</h3>
      <div class="apr-tiles">
        <div v-for="t in tiles" :key="t.label" class="apr-tile">
          <div class="pl-eyebrow">{{ t.label }}</div>
          <b>{{ t.value }}</b>
        </div>
      </div>

      <template v-if="comments.length">
        <h3 class="pl-eyebrow">360° comments · {{ ev.comments360.cycle }}</h3>
        <div v-for="(c, i) in comments" :key="i" class="apr-tile">
          <div v-if="c.keep"><b>Keep:</b> {{ c.keep }}</div>
          <div v-if="c.start"><b>Start:</b> {{ c.start }}</div>
        </div>
      </template>

      <template v-if="canPropose && editableRow">
        <PlField
          v-model="form.pct"
          type="number"
          :label="`Proposed increment % (0–${rules?.max ?? 20}${band ? `, band ${band.lo}–${band.hi}%` : ''})`"
          :error="fieldError.pct"
        />
        <PlField v-model="form.reason" type="textarea" label="Reason (required outside the band)" :maxlength="1000" :error="fieldError.reason" />
        <label class="row">
          <input v-model="form.promotion" type="checkbox" />
          Recommend promotion to the next level
        </label>
      </template>
      <PlField
        v-if="isApprover && ev.row.proposal.status === 'Submitted'"
        v-model="sendBackReason"
        type="textarea"
        label="Reason to send back (for the manager)"
        :maxlength="1000"
        :error="fieldError.sendBack"
      />
    </template>

    <template #actions>
      <button type="button" class="pl-btn" @click="emit('close')">Close</button>
      <template v-if="ev && isApprover && ev.row.proposal.status === 'Submitted'">
        <button type="button" class="pl-btn" :disabled="busy" @click="sendBack">Send back</button>
        <button type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="approve">Approve</button>
      </template>
      <button v-else-if="ev && canPropose && editableRow" type="button" class="pl-btn pl-btn-primary" :disabled="busy" @click="save">
        Save
      </button>
    </template>
  </PlModal>
</template>

<script setup>
// Evidence panel (reference aprDetail; spec appraisal/appraisal "Evidence panel"): GET
// /appraisal/people/:id/evidence. 360° comments come grouped and without any reviewer identity; evidence a
// track has not registered yet shows "No data yet". Managers save the proposal here (a reason is required
// outside the band); HR approves or sends back with a reason.
import { computed, reactive, ref, watch } from 'vue';
import { api } from '@/api/client.js';
import { Badge } from '@/kit/index.js';
import { PlAlert, PlField, PlLoading, PlModal, showToast } from '@/ui/index.js';
import { editable, pct, score, statusTone } from './presentation.js';

const props = defineProps({
  open: { type: Boolean, default: false },
  personId: { type: String, default: '' },
  rules: { type: Object, default: null },
  canPropose: { type: Boolean, default: false },
  isApprover: { type: Boolean, default: false },
});

const emit = defineEmits(['close', 'changed']);

const ev = ref(null);
const error = ref('');
const busy = ref(false);
const sendBackReason = ref('');
const form = reactive({ pct: '', reason: '', promotion: false });
const fieldError = reactive({ pct: '', reason: '', sendBack: '' });

async function load() {
  ev.value = null;
  error.value = '';
  sendBackReason.value = '';
  Object.assign(fieldError, { pct: '', reason: '', sendBack: '' });

  try {
    ev.value = await api.get(`/appraisal/people/${props.personId}/evidence`);

    Object.assign(form, {
      pct: String(ev.value.row.proposal.pct),
      reason: ev.value.row.proposal.reason ?? '',
      promotion: !!ev.value.row.proposal.promotion,
    });
  } catch (e) {
    error.value = e.message || 'Could not load the evidence.';
  }
}

watch(
  () => [props.open, props.personId],
  ([open]) => {
    if (open && props.personId) {
      load();
    }
  },
  { immediate: true },
);

const band = computed(() => (ev.value?.row.recommendation.eligible ? ev.value.row.recommendation.band : null));

const editableRow = computed(() => ev.value && editable(ev.value.row) && ev.value.row.recommendation.eligible && ev.value.row.ctc !== null);

function show(v, render) {
  return v === null || v === undefined ? 'No data yet' : render(v);
}

const tiles = computed(() => {
  const e = ev.value.evidence;
  const q = ev.value.quarterlyIndex ?? [];

  return [
    { label: 'Quarterly index', value: q.length ? q.map((v) => (v === null || typeof v === 'object' ? '—' : Number(v).toFixed(1))).join(' → ') : 'No data yet' },
    { label: 'OKR pillar', value: show(e.okrPillar, (v) => (typeof v === 'number' ? `${v.toFixed(1)} / 5` : '—')) },
    { label: 'Culture values', value: show(e.culture, (v) => (typeof v?.average === 'number' ? `${v.average.toFixed(2)} / 5` : 'Calculated')) },
    { label: 'Extra-mile points', value: show(e.extraMile, (v) => (Array.isArray(v) ? `${v.length} items` : '—')) },
    { label: 'Bugs and support', value: show(e.bugs, () => 'See Bugs & support') },
    { label: 'Pull requests', value: show(e.pullRequests, () => 'See Pull requests') },
    { label: 'Customer visits (year)', value: show(e.visits, (v) => `${v.count ?? v.visits ?? 0} visits`) },
    { label: 'Training rating', value: show(e.training, (v) => (typeof v.score === 'number' ? `${v.score.toFixed(1)} / 5${v.final ? '' : ' (provisional)'}` : 'Not rated')) },
  ];
});

const comments = computed(() => {
  const groups = ev.value?.comments360?.groups ?? {};

  return Object.values(groups).flat().slice(0, 3);
});

async function save() {
  busy.value = true;
  Object.assign(fieldError, { pct: '', reason: '' });

  try {
    await api.put(
      `/appraisal/proposals/${props.personId}`,
      { pct: Number(form.pct), reason: form.reason, promotion: form.promotion },
      { quiet: true },
    );

    showToast(`Saved ${ev.value.row.name} · ${pct(form.pct)}`);
    emit('changed');
    emit('close');
  } catch (e) {
    if (e.field === 'pct' || e.field === 'reason') {
      fieldError[e.field] = e.message;
    } else {
      showToast(e.message);
    }
  } finally {
    busy.value = false;
  }
}

async function decide(path, body, done) {
  busy.value = true;

  try {
    await api.post(`/appraisal/proposals/${props.personId}/${path}`, body, { quiet: true });

    showToast(done);
    emit('changed');
    emit('close');
  } catch (e) {
    if (path === 'send-back' && e.field === 'reason') {
      fieldError.sendBack = e.message;
    } else {
      showToast(e.message);
    }
  } finally {
    busy.value = false;
  }
}

function approve() {
  decide('approve', {}, `Approved · ${ev.value.row.name}`);
}

function sendBack() {
  decide('send-back', { reason: sendBackReason.value }, 'Sent back to the manager');
}
</script>
