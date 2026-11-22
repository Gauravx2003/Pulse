<template>
  <div class="att-team" role="table" :aria-label="`Day by day · ${label}`">
    <div class="att-row" role="row" :style="cols">
      <span role="columnheader" class="att-nm"><span class="visually-hidden">Person</span></span>
      <span v-for="d in days" :key="d.date" role="columnheader" class="att-dn" :title="formatDate(d.date)">
        {{ Number(d.date.slice(8)) }}
      </span>
    </div>
    <div v-for="r in rows" :key="r.personId" role="row" class="att-row" :style="cols">
      <span role="rowheader" class="att-nm">
        <button type="button" class="att-link" :title="r.name" @click="emit('open-person', r.personId)">
          {{ r.short }}
        </button>
      </span>
      <span v-for="c in r.cells" :key="c.date" role="cell" :class="['att-cell', c.cls]" :title="c.title" :aria-label="c.title">
        <span class="att-code" aria-hidden="true">{{ c.code }}</span>
      </span>
    </div>
  </div>
</template>

<script setup>
// Team "Day by day" grid (reference attTeam): working days of the period up to today, one row per
// person, codes from GET /attendance/team grid.rows[].cells. At phone width the cells keep their
// colour and drop the letter, so the grid always fits the card.
import { computed } from 'vue';
import { formatDate } from '@/ui/format.js';
import { CELLS } from './presentation.js';

const props = defineProps({
  /** GET /attendance/team grid: { days: [{ date, weekend, holiday }], rows: [{ personId, cells }] } */
  grid: { type: Object, required: true },
  /** personId -> name */
  names: { type: Object, required: true },
  /** Last day to show (today for a live period). */
  until: { type: String, required: true },
  label: { type: String, default: '' },
});

const emit = defineEmits(['open-person']);

const days = computed(() => props.grid.days.filter((d) => !d.weekend && d.date <= props.until));

const cols = computed(() => ({
  gridTemplateColumns: `minmax(56px, 130px) repeat(${days.value.length}, minmax(0, 1fr))`,
}));

const rows = computed(() =>
  props.grid.rows.map((r) => {
    const name = props.names[r.personId] ?? '—';

    return {
      personId: r.personId,
      name,
      short: name.split(' ')[0],
      cells: days.value.map((d) => {
        const code = r.cells[d.date] ?? '';
        const cell = CELLS[code] ?? CELLS[''];

        return {
          date: d.date,
          cls: cell.cls,
          code: cell.code,
          title: `${name} · ${formatDate(d.date)} · ${cell.label}`,
        };
      }),
    };
  }),
);
</script>

<style scoped>
.att-team {
  display: grid;
  gap: 3px;
}
.att-row {
  display: grid;
  align-items: center;
  gap: 3px;
}
.att-nm {
  min-width: 0;
  overflow: hidden;
  font-size: 13px;
  white-space: nowrap;
  text-overflow: ellipsis;
}
.att-link {
  max-width: 100%;
  padding: 0;
  overflow: hidden;
  border: 0;
  background: none;
  color: var(--ink);
  font: inherit;
  text-align: left;
  text-overflow: ellipsis;
  cursor: pointer;
}
.att-link:hover {
  color: var(--brand-ink);
}
.att-dn {
  overflow: hidden;
  color: var(--ink-muted);
  font: 400 10px/1 var(--font-mono);
  text-align: center;
}
.visually-hidden {
  position: absolute;
  width: 1px;
  height: 1px;
  overflow: hidden;
  clip: rect(0 0 0 0);
}
@media (max-width: 600px) {
  .att-team,
  .att-row {
    gap: 1px;
  }
  .att-team .att-cell {
    height: 20px;
  }
  .att-code,
  .att-dn {
    font-size: 0;
  }
}
</style>
