<template>
  <div class="att-cal">
    <div class="att-cal-h" aria-hidden="true">
      <span v-for="d in HEAD" :key="d">{{ d }}</span>
    </div>
    <ol class="att-cal-g" :aria-label="`Calendar · ${label}`">
      <li
        v-for="c in cells"
        :key="c.date"
        :class="['att-cell', 'att-day', c.cls, { 'att-late': c.late, 'att-out': !c.inPeriod }]"
        :title="c.title"
        :aria-label="c.inPeriod ? c.title : undefined"
        :aria-hidden="c.inPeriod ? undefined : 'true'"
      >
        <small>{{ Number(c.date.slice(8)) }}</small>
        <span aria-hidden="true">{{ c.code }}</span>
      </li>
    </ol>
  </div>
</template>

<script setup>
// Person calendar (reference attPerson "Calendar"): Monday-first weeks covering the period. A day
// shows the HR record, a Pulse customer visit as on duty, a red dot for a late check-in (only when
// the viewer may see check-ins), "?" for a working day without a row and nothing on weekends and
// holidays.
import { computed } from 'vue';
import { formatDate } from '@/ui/format.js';
import { CELLS, LEAVE_CLASS, addDays, daysBetween, weekday } from './presentation.js';

const HEAD = ['Mon', 'Tue', 'Wed', 'Thu', 'Fri', 'Sat', 'Sun'];

const props = defineProps({
  /** { from, to } */
  period: { type: Object, required: true },
  /** GET /attendance/people/:id calendar: [{ day, status, leaveType, appliedOn, checkIn?, checkOut? }] */
  calendar: { type: Array, default: () => [] },
  /** [{ from, to }] customer visits */
  visits: { type: Array, default: () => [] },
  /** stats: leaves, lateDays, noRecord, uploaded */
  stats: { type: Object, required: true },
  label: { type: String, default: '' },
});

const byDay = computed(() => Object.fromEntries(props.calendar.map((r) => [r.day, r])));
const leaveByDay = computed(() => Object.fromEntries((props.stats.leaves ?? []).map((l) => [l.date, l])));
const lateByDay = computed(() => Object.fromEntries((props.stats.lateDays ?? []).map(([d, t]) => [d, t])));
const noRecord = computed(() => new Set(props.stats.noRecord ?? []));
const uploaded = computed(() => new Set(props.stats.uploaded ?? []));

function onVisit(day) {
  return props.visits.some((v) => v.from <= day && day <= v.to);
}

function cellFor(day) {
  const r = byDay.value[day];
  const status = r?.status;

  if (onVisit(day) && !['L', 'S', 'A'].includes(status)) {
    return { ...CELLS.D, extra: '' };
  }

  if (status === 'L') {
    const leave = leaveByDay.value[day];
    const cls = LEAVE_CLASS[leave?.class];
    const base = CELLS[cls?.cell ?? 'L'];

    return {
      ...base,
      label: r.leaveType ? `${r.leaveType} · ${cls?.label ?? 'Leave'}` : base.label,
      extra: '',
    };
  }

  if (status && CELLS[status]) {
    const times = r.checkIn ? ` · ${r.checkIn}–${r.checkOut ?? '—'}` : '';
    return {
      ...CELLS[status],
      label: r.leaveType && status !== 'O' && status !== 'W' ? `${CELLS[status].label} · ${r.leaveType}` : CELLS[status].label,
      extra: times,
    };
  }

  if (noRecord.value.has(day)) {
    return { ...CELLS[''], extra: '' };
  }

  if (!uploaded.value.has(day.slice(0, 7)) && ![0, 6].includes(weekday(day))) {
    return { ...CELLS.x, extra: '' };
  }

  return {
    cls: 'att-off',
    code: '',
    label: [0, 6].includes(weekday(day)) ? 'Weekend' : 'Holiday',
    extra: '',
  };
}

const cells = computed(() => {
  const back = (weekday(props.period.from) + 6) % 7;
  const ahead = (7 - weekday(props.period.to)) % 7;
  const from = addDays(props.period.from, -back);
  const to = addDays(props.period.to, ahead);

  return daysBetween(from, to).map((date) => {
    const inPeriod = date >= props.period.from && date <= props.period.to;

    if (!inPeriod) {
      return {
        date,
        inPeriod,
        cls: 'att-off',
        code: '',
        title: '',
        late: false,
      };
    }

    const c = cellFor(date);
    const late = lateByDay.value[date];

    return {
      date,
      inPeriod,
      cls: c.cls,
      code: c.code,
      late: Boolean(late),
      title: `${formatDate(date)} · ${c.label}${c.extra}${late ? ` · late check-in ${late}` : ''}`,
    };
  });
});
</script>

<style scoped>
.att-cal-h,
.att-cal-g {
  display: grid;
  grid-template-columns: repeat(7, minmax(0, 1fr));
  gap: 4px;
}
.att-cal-g {
  margin: 0;
  padding: 0;
  list-style: none;
}
.att-cal-h span {
  padding-bottom: 6px;
  color: var(--ink-muted);
  font: 600 11px/1 var(--font-sans);
  text-align: center;
}
.att-day {
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 2px;
  height: 46px;
  font-size: 13px;
}
.att-day small {
  font: 400 10px/1 var(--font-mono);
  opacity: 0.8;
}
</style>
