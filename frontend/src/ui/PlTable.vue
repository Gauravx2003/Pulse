<template>
  <PlLoading v-if="loading" />
  <PlEmpty v-else-if="!rows.length" :text="emptyText">
    <slot name="empty" />
  </PlEmpty>
  <div v-else class="pl-tbl-wrap" role="region" :aria-label="label" tabindex="0">
    <table class="pl-table">
      <caption class="pl-sr-only">{{ label }}</caption>
      <thead>
        <tr>
          <th v-for="col in columns" :key="col.key" scope="col" :class="{ num: col.num }">{{ col.label }}</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(row, i) in rows"
          :key="rowKey ? row[rowKey] : i"
          :class="{ 'is-clickable': clickable, 'is-flagged': flagged?.(row) }"
          :tabindex="clickable ? 0 : undefined"
          @click="open(row)"
          @keydown="onKey($event, row)"
        >
          <td v-for="col in columns" :key="col.key" :class="{ num: col.num }">
            <slot :name="`cell-${col.key}`" :row="row" :value="row[col.key]">{{ display(row[col.key]) }}</slot>
          </td>
        </tr>
      </tbody>
    </table>
  </div>
</template>

<script setup>
// Scrolling table (reference section 3, "Table"): scrolls inside its own container so the page never
// scrolls sideways; numbers right-aligned in mono; clickable rows open on click or Enter. A missing
// value shows "—", never null or undefined.
import PlEmpty from './PlEmpty.vue';
import PlLoading from './PlLoading.vue';

const props = defineProps({
  /** [{ key, label, num? }] */
  columns: { type: Array, required: true },
  rows: { type: Array, default: () => [] },
  /** Accessible name of the table and its scroll region. */
  label: { type: String, required: true },
  rowKey: { type: String, default: '' },
  clickable: { type: Boolean, default: false },
  flagged: { type: Function, default: null },
  loading: { type: Boolean, default: false },
  emptyText: { type: String, default: 'No rows to show.' },
});

const emit = defineEmits(['row-click']);

function open(row) {
  if (props.clickable) {
    emit('row-click', row);
  }
}

function onKey(event, row) {
  if (event.key === 'Enter' && event.target === event.currentTarget) {
    event.preventDefault();
    open(row);
  }
}

function display(value) {
  if (value === null || value === undefined || value === '' || (typeof value === 'number' && !Number.isFinite(value))) {
    return '—';
  }

  return value;
}
</script>
