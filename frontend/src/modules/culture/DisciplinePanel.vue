<template>
  <PlCard :title="`Discipline · ${weight ?? '—'}% weight`" subtitle="Data only: base from daily update compliance, adjusted by rules b–h">
    <template #actions><Badge :text="`${fixed(discipline?.score)} / 5`" :tone="scoreTone(discipline?.score, tones)" /></template>

    <PlEmpty v-if="!m" text="No Jira history yet — Discipline shows 3.0 until 4 weeks of data exist." />
    <template v-else>
      <PlAlert v-if="!m.enoughData" badge="Info">
        {{ m.weeks }} week{{ m.weeks === 1 ? '' : 's' }} of data so far — Discipline shows 3.0 until 4 weeks of data exist.
      </PlAlert>
      <PlKpiRow :columns="4" label="Discipline">
        <StatTile v-for="k in kpis" :key="k.label" :eyebrow="k.label" :value="k.value" :foot="k.caption" />
      </PlKpiRow>

      <h3 class="pl-h3 cv-h3">Daily updates · last 4 weeks (leave and holidays excluded)</h3>
      <ul class="cv-days" aria-label="Daily updates">
        <li v-for="day in m.days" :key="day[0]" :class="day[1] ? 'u2' : 'u5'" class="pl-heat-cell">
          <span>{{ formatDate(day[0]) }}</span>
          <b :aria-label="day[1] ? 'updated' : 'no meaningful update'">{{ day[1] ? '✓' : '✗' }}</b>
        </li>
      </ul>

      <PlTable :columns="RULE_COLUMNS" :rows="ruleRows" row-key="code" label="Discipline rules a–h">
        <template #cell-measure="{ row }">
          <b>{{ row.measure }}</b>
          <div class="pl-caption">{{ row.how }}</div>
        </template>
        <template #cell-state="{ row }">
          <Badge :text="row.ok === true ? 'Good' : row.ok === false ? 'Improve' : 'Info'" :tone="row.ok === true ? 'ok' : row.ok === false ? 'accent' : ''" />
        </template>
      </PlTable>

      <h3 class="pl-h3 cv-h3">How the score was calculated</h3>
      <p class="cv-note">
        Base {{ m.base }} (compliance {{ m.compliance }}%)<template v-for="a in m.adjustments ?? []" :key="a.rule"> · {{ a.rule }} {{ a.label }} {{ a.points }} ({{ a.detail }})</template
        ><template v-if="!m.adjustments?.length"> · no adjustments</template> → <b>{{ fixed(m.auto, 2) }}</b>. Adjustments never move the score more than 1
        below the base.
      </p>
    </template>

    <p v-if="override" class="cv-note" role="status">
      Manager override to <b>{{ fixed(override.score, 2) }}</b> by {{ override.setBy }} · {{ formatDate(override.setAt) }}: {{ override.reason }}
    </p>

    <div v-if="canOverride" class="cv-actions">
      <button type="button" class="pl-btn" @click="emit('override')">{{ override ? 'Change override' : 'Override score' }}</button>
      <button v-if="override" type="button" class="pl-btn" @click="emit('clear')">Use auto score</button>
    </div>
  </PlCard>
</template>

<script setup>
// Discipline (spec culture/culture-values "Discipline rules" and "Discipline override"): the KPIs, the daily
// update days, rules a–h with their values and the calculation; the override buttons only for
// reviews.calibrate holders looking at someone else (the server refuses your own with 422).
import { computed } from 'vue';
import { Badge, StatTile } from '@/kit/index.js';
import { formatDate } from '@/ui/format.js';
import { PlAlert, PlCard, PlEmpty, PlKpiRow, PlTable } from '@/ui/index.js';
import { fixed, scoreTone } from './presentation.js';

const props = defineProps({
  discipline: { type: Object, default: null },
  weight: { type: Number, default: null },
  canOverride: { type: Boolean, default: false },
  personName: { type: String, default: '' },
  // score badge bands from the server (core.thresholds tones.score)
  tones: { type: Array, default: () => [] },
});

const emit = defineEmits(['override', 'clear']);

const RULE_COLUMNS = [
  { key: 'code', label: '' },
  { key: 'measure', label: 'Measure' },
  { key: 'value', label: 'Value', num: true },
  { key: 'target', label: 'Target' },
  { key: 'state', label: 'Status' },
];

const m = computed(() => props.discipline?.metrics ?? null);
const override = computed(() => props.discipline?.override ?? null);

const kpis = computed(() => {
  const x = m.value;
  const adj = (x.adjustments ?? []).reduce((sum, a) => sum + a.points, 0);

  return [
    { label: 'Daily update compliance', value: `${x.compliance}%`, caption: `${x.updated} of ${x.days.length} working days` },
    { label: 'Base rating', value: `${x.base} / 5`, caption: 'from the compliance band' },
    { label: 'Adjustments', value: adj.toFixed(2), caption: `${(x.adjustments ?? []).length} from b–h` },
    {
      label: 'Discipline score',
      value: `${fixed(props.discipline.score)} / 5`,
      caption: props.discipline.source === 'override' ? 'Manager override' : 'Automatic from Jira',
    },
  ];
});

const ruleRows = computed(() => {
  const x = m.value;
  const att = x.attendance ?? {};
  // each rule's target from the server (core.discipline metrics.targets; a from rules d4/d5)
  const t = x.targets ?? {};

  return [
    { code: 'a', measure: 'Daily update compliance', how: 'Days with a meaningful update on active tickets ÷ working days with active tickets', value: `${x.compliance}%`, target: `≥ ${t.compliance}% (5 at ≥ ${t.complianceTop}%)`, ok: x.compliance >= t.compliance },
    { code: 'b', measure: 'Stale tickets', how: 'Assigned tickets with no recent update', value: String(x.stale), target: '0', ok: x.stale === 0 },
    { code: 'c', measure: 'Proactive re-planning', how: `${x.replanN?.[0] ?? 0} due-date changes before the due date, ${x.replanN?.[1] ?? 0} after it had passed`, value: `${x.replan}%`, target: `≥ ${t.replan}%`, ok: x.replan >= t.replan },
    { code: 'd', measure: 'Status accuracy', how: `${x.statusN?.[0] ?? 0} of ${x.statusN?.[1] ?? 0} tickets had a status not matching reality`, value: `${x.status}%`, target: `≥ ${t.status}%`, ok: x.status >= t.status },
    { code: 'e', measure: 'Time logging accuracy', how: `${x.bulk} bulk entries (3+ days logged at once)`, value: `${x.logSame}% same-day`, target: `≥ ${t.logSame}%, < ${t.bulkBelow} bulk`, ok: x.logSame >= t.logSame && x.bulk < t.bulkBelow },
    { code: 'f', measure: 'Early blocker reporting', how: `${x.blockN?.[0] ?? 0} of ${x.blockN?.[1] ?? 0} blockers flagged within 1 working day`, value: `${x.block}%`, target: `≥ ${t.block}%`, ok: x.block >= t.block },
    { code: 'g', measure: 'Process adherence', how: 'Definition of done, PR standard and stand-up attendance', value: `${x.process}%`, target: `≥ ${t.process}%`, ok: x.process >= t.process },
    {
      code: 'h',
      measure: 'Attendance & leave',
      how: `Monthly HR upload${att.absent ? ` · ${att.absent} absent without leave` : ''} · sick and approved leave never count`,
      value: att.score == null ? '—' : `${att.score}%`,
      target: `≥ ${t.attendance}%, no absence`,
      ok: att.score == null ? null : att.score >= t.attendance && !att.absent,
    },
  ];
});
</script>
